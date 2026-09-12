package newhope.aht10

import spinal.core._
import spinal.lib._

// =====================================================================
//  ETAP 3 - ScalerDsp na plytce.
//
//  Cztery surowe wartosci razy trzy tryby, po 2 s na kombinacje, pelna
//  petla 24 s. Wartosci dobrane tak, zeby odczyt dalo sie sprawdzic
//  bez kalkulatora - tabela w etap3-README.
//
//  Format tymczasowy: zawsze XX.X, bez gaszenia wiodacych zer i bez
//  znaku na wyswietlaczu. Znak i przekroczenie zakresu ida na LED-y.
//  Pelny format to zadanie DisplayFormat w etapie 5 - tutaj celem jest
//  zobaczyc liczbe, nie wyswietlic ja ladnie.
//
//  DSP: buildMac decyduje, czy w srodku jest model, czy prymityw.
//  Dla etapu 3 chcemy prymityw - o to chodzi w calym cwiczeniu.
// =====================================================================
case class ScalerDspDemoTop(clkFrequency : HertzNumber = 100 MHz,
                            usePrimitive : Boolean     = true) extends Component {

  val io = new Bundle {
    val seg = out Bits (8 bits)
    val en  = out Bits (3 bits)
    val led = out Bits (8 bits)
  }
  noIoPrefix()

  val mux    = SevenSegMux(clkFrequency)
  val scaler = ScalerDsp(if (usePrimitive) () => new DspMacPrimitive
                         else              () => new DspMacBehavioral)
  // 12 bitow, nie 11: pelna skala w Fahrenheitach to 3020, a 11 bitow
  // konczy sie na 2047. W finalnym projekcie zakres czujnika daje max
  // 1850, ale przekrecony odczyt z I2C nie zna zakresu czujnika.
  val conv = BinToBcd(binWidth = 12, bcdDigits = 4)

  // --- podstawa czasu 0.5 Hz -------------------------------------------
  val stepCycles = (clkFrequency / (0.5 Hz)).toInt
  val prescaler  = Reg(UInt(log2Up(stepCycles) bits)) init (0)
  val tick       = prescaler === stepCycles - 1
  prescaler := tick ? U(0) | (prescaler + 1)

  // --- sekwencer: tryb szybciej, wartosc wolniej ------------------------
  val modeIdx = Reg(UInt(2 bits)) init (0)
  val valIdx  = Reg(UInt(2 bits)) init (0)
  when(tick) {
    when(modeIdx === 2) {
      modeIdx := 0
      valIdx  := valIdx + 1
    } otherwise {
      modeIdx := modeIdx + 1
    }
  }

  val rawTable = Vec(U(0,       20 bits),   // -50.0 C / -58.0 F /   0.0 %
                     U(262144,  20 bits),   //   0.0 C /  32.0 F /  25.0 %
                     U(393216,  20 bits),   //  25.0 C /  77.0 F /  37.5 %
                     U(1048575, 20 bits))   // 150.0 C / 302.0 F / 100.0 %

  val mode = ScalerMode()
  switch(modeIdx) {
    is(0)   { mode := ScalerMode.celsius }
    is(1)   { mode := ScalerMode.fahrenheit }
    default { mode := ScalerMode.humidity }
  }

  // Konwersja startuje takt PO ticku, kiedy modeIdx i valIdx maja juz
  // nowe wartosci - inaczej pokazywalibysmy poprzedni krok.
  scaler.io.cmd.valid := RegNext(tick) init (False)
  scaler.io.cmd.raw   := rawTable(valIdx)
  scaler.io.cmd.mode  := mode

  // --- wartosc bezwzgledna do BCD --------------------------------------
  val result = Reg(SInt(ScalerDsp.resultWidth bits)) init (0)
  when(scaler.io.rsp.valid) { result := scaler.io.rsp.payload }

  val negative  = result < 0
  val magnitude = (negative ? (-result) | result).asUInt.resize(12)

  conv.io.cmd.valid   := RegNext(scaler.io.rsp.valid) init (False)
  conv.io.cmd.payload := magnitude

  val shown = Vec.fill(4)(Reg(UInt(4 bits)) init (0))
  when(conv.io.rsp.valid) {
    for (i <- 0 until 4) shown(i) := conv.io.rsp.payload(i)
  }

  // --- wyswietlacz: zawsze XX.X ----------------------------------------
  mux.io.digits(0).code := shown(2)
  mux.io.digits(0).dot  := False
  mux.io.digits(1).code := shown(1)
  mux.io.digits(1).dot  := True     // kropka dziesietna
  mux.io.digits(2).code := shown(0)
  mux.io.digits(2).dot  := False

  io.seg := mux.io.seg
  io.en  := mux.io.en

  // --- diagnostyka ------------------------------------------------------
  io.led    := B(0, 8 bits)
  io.led(0) := prescaler.msb                    // wolne bicie, uklad zyje
  io.led(2) := valIdx === 0                     // pierwsza wartosc z tabeli
  io.led(3) := mode === ScalerMode.celsius
  io.led(4) := mode === ScalerMode.fahrenheit
  io.led(5) := mode === ScalerMode.humidity
  io.led(6) := negative
  io.led(7) := shown(3) =/= 0                   // wartosc ma cyfre tysiecy
}

object ScalerDspDemoTopVerilog extends App {
  SpinalConfig(
    targetDirectory              = "hw/gen/verilog",
    defaultClockDomainFrequency  = FixedFrequency(100 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(resetKind = BOOT),
    anonymSignalUniqueness       = true
  ).generateVerilog(ScalerDspDemoTop(usePrimitive = true))
}
