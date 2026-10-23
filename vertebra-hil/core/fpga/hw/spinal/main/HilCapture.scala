package newhope.vertebra.hil

import spinal.core._
import spinal.lib._
import HilProtocol._

// =====================================================================
//  Bufor przechwytywania (contract/commands.md, capture 0x1000-).
//
//  Pierscien `depth` wpisow po 5 slow. Po pierwszym wyzwoleniu zapisuje
//  jeszcze tyle wpisow, zeby wyzwalajacy lezal w polowie okna
//  (depth/2 przed nim, depth/2 lacznie z nim), i zamarza. Wpis zawiera
//  wlasny indeks ramki, wiec host sortuje po nim i nie potrzebuje
//  wskaznika zapisu; liczba waznych wpisow to cap_count (licznik).
//
//  Zapis: 5 cykli na wpis (jeden port BRAM 32 bity). Wpisy przychodza
//  raz na ramke, czyli co >= 128 cykli (I2sPatternTestplan, param_bounds);
//  wpis w trakcie zapisu przepada i ustawia `dutDropped`.
//
//  Odczyt: readSync w domenie sys z bus.addr. Most trzyma adres stabilny
//  od bajtu adresu do konca transakcji (HilRegBus), wiec dana jest gotowa
//  w cyklu valid. Czytac po stop (tak jak liczniki).
// =====================================================================
case class HilCapture(sysCd : ClockDomain, dutCd : ClockDomain, depth : Int = Capture.Depth) extends Component {
  require(isPow2(depth) && depth >= 4, s"depth=$depth")
  require(Capture.Stride == 8 && Capture.WordsPerEntry <= 8)
  val aw = log2Up(depth * Capture.Stride)
  require((Capture.Base & ((1 << aw) - 1)) == 0, "baza capture wyrownana do rozmiaru")

  val io = new Bundle {
    val bus        = slave(HilRegBus())                                   // sys
    val dutClear   = in  Bool()                                           // dut, impuls (start)
    val dutPush    = slave(Flow(Vec(Bits(32 bits), Capture.WordsPerEntry)))  // dut
    val dutTrigger = in  Bool()                                           // dut, razem z push
    val dutCount   = out UInt(32 bits)                                    // dut
    val dutDropped = out Bool()                                           // dut, sticky
  }

  val mem = Mem(Bits(32 bits), depth * Capture.Stride)

  val dut = new ClockingArea(dutCd) {
    val wr        = Reg(UInt(log2Up(depth) bits)) init 0
    val count     = Reg(UInt(32 bits)) init 0
    val triggered = RegInit(False)
    val postLeft  = Reg(UInt(log2Up(depth) bits)) init 0
    val frozen    = RegInit(False)
    val busy      = RegInit(False)
    val j         = Reg(UInt(3 bits)) init 0
    val entry     = Reg(Vec(Bits(32 bits), Capture.WordsPerEntry))
    val dropped   = RegInit(False)

    val accept = io.dutPush.valid && !frozen && !busy
    when(io.dutPush.valid && busy) { dropped := True }
    when(accept) {
      entry := io.dutPush.payload
      busy  := True
      j     := 0
      when(io.dutTrigger && !triggered) { triggered := True; postLeft := depth / 2 - 1 }
    }

    mem.write(address = (wr ## j).asUInt, data = entry(j.resize(log2Up(Capture.WordsPerEntry) bits)),
              enable = busy)

    when(busy) {
      j := j + 1
      when(j === Capture.WordsPerEntry - 1) {
        busy := False
        wr   := wr + 1
        when(count =/= depth) { count := count + 1 }
        when(triggered) {
          when(postLeft === 0) { frozen := True } otherwise { postLeft := postLeft - 1 }
        }
      }
    }

    when(io.dutClear) {
      wr := 0; count := 0; triggered := False; frozen := False; busy := False; dropped := False
    }

    io.dutCount   := count
    io.dutDropped := dropped
  }

  val sys = new ClockingArea(sysCd) {
    // Port zapisu w dut, odczytu w sys (BRAM dwuportowy). Czytamy po stop,
    // gdy zapis stoi - jak StreamFifoCC, przejscie oznaczone jawnie.
    val rd  = mem.readSync(io.bus.addr(aw - 1 downto 0), clockCrossing = true)
    val col = io.bus.addr(2 downto 0)
    io.bus.rdata  := Mux(col < Capture.WordsPerEntry, rd, B(0, 32 bits))
    io.bus.status := Mux(io.bus.write, B(Status.BadOp, 8 bits), B(Status.Ok, 8 bits))
  }
}
