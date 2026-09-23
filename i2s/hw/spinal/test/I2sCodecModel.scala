package newhope.i2s

import spinal.core.{Bool, ClockDomain}
import spinal.core.sim._
import scala.collection.mutable
import I2sEvent._

// =====================================================================
//  MODEL KODEKA - nadajnik po stronie SDI (§2.2, "FooBusModel").
//
//  Zachowuje sie jak slave-transmitter I2S: czyta SCK/WS mastera i przy
//  kazdym opadajacym SCK wystawia nastepny bit. Slot kanalu c (slowo
//  MSB-first + padding) trafia do kolejki bitow w chwili zbocza WS na c,
//  PO zdjeciu bitu dla tego samego opadajacego SCK. To daje opoznienie
//  o jeden bit: na f0 (zbocze WS) wychodzi jeszcze ostatni bit
//  poprzedniego slotu, a MSB nowego dopiero na f1.
//
//  LOG
//  Jeden wpis na ramke od synchronizacji (opadajace WS): Some(ramka)
//  albo None, gdy kolejka byla pusta i poszla cisza. To jest scoreboard
//  toru RX. Logowane jest to, co model FAKTYCZNIE wystawil, a nie to,
//  co test wlozyl do send().
//
//  RESET
//  Pod resetem model trzyma SDI nisko, porzuca kolejke bitow i czeka na
//  nastepne opadajace WS. Log i kolejke ramek zostawia; te czysci resync().
// =====================================================================
object I2sCodecModel {
  /** Opoznienie wyjscia kodeka (t_d) w jednostkach czasu symulacji,
    * liczone od aktywnego zbocza. MUSI byc mniejsze od okresu zegara
    * (10 w I2sMasterDriver.setup), inaczej bit spoznia sie o caly cykl.
    *
    * Po co w ogole: watek wznowiony przez waitActiveEdge() czyta wartosci
    * SPRZED zbocza (ta sama wlasnosc, dzieki ktorej StreamMonitor widzi
    * valid && ready w chwili handshake'u). Model reagujacy od razu widzialby
    * opadajace SCK dopiero na nastepnym zboczu i wystawial bit 2 cykle po
    * nim. Przy halfDiv = 1 DUT probkowal wtedy stary bit - RX przesuniety
    * o jeden bit na `min`, przy czystym TX. Tak wlasnie wygladal objaw. */
  val outDelay = 1

  /** Wyprowadzenie. SCK opada na zboczu t; nowy bit jest na SDI od
    * t + outDelay (przed zboczem t+1) do t + 2*halfDiv + outDelay.
    * DUT probkuje na zboczu, na ktorym SCK wstaje (t + halfDiv), albo
    * cykl pozniej (t + halfDiv + 1 <= t + 2*halfDiv). Oba mieszcza sie
    * w oknie dla kazdego halfDiv >= 1, o ile outDelay < okres. */
  val minHalfDiv = 1
}

/** Linie od strony mastera (jak I2sPins): sck, ws i sdo czyta, sdi steruje.
  * Konstruktor na sygnalach pozwala podpiac model do pinow harnessu
  * vertebra-hil, gdzie master DUT siedzi w srodku wiekszego komponentu. */
class I2sCodecModel(sck : Bool, ws : Bool, sdo : Bool, sdi : Bool,
                    width : Int, slotWidth : Int, cd : ClockDomain) {
  def this(d : I2sMaster) =
    this(d.io.pins.sck, d.io.pins.ws, d.io.pins.sdo, d.io.pins.sdi, d.g.width, d.g.slotWidth, d.clockDomain)

  /** Czym wypelniac padding. Domyslnie zera, jak robi to wiekszosc kodekow;
    * i2s_padding ustawia jedynki, zeby smieci w paddingu byly widoczne. */
  var padFill = false

  /** Dlugosc slowa KODEKA. Domyslnie == width DUT-a; inna wartosc to kodek
    * o innej rozdzielczosci (i2s_rx_word_length_mismatch). 1..slotWidth.
    * Zmiana dziala od nastepnego slotu. */
  var wordWidth : Int = width

  /** Nadajnik taktujacy SD narastajacym SCK zamiast opadajacego. Specyfikacja
    * dopuszcza oba zbocza po stronie nadajnika i wymaga zatrzasniecia na
    * narastajacym po stronie odbiornika. Ustawiac przed pierwsza ramka. */
  var leadingEdge = false

  /** Zamiast kodeka: SDI = SDO z opoznieniem outDelay (petla zewnetrzna,
    * i2s_loopback). Kolejka, log i send() sa wtedy nieuzywane.
    * Ustawiac przed pierwsza ramka i nie wylaczac w trakcie testu. */
  var loopback = false

  private val pending = mutable.Queue[Frame]()
  private val _log    = mutable.ArrayBuffer[Option[Frame]]()
  private val out     = mutable.Queue[Boolean]()

  private var synced = false
  private var cur    : Option[Frame] = None
  private var prev   : Option[(Boolean, Boolean)] = None     // sck, ws

  def log : Seq[Option[Frame]] = _log.toSeq

  /** Kolejkuje ramki; kazda kolejna granica ramki zdejmuje jedna. */
  def send(fs : Frame*) : Unit = pending ++= fs

  def start() : Unit = fork {
    while (true) {
      cd.waitActiveEdge()
      sleep(I2sCodecModel.outDelay)   // od tej chwili czytamy stan PO zboczu
      if (cd.isResetAsserted) onReset()
      else if (loopback) sdi #= sdo.toBoolean
      else step(sck.toBoolean, ws.toBoolean)
    }
  }

  /** Bariera (Env.resync). Zostawia w logu tylko ramke W TOKU, bo io.rx
    * tej ramki strzeli dopiero bit po nastepnym opadajacym WS, czyli juz
    * po barierze. Niewyslane ramki wyrzuca, zeby tresc tury po barierze
    * nie zalezala od tego, ile zdazylo wyjsc przed nia. */
  def resync() : Unit = {
    val keep = if (synced) _log.lastOption.toSeq else Nil
    _log.clear(); _log ++= keep
    pending.clear()
  }

  private def onReset() : Unit = {
    out.clear(); synced = false; cur = None; prev = None
    sdi #= false
  }

  private def slot(v : Long) : Seq[Boolean] = {
    require(wordWidth >= 1 && wordWidth <= slotWidth,
            s"wordWidth = $wordWidth poza 1..$slotWidth")
    (wordWidth - 1 to 0 by -1).map(i => ((v >> i) & 1) != 0) ++
    Seq.fill(slotWidth - wordWidth)(padFill)
  }

  private def step(sckV : Boolean, wsV : Boolean) : Unit = {
    for ((pSck, pWs) <- prev) {
      // Trailing (domyslnie): najpierw bit dla tego opadajacego SCK, POTEM
      // ewentualny nowy slot. Zamiana kolejnosci daje left-justified.
      // Leading: bit wystawiony na narastajacym r_j jest probkowany na
      // r_(j+1). Slot laduje sie na opadajacym f0, a jego S bitow schodzi
      // na S narastajacych zboczach slotu, wiec kolejka jest pusta na
      // kazdym f0 i opoznienie o bit wychodzi samo.
      val shift = if (leadingEdge) !pSck && sckV else pSck && !sckV
      if (shift)
        sdi #= (if (out.nonEmpty) out.dequeue() else false)

      if (wsV != pWs) {
        if (!wsV) {
          synced = true
          cur = if (pending.nonEmpty) Some(pending.dequeue()) else None
          _log += cur
          out ++= slot(cur.fold(0L)(_.left))
        } else if (synced) {
          out ++= slot(cur.fold(0L)(_.right))
        }
      }
    }
    prev = Some((sckV, wsV))
  }
}
