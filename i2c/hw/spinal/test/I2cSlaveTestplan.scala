package newhope.i2c

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
//  LOOPBACK - master i slave w jednej elaboracji.
//
//  DLACZEGO TAK, A NIE PROGRAMOWY MASTER. Symulacja SpinalSim kompiluje
//  JEDEN komponent, wiec zeby I2cMaster mogl pogadac z I2cSlave, oba
//  musza siedziec w jednym module. Alternatywa - programowy master
//  w Scali, analogicznie do I2cSlaveModel - jest w planie jako
//  slv_abort_mid_byte i spolka; tego, czego nie da sie tu sprawdzic,
//  nie da sie WLASNIE dlatego, ze RTL-owy master jest poprawny
//  i komendy ma calobajtowe.
//
//  CO RATUJE TEN UKLAD PRZED "DWA DUT-y, ten sam blad, test przechodzi":
//   1. I2cMaster jest zweryfikowany NIEZALEZNIE od tego slave'a
//      (I2cMasterTestplan, przeciwko I2cSlaveModel),
//   2. I2cMonitor dekoduje magistrale wlasnym filtrem i wlasnymi
//      niezmiennikami - jest trzecia strona, ktora nie wierzy zadnemu
//      z DUT-ow.
//
//  MAGISTRALA. Wired-AND robimy w RTL, a piny wyprowadzamy na zewnatrz
//  jako zwykly I2cPins - dzieki temu I2cBusModel i I2cMonitor dzialaja
//  BEZ ZMIAN, razem z bus.sclPull / bus.stretch do wstrzykiwania z boku.
//
//  mstScl/slvScl/mstSda/slvSda: wyjscia diagnostyczne. Po wired-AND nie
//  da sie odroznic "master trzyma SCL" od "slave rozciaga zegar", a to
//  jest dokladnie ta roznica, ktora mierza testy slv_stretch_*.
// =====================================================================
case class I2cLoopbackIo() extends Bundle {
  val pins = master(I2cPins())

  // --- strona mastera ---
  val cmd = slave  Stream (I2cCmd())
  val rsp = master Flow   (I2cRsp())

  // --- strona slave'a ---
  val address = in Bits (7 bits)
  val start   = out Bool()
  val stop    = out Bool()
  val select  = master Flow (Bool())
  val rx      = master Stream (Bits(8 bits))
  val rxAck   = in Bool()
  val tx      = slave  Stream (Bits(8 bits))
  val txAck   = master Flow (Bool())

  // --- diagnostyka: co ktora strona trzyma ---
  val mstScl = out Bool()
  val slvScl = out Bool()
  val mstSda = out Bool()
  val slvSda = out Bool()
}

case class I2cLoopback(g        : I2cGenerics,
                       buildPhy : I2cGenerics => I2cPhyBase = I2cMasterBuild.table)
    extends Component {

  val io = I2cLoopbackIo()

  val mst = I2cMaster(g, buildPhy)
  val slv = I2cSlave(g)

  // Open-drain: linia jest niska, gdy KTOKOLWIEK ja sciaga. Petla
  // zamyka sie przez testbench (I2cBusModel przepisuje write -> read),
  // wiec kazdy widzi drugiego z opoznieniem jednego cyklu - tak samo,
  // jak w kazdym innym tescie tej magistrali.
  io.pins.scl.write := mst.io.pins.scl.write && slv.io.pins.scl.write
  io.pins.sda.write := mst.io.pins.sda.write && slv.io.pins.sda.write

  mst.io.pins.scl.read := io.pins.scl.read
  mst.io.pins.sda.read := io.pins.sda.read
  slv.io.pins.scl.read := io.pins.scl.read
  slv.io.pins.sda.read := io.pins.sda.read

  io.mstScl := mst.io.pins.scl.write
  io.slvScl := slv.io.pins.scl.write
  io.mstSda := mst.io.pins.sda.write
  io.slvSda := slv.io.pins.sda.write

  mst.io.cmd << io.cmd
  io.rsp     << mst.io.rsp

  slv.io.address := io.address
  io.start       := slv.io.start
  io.stop        := slv.io.stop
  io.select      << slv.io.select
  io.rx          << slv.io.rx
  slv.io.rxAck   := io.rxAck
  slv.io.tx      << io.tx
  io.txAck       << slv.io.txAck
}

// =====================================================================
//  HOST - atrapa warstwy 3 po stronie slave'a.
//
//  To jest lustrzane odbicie I2cMasterDriver: tam testbench udaje
//  warstwe 3 nad masterem (Aht10Ctrl), tu udaje warstwe 3 nad slavem.
//  Zadnej wiedzy o protokole nie ma - oddaje bajty z kolejki, na
//  odebrane odpowiada wedlug polityki.
//
//  ZWLOKI SA TU PO TO, ZEBY BYLY. rxDelay/txDelay to jedyny sposob
//  wymuszenia clock stretchingu od strony DUT-a; ustawione na zero daja
//  przypadek "warstwa 3 nadaza", ktory ma NIE rozciagac zegara
//  (slv_no_stretch_when_fast).
//
//  Tor rx jest napisany recznie, a nie przez StreamReadyRandomizer, bo
//  io.rxAck musi byc ustawione W TYM SAMYM delcie co io.rx.ready -
//  wartosc ACK jest probkowana dokladnie w takcie handshake'u.
// =====================================================================
class I2cSlaveHost(cd : ClockDomain, io : I2cLoopbackIo) {

  val rxBytes = mutable.ArrayBuffer[Int]()
  val txSent  = mutable.ArrayBuffer[Int]()
  val txAcks  = mutable.ArrayBuffer[Boolean]()
  val selects = mutable.ArrayBuffer[Boolean]()
  val txQueue = mutable.Queue[Int]()

  var starts = 0
  var stops  = 0

  /** Odpowiedz na odebrany bajt. Domyslnie zawsze ACK. */
  var rxAckOf : Int => Boolean = _ => true
  /** Zwloka warstwy 3 (w cyklach zegara systemowego) - czyli dlugosc
    * clock stretchingu, ktory slave ma z tego powodu wygenerowac. */
  var rxDelay : () => Int = () => 0
  var txDelay : () => Int = () => 0

  // Zadnego bajtu zastepczego TU NIE MA i nie moze byc. Podstawienie
  // czegokolwiek przy pustej kolejce zamienia "warstwa 3 nie zdazyla"
  // na "warstwa 3 podala inny bajt", czyli maskuje dokladnie ten stan,
  // ktory slave ma obslugiwac rozciaganiem zegara. Host czeka - a
  // zaglodzenie toru tx wychodzi jako SimTimeout, glosno i w jednym
  // miejscu. Kosztowalo to jeden fałszywy blad w slv_stress_random.

  def clear() : Unit = {
    rxBytes.clear(); txSent.clear(); txAcks.clear(); selects.clear()
    txQueue.clear(); starts = 0; stops = 0
  }

  def start() : Unit = {
    io.rx.ready #= false
    io.tx.valid #= false
    io.rxAck    #= true

    cd.onSamplings {
      if (io.start.toBoolean) starts += 1
      if (io.stop.toBoolean)  stops  += 1
    }
    FlowMonitor(io.select, cd) { p => selects += p.toBoolean }
    FlowMonitor(io.txAck,  cd) { p => txAcks  += p.toBoolean }

    // --- tor rx: bajt przyszedl, decydujemy o ACK -----------------------
    fork {
      while (true) {
        cd.waitSamplingWhere(io.rx.valid.toBoolean)
        val v = io.rx.payload.toInt          // payload stabilny do handshake'u
        val n = rxDelay()
        if (n > 0) cd.waitSampling(n)        // tu slave trzyma SCL
        io.rxAck    #= rxAckOf(v)
        io.rx.ready #= true
        cd.waitSampling()
        io.rx.ready #= false
        rxBytes += v
      }
    }

    // --- tor tx: slave prosi o bajt podnoszac ready ---------------------
    // Czekamy na ready, a nie wystawiamy valid z gory, bo inaczej zwloka
    // txDelay uplynelaby jeszcze przed transakcja i nigdy nie zamienila
    // sie w stretching.
    //
    // WYSCIG, O KTORYM TRZEBA WIEDZIEC: ten fork budzi sie WCZESNIEJ niz
    // watek testu. Slave podnosi tx.ready filterLatency+1 cykli po
    // opadajacym zboczu bitu ACK, a io.cmd.ready mastera dopiero po
    // pelnej cwiartce (fmp1M: 8 vs 25 cykli). Kto uzbraja txQueue PO
    // powrocie z write(addr), ten uzbraja za pozno - stad petla ponizej,
    // ktora czeka na bajt zamiast brac cokolwiek.
    fork {
      while (true) {
        cd.waitSamplingWhere(io.tx.ready.toBoolean)
        val n = txDelay()
        if (n > 0) cd.waitSampling(n)
        while (txQueue.isEmpty) cd.waitSampling()   // slave rozciaga zegar
        val v = txQueue.dequeue()
        io.tx.payload #= v
        io.tx.valid   #= true
        cd.waitSamplingWhere(io.tx.ready.toBoolean)
        io.tx.valid   #= false
        txSent += v
      }
    }
  }
}

/** Ile cykli slave trzymal SCL w dol PRZY PUSZCZONEJ linii mastera.
  * To jest operacyjna definicja clock stretchingu - po wired-AND nie
  * widac jej z zewnatrz. */
class StretchProbe(cd : ClockDomain, io : I2cLoopbackIo) {
  var cycles = 0
  def reset() : Unit = cycles = 0
  def start() : Unit = cd.onSamplings {
    if (io.mstScl.toBoolean && !io.slvScl.toBoolean) cycles += 1
  }
}

// =====================================================================
//  DRIVER strony mastera. Kopia I2cMasterDriver.cmd, bo tamten jest
//  otypowany na I2cMaster, a tu DUT-em jest I2cLoopback. Gdyby pojawil
//  sie trzeci uzytkownik, przepisac I2cMasterDriver na (ClockDomain,
//  Stream[I2cCmd]) i usunac to stad.
// =====================================================================
object I2cSlaveDriver {
  import I2cCmdMode._

  case class Env(d     : I2cLoopback,
                 bus   : I2cBusModel,
                 mon   : I2cMonitor,
                 host  : I2cSlaveHost,
                 probe : StretchProbe,
                 rsps  : mutable.Queue[I2cMasterDriver.Rsp])

  val addr = 0x38
  def addrW(a : Int = addr) : Int = a << 1
  def addrR(a : Int = addr) : Int = (a << 1) | 1

  def cmd(d    : I2cLoopback,
          mode : SpinalEnumElement[I2cCmdMode.type],
          data : Int     = 0,
          ack  : Boolean = true) : Unit = {
    d.io.cmd.valid        #= true
    d.io.cmd.payload.mode #= mode
    d.io.cmd.payload.data #= data
    d.io.cmd.payload.ack  #= ack
    d.clockDomain.waitSamplingWhere(d.io.cmd.ready.toBoolean)
    d.io.cmd.valid #= false
  }

  def start(d : I2cLoopback) : Unit                = cmd(d, START)
  def stop (d : I2cLoopback) : Unit                = cmd(d, STOP)
  def write(d : I2cLoopback, v : Int) : Unit       = cmd(d, WRITE, data = v)
  def read (d : I2cLoopback, ack : Boolean) : Unit = cmd(d, READ, ack = ack)

  /** Jak w I2cMasterTestplan: SimTimeout jest tu obowiazkowy, bo
    * waitSamplingWhere na cmd.ready nie ma wlasnego limitu, a slave
    * potrafi rozciagnac zegar w nieskonczonosc (i to jest jego
    * udokumentowane zachowanie, nie awaria). */
  def setup(d : I2cLoopback, bits : Int = 400) : Env = {
    SimTimeout(bits.toLong * 4 * d.g.quarterCycles * 10 * 4)

    val bus   = new I2cBusModel(d.clockDomain, d.io.pins)
    val mon   = new I2cMonitor(d.clockDomain, bus)
    val host  = new I2cSlaveHost(d.clockDomain, d.io)
    val probe = new StretchProbe(d.clockDomain, d.io)
    val rsps  = mutable.Queue[I2cMasterDriver.Rsp]()

    d.io.cmd.valid     #= false
    d.io.address       #= addr
    d.io.pins.scl.read #= true
    d.io.pins.sda.read #= true
    d.clockDomain.forkStimulus(period = 10)

    FlowMonitor(d.io.rsp, d.clockDomain) { p =>
      rsps.enqueue(I2cMasterDriver.Rsp(p.data.toInt, p.ack.toBoolean))
    }
    bus.start(); mon.start(); host.start(); probe.start()
    d.clockDomain.waitSampling(5)
    Env(d, bus, mon, host, probe, rsps)
  }
}

// =====================================================================
//  TESTPLAN I2cSlave.
//
//  CO TEN POZIOM SPRAWDZA, A CZEGO NIE
//  -----------------------------------
//  Timing SCL, filtr i generacja zbocz naleza do mastera i sa juz
//  sprawdzone (I2cPhyTestplan, I2cMasterTestplan) - tu sie nie
//  powtarzaja. Slave odpowiada za cztery rzeczy i o nich jest ten plan:
//    - dekodowanie adresu i kierunku,
//    - zlozenie/rozlozenie bajtu na wlasciwych zboczach,
//    - kto trzyma SDA w dziewiatym bicie (ACK w obie strony),
//    - clock stretching: czy wystepuje, kiedy trzeba, i czy NIE
//      wystepuje, kiedy nie trzeba.
//
//  Ostatni punkt jest jedynym powodem, dla ktorego I2cLoopback ma
//  wyjscia mstScl/slvScl - patrz StretchProbe.
//
//  Debugowanie pojedynczej komorki (kazdy testpoint ma fale FST):
//    sbt "testOnly *I2cSlaveTestplan -- -z \"slv_read_byte (qtight)\""
// =====================================================================
class I2cSlaveTestplan extends TestplanSuite {
  import I2cEvent._
  import I2cMasterDriver.{evByte, evTxn}
  import I2cSlaveDriver._

  val planLabel = "slv"

  // -------------------------------------------------------------------
  //  PLAN. `def`, nie `val` - patrz guard w TestplanSuite.
  // -------------------------------------------------------------------
  def testplan : Seq[Testpoint] = Seq(

    // --- V1: adres, kierunek, jeden bajt w kazda strone --------------
    Testpoint("slv_addr_ack", Stage.V1,
      "Slave potwierdza wlasny adres i zglasza kierunek w io.select",
      stimulus = Seq("START, bajt adresowy 0x70 (0x38 + W), STOP"),
      checking = Seq("Dziewiaty bit sciagniety do zera przez slave'a",
                     "io.select strzela raz, z payloadem False (zapis)",
                     "io.start i io.stop strzelaja po razie")),

    Testpoint("slv_addr_nack", Stage.V1,
      "Obcy adres nie jest potwierdzany i nie otwiera transakcji",
      stimulus = Seq("Bajt adresowy 0x72 (0x39 + W), po nim bajt danych"),
      checking = Seq("Oba dziewiate bity puszczone (NACK)",
                     "Zero zdarzen io.select, zero bajtow na io.rx",
                     "Slave nie dotyka SDA do konca transakcji")),

    Testpoint("slv_write_byte", Stage.V1,
      "Bajt zapisany przez mastera wychodzi na io.rx i jest potwierdzany",
      stimulus = Seq("Adres + W, bajt 0x5A, STOP"),
      checking = Seq("io.rx oddaje 0x5A dokladnie raz",
                     "Na magistrali 8 bitow 0x5A i ACK")),

    Testpoint("slv_read_byte", Stage.V1,
      "Bajt z io.tx wychodzi na magistrale MSB-first",
      stimulus = Seq("Adres + R, kolejka tx = 0x3C, READ z NACK, STOP"),
      checking = Seq("io.rsp.data mastera == 0x3C",
                     "io.select strzela z payloadem True")),

    Testpoint("slv_rx_nack", Stage.V1,
      "io.rxAck steruje dziewiatym bitem i konczy odbior",
      stimulus = Seq("Adres + W, bajt odrzucony przez warstwe 3, drugi bajt"),
      checking = Seq("Pierwszy bajt: NACK na magistrali, io.rsp.ack = false",
                     "Drugi bajt juz nie trafia na io.rx - slave wyszedl z transakcji")),

    Testpoint("slv_tx_ack_report", Stage.V1,
      "io.txAck odzwierciedla to, co master zrobil z dziewiatym bitem",
      stimulus = Seq("Dwa bajty odczytu: pierwszy z ACK, drugi z NACK"),
      checking = Seq("io.txAck: true, potem false",
                     "Po NACK slave puszcza linie i nie nadaje dalej")),

    Testpoint("slv_start_stop_events", Stage.V1,
      "START, RESTART i STOP sa rozpoznane i zglaszane w gore",
      stimulus = Seq("START, adres+W, bajt, RESTART, adres+W, bajt, STOP"),
      checking = Seq("io.start strzela dwa razy, io.stop raz",
                     "Po RESTART licznik bitow startuje od nowa - drugi bajt caly")),

    // --- V2: pelne transakcje i stretching ---------------------------
    Testpoint("slv_write_then_read", Stage.V2,
      "Zapis, RESTART, odczyt - jedna transakcja, oba kierunki",
      stimulus = Seq("Adres+W, 0xC3, RESTART, adres+R, 0x3C z NACK, STOP"),
      checking = Seq("Sekwencja zdarzen magistrali bez naruszen",
                     "io.rx = 0xC3, io.rsp mastera = 0x3C")),

    Testpoint("slv_multi_byte_write", Stage.V2,
      "Wiele bajtow w jednej transakcji zapisu",
      stimulus = Seq("Adres+W, trzy bajty 0x00 / 0xFF / 0x5A, STOP"),
      checking = Seq("io.rx oddaje dokladnie te trzy bajty w kolejnosci",
                     "Kazdy potwierdzony, zero dodatkowych zdarzen")),

    Testpoint("slv_multi_byte_read", Stage.V2,
      "Wiele bajtow odczytu, ostatni zamkniety NACK-iem",
      stimulus = Seq("Kolejka tx = 0x11 / 0x22 / 0x33, READ x3, ostatni NACK"),
      checking = Seq("Master sklada te same trzy bajty",
                     "io.txAck: true, true, false")),

    Testpoint("slv_stretch_rx", Stage.V2,
      "Zwloka warstwy 3 przy ACK rozciaga zegar zamiast psuc bajt",
      stimulus = Seq("rxDelay = 8 cwiartek na kazdy odebrany bajt"),
      checking = Seq("Slave trzymal SCL przy puszczonej linii mastera",
                     "Sekwencja na magistrali i tresc bajtow bez zmian")),

    Testpoint("slv_stretch_tx", Stage.V2,
      "Zwloka warstwy 3 przy podaniu bajtu rozciaga zegar",
      stimulus = Seq("txDelay = 8 cwiartek na kazdy nadany bajt"),
      checking = Seq("Slave trzymal SCL przy puszczonej linii mastera",
                     "Master sklada bajty bez zmian")),

    Testpoint("slv_no_stretch_when_fast", Stage.V2,
      "Gdy warstwa 3 nadaza, slave nie rozciaga zegara",
      // Kontrola dla dwoch testow wyzej: bez niej "stretching dziala"
      // przeszloby rowniez dla slave'a, ktory trzyma SCL zawsze.
      stimulus = Seq("rxDelay = txDelay = 0, zapis i odczyt po jednym bajcie"),
      checking = Seq("Licznik cykli rozciagniecia w granicach opoznienia filtra",
                     "Czyli: reakcja miesci sie w niskiej polowce SCL")),

    Testpoint("slv_address_runtime", Stage.V2,
      "io.address dziala w runtime, nie tylko przy elaboracji",
      stimulus = Seq("Zmiana io.address na 0x2A miedzy transakcjami"),
      checking = Seq("0x2A potwierdzony, 0x38 przestaje byc potwierdzany",
                     "Po powrocie adresu stan slave'a jest czysty")),

    // --- V3 ----------------------------------------------------------
    Testpoint("slv_reset_releases_bus", Stage.V3,
      "Reset w srodku transakcji puszcza obie linie i nie zostawia stanu",
      stimulus = Seq("Reset asynchroniczny w trakcie bajtu",
                     "Po zwolnieniu resetu pelna, czysta transakcja"),
      checking = Seq("W trakcie resetu slave nie trzyma ani SCL ani SDA",
                     "Transakcja po resecie dekoduje sie bez naruszen")),

    Testpoint("slv_stress_random", Stage.V3,
      "Losowy ruch: kierunek, dlugosc, tresc bajtow i zwloki warstwy 3",
      stimulus = Seq("4 transakcje x 1-2 bajty, kierunek losowy",
                     "rxDelay/txDelay losowane z osobnego seeda"),
      checking = Seq("Scoreboard zdarzen magistrali zgadza sie co do bitu",
                     "Ciag bajtow na io.rx zgadza sie z wyslanymi")),

    // --- odlozone ----------------------------------------------------
    Testpoint("slv_abort_mid_byte", Stage.V3,
      "START albo STOP w srodku bajtu kasuje stan slave'a",
      stimulus = Seq("Warunek START po czwartym bicie bajtu danych"),
      checking = Seq("Zaden niepelny bajt nie trafia na io.rx",
                     "Nastepny bajt dekoduje sie jako adres")),

    Testpoint("slv_tx_underrun_timeout", Stage.V2,
      "Brak bajtu w torze tx nie moze wiesic magistrali w nieskonczonosc",
      stimulus = Seq("Odczyt przy pustej kolejce tx i milczacej warstwie 3"),
      checking = Seq("Slave po zaprogramowanym limicie puszcza SCL")),

    Testpoint("slv_general_call", Stage.V2,
      "Adres 0x00 (general call) jest rozpoznawany osobno",
      stimulus = Seq("Bajt adresowy 0x00"),
      checking = Seq("io.select zglasza general call, a nie zwykle trafienie"))

  ) ++ StreamConformance.testpoints("slv_rx")

  // -------------------------------------------------------------------
  //  KONFIGURACJE.
  //
  //  SIATKA JEST INNA NIZ U MASTERA i to nie jest przeoczenie. Master
  //  ma qmin = 96 MHz / 8 MHz / w=3, gdzie okno filtra jest porownywalne
  //  z cwiartka - dla mastera to tylko wydluza cwiartke, dla slave'a
  //  jest zabojcze: reakcja L+1 = 7 cykli nie miesci sie w niskim SCL
  //  (2q = 6), wiec ACK ladowal na magistrali PRZY WYSOKIM SCL i
  //  monitor slusznie dekodowal go jako warunek START. Teraz broni
  //  przed tym require w I2cSlave - qmin nie przejdzie elaboracji.
  //
  //  qtight = 96 MHz / 6 MHz / w=2: q = 4, L = 5, czyli reakcja 6 cykli
  //  w niskim stanie SCL trwajacym 8. Zapas to JEDEN cykl, licząc
  //  z cyklem, ktory dokłada model magistrali w loopbacku - czyli
  //  dokladnie to, czym qmin byl dla mastera.
  //
  //  Realne magistrale sa w tej siatce z ogromnym zapasem: fmp1M to
  //  Fm+, najszybszy tryb w standardzie I2C.
  // -------------------------------------------------------------------
  case class Cfg(name : String, g : I2cGenerics)

  val configs = Seq(
    Cfg("std100k",  I2cGenerics(100 MHz, 100 kHz)),
    Cfg("fast400k", I2cGenerics(100 MHz, 400 kHz)),
    Cfg("fmp1M",    I2cGenerics(100 MHz,   1 MHz)),
    Cfg("qtight",   I2cGenerics(96 MHz,    6 MHz, filterWindow = 2))
  )

  val heavy = Set("fmp1M", "qtight")

  for (Cfg(cfgName, g) <- configs) {

    // Handle do io.rx slave'a. Ten port ma nietrywialny kontrakt:
    // valid stoi przez CALY clock stretching, wiec payload musi byc
    // staly przez dziesiatki cykli, a nie przez jeden.
    var rxPort : StreamPortHandle = null

    lazy val dut : SimCompiled[I2cLoopback] = Config.sim
      .withFstWave
      .workspaceName(s"${planLabel}_${cfgName}_${SimBackend.default.label}")
      .compile {
        val d = I2cLoopback(g)
        d.rework {
          rxPort = StreamPortHandle(
            valid   = d.io.rx.valid.simPublic(),
            ready   = d.io.rx.ready.simPublic(),
            payload = Instrument.stream(d.io.rx, "slv_rx"),
            isInput = false,
            name    = "slv_rx")
        }
        d
      }

    def scenario(name : String)(body : Env => Unit) : Unit =
      testpoint(name, variant = cfgName) {
        dut.doSim(s"${planLabel}_${cfgName}_$name", seed = 42) { d => body(setup(d)) }
      }

    // =================================================================
    //  V1
    // =================================================================

    scenario("slv_addr_ack") { case Env(d, bus, mon, host, probe, rsps) =>
      start(d); write(d, addrW()); stop(d)
      d.clockDomain.waitSampling(20)

      mon.expect(evTxn(evByte(addrW(), ack = true)) : _*)
      assert(rsps.size == 1 && rsps.head.ack, "slave nie potwierdzil wlasnego adresu")
      assert(host.selects.toSeq == Seq(false), s"select = ${host.selects.toSeq}")
      assert(host.starts == 1 && host.stops == 1,
             s"start x${host.starts}, stop x${host.stops}")
    }

    scenario("slv_addr_nack") { case Env(d, bus, mon, host, probe, rsps) =>
      start(d)
      write(d, addrW(0x39))       // cudzy adres
      write(d, 0x5A)              // ...i bajt, ktorego nikt nie powinien wziac
      stop(d)
      d.clockDomain.waitSampling(20)

      mon.expect(evTxn(evByte(addrW(0x39), ack = false),
                       evByte(0x5A,        ack = false)) : _*)
      assert(rsps.forall(!_.ack), s"ktos potwierdzil cudzy adres: ${rsps.toSeq}")
      assert(host.selects.isEmpty, s"select przy cudzym adresie: ${host.selects.toSeq}")
      assert(host.rxBytes.isEmpty, s"bajty przy cudzym adresie: ${host.rxBytes.toSeq}")
    }

    scenario("slv_write_byte") { case Env(d, bus, mon, host, probe, rsps) =>
      start(d); write(d, addrW()); write(d, 0x5A); stop(d)
      d.clockDomain.waitSampling(20)

      mon.expect(evTxn(evByte(addrW(), ack = true), evByte(0x5A, ack = true)) : _*)
      assert(host.rxBytes.toSeq == Seq(0x5A), f"io.rx = ${host.rxBytes.toSeq}")
      assert(rsps.map(_.ack).toSeq == Seq(true, true), s"ack = ${rsps.map(_.ack).toSeq}")
    }

    scenario("slv_read_byte") { case Env(d, bus, mon, host, probe, rsps) =>
      host.txQueue += 0x3C
      start(d); write(d, addrR()); read(d, ack = false); stop(d)
      d.clockDomain.waitSampling(20)

      mon.expect(evTxn(evByte(addrR(), ack = true), evByte(0x3C, ack = false)) : _*)
      assert(rsps.map(_.data).toSeq == Seq(addrR(), 0x3C),
             s"${rsps.map(_.data).toSeq}")
      assert(host.selects.toSeq == Seq(true), s"select = ${host.selects.toSeq}")
    }

    scenario("slv_rx_nack") { case Env(d, bus, mon, host, probe, rsps) =>
      host.rxAckOf = _ => false

      start(d); write(d, addrW())
      write(d, 0xC3)              // odrzucony przez warstwe 3
      write(d, 0x3C)              // slave juz nie uczestniczy
      stop(d)
      d.clockDomain.waitSampling(20)

      mon.expect(evTxn(evByte(addrW(), ack = true),
                       evByte(0xC3,    ack = false),
                       evByte(0x3C,    ack = false)) : _*)
      assert(host.rxBytes.toSeq == Seq(0xC3),
             s"po NACK slave nadal odbiera: ${host.rxBytes.toSeq}")
    }

    scenario("slv_tx_ack_report") { case Env(d, bus, mon, host, probe, rsps) =>
      host.txQueue ++= Seq(0xF0, 0x0F)
      start(d); write(d, addrR())
      read(d, ack = true)
      read(d, ack = false)
      stop(d)
      d.clockDomain.waitSampling(20)

      mon.expect(evTxn(evByte(addrR(), ack = true),
                       evByte(0xF0,    ack = true),
                       evByte(0x0F,    ack = false)) : _*)
      assert(host.txAcks.toSeq == Seq(true, false), s"txAck = ${host.txAcks.toSeq}")
      assert(rsps.map(_.data).toSeq == Seq(addrR(), 0xF0, 0x0F),
             s"${rsps.map(_.data).toSeq}")
    }

    scenario("slv_start_stop_events") { case Env(d, bus, mon, host, probe, rsps) =>
      start(d); write(d, addrW()); write(d, 0x11)
      start(d); write(d, addrW()); write(d, 0x22)   // RESTART, bez STOP
      stop(d)
      d.clockDomain.waitSampling(20)

      val expected = ((Start +: (evByte(addrW(), ack = true) ++ evByte(0x11, ack = true))) ++
                      (Start +: (evByte(addrW(), ack = true) ++ evByte(0x22, ack = true)))) :+ Stop
      mon.expect(expected : _*)
      assert(host.starts == 2 && host.stops == 1,
             s"start x${host.starts}, stop x${host.stops}")
      assert(host.rxBytes.toSeq == Seq(0x11, 0x22), s"io.rx = ${host.rxBytes.toSeq}")
    }

    // =================================================================
    //  StreamConformance na io.rx
    // =================================================================

    scenario("slv_rx_payload_stable") { case Env(d, bus, mon, host, probe, rsps) =>
      StreamConformance.payloadStable(d.clockDomain, rxPort)
      StreamConformance.noStall(d.clockDomain, rxPort, 200 * g.quarterCycles)

      // Zwloka jest tu po to, zeby valid stalo dluzej niz jeden cykl -
      // inaczej "payload staly przez caly czas valid && !ready" jest
      // spelnione trywialnie i test niczego nie sprawdza.
      host.rxDelay = () => 2 * g.quarterCycles

      start(d); write(d, addrW()); write(d, 0xA5); write(d, 0x5A); stop(d)
      d.clockDomain.waitSampling(20)

      mon.expect(evTxn(evByte(addrW(), ack = true),
                       evByte(0xA5,    ack = true),
                       evByte(0x5A,    ack = true)) : _*)
      assert(host.rxBytes.toSeq == Seq(0xA5, 0x5A), s"io.rx = ${host.rxBytes.toSeq}")
    }

    scenario("slv_rx_reset_quiet") { case Env(d, bus, mon, host, probe, rsps) =>
      StreamConformance.quietDuringReset(d.clockDomain, rxPort)

      start(d); write(d, addrW()); write(d, 0xA5); stop(d)
      d.clockDomain.waitSampling(20)
      mon.expect(evTxn(evByte(addrW(), ack = true), evByte(0xA5, ack = true)) : _*)

      // Reset przy JALOWEJ magistrali - po STOP obie linie sa puszczone,
      // wiec nie ma zbocz i checker patrzy wylacznie na valid.
      // waitActiveEdge, NIE waitSampling - uzasadnienie w
      // phy_cmd_reset_quiet (pod aktywnym resetem waitSampling nie
      // dobija i symulacja stoi do SimTimeout).
      d.clockDomain.assertReset()
      d.clockDomain.waitActiveEdge(20)
      d.clockDomain.deassertReset()
      d.clockDomain.waitSampling(10)

      host.clear(); mon.forget(); rsps.clear()

      start(d); write(d, addrW()); write(d, 0x5A); stop(d)
      d.clockDomain.waitSampling(20)
      mon.expect(evTxn(evByte(addrW(), ack = true), evByte(0x5A, ack = true)) : _*)
      assert(host.rxBytes.toSeq == Seq(0x5A), s"io.rx = ${host.rxBytes.toSeq}")
    }

    // =================================================================
    //  V2 / V3 - tylko na krotkich konfiguracjach
    // =================================================================
    if (heavy(cfgName)) {

      scenario("slv_rx_backpressure") { case Env(d, bus, mon, host, probe, rsps) =>
        val rng = new Random(2)
        host.rxDelay = () => rng.nextInt(3 * g.quarterCycles)

        start(d); write(d, addrW())
        for (v <- Seq(0x00, 0xFF, 0x5A)) write(d, v)
        stop(d)
        d.clockDomain.waitSampling(20)

        mon.expect(evTxn(evByte(addrW(), ack = true),
                         evByte(0x00, ack = true),
                         evByte(0xFF, ack = true),
                         evByte(0x5A, ack = true)) : _*)
        assert(host.rxBytes.toSeq == Seq(0x00, 0xFF, 0x5A), s"${host.rxBytes.toSeq}")
      }

      scenario("slv_write_then_read") { case Env(d, bus, mon, host, probe, rsps) =>
        host.txQueue += 0x3C

        start(d); write(d, addrW()); write(d, 0xC3)
        start(d); write(d, addrR()); read(d, ack = false)
        stop(d)
        d.clockDomain.waitSampling(20)

        val expected = ((Start +: (evByte(addrW(), ack = true) ++ evByte(0xC3, ack = true))) ++
                        (Start +: (evByte(addrR(), ack = true) ++ evByte(0x3C, ack = false)))) :+ Stop
        mon.expect(expected : _*)
        assert(host.rxBytes.toSeq == Seq(0xC3), s"io.rx = ${host.rxBytes.toSeq}")
        assert(rsps.last.data == 0x3C, f"odczytano 0x${rsps.last.data}%02X")
        assert(host.selects.toSeq == Seq(false, true), s"select = ${host.selects.toSeq}")
      }

      scenario("slv_multi_byte_write") { case Env(d, bus, mon, host, probe, rsps) =>
        val bytes = Seq(0x00, 0xFF, 0x5A)
        start(d); write(d, addrW())
        for (v <- bytes) write(d, v)
        stop(d)
        d.clockDomain.waitSampling(20)

        mon.check()
        val got  = mon.drain()
        val bits = got.count { case Bit(_) => true; case _ => false }
        assert(bits == 36, s"bitow na magistrali $bits, oczekiwano 36 (4 x 9)")
        assert(host.rxBytes.toSeq == bytes, s"io.rx = ${host.rxBytes.toSeq}")
      }

      scenario("slv_multi_byte_read") { case Env(d, bus, mon, host, probe, rsps) =>
        val bytes = Seq(0x11, 0x22, 0x33)
        host.txQueue ++= bytes

        start(d); write(d, addrR())
        for ((_, i) <- bytes.zipWithIndex) read(d, ack = i != bytes.size - 1)
        stop(d)
        d.clockDomain.waitSampling(20)

        mon.check()
        assert(rsps.map(_.data).toSeq == addrR() +: bytes, s"${rsps.map(_.data).toSeq}")
        assert(host.txAcks.toSeq == Seq(true, true, false), s"txAck = ${host.txAcks.toSeq}")
      }

      // ---------------------------------------------------------------
      //  Trzy testy stretchingu czytac razem: dwa mowia "rozciagnij",
      //  trzeci "nie rozciagaj". Osobno kazdy z nich da sie oszukac
      //  slave'em, ktory trzyma SCL zawsze albo nigdy.
      // ---------------------------------------------------------------
      scenario("slv_stretch_rx") { case Env(d, bus, mon, host, probe, rsps) =>
        host.rxDelay = () => 8 * g.quarterCycles
        probe.reset()

        start(d); write(d, addrW()); write(d, 0x5A); stop(d)
        d.clockDomain.waitSampling(20)

        mon.expect(evTxn(evByte(addrW(), ack = true), evByte(0x5A, ack = true)) : _*)
        assert(host.rxBytes.toSeq == Seq(0x5A), s"io.rx = ${host.rxBytes.toSeq}")
        info(s"cykli rozciagniecia: ${probe.cycles}")
        assert(probe.cycles >= 4 * g.quarterCycles,
               s"slave nie rozciagnal zegara mimo zwloki warstwy 3 (${probe.cycles} cykli)")
      }

      scenario("slv_stretch_tx") { case Env(d, bus, mon, host, probe, rsps) =>
        host.txQueue ++= Seq(0xA5, 0x5A)
        host.txDelay = () => 8 * g.quarterCycles
        probe.reset()

        start(d); write(d, addrR())
        read(d, ack = true); read(d, ack = false)
        stop(d)
        d.clockDomain.waitSampling(20)

        mon.expect(evTxn(evByte(addrR(), ack = true),
                         evByte(0xA5,    ack = true),
                         evByte(0x5A,    ack = false)) : _*)
        info(s"cykli rozciagniecia: ${probe.cycles}")
        assert(probe.cycles >= 4 * g.quarterCycles,
               s"slave nie rozciagnal zegara mimo zwloki warstwy 3 (${probe.cycles} cykli)")
      }

      scenario("slv_no_stretch_when_fast") { case Env(d, bus, mon, host, probe, rsps) =>
        host.txQueue += 0x3C
        probe.reset()

        start(d); write(d, addrW()); write(d, 0x5A)
        start(d); write(d, addrR()); read(d, ack = false)
        stop(d)
        d.clockDomain.waitSampling(20)

        // Zero jest osiagalne tylko wtedy, gdy reakcja slave'a miesci sie
        // w niskiej polowce SCL (2q). Przy qtight zapas jest jednocyklowy,
        // wiec kilka cykli rozciagniecia zostaje ZAWSZE (handshake
        // z warstwa 3 tez trwa) - stad prog, a nie rownosc z zerem. Prog musi byc
        // znaczaco mniejszy od zwloki z testow wyzej (8q).
        val limit = g.filterLatency + 8
        info(s"cykli rozciagniecia: ${probe.cycles} (limit $limit)")
        assert(probe.cycles <= limit,
               s"slave rozciaga zegar bez powodu: ${probe.cycles} cykli, limit $limit")
      }

      scenario("slv_address_runtime") { case Env(d, bus, mon, host, probe, rsps) =>
        d.io.address #= 0x2A
        d.clockDomain.waitSampling(2)

        start(d); write(d, addrW(0x2A)); write(d, 0x11); stop(d)
        d.clockDomain.waitSampling(20)
        mon.expect(evTxn(evByte(addrW(0x2A), ack = true), evByte(0x11, ack = true)) : _*)
        assert(host.rxBytes.toSeq == Seq(0x11), s"io.rx = ${host.rxBytes.toSeq}")

        // Stary adres przestaje byc nasz - i to bez sladu po poprzedniej
        // transakcji (bajt po NACK-niętym adresie nie moze wejsc na io.rx).
        start(d); write(d, addrW(0x38)); write(d, 0x22); stop(d)
        d.clockDomain.waitSampling(20)
        mon.expect(evTxn(evByte(addrW(0x38), ack = false),
                         evByte(0x22,        ack = false)) : _*)
        assert(host.rxBytes.toSeq == Seq(0x11), s"io.rx = ${host.rxBytes.toSeq}")
        assert(host.selects.toSeq == Seq(false), s"select = ${host.selects.toSeq}")
      }

      // ---------------------------------------------------------------
      //  Reset leci z forka, tak jak w mst_random_reset: watek testu
      //  wisi na waitSamplingWhere(cmd.ready), a pod aktywnym resetem
      //  ten warunek nigdy nie nadejdzie. Komenda przerwana resetem
      //  domknie sie sama, bo io.cmd.valid zostaje wysoko.
      //
      //  Ruch z przerwanej czesci jest protokolarnie bezsensowny
      //  (mon.forget), sprawdzamy dwie rzeczy: czy slave puscil
      //  magistrale i czy NASTEPNA transakcja jest czysta.
      // ---------------------------------------------------------------
      scenario("slv_reset_releases_bus") { case Env(d, bus, mon, host, probe, rsps) =>
        val rng = new Random(5)

        for (_ <- 0 until 3) {
          fork {
            d.clockDomain.waitSampling(rng.nextInt(8 * g.quarterCycles) + g.quarterCycles)
            d.clockDomain.assertReset()
            d.clockDomain.waitActiveEdge(5)
            assert(d.io.slvScl.toBoolean && d.io.slvSda.toBoolean,
                   "reset nie puscil magistrali po stronie slave'a")
            d.clockDomain.deassertReset()
          }

          start(d); write(d, addrW()); write(d, 0x5A)
          d.clockDomain.waitSampling(8 * g.quarterCycles)

          host.clear(); mon.forget(); rsps.clear()

          start(d); write(d, addrW()); write(d, 0xA5); stop(d)
          d.clockDomain.waitSampling(20)
          mon.expect(evTxn(evByte(addrW(), ack = true), evByte(0xA5, ack = true)) : _*)
          assert(host.rxBytes.toSeq == Seq(0xA5),
                 s"po resecie io.rx = ${host.rxBytes.toSeq}")
          host.clear(); rsps.clear()
        }
      }

      scenario("slv_stress_random") { case Env(d, bus, mon, host, probe, rsps) =>
        // Dwa osobne generatory: jeden dla scenariusza (watek testu),
        // drugi dla zwlok (forki hosta). Jeden wspolny dawalby kolejnosc
        // losowan zalezna od przeplotu watkow - deterministyczna, ale
        // nieczytelna przy debugowaniu.
        val rng   = new Random(11)
        val rngHost = new Random(12)
        host.rxDelay = () => rngHost.nextInt(2 * g.quarterCycles)
        host.txDelay = () => rngHost.nextInt(2 * g.quarterCycles)

        val expEv = mutable.ArrayBuffer[Event]()
        val expRx = mutable.ArrayBuffer[Int]()

        for (_ <- 0 until 4) {
          val reading = rng.nextBoolean()
          val a       = if (reading) addrR() else addrW()
          val n       = rng.nextInt(2) + 1

          // Uzbrajanie PRZED bajtem adresowym, nie po nim - ta sama
          // regula co w naglowku I2cSlaveModel, tylko po drugiej
          // stronie magistrali. Po powrocie z write(addr) slave juz od
          // kilkunastu cykli prosi o pierwszy bajt.
          val bytes = if (reading) Seq.fill(n)(rng.nextInt(256)) else Seq.empty[Int]
          host.txQueue ++= bytes

          start(d); expEv += Start
          write(d, a); expEv ++= evByte(a, ack = true)

          if (reading) {
            for ((v, i) <- bytes.zipWithIndex) {
              val ack = i != n - 1
              read(d, ack)
              expEv ++= evByte(v, ack)
            }
          } else {
            for (_ <- 0 until n) {
              val v = rng.nextInt(256)
              write(d, v)
              expEv ++= evByte(v, ack = true)
              expRx += v
            }
          }
          stop(d); expEv += Stop
        }
        d.clockDomain.waitSampling(20)

        mon.expect(expEv.toSeq : _*)
        assert(host.rxBytes.toSeq == expRx.toSeq,
               s"rx  = ${host.rxBytes.toSeq}\nexp = ${expRx.toSeq}")
      }
    }
  }

  // -----------------------------------------------------------------
  //  ODLOZONE
  // -----------------------------------------------------------------

  unimplemented("slv_abort_mid_byte",
    "I2cMaster generuje wylacznie calobajtowe komendy, wiec nie potrafi " +
    "wystawic START/STOP w srodku bajtu. Potrzebny programowy master " +
    "(odpowiednik I2cSlaveModel dla drugiej strony), ktory bitami steruje " +
    "recznie - to samo narzedzie odblokuje testy zlych czasow setup/hold")

  unimplemented("slv_tx_underrun_timeout",
    "I2cSlave celowo nie ma timeoutu: brak bajtu w io.tx trzyma SCL " +
    "w nieskonczonosc, zeby nie wysylac po cichu smieci. Test wymaga " +
    "najpierw decyzji, co slave ma robic po limicie - odlozone razem " +
    "z mst_stretch_timeout")

  unimplemented("slv_general_call",
    "RTL nie dekoduje adresu 0x00 - jest jeden komparator na io.address. " +
    "Dopisanie general call to zmiana interfejsu (io.select musi umiec " +
    "powiedziec, KTORY adres trafil), a nie sam test")

  unimplemented("slv_rx_stress_with_rand_reset",
    "stimulus pokrywa slv_reset_releases_bus; do dolaczenia checkerow " +
    "strumienia brakuje instrumentacji, ktora przezywa assertReset " +
    "w trakcie transakcji - tak samo jak w phy_cmd_stress_with_rand_reset")
}
