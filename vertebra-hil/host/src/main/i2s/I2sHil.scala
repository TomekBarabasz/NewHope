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
  val fpgaSources = Seq(
    "vertebra-hil/fpga/hw",
    ":(exclude)vertebra-hil/fpga/hw/spinal/test",
    "i2s/hw/spinal/main",                     // DUT-y
    "mimas_v2/hw/spinal/main")

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
}
