package newhope.vertebra.hil.i2s

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import newhope.i2s.I2sFormat
import I2sPattern.{Link, U32}

// =====================================================================
//  Wektory testowe kontraktu (contract/i2s/vectors/*.csv).
//
//  Czytaja je: I2sContractTestplan (swiezosc - pliki w repo == ten kod),
//  test generatora i checkera w Spinalu (etap 2) i `selftest` na ESP32
//  (etap 3, EMBED_FILES). Format opisuje contract/i2s/pattern.md, "Wektory".
//
//  Regeneracja (katalog roboczy forka = vertebra-hil/fpga):
//    sbt "hilFpga/runMain newhope.vertebra.hil.i2s.I2sVectors"
//
//  Wszystko jest deterministyczne i bez java.util.Random: "smieci" pochodza
//  z xorshift32, zeby kazda implementacja mogla je odtworzyc, gdyby
//  kiedys trzeba bylo generowac strumienie zamiast je czytac.
// =====================================================================
object I2sVectors {

  val defaultDir = "../contract/i2s/vectors"

  def hex(v : Long) : String = f"${v & U32}%08x"
  def mask(w : Int) : Long   = (1L << w) - 1

  // -------------------------------------------------------------------
  //  pattern.csv : seed, n, c, w, word
  // -------------------------------------------------------------------
  val patternSeeds  : Seq[Long] = Seq(0L, 0x2aL, 0xdeadbeefL, 0xffffffffL)
  val patternWidths : Seq[Int]  = Seq(8, 16, 24, 32)

  /** n: poczatek, zawiniecie pola seq, zawiniecie 8 bitow, granice uint32. */
  def patternNs(w : Int) : Seq[Long] = {
    val sm = 1L << I2sPattern.seqBits(w)
    Seq(0L, 1L, 2L, sm - 1, sm, sm + 1, 255L, 256L, 257L, 1000L,
        0x7fffffffL, 0x80000000L, 0xffffffffL).distinct.sorted
  }

  def patternCsv : String = {
    val rows = for {
      seed <- patternSeeds
      w    <- patternWidths
      n    <- patternNs(w)
      c    <- Seq(0, 1)
    } yield s"${hex(seed)},$n,$c,$w,${hex(I2sPattern.word(seed, n, c, w))}"
    ("seed,n,c,w,word" +: rows).mkString("", "\n", "\n")
  }

  // -------------------------------------------------------------------
  //  transfer.csv : word, wtx, slot, wrx, result
  //  slot 8 < wtx: slowo obciete juz na magistrali; slot 40: padding.
  // -------------------------------------------------------------------
  val transferSlots : Seq[Int] = Seq(8, 16, 24, 32, 40)

  def transferWords(wtx : Int) : Seq[Long] = Seq(
    I2sPattern.word(0x2aL, 5, 0, wtx),
    I2sPattern.word(0x2aL, 5, 1, wtx),
    mask(wtx),
    0x55555555L & mask(wtx))

  def transferCsv : String = {
    val rows = for {
      wtx  <- patternWidths
      v    <- transferWords(wtx)
      slot <- transferSlots
      wrx  <- patternWidths
    } yield s"${hex(v)},$wtx,$slot,$wrx,${hex(I2sFormat.transfer(v, wtx, slot, wrx))}"
    ("word,wtx,slot,wrx,result" +: rows).mkString("", "\n", "\n")
  }

  // -------------------------------------------------------------------
  //  Strumienie dla checkera
  // -------------------------------------------------------------------
  val silence = I2sWords(0, 0)

  def clean(link : Link, n0 : Long, count : Int) : Seq[I2sWords] =
    (0 until count).map(i => link.expected((n0 + i) & U32))

  /** Deterministyczne smieci o szerokosci odbiorcy. */
  def garbage(link : Link, salt : Int, count : Int) : Seq[I2sWords] =
    (0 until count).map { i =>
      def g(k : Int) = I2sPattern.xorshift32(((salt * 1000L + 2 * i + k + 1) * 0x9e3779b9L) & U32)
      I2sWords(g(0) & mask(link.wrx), g(1) & mask(link.wrx))
    }

  /** Surowe bity magistrali: kolejne sloty L, R, pozycja 0 = MSB slowa.
    * Niezalezna, bitowa droga do tego samego, co liczy transfer. */
  def serialize(raw : Seq[I2sWords], wtx : Int, slot : Int) : IndexedSeq[Boolean] =
    raw.flatMap { f =>
      Seq(f.l, f.r).flatMap { v =>
        (0 until slot).map(p => p < wtx && ((v >> (wtx - 1 - p)) & 1L) == 1L)
      }
    }.toIndexedSeq

  /** Odbiorca czytajacy slot z przesunieciem `offset` bitow (0 = poprawnie,
    * 1 = o bit za pozno). Poza strumieniem zera. */
  def deserialize(bits : IndexedSeq[Boolean], frames : Int, slot : Int, wrx : Int,
                  offset : Int) : Seq[I2sWords] = {
    def word(k : Int) : Long = (0 until wrx).foldLeft(0L) { (acc, p) =>
      val pos = k * slot + offset + p
      val b   = p < slot && pos >= 0 && pos < bits.size && bits(pos)
      (acc << 1) | (if (b) 1L else 0L)
    }
    (0 until frames).map(i => I2sWords(word(2 * i), word(2 * i + 1)))
  }

  case class CheckerCase(name : String, link : Link, frames : Seq[I2sWords]) {
    lazy val stat : CheckerStat = I2sCheckerModel.run(link, frames)
  }

  val linkA = Link(0x2aL, 16, 32, 16)

  lazy val checkerCases : Seq[CheckerCase] = {
    val a40 = clean(linkA, 0, 40)
    val bitshift = {
      val raw  = (0 until 40).map(n => I2sPattern.frame(linkA.seed, n.toLong, linkA.wtx))
      val bits = serialize(raw, linkA.wtx, linkA.slot)
      deserialize(bits, 40, linkA.slot, linkA.wrx, 0).take(20) ++
      deserialize(bits, 40, linkA.slot, linkA.wrx, 1).drop(20)
    }
    val w8 = Link(0x5a5aL, 8, 16, 8)
    Seq(
      CheckerCase("clean", linkA, a40),
      CheckerCase("prelock", linkA, {
        val c = clean(linkA, 517, 40)
        Seq.fill(3)(silence) ++ garbage(linkA, 1, 2) ++ Seq(I2sWords(linkA.expected(516).l, 0)) ++
        c.take(1) ++ Seq(silence) ++ c.drop(1)
      }),
      CheckerCase("gaps", linkA, {
        val c = clean(linkA, 100, 40)
        c.take(10) ++ Seq(silence) ++ c.slice(10, 25) ++ Seq(silence, silence) ++ c.drop(25)
      }),
      CheckerCase("drop", linkA, { val c = clean(linkA, 0, 41); c.take(20) ++ c.drop(21) }),
      CheckerCase("dup",  linkA, a40.take(21) ++ Seq(a40(20)) ++ a40.drop(21)),
      CheckerCase("swap", linkA, a40.updated(20, a40(20).swap)),
      CheckerCase("bitflip", linkA, a40.updated(20, I2sWords(a40(20).l, a40(20).r ^ 1L))),
      CheckerCase("bitshift", linkA, bitshift),
      CheckerCase("wrap", Link(0xdeadbeefL, 24, 32, 24), clean(Link(0xdeadbeefL, 24, 32, 24), 0xfffffff0L, 40)),
      CheckerCase("trunc", Link(0x2aL, 24, 32, 16), clean(Link(0x2aL, 24, 32, 16), 0, 40)),
      CheckerCase("ext",   Link(0x2aL, 16, 32, 24), clean(Link(0x2aL, 16, 32, 24), 0, 40)),
      CheckerCase("slot_trunc", Link(0x2aL, 32, 16, 32), clean(Link(0x2aL, 32, 16, 32), 0, 40)),
      CheckerCase("w8_drop", w8, { val c = clean(w8, 0, 31); c.take(12) ++ c.drop(13) }),
      CheckerCase("nolock", linkA, {
        val g = garbage(linkA, 2, 10)
        garbage(linkA, 3, 10) ++
        (0 until 10).flatMap(i => Seq(linkA.expected(2L * i), linkA.expected(2L * i + 1), g(i)))
      })
    )
  }

  def checkerCasesCsv : String = {
    val rows = checkerCases.map { c =>
      val s = c.stat
      val e = s.firstErr.fold(Seq("-", "-", "-", "-", "-"))(e =>
        Seq(e.n.toString, hex(e.got.l), hex(e.got.r), hex(e.exp.l), hex(e.exp.r)))
      (Seq(c.name, hex(c.link.seed), c.link.wtx.toString, c.link.slot.toString, c.link.wrx.toString,
           c.frames.size.toString, s.frames.toString, s.bad.toString, s.gaps.toString,
           s.relocks.toString, s.lockAt.toString) ++ e).mkString(",")
    }
    val header = "case,seed,wtx,slot,wrx,nframes,frames,bad,gaps,relocks,lock_at," +
                 "err_n,err_got_l,err_got_r,err_exp_l,err_exp_r"
    (header +: rows).mkString("", "\n", "\n")
  }

  def checkerFramesCsv : String = {
    val rows = for {
      c      <- checkerCases
      (f, i) <- c.frames.zipWithIndex
    } yield s"${c.name},$i,${hex(f.l)},${hex(f.r)}"
    ("case,idx,l,r" +: rows).mkString("", "\n", "\n")
  }

  /** Nazwa pliku -> zawartosc. Kolejnosc = kolejnosc w pattern.md. */
  def files : Seq[(String, String)] = Seq(
    "pattern.csv"       -> patternCsv,
    "transfer.csv"      -> transferCsv,
    "checker_cases.csv" -> checkerCasesCsv,
    "checker_frames.csv"-> checkerFramesCsv)

  def main(args : Array[String]) : Unit = {
    val dir = new File(args.headOption.getOrElse(defaultDir))
    require(dir.isDirectory || dir.mkdirs(), s"nie moge utworzyc ${dir.getCanonicalPath}")
    for ((name, text) <- files) {
      val f = new File(dir, name)
      Files.write(f.toPath, text.getBytes(StandardCharsets.US_ASCII))
      println(s"${f.getCanonicalPath}: ${text.count(_ == '\n') - 1} wierszy")
    }
  }
}
