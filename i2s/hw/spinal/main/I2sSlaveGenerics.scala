package newhope.i2s

// =====================================================================
//  Parametry I2S slave'a (§4.3). Slave nie ma dzielnika ani fs - SCK i WS
//  dostaje z magistrali, asynchronicznie wzgledem wlasnego zegara, i
//  nadprobkowuje je przez synchronizatory. Jedynym ograniczeniem jest
//  wiec minimalny polokres SCK mierzony w cyklach zegara slave'a.
//
//  TX (slave -> master), najgorszy przypadek od opadajacego SCK na pinie
//  do nowego bitu na pinie:
//    pierwszy stopien lapie zbocze najpozniej po 1 cyklu (zbocze tuz po
//    probkowaniu), kazdy nastepny stopien +1, rejestr wyjsciowy +1
//      txLatencyCycles = syncStages + 1
//  Master probkuje na narastajacym SCK, polokres pozniej, wiec
//      polokres SCK > txLatencyCycles   (ostro)
//
//  RX (master -> slave): slave bierze SD z OSTATNIEJ probki, w ktorej SCK
//  byl jeszcze niski. Ta probka lezy do 1 cyklu przed zboczem, wiec:
//      setup nadajnika >= 1 cykl zegara slave'a, hold >= 0.
//  Hold 0 znaczy, ze dziala tez nadajnik taktujacy SD narastajacym SCK,
//  co specyfikacja dopuszcza. Probka "w chwili wykrycia zbocza" wymagalaby
//  holdu >= 1 cykl i taki nadajnik by psula.
// =====================================================================
case class I2sSlaveGenerics(width      : Int = 16,
                            syncStages : Int = 2) {

  def txLatencyCycles : Int = syncStages + 1
  def rxSetupCycles   : Int = 1

  def isLegal : Boolean = width >= 2 && width <= 32 && syncStages >= 2

  /** Czy slave nadazy z TX przy polokresie SCK `halfCycles` (w cyklach
    * zegara slave'a, moze byc ulamkowy - magistrala jest asynchroniczna). */
  def supportsSckHalf(halfCycles : BigDecimal) : Boolean = halfCycles > txLatencyCycles
}
