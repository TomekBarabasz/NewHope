package newhope.aht10

import spinal.core._

// =====================================================================
//  ETAP 0 - szkielet projektu i weryfikacja toolchainu.
//
//  Ten modul nie ma nic wspolnego z docelowym demem. Jego jedyne
//  zadanie: przejsc cala droge SpinalHDL -> Verilog -> ISE -> .bin ->
//  plytka i pokazac na LED-ach trzy rzeczy naraz:
//
//   1. czy bitstream w ogole sie zaladowal      (cokolwiek sie swieci)
//   2. jaka jest polaryzacja LED-ow             (jeden zapalony vs siedem)
//   3. czy zegar ma faktycznie 100 MHz          (heartbeat mierzalny zegarkiem)
//
//  Punkt 3 jest najwazniejszy. Jesli oscylator, constraint albo zalozenie
//  o czestotliwosci sie nie zgadza, kazdy pozniejszy timing (75 ms na
//  pomiar, 100 kHz na SCL, 1 kHz na multiplekser) bedzie przesuniety o
//  ten sam czynnik - i bedzie to wygladalo na blad protokolu.
// =====================================================================
case class BlinkTop(clkFrequency : HertzNumber = 100 MHz,
                    stepRate     : HertzNumber = 8 Hz) extends Component {

  val io = new Bundle {
    /** Bit 0 = D1 (LED1 na sitodruku), bit 7 = D8.
      * UWAGA: referencyjny UCF Numato numeruje odwrotnie (LED[7] = D1).
      * Tutaj indeks idzie zgodnie z sitodrukiem - patrz mimas_v2.ucf. */
    val led = out Bits (8 bits)
  }
  noIoPrefix()   // porty nazywaja sie `led`, nie `io_led` - UCF bedzie czytelny

  val cyclesPerStep = (clkFrequency / stepRate).toInt
  assert(cyclesPerStep >= 2, "stepRate za wysoki wzgledem zegara")

  // -------------------------------------------------------------------
  //  Podzial zegara. Jeden licznik, dwa uzytkownicy - swiatlo biegnace
  //  (8 krokow/s) i heartbeat (1 Hz, czyli co 8 krokow polowa okresu).
  // -------------------------------------------------------------------
  val prescaler = Reg(UInt(log2Up(cyclesPerStep) bits)) init (0)
  val step      = prescaler === cyclesPerStep - 1
  prescaler := step ? U(0) | (prescaler + 1)

  // Swiatlo biegnace na D2..D8. Jeden zapalony bit wedruje w lewo.
  val pattern = Reg(Bits(7 bits)) init (1)
  when(step) { pattern := pattern.rotateLeft(1) }

  // Heartbeat na D1. 8 krokow na sekunde, wiec MSB licznika krokow
  // przelacza sie co 0.5 s -> dokladnie 1 Hz.
  val stepIndex = Reg(UInt(3 bits)) init (0)
  when(step) { stepIndex := stepIndex + 1 }
  val heartbeat = stepIndex.msb

  io.led := pattern ## heartbeat
}

// =====================================================================
//  Generacja RTL.
//
//  resetKind = BOOT: Mimas V2 nie ma dedykowanego przycisku resetu, a
//  Spartan-6 wspiera GSR - wszystkie `Reg init()` staja sie atrybutami
//  INIT i uklad startuje w znanym stanie zaraz po konfiguracji. Dzieki
//  temu top nie ma portu `reset` i UCF jest o jeden pin krotszy.
//
//  Jesli XST zignoruje wartosci poczatkowe (sprawdz w raporcie .syr:
//  "INIT" przy rejestrach), trzeba wrocic do SYNC i przypisac reset do
//  jednego z szesciu przyciskow.
// =====================================================================
object BlinkTopVerilog extends App {
  SpinalConfig(
    targetDirectory              = "hw/gen/verilog",
    defaultClockDomainFrequency  = FixedFrequency(100 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(resetKind = BOOT),
    anonymSignalUniqueness       = true
  ).generateVerilog(BlinkTop())
}
