package newhope.mimas_v2

import spinal.core._
import spinal.lib._
import newhope.mimas_v2._

// =====================================================================
//  ETAP 2 - BinToBcd w calym torze.
//
//  Licznik 000..999 odswiezany 10 razy na sekunde. Pelny przebieg
//  trwa 100 s, ale to nie o czekanie chodzi: przeniesienia 9->10 co
//  sekunde i 99->100 co dziesiec sekund sa czesto i to one lamia
//  double dabble przy bledzie w lancuchu przesuwnym.
//
//  Wiodace zera zostaja (007, nie __7). Gaszenie ich to zadanie
//  DisplayFormat w etapie 5; tutaj kazda cyfra ma pokazac, ze umie
//  wyswietlic zero na swojej pozycji.
//
//  D8 zapala sie, gdy cyfra tysiecy jest niezerowa. Przy zakresie
//  0..999 nie powinna nigdy - jesli mrugnie, licznik albo konwerter
//  wyszedl poza zalozony zakres.
// =====================================================================
case class BinToBcdDemoTop(clkFrequency : HertzNumber = 100 MHz) extends Component {

  val io = new Bundle {
    val seg = out Bits (8 bits)
    val en  = out Bits (3 bits)
    val led = out Bits (8 bits)
  }
  noIoPrefix()

  val mux  = SevenSegMux(clkFrequency)
  val conv = BinToBcd(binWidth = 11, bcdDigits = 4)

  // --- podstawa czasu 10 Hz -------------------------------------------
  val tenthCycles = (clkFrequency / (10 Hz)).toInt
  val prescaler   = Reg(UInt(log2Up(tenthCycles) bits)) init (0)
  val tick        = prescaler === tenthCycles - 1
  prescaler := tick ? U(0) | (prescaler + 1)

  // --- licznik ---------------------------------------------------------
  val value = Reg(UInt(11 bits)) init (0)
  val next  = (value === 999) ? U(0, 11 bits) | (value + 1)
  when(tick) { value := next }

  // Konwersja trwa 12 taktow, tick przychodzi co 10 000 000 - cmd.ready
  // nie ma prawa byc niskie. Gdyby bylo, komenda przepadnie po cichu;
  // przy tej dysproporcji nie warto na to wydawac logiki.
  conv.io.cmd.valid   := tick
  conv.io.cmd.payload := next

  // --- zatrzask wyniku -------------------------------------------------
  val shown = Vec.fill(4)(Reg(UInt(4 bits)) init (0))
  when(conv.io.rsp.valid) {
    for (i <- 0 until 4) shown(i) := conv.io.rsp.payload(i)
  }

  // Cyfra 0 to jednosci, wiec na wyswietlaczu ida od prawej.
  mux.io.digits(0).code := shown(2)
  mux.io.digits(1).code := shown(1)
  mux.io.digits(2).code := shown(0)
  mux.io.digits.foreach(_.dot := False)

  io.seg := mux.io.seg
  io.en  := mux.io.en

  // --- diagnostyka -----------------------------------------------------
  val tickCount = Reg(UInt(4 bits)) init (0)
  when(tick) { tickCount := (tickCount === 9) ? U(0, 4 bits) | (tickCount + 1) }

  io.led    := B(0, 8 bits)
  io.led(0) := tickCount < 5          // heartbeat 1 Hz
  io.led(7) := shown(3) =/= 0         // przekroczenie zakresu
}

object BinToBcdDemoTopVerilog extends App {
  SpinalConfig(
    targetDirectory              = "hw/gen/verilog",
    defaultClockDomainFrequency  = FixedFrequency(100 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(resetKind = BOOT),
    anonymSignalUniqueness       = true
  ).generateVerilog(BinToBcdDemoTop())
}
