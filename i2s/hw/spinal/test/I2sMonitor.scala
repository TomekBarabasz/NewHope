package newhope.i2s

import spinal.core.sim._
import scala.collection.mutable
import I2sEvent._

// =====================================================================
//  MONITOR I2S - czyta WYLACZNIE piny sck/ws/sdo, na zegarze DUT-a.
//  Nie zna io.tx ani stanu DUT-a; to jest druga, niezalezna
//  implementacja reguly Philipsa (§2.2, "FooMonitor").
//
//  REGULA DEKODOWANIA
//  Bit probkowany na narastajacym SCK nalezy do kanalu, ktory WS
//  wskazywal na POPRZEDNIM narastajacym SCK. Wynika to wprost z formatu:
//  WS zmienia sie przy opadajacym SCK f0, a MSB nowego slowa wychodzi
//  dopiero przy f1. Na r0 (miedzy f0 i f1) WS ma juz nowa wartosc, ale
//  SD niesie jeszcze LSB poprzedniego slowa.
//  Ta sama regula poprawnie dekoduje width == slotWidth, gdzie LSB
//  wpada w slot drugiego kanalu. Monitor liczacy bity "od zbocza WS"
//  zgubilby tam LSB tak samo jak zepsuty DUT.
//
//  PROBKOWANIE
//  Po kazdym aktywnym zboczu czytamy piny. Kolejne probki to wartosci
//  pinow w kolejnych cyklach. SCK/WS/SDO sa wyjsciami rejestrow na tym
//  samym zegarze, wiec nie ma tu filtra, okna ani granicy slepoty jak
//  w I2cMonitor. Jedyna granica to halfDiv >= 1, pilnowana w
//  i2s_param_bounds.
//
//  RESET
//  Pod resetem monitor porzuca stan czastkowy (slowo, slot, pomiar
//  polokresu) i czeka na nastepne opadajace WS, ale NIE kasuje historii
//  ani naruszen. Czysci je dopiero resync(), wolany z bariery Env.resync.
// =====================================================================
object I2sMonitor {
  /** Surowy slot: probki SDO na narastajacych SCK przy stalym WS.
    * bits(0) = LSB poprzedniego slowa, bits(1..width) = slowo, reszta
    * to padding (jego ostatni bit wpada w bits(0) nastepnego slotu). */
  case class Slot(ws : Boolean, bits : IndexedSeq[Boolean]) {
    override def toString =
      s"Slot(${if (ws) "R" else "L"}, ${bits.map(b => if (b) '1' else '0').mkString})"
  }
}

class I2sMonitor(d : I2sMaster) {
  import I2sMonitor._

  private val g  = d.g
  private val cd = d.clockDomain

  // --- wyniki ----------------------------------------------------------
  private val _frames       = mutable.ArrayBuffer[Frame]()
  private val _slots        = mutable.ArrayBuffer[Slot]()
  private val _sckHigh      = mutable.ArrayBuffer[Int]()
  private val _sckLow       = mutable.ArrayBuffer[Int]()
  private val _sckPerSlot   = mutable.ArrayBuffer[Int]()
  private val _framePeriods = mutable.ArrayBuffer[Int]()
  private val _changeDelays = mutable.ArrayBuffer[Int]()
  private val violations    = mutable.ArrayBuffer[String]()
  private var _preSyncSdoHigh = false
  private var everSynced      = false

  def frames         : Seq[Frame] = _frames.toSeq
  def slots          : Seq[Slot]  = _slots.toSeq
  /** Pelne polokresy SCK w cyklach. Pierwszy odcinek po resecie jest
    * pomijany, bo zaczyna sie od zwolnienia resetu, a nie od zbocza. */
  def sckHigh        : Seq[Int]   = _sckHigh.toSeq
  def sckLow         : Seq[Int]   = _sckLow.toSeq
  /** Narastajace SCK miedzy kolejnymi zboczami WS (pierwszy slot po resecie pominiety). */
  def sckPerSlot     : Seq[Int]   = _sckPerSlot.toSeq
  /** Cykle miedzy kolejnymi opadajacymi WS. */
  def framePeriods   : Seq[Int]   = _framePeriods.toSeq
  /** Dla kazdej zmiany SDO albo WS: cykle od ostatniego opadajacego SCK
    * (0 = w tym samym cyklu). Zmiany przed pierwszym opadajacym SCK po
    * resecie sa pomijane. */
  def changeDelays   : Seq[Int]   = _changeDelays.toSeq
  /** Czy SDO bylo kiedykolwiek wysoko przed PIERWSZA synchronizacja. */
  def preSyncSdoHigh : Boolean    = _preSyncSdoHigh

  // --- stan --------------------------------------------------------------
  private var cycle = 0L
  private var prev  : Option[(Boolean, Boolean, Boolean)] = None   // sck, ws, sdo

  private var synced       = false
  private var wsAtLastRise : Option[Boolean] = None

  private var wordCh    : Option[Boolean] = None    // false = L, true = R
  private var wordFresh = false                     // slowo zaczete od MSB?
  private val wordBits  = mutable.ArrayBuffer[Boolean]()
  private var leftWord  : Option[Long] = None

  private var slotWs   : Option[Boolean] = None
  private val slotBits = mutable.ArrayBuffer[Boolean]()

  private var lastSckEdge : Option[Long] = None
  private var lastSckFall : Option[Long] = None
  private var risesInSlot = 0
  private var wsEdgeSeen  = false
  private var lastWsFall  : Option[Long] = None

  // --- API -----------------------------------------------------------------
  def start() : Unit = fork {
    while (true) {
      cd.waitActiveEdge()         // NIE waitSampling - musimy widziec reset
      cycle += 1
      if (cd.isResetAsserted) onReset()
      else step(d.io.pins.sck.toBoolean, d.io.pins.ws.toBoolean, d.io.pins.sdo.toBoolean)
    }
  }

  /** Niezmiennik formatu: SDO i WS ruszaja sie tylko przy niskim SCK.
    * Zmiana w cyklu, w ktorym SCK wstaje, tez jest naruszeniem, bo odbiornik
    * probkuje wlasnie wtedy. Do tego slowa krotsze niz width. */
  def check() : Unit =
    assert(violations.isEmpty,
      s"${violations.size} naruszen formatu I2S, pierwsze:\n  " +
      violations.take(5).mkString("\n  "))

  /** Bariera: zapomina historie, pomiary i naruszenia, czeka na nastepne
    * opadajace WS. Wolane tylko z Env.resync. */
  def resync() : Unit = {
    Seq(_frames, _slots).foreach(_.clear())
    Seq(_sckHigh, _sckLow, _sckPerSlot, _framePeriods, _changeDelays).foreach(_.clear())
    violations.clear()
    desync()
  }

  // --- implementacja -------------------------------------------------------
  private def desync() : Unit = {
    synced = false
    wordCh = None; wordFresh = false; wordBits.clear(); leftWord = None
    slotWs = None; slotBits.clear()
  }

  private def onReset() : Unit = {
    desync()
    prev = None; wsAtLastRise = None
    lastSckEdge = None; lastSckFall = None
    risesInSlot = 0; wsEdgeSeen = false; lastWsFall = None
  }

  private def sync() : Unit = {
    desync()
    synced = true; everSynced = true
    // Pierwsze slowo po synchronizacji to ogon prawego kanalu sprzed
    // granicy (jego LSB wpada na r0). Liczymy go jako czesciowe i wyrzucamy.
    wordCh = Some(true); wordFresh = false
  }

  private def step(sck : Boolean, ws : Boolean, sdo : Boolean) : Unit = prev match {
    case None =>
      prev = Some((sck, ws, sdo))         // pierwszy cykl po resecie: punkt odniesienia

    case Some((pSck, pWs, pSdo)) =>
      val rise = !pSck && sck
      val fall = pSck && !sck

      if (sck && sdo != pSdo) violations += s"cykl $cycle: SDO $pSdo -> $sdo przy wysokim SCK"
      if (sck && ws  != pWs)  violations += s"cykl $cycle: WS $pWs -> $ws przy wysokim SCK"
      if (!everSynced && sdo) _preSyncSdoHigh = true

      if (fall) lastSckFall = Some(cycle)
      if (sdo != pSdo || ws != pWs)
        lastSckFall.foreach(t => _changeDelays += (cycle - t).toInt)

      if (rise || fall) {
        lastSckEdge.foreach { t => (if (rise) _sckLow else _sckHigh) += (cycle - t).toInt }
        lastSckEdge = Some(cycle)
      }

      if (ws != pWs) {
        if (wsEdgeSeen) _sckPerSlot += risesInSlot
        wsEdgeSeen = true; risesInSlot = 0
        if (!ws) {                                    // granica ramki
          lastWsFall.foreach(t => _framePeriods += (cycle - t).toInt)
          lastWsFall = Some(cycle)
          if (!synced) sync()
        }
        if (synced) {
          slotWs.foreach(w => _slots += Slot(w, slotBits.toVector))
          slotBits.clear(); slotWs = Some(ws)
        }
      }

      if (rise) {
        risesInSlot += 1
        if (synced) {
          slotBits += sdo
          bit(wsAtLastRise.getOrElse(ws), sdo)
        }
        wsAtLastRise = Some(ws)
      }

      prev = Some((sck, ws, sdo))
  }

  private def bit(ch : Boolean, v : Boolean) : Unit = {
    if (!wordCh.contains(ch)) {
      finishWord()
      wordCh = Some(ch); wordFresh = true
    }
    wordBits += v
  }

  private def finishWord() : Unit = {
    for (ch <- wordCh if wordFresh) {
      if (wordBits.size < g.width)
        violations += s"cykl $cycle: slowo ${if (ch) "R" else "L"} ma ${wordBits.size} bitow, width = ${g.width}"
      val v = wordBits.take(g.width).padTo(g.width, false)
                      .foldLeft(0L)((a, b) => (a << 1) | (if (b) 1L else 0L))
      if (!ch) leftWord = Some(v)
      else {
        leftWord.foreach(l => _frames += Frame(l, v))
        leftWord = None
      }
    }
    wordBits.clear()
  }
}
