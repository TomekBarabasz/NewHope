package newhope.i2c

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._
import scala.collection.mutable
import scala.util.Random
import newhope.vertebra.{Stage, Testpoint, TestplanSuite,
                         Instrument, StreamConformance, StreamPortHandle}
import newhope.vertebra.sim.SimBackend

// =====================================================================
//  DRIVER I2cMaster - jedyna definicja w projekcie, tak samo jak sekcja
//  cmd/byte/setup w I2cSmoke.scala. Gdy pojawi sie sweep warstwy 2,
//  to stad, a nie z kopii.
//
//  RZECZ, KTORA TRZEBA WIEDZIEC O UZBRAJANIU SLAVE'A:
//  slave.writeAck() / slave.sendByte() wola sie BEZPOSREDNIO PRZED
//  komenda bajtowa. Uzasadnienie w naglowku I2cSlaveModel - w skrocie:
//  miedzy komendami SCL jest nisko, wiec pierwszy slot wchodzi na SDA
//  natychmiast, i ten sam zapis dziala dla pierwszego bajtu po START,
//  po RESTART i w srodku transakcji.
// =====================================================================
object I2cMasterDriver {
  import I2cEvent._
  import I2cCmdMode._

  case class Rsp(data : Int, ack : Boolean)

  case class Env(dut   : I2cMaster,
                 bus   : I2cBusModel,
                 slave : I2cSlaveModel,
                 mon   : I2cMonitor,
                 rsps  : mutable.Queue[Rsp])

  // --- komendy -------------------------------------------------------
  def cmd(d    : I2cMaster,
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

  def start(d : I2cMaster) : Unit               = cmd(d, START)
  def stop (d : I2cMaster) : Unit               = cmd(d, STOP)
  def write(d : I2cMaster, v : Int) : Unit      = cmd(d, WRITE, data = v)
  def read (d : I2cMaster, ack : Boolean) : Unit = cmd(d, READ, ack = ack)

  // --- oczekiwane zdarzenia -----------------------------------------
  def evBits(v : Int) : Seq[Event] =
    for (i <- 7 to 0 by -1) yield Bit(((v >> i) & 1) != 0)

  /** Bajt + dziewiaty bit. ACK na magistrali to NISKI poziom SDA,
    * dlatego `Bit(!ack)`. */
  def evByte(v : Int, ack : Boolean) : Seq[Event] = evBits(v) :+ Bit(!ack)

  /** START + bajty + STOP. */
  def evTxn(bytes : Seq[Event]*) : Seq[Event] = (Start +: bytes.flatten.toSeq) :+ Stop

  // --- otoczenie -----------------------------------------------------
  /** UWAGA - inaczej niz I2cSmoke.setup, ten USTAWIA SimTimeout.
    * waitSamplingWhere na io.cmd.ready nie ma wlasnego limitu, a tu nie
    * ma sweepu, ktory musialby odrozniac "wisi" od "nie dziala"; test
    * ma failowac, nie wisiec do konca swiata. */
  def setup(d : I2cMaster, bits : Int = 300) : Env = {
    SimTimeout(bits.toLong * 4 * d.g.quarterCycles * 10 * 4)

    val bus   = new I2cBusModel(d)
    val slave = new I2cSlaveModel(d, bus)
    val mon   = new I2cMonitor(d, bus)
    val rsps  = mutable.Queue[Rsp]()

    d.io.cmd.valid     #= false
    d.io.pins.scl.read #= true
    d.io.pins.sda.read #= true
    d.clockDomain.forkStimulus(period = 10)

    FlowMonitor(d.io.rsp, d.clockDomain) { p =>
      rsps.enqueue(Rsp(p.data.toInt, p.ack.toBoolean))
    }
    bus.start(); slave.start(); mon.start()
    d.clockDomain.waitSampling(5)
    Env(d, bus, slave, mon, rsps)
  }
}

// =====================================================================
//  JEDYNA suita testowa I2cMaster. Ten sam uklad co I2cPhyTestplan:
//  jeden plan, dwie podklasy, po jednym DUT-cie na konfiguracje.
//
//  CO TEN POZIOM MA SPRAWDZIC, A CZEGO NIE
//  ---------------------------------------
//  Timing SCL, filtr wejsciowy i stretching sa juz sprawdzone w
//  I2cPhyTestplan i NIE powtarzaja sie tutaj (wyjatek: mst_clock_-
//  stretching, bo stretching w srodku bajtu dotyka licznika bitow
//  mastera, a nie tylko licznika cwiartek PHY). Tutaj chodzi o:
//   - kolejnosc i liczbe bitow (8 + ACK, MSB-first),
//   - kierunek (kto trzyma SDA w ktorym bicie),
//   - tor rsp (dane, ACK, ile razy strzela),
//   - i przede wszystkim o to, ze master nie rusza payloadu komendy
//     PHY w trakcie jej wykonywania.
//
//  DLACZEGO phy_cmd_* JEST W TYM PLANIE
//  ------------------------------------
//  StreamConformance z vertebry nie dalo sie dolaczyc do planu I2C, bo
//  nikt nie wolal Instrument.stream (patrz komentarz w Testplan.scala).
//  Teraz sterowanie PHY idzie przez I2cMaster.phyCmd - strumien w
//  zasiegu mastera - wiec da sie go zinstrumentowac w rework i wlasnosc
//  "payload staly przez caly czas valid && !ready" staje sie assercja.
//
//  To nie jest ozdoba. Pierwsza wersja I2cMaster przesuwala rejestr na
//  phy.io.rsp.valid, czyli w Q2, w srodku wysokiego SCL. Na
//  I2cPhyTable (Drive.FromCmd, odczyt kombinacyjny) objaw byl brutalny:
//  SDA rusza przy wysokim SCL, czyli falszywy START/STOP w srodku
//  bajtu. Na I2cPhyFsm (sdaReg zatrzasniety raz, w sBitSetup) objawu
//  NIE BYLO ZADNEGO - blad byl niewidoczny na pinach. phy_cmd_payload_-
//  stable lapie go w obu wersjach, bo patrzy na kontrakt, a nie na
//  skutek.
//
//  Debugowanie pojedynczej komorki (kazdy testpoint ma fale FST):
//    sbt "testOnly *I2cMasterTableTestplan -- -z \"mst_read_byte (qmin)\""
// =====================================================================
abstract class I2cMasterTestplan(label : String,
                                 build : I2cGenerics => I2cPhyBase) extends TestplanSuite {
  import I2cEvent._
  import I2cMasterDriver._

  // -------------------------------------------------------------------
  //  PLAN. `def`, nie `val` - patrz guard w TestplanSuite.
  // -------------------------------------------------------------------
  def testplan : Seq[Testpoint] = Seq(

    // --- V1: sanity kierunku i kolejnosci bitow ---------------------
    Testpoint("mst_write_byte", Stage.V1,
      "Bajt z io.cmd wychodzi na magistrale jako 8 bitow MSB-first + szczelina ACK",
      stimulus = Seq("START, WRITE 0xA5, slave potwierdza, STOP"),
      checking = Seq("Monitor dekoduje Start, 8 bitow 0xA5, Bit(false) jako ACK, Stop",
                     "Dokladnie jedno zdarzenie na io.rsp, z ack = true")),

    Testpoint("mst_sda_stable_during_bit", Stage.V1,
      "Rejestr przesuwny nie rusza SDA w trakcie bitu",
      // 0xA5 i 0x5A zmieniaja sie na kazdej granicy bitu, wlacznie z
      // granica bajt/ACK i ACK/bajt - czyli w kazdym miejscu, w ktorym
      // przesuniecie moglo wypasc w zlym cyklu.
      stimulus = Seq("START, WRITE 0xA5, WRITE 0x5A, STOP"),
      checking = Seq("Zero naruszen niezmiennika 'SDA stabilne przy wysokim SCL'",
                     "Dokladnie 18 bitow, zero dodatkowych Start/Stop w srodku")),

    Testpoint("mst_ack_capture", Stage.V1,
      "io.rsp.ack odzwierciedla to, co slave zrobil z SDA w 9. bicie",
      stimulus = Seq("Bajt potwierdzony przez slave'a, potem bajt niepotwierdzony"),
      checking = Seq("Pierwsze rsp z ack = true, drugie z ack = false",
                     "Konwencja: ACK to sciagniecie SDA, inwersja jest w RTL")),

    Testpoint("mst_read_byte", Stage.V1,
      "READ sklada bajt z poziomow wystawionych przez slave'a",
      stimulus = Seq("Slave wystawia 0x3C bit po bicie od opadajacego SCL",
                     "Master puszcza SDA na caly bajt i potwierdza"),
      checking = Seq("io.rsp.data == 0x3C",
                     "Na magistrali widac 0x3C, a 9. bit jest sciagniety przez mastera")),

    Testpoint("mst_read_ack_nack", Stage.V1,
      "io.cmd.ack steruje 9. bitem przy odczycie",
      stimulus = Seq("READ z ack = true, potem READ z ack = false"),
      checking = Seq("9. bit odpowiednio Bit(false) i Bit(true) na magistrali",
                     "Oba bajty zlozone poprawnie")),

    Testpoint("mst_rsp_one_per_byte", Stage.V1,
      "Flow rsp strzela raz na bajt i ani razu na START/STOP",
      stimulus = Seq("START, WRITE, READ, STOP"),
      checking = Seq("Dokladnie dwa zdarzenia rsp",
                     "Kolejnosc i tresc zgodna z kolejnoscia komend")),

    // --- V2 ---------------------------------------------------------
    Testpoint("mst_write_then_read", Stage.V2,
      "Pelna transakcja: adres z ACK, bajt odczytany z NACK, STOP",
      stimulus = Seq("START, WRITE 0xA5 (ACK), READ 0x5A (NACK), STOP"),
      checking = Seq("Jeden ciag zdarzen bez przerwy miedzy komendami",
                     "rsp: (0xA5, ack) i (0x5A, nack)")),

    Testpoint("mst_repeated_start", Stage.V2,
      "RESTART bez STOP miedzy bajtami",
      stimulus = Seq("START, WRITE, START, WRITE, STOP"),
      checking = Seq("Dwa zdarzenia Start, dokladnie jedno Stop na koncu",
                     "Drugi bajt przechodzi w calosci")),

    Testpoint("mst_cmd_backpressure", Stage.V2,
      "Luki na io.cmd nie generuja ruchu na magistrali",
      stimulus = Seq("Losowe przerwy 1..4 cwiartek miedzy komendami"),
      checking = Seq("SCL nie drgnie w przerwie",
                     "Zero nowych zdarzen monitora w przerwie",
                     "Sekwencja po zlozeniu identyczna jak bez przerw")),

    Testpoint("mst_write_readback", Stage.V2,
      "Przy zapisie io.rsp.data zwraca to, co bylo widziane na SDA",
      // Jedyny test toru probkowania w kierunku zapisu. Ta sciezka jest
      // fundamentem pod wykrywanie kolizji (patrz mst_arbitration_lost).
      stimulus = Seq("WRITE 0x96 bez ingerencji slave'a w tor danych"),
      checking = Seq("io.rsp.data == 0x96")),

    Testpoint("mst_clock_stretching", Stage.V2,
      "Stretching w srodku bajtu nie gubi i nie dubluje bitow",
      stimulus = Seq("Slave trzyma SCL 1..4 cwiartki po kazdym puszczeniu linii",
                     "W trakcie bajtu zapisu i bajtu odczytu"),
      checking = Seq("Sekwencja zdarzen niezmieniona",
                     "Odczytany bajt i ACK bez zmian")),

    Testpoint("mst_bit_count", Stage.V2,
      "Dokladnie dziewiec bitow na bajt, zero bitow miedzy komendami",
      stimulus = Seq("Trzy bajty w jednej transakcji: 0x00, 0xFF, 0x5A"),
      checking = Seq("27 zdarzen Bit, jeden Start, jeden Stop, nic wiecej")),

    // --- V3 ---------------------------------------------------------
    Testpoint("mst_random_reset", Stage.V3,
      "Reset w losowym momencie transakcji zostawia mastera w stanie jalowym",
      stimulus = Seq("Reset asynchroniczny w trakcie bajtu",
                     "Po zwolnieniu resetu pelna, czysta transakcja"),
      checking = Seq("W trakcie resetu obie linie puszczone",
                     "Transakcja po resecie dekoduje sie bez naruszen")),

    Testpoint("mst_stress_random_transactions", Stage.V3,
      "Losowy ruch: dlugosc transakcji, kierunek, ACK i tresc bajtow",
      stimulus = Seq("4 transakcje x 1-2 bajty, WRITE albo READ losowo",
                     "ACK/NACK losowany per bajt, seed staly"),
      checking = Seq("Scoreboard zdarzen magistrali zgadza sie co do bitu",
                     "Ciag io.rsp zgadza sie z tym, co wyslano i odebrano")),

    // --- odlozone ---------------------------------------------------
    Testpoint("mst_arbitration_lost", Stage.V3,
      "Po przegranej arbitrazu master przerywa transakcje",
      stimulus = Seq("Obcy uczestnik sciaga SDA w bicie, w ktorym master ja puscil"),
      checking = Seq("Rozjazd wystawionego i odczytanego bitu podnosi blad",
                     "Master puszcza obie linie do konca transakcji")),

    Testpoint("mst_stretch_timeout", Stage.V2,
      "Stretching dluzszy niz limit konczy transakcje bledem",
      stimulus = Seq("Slave trzyma SCL dluzej niz zaprogramowany timeout"),
      checking = Seq("io.rsp zglasza blad zamiast czekac w nieskonczonosc")),

    Testpoint("mst_bus_free_time_after_stop", Stage.V2,
      "Po STOP magistrala jest wolna przez tBUF przed nastepnym START",
      stimulus = Seq("STOP i natychmiastowy START"),
      checking = Seq("Odstep miedzy zboczami >= tBUF"))

  ) ++ StreamConformance.testpoints("phy_cmd")

  // -------------------------------------------------------------------
  //  KONFIGURACJE
  //
  //  Ta sama siatka co I2cPhyTestplan.configs - jesli bedzie trzecie
  //  miejsce, ktore jej potrzebuje, wyfaktoryzowac do object I2cConfigs.
  //  Bajt to 9 bitow, czyli 36 cwiartek, wiec transakcja przy std100k
  //  (q=250) trwa kilkaset tysiecy jednostek sim. V1 leci na wszystkich
  //  konfiguracjach, V2/V3 tylko na dwoch najkrotszych - ciekawe jest
  //  qmin (zero zapasu na filtrze), nie std100k (nadmiar wszystkiego).
  // -------------------------------------------------------------------
  case class Cfg(name : String, g : I2cGenerics)

  val configs = Seq(
    Cfg("std100k",  I2cGenerics(100 MHz, 100 kHz)),
    Cfg("fast400k", I2cGenerics(100 MHz, 400 kHz)),
    Cfg("fmp1M",    I2cGenerics(100 MHz,   1 MHz)),
    Cfg("qmin",     I2cGenerics(96 MHz,  8 MHz, filterWindow = 3))
  )

  val heavy = Set("fmp1M", "qmin")

  for (Cfg(cfgName, g) <- configs) {

    // Handle do portu phy.io.cmd, wypelniany podczas elaboracji.
    // Instrument.stream tworzy sprzet (asBits), wiec musi byc w rework;
    // simPublic samo w sobie sprzetu nie tworzy, ale trzymamy to razem.
    var cmdPort : StreamPortHandle = null

    lazy val dut : SimCompiled[I2cMaster] = Config.sim
      .withFstWave
      .workspaceName(s"${label}_${cfgName}_${SimBackend.default.label}")
      .compile {
        val d = I2cMaster(g, build)
        d.rework {
          cmdPort = StreamPortHandle(
            valid   = d.phyCmd.valid.simPublic(),
            ready   = d.phyCmd.ready.simPublic(),
            payload = Instrument.stream(d.phyCmd, "phy_cmd"),
            isInput = false,
            name    = "phy_cmd")
        }
        d
      }

    def scenario(name : String)(body : Env => Unit) : Unit =
      testpoint(name, variant = cfgName) {
        dut.doSim(s"${label}_${cfgName}_$name", seed = 42) { d => body(setup(d)) }
      }

    // =================================================================
    //  V1 - kierunek, kolejnosc, tor rsp
    // =================================================================

    scenario("mst_write_byte") { case Env(d, bus, slave, mon, rsps) =>
      start(d)
      slave.writeAck(ack = true)
      write(d, 0xA5)
      stop(d)
      d.clockDomain.waitSampling(20)

      mon.expect(evTxn(evByte(0xA5, ack = true)) : _*)
      assert(rsps.size == 1, s"rsp x${rsps.size}, oczekiwano 1")
      assert(rsps.head.ack, "master nie zobaczyl ACK slave'a")
    }

    // Ten testpoint jest po to, zeby blad z pierwszej wersji sBits nie
    // wrocil. Objaw na I2cPhyTable: zamiast bitow monitor dekoduje
    // dodatkowe Start/Stop, bo SDA rusza przy wysokim SCL. Na
    // I2cPhyFsm ten test PRZEJDZIE nawet z zepsutym masterem - tam
    // bug lapie tylko phy_cmd_payload_stable.
    scenario("mst_sda_stable_during_bit") { case Env(d, bus, slave, mon, rsps) =>
      start(d)
      slave.writeAck(); write(d, 0xA5)
      slave.writeAck(); write(d, 0x5A)
      stop(d)
      d.clockDomain.waitSampling(20)

      mon.check()
      val got = mon.drain()
      assert(got == evTxn(evByte(0xA5, ack = true), evByte(0x5A, ack = true)),
             s"sekwencja rozjechana: $got")
    }

    scenario("mst_ack_capture") { case Env(d, bus, slave, mon, rsps) =>
      start(d)
      slave.writeAck(ack = true);  write(d, 0x00)
      slave.writeAck(ack = false); write(d, 0xFF)
      stop(d)
      d.clockDomain.waitSampling(20)

      mon.expect(evTxn(evByte(0x00, ack = true), evByte(0xFF, ack = false)) : _*)
      assert(rsps.map(_.ack).toSeq == Seq(true, false), s"ack = ${rsps.map(_.ack).toSeq}")
    }

    scenario("mst_read_byte") { case Env(d, bus, slave, mon, rsps) =>
      start(d)
      slave.sendByte(0x3C)
      read(d, ack = true)
      stop(d)
      d.clockDomain.waitSampling(20)

      mon.expect(evTxn(evByte(0x3C, ack = true)) : _*)
      assert(rsps.size == 1, s"rsp x${rsps.size}")
      assert(rsps.head.data == 0x3C, f"odczytano 0x${rsps.head.data}%02X")
    }

    scenario("mst_read_ack_nack") { case Env(d, bus, slave, mon, rsps) =>
      start(d)
      slave.sendByte(0xF0); read(d, ack = true)
      slave.sendByte(0x0F); read(d, ack = false)
      stop(d)
      d.clockDomain.waitSampling(20)

      mon.expect(evTxn(evByte(0xF0, ack = true), evByte(0x0F, ack = false)) : _*)
      assert(rsps.map(_.data).toSeq == Seq(0xF0, 0x0F), s"${rsps.map(_.data).toSeq}")
    }

    scenario("mst_rsp_one_per_byte") { case Env(d, bus, slave, mon, rsps) =>
      start(d)
      slave.writeAck(); write(d, 0x12)
      slave.sendByte(0x34); read(d, ack = false)
      stop(d)
      d.clockDomain.waitSampling(20)

      mon.check()
      assert(rsps.size == 2, s"rsp x${rsps.size} - START/STOP nie moga strzelac")
      assert(rsps.map(_.data).toSeq == Seq(0x12, 0x34), s"${rsps.map(_.data).toSeq}")
    }

    // =================================================================
    //  StreamConformance na phy.io.cmd - kontrakt, ktory lamal master
    // =================================================================

    scenario("phy_cmd_payload_stable") { case Env(d, bus, slave, mon, rsps) =>
      StreamConformance.payloadStable(d.clockDomain, cmdPort)
      StreamConformance.noStall(d.clockDomain, cmdPort, 200 * g.quarterCycles)

      start(d)
      slave.writeAck(); write(d, 0xA5)
      slave.sendByte(0x5A); read(d, ack = true)
      stop(d)
      d.clockDomain.waitSampling(20)

      mon.expect(evTxn(evByte(0xA5, ack = true), evByte(0x5A, ack = true)) : _*)
    }

    scenario("phy_cmd_reset_quiet") { case Env(d, bus, slave, mon, rsps) =>
      StreamConformance.quietDuringReset(d.clockDomain, cmdPort)

      start(d); slave.writeAck(); write(d, 0xA5); stop(d)
      d.clockDomain.waitSampling(20)
      mon.expect(evTxn(evByte(0xA5, ack = true)) : _*)

      // Reset przy JALOWEJ magistrali: po STOP obie linie sa juz
      // puszczone, wiec reset nie generuje zadnych zbocz i checker
      // patrzy wylacznie na valid. Reset w trakcie ruchu to osobna
      // rzecz - mst_random_reset.
      // waitActiveEdge, NIE waitSampling: waitSampling zlicza tylko
      // zbocza, na ktorych isSamplingEnable, czyli te ze ZWOLNIONYM
      // resetem (spinal/core/sim/package.scala:920). Pod aktywnym
      // resetem licznik nigdy nie dobija, deassertReset ponizej sie nie
      // wykonuje i symulacja stoi do SimTimeout.
      d.clockDomain.assertReset()
      d.clockDomain.waitActiveEdge(20)
      d.clockDomain.deassertReset()
      d.clockDomain.waitSampling(10)

      slave.clear()
      start(d); slave.writeAck(); write(d, 0x5A); stop(d)
      d.clockDomain.waitSampling(20)
      mon.expect(evTxn(evByte(0x5A, ack = true)) : _*)
    }

    // =================================================================
    //  V2 / V3 - tylko na krotkich konfiguracjach
    // =================================================================
    if (heavy(cfgName)) {

      scenario("mst_write_then_read") { case Env(d, bus, slave, mon, rsps) =>
        start(d)
        slave.writeAck(ack = true); write(d, 0xA5)
        slave.sendByte(0x5A);       read(d, ack = false)
        stop(d)
        d.clockDomain.waitSampling(20)

        mon.expect(evTxn(evByte(0xA5, ack = true), evByte(0x5A, ack = false)) : _*)
        assert(rsps.map(r => (r.data, r.ack)).toSeq == Seq((0xA5, true), (0x5A, false)),
               s"${rsps.toSeq}")
      }

      scenario("mst_repeated_start") { case Env(d, bus, slave, mon, rsps) =>
        start(d)
        slave.writeAck(); write(d, 0x11)
        start(d)                            // RESTART, bez STOP
        slave.writeAck(); write(d, 0x22)
        stop(d)
        d.clockDomain.waitSampling(20)

        val expected = ((Start +: evByte(0x11, ack = true)) ++
                        (Start +: evByte(0x22, ack = true))) :+ Stop
        mon.expect(expected : _*)
      }

      scenario("mst_cmd_backpressure") { case Env(d, bus, slave, mon, rsps) =>
        val rng = new Random(3)

        /** Przerwa miedzy komendami. Najpierw stala zwloka, zeby filtr
          * monitora domknal ostatni bit (opoznienie rzedu `window`) -
          * inaczej zdarzenie z poprzedniej komendy wpada do okna
          * pomiarowego i test oskarza mastera o cudzy bit. */
        def gap() : Unit = {
          d.clockDomain.waitSampling(2 * I2cMonitor.defaultWindow + 4)
          val ev  = mon.events.size
          val scl = d.io.pins.scl.write.toBoolean
          d.clockDomain.waitSampling(rng.nextInt(4 * g.quarterCycles) + 1)
          assert(d.io.pins.scl.write.toBoolean == scl, "SCL ruszyl bez komendy")
          assert(mon.events.size == ev,
                 s"zdarzenie na magistrali bez komendy (${mon.events.size - ev})")
        }

        start(d); gap()
        slave.writeAck();     write(d, 0x5A);      gap()
        slave.sendByte(0xA5); read(d, ack = true); gap()
        stop(d)
        d.clockDomain.waitSampling(20)

        mon.expect(evTxn(evByte(0x5A, ack = true), evByte(0xA5, ack = true)) : _*)
      }

      scenario("mst_write_readback") { case Env(d, bus, slave, mon, rsps) =>
        start(d)
        slave.writeAck(); write(d, 0x96)
        stop(d)
        d.clockDomain.waitSampling(20)

        mon.check()
        assert(rsps.head.data == 0x96,
               f"readback 0x${rsps.head.data}%02X, wyslano 0x96")
      }

      scenario("mst_clock_stretching") { case Env(d, bus, slave, mon, rsps) =>
        val rng = new Random(4)

        // START przed forkiem, tak jak w host_mode_clock_stretching:
        // przy jalowej magistrali SCL jest juz wysoko, wiec fork
        // odpalony wczesniej sciagnalby linie jeszcze przed startem.
        start(d)
        fork {
          while (true) {
            d.clockDomain.waitSamplingWhere(d.io.pins.scl.write.toBoolean)
            bus.stretch(rng.nextInt(4 * g.quarterCycles) + 1)
            d.clockDomain.waitSamplingWhere(!d.io.pins.scl.write.toBoolean)
          }
        }

        slave.writeAck();     write(d, 0xC3)
        slave.sendByte(0x3C); read(d, ack = true)
        stop(d)
        d.clockDomain.waitSampling(20)

        mon.expect(evTxn(evByte(0xC3, ack = true), evByte(0x3C, ack = true)) : _*)
        assert(rsps.map(_.data).toSeq == Seq(0xC3, 0x3C), s"${rsps.map(_.data).toSeq}")
      }

      scenario("mst_bit_count") { case Env(d, bus, slave, mon, rsps) =>
        start(d)
        for (v <- Seq(0x00, 0xFF, 0x5A)) { slave.writeAck(); write(d, v) }
        stop(d)
        d.clockDomain.waitSampling(20)

        mon.check()
        val got  = mon.drain()
        val bits = got.count { case Bit(_) => true; case _ => false }
        assert(bits == 27, s"bitow na magistrali $bits, oczekiwano 27 (3 x 9)")
        assert(got.size == 29 && got.head == Start && got.last == Stop, s"$got")
      }

      // ---------------------------------------------------------------
      //  mst_random_reset
      //
      //  NAJKRUCHSZY test w tym pliku, celowo opisany. Reset leci z
      //  forka, zeby watek testu nie musial zgadywac momentu. Komenda
      //  przerwana resetem DOMKNIE sie sama: io.cmd.valid trzymamy
      //  wysoko, wiec master po wyjsciu z resetu przyjmuje ja od nowa.
      //  Ruch z tej czesci jest protokolarnie bezsensowny i jest
      //  wyrzucany (mon.forget) - sprawdzamy dwie rzeczy: czy reset
      //  puscil magistrale i czy NASTEPNA transakcja jest czysta.
      // ---------------------------------------------------------------
      scenario("mst_random_reset") { case Env(d, bus, slave, mon, rsps) =>
        val rng = new Random(5)

        for (_ <- 0 until 3) {
          fork {
            d.clockDomain.waitSampling(rng.nextInt(8 * g.quarterCycles) + g.quarterCycles)
            d.clockDomain.assertReset()
            d.clockDomain.waitActiveEdge(5)   // patrz phy_cmd_reset_quiet
            assert(d.io.pins.scl.write.toBoolean && d.io.pins.sda.write.toBoolean,
                   "reset nie puscil magistrali")
            d.clockDomain.deassertReset()
          }

          slave.writeAck()
          start(d); write(d, 0x5A)
          d.clockDomain.waitSampling(8 * g.quarterCycles)

          slave.clear(); mon.forget(); rsps.clear()

          start(d); slave.writeAck(); write(d, 0xA5); stop(d)
          d.clockDomain.waitSampling(20)
          mon.expect(evTxn(evByte(0xA5, ack = true)) : _*)
          assert(rsps.size == 1 && rsps.head.ack, s"po resecie rsp = ${rsps.toSeq}")
          rsps.clear()
        }
      }

      scenario("mst_stress_random_transactions") { case Env(d, bus, slave, mon, rsps) =>
        val rng      = new Random(7)
        val expected = mutable.ArrayBuffer[Event]()
        val expRsp   = mutable.ArrayBuffer[(Int, Boolean)]()

        for (_ <- 0 until 4) {
          start(d); expected += Start
          for (_ <- 0 to rng.nextInt(2)) {
            val v   = rng.nextInt(256)
            val ack = rng.nextBoolean()
            if (rng.nextBoolean()) {
              slave.writeAck(ack); write(d, v)   // zapis: ACK od slave'a
            } else {
              slave.sendByte(v);   read(d, ack)  // odczyt: ACK od nas
            }
            expected ++= evByte(v, ack)
            expRsp   += ((v, ack))                 // WRITE: readback == v
          }
          stop(d); expected += Stop
        }
        d.clockDomain.waitSampling(20)

        mon.expect(expected.toSeq : _*)
        assert(rsps.map(r => (r.data, r.ack)).toSeq == expRsp.toSeq,
               s"rsp = ${rsps.toSeq}\nexp = ${expRsp.toSeq}")
      }
    }
  }

  // -----------------------------------------------------------------
  //  ODLOZONE
  // -----------------------------------------------------------------

  unimplemented("mst_arbitration_lost",
    "I2cMaster ma juz readback SDA (io.rsp.data przy zapisie), ale nie " +
    "porownuje go z wystawionym bitem i nie ma czym zglosic przegranej")

  unimplemented("mst_stretch_timeout",
    "brak timeoutu na stretching w PHY - odlozone razem z host_stretch_timeout")

  unimplemented("mst_bus_free_time_after_stop",
    "brak tBUF po STOP - odlozone razem z bus_free_time_after_stop")

  unimplemented("phy_cmd_backpressure",
    "ready tego portu sterowane jest przez PHY, nie przez testbench - " +
    "jedynym dostepnym wzorcem ready jest ten z clock stretchingu, " +
    "czyli mst_clock_stretching")

  unimplemented("phy_cmd_stress_with_rand_reset",
    "stimulus pokrywa mst_random_reset; do dolaczenia checkerow strumienia " +
    "brakuje instrumentacji, ktora przezywa assertReset w trakcie transakcji")
}

class I2cMasterFsmTestplan   extends I2cMasterTestplan("mst_fsm",   I2cMasterBuild.fsm)
class I2cMasterTableTestplan extends I2cMasterTestplan("mst_table", I2cMasterBuild.table)

// =====================================================================
//  ROWNOWAZNOSC OBU PHY - osobna suita, bo jest to jedyny testpoint,
//  ktory potrzebuje DWOCH DUT-ow naraz. Gdyby siedzial w planie wyzej,
//  kompilowalby oba warianty w kazdej z dwoch suit, czyli cztery razy
//  zamiast dwoch.
//
//  Po co to, skoro oba warianty przechodza ten sam plan: plan sprawdza
//  ZDARZENIA, a nie wszystkie mozliwe rozjazdy. Ten test porownuje
//  pelne ciagi zdarzen i rsp bok w bok, wiec lapie roznice, ktorej nikt
//  nie przewidzial w zadnym checkingu - a dwie implementacje PHY sa po
//  to, zeby byly wymienne.
//
//  Konfiguracja: qmin. Najkrotsza w symulacji i z zerowym zapasem na
//  filtrze, wiec jesli cos ma sie rozjechac, rozjedzie sie tutaj.
// =====================================================================
class I2cMasterEquivalence extends TestplanSuite {
  import I2cEvent._
  import I2cMasterDriver._

  def testplan : Seq[Testpoint] = Seq(
    Testpoint("mst_phy_equivalence", Stage.V2,
      "I2cPhyFsm i I2cPhyTable sa wymienne pod I2cMaster",
      stimulus = Seq("Ten sam ciag: START, WRITE+ACK, READ+ACK, WRITE+NACK, STOP",
                     "Ten sam seed, ta sama konfiguracja (qmin)"),
      checking = Seq("Identyczny ciag zdarzen magistrali",
                     "Identyczny ciag io.rsp")))

  testpoint("mst_phy_equivalence") {
    val g = I2cGenerics(96 MHz, 8 MHz, filterWindow = 3)

    def trace(tag : String, build : I2cGenerics => I2cPhyBase)
        : (Seq[Event], Seq[(Int, Boolean)]) = {
      val compiled = Config.sim.workspaceName(s"mst_eq_$tag").compile { I2cMaster(g, build) }
      var out : (Seq[Event], Seq[(Int, Boolean)]) = null

      compiled.doSim(s"eq_$tag", seed = 42) { d =>
        val e = setup(d)
        start(d)
        e.slave.writeAck(ack = true);  write(d, 0xA5)
        e.slave.sendByte(0x5A);        read(d, ack = true)
        e.slave.writeAck(ack = false); write(d, 0x0F)
        stop(d)
        d.clockDomain.waitSampling(20)
        e.mon.check()
        out = (e.mon.drain(), e.rsps.toSeq.map(r => (r.data, r.ack)))
      }
      out
    }

    val (evFsm, rsFsm) = trace("fsm",   I2cMasterBuild.fsm)
    val (evTab, rsTab) = trace("table", I2cMasterBuild.table)

    info(s"fsm   : $evFsm")
    info(s"table : $evTab")

    assert(evFsm == evTab,
           s"rozjazd zdarzen:\n  fsm   = $evFsm\n  table = $evTab")
    assert(rsFsm == rsTab,
           s"rozjazd rsp:\n  fsm   = $rsFsm\n  table = $rsTab")
  }
}
