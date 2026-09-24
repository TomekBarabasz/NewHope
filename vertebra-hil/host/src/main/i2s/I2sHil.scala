package newhope.vertebra.hil.i2s

import newhope.vertebra.hil._
import I2sPattern.Link

// =====================================================================
//  Host dla I2S: klucze cfg bloku 0x100 FPGA (contract/i2s/commands.md),
//  zrodla firmware i bitstreamu (sprawdzenie build w HilBench) i lista
//  konfiguracji stanowiska (vertebra-hil.md §8, "Plan I2S").
// =====================================================================

object I2sFpgaMap extends HilFpgaMap {
  val ipId = "I2S"

  /** Nazwy jak w cfg ESP32; `role` to rola FPGA. */
  val keys : Map[String, HilCfgKey] = Map(
    "role"   -> HilCfgKey.enumOf(I2sHilRegs.Role, "slave", "master"),   // RoleSlave = 0, RoleMaster = 1
    "peer_w" -> HilCfgKey.range(I2sHilRegs.PeerW, I2sPattern.MinWidth, I2sPattern.MaxWidth),
    "slot"   -> HilCfgKey.range(I2sHilRegs.Slot, 1, 63))                    // 6 bitow w I2sHilCfg

  def variant(code : Long) : Option[I2sHilVariant] = I2sHilVariant.all.find(_.code == code)
  def variantName(code : Long) : Option[String] = variant(code).map(_.name)

  /** Checker FPGA liczy Link(seed, peer_w, slot, width wariantu). */
  override def validate(code : Long, get : String => Long) : Option[String] =
    variant(code) match {
      case None => Some(f"nieznany wariant $code%08x")
      case Some(v) =>
        val (pw, slot) = (get("peer_w").toInt, get("slot").toInt)
        val ok = scala.util.Try(Link(get("seed"), pw, slot, v.width)).toOption.exists(_.checkable)
        if (ok) None
        else Some(s"peer_w=$pw slot=$slot przy width=${v.width} (${v.name}): polaczenie nie jest checkable")
    }
}

object I2sHil extends HilIp {
  val name    = "i2s"
  val fpgaMap = I2sFpgaMap

  val espSources = Seq(
    "vertebra-hil/esp32",
    ":(exclude)vertebra-hil/esp32/test",      // testy na PC
    ":(exclude)vertebra-hil/esp32/echo",      // osobny projekt z etapu 0
    "vertebra-hil/contract/i2s/vectors")      // wbudowane przez EMBED_TXTFILES
  /** Ta sama lista, z ktorej harness liczy flage dirty w rejestrze build. */
  val fpgaSources = HilBuildInfo.sources

  val espFlashHint  = "Przeflashuj: cd vertebra-hil/esp32 && idf.py build flash"
  val fpgaFlashHint = "Wgraj bitstream: I2sHarnessTop_<wariant> -> ISE -> .bin, tools/programmer.py"

  /** `selftest` ESP32: wiersze pattern.csv i transfer.csv + scenariusze
    * checkera (contract/i2s/commands.md). Liczone z generatora wektorow,
    * a zgodnosc plikow w repo z generatorem sprawdza ctr_vectors_fresh. */
  lazy val selftestVectors : Int = {
    def rows(csv : String) = csv.split('\n').count(_.trim.nonEmpty) - 1
    rows(I2sVectors.patternCsv) + rows(I2sVectors.transferCsv) + I2sVectors.checkerCases.size
  }
}

/** Konfiguracja magistrali w tescie: strona ESP32 (fs, w, slot) i wariant
  * bitstreamu (DUT-y). `espW` rozne od v.width to word_length_mismatch. */
case class I2sBenchCfg(name : String, v : I2sHilVariant, espFs : Int, espW : Int, espSlot : Int) {
  /** FPGA -> ESP32: nadaje DUT (width), slot mastera, ESP32 czyta espW. */
  def linkToEsp(seed : Long, fpgaMaster : Boolean) : Link =
    Link(seed, v.width, if (fpgaMaster) v.slotWidth else espSlot, espW)
  /** ESP32 -> FPGA: nadaje ESP32 (espW), checker FPGA czyta width. */
  def linkToFpga(seed : Long, fpgaMaster : Boolean) : Link =
    Link(seed, espW, if (fpgaMaster) v.slotWidth else espSlot, v.width)
}

object I2sBenchCfg {
  /** Jawna lista konfiguracji (§8): typowe 48 kHz / 16 w 32, 44,1 kHz /
    * 24 w 32, 16/16 i 32/32 bez paddingu, plus niedopasowanie dlugosci
    * slowa w obie strony. */
  val all : Seq[I2sBenchCfg] = Seq(
    I2sBenchCfg("48k_16in32",     I2sHilVariant.v16_32, 48000, 16, 32),
    I2sBenchCfg("44k1_24in32",    I2sHilVariant.v24_32, 44100, 24, 32),
    I2sBenchCfg("48k_16in16",     I2sHilVariant.v16_16, 48000, 16, 16),
    I2sBenchCfg("48k_32in32",     I2sHilVariant.v32_32, 48000, 32, 32),
    I2sBenchCfg("48k_esp24_dut16", I2sHilVariant.v16_32, 48000, 24, 32),
    I2sBenchCfg("44k1_esp16_dut24", I2sHilVariant.v24_32, 44100, 16, 32))

  // Konfiguracje V2 pochodne od wariantu (I2sHilTestplan). FPGA slave
  // przyjmie dowolny slot ESP32 mastera, wiec padding, jego brak i fs
  // ulamkowe da sie wymusic z tym samym bitstreamem.

  /** Slowo DUT-a w slocie 32; None dla slowa 32 bity (brak paddingu). */
  def padding(v : I2sHilVariant) : Option[I2sBenchCfg] =
    if (v.width < 32) Some(I2sBenchCfg(s"pad_${v.width}in32", v, v.fs, v.width, 32)) else None

  /** Slot rowny slowu. */
  def lsbAcrossWs(v : I2sHilVariant) : I2sBenchCfg =
    I2sBenchCfg(s"lsb_${v.width}in${v.width}", v, v.fs, v.width, v.width)

  /** ESP32 z inna szerokoscia niz DUT, slot wariantu (FPGA master go narzuca);
    * przy slocie 16 jedyna inna szerokosc ESP32 to 8. */
  def mismatch(v : I2sHilVariant) : I2sBenchCfg = {
    val other = if (v.slotWidth == 16) 8 else if (v.width == 16) 24 else 16
    I2sBenchCfg(s"esp${other}_dut${v.width}", v, v.fs, other, v.slotWidth)
  }

  /** ESP32 master na 44,1 kHz (dzielnik ulamkowy PLL 160 MHz). */
  def fsFractional(v : I2sHilVariant) : I2sBenchCfg =
    I2sBenchCfg(s"44k1_${v.width}in${v.slotWidth}", v, 44100, v.width, v.slotWidth)

  /** hw_clock_ratio_sweep: ESP32 master z fs wariantu w slocie 32 (FPGA
    * slave przyjmie dowolny slot). Slot 32 trzyma granice f* ~18-19 MHz,
    * w zasiegu DCM; przy slocie 16 spadlaby ponizej 10 MHz. */
  def sweep(v : I2sHilVariant) : I2sBenchCfg =
    I2sBenchCfg(s"sweep_${v.fs}_${v.width}in32", v, v.fs, v.width, 32)

  /** Wszystkie biegi V2 wariantu: (konfiguracja, czy FPGA moze byc masterem). */
  def v2(v : I2sHilVariant) : Seq[(I2sBenchCfg, Boolean)] =
    padding(v).map(_ -> false).toSeq ++ Seq(lsbAcrossWs(v) -> false, mismatch(v) -> true, fsFractional(v) -> false)
}
