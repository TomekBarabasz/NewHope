package newhope.i2s

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import scala.collection.mutable
import scala.util.Random
import newhope.vertebra.{Stage, Testpoint, TestplanSuite,
                         Instrument, StreamConformance, StreamPortHandle}
import newhope.vertebra.sim.SimBackend

// =====================================================================
//  ZRODLO PLANU
//  Jak w I2sMasterTestplan: publicznego testplanu I2S nie ma, nazwy sa
//  nasze (prefiks slv_), wymagania ze specyfikacji I2S (NXP UM11732).
//  Wspolne z masterem testpointy maja te same nazwy z innym prefiksem,
//  zeby dalo sie porownac raporty obu suit.
//
//  Z przegladu publicznych testbenchy (szczegoly w I2sMasterTestplan):
//   Beyond Circuits t18/t19: test steruje SCK i WS, a okres WS jest
//       zmienny -> slv_variable_ws_period (dla mastera to bylo odrzucone,
//       tu jest glownym wymaganiem slave'a)
//   Microchip I2S UG: Initiator + Target na wspolnych liniach ->
//       I2sPairTestplan; obcinanie slowa -> slv_word_length_mismatch
//   AMD PG308, retroSoC loop mode -> I2sPairTestplan
//   audio-to-MIDI: SD zmieniane niezaleznie od BCLK ->
//       slv_rx_leading_edge_transmitter
//
//  ODRZUCONE
//   csr_*, tl_*, intr_*       - brak rejestrow
//   tryb master               - to jest I2sMaster
//   TDM, mono, polaryzacja SCK, LSB-first - jak w I2sMasterTestplan
//   glitche na SCK/WS          - odrzucone swiadomie: slave nie ma filtra,
//                                a I2S go nie przewiduje; glitch krotszy
//                                niz cykl zegara moze, ale nie musi zostac
//                                zauwazony, wiec test bylby niedeterministyczny
//   timeout na SCK             - brak; zatrzymany SCK jest LEGALNY
//                                (slv_sck_pause)
//
//  ASYNCHRONICZNOSC
//  Zegar slave'a ma okres 10; polokres SCK konfiguracji jest w tych samych
//  jednostkach i celowo nie zawsze jest wielokrotnoscia 10, zeby faza SCK
//  dryfowala wzgledem zegara (§4.2 mowi o wartosciach calkowitych - tu
//  calkowite sa jednostki czasu, a ulamkowy stosunek jest celem testu).
// =====================================================================

object I2sSlaveDriver {
  import I2sEvent._
  import I2sBusMaster._

  /** Okres zegara slave'a w jednostkach symulacji. */
  val period = 10

  def mask(w : Int, v : Long) : Long = v & ((1L << w) - 1)
  def randFrame(w : Int, rng : Random) : Frame = Frame(mask(w, rng.nextLong()), mask(w, rng.nextLong()))

  class Env(val d : I2sSlave, val bus : I2sBusMaster) {
    val w  = d.g.width
    def cd = d.clockDomain

    /** Jak w I2sMasterDriver: zdarzenia na granicach ramki w kolejnosci DUT-a. */
    val txOrder = mutable.ArrayBuffer[Option[Frame]]()
    val rxs     = mutable.ArrayBuffer[Frame]()

    /** Indeksy bus.frames, od ktorych porownujemy RX i TX (patrz resync). */
    var rxFrom = 0
    var txFrom = 0

    def push(f : Frame) : Unit = {
      d.io.tx.valid         #= true
      d.io.tx.payload.left  #= f.left
      d.io.tx.payload.right #= f.right
      cd.waitSamplingWhere(d.io.tx.ready.toBoolean)
      d.io.tx.valid #= false
    }

    /** Czeka n ramek MAGISTRALI - odporne na jitter i pauzy SCK. */
    def waitFrames(n : Int) : Unit = {
      val c = bus.curIdx
      waitUntil(bus.curIdx >= c + n)
    }

    /** Najpierw model musi wyslac wszystko z send() - send() nie blokuje,
      * wiec liczenie ramek od chwili wywolania nie wystarcza. Potem 3 ramki:
      *  RX: io.rx ramki k strzela na r0 ramki k+1,
      *  TX: ostatni push <= 1 ramka w buforze + 1 na magistrali + prawy LSB
      *      w r0 nastepnej.
      * waitFrames liczy STARTY ramek modelu, ktore wyprzedzaja granice
      * widziana przez slave'a (r0 + synchronizator) - stad 3, nie 2. */
    def settle() : Unit = {
      waitUntil(bus.pendingCount == 0)
      waitFrames(3)
    }

    /** SDI slave'a zdekodowane przez model == txOrder, obciete do ostatniej
      * ramki z danymi. Oczekiwana wartosc przechodzi przez transfer(), bo
      * dlugosc slotu i szerokosc odbiornika modelu moga sie roznic od w. */
    def expectTx() : Unit = {
      bus.check()
      val n = txOrder.lastIndexWhere(_.isDefined) + 1
      assert(n > 0, "DUT nie przyjal zadnej ramki z io.tx")
      val fr = bus.frames.slice(txFrom, txFrom + n)
      assert(fr.size == n && fr.forall(_.received.isDefined),
             s"TX: model zdekodowal ${fr.count(_.received.isDefined)} z $n ramek")
      val exp = txOrder.take(n).zip(fr).map { case (o, b) =>
        val f = o.getOrElse(Silence)
        Frame(transfer(f.left,  w, b.lenL, bus.rxWidth),
              transfer(f.right, w, b.lenR, bus.rxWidth))
      }.toSeq
      val got = fr.map(_.received.get).toSeq
      assert(got == exp, s"TX: SDI != to, co DUT przyjal\n  got = $got\n  exp = $exp")
    }

    /** io.rx == to, co model wyslal, po transfer() na dlugosc slotu. */
    def expectRx() : Unit = {
      val sent = bus.frames.drop(rxFrom)
      val n    = sent.lastIndexWhere(_.sent.isDefined) + 1
      assert(n > 0, "model niczego nie wyslal")
      assert(rxs.size >= n, s"RX: ${rxs.size} ramek na io.rx, model wyslal $n")
      val exp = sent.take(n).map { b =>
        val f = b.sent.getOrElse(Silence)
        Frame(transfer(f.left,  b.wordWidth, b.lenL, w),
              transfer(f.right, b.wordWidth, b.lenR, w))
      }.toSeq
      assert(rxs.take(n).toSeq == exp,
             s"RX: io.rx != model\n  got = ${rxs.take(n).toSeq}\n  exp = $exp")
    }

    /** Bariera jak w I2sMasterDriver: poczatek prawego slotu ramki k.
      * RX porownujemy od k (io.rx ramki k strzeli po barierze), TX od k+1. */
    def resync() : Unit = {
      val c = bus.curIdx
      waitUntil(bus.curIdx > c && bus.inRight)
      txOrder.clear(); rxs.clear()
      rxFrom = bus.curIdx; txFrom = bus.curIdx + 1
      bus.dropPending(); bus.clearViolations()
    }
  }

  /** Model magistrali startuje PO zwolnieniu resetu slave'a, od slotu
    * wstepnego, wiec ramka 0 modelu jest pierwsza ramka slave'a. */
  def setup(d : I2sSlave, sckHalf : Int, slotWidth : Int, frames : Int = 200) : Env = {
    // ramki x jednostki na ramke x 3 (jitter do 2x, zmienne sloty) x zapas 2
    SimTimeout(frames.toLong * 2 * (slotWidth + 8) * 2 * sckHalf * 3 * 2)

    val bus = new I2sBusMaster(d.io.pins, sckHalf, slotWidth, d.g.width)
    val e   = new Env(d, bus)

    d.io.tx.valid         #= false
    d.io.tx.payload.left  #= 0
    d.io.tx.payload.right #= 0
    d.io.pins.sck #= false; d.io.pins.ws #= true; d.io.pins.sdo #= false
    d.clockDomain.forkStimulus(period = period)

    FlowMonitor(d.io.rx, d.clockDomain) { p =>
      e.rxs += Frame(p.left.toLong, p.right.toLong)
    }
    fork {
      while (true) {
        d.clockDomain.waitSampling()
        if (d.io.underrun.toBoolean) e.txOrder += None
        if (d.io.tx.valid.toBoolean && d.io.tx.ready.toBoolean)
          e.txOrder += Some(Frame(d.io.tx.payload.left.toLong,
                                  d.io.tx.payload.right.toLong))
      }
    }
    d.clockDomain.waitSampling(5)
    bus.start()
    e
  }
}

// =====================================================================
//  SUITA I2sSlave
//
//  Debug pojedynczej komorki:
//    sbt "testOnly *I2sSlaveTestplan -- -z \"slv_rx_frame (min)\""
// =====================================================================
class I2sSlaveTestplan extends TestplanSuite {
  import I2sEvent._
  import I2sBusMaster._
  import I2sSlaveDriver._

  val label = "i2s_slv"

  def testplan : Seq[Testpoint] = Seq(

    // --- V1 ---------------------------------------------------------
    Testpoint("slv_param_bounds", Stage.V1,
      "Polokres SCK kazdej konfiguracji miesci sie w ograniczeniach slave'a i modelu",
      checking = Seq("sckHalf / okres > txLatencyCycles (TX zdazy przed narastajacym)",
                     "sckHalf > rxSetupCycles * okres + outDelay (setup RX)",
                     "Ta sama granica dla zakresu jittera w slv_sck_jitter")),

    Testpoint("slv_tx_frame", Stage.V1,
      "Ramka z io.tx wychodzi na SDI: L przy WS=0, R przy WS=1, MSB-first",
      stimulus = Seq("Dwie ramki z roznymi L i R"),
      checking = Seq("Model mastera odbiera dokladnie te ramki",
                     "Probka w chwili narastajacego SCK, czyli z prawdziwym setupem")),

    Testpoint("slv_rx_frame", Stage.V1,
      "Ramka wyslana przez mastera pojawia sie na io.rx",
      stimulus = Seq("Dwie ramki, SD zmieniane przy opadajacym SCK"),
      checking = Seq("io.rx == ramki modelu, ramka w ramke, wlacznie z cisza")),

    Testpoint("slv_ws_one_bit_delay", Stage.V1,
      "Opoznienie o bit wzgledem WS w obu kierunkach",
      stimulus = Seq("Ramki z pojedynczym MSB: (MSB, 0) i (0, MSB), w obie strony"),
      checking = Seq("Przesuniecie o bit w ktoras strone daje inna wartosc na scoreboardzie")),

    Testpoint("slv_sd_stable_while_sck_high", Stage.V1,
      "SDI slave'a nie zmienia sie przy wysokim SCK",
      stimulus = Seq("Wzorce 0x55../0xAA.. i pelne zera/jedynki"),
      checking = Seq("Wartosc na narastajacym == wartosc tuz przed opadajacym, kazdy bit")),

    // --- V2 ---------------------------------------------------------
    Testpoint("slv_full_duplex", Stage.V2,
      "TX i RX jednoczesnie, niezalezne dane",
      stimulus = Seq("8 losowych ramek w kazda strone"),
      checking = Seq("Scoreboard TX i RX")),

    Testpoint("slv_tx_underrun", Stage.V2,
      "Brak danych na granicy ramki daje cisze i impuls underrun",
      stimulus = Seq("Dwie ramki, przerwa na 3 ramki, dwie ramki"),
      checking = Seq("W przerwie same zera, liczba luk == liczba impulsow")),

    Testpoint("slv_padding", Stage.V2,
      "Slot dluzszy niz width: padding TX zerowy, padding RX ignorowany",
      stimulus = Seq("Model odbiera caly slot (rxWidth = slotWidth)",
                     "Model wypelnia swoj padding jedynkami"),
      checking = Seq("Bity slotu za LSB slave'a sa zerami", "io.rx bez smieci")),

    Testpoint("slv_lsb_across_ws", Stage.V2,
      "slot == width: LSB wpada w pierwszy bit slotu drugiego kanalu",
      stimulus = Seq("(1, 0), (0, 1), (max, 0), (0, max) w obie strony"),
      checking = Seq("TX i RX zdekodowane poprawnie")),

    Testpoint("slv_word_length_mismatch", Stage.V2,
      "Master nadaje i odbiera slowa innej dlugosci niz width",
      stimulus = Seq("Dluzsze o do 8 bitow (w granicach slotu), potem o polowe krotsze"),
      checking = Seq("RX: nadmiar LSB odciety / brak uzupelniony zerami",
                     "TX: model o innej szerokosci odbiera slowo MSB-first")),

    Testpoint("slv_variable_ws_period", Stage.V2,
      "Kazdy slot ma inna dlugosc, takze krotsza niz width",
      // Beyond Circuits t18/t19: slave nie zna dlugosci slotu z gory.
      stimulus = Seq("Dlugosc slotu losowana z [width/2, slotWidth + 8]",
                     "12 ramek w kazda strone"),
      checking = Seq("Scoreboard po transfer() na faktyczna dlugosc kazdego slotu",
                     "Brak przesuniecia kolejnych ramek po krotkim slocie")),

    Testpoint("slv_rx_leading_edge_transmitter", Stage.V2,
      "Master taktujacy SD narastajacym SCK - spec dopuszcza, hold = 0",
      stimulus = Seq("SD zmieniane outDelay po narastajacym SCK"),
      checking = Seq("io.rx == model, czyli slave bierze SD sprzed zbocza, nie po nim")),

    Testpoint("slv_sck_jitter", Stage.V2,
      "Nierowne i zmienne polowki okresu SCK",
      stimulus = Seq("Kazda polowka losowana z [sckHalf, 2*sckHalf]"),
      checking = Seq("Scoreboard TX i RX", "Zero naruszen stabilnosci SDI")),

    Testpoint("slv_sck_pause", Stage.V2,
      "SCK zatrzymany w srodku slotu na kilka ramek, raz nisko, raz wysoko",
      stimulus = Seq("Jedna niska i jedna wysoka polowka trwajaca 5 ramek"),
      checking = Seq("Zadnego zgubionego ani dodatkowego bitu", "Scoreboard TX i RX")),

    Testpoint("slv_startup_mid_frame", Stage.V2,
      "Slave wychodzi z resetu w srodku ramki trwajacego ruchu",
      stimulus = Seq("Reset przez 2+ ramki, zwolnienie w srodku prawego slotu"),
      checking = Seq("Do pierwszej pelnej ramki SDI to cisza",
                     "Pierwsze io.rx to pierwsza PELNA ramka po resecie, bez ramki czesciowej")),

    // --- V3 ---------------------------------------------------------
    Testpoint("slv_random_reset", Stage.V3,
      "Reset slave'a w losowym momencie, magistrala biegnie dalej",
      stimulus = Seq("Reset w losowym miejscu ramki, 3 razy", "Po resecie czysta tura"),
      checking = Seq("W resecie SDI=0, io.rx i underrun nisko",
                     "Tura po resecie zgodna ze scoreboardem")),

    Testpoint("slv_stress_random", Stage.V3,
      "Losowe dane, luki na io.tx, jitter i zmienne sloty naraz",
      stimulus = Seq("24 ramki w kazda strone, seed staly"),
      checking = Seq("Scoreboard TX i RX co do bitu")),

    // --- odlozone ---------------------------------------------------
    Testpoint("slv_left_justified", Stage.V2,
      "Format left-justified jako generyk",
      checking = Seq("MSB na r0, bez opoznienia o bit")),

    Testpoint("slv_right_justified", Stage.V2,
      "Format right-justified jako generyk",
      checking = Seq("LSB w ostatnim bicie slotu - wymaga znajomosci dlugosci slotu"))

  ) ++ StreamConformance.testpoints("tx")

  // -------------------------------------------------------------------
  //  KONFIGURACJE. sckHalf w jednostkach symulacji, okres zegara = 10.
  //  Minimum: > txLatencyCycles * 10 = 30 dla syncStages = 2.
  // -------------------------------------------------------------------
  case class Cfg(name : String, g : I2sSlaveGenerics, sckHalf : Int, slotWidth : Int)

  val configs = Seq(
    // 8 cykli na polokres, faza stala - odpowiednik typowego 12.288 MHz / 1.536 MHz
    Cfg("typ",    I2sSlaveGenerics(width = 16), sckHalf = 80, slotWidth = 32),
    // 3.7 cyklu: faza dryfuje, zbocza SCK wypadaja we wszystkich
    // polozeniach wzgledem zegara slave'a
    Cfg("drift",  I2sSlaveGenerics(width = 24), sckHalf = 37, slotWidth = 32),
    Cfg("w16s16", I2sSlaveGenerics(width = 16), sckHalf = 53, slotWidth = 16),
    Cfg("w32s32", I2sSlaveGenerics(width = 32), sckHalf = 41, slotWidth = 32),
    // DOLNA GRANICA: 30 + 1. Nowy bit na SDI najpozniej 30 jednostek po
    // opadajacym, model probkuje 31 jednostek po nim.
    Cfg("min",    I2sSlaveGenerics(width = 16), sckHalf = 31, slotWidth = 32)
  )

  for (Cfg(cfgName, g, sckHalf, slotWidth) <- configs) {

    val frameCycles = 2 * slotWidth * 2 * sckHalf / period
    var txPort : StreamPortHandle = null

    lazy val dut : SimCompiled[I2sSlave] = Config.sim
      .withFstWave
      .workspaceName(s"${label}_${cfgName}_${SimBackend.default.label}")
      .compile {
        val d = I2sSlave(g)
        d.rework {
          txPort = StreamPortHandle(
            valid   = d.io.tx.valid,
            ready   = d.io.tx.ready,
            payload = Instrument.stream(d.io.tx, "tx"),
            isInput = true,
            name    = "tx")
        }
        d
      }

    def scenario(name : String)(body : Env => Unit) : Unit =
      testpoint(name, variant = cfgName) {
        dut.doSim(s"${label}_${cfgName}_$name", seed = 42) { d =>
          body(setup(d, sckHalf, slotWidth))
        }
      }

    val w = g.width
    val m = mask(w, -1L)

    // =================================================================
    //  V1
    // =================================================================

    scenario("slv_tx_frame") { e =>
      val fs = Seq(Frame(mask(w, 0xA5C3F00FL), mask(w, 0x5A3C0FF0L)),
                   Frame(mask(w, 0x12345678L), mask(w, 0x87654321L)))
      fs.foreach(e.push)
      e.settle()
      e.expectTx()
      assert(e.txOrder.flatten.toSeq == fs, s"DUT przyjal ${e.txOrder.flatten}")
    }

    scenario("slv_rx_frame") { e =>
      e.bus.send(Frame(mask(w, 0x0F1E2D3CL), mask(w, 0xF0E1D2C3L)),
                 Frame(mask(w, 0x80000001L), mask(w, 0x7FFFFFFEL)))
      e.settle()
      e.expectRx()
    }

    scenario("slv_ws_one_bit_delay") { e =>
      // Pojedynczy bit: przesuniecie o pozycje zamienia MSB w MSB-1 albo
      // przenosi go do sasiedniego kanalu - scoreboard od razu to pokaze.
      val msb = 1L << (w - 1)
      val fs  = Seq(Frame(msb, 0), Frame(0, msb))
      e.bus.send(fs : _*)
      fs.foreach(e.push)
      e.settle()
      e.expectTx()
      e.expectRx()
    }

    scenario("slv_sd_stable_while_sck_high") { e =>
      Seq(Frame(mask(w, 0x55555555L), mask(w, 0xAAAAAAAAL)),
          Frame(m, 0), Frame(0, m), Frame(m, m)).foreach(e.push)
      e.settle()
      e.bus.check()      // wlasciwa asercja
      e.expectTx()
    }

    // =================================================================
    //  StreamConformance na io.tx - jak w I2sMasterTestplan
    // =================================================================

    scenario("tx_payload_stable") { e =>
      StreamConformance.payloadStable(e.cd, txPort)
      StreamConformance.noStall(e.cd, txPort, 2 * frameCycles + 16)

      val rng = new Random(11)
      val fs  = Seq.fill(6)(randFrame(w, rng))
      fs.foreach(e.push)
      e.settle()
      e.expectTx()
      val body = e.txOrder.dropWhile(_.isEmpty).take(fs.size)
      assert(body.forall(_.isDefined), s"luka mimo ciaglego zasilania: $body")
    }

    scenario("tx_reset_quiet") { e =>
      StreamConformance.quietDuringReset(e.cd, txPort)

      e.push(Frame(mask(w, 0x1234L), mask(w, 0x5678L)))
      e.settle()
      e.expectTx()

      e.cd.assertReset()
      e.cd.waitActiveEdge(20)
      e.cd.deassertReset()
      e.waitFrames(2)          // slave musi zobaczyc granice ramki
      e.resync()

      e.push(Frame(mask(w, 0x9ABCL), mask(w, 0xDEF0L)))
      e.settle()
      e.expectTx()
    }

    // =================================================================
    //  V2
    // =================================================================

    scenario("slv_full_duplex") { e =>
      val rng = new Random(1)
      e.bus.send(Seq.fill(8)(randFrame(w, rng)) : _*)
      Seq.fill(8)(randFrame(w, rng)).foreach(e.push)
      e.settle()
      e.expectTx()
      e.expectRx()
    }

    scenario("slv_tx_underrun") { e =>
      val a = Seq(Frame(mask(w, 0x1111L), mask(w, 0x2222L)),
                  Frame(mask(w, 0x3333L), mask(w, 0x4444L)))
      val b = Seq(Frame(mask(w, 0x5555L), mask(w, 0x6666L)),
                  Frame(mask(w, 0x7777L), mask(w, 0x8888L)))
      a.foreach(e.push)
      // 4, nie 3: start ramki w modelu (curIdx) wyprzedza granice ramki
      // widziana przez slave'a o pol bitu + synchronizator. Push tuz po
      // starcie ramki modelu zdazy jeszcze do bufora na TE ramke, wiec
      // przerwa liczona startami modelu jest o jedna ramke krotsza.
      e.waitFrames(4)
      b.foreach(e.push)
      e.settle()
      e.expectTx()
      val gap = e.txOrder.slice(e.txOrder.indexWhere(_.contains(a.last)),
                                e.txOrder.indexWhere(_.contains(b.head)))
      assert(gap.count(_.isEmpty) >= 2, s"za malo luk w przerwie: $gap")
    }

    if (slotWidth > w) scenario("slv_padding") { e =>
      e.bus.rxWidth = slotWidth     // model widzi caly slot, wlacznie z paddingiem
      e.bus.padFill = true          // i sam wypelnia swoj padding jedynkami
      val fs = Seq(Frame(m, m), Frame(m, 0), Frame(0, m))
      e.bus.send(fs : _*)
      fs.foreach(e.push)
      e.settle()
      // transfer(v, w, slot, slot) == v << (slot - w): zera w paddingu TX
      e.expectTx()
      e.expectRx()
    }

    if (slotWidth == w) scenario("slv_lsb_across_ws") { e =>
      val fs = Seq(Frame(1, 0), Frame(0, 1), Frame(m, 0), Frame(0, m))
      e.bus.send(fs : _*)
      fs.foreach(e.push)
      e.settle()
      e.expectTx()
      e.expectRx()
    }

    scenario("slv_word_length_mismatch") { e =>
      val rng = new Random(23)

      def phase(ww : Int) : Unit = {
        e.bus.wordWidth = ww      // master nadaje ww bitow
        e.bus.rxWidth   = ww      // i odbiera ww bitow
        val rx = Seq.fill(6)(randFrame(ww, rng))
        val tx = Seq.fill(6)(randFrame(w,  rng))
        e.bus.send(rx : _*)
        tx.foreach(e.push)
        e.waitFrames(rx.size)
        e.settle()
        e.expectTx()
        e.expectRx()
      }

      if (slotWidth > w) {
        phase(scala.math.min(w + 8, slotWidth))
        e.resync()
      }
      phase(scala.math.max(2, w / 2))
    }

    scenario("slv_variable_ws_period") { e =>
      val rngBus = new Random(34)
      val lo     = scala.math.max(2, w / 2)
      val hi     = slotWidth + 8
      e.bus.slotLen = () => lo + rngBus.nextInt(hi - lo + 1)

      val rng = new Random(35)
      e.waitFrames(1)          // ramka w toku miala jeszcze stala dlugosc - bez znaczenia
      e.bus.send(Seq.fill(12)(randFrame(w, rng)) : _*)
      Seq.fill(12)(randFrame(w, rng)).foreach(e.push)
      e.waitFrames(12)
      e.settle()
      e.expectTx()
      e.expectRx()

      val lens = e.bus.frames.flatMap(f => Seq(f.lenL, f.lenR))
      assert(lens.exists(_ < w) && lens.exists(_ > w),
             s"losowanie nie trafilo w obie strony width=$w: ${lens.distinct.sorted}")
    }

    scenario("slv_rx_leading_edge_transmitter") { e =>
      e.bus.leadingEdge = true
      val rng = new Random(22)
      e.waitFrames(1)          // przelaczenie na granicy ramki modelu
      e.bus.send(Seq.fill(8)(randFrame(w, rng)) : _*)
      e.waitFrames(8)
      e.settle()
      e.expectRx()
    }

    scenario("slv_sck_jitter") { e =>
      val rngBus = new Random(33)
      e.bus.lowLen  = () => sckHalf + rngBus.nextInt(sckHalf + 1)
      e.bus.highLen = () => sckHalf + rngBus.nextInt(sckHalf + 1)

      val rng = new Random(36)
      e.bus.send(Seq.fill(8)(randFrame(w, rng)) : _*)
      Seq.fill(8)(randFrame(w, rng)).foreach(e.push)
      e.waitFrames(8)
      e.settle()
      e.expectTx()
      e.expectRx()
    }

    scenario("slv_sck_pause") { e =>
      val pause = 5 * 2 * slotWidth * 2 * sckHalf
      // Liczniki polowek od chwili podstawienia; progi wypadaja w srodku
      // slotow, raz w niskiej, raz w wysokiej polowce.
      var nl = 0; var nh = 0
      val atLow  = 3 * slotWidth / 2 + 5
      val atHigh = 5 * slotWidth + 7
      e.bus.lowLen  = () => { nl += 1; if (nl == atLow)  pause else sckHalf }
      e.bus.highLen = () => { nh += 1; if (nh == atHigh) pause else sckHalf }

      val rng = new Random(37)
      e.bus.send(Seq.fill(8)(randFrame(w, rng)) : _*)
      Seq.fill(8)(randFrame(w, rng)).foreach(e.push)
      e.waitFrames(8)
      e.settle()
      assert(nl > atLow && nh > atHigh, s"pauzy nie wystapily ($nl/$atLow, $nh/$atHigh)")
      e.expectTx()
      e.expectRx()
    }

    scenario("slv_startup_mid_frame") { e =>
      val rng = new Random(31)
      e.bus.send(Seq.fill(10)(randFrame(w, rng)) : _*)

      e.cd.assertReset()
      e.waitFrames(2)
      waitUntil(!e.bus.inRight)
      waitUntil(e.bus.inRight)                            // poczatek prawego slotu
      sleep(rng.nextInt(slotWidth * sckHalf))             // do polowy slotu
      val k0 = e.bus.curIdx
      e.cd.deassertReset()

      // Ramka k0 jest czesciowa z punktu widzenia slave'a; pierwsza pelna to k0+1.
      e.txOrder.clear(); e.rxs.clear(); e.bus.clearViolations()
      e.rxFrom = k0 + 1; e.txFrom = k0 + 1

      val fs = Seq.fill(4)(randFrame(w, rng))
      fs.foreach(e.push)
      e.settle()

      for (bf <- e.bus.frames.take(k0 + 1))
        assert(bf.received.forall(_ == Silence), s"SDI przed synchronizacja: $bf")
      e.expectTx()
      e.expectRx()
    }

    // =================================================================
    //  V3
    // =================================================================

    scenario("slv_random_reset") { e =>
      val rng = new Random(5)
      val d   = e.d

      for (_ <- 0 until 3) {
        fork {
          e.cd.waitSampling(rng.nextInt(2 * frameCycles) + 1)
          e.cd.assertReset()
          e.cd.waitActiveEdge(5)
          assert(!d.io.pins.sdi.toBoolean, "reset nie wyzerowal SDI")
          assert(!d.io.rx.valid.toBoolean && !d.io.underrun.toBoolean,
                 "io.rx albo underrun w trakcie resetu")
          e.cd.deassertReset()
        }

        e.bus.send(randFrame(w, rng), randFrame(w, rng))
        e.push(randFrame(w, rng)); e.push(randFrame(w, rng))
        e.waitFrames(4)          // > 2 ramki: reset byl, slave widzial granice
        e.resync()

        val fr = randFrame(w, rng); val ft = randFrame(w, rng)
        e.bus.send(fr); e.push(ft)
        e.settle()
        e.expectTx()
        e.expectRx()
      }
    }

    scenario("slv_stress_random") { e =>
      val rngBus = new Random(38)
      e.bus.lowLen  = () => sckHalf + rngBus.nextInt(sckHalf / 2 + 1)
      e.bus.highLen = () => sckHalf + rngBus.nextInt(sckHalf / 2 + 1)
      e.bus.slotLen = () => scala.math.max(2, w / 2) + rngBus.nextInt(slotWidth + 8 - scala.math.max(2, w / 2) + 1)

      val rng = new Random(7)
      e.waitFrames(1)
      e.bus.send(Seq.fill(24)(randFrame(w, rng)) : _*)
      for (_ <- 0 until 24) {
        e.push(randFrame(w, rng))
        if (rng.nextBoolean()) e.cd.waitSampling(rng.nextInt(frameCycles))
      }
      e.settle()
      e.expectTx()
      e.expectRx()
    }
  }

  // -------------------------------------------------------------------
  //  Bez symulacji (§4.3)
  // -------------------------------------------------------------------
  testpoint("slv_param_bounds") {
    configs.foreach { case Cfg(n, g, h, s) =>
      val half = BigDecimal(h) / period
      info(f"$n%-7s sckHalf=$h%3d (${half}%s cykli) slot=$s%2d width=${g.width}%2d  " +
           f"zapas TX=${half - g.txLatencyCycles}%s cykli  " +
           f"zapas RX=${h - g.rxSetupCycles * period - outDelay}%d jedn.")
    }

    val bad = configs.filterNot(_.g.isLegal)
    assert(bad.isEmpty, s"nielegalne generyki: ${bad.map(_.name)}")

    // TX: nowy bit najpozniej txLatencyCycles * okres po opadajacym.
    val slowTx = configs.filterNot(c => c.g.supportsSckHalf(BigDecimal(c.sckHalf) / period))
    assert(slowTx.isEmpty, s"slave nie zdazy z SDI: ${slowTx.map(_.name)}")

    // RX: SD modelu zmienia sie outDelay po opadajacym, slave bierze probke
    // do rxSetupCycles cykli przed narastajacym.
    val blindRx = configs.filterNot(c => c.sckHalf > c.g.rxSetupCycles * period + outDelay)
    assert(blindRx.isEmpty, s"setup RX niespelniony: ${blindRx.map(_.name)}")

    // Jitter i stres losuja polowki z [sckHalf, ...] - minimum to sckHalf,
    // wiec granica wyzej obejmuje tez je.
  }

  // -------------------------------------------------------------------
  //  ODLOZONE
  // -------------------------------------------------------------------
  unimplemented("slv_left_justified",  "DUT ma tylko format Philips")
  unimplemented("slv_right_justified", "DUT ma tylko format Philips; slave musialby znac dlugosc slotu z gory")
  unimplemented("tx_backpressure",
    "ready steruje DUT; wzorce po stronie valid pokrywa slv_tx_underrun i slv_stress_random")
  unimplemented("tx_stress_with_rand_reset",
    "jak w I2sMasterTestplan: driver trzyma valid przez reset z forka")
}

// =====================================================================
//  PARA MASTER <-> SLAVE - osobna suita, bo potrzebuje innego DUT-a
//  (jak I2cMasterEquivalence).
//
//  Microchip UG (Initiator + Target na wspolnych liniach), PG308
//  (loopback TX-RX), retroSoC (loop mode). W odroznieniu od suit
//  pojedynczych IP obie strony sa prawdziwe - nie ma modelu, ktory
//  moglby miec ten sam blad co DUT.
//
//  Zegar wspolny, wiec faza SCK jest stala; asynchronicznosc pokrywa
//  I2sSlaveTestplan. halfDiv = 4 to dokladnie granica: slave wystawia bit
//  3 cykle po opadajacym (txLatencyCycles), master probkuje na 4.
// =====================================================================
case class I2sPair(gm : I2sGenerics, gs : I2sSlaveGenerics) extends Component {
  require(gm.width == gs.width, "para zaklada te sama szerokosc slowa")
  val io = new Bundle {
    val mTx = slave(Stream(I2sFrame(gm.width)))
    val mRx = master(Flow(I2sFrame(gm.width)))
    val sTx = slave(Stream(I2sFrame(gs.width)))
    val sRx = master(Flow(I2sFrame(gs.width)))
  }
  val m = I2sMaster(gm)
  val s = I2sSlave(gs)
  m.io.tx << io.mTx
  s.io.tx << io.sTx
  io.mRx.valid := m.io.rx.valid; io.mRx.payload := m.io.rx.payload
  io.sRx.valid := s.io.rx.valid; io.sRx.payload := s.io.rx.payload
  m.io.pins <> s.io.pins
}

class I2sPairTestplan extends TestplanSuite {
  import I2sEvent._

  def testplan : Seq[Testpoint] = Seq(
    Testpoint("pair_param_bounds", Stage.V1,
      "Kazda para spelnia ograniczenie slave'a przy SCK mastera",
      checking = Seq("halfDiv mastera > txLatencyCycles slave'a")),
    Testpoint("pair_master_slave_duplex", Stage.V2,
      "I2sMaster i I2sSlave na wspolnych liniach, oba kierunki naraz",
      stimulus = Seq("12 losowych, niezerowych ramek w kazda strone, rownolegle"),
      checking = Seq("slave.rx bez ciszy == ramki mastera.tx",
                     "master.rx bez ciszy == ramki slave.tx")))

  // 24.576 MHz / (48k * 64 * 2) = 4 - granica; 49.152 MHz -> 8
  val pairs = Seq(
    "hd4" -> I2sGenerics(24576 kHz, 48 kHz, width = 16, slotWidth = 32),
    "hd8" -> I2sGenerics(49152 kHz, 48 kHz, width = 16, slotWidth = 32))

  testpoint("pair_param_bounds") {
    for ((n, gm) <- pairs) {
      val gs = I2sSlaveGenerics(gm.width)
      info(s"$n: halfDiv=${gm.halfDiv}, txLatency=${gs.txLatencyCycles}")
      assert(gs.supportsSckHalf(gm.halfDiv), s"$n: slave nie zdazy przy halfDiv=${gm.halfDiv}")
    }
  }

  private def push(s : Stream[I2sFrame], cd : ClockDomain, f : Frame) : Unit = {
    s.valid #= true
    s.payload.left  #= f.left
    s.payload.right #= f.right
    cd.waitSamplingWhere(s.ready.toBoolean)
    s.valid #= false
  }

  for ((name, gm) <- pairs) {
    lazy val dut = Config.sim
      .withFstWave
      .workspaceName(s"i2s_pair_${name}_${SimBackend.default.label}")
      .compile(I2sPair(gm, I2sSlaveGenerics(gm.width)))

    testpoint("pair_master_slave_duplex", variant = name) {
      dut.doSim(s"i2s_pair_${name}", seed = 42) { d =>
        SimTimeout(40L * gm.cyclesPerFrame * 10 * 4)
        Seq(d.io.mTx, d.io.sTx).foreach { s =>
          s.valid #= false; s.payload.left #= 0; s.payload.right #= 0
        }
        d.clockDomain.forkStimulus(period = 10)

        val mRx = mutable.ArrayBuffer[Frame]()
        val sRx = mutable.ArrayBuffer[Frame]()
        FlowMonitor(d.io.mRx, d.clockDomain)(p => mRx += Frame(p.left.toLong, p.right.toLong))
        FlowMonitor(d.io.sRx, d.clockDomain)(p => sRx += Frame(p.left.toLong, p.right.toLong))
        d.clockDomain.waitSampling(5)

        // Niezerowe ramki: cisza (underrun) jest wtedy jednoznaczna i mozna
        // ja odfiltrowac. Zgubienie, dubel albo mieszanie kanalow i tak
        // zmieni ciag.
        val rng = new Random(41)
        def nz() : Frame = {
          val f = I2sSlaveDriver.randFrame(gm.width, rng)
          if (f == Silence) Frame(1, 1) else f
        }
        val mf = Seq.fill(12)(nz())
        val sf = Seq.fill(12)(nz())

        val t = fork { sf.foreach(push(d.io.sTx, d.clockDomain, _)) }
        mf.foreach(push(d.io.mTx, d.clockDomain, _))
        t.join()
        d.clockDomain.waitSampling(4 * gm.cyclesPerFrame)

        assert(sRx.filter(_ != Silence).toSeq == mf,
               s"master -> slave\n  got = ${sRx.filter(_ != Silence)}\n  exp = $mf")
        assert(mRx.filter(_ != Silence).toSeq == sf,
               s"slave -> master\n  got = ${mRx.filter(_ != Silence)}\n  exp = $sf")
      }
    }
  }
}
