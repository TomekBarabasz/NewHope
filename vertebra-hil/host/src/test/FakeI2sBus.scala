package newhope.vertebra.hil

import scala.collection.mutable
import newhope.vertebra.hil.i2s.{I2sBenchCfg, I2sPattern, I2sWords}
import HilProtocol._

// =====================================================================
//  Magistrala I2S miedzy atrapami (FakeFpga + ESP32 ze stanem), zeby
//  scenariusze hw_slv_* dalo sie przejsc bez stanowiska: ramki plyna
//  z czestotliwoscia fs, gdy ESP32 (master) daje zegar, a druga strona
//  pracuje. Bledy wstrzykiwane w jedna ramke odbiornika.
// =====================================================================
class FakeI2sBus(val c : I2sBenchCfg, val seed : Long, val build : String, val fpgaBuild : Long,
                 val vectors : Int) {
  val fpga = new FakeFpga(c.v.code, fpgaBuild)

  /** Przeklamanie ramki `n` po stronie odbiornika: got = f(exp). */
  var fpgaRxFault : Option[(Long, I2sWords => I2sWords)] = None
  var espRxFault  : Option[(Long, I2sWords => I2sWords)] = None

  /** Ponizej tego zegara dut DUT slave "nie nadaza": co druga ramka zla. */
  var dutMinHz : Double = 0

  private def slow : Boolean = !fpgaMaster && fpga.dutHz < dutMinHz

  private def now = System.nanoTime
  private var espOn  : Option[(Long, Option[Long])] = None       // (start, stop)
  private var fpgaOn : Option[(Long, Option[Long])] = None
  private val espCfg = mutable.Map[String, String]("tx" -> "1", "rx" -> "1", "loop" -> "0")

  private def span(x : Option[(Long, Option[Long])]) = x.map { case (a, b) => (a, b.getOrElse(now)) }
  private def overlap(a : Option[(Long, Option[Long])], b : Option[(Long, Option[Long])]) : Long =
    (span(a), span(b)) match {
      case (Some((a0, a1)), Some((b0, b1))) =>
        val t = scala.math.min(a1, b1) - scala.math.max(a0, b0)
        if (t <= 0) 0L else (t / 1e9 * c.espFs).toLong
      case _ => 0L
    }
  private def espFlag(k : String) = espCfg.get(k).contains("1")
  private def fpgaMaster : Boolean = fpga.rw(0x100) == 1L
  /** Luki generatora (gap_mode 1): z `slots` ramek na magistrali tyle niesie wzorzec. */
  private def gap : Option[(Long, Long)] =
    if (fpga.rw(Addr.GapMode) == 1 && fpga.rw(Addr.GapEvery) > 0 && fpga.rw(Addr.GapLen) > 0)
      Some((fpga.rw(Addr.GapEvery), fpga.rw(Addr.GapLen))) else None
  private def patternPart(slots : Long) : Long = gap.fold(slots) { case (e, l) => slots * e / (e + l) }

  // --- FPGA: liczniki przy stop -------------------------------------------
  // Lock checkera FPGA: bieg z nadajacym ESP32.
  fpga.extraStatus = () =>
    if (fpga.running && espFlag("tx") && overlap(espOn, fpgaOn) > 3) 1L << StatusBit.Locked else 0L

  /** Reset DUT-a: kazdy daje jeden blad po obu stronach (i nic po ostatnim). */
  private def resets : Long = if (fpgaOn.isDefined) fpga.rw(Addr.RstCount) else 0L

  fpga.onCtrl = {
    case CtrlBit.Start => fpgaOn = Some((now, None))
    case CtrlBit.Stop  => fpgaOn = fpgaOn.map { case (a, _) => (a, Some(now)) }; snapshotFpga()
    case _             => fpgaOn = fpgaOn.map { case (a, b) => (a, Some(b.getOrElse(now))) }     // soft reset
  }

  private def snapshotFpga() : Unit = {
    val cnt = fpga.counters
    cnt.clear(); cnt("lock_at") = 0xFFFFFFFFL
    fpga.capture = Nil
    val link = c.linkToFpga(seed, fpgaMaster)
    if (espFlag("tx")) {                                    // ESP32 nadaje -> checker FPGA
      val n = overlap(espOn, fpgaOn)
      if (n > 3) {
        cnt("lock_at") = 0; cnt("frames") = n
        fpgaRxFault.filter(_._1 < n).foreach { case (k, f) =>
          val exp = link.expected(k); val got = f(exp)
          cnt("frames") = n - 1; cnt("bad") = 1
          Seq("err_n" -> k, "err_got_l" -> got.l, "err_got_r" -> got.r, "err_exp_l" -> exp.l, "err_exp_r" -> exp.r)
            .foreach { case (a, b) => cnt(a) = b }
          fpga.capture = (k - 16 until k + 16).filter(_ >= 0).map { i =>
            val e = link.expected(i); val g = if (i == k) got else e
            Seq(i, g.l, g.r, e.l, e.r)
          }
          cnt("cap_count") = fpga.capture.size.toLong
        }
      }
    }
    if (espFlag("rx")) cnt("sent") = patternPart(overlap(espOn, fpgaOn))   // generator FPGA
    if (slow && cnt("frames") > 0) { cnt("bad") = cnt("bad") + cnt("frames") / 2; cnt("frames") = cnt("frames") / 2 }
    cnt("rst_done") = resets
    if (resets > 0 && cnt("frames") > 0) cnt("bad") = cnt("bad") + resets
  }

  // --- ESP32 ---------------------------------------------------------------
  private def espStat : String = {
    // Nadawanie ESP32 idzie za zegarem: wlasnym (master) albo FPGA (slave).
    val clock = if (fpgaMaster) fpgaOn else espOn
    val sent = if (espFlag("tx")) overlap(espOn, clock) else 0L
    val link = c.linkToEsp(seed, fpgaMaster)
    var frames = 0L; var bad = 0L; var err = "-"; var lockAt = "-1"
    if (espFlag("rx")) {
      val n = scala.math.max(0L, overlap(espOn, fpgaOn) - 480)  // ramki jeszcze w DMA
      if (n > 3) {
        frames = n; lockAt = "0"
        espRxFault.filter(_._1 < n).foreach { case (k, f) =>
          val exp = link.expected(k); val got = f(exp)
          frames = n - 1; bad = 1
          err = f"$k:${got.l}%08x:${got.r}%08x:${exp.l}%08x:${exp.r}%08x"
        }
      }
    }
    // Z `frames` ramek na magistrali luki zajmuja l z kazdych e + l.
    val gaps = if (frames > 0) gap.fold(0L) { case (e, l) => val g = frames * l / (e + l); frames -= g; g }
               else 0L
    if (frames > 0) bad += resets
    if (slow && frames > 0) { bad += frames / 2; frames = frames / 2 }
    s"sent=$sent frames=$frames bad=$bad gaps=$gaps relocks=0 lock_at=$lockAt first_err=$err overflow=0"
  }

  private def espDump : Seq[String] = espRxFault match {
    case Some((k, f)) if espOn.isDefined =>
      val link = c.linkToEsp(seed, fpgaMaster)
      val rows = (k - 16 until k + 16).filter(_ >= 0).map { i =>
        val e = link.expected(i); val g = if (i == k) f(e) else e
        f"$i ${g.l}%08x ${g.r}%08x ${e.l}%08x ${e.r}%08x"
      }
      s"ok n=${rows.size}" +: rows :+ "ok end"
    case _ => Seq("ok n=0", "ok end")
  }

  val esp = new FakeEsp({
    case "ver"      => Seq(s"ok proto=1 dev=esp32s3 ip=i2s build=$build")
    case "selftest" => Seq(s"ok vectors=$vectors")
    case "start"    => espOn = Some((now, None)); Seq("ok")
    case "stop"     => espOn = espOn.map { case (a, b) => (a, Some(b.getOrElse(now))) }; Seq("ok")
    case "stat"     => Seq(s"ok $espStat")
    case "dump"     => espDump
    case l if l.startsWith("cfg ") =>
      val kv = l.split(' ').drop(1).map(_.split('=')).map(a => a(0) -> a(1))
      if (kv.forall(k => Set("role", "fs", "w", "slot", "seed", "peer_w", "tx", "rx", "loop")(k._1))) {
        espCfg ++= kv; Seq("ok")
      } else Seq("err 2 nieznany klucz")
    case x => Seq(s"err 1 nieznana komenda '$x'")
  })
}
