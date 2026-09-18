package newhope.aht10

import spinal.core._
import spinal.lib._
import spinal.lib.io.InOutWrapper
import newhope.i2c._
import newhope.mimas_v2._

// =====================================================================
//  Odmierzanie okresu pomiaru.
//
//  Po wycofaniu punktu [3] wymagan to zwykly licznik swobodny - nic go
//  nie restartuje, bo przelaczniki nie dotykaja juz sciezki pomiaru.
//
//  periodMs jako parametr, nie stala: testbench ustawia 5 i nie czeka
//  dwoch sekund na kazdy przebieg.
// =====================================================================
case class MeasTimer(clkFrequency : HertzNumber = 100 MHz,
                     periodMs     : Int         = 2000) extends Component {

  val io = new Bundle {
    val trigger = out Bool()
  }

  val cycles = (clkFrequency.toBigDecimal / 1000).toInt * periodMs
  require(cycles >= 2, "okres krotszy niz dwa takty")

  val counter = Reg(UInt(log2Up(cycles) bits)) init (0)
  val done    = counter === cycles - 1
  counter := done ? U(0) | (counter + 1)

  io.trigger := done
}

// =====================================================================
//  Wejscia asynchroniczne: synchronizacja i odszumienie.
//
//  BufferCC jest obowiazkowe - przelacznik nie ma nic wspolnego
//  z zegarem i bez dwoch stopni synchronizacji metastabilnosc
//  propaguje sie w glab ukladu.
//
//  Debounce po wycofaniu [3] nie chroni juz przed wielokrotnym
//  wyzwoleniem pomiaru, ale drgajacy styk daje migotanie wyswietlacza
//  miedzy temperatura a wilgotnoscia. Zostaje.
//
//  INWERSJA: przelaczniki na Mimas V2 zwieraja do masy i maja PULLUP,
//  wiec pozycja ON to poziom NISKI. Odwracamy tutaj, zeby wyzej
//  "wlaczony" znaczylo True.
// =====================================================================
case class SwitchInput(width        : Int,
                       clkFrequency : HertzNumber = 100 MHz,
                       debounceMs   : Int         = 10) extends Component {

  val io = new Bundle {
    val pins  = in  Bits (width bits)
    val value = out Bits (width bits)
  }

  val stableCycles = (clkFrequency.toBigDecimal / 1000).toInt * debounceMs
  require(stableCycles >= 2, "okno debounce krotsze niz dwa takty")

  val synced = ~BufferCC(io.pins, B(0, width bits))

  val candidate = Reg(Bits(width bits)) init (0)
  val stable    = Reg(Bits(width bits)) init (0)
  val counter   = Reg(UInt(log2Up(stableCycles) bits)) init (0)

  when(synced =/= candidate) {
    candidate := synced
    counter   := 0
  } elsewhen (counter =/= stableCycles - 1) {
    counter := counter + 1
  } otherwise {
    stable := candidate
  }

  io.value := stable
}

// =====================================================================
//  ETAP 5 - demo w calosci.
//
//  DP1  temperatura / wilgotnosc
//  DP2  stopnie Celsjusza / Fahrenheita
//
//  Zaden z przelacznikow nie dotyka Aht10Ctrl. Jedna transakcja zwraca
//  oba pomiary, wiec przelaczenie zmienia tylko stale w ScalerDsp -
//  wynik pojawia sie po czterech taktach, bez ruchu na magistrali.
//  To jest wycofany punkt [3] wymagan.
//
//  Przeliczenie uruchamia sie z dwoch powodow: nowa probka albo zmiana
//  przelacznikow. Rejestr surowych wartosci lezy pomiedzy, wiec obie
//  drogi wygladaja tak samo.
// =====================================================================
case class Aht10Demo(clkFrequency : HertzNumber = 100 MHz,
                     periodMs     : Int         = 2000,
                     usePrimitive : Boolean     = true) extends Component {

  val io = new Bundle {
    val pins = master(I2cPins())
    val dp   = in  Bits (2 bits)
    val seg  = out Bits (8 bits)
    val en   = out Bits (3 bits)
    val led  = out Bits (8 bits)
  }
  noIoPrefix()

  val g      = I2cGenerics(clkFrequency = clkFrequency, sclFrequency = 100 kHz)
  val ctrl   = Aht10Ctrl(g)
  val timer  = MeasTimer(clkFrequency, periodMs)
  val sw     = SwitchInput(2, clkFrequency)
  val scaler = ScalerDsp(if (usePrimitive) () => new DspMacPrimitive
                         else              () => new DspMacBehavioral)
  val format = DisplayFormat()
  val mux    = SevenSegMux(clkFrequency)

  io.pins <> ctrl.io.pins
  sw.io.pins   := io.dp
  ctrl.io.trigger := timer.io.trigger

  val showHumidity = sw.io.value(0)
  val fahrenheit   = sw.io.value(1)

  // --- zatrzask surowych pomiarow ---------------------------------------
  val rawT       = Reg(UInt(20 bits)) init (0)
  val rawRh      = Reg(UInt(20 bits)) init (0)
  val haveSample = RegInit(False)
  when(ctrl.io.sample.valid) {
    rawT       := ctrl.io.sample.rawT
    rawRh      := ctrl.io.sample.rawRh
    haveSample := True
  }

  // --- co uruchamia przeliczenie ----------------------------------------
  val switchChanged = sw.io.value =/= RegNext(sw.io.value)
  val sampleDone    = RegNext(ctrl.io.sample.valid) init (False)
  val recompute     = sampleDone || (switchChanged && haveSample)
  val pending       = RegInit(False)
  
  when(recompute)               { pending := True }
  when(scaler.io.cmd.fire)      { pending := False }

  scaler.io.cmd.valid := recompute || pending
  scaler.io.cmd.raw   := showHumidity ? rawRh | rawT
  scaler.io.cmd.mode  := showHumidity ? ScalerMode.humidity |
                         (fahrenheit  ? ScalerMode.fahrenheit | ScalerMode.celsius)

  // --- prezentacja --------------------------------------------------------
  //  Dopoki nie ma probki, wymuszamy blad - inaczej wyswietlacz pokazalby
  //  zera, ktore wygladaja jak zmierzone zero stopni.
  format.io.update.valid := scaler.io.rsp.valid || ctrl.io.status.error
  format.io.update.value := scaler.io.rsp.payload
  format.io.update.error := ctrl.io.status.error || !haveSample

  for (i <- 0 until 3) mux.io.digits(i) := format.io.digits(i)
  io.seg := mux.io.seg
  io.en  := mux.io.en

  // --- diagnostyka ---------------------------------------------------------
  io.led    := B(0, 8 bits)
  io.led(0) := ctrl.io.status.error
  io.led(1) := ctrl.io.status.ready
  io.led(2) := ctrl.io.status.busy
  io.led(3) := ctrl.io.status.calibrated
  io.led(4) := showHumidity
  io.led(5) := fahrenheit
  io.led(7 downto 6) := ctrl.io.status.state(1 downto 0)
}

// ---------------------------------------------------------------------
//  InOutWrapper zamienia ReadableOpenDrain na prawdziwe porty inout -
//  bez tego UCF nie ma czego przypiac do U7 i V7.
// ---------------------------------------------------------------------
object Aht10DemoVerilog extends App {
  SpinalConfig(
    targetDirectory              = "hw/gen/verilog",
    defaultClockDomainFrequency  = FixedFrequency(100 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(resetKind = BOOT),
    anonymSignalUniqueness       = true
  ).generateVerilog(InOutWrapper(Aht10Demo()))
}
