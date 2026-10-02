package newhope.vertebra.hil.fe

import scala.collection.mutable
import newhope.frontend.DcGolden
import newhope.vertebra.hil._
import HilProtocol._

// =====================================================================
//  Stanowisko FE z atrap (HilHostTestplan, host_fe_on_fakes): ESP32
//  z `load` / `rec` i FPGA z licznikami FE. Ramki plyna z fs, gdy pracuja
//  obie strony; przy stop FPGA atrapa sklada nagranie tak, jak zlozylby je
//  krzem: x = slowo bodzca >> 14 (z jedna ramka ciszy przed bodzcem),
//  y = DcGolden(x) na odcinkach miedzy resetami, bypass z rejestrow.
//  Bledy wstrzykiwane do pojedynczej ramki, zeby sprawdzic komunikaty FeCheck.
// =====================================================================
class FakeFeBus(val v : FeHilVariant, val build : String, val fpgaBuild : Long) {
  val fpga = new FakeFpga(v.code, fpgaBuild, "FE", FeHilRegs.counters,
    Some(Map(FeHilRegs.BypassFrom -> 0L, FeHilRegs.BypassTo -> 0L, FeHilRegs.Tail -> FeHilRegs.TailDefault.toLong,
             FeHilRegs.DumpEvery -> FeHilRegs.DumpEveryDefault.toLong)))

  /** Blad DUT-a: y ramki k z odwroconym LSB. */
  var yFault : Option[Int] = None
  /** Blad N0: echo x ramki k inne niz bodziec (golden z tego x, wiec tylko N0). */
  var xFault : Option[Int] = None
  /** Ramka k ginie po drodze do ESP32. */
  var dropAt : Option[Int] = None
  /** N4: rekord CRC ramki widma nr k ma przeklamane CRC N3 (blad FftCore). */
  var n3Fault : Option[Int] = None

  private val fs = v.fe.i2s.fs
  private def now = System.nanoTime
  private var espOn  : Option[(Long, Option[Long])] = None
  private var fpgaOn : Option[(Long, Option[Long])] = None
  private val stim = mutable.ArrayBuffer[Long]()
  private var raw : Seq[(Long, Long)] = Nil
  private var recorded = false

  private def span(x : Option[(Long, Option[Long])]) = x.map { case (a, b) => (a, b.getOrElse(now)) }
  private def overlap : Long = (span(espOn), span(fpgaOn)) match {
    case (Some((a0, a1)), Some((b0, b1))) =>
      val t = scala.math.min(a1, b1) - scala.math.max(a0, b0)
      if (t <= 0) 0L else (t / 1e9 * fs).toLong
    case _ => 0L
  }
  /** Probki DUT-a w biegu FPGA (bez partnera SD_IN = 0, x = 0). */
  private def fpgaSamples : Long = span(fpgaOn).fold(0L) { case (a, b) => ((b - a) / 1e9 * fs).toLong }
  private def withEsp = espOn.exists { case (a, b) => b.isEmpty && fpgaOn.exists(_._1 >= a) }

  fpga.extraStatus = () => if (fpga.running) 1L << StatusBit.Locked else 0L

  fpga.onCtrl = {
    case CtrlBit.Start => fpgaOn = Some((now, None)); recorded = false
    case CtrlBit.Stop  =>
      if (fpgaOn.exists(_._2.isEmpty)) {
        fpgaOn = fpgaOn.map { case (a, b) => (a, Some(b.getOrElse(now))) }
        result()
      }
    case _ => fpgaOn = fpgaOn.map { case (a, b) => (a, Some(b.getOrElse(now))) }
  }

  /** Nagranie i liczniki FPGA po stop. */
  private def result() : Unit = {
    val sw = v.sampleWidth
    val esp = withEsp
    val n  = (if (esp) overlap else fpgaSamples).toInt
    val xs = (0 until n).map { k =>
      val x = if (esp && k >= 1 && k - 1 < stim.size) FeFrame.micSample(stim(k - 1), sw) else 0L
      if (xFault.contains(k)) x ^ 4L else x
    }
    val cnt  = fpga.rw(Addr.RstCount).toInt
    val rsts = (1 to cnt).map(j => j * n / (cnt + 1)).toSet + 0
    val (from, to) = (fpga.rw(FeHilRegs.BypassFrom), fpga.rw(FeHilRegs.BypassTo))
    val cuts = (rsts.toSeq.sorted :+ n).distinct
    val gold = cuts.sliding(2).filter(_.size == 2).flatMap { case Seq(a, b) => DcGolden.run(v.fe.dc, xs.slice(a, b)).y }.toVector
    val outs = (0 until n).map { k =>
      val byp = k >= from && k < to
      val y0  = if (byp) xs(k) else gold(k)
      FeFrame.Out(k & FeFrame.IdxMask, xs(k), if (yFault.contains(k)) y0 ^ 1L else y0, rsts(k), byp, false)
    }
    val c = fpga.counters
    c.clear()
    c("sent") = n; c("frames") = n; c("underrun") = 1
    val (xsum, ysum) = FeCheck.sums(outs)
    c("x_sum") = xsum; c("y_sum") = ysum; c("rst_done") = cnt
    // N2 - N4: rekordy jak ze sprzetu, bajt na ramke od trig + 6 probek
    val aux = scala.collection.mutable.Map[Int, (Boolean, Int)]()
    if (v.hasN4 && n > 0) {
      val recs = FeN4Check.records(v, k => if (k < n) Some(outs(k.toInt).y) else None, 0, n - 1,
                                   rsts.toSeq.sorted.map(_.toLong), fpga.rw(FeHilRegs.DumpEvery).toInt)
      var cursor = 0
      var crcK = 0
      for ((trig, bytes0) <- recs) {
        val bytes = if (bytes0.head == FeN4.TypeCrc) {
          val b = if (n3Fault.contains(crcK)) bytes0.updated(11, bytes0(11) ^ 0x10) else bytes0
          crcK += 1; b
        } else bytes0
        val start = scala.math.max(cursor, trig.toInt + 6)
        for ((b, i) <- bytes.zipWithIndex if start + i < n) aux(start + i) = (i == 0, b)
        cursor = start + bytes.size
      }
      c("n4_frames") = crcK; c("n4_crc") = crcK; c("n4_dump") = recs.size - crcK
    }
    val silence = Seq.fill(2)((0L, 0L))
    val enc = outs.zipWithIndex.filterNot(o => dropAt.contains(o._2))
      .map { case (o, k) => FeFrame.encode(o.copy(aux = aux.get(k)), sw) }
    raw = silence ++ enc ++ Seq.fill(fpga.rw(FeHilRegs.Tail).toInt)((0L, 0L))
    recorded = true
  }

  // --- ESP32 ---------------------------------------------------------------
  private val espCfg = mutable.Map[String, String]("rec" -> "1")
  private def espRunning = espOn.exists(_._2.isEmpty)

  private def espStat : String = {
    val frames = if (recorded) raw.size.toLong else overlap
    val sum = stim.foldLeft(0L)((a, w) => (a + w) & FeFrame.U32)
    s"sent=${if (espOn.isDefined) stim.size else 0} frames=$frames bad=0 gaps=0 relocks=0 lock_at=-1 first_err=- " +
    f"overflow=0 stim=${stim.size} stim_sum=$sum%08x rec=${if (recorded) raw.size else 0} rec_max=${FeHilPlan.RecCap}"
  }

  private def kv(l : String) : Map[String, String] =
    l.split(' ').drop(1).map(_.split("=", 2)).map(a => a(0) -> (if (a.size > 1) a(1) else "")).toMap

  private def load(l : String) : Seq[String] = {
    val a = kv(l)
    if (!a.keySet.subsetOf(Set("off", "data"))) return Seq("err 2 nieznany klucz")
    if (espRunning) return Seq("err 4 load w trakcie biegu, najpierw stop")
    val off = a.get("off").flatMap(_.toIntOption).getOrElse(-1)
    val hex = a.getOrElse("data", "")
    if (off < 0 || (off != 0 && off != stim.size)) return Seq(s"err 3 off=$off: bufor ma ${stim.size} slow")
    if (hex.isEmpty || hex.length % 8 != 0 || hex.length > 8 * FeEsp.LoadWords ||
        !hex.forall(c => Character.digit(c, 16) >= 0)) return Seq("err 3 data")
    if (off == 0) stim.clear()
    stim ++= hex.grouped(8).map(java.lang.Long.parseLong(_, 16))
    Seq(s"ok n=${stim.size}")
  }

  private def rec(l : String) : Seq[String] = {
    val a = kv(l)
    if (espRunning) return Seq("err 4 rec w trakcie biegu, najpierw stop")
    val (off, n) = (a.get("off").flatMap(_.toIntOption).getOrElse(-1), a.get("n").flatMap(_.toIntOption).getOrElse(-1))
    val have = if (recorded) raw.size else 0
    if (off < 0 || n < 1 || n > FeEsp.RecPerCmd || off + n > have) return Seq(s"err 3 off=$off n=$n: nagranie ma $have ramek")
    val lines = raw.slice(off, off + n).grouped(FeEsp.RecLineFrames)
      .map(_.map { case (x, y) => f"$x%08x$y%08x" }.mkString).toSeq
    s"ok n=${lines.size}" +: lines :+ "ok end"
  }

  val esp = new FakeEsp({
    case "ver"      => Seq(s"ok proto=1 dev=esp32s3 ip=fe build=$build")
    case "selftest" => Seq("# petla 32/32 fs=16000: 4096 slow od ramki 3 ok", "ok vectors=0")
    case "start"    => espOn = Some((now, None)); recorded = false; Seq("ok")
    case "stop"     => espOn = espOn.map { case (a, b) => (a, Some(b.getOrElse(now))) }; Seq("ok")
    case "stat"     => Seq(s"ok $espStat")
    case "dump"     => Seq("ok n=0", "ok end")
    case l if l.startsWith("load") => load(l)
    case l if l.startsWith("rec")  => rec(l)
    case l if l.startsWith("cfg ") =>
      val a = kv(l)
      if (espRunning) Seq("err 4 cfg w trakcie biegu")
      else if (a.keySet.subsetOf(Set("fs", "rec"))) { espCfg ++= a; Seq("ok") }
      else Seq("err 2 nieznany klucz")
    case x => Seq(s"err 1 nieznana komenda '$x'")
  })
}
