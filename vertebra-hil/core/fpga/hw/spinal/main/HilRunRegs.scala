package newhope.vertebra.hil

import spinal.core._
import spinal.lib._
import HilProtocol._

// =====================================================================
//  Konfiguracja biegu wspolna dla IP: generator (0x020-0x023) i
//  wstrzykiwanie resetu (0x040-0x044). Zapis tylko w stanie stop
//  (status Busy), a domena DUT-a zatrzaskuje cala konfiguracje w cyklu
//  impulsu startu i cykl pozniej daje dutGo - od niego startuja uzytkownicy
//  konfiguracji. W trakcie biegu nic nie przechodzi miedzy domenami.
// =====================================================================
case class HilRunCfg() extends Bundle {
  val seed     = Bits(32 bits)
  val gapMode  = UInt(2 bits)
  val gapEvery = UInt(16 bits)
  val gapLen   = UInt(16 bits)
  val rstCount = UInt(16 bits)
  val rstSeed  = Bits(32 bits)
  val rstMin   = UInt(32 bits)
  val rstMask  = UInt(32 bits)
  val rstLen   = UInt(16 bits)
}

case class HilRunRegs(sysCd : ClockDomain, dutCd : ClockDomain) extends Component {
  val io = new Bundle {
    val bus      = slave(HilRegBus())       // sys
    val running  = in  Bool()               // sys: blokada zapisu
    val dutStart = in  Bool()               // dut, impuls startu po CDC
    val dutCfg   = out(HilRunCfg())         // dut, stala w trakcie biegu
    val dutGo    = out Bool()               // dut, impuls: start z JUZ zatrzasnieta konfiguracja
  }

  val sys = new ClockingArea(sysCd) {
    val cfg = Reg(HilRunCfg()) init(HilRunCfg().getZero)
    cfg.flatten.foreach(_.addTag(crossClockDomain))

    val map = new HilRegMap(io.bus, lock = io.running)
    map.rw(Addr.GenSeed,  cfg.seed,     locked = true)
    map.rw(Addr.GapMode,  cfg.gapMode,  locked = true)
    map.rw(Addr.GapEvery, cfg.gapEvery, locked = true)
    map.rw(Addr.GapLen,   cfg.gapLen,   locked = true)
    map.rw(Addr.RstCount, cfg.rstCount, locked = true)
    map.rw(Addr.RstSeed,  cfg.rstSeed,  locked = true)
    map.rw(Addr.RstMin,   cfg.rstMin,   locked = true)
    map.rw(Addr.RstMask,  cfg.rstMask,  locked = true)
    map.rw(Addr.RstLen,   cfg.rstLen,   locked = true)
    map.build()
  }

  val dut = new ClockingArea(dutCd) {
    val latched = Reg(HilRunCfg()) init(HilRunCfg().getZero)
    when(io.dutStart) { latched := sys.cfg }
    io.dutCfg := latched
    // W cyklu dutStart `latched` ma jeszcze stara wartosc. Wszystko, co
    // czyta konfiguracje przy starcie (generator, reset, capture), startuje
    // od dutGo - cykl pozniej.
    io.dutGo := RegNext(io.dutStart) init False
  }
}
