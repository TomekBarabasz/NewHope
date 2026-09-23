package newhope.i2s

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._
import scala.collection.mutable
import scala.util.Random
import newhope.vertebra.{Stage, Testpoint, TestplanSuite,
                         Instrument, StreamConformance, StreamPortHandle}
import newhope.vertebra.sim.SimBackend

// =====================================================================
//  ZRODLO PLANU
//  ------------
//  Publicznego testplanu I2S nie ma. OpenTitan nie ma bloku I2S, a
//  uvmdvgen generuje tylko szkielet (smoke + import csr/mem/intr/tl),
//  bez niczego specyficznego dla protokolu. Plany komercyjnych VIP-ow
//  (Synopsys itd.) sa zamkniete.
//
//  Nazwy testpointow sa wiec NASZE (prefiks i2s_), a wymagania pochodza
//  ze specyfikacji I2S (Philips 1996, obecnie NXP UM11732). Przy kazdym
//  testpoincie checking mowi, z ktorej wlasnosci formatu wynika.
//
//  PRZEGLAD PUBLICZNYCH TESTBENCHY (drugi obieg)
//  Zadne z ponizszych nie ma testplanu; to sa testbenche albo opisy
//  przykladow. Brane sa POMYSLY na scenariusze, nie nazwy.
//   eagleirony/audio-to-MIDI  i2s_master_TB / i2s_slave_TB: sama stymulacja
//       bez asercji. SDI przelaczane asynchronicznie do BCLK -> po stronie
//       spec: nadajnik moze taktowac SD dowolnym zboczem ->
//       i2s_rx_leading_edge_transmitter. ctr_reg wlacza kanaly -> mono,
//       odrzucone nizej. slave_TB laczy master ze slave -> i2s_loopback.
//   AMD PG308, przyklad "Loopback TX-RX": generator AXIS -> TX -> I2S ->
//       RX -> checker integralnosci -> i2s_loopback.
//   Microchip I2S Protocol IP UG: Initiator-TX + Target-RX na wspolnych
//       I2S_CLK/WSEL/SD (-> i2s_loopback); obcinanie slowa dluzszego niz
//       odbiornik -> i2s_rx_word_length_mismatch; konfigurowalna
//       szerokosc i dzielnik -> configs.
//   Beyond Circuits, tutorial 18/19: odbiornik nie zna dlugosci slowa
//       nadajnika -> i2s_rx_word_length_mismatch; SD zmieniane do 2
//       cykli po SCK opisane jako odstepstwo -> i2s_sdo_on_sck_fall;
//       zmienny okres WS -> dotyczy slave'a, odrzucone nizej.
//   retroSoC/i2s (Mulan PSL v2): testy rejestrow/przerwan nieaplikowalne;
//       test_clk_div (dzielnik 2) -> odpowiada `min`; test_loop_mode ->
//       i2s_loopback; 8/16/24/32 bity i fs 8..96 kHz -> configs
//       w8fs8k i w32fs96k; FMT -> i2s_right_justified (odlozone).
//
//  ODRZUCONE JAKO NIEAPLIKOWALNE
//   csr_*, mem_*, intr_*, tl_*  - nie mamy rejestrow ani TileLinka
//   tryb slave                  - SCK i WS sa u nas wyjsciami
//   TDM, >2 kanaly, tryb PCM/DSP - poza zakresem tego IP
//   glitche na SCK/WS           - to wyjscia mastera, nikt ich nie
//                                 zakloca; glitch na SDI nie ma sensu
//                                 bez filtra, ktorego I2S nie przewiduje
//   clock stretching, arbitraz  - nie istnieja w I2S
//   zmienny okres WS            - wymaganie odbiornika-slave'a (Beyond
//                                 Circuits); u nas WS generuje DUT, a jego
//                                 stalosc sprawdza i2s_sck_ws_timing
//   mono / kanal wylaczony,     - funkcje retroSoC (CHM, POL, LSB) i
//   polaryzacja SCK, LSB-first    audio-to-MIDI (ctr_reg) spoza naszych
//                                 wymagan; testpoint dopiero razem z funkcja
//
//  ZALOZENIA O DUT-cie (do potwierdzenia, zanim powstanie RTL)
//   io.tx       : slave(Stream(I2sFrame))  left, right : Bits(width)
//   io.rx       : master(Flow(I2sFrame))   jedna ramka na okres ramki
//   io.underrun : Bool                     impuls na granicy ramki bez danych
//   io.pins     : sck, ws, sdo out; sdi in
//   - format Philips: WS zmienia sie przy opadajacym SCK, MSB idzie
//     jeden okres SCK po zboczu WS, WS=0 to kanal lewy
//   - [DECYZJA] po resecie SCK=0, WS=1, SDO=0; ramka zaczyna sie opadajacym WS,
//     wiec pierwsza ramka po resecie jest poprzedzona jednym pustym
//     prawym slotem. Dzieki temu granica ramki ZAWSZE jest zboczem,
//     a monitor i kodek synchronizuja sie na tym samym zdarzeniu.
//   - underrun wysyla cisze (zera) i nie powtarza ostatniej ramki
//   - io.tx ma co najwyzej jedna ramke bufora: ramka przyjeta w
//     ramce k wychodzi na piny w ramce k+1
//
//  API TESTBENCHU (osobne pliki, §2.2)
//   I2sEvent.Frame(left : Long, right : Long), I2sEvent.Silence
//   I2sMonitor(d)      - tylko sck/ws/sdo, zegar DUT-a
//     frames           : Seq[Frame] od pierwszego opadajacego WS, regula
//                        Philips: bit probkowany na narastajacym SCK
//                        nalezy do kanalu, ktory WS wskazywal przy
//                        POPRZEDNIM narastajacym SCK. To samo daje
//                        poprawne dekodowanie przy width == slotWidth.
//     slots            : Seq[Slot(ws, bits : IndexedSeq[Boolean])], surowe
//                        probki SDO; bits(0) = LSB poprzedniego slowa
//     sckHigh, sckLow  : Seq[Int] pelne polokresy SCK od zwolnienia resetu
//     sckPerSlot       : Seq[Int] narastajace SCK miedzy zboczami WS
//     framePeriods     : Seq[Int] cykle miedzy kolejnymi opadajacymi WS
//     preSyncSdoHigh   : Boolean  SDO wysoko przed pierwsza ramka?
//     changeDelays     : Seq[Int] cykle od opadajacego SCK do kazdej zmiany SDO/WS
//     check()          : zero zmian SDO/WS przy wysokim SCK
//     resync()         : zapomina wszystko, czeka na nastepne opadajace WS
//   I2sCodecModel(d)   - drugi koniec: czyta sck/ws, steruje sdi
//     send(frames*)    : kolejka; jedna ramka na okres, pusto = cisza
//     padFill          : Boolean, czym wypelniac padding (default false)
//     wordWidth        : dlugosc slowa kodeka (default width)
//     leadingEdge      : nadajnik taktowany narastajacym SCK
//     loopback         : SDI = SDO zamiast kodeka
//     log              : Seq[Option[Frame]] jeden wpis na ramke, None = cisza
//     resync()         : zostawia w logu tylko ramke W TOKU (patrz Env.resync)
//     I2sCodecModel.outDelay, I2sCodecModel.minHalfDiv
// =====================================================================

object I2sMasterDriver {
  import I2sEvent._

  def mask(g : I2sGenerics, v : Long) : Long = v & ((1L << g.width) - 1)
  def full(g : I2sGenerics) : Long            = mask(g, -1L)
  def randFrame(g : I2sGenerics, rng : Random) : Frame =
    Frame(mask(g, rng.nextLong()), mask(g, rng.nextLong()))

  class Env(val d : I2sMaster, val codec : I2sCodecModel, val mon : I2sMonitor) {
    val g  = d.g
    def cd = d.clockDomain

    /** Zdarzenia na granicach ramki w kolejnosci, w jakiej zglasza je DUT:
      * Some = ramka przyjeta z io.tx, None = impuls io.underrun. Przy
      * buforze jednoramkowym ta kolejnosc JEST kolejnoscia na pinach,
      * wiec porownanie z monitorem sprawdza jednoczesnie: brak zgubionych,
      * brak zdublowanych, brak mieszania kanalow z roznych ramek i to, ze
      * luka to cisza, a nie powtorka. */
    val txOrder = mutable.ArrayBuffer[Option[Frame]]()
    val rxs     = mutable.ArrayBuffer[Frame]()

    def push(f : Frame) : Unit = {
      d.io.tx.valid         #= true
      d.io.tx.payload.left  #= f.left
      d.io.tx.payload.right #= f.right
      cd.waitSamplingWhere(d.io.tx.ready.toBoolean)
      d.io.tx.valid #= false
    }

    def waitFrames(n : Int) : Unit = cd.waitSampling(n * g.cyclesPerFrame)

    /** Ostatnia przyjeta ramka: <= 1 ramka w buforze + 1 ramka na pinach
      * + LSB prawego kanalu wpada bit za opadajace WS nastepnej ramki.
      * Trzy ramki pokrywaja to z zapasem. */
    def settle() : Unit = waitFrames(3)

    /** Magistrala == txOrder, obciete do ostatniej ramki z danymi. Ogon
      * to same luki, a ich liczba zalezy tylko od tego, jak dlugo test
      * czekal. */
    def expectTx() : Unit = {
      mon.check()
      val n = txOrder.lastIndexWhere(_.isDefined) + 1
      assert(n > 0, "DUT nie przyjal zadnej ramki z io.tx")
      val exp = txOrder.take(n).map(_.getOrElse(Silence)).toSeq
      val got = mon.frames.take(n).toSeq
      assert(got == exp, s"TX: piny != to, co DUT przyjal\n  got = $got\n  exp = $exp")
    }

    /** io.rx == to, co kodek faktycznie wystawil, ramka w ramke. */
    def expectRx() : Unit = {
      val n = codec.log.lastIndexWhere(_.isDefined) + 1
      assert(n > 0, "kodek niczego nie wyslal")
      assert(rxs.size >= n, s"RX: ${rxs.size} ramek na io.rx, kodek wyslal $n")
      val exp = codec.log.take(n).map(_.getOrElse(Silence)).toSeq
      assert(rxs.take(n).toSeq == exp,
             s"RX: io.rx != kodek\n  got = ${rxs.take(n).toSeq}\n  exp = $exp")
    }

    /** Bariera do czyszczenia scoreboardow w srodku testu.
      *
      * Czyscimy na POCZATKU PRAWEGO SLOTU, pol ramki od granicy: wszystko,
      * co DUT robi na granicy (underrun, zatrzasniecie tx, rx), dzieje sie
      * przy opadajacym WS, wiec czyszczenie w poblizu tego cyklu robiloby
      * z kolejnosci watkow w jednym cyklu czesc wyniku testu.
      *
      * RX jest o ramke do tylu: io.rx ramki k strzela po LSB prawego
      * kanalu, czyli bit PO opadajacym WS ramki k+1. Dlatego kodek
      * zostawia w logu ramke w toku, a monitor i txOrder zaczynaja od
      * nastepnej. */
    def resync() : Unit = {
      cd.waitSamplingWhere(!d.io.pins.ws.toBoolean)
      cd.waitSamplingWhere( d.io.pins.ws.toBoolean)
      txOrder.clear(); rxs.clear()
      mon.resync(); codec.resync()
    }
  }

  /** SimTimeout z tego samego powodu co w I2cMasterDriver: waitSamplingWhere
    * na ready nie ma limitu, a test ma failowac, nie wisiec. Ramki x cykle
    * na ramke x okres 10 x zapas 4. */
  def setup(d : I2sMaster, frames : Int = 128) : Env = {
    SimTimeout(frames.toLong * d.g.cyclesPerFrame * 10 * 4)

    val e = new Env(d, new I2sCodecModel(d), new I2sMonitor(d))

    d.io.tx.valid         #= false
    d.io.tx.payload.left  #= 0
    d.io.tx.payload.right #= 0
    d.io.pins.sdi         #= false
    d.clockDomain.forkStimulus(period = 10)

    FlowMonitor(d.io.rx, d.clockDomain) { p =>
      e.rxs += Frame(p.left.toLong, p.right.toLong)
    }
    // Jeden watek na oba zrodla, zeby kolejnosc w obrebie cyklu byla
    // deterministyczna: underrun przed fire (luka konczy poprzednia
    // ramke, fire zasila nastepna).
    fork {
      while (true) {
        d.clockDomain.waitSampling()
        if (d.io.underrun.toBoolean) e.txOrder += None
        if (d.io.tx.valid.toBoolean && d.io.tx.ready.toBoolean)
          e.txOrder += Some(Frame(d.io.tx.payload.left.toLong,
                                  d.io.tx.payload.right.toLong))
      }
    }
    e.codec.start(); e.mon.start()
    d.clockDomain.waitSampling(5)
    e
  }
}

// =====================================================================
//  SUITA I2sMaster. Jedna implementacja, wiec bez klasy abstrakcyjnej
//  z `build` (§4.5) - dojdzie, gdy pojawi sie druga.
//
//  CO TEN PLAN SPRAWDZA
//   - format na pinach (Philips: opoznienie o bit, MSB-first, L przy WS=0),
//   - timing SCK/WS dokladnie w cyklach (wszystko jest z jednego zegara),
//   - tor tx: kolejnosc, atomowosc ramki, underrun,
//   - tor rx: probkowanie SDI i skladanie ramki,
//   - start po resecie i reset w trakcie ruchu.
//
//  Debug pojedynczej komorki:
//    sbt "testOnly *I2sMasterTestplan -- -z \"i2s_rx_frame (min)\""
// =====================================================================
class I2sMasterTestplan extends TestplanSuite {
  import I2sEvent._
  import I2sMasterDriver._

  val label = "i2s"

  // -------------------------------------------------------------------
  //  PLAN. `def`, nie `val` - patrz guard w TestplanSuite.
  // -------------------------------------------------------------------
  def testplan : Seq[Testpoint] = Seq(

    // --- V1 ---------------------------------------------------------
    Testpoint("i2s_param_bounds", Stage.V1,
      "Wszystkie konfiguracje sa legalne dla DUT-a i dla testbenchu",
      checking = Seq("fclk / (2 * fs * 2 * slotWidth) calkowite i >= 1",
                     "width <= slotWidth, width <= 32 (dane testowe w Long)",
                     "halfDiv >= I2sCodecModel.minHalfDiv - okno waznosci bitu " +
                     "z modelu obejmuje oba mozliwe zbocza probkowania DUT-a")),

    Testpoint("i2s_tx_frame", Stage.V1,
      "Ramka z io.tx wychodzi na SDO: L przy WS=0, R przy WS=1, MSB-first",
      stimulus = Seq("Dwie ramki z roznymi, niesymetrycznymi L i R"),
      checking = Seq("Monitor dekoduje dokladnie te ramki w tej kolejnosci",
                     "Zamiana kanalow albo kolejnosci bitow daje inna ramke")),

    Testpoint("i2s_ws_one_bit_delay", Stage.V1,
      "MSB pojawia sie jeden okres SCK po zboczu WS (format Philips)",
      // Najczestszy blad I2S: pomylenie z left-justified. Monitor Philipsa
      // zdekoduje wtedy smieci, ale ten testpoint mowi WPROST, co jest nie tak.
      stimulus = Seq("Ramki z pojedynczym ustawionym MSB: (MSB, 0) i (0, MSB)"),
      checking = Seq("W surowym slocie pierwsza jedynka ma indeks 1, nie 0",
                     "Pelne dekodowanie ramek zgodne")),

    Testpoint("i2s_rx_frame", Stage.V1,
      "Ramka wystawiona przez kodek na SDI pojawia sie na io.rx",
      stimulus = Seq("Kodek wysyla dwie rozne ramki, zmieniajac SDI przy opadajacym SCK"),
      checking = Seq("io.rx == log kodeka, ramka w ramke, wlacznie z cisza")),

    Testpoint("i2s_sd_stable_while_sck_high", Stage.V1,
      "SDO i WS zmieniaja sie tylko przy niskim SCK",
      stimulus = Seq("Wzorce 0x55../0xAA.. (zmiana na kazdej granicy bitu)",
                     "Pelne zera i jedynki na granicach slowa i ramki"),
      checking = Seq("Zero naruszen w I2sMonitor.check()",
                     "Ramki zdekodowane poprawnie")),

    Testpoint("i2s_sck_ws_timing", Stage.V1,
      "SCK i WS maja okresy wynikajace z generykow, bez tolerancji",
      stimulus = Seq("Kilka ramek ciszy"),
      checking = Seq("Polokres SCK wysoki i niski == halfDiv",
                     "slotWidth narastajacych SCK miedzy zboczami WS",
                     "Okres ramki == cyclesPerFrame, czyli fs dokladnie")),

    // --- V2 ---------------------------------------------------------
    Testpoint("i2s_full_duplex", Stage.V2,
      "TX i RX jednoczesnie, niezalezne dane",
      stimulus = Seq("8 losowych ramek w kazda strone, seed staly"),
      checking = Seq("Scoreboard TX i RX, kazdy osobno")),

    Testpoint("i2s_tx_frame_atomic", Stage.V2,
      "Ramka nie jest skladana z kanalow dwoch roznych transakcji",
      stimulus = Seq("Push w losowych momentach wzgledem granicy ramki"),
      checking = Seq("Kazda ramka na pinach to cisza albo kolejna przyjeta",
                     "Wystapila co najmniej jedna luka (test naprawde ja wywolal)")),

    Testpoint("i2s_tx_underrun", Stage.V2,
      "Brak danych na granicy ramki daje cisze i impuls underrun",
      stimulus = Seq("Dwie ramki, przerwa na 3 ramki, dwie ramki"),
      checking = Seq("W przerwie same zera, liczba luk == liczba impulsow",
                     "Po przerwie ramki bez przesuniecia kanalow")),

    Testpoint("i2s_padding", Stage.V2,
      "width < slotWidth: padding TX zerowy, padding RX ignorowany",
      stimulus = Seq("TX: probki z samych jedynek",
                     "RX: kodek wypelnia padding jedynkami"),
      checking = Seq("Bity slotu za LSB sa zerami na SDO",
                     "io.rx bez smieci z paddingu")),

    Testpoint("i2s_lsb_across_ws", Stage.V2,
      "width == slotWidth: LSB kanalu wpada w pierwszy bit slotu drugiego kanalu",
      // Jedyna konfiguracja, w ktorej opoznienie o bit przekracza granice
      // slotu. Implementacja liczaca bity od zbocza WS gubi tu LSB.
      stimulus = Seq("Ramki (1, 0), (0, 1), (max, 0), (0, max) w obie strony"),
      checking = Seq("Prawy slot ramki (1, 0) ma surowe bity 1, 0, 0, ...",
                     "TX i RX zdekodowane poprawnie")),

    Testpoint("i2s_startup", Stage.V2,
      "Pierwsza ramka po resecie jest kompletna i poprzedzona cisza",
      stimulus = Seq("Kodek i push gotowe od razu po resecie"),
      checking = Seq("WS=1 po resecie, SDO nisko przed pierwsza ramka",
                     "Pierwszy polokres SCK pelny",
                     "io.rx nie zglasza ramki czesciowej: pierwsze rx == pierwsza ramka kodeka")),

    // --- V2: z przegladu publicznych testbenchy (naglowek) -----------
    Testpoint("i2s_loopback", Stage.V2,
      "Petla SDO -> SDI: odbiornik DUT-a odtwarza strumien nadajnika",
      stimulus = Seq("SDI = SDO z opoznieniem outDelay, model kodeka wylaczony",
                     "16 losowych ramek z losowymi lukami"),
      checking = Seq("io.rx == ramki na pinach == txOrder, ramka w ramke",
                     "Scoreboard nie przechodzi przez logike bitow modelu kodeka")),

    Testpoint("i2s_rx_leading_edge_transmitter", Stage.V2,
      "Nadajnik taktujacy SD narastajacym SCK - spec dopuszcza oba zbocza",
      stimulus = Seq("Kodek zmienia SDI outDelay po NARASTAJACYM SCK",
                     "8 losowych ramek"),
      checking = Seq("io.rx == log kodeka",
                     "Czyli DUT zatrzaskuje SDI dokladnie na narastajacym SCK; " +
                     "probkowanie cykl pozniej zlapaloby juz nastepny bit")),

    Testpoint("i2s_rx_word_length_mismatch", Stage.V2,
      "Kodek o innej dlugosci slowa niz width - MSB-first ma to wchlonac",
      stimulus = Seq("Slowo dluzsze o do 8 bitow, w granicach slotu (gdy jest padding)",
                     "Slowo o polowe krotsze"),
      checking = Seq("Dluzsze: io.rx == najstarsze width bitow, nadmiar LSB odciety",
                     "Krotsze: io.rx == slowo << (width - wordWidth), brakujace LSB zerami")),

    Testpoint("i2s_sdo_on_sck_fall", Stage.V2,
      "SDO i WS zmieniaja sie w tym samym cyklu co opadajace SCK",
      stimulus = Seq("Wzorce zmieniajace SDO na kazdej granicy bitu"),
      checking = Seq("Kazda zmiana SDO/WS 0 cykli po opadajacym SCK",
                     "Odbiornik dostaje caly polokres na setup; przy halfDiv = 1 " +
                     "kazdy cykl opoznienia to zero zapasu")),

    // --- V3 ---------------------------------------------------------
    Testpoint("i2s_random_reset", Stage.V3,
      "Reset w losowym momencie ramki zostawia piny w stanie spoczynku",
      stimulus = Seq("Reset asynchroniczny w losowym miejscu ramki, 3 razy",
                     "Po resecie czysta tura TX i RX"),
      checking = Seq("W resecie SCK=0, WS=1, SDO=0, io.rx i underrun nisko",
                     "Tura po resecie zgodna ze scoreboardem")),

    Testpoint("i2s_stress_random", Stage.V3,
      "Losowe dane w obie strony, losowe luki na io.tx",
      stimulus = Seq("32 ramki kazdy kierunek, luki 0..1 ramki, seed staly"),
      checking = Seq("Scoreboard TX i RX co do bitu")),

    // --- odlozone ---------------------------------------------------
    Testpoint("i2s_left_justified", Stage.V2,
      "Format left-justified jako generyk",
      checking = Seq("MSB w bits(0) slotu, reszta jak Philips")),

    Testpoint("i2s_right_justified", Stage.V2,
      "Format right-justified (LSB-justified) jako generyk",
      checking = Seq("LSB w ostatnim bicie slotu, bez opoznienia o bit")),

    Testpoint("i2s_mclk_output", Stage.V2,
      "Wyjscie MCLK = N * fs, fazowo zwiazane z SCK",
      checking = Seq("Okres MCLK i relacja zboczy MCLK/SCK")),

    Testpoint("i2s_stop_on_frame_boundary", Stage.V2,
      "Wylaczenie mastera konczy biezaca ramke i dopiero wtedy stawia SCK",
      checking = Seq("Ostatnia ramka kompletna, SCK=0 i WS=1 po zatrzymaniu"))

  ) ++ StreamConformance.testpoints("tx")

  // -------------------------------------------------------------------
  //  KONFIGURACJE (§4.2)
  //
  //  Wszystkie w Hz calkowitych (kHz/Hz na Int), zadnych literalow
  //  Double - 45.1584 MHz jako Double to juz zaokraglenie.
  //
  //  Ramka to 2 * slotWidth * 2 * halfDiv cykli, czyli 128..1024 cykli.
  //  Tanio, wiec - inaczej niz w I2C - wszystko leci na wszystkich
  //  konfiguracjach i nie ma zbioru `heavy`.
  // -------------------------------------------------------------------
  case class Cfg(name : String, g : I2sGenerics)

  val configs = Seq(
    // 49.152 MHz / (48k * 64 * 2) = 8
    Cfg("fs48k",  I2sGenerics(49152 kHz, 48 kHz, width = 16, slotWidth = 32)),
    // 45.1584 MHz / (44.1k * 64 * 2) = 8; 24 bity w slocie 32
    Cfg("fs44k1", I2sGenerics(45158400 Hz, 44100 Hz, width = 24, slotWidth = 32)),
    // 24.576 MHz / (48k * 32 * 2) = 8; bez paddingu - LSB za zboczem WS
    Cfg("w16s16", I2sGenerics(24576 kHz, 48 kHz, width = 16, slotWidth = 16)),
    // 6.144 MHz / (48k * 64 * 2) = 1 - DOLNA GRANICA: SCK przelacza sie
    // co cykl, zapas modelu kodeka = 0 (wyprowadzenie w
    // I2sCodecModel.minHalfDiv). Odpowiednik qmin z I2C.
    Cfg("min",    I2sGenerics(6144 kHz, 48 kHz, width = 16, slotWidth = 32)),
    // Skrajne szerokosci i fs z retroSoC/i2s (8..32 bity, 8..96 kHz).
    // 2.048 MHz / (8k * 16 * 2 * 2) = 4; 8 bitow w slocie 16
    Cfg("w8fs8k",   I2sGenerics(2048 kHz,  8 kHz, width =  8, slotWidth = 16)),
    // 24.576 MHz / (96k * 32 * 2 * 2) = 2; pelne 32 bity - Frame(Long)
    // na granicy i LSB za zboczem WS, jak w w16s16
    Cfg("w32fs96k", I2sGenerics(24576 kHz, 96 kHz, width = 32, slotWidth = 32))
  )

  for (Cfg(cfgName, g) <- configs) {

    var txPort : StreamPortHandle = null

    lazy val dut : SimCompiled[I2sMaster] = Config.sim
      .withFstWave
      .workspaceName(s"${label}_${cfgName}_${SimBackend.default.label}")
      .compile {
        val d = I2sMaster(g)
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
        dut.doSim(s"${label}_${cfgName}_$name", seed = 42) { d => body(setup(d)) }
      }

    val m = full(g)

    // =================================================================
    //  V1
    // =================================================================

    scenario("i2s_tx_frame") { e =>
      val fs = Seq(Frame(mask(g, 0xA5C3F00FL), mask(g, 0x5A3C0FF0L)),
                   Frame(mask(g, 0x12345678L), mask(g, 0x87654321L)))
      fs.foreach(e.push)
      e.settle()

      e.expectTx()
      assert(e.txOrder.flatten.toSeq == fs, s"DUT przyjal ${e.txOrder.flatten}")
    }

    scenario("i2s_ws_one_bit_delay") { e =>
      val msb = 1L << (g.width - 1)
      Seq(Frame(msb, 0), Frame(0, msb)).foreach(e.push)
      e.settle()
      e.expectTx()

      // bits(0) slotu to LSB poprzedniego slowa. Philips: MSB w bits(1).
      // Left-justified dalby bits(0), right-justified bits(slotWidth - width).
      for (ws <- Seq(false, true)) {
        val ch = if (ws) "prawego" else "lewego"
        val s  = e.mon.slots.find(s => s.ws == ws && s.bits.contains(true))
        assert(s.isDefined, s"brak slotu $ch kanalu z danymi")
        val at = s.get.bits.indexOf(true)
        assert(at == 1, s"MSB $ch kanalu w bicie $at slotu, oczekiwano 1")
      }
    }

    scenario("i2s_rx_frame") { e =>
      e.codec.send(Frame(mask(g, 0x0F1E2D3CL), mask(g, 0xF0E1D2C3L)),
                   Frame(mask(g, 0x80000001L), mask(g, 0x7FFFFFFEL)))
      e.settle()
      e.expectRx()
    }

    scenario("i2s_sd_stable_while_sck_high") { e =>
      Seq(Frame(mask(g, 0x55555555L), mask(g, 0xAAAAAAAAL)),
          Frame(m, 0), Frame(0, m), Frame(m, m)).foreach(e.push)
      e.settle()
      e.mon.check()      // wlasciwa asercja testpointu
      e.expectTx()       // i dowod, ze SDO faktycznie sie ruszalo
    }

    scenario("i2s_sck_ws_timing") { e =>
      e.waitFrames(4)
      // halfDiv = fclk / (2 * fs * 2 * slotWidth), calkowite z konstrukcji
      // (i2s_param_bounds). Tolerancja 0: SCK i WS to rejestry na jednym
      // zegarze, nie ma czego zaokraglac. Nieparzysty dzielnik pelnego
      // okresu (gdyby kiedys byl) wprowadzi tu +-1 na polokres.
      val hi = e.mon.sckHigh.distinct
      val lo = e.mon.sckLow.distinct
      assert(hi == Seq(g.halfDiv), s"SCK wysoki: $hi cykli, oczekiwano ${g.halfDiv}")
      assert(lo == Seq(g.halfDiv), s"SCK niski: $lo cykli, oczekiwano ${g.halfDiv}")

      assert(e.mon.sckPerSlot.size >= 6, s"za malo slotow: ${e.mon.sckPerSlot.size}")
      val ps = e.mon.sckPerSlot.distinct
      assert(ps == Seq(g.slotWidth), s"SCK na slot: $ps, oczekiwano ${g.slotWidth}")

      // cyclesPerFrame * fs == fclk dokladnie, wiec to jest pomiar fs.
      val fp = e.mon.framePeriods.distinct
      assert(fp == Seq(g.cyclesPerFrame), s"okres ramki $fp, oczekiwano ${g.cyclesPerFrame}")
      e.mon.check()
    }

    // =================================================================
    //  StreamConformance na io.tx
    //
    //  io.tx jest WEJSCIEM: valid i payload steruje testbench, wiec
    //  payloadStable i quietDuringReset sprawdzaja tu glownie drivera.
    //  Czesc skierowana w DUT-a to noStall: bufor jednoramkowy musi
    //  przyjac kolejna ramke najpozniej na nastepnej granicy.
    // =================================================================

    scenario("tx_payload_stable") { e =>
      StreamConformance.payloadStable(e.cd, txPort)
      // <= 1 ramka czekania na zwolnienie bufora + zapas x2
      StreamConformance.noStall(e.cd, txPort, 2 * g.cyclesPerFrame)

      val rng = new Random(11)
      val fs  = Seq.fill(6)(randFrame(g, rng))
      fs.foreach(e.push)
      e.settle()
      e.expectTx()

      // Zasilany bez przerw DUT nie moze wstawic luki miedzy ramkami.
      val body = e.txOrder.dropWhile(_.isEmpty).take(fs.size)
      assert(body.forall(_.isDefined), s"luka mimo ciaglego zasilania: $body")
    }

    scenario("tx_reset_quiet") { e =>
      StreamConformance.quietDuringReset(e.cd, txPort)

      Seq(Frame(mask(g, 0x1234L), mask(g, 0x5678L))).foreach(e.push)
      e.settle()
      e.expectTx()

      // Reset przy jalowym driverze; waitActiveEdge, nie waitSampling -
      // patrz phy_cmd_reset_quiet w I2cMasterTestplan.
      e.cd.assertReset()
      e.cd.waitActiveEdge(20)
      e.cd.deassertReset()
      e.resync()

      Seq(Frame(mask(g, 0x9ABCL), mask(g, 0xDEF0L))).foreach(e.push)
      e.settle()
      e.expectTx()
    }

    // =================================================================
    //  V2
    // =================================================================

    scenario("i2s_full_duplex") { e =>
      val rng = new Random(1)
      e.codec.send(Seq.fill(8)(randFrame(g, rng)) : _*)
      Seq.fill(8)(randFrame(g, rng)).foreach(e.push)
      e.settle()
      e.expectTx()
      e.expectRx()
    }

    scenario("i2s_tx_frame_atomic") { e =>
      val rng = new Random(2)
      for (_ <- 0 until 12) {
        e.push(randFrame(g, rng))
        e.cd.waitSampling(rng.nextInt(2 * g.cyclesPerFrame))
      }
      e.settle()
      e.expectTx()

      val first = e.txOrder.indexWhere(_.isDefined)
      val last  = e.txOrder.lastIndexWhere(_.isDefined)
      assert(e.txOrder.slice(first, last).contains(None),
             "zadnej luki miedzy ramkami - seed nie trafil w granice, zmien rng")
    }

    scenario("i2s_tx_underrun") { e =>
      val a = Seq(Frame(mask(g, 0x1111L), mask(g, 0x2222L)),
                  Frame(mask(g, 0x3333L), mask(g, 0x4444L)))
      val b = Seq(Frame(mask(g, 0x5555L), mask(g, 0x6666L)),
                  Frame(mask(g, 0x7777L), mask(g, 0x8888L)))
      a.foreach(e.push)
      e.waitFrames(3)
      b.foreach(e.push)
      e.settle()

      // expectTx sprawdza, ze kazda luka to cisza i ze liczba cich ramek
      // na pinach == liczba impulsow underrun (jedna lista, dwa zrodla).
      e.expectTx()
      val gap = e.txOrder.slice(e.txOrder.indexWhere(_.contains(a.last)),
                                e.txOrder.indexWhere(_.contains(b.head)))
      assert(gap.count(_.isEmpty) >= 2, s"za malo luk w przerwie: $gap")
    }

    if (g.paddingBits > 0) scenario("i2s_padding") { e =>
      e.codec.padFill = true
      val fs = Seq(Frame(m, m), Frame(m, 0), Frame(0, m))
      e.codec.send(fs : _*)
      fs.foreach(e.push)
      e.settle()
      e.expectTx()
      e.expectRx()

      // bits(0) = LSB poprzedniego slowa, bits(1..width) = slowo, reszta
      // to padding; ostatni bit paddingu wpada w bits(0) nastepnego slotu.
      val slots = e.mon.slots
      for (Seq(s, next) <- slots.sliding(2) if s.bits.slice(1, 1 + g.width).contains(true)) {
        val pad = s.bits.drop(1 + g.width) :+ next.bits(0)
        assert(pad.size == g.paddingBits && pad.forall(!_),
               s"padding na SDO: $pad (ws=${s.ws})")
      }
    }

    if (g.paddingBits == 0) scenario("i2s_lsb_across_ws") { e =>
      val fs = Seq(Frame(1, 0), Frame(0, 1), Frame(m, 0), Frame(0, m))
      e.codec.send(fs : _*)
      fs.foreach(e.push)
      e.settle()
      e.expectTx()
      e.expectRx()

      val want = true +: Vector.fill(g.slotWidth - 1)(false)
      assert(e.mon.slots.exists(s => s.ws && s.bits == want),
             "LSB lewego kanalu (1, 0) nie trafil w pierwszy bit prawego slotu")
    }

    scenario("i2s_startup") { e =>
      // setup czeka 5 cykli po resecie; pierwsze opadajace WS jest slot
      // pozniej (>= 64 cykle), wiec tu jestesmy w pustym slocie wstepnym.
      assert(e.d.io.pins.ws.toBoolean, "WS nisko zaraz po resecie")

      val f = Frame(mask(g, 0xCAFEL), mask(g, 0xBEEFL))
      e.codec.send(f)
      e.push(f)
      e.settle()

      assert(!e.mon.preSyncSdoHigh, "SDO wysoko przed pierwsza ramka")
      assert(e.mon.sckHigh.head == g.halfDiv,
             s"pierwszy polokres SCK ${e.mon.sckHigh.head}, oczekiwano ${g.halfDiv}")
      assert(e.rxs.nonEmpty && e.rxs.head == e.codec.log.head.getOrElse(Silence),
             s"pierwsze rx ${e.rxs.headOption} to nie pierwsza ramka kodeka")
      e.expectTx()
      e.expectRx()
    }

    // =================================================================
    //  V2 z przegladu zrodel
    // =================================================================

    // PG308 "Loopback TX-RX", Microchip Initiator/Target, retroSoC loop mode.
    scenario("i2s_loopback") { e =>
      e.codec.loopback = true
      val rng = new Random(21)
      for (_ <- 0 until 16) {
        e.push(randFrame(g, rng))
        if (rng.nextBoolean()) e.cd.waitSampling(rng.nextInt(g.cyclesPerFrame))
      }
      e.settle()
      e.expectTx()

      // TX i RX licza ramki od tej samej granicy (pierwsze opadajace WS),
      // wiec k-te rx to k-ta ramka na pinach, luki wlacznie.
      val n    = e.txOrder.lastIndexWhere(_.isDefined) + 1
      val sent = e.txOrder.take(n).map(_.getOrElse(Silence)).toSeq
      assert(e.rxs.size >= n, s"io.rx: ${e.rxs.size} ramek, nadano $n")
      assert(e.rxs.take(n).toSeq == sent,
             s"petla rozjechana\n  rx = ${e.rxs.take(n).toSeq}\n  tx = $sent")
    }

    // Spec: nadajnik moze taktowac SD dowolnym zboczem, odbiornik zatrzaskuje
    // na narastajacym. Tu bit zmienia sie outDelay PO narastajacym SCK, czyli
    // jest wazny od poprzedniego narastajacego do tego zbocza wlacznie.
    // Domyslny model (trailing) zostawia DUT-owi okno obejmujace tez cykl
    // po narastajacym; ten test to okno zamyka.
    scenario("i2s_rx_leading_edge_transmitter") { e =>
      e.codec.leadingEdge = true
      val rng = new Random(22)
      e.codec.send(Seq.fill(8)(randFrame(g, rng)) : _*)
      e.waitFrames(8)
      e.settle()
      e.expectRx()
    }

    // Beyond Circuits t18 + Microchip UG: odbiornik nie zna dlugosci slowa
    // nadajnika. Oczekiwanie liczy I2sFormat.transfer - ta sama regula co
    // w scoreboardzie slave'a i w kontrakcie vertebra-hil.
    scenario("i2s_rx_word_length_mismatch") { e =>
      val rng = new Random(23)

      def phase(cw : Int) : Unit = {
        e.codec.wordWidth = cw
        val cm = (1L << cw) - 1
        val fs = Seq.fill(6)(Frame(rng.nextLong() & cm, rng.nextLong() & cm))
        e.codec.send(fs : _*)
        e.waitFrames(fs.size)
        e.settle()

        def conv(v : Long) : Long = I2sFormat.transfer(v, cw, g.slotWidth, g.width)

        val n   = e.codec.log.lastIndexWhere(_.isDefined) + 1
        val exp = e.codec.log.take(n).map(_.getOrElse(Silence))
                   .map(f => Frame(conv(f.left), conv(f.right))).toSeq
        assert(e.rxs.size >= n, s"[cw=$cw] io.rx: ${e.rxs.size} ramek, kodek wyslal $n")
        assert(e.rxs.take(n).toSeq == exp,
               s"[cw=$cw, width=${g.width}]\n  got = ${e.rxs.take(n).toSeq}\n  exp = $exp")
      }

      // Dluzsze slowo tylko tam, gdzie slot ma na nie miejsce.
      if (g.paddingBits > 0) {
        phase(math.min(g.width + 8, g.slotWidth))
        e.resync()        // ramka w toku to cisza, wiec zmiana wordWidth jej nie dotyczy
      }
      phase(math.max(1, g.width / 2))
    }

    // Beyond Circuits t19: SD zmieniane dopiero 2 cykle zegara po SCK
    // opisane jako odstepstwo od spec. Nasz check() lapie tylko zmiane
    // przy WYSOKIM SCK, wiec przy duzym halfDiv skew by przeszedl.
    scenario("i2s_sdo_on_sck_fall") { e =>
      Seq(Frame(mask(g, 0x55555555L), mask(g, 0xAAAAAAAAL)),
          Frame(m, 0), Frame(0, m)).foreach(e.push)
      e.settle()
      e.expectTx()

      val dl = e.mon.changeDelays
      assert(dl.size > 2 * g.width, s"za malo zmian SDO/WS do oceny: ${dl.size}")
      val late = dl.distinct.filter(_ != 0).sorted
      assert(late.isEmpty,
             s"zmiany SDO/WS ${late.mkString(", ")} cykli po opadajacym SCK, oczekiwano 0")
    }

    // =================================================================
    //  V3
    // =================================================================

    scenario("i2s_random_reset") { e =>
      val rng = new Random(5)
      val d   = e.d

      for (_ <- 0 until 3) {
        fork {
          e.cd.waitSampling(rng.nextInt(2 * g.cyclesPerFrame) + g.halfDiv)
          e.cd.assertReset()
          e.cd.waitActiveEdge(5)
          assert(!d.io.pins.sck.toBoolean && d.io.pins.ws.toBoolean &&
                 !d.io.pins.sdo.toBoolean, "reset nie ustawil pinow w spoczynek")
          assert(!d.io.rx.valid.toBoolean && !d.io.underrun.toBoolean,
                 "io.rx albo underrun w trakcie resetu")
          e.cd.deassertReset()
        }

        // Ruch przerywany resetem - tresc nieistotna, wyrzucana w resync.
        e.codec.send(randFrame(g, rng), randFrame(g, rng))
        e.push(randFrame(g, rng)); e.push(randFrame(g, rng))
        e.waitFrames(3)       // > 2 ramki + halfDiv, reset juz byl
        e.resync()

        val f = randFrame(g, rng)
        e.codec.send(f); e.push(f)
        e.settle()
        e.expectTx()
        e.expectRx()
      }
    }

    scenario("i2s_stress_random") { e =>
      val rng = new Random(7)
      e.codec.send(Seq.fill(32)(randFrame(g, rng)) : _*)
      for (_ <- 0 until 32) {
        e.push(randFrame(g, rng))
        if (rng.nextBoolean()) e.cd.waitSampling(rng.nextInt(g.cyclesPerFrame))
      }
      e.settle()
      e.expectTx()
      e.expectRx()
    }
  }

  // -------------------------------------------------------------------
  //  Bez symulacji: tylko parametry (§4.3).
  // -------------------------------------------------------------------
  testpoint("i2s_param_bounds") {
    configs.foreach { case Cfg(n, g) =>
      if (g.dividerExact)
        info(f"$n%-7s halfDiv=${g.halfDiv}%3d  ramka=${g.cyclesPerFrame}%5d cykli  " +
             f"padding=${g.paddingBits}%2d  zapas modelu=${g.halfDiv - I2sCodecModel.minHalfDiv}%d")
      else
        info(s"$n: halfDiv niecalkowite = ${g.halfDivExact}")
    }

    val bad = configs.filterNot(_.g.isLegal)
    assert(bad.isEmpty, s"nielegalne dla DUT-a: ${bad.map(_.name).mkString(", ")}")

    // Granica TESTBENCHU. Objaw przekroczenia jest mylacy: RX przesuniety
    // o bit przy czystym TX, co wyglada jak blad probkowania w DUT-cie.
    val blind = configs.filter(c => c.g.dividerExact && c.g.halfDiv < I2sCodecModel.minHalfDiv)
    assert(blind.isEmpty,
           s"model kodeka nie nadaza: ${blind.map(_.name).mkString(", ")} " +
           s"(minHalfDiv = ${I2sCodecModel.minHalfDiv})")

    val wide = configs.filter(_.g.width > 32)
    assert(wide.isEmpty, s"width > 32 nie miesci sie w Frame(Long): ${wide.map(_.name)}")
  }

  // -------------------------------------------------------------------
  //  ODLOZONE
  // -------------------------------------------------------------------
  unimplemented("i2s_left_justified",
    "DUT ma tylko format Philips; format jako generyk pozniej")

  unimplemented("i2s_right_justified",
    "DUT ma tylko format Philips; razem z i2s_left_justified")

  unimplemented("i2s_mclk_output",
    "DUT nie wystawia MCLK")

  unimplemented("i2s_stop_on_frame_boundary",
    "DUT nie ma io.enable - zegar biegnie od zwolnienia resetu")

  unimplemented("tx_backpressure",
    "ready tego portu steruje DUT, nie testbench; wzorce po stronie valid " +
    "(luki w dowolnym miejscu ramki) pokrywaja i2s_tx_frame_atomic i i2s_tx_underrun")

  unimplemented("tx_stress_with_rand_reset",
    "valid steruje testbench, a reset w i2s_random_reset leci z forka - driver " +
    "trzyma valid przez reset, wiec quietDuringReset padlby z definicji; " +
    "wymaga drivera swiadomego resetu")
}
