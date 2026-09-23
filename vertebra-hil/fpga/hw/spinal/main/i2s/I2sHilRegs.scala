package newhope.vertebra.hil.i2s

import spinal.core._
import spinal.lib._
import newhope.i2s.{I2sGenerics, I2sSlaveGenerics}
import newhope.vertebra.hil.HilRegMap

// =====================================================================
//  Wariant bitstreamu I2S (vertebra-hil.md §6, contract/i2s/commands.md).
//
//  Generyki DUT-ow sa ustalane przy elaboracji, wiec kazdy wariant to
//  osobny bitstream. Zegar `dut` jest staly w wariancie:
//    dutHz = fs * 2 kanaly * slotWidth * 2 polokresy * halfDiv
//  Liczba jest nominalna - I2sGenerics wymaga calkowitego halfDiv, a to,
//  co naprawde da DCM, wplywa tylko na fs (ESP32 jako slave idzie za
//  SCK, jako master nie zalezy od naszego fs). halfDiv dobrany tak, zeby
//  dut ~ 45-50 MHz: slave ma wtedy ~8 cykli na polokres SCK ESP32
//  (wymog > txLatencyCycles = 3).
// =====================================================================
case class I2sHilVariant(name : String, fs : Int, width : Int, slotWidth : Int, halfDiv : Int) {
  def dutHz : Long = fs.toLong * 4 * halfDiv * slotWidth

  def masterG : I2sGenerics =
    I2sGenerics(HertzNumber(BigDecimal(dutHz)), HertzNumber(BigDecimal(fs)), width, slotWidth)
  def slaveG : I2sSlaveGenerics = I2sSlaveGenerics(width)

  /** Rejestr variant (0x006): width | slotWidth << 8 | halfDiv << 16. */
  def code : Long = width.toLong | (slotWidth.toLong << 8) | (halfDiv.toLong << 16)

  def isLegal : Boolean =
    I2sPattern.validWidth(width) && masterG.isLegal && masterG.halfDiv == halfDiv && slaveG.isLegal

  /** Polokres SCK mastera zewnetrznego (ESP32) w cyklach dut, przy fs i slocie. */
  def slaveMarginCycles(espFs : Int, espSlot : Int) : BigDecimal =
    BigDecimal(dutHz) / (BigDecimal(espFs) * 2 * espSlot * 2)
}

object I2sHilVariant {
  val v16_32 = I2sHilVariant("v16_32", 48000, 16, 32, 8)    // 49,152 MHz
  val v24_32 = I2sHilVariant("v24_32", 44100, 24, 32, 8)    // 45,1584 MHz
  val v16_16 = I2sHilVariant("v16_16", 48000, 16, 16, 16)   // 49,152 MHz
  val v32_32 = I2sHilVariant("v32_32", 48000, 32, 32, 8)    // 49,152 MHz
  val all = Seq(v16_32, v24_32, v16_16, v32_32)
}

/** Konfiguracja IP w bloku 0x100 (contract/i2s/commands.md). */
case class I2sHilCfg() extends Bundle {
  val master = Bool()           // 1 = FPGA master (SCK/WS wyjscia), 0 = slave
  val peerW  = UInt(6 bits)     // Wtx strony ESP32 dla checkera
  val slot   = UInt(6 bits)     // slot na magistrali dla checkera
}

object I2sHilRegs {
  val Role  = 0x100
  val PeerW = 0x101
  val Slot  = 0x102
  val RoleSlave  = 0
  val RoleMaster = 1
}

/** Rejestry 0x100-: zapis w stop, zatrzasniecie w domenie dut przy starcie
  * (jak HilRunRegs; wazne od dutGo). Po resecie rola slave: FPGA nie
  * steruje SCK/WS, wiec nie walczy z ESP32 skonfigurowanym jako master. */
case class I2sHilRegs(v : I2sHilVariant, sysCd : ClockDomain, dutCd : ClockDomain) extends Component {
  import I2sHilRegs._

  val io = new Bundle {
    val bus      = slave(newhope.vertebra.hil.HilRegBus())
    val running  = in  Bool()
    val dutStart = in  Bool()
    val dutCfg   = out(I2sHilCfg())
  }

  private def initCfg : I2sHilCfg = {
    val c = I2sHilCfg()
    c.master := False
    c.peerW  := U(v.width, 6 bits)
    c.slot   := U(v.slotWidth, 6 bits)
    c
  }

  val sys = new ClockingArea(sysCd) {
    val cfg = Reg(I2sHilCfg()) init(initCfg)
    cfg.flatten.foreach(_.addTag(crossClockDomain))
    val map = new HilRegMap(io.bus, lock = io.running)
    map.rw(Role,  cfg.master, locked = true)
    map.rw(PeerW, cfg.peerW,  locked = true)
    map.rw(Slot,  cfg.slot,   locked = true)
    map.build()
  }

  val dut = new ClockingArea(dutCd) {
    val latched = Reg(I2sHilCfg()) init(initCfg)
    when(io.dutStart) { latched := sys.cfg }
    io.dutCfg := latched
  }
}
