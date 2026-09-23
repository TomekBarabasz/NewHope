package newhope.vertebra.hil

import spinal.core._
import spinal.lib._
import HilProtocol._

// =====================================================================
//  Liczniki 0x010-0x01D (contract/commands.md): migawka przy stop.
//
//  Zasada CDC z §6: zadnych dynamicznych przejsc miedzy domenami w
//  trakcie biegu. Impuls stop (juz w domenie DUT-a) zatrzaskuje wszystkie
//  slowa naraz i przelacza toggle; domena sys widzi toggle przez BufferCC
//  i dopiero wtedy ustawia `ready` (status.snapshot). Slowa czytane przez
//  most sa w tym czasie stale, wiec przejscie jest bezpieczne mimo braku
//  synchronizatora na 32 bitach (tag crossClockDomain).
//
//  Host: stop -> czeka na status.snapshot -> czyta liczniki. Start kasuje
//  `ready`; migawka zostaje do nastepnego stop.
// =====================================================================
case class HilCounters(sysCd : ClockDomain, dutCd : ClockDomain) extends Component {
  val n = Counters.names.size

  val io = new Bundle {
    val bus      = slave(HilRegBus())                 // sys
    val start    = in  Bool()                         // sys, impuls
    val ready    = out Bool()                         // sys
    val dutStop  = in  Bool()                         // dut, impuls
    val dutWords = in  Vec(Bits(32 bits), n)          // dut
  }

  val dut = new ClockingArea(dutCd) {
    val snap   = Vec.fill(n)(Reg(Bits(32 bits)) init 0)
    val toggle = RegInit(False)
    when(io.dutStop) { snap := io.dutWords; toggle := !toggle }
    snap.foreach(_.addTag(crossClockDomain))
  }

  val sys = new ClockingArea(sysCd) {
    val t     = BufferCC(dut.toggle, False)
    val seen  = RegInit(False)
    val ready = RegInit(False)
    when(t =/= seen) { seen := t; ready := True }
    when(io.start)   { ready := False }
    io.ready := ready

    val map = new HilRegMap(io.bus, lock = False)
    for (i <- 0 until n) map.ro(Counters.Base + i, dut.snap(i))
    map.build()
  }
}
