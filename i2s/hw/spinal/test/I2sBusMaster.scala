package newhope.i2s

import spinal.core.sim._
import scala.collection.mutable
import I2sEvent._

// =====================================================================
//  MODEL MASTERA I2S - druga strona magistrali dla I2sSlave (§2.2).
//
//  Dziala w CZASIE SYMULACJI, nie na zegarze DUT-a: polowki okresu SCK
//  sa w jednostkach czasu (okres zegara slave'a = 10) i nie musza byc
//  wielokrotnoscia okresu, wiec faza SCK wzgledem zegara slave'a dryfuje.
//  Zbocze SCK w tej samej chwili co zbocze zegara jest dozwolone: dla
//  wejscia asynchronicznego kazdy wynik probkowania jest fizycznie
//  mozliwy, a ograniczenia w I2sSlaveGenerics licza najgorszy przypadek.
//
//  Model NIE dekoduje WS - sam generuje sloty, wiec wie, do ktorej pozycji
//  ktorego slotu nalezy kazda probka. Regula Philipsa wprost:
//    TX trailing: f_j (j >= 1) -> pozycja j-1; f0 -> ostatnia pozycja
//                 poprzedniego slotu
//    TX leading : r_j -> pozycja j (probkowana przez slave'a na r_(j+1))
//    RX         : r_j (j >= 1) -> pozycja j-1; r0 -> ostatnia pozycja
//                 poprzedniego slotu
//  SDI slave'a probkowane jest w chwili narastajacego zbocza, przed jego
//  wystawieniem - to jest prawdziwy warunek setupu, ktorego monitor
//  probkujacy zegarem DUT-a by nie zobaczyl.
//
//  Po starcie: jeden pusty prawy slot (WS=1), potem ramki. Ramka k to
//  frames(k); frames(k).received wypelnia sie na r0 ramki k+1.
// =====================================================================
object I2sBusMaster {
  /** Opoznienie wyjsc modelu (t_d) po jego wlasnym zboczu SCK. */
  val outDelay = 1

  /** Przeniesione do I2sFormat (main), bo jest czescia kontraktu
    * vertebra-hil. Alias zostaje, zeby `import I2sBusMaster._` w suitach
    * dalej widzial transfer - implementacja jest jedna. */
  def transfer(v : Long, fromW : Int, len : Int, toW : Int) : Long =
    I2sFormat.transfer(v, fromW, len, toW)

  class BusFrame(val idx       : Int,
                 val sent      : Option[Frame],   // None = cisza
                 val wordWidth : Int,
                 val lenL      : Int,
                 val lenR      : Int) {
    var received : Option[Frame] = None           // odebrane z SDI slave'a
    override def toString = s"#$idx sent=$sent ww=$wordWidth L$lenL/R$lenR recv=$received"
  }
}

class I2sBusMaster(pins : I2sPins, sckHalf : Int, slotWidth : Int, width : Int) {
  import I2sBusMaster._

  // --- konfiguracja; zmiana dziala od nastepnego bitu / slotu / ramki ----
  var lowLen      : () => Int = () => sckHalf
  var highLen     : () => Int = () => sckHalf
  var slotLen     : () => Int = () => slotWidth
  var wordWidth   : Int       = width      // slowo NADAWANE przez model
  var rxWidth     : Int       = width      // slowo ODBIERANE przez model
  var padFill     : Boolean   = false
  var leadingEdge : Boolean   = false

  // --- wyniki --------------------------------------------------------------
  val frames = mutable.ArrayBuffer[BusFrame]()
  private val pending    = mutable.Queue[Frame]()
  private val violations = mutable.ArrayBuffer[String]()
  private var _inRight   = true

  def inRight : Boolean = _inRight
  /** Indeks ramki w toku; -1 w slocie wstepnym. */
  def curIdx  : Int     = frames.size - 1

  def send(fs : Frame*) : Unit = pending ++= fs
  /** Ramki z send(), ktore nie weszly jeszcze na magistrale. */
  def pendingCount      : Int  = pending.size
  def dropPending()     : Unit = pending.clear()
  /** Reset slave'a zeruje SDI asynchronicznie, co moze wypasc przy wysokim
    * SCK - po celowym resecie naruszenia sprzed niego sa bez znaczenia. */
  def clearViolations() : Unit = violations.clear()

  def check() : Unit =
    assert(violations.isEmpty,
      s"${violations.size} naruszen po stronie slave'a, pierwsze:\n  " +
      violations.take(5).mkString("\n  "))

  // --- stan miedzy slotami -------------------------------------------------
  private var prevTx    : Array[Boolean]   = Array(false)
  private var prevRx    : Array[Boolean]   = Array(false)
  private var prevCh    : Boolean          = true
  private var prevFrame : Option[BusFrame] = None
  private var leftWord  : Long             = 0L

  private def bits(v : Long, len : Int) : Array[Boolean] = {
    val ww = wordWidth
    Array.tabulate(len)(p => if (p < ww) ((v >> (ww - 1 - p)) & 1L) == 1L else padFill)
  }

  private def word(rx : Array[Boolean]) : Long =
    (0 until rxWidth).foldLeft(0L)((a, p) => (a << 1) | (if (p < rx.length && rx(p)) 1L else 0L))

  private def finishPrev() : Unit = {
    val v = word(prevRx)
    if (!prevCh) leftWord = v
    else prevFrame.foreach(_.received = Some(Frame(leftWord, v)))
  }

  private def runSlot(ch : Boolean, len : Int, tx : Array[Boolean], frame : Option[BusFrame]) : Unit = {
    val rx = new Array[Boolean](len)
    for (j <- 0 until len) {
      val lo = lowLen(); val hi = highLen()
      assert(lo > outDelay && hi > outDelay, s"polowka SCK $lo/$hi <= outDelay")

      // --- opadajace SCK
      pins.sck #= false
      sleep(outDelay)
      if (j == 0) pins.ws #= ch
      if (!leadingEdge) pins.sdo #= (if (j == 0) prevTx.last else tx(j - 1))
      sleep(lo - outDelay)

      // --- narastajace SCK: najpierw probka SDI, potem zbocze
      val s = pins.sdi.toBoolean
      if (j == 0) { prevRx(prevRx.length - 1) = s; finishPrev() }
      else rx(j - 1) = s
      pins.sck #= true
      if (leadingEdge) { sleep(outDelay); pins.sdo #= tx(j); sleep(hi - outDelay) }
      else sleep(hi)

      // Tuz przed nastepnym opadajacym: SDI slave'a nie moglo sie ruszyc.
      if (pins.sdi.toBoolean != s)
        violations += s"ramka ${frame.map(_.idx).getOrElse(-1)} ${if (ch) "R" else "L"} " +
                      s"bit $j: SDI zmienione przy wysokim SCK"
    }
    prevTx = tx; prevRx = rx; prevCh = ch; prevFrame = frame
  }

  def start() : Unit = fork {
    pins.sck #= false; pins.ws #= true; pins.sdo #= false

    val l0 = slotLen()
    runSlot(ch = true, l0, Array.fill(l0)(false), None)      // slot wstepny

    while (true) {
      val lenL = slotLen(); val lenR = slotLen()
      val f    = if (pending.nonEmpty) Some(pending.dequeue()) else None
      val bf   = new BusFrame(frames.size, f, wordWidth, lenL, lenR)
      frames += bf
      _inRight = false
      runSlot(ch = false, lenL, bits(f.fold(0L)(_.left),  lenL), Some(bf))
      _inRight = true
      runSlot(ch = true,  lenR, bits(f.fold(0L)(_.right), lenR), Some(bf))
    }
  }
}
