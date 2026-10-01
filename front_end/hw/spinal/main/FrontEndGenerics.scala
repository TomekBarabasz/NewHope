package newhope.frontend

import spinal.core._

// =====================================================================
//  Parametry N0 (I2S RX z INMP441) i N1 (filtr DC).
//
//  Jedno zrodlo prawdy o parametrach, wartosciach pochodnych
//  i predykatach legalnosci (TESTING-STRATEGY §4.3). Z tych samych
//  definicji korzysta RTL, golden model i testy bez symulacji.
// =====================================================================

/** N0 · I2S RX. Odbiornik jest masterem: sam generuje SCK i WS.
  *
  * Ramka I2S: 2 sloty po `slotBits` taktow SCK, WS = 0 to lewy kanal.
  * INMP441 (L/R = GND) nadaje w lewym slocie 24-bitowe slowo MSB-first,
  * z opoznieniem jednego taktu SCK wzgledem zmiany WS, dane zmienia na
  * zboczu opadajacym, odbiornik probkuje na narastajacym.
  *
  * Wyjscie: gorne `sampleWidth` bitow slowa, czyli slowo24[23:6] dla 18.
  * Obciecie, nie zaokraglenie (kontrakt, wejscie audio).
  */
case class I2sMicGenerics(clockHz    : Long    = 75000000L,  // c3_clk0 (D-006)
                          bclkDiv    : Int     = 73,         // BCLK = 75 MHz / 73
                          slotBits   : Int     = 32,
                          wordBits   : Int     = 24,
                          sampleWidth: Int     = 18,
                          leftChannel: Boolean = true,
                          syncStages : Int     = 2) {
  def frameBits       = 2 * slotBits
  def sckLow          = bclkDiv / 2              // przy nieparzystym dzielniku SCK
  def sckHigh         = bclkDiv - sckLow         // jest o cykl dluzej wysoko
  def cyclesPerSample = bclkDiv.toLong * frameBits
  def fs              = clockHz.toDouble / cyclesPerSample
  def bclkHz          = clockHz.toDouble / bclkDiv
  def dropBits        = wordBits - sampleWidth

  /** Odstrojenie wysokosci wzgledem nominalnych 16 kHz, w centach. */
  def cents(nominal: Double = 16000.0) = 1200 * math.log(fs / nominal) / math.log(2)

  /** Narastajace zbocze SCK wypada sckLow cykli po opadajacym. SD przechodzi
    * przez synchronizator (syncStages), a model mikrofonu w symulacji widzi
    * zbocze z opoznieniem jednego cyklu: stad syncStages + 2. Na plytce
    * zapas jest ogromny (36 cykli = 480 ns wobec opoznienia danych INMP441
    * rzedu kilkudziesieciu ns). */
  def sampleMargin = sckLow - (syncStages + 2)

  def problems: Seq[String] = Seq(
    isPow2(slotBits)                         -> s"slotBits = $slotBits nie jest potega 2",
    (wordBits + 1 <= slotBits)               -> s"slowo $wordBits b + bit opoznienia I2S nie miesci sie w slocie $slotBits",
    (sampleWidth >= 2 && sampleWidth <= wordBits) -> s"sampleWidth = $sampleWidth poza [2, $wordBits]",
    (syncStages >= 2)                        -> s"syncStages = $syncStages < 2",
    (sampleMargin >= 0)                      -> s"bclkDiv = $bclkDiv za maly: SD probkowane $sckLow cykli po zboczu, potrzeba ${syncStages + 2}"
  ).collect { case (false, msg) => msg }
  def isLegal = problems.isEmpty

  /** Zakres pracy INMP441 (nota: fs 7,8-50 kHz przy SCK = 64 fs). Osobno
    * od isLegal, bo konfiguracje testowe celowo z niego wychodza. */
  def micInRange = slotBits == 32 && fs >= 7800 && fs <= 50000
}

/** Jeden skladnik (1 - a) = sum(sign * 2^-shift). */
case class DcTerm(sign: Int, shift: Int) {
  require(sign == 1 || sign == -1, s"sign = $sign")
  def value = sign * math.pow(2, -shift)
}

/** N1 · Filtr DC: y[n] = x[n] - x[n-1] + a * y[n-1], a = 1 - 2*pi*fc/fs.
  *
  * Stalego `a` nie mnozymy: 1 - a rozkladamy na `maxTerms` poteg dwojki
  * ze znakiem (CSD), wiec a * y = y - sum(sign * (y >> s)). Przy fs =
  * 16 053 Hz i 2 skladnikach: 1 - a = 2^-6 - 2^-8, fc = 29,94 Hz. Zero
  * DSP48A1 (kontrakt nie przewiduje mnoznika dla N1, budzet DSP ciasny).
  *
  * Arytmetyka w liczbach calkowitych, LSB stanu = LSB probki * 2^-G:
  *   D    = (x - x1) << G
  *   Y    = D + Y1 - sum(sign_i * roundShr(Y1, s_i))
  *   y    = sat_SW(roundShr(Y, G))
  * roundShr = przesuniecie arytmetyczne z zaokragleniem polowy w gore,
  * jak w newhope.fft.
  *
  * Po co G: bez bitow ochronnych zaokraglenie w petli sprzezenia tworzy
  * cykl graniczny - staly offset na wejsciu zostawia ~31 LSB DC na
  * wyjsciu (pomiar golden, G = 0). Przy G = 8 blad wobec float < 0,6 LSB.
  */
case class DcGenerics(sampleRate   : Double    = I2sMicGenerics().fs,
                      cutoffHz     : Double    = 30.0,
                      sampleWidth  : Int       = 18,
                      guardBits    : Int       = 8,
                      maxTerms     : Int       = 2,
                      cutoffTol    : Double    = 0.02,
                      termsOverride: Seq[DcTerm] = Nil) {
  def oneMinusAIdeal = 2 * math.Pi * cutoffHz / sampleRate
  def terms: Seq[DcTerm] =
    if (termsOverride.nonEmpty) termsOverride else Dc.csd(oneMinusAIdeal, maxTerms)
  def oneMinusA = terms.map(_.value).sum
  def a         = 1 - oneMinusA
  def cutoffEff = oneMinusA * sampleRate / (2 * math.Pi)
  def tau       = 1 / oneMinusA          // stala czasowa w probkach
  def accWidth  = sampleWidth + 3 + guardBits

  /** Wejscie valid -> wyjscie valid, w cyklach (etapy A, B, C, wyjscie). */
  def latency    = 4
  /** Rekurencja ma dwa takty (B, C), wiec probki co najmniej co 2 cykle. */
  def minSpacing = 2

  /** |Y| <= ||h||_1 * max|x| + blad zaokraglen. Dla 0 < a < 1 odpowiedz
    * impulsowa h = [1, -(1-a), -(1-a)a, ...] ma ||h||_1 = 2. Blad: najwyzej
    * 1/2 LSB stanu na skladnik i krok, wzmocniony przez 1/(1-a). */
  def accBound: BigInt =
    (BigInt(2) << (sampleWidth - 1 + guardBits)) +
      BigInt(math.ceil(terms.size * 0.5 / oneMinusA).toLong + 1)

  def problems: Seq[String] = Seq(
    (sampleWidth >= 4)                          -> s"sampleWidth = $sampleWidth < 4",
    (guardBits >= 0 && guardBits <= 16)         -> s"guardBits = $guardBits poza [0, 16]",
    (cutoffHz > 0 && cutoffHz < sampleRate / 8) -> s"cutoffHz = $cutoffHz poza (0, fs/8)",
    terms.nonEmpty                              -> "brak skladnikow 1 - a",
    terms.forall(t => t.shift >= 1 && t.shift < accWidth - 1) -> s"przesuniecia ${terms.map(_.shift)} poza [1, ${accWidth - 2}]",
    (oneMinusA > 0 && oneMinusA < 0.5)          -> f"1 - a = $oneMinusA%.6f poza (0; 0,5)",
    (math.abs(cutoffEff - cutoffHz) <= cutoffTol * cutoffHz) ->
      f"odciecie $cutoffEff%.2f Hz zamiast $cutoffHz Hz (zwieksz maxTerms)",
    (accBound < (BigInt(1) << (accWidth - 1)))  -> s"akumulator $accWidth b za waski na $accBound"
  ).collect { case (false, msg) => msg }
  def isLegal = problems.isEmpty
}

object Dc {
  /** Zachlanny rozklad v na sume poteg dwojki ze znakiem: za kazdym razem
    * najblizsza potega reszty. Dla 0,011742 daje 2^-6 - 2^-8. */
  def csd(v: Double, maxTerms: Int, maxShift: Int = 24): Seq[DcTerm] = {
    val out = scala.collection.mutable.ArrayBuffer[DcTerm]()
    var r = v
    while (out.size < maxTerms && r != 0 && !r.isNaN) {
      val s = math.max(1, math.min(maxShift, math.round(-math.log(math.abs(r)) / math.log(2)).toInt))
      val t = DcTerm(if (r > 0) 1 else -1, s)
      out += t
      r -= t.value
    }
    out.toList
  }
}

/** N0 + N1 razem: to, co trafia na wejscie Framera (`Rfft.io.input`). */
case class FrontEndGenerics(i2s: I2sMicGenerics, dc: DcGenerics) {
  def problems: Seq[String] = i2s.problems ++ dc.problems ++ Seq(
    (i2s.sampleWidth == dc.sampleWidth) -> s"szerokosc probki: I2S ${i2s.sampleWidth}, DC ${dc.sampleWidth}",
    (math.abs(dc.sampleRate / i2s.fs - 1) < 1e-3) ->
      f"DcGenerics.sampleRate = ${dc.sampleRate}%.1f Hz, a I2S daje ${i2s.fs}%.1f Hz",
    (i2s.cyclesPerSample >= dc.minSpacing) -> "probki z I2S szybciej niz przyjmuje filtr DC"
  ).collect { case (false, msg) => msg }
  def isLegal = problems.isEmpty
}

object FrontEndGenerics {
  /** fs filtra DC brane z dzielnika I2S, nie z nominalnych 16 kHz. */
  def apply(i2s: I2sMicGenerics): FrontEndGenerics =
    FrontEndGenerics(i2s, DcGenerics(sampleRate = i2s.fs, sampleWidth = i2s.sampleWidth))

  /** Mimas V2: 75 MHz / 73 / 64 = 16 053 Hz (+0,33%, +5,7 centa). */
  val mimas = FrontEndGenerics(I2sMicGenerics())
}
