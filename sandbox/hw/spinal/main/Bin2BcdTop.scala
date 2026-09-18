package newhope.sandbox

import spinal.core._
import spinal.lib._

case class SevenSegDigit() extends Bundle {
  val code = UInt(4 bits)
  val dot  = Bool()
}

case class SevenSegMux(clkFrequency : HertzNumber = 100 MHz,
                       frameRate    : HertzNumber =   1 kHz,
                       blankCycles  : Int         =  64)
                       extends Component {
val io = new Bundle {
  val digits   = in Vec(SevenSegDigit(), 3)
  val segCode  = out Bits(8 bits)
  val pod      = out Bits(3 bits)
}

}
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
}

object BinToBcdDemoTopVerilog extends App {
  SpinalConfig(
    targetDirectory              = "hw/gen/verilog",
    defaultClockDomainFrequency  = FixedFrequency(100 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(resetKind = BOOT),
    anonymSignalUniqueness       = true
  ).generateVerilog(BinToBcdDemoTop())
}
