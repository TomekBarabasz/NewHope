package newhope.i2s

import spinal.core._

// =====================================================================
//  Parametry I2S mastera. Jedyne zrodlo prawdy o podzielnikach
//  (TESTING-STRATEGY §4.3) - testbench NIE liczy ich po swojemu.
//
//  Lancuch, wszystko w cyklach zegara systemowego:
//    fSCK    = fs * 2 * slotWidth        (dwa kanaly, jeden slot na kanal)
//    sckDiv  = fclk / fSCK               (cykle na pelny okres SCK)
//    sckLow  = ceil(sckDiv / 2)          (SCK nisko: dluzsza polowka)
//    sckHigh = floor(sckDiv / 2)
//
//  sckDiv MUSI wyjsc calkowite. Master nie ma ulamkowego dzielnika, wiec
//  konfiguracja nieprzystajaca daje inne fs niz zadeklarowane, a test
//  porownujacy okres ze wzorem testowalby zaokraglenie, nie DUT-a (§4.2).
//  Nieparzysty sckDiv jest legalny: SCK nisko o cykl dluzej niz wysoko.
//  Dluzsza jest faza niska, bo w niej nadajnik zmienia SD, a odbiornik
//  probkuje na narastajacym SCK - to okno setupu po obu stronach.
//
//  Brak `require` w konstruktorze celowo: nielegalna konfiguracja ma dac
//  sie wpisac do `configs` i zostac zgloszona przez i2s_param_bounds
//  z nazwa i powodem, a nie wyjatkiem przy inicjalizacji suity.
// =====================================================================
case class I2sGenerics(clkFreq    : HertzNumber,
                       sampleRate : HertzNumber,
                       width      : Int = 16,     // bity probki
                       slotWidth  : Int = 32) {   // okresy SCK na kanal

  val sckFreq     : BigDecimal = sampleRate.toBigDecimal * 2 * slotWidth
  val sckDivExact : BigDecimal = clkFreq.toBigDecimal / sckFreq

  // fs podane od strony dzielnika (fromSckDiv) bywa nieskonczonym
  // ulamkiem dziesietnym, np. 75 MHz / 4672, ktory BigDecimal zaokragla do
  // 34 cyfr; sckDivExact wychodzi wtedy 72,999...99 zamiast 73.
  // Tolerancja 1e-20 lapie tylko ten szum zaokraglenia (rzad 1e-32), a
  // nadal odrzuca kazde fs, ktore naprawde nie daje calkowitego dzielnika
  // (44,1 kHz przy 75 MHz: 26,57).
  private val wholeTol      : BigDecimal = BigDecimal("1e-20")
  private def sckDivRounded : BigDecimal = sckDivExact.setScale(0, BigDecimal.RoundingMode.HALF_EVEN)

  def dividerExact : Boolean = (sckDivExact - sckDivRounded).abs <= wholeTol
  def widthFits    : Boolean = width >= 1 && width <= slotWidth
  def isLegal      : Boolean = dividerExact && sckDivExact >= 2 - wholeTol && widthFits

  def sckDiv : Int = {
    require(dividerExact, s"sckDiv niecalkowite ($sckDivExact) dla $this")
    sckDivRounded.toIntExact
  }
  def sckLow  : Int = (sckDiv + 1) / 2
  def sckHigh : Int = sckDiv / 2

  def cyclesPerSlot  : Int = sckDiv * slotWidth
  def cyclesPerFrame : Int = 2 * cyclesPerSlot
  def paddingBits    : Int = slotWidth - width
}

object I2sGenerics {
  /** Konfiguracja od strony dzielnika: fs = clkFreq / (2 * slotWidth * sckDiv).
    * Dla zegara, ktory nie dzieli sie rowno na zadane fs (Mimas: 75 MHz,
    * sckDiv 73 -> 16 053,08... Hz), to jedyny sposob na legalne generyki. */
  def fromSckDiv(clkFreq : HertzNumber, sckDiv : Int, width : Int = 16, slotWidth : Int = 32) : I2sGenerics = {
    require(sckDiv >= 1 && slotWidth >= 1, s"fromSckDiv: sckDiv=$sckDiv slotWidth=$slotWidth")
    I2sGenerics(clkFreq, HertzNumber(clkFreq.toBigDecimal / (2 * slotWidth * sckDiv)), width, slotWidth)
  }
}
