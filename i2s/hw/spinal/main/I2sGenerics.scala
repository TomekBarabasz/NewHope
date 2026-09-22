package newhope.i2s

import spinal.core._

// =====================================================================
//  Parametry I2S mastera. Jedyne zrodlo prawdy o podzielnikach
//  (TESTING-STRATEGY §4.3) - testbench NIE liczy ich po swojemu.
//
//  Lancuch, wszystko w cyklach zegara systemowego:
//    fSCK    = fs * 2 * slotWidth        (dwa kanaly, jeden slot na kanal)
//    halfDiv = fclk / (2 * fSCK)         (cykle na polokres SCK)
//
//  halfDiv MUSI wyjsc calkowite. Master nie ma ulamkowego dzielnika, wiec
//  konfiguracja nieprzystajaca daje inne fs niz zadeklarowane, a test
//  porownujacy okres ze wzorem testowalby zaokraglenie, nie DUT-a (§4.2).
//
//  Brak `require` w konstruktorze celowo: nielegalna konfiguracja ma dac
//  sie wpisac do `configs` i zostac zgloszona przez i2s_param_bounds
//  z nazwa i powodem, a nie wyjatkiem przy inicjalizacji suity.
// =====================================================================
case class I2sGenerics(clkFreq    : HertzNumber,
                       sampleRate : HertzNumber,
                       width      : Int = 16,     // bity probki
                       slotWidth  : Int = 32) {   // okresy SCK na kanal

  val sckFreq      : BigDecimal = sampleRate.toBigDecimal * 2 * slotWidth
  val halfDivExact : BigDecimal = clkFreq.toBigDecimal / (sckFreq * 2)

  def dividerExact : Boolean = halfDivExact.isWhole
  def widthFits    : Boolean = width >= 1 && width <= slotWidth
  def isLegal      : Boolean = dividerExact && halfDivExact >= 1 && widthFits

  def halfDiv : Int = {
    require(dividerExact, s"halfDiv niecalkowite ($halfDivExact) dla $this")
    halfDivExact.toIntExact
  }

  def cyclesPerSlot  : Int = 2 * halfDiv * slotWidth
  def cyclesPerFrame : Int = 2 * cyclesPerSlot
  def paddingBits    : Int = slotWidth - width
}
