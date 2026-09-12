package newhope.aht10

import spinal.core._

// =====================================================================
//  ETAP 1 - weryfikacja wyswietlacza na plytce.
//
//  Dwa wzorce na przemian, po 2 s kazdy. Sam "1.23" nie wystarczy:
//  uzywa segmentow a, b, c, d, e, g - segment f nie zapala sie ani
//  razu, wiec urwany LOC na D6 przeszedlby niezauwazony az do etapu 5,
//  gdzie objawilby sie jako "szostka wyglada jak szesc, ale piatka jak
//  cos dziwnego".
//
//   * "8.8.8." - kazdy segment i kazda kropka, plus porownanie jasnosci
//                trzech cyfr. Lapie martwe segmenty i martwe enable'y.
//   * "1.23"   - dekoder, pozycja kropki, kolejnosc cyfr. Lapie
//                zamienione segmenty (na "8.8.8." zamiana jest
//                niewidoczna) i odwrocona kolejnosc cyfr.
//
//  D1 dalej bije 1 Hz - jesli wyswietlacz zgasnie, od razu wiadomo, czy
//  uklad stanal, czy tylko wyswietlacz jest zle podlaczony.
// =====================================================================
case class SevenSegDemoTop(clkFrequency : HertzNumber = 100 MHz) extends Component {

  val io = new Bundle {
    val seg = out Bits (8 bits)
    val en  = out Bits (3 bits)
    val led = out Bits (8 bits)
  }
  noIoPrefix()

  val mux = SevenSegMux(clkFrequency)

  // Podzial na 2 Hz, potem licznik faz: bit 0 = heartbeat 1 Hz,
  // bit 2 = wybor wzorca co 2 s.
  val halfSecond = (clkFrequency / (2 Hz)).toInt
  val prescaler  = Reg(UInt(log2Up(halfSecond) bits)) init (0)
  val tick       = prescaler === halfSecond - 1
  prescaler := tick ? U(0) | (prescaler + 1)

  val phase = Reg(UInt(3 bits)) init (0)
  when(tick) { phase := phase + 1 }

  val allSegments = phase.msb

  // Domyslnie "8.8.8.", warunkowo nadpisane przez "1.23".
  for (i <- 0 until 3) {
    mux.io.digits(i).code := U(8, 4 bits)
    mux.io.digits(i).dot  := True
  }
  when(!allSegments) {
    mux.io.digits(0).code := U(1, 4 bits)
    mux.io.digits(0).dot  := True
    mux.io.digits(1).code := U(2, 4 bits)
    mux.io.digits(1).dot  := False
    mux.io.digits(2).code := U(3, 4 bits)
    mux.io.digits(2).dot  := False
  }

  io.seg := mux.io.seg
  io.en  := mux.io.en

  io.led    := B(0, 8 bits)
  io.led(0) := phase(0)
}

object SevenSegDemoTopVerilog extends App {
  SpinalConfig(
    targetDirectory              = "hw/gen/verilog",
    defaultClockDomainFrequency  = FixedFrequency(100 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(resetKind = BOOT),
    anonymSignalUniqueness       = true
  ).generateVerilog(SevenSegDemoTop())
}
