package newhope.vertebra.hil.i2s

import I2sPattern.{Link, U32}

// =====================================================================
//  Referencyjny checker wzorca (contract/i2s/pattern.md, "Checker").
//
//  Ten sam algorytm realizuja I2sPatternCheck (Spinal, etap 2) i
//  hil_pattern (C, etap 3); oba sprawdzaja sie na checker_*.csv
//  wygenerowanych z tego modelu. Host (etap 4) uzywa go do dekodowania
//  dumpu przy bledzie.
//
//  Maszyna stanow jest celowo mala, bo ma ja powtorzyc RTL:
//    Hunt     - czekamy na ramke zgodna z wlasnym polem seq
//    Confirm  - 1 albo 2 kolejne ramki z rzedu; trzecia daje lock
//    Locked   - oczekiwana ramka n; cisza = luka, nie zuzywa numeru
// =====================================================================

case class CheckerErr(n : Long, got : I2sWords, exp : I2sWords) {
  override def toString : String = f"n=$n got=$got exp=$exp"
}

case class CheckerStat(frames   : Long,
                       bad      : Long,
                       gaps     : Long,
                       relocks  : Long,
                       lockAt   : Long,               // -1 = nie bylo locka
                       firstErr : Option[CheckerErr]) {
  def locked : Boolean = lockAt >= 0
  override def toString : String =
    s"frames=$frames bad=$bad gaps=$gaps relocks=$relocks lock_at=$lockAt " +
    s"first_err=${firstErr.fold("-")(_.toString)}"
}

object I2sCheckerModel {
  /** Ramki potrzebne do locka: pierwsza + 2 potwierdzenia. */
  val LockFrames = 3

  private sealed trait Phase
  private case object Hunt extends Phase
  private case class Confirm(next : Long, seen : Int, start : Long) extends Phase
  private case class Locked(next : Long) extends Phase

  def run(link : Link, frames : Seq[I2sWords]) : CheckerStat = {
    val c = new I2sCheckerModel(link)
    frames.foreach(c.push)
    c.stat
  }
}

class I2sCheckerModel(val link : Link) {
  import I2sCheckerModel._
  require(link.checkable,
    s"${link.label}: odbiorca widzi ${link.effBits} bitow, a potrzebuje ${1 + link.s} (kanal + seq)")

  private val seqMod = 1L << link.s

  private var phase : Phase = Hunt
  private var idx      = 0L                 // wszystkie ramki, lacznie z cisza
  private var frames   = 0L
  private var bad      = 0L
  private var gaps     = 0L
  private var relocks  = 0L
  private var lockAt   = -1L
  private var firstErr : Option[CheckerErr] = None

  def stat : CheckerStat = CheckerStat(frames, bad, gaps, relocks, lockAt, firstErr)

  /** Hunt dla ramki f: jesli jest ramka wzorca, zaczyna potwierdzanie. */
  private def hunt(f : I2sWords) : Phase =
    if (link.isPatternFrame(f)) Confirm((link.seqOf(f.l) + 1) & U32, 1, idx)
    else Hunt

  def push(f : I2sWords) : Unit = {
    phase = phase match {
      case Hunt =>
        if (f.isSilence) Hunt else hunt(f)

      case p @ Confirm(next, seen, start) =>
        if (f.isSilence) p                                  // luka przed lockiem: bez znaczenia
        else if (f == link.expected(next)) {
          if (seen + 1 == LockFrames) {
            frames += LockFrames
            lockAt  = start
            Locked((next + 1) & U32)
          } else Confirm((next + 1) & U32, seen + 1, start)
        }
        else hunt(f)                                        // ciag przerwany: ta ramka moze zaczac nowy

      case Locked(next) =>
        if (f.isSilence) { gaps += 1; Locked(next) }
        else {
          val exp = link.expected(next)
          if (f == exp) { frames += 1; Locked((next + 1) & U32) }
          else {
            bad += 1
            if (firstErr.isEmpty) firstErr = Some(CheckerErr(next, f, exp))
            if (link.isPatternFrame(f)) {
              // Poprawna ramka z innym numerem: zgubiona albo zdublowana.
              // Numer przeskakuje zawsze do przodu o d = roznica seq mod 2^S,
              // wiec liczy sie tylko n mod 2^S - n jest etykieta checkera.
              relocks += 1
              val d = (link.seqOf(f.l) - next) & (seqMod - 1)
              Locked((next + d + 1) & U32)
            }
            else Locked((next + 1) & U32)                    // przeklamana ramka zuzywa numer
          }
        }
    }
    idx += 1
  }
}
