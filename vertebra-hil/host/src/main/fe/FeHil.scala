package newhope.vertebra.hil.fe

import newhope.vertebra.hil._

// =====================================================================
//  Host dla frontendu N0 + N1 (contract/fe/commands.md, vertebra-hil.md
//  §12): klucze cfg bloku 0x100 FPGA, liczniki w ukladzie FE, zrodla
//  firmware i bitstreamu oraz komendy roli ESP32 (`load`, `rec`).
// =====================================================================

object FeFpgaMap extends HilFpgaMap {
  val ipId = "FE"

  val keys : Map[String, HilCfgKey] = Map(
    "bypass_from" -> HilCfgKey.bits(FeHilRegs.BypassFrom, 32),
    "bypass_to"   -> HilCfgKey.bits(FeHilRegs.BypassTo, 32),
    "tail"        -> HilCfgKey.bits(FeHilRegs.Tail, 16))

  def variant(code : Long) : Option[FeHilVariant] = FeHilVariant.all.find(_.code == code)
  def variantName(code : Long) : Option[String] = variant(code).map(_.name)

  override def validate(code : Long, get : String => Long) : Option[String] =
    if (variant(code).isEmpty) Some(f"nieznany wariant $code%08x")
    else if (get("bypass_from") > get("bypass_to")) Some(s"bypass_from ${get("bypass_from")} > bypass_to ${get("bypass_to")}")
    else None

  override def counterNames : Seq[String] = FeHilRegs.counters

  /** Bez checkera wzorca: frames = ramki wyniku, overflow = kolejka do
    * nadajnika powrotnego; reszta licznikow FE w `extra`. */
  override def toStat(c : Map[String, Long]) : HilStat =
    HilStat(c("sent"), c("frames"), 0, 0, 0, if (c("frames") > 0) 0L else -1L, None, c("overflow"),
            scala.collection.immutable.ListMap(FeHilRegs.counters.filterNot(Set("sent", "frames", "overflow"))
              .map(n => n -> (if (n.endsWith("_sum")) f"${c(n)}%08x" else c(n).toString)) : _*))
}

object FeHil extends HilIp {
  val name    = "fe"
  val fpgaMap = FeFpgaMap

  /** Ten sam projekt ESP-IDF co I2S (rola z sdkconfig.fe), wiec te same zrodla. */
  val espSources = Seq(
    "vertebra-hil/esp32",
    ":(exclude)vertebra-hil/esp32/test",
    ":(exclude)vertebra-hil/esp32/echo",
    "vertebra-hil/contract/i2s/vectors")
  val fpgaSources = HilBuildInfo.feSources

  val espFlashHint = "Przeflashuj firmware FE: cd vertebra-hil/esp32 && idf.py -B build-fe " +
    "-D SDKCONFIG=build-fe/sdkconfig -D SDKCONFIG_DEFAULTS=\"sdkconfig.defaults;sdkconfig.fe\" build flash"
  val fpgaFlashHint = "Wgraj bitstream: FeHarnessTop_mimas (hilFpga/runMain ...FeHarnessTopVerilog) -> ISE " +
    "z hw/fe_harness.ucf -> .bin, tools/programmer.py"
}

/** Komendy roli ESP32 FE nad EspDevice. */
object FeEsp {
  val LoadWords      = 29          // slow w linii `load` (HIL_LOAD_MAX_WORDS)
  val RecPerCmd      = 4096        // ramek na komende `rec` (HIL_REC_MAX_PER_CMD)
  val RecLineFrames  = 15

  def loadLines(stim : Seq[Long]) : Seq[String] =
    stim.grouped(LoadWords).zipWithIndex.map { case (g, i) =>
      s"load off=${i * LoadWords} data=" + g.map(w => f"${w & FeFrame.U32}%08x").mkString
    }.toSeq

  def sum(stim : Seq[Long]) : Long = stim.foldLeft(0L)((a, w) => (a + w) & FeFrame.U32)

  /** Bodziec do PSRAM; sprawdza liczbe slow i sume z `stat`. */
  def load(d : EspDevice, stim : Seq[Long]) : Unit = {
    require(stim.nonEmpty, "pusty bodziec")
    d.pipeline(loadLines(stim))
    val st = d.stat()
    val n  = st.extra.get("stim").flatMap(_.toLongOption)
    val s  = st.extra.get("stim_sum").map(java.lang.Long.parseLong(_, 16))
    if (n != Some(stim.size.toLong) || s != Some(sum(stim)))
      throw new HilDeviceError(d.label, None,
        f"load: ESP32 ma stim=${n.getOrElse(-1L)} stim_sum=${s.getOrElse(-1L)}%08x, wyslano ${stim.size} slow, suma ${sum(stim)}%08x")
  }

  def parseLine(l : String) : Seq[(Long, Long)] = {
    if (l.isEmpty || l.length % 16 != 0 || l.length > 16 * RecLineFrames)
      throw new IllegalArgumentException(s"rec: zla linia '${l.take(40)}' (${l.length} znakow)")
    l.grouped(16).map(f => (java.lang.Long.parseLong(f.take(8), 16), java.lang.Long.parseLong(f.drop(8), 16))).toSeq
  }

  /** Cale nagranie (`rec` = liczba ramek z `stat`), kawalkami. */
  def record(d : EspDevice, frames : Long, progress : Long => Unit = _ => ()) : Seq[(Long, Long)] = {
    val out = scala.collection.mutable.ArrayBuffer[(Long, Long)]()
    while (out.size < frames) {
      val n = scala.math.min(RecPerCmd.toLong, frames - out.size).toInt
      val (_, lines) = d.cmdData(s"rec off=${out.size} n=$n")
      val got = lines.flatMap(parseLine)
      if (got.size != n) throw new HilDeviceError(d.label, None, s"rec off=${out.size} n=$n: przyszlo ${got.size} ramek")
      out ++= got
      progress(out.size.toLong)
    }
    out.toVector
  }
}
