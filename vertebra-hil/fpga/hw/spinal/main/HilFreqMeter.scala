package newhope.vertebra.hil

import spinal.core._
import spinal.lib._

// =====================================================================
//  Pomiar zegara dut zegarem sys: domena dut liczy swoje cykle, dopoki
//  trwa okno `windowCycles` cykli sys. Po oknie sys odczekuje `settle`
//  cykli (licznik juz stoi) i przepisuje wynik. Zadnych slow w ruchu
//  miedzy domenami: `last` zmienia sie tylko po opadnieciu okna.
//
//  f_dut = count * f_sys / windowCycles, z dokladnoscia +-2 cykle dut
//  (synchronizacja okna). Na plytce: 1 000 000 cykli sys = 10 ms.
// =====================================================================
case class HilFreqMeter(sysCd : ClockDomain, dutCd : ClockDomain, windowCycles : Int,
                        settle : Int = 64, width : Int = 24) extends Component {
  require(windowCycles > 2 * settle)
  val io = new Bundle {
    val count = out UInt(width bits)     // sys: cykle dut w ostatnim pelnym oknie
  }

  val sys = new ClockingArea(sysCd) {
    val period = windowCycles + 2 * settle
    val phase  = Reg(UInt(log2Up(period) bits)) init 0
    phase := (if (isPow2(period)) phase + 1 else Mux(phase === period - 1, U(0), phase + 1))
    val gate   = RegNext(phase < windowCycles) init False
    val result = Reg(UInt(width bits)) init 0
  }

  val dut = new ClockingArea(dutCd) {
    val gate = BufferCC(sys.gate, False)
    val cnt  = Reg(UInt(width bits)) init 0
    val last = Reg(UInt(width bits)) init 0
    last.addTag(crossClockDomain)
    when(gate) { cnt := cnt + 1 }
    when(gate.fall(False)) { last := cnt; cnt := 0 }
  }

  when(sys.phase === windowCycles + settle) { sys.result := dut.last }   // domena sys (sysCd)
  io.count := sys.result
}
