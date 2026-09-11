package newhope.i2c

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._
import scala.collection.mutable
import scala.util.Random
import newhope.vertebra.{Stage, Testpoint, TestplanSuite}
import newhope.vertebra.sim.SimBackend

// =====================================================================
//  JEDYNA suita testowa I2cPhy. Zbudowana na testpointach z OpenTitan
//  hw/ip/i2c/data/i2c_testplan.hjson (Apache 2.0) - nazwy celowo takie
//  same jak tam, latwiej wrocic do zrodla po szczegoly.
//
//  Odrzucone jako nieaplikowalne: csr_*, tl_*, *_fifo_*, *_intr*,
//  alert_test, target_* (jestesmy tylko masterem), host_override.
//
//  SCALENIE Z I2cPhySuite
//  ----------------------
//  I2cPhySuite (AnyFunSuite, jedna konfiguracja, bez planu) byla
//  artefaktem kolejnosci prac: powstala zanim pojawil sie vertebra.
//  Jej `g` to bylo dokladnie I2cGenerics(100 MHz, 1 MHz), czyli config
//  "fmp1M" ponizej - ten sam RTL kompilowany drugi raz do drugiego
//  workspace'u. Rozdzielenie kosztowalo kompilacje i sprawialo, ze
//  cztery testy sanity biegaly WYLACZNIE przy q=25, gdzie wszystkiego
//  jest w nadmiarze.
//
//  Przy przepisywaniu na testpointy wyszla luka: zaden testpoint planu
//  nie dotykal toru io.rsp. Sprawdzal go tylko I2cSmoke.run, ktory z
//  zalozenia nie rzuca (sweep musi przejsc przez komorki, ktore nie
//  dzialaja). Stad phy_read_data_path.
//
//  Nazwy z przedrostkiem phy_ nie maja odpowiednika w OpenTitanie -
//  to sanity ponizej poziomu ich smoke'a, plus wlasnosci tej konkretnej
//  konstrukcji (filter_window_vs_quarter_boundary).
//
//  Debugowanie pojedynczej komorki - kazdy testpoint ma juz fale FST
//  i wlasny workspace:
//    sbt "testOnly *I2cPhyTableTestplan -- -z \"host_smoke (qmin)\""
//
//  cmd / byte / cmdWithSda / setup -> I2cSmoke.scala (jedyna definicja)
//  I2cBusModel / I2cMonitor        -> I2cAgent.scala
//
//  UWAGA: I2cSmoke.setup nie ustawia SimTimeout (robi to dopiero
//  I2cSmoke.run). waitSamplingWhere na cmd.ready nie ma wlasnego limitu,
//  wiec zle sparametryzowana konfiguracja tu zawisnie zamiast failowac.
// =====================================================================
abstract class I2cPhyTestplan(label : String,
                              build : I2cGenerics => I2cPhyBase) extends TestplanSuite {
  import I2cEvent._
  import I2cPhyCmdMode._
  import I2cSmoke.{cmd, byte, cmdWithSda, setup}

  // -------------------------------------------------------------------
  //  PLAN
  //
  //  `def`, nie `val` - patrz guard w TestplanSuite. `val` zadeklarowany
  //  tutaj akurat by przeszedl (jest nad petla po configach), ale ta
  //  wlasnosc znika przy pierwszym przestawieniu linijek, a blad jest
  //  wtedy nieczytelny.
  // -------------------------------------------------------------------
  def testplan : Seq[Testpoint] = Seq(

    // --- V1: sanity, przeniesione z I2cPhySuite --------------------
    Testpoint("phy_bus_condition_sanity", Stage.V1,
      "Same komendy START i STOP produkuja warunki START/STOP na magistrali",
      stimulus = Seq("START, potem STOP, bez zadnych bitow miedzy nimi"),
      checking = Seq("Monitor dekoduje dokladnie Start, Stop - nic wiecej",
                     "Zero naruszen niezmiennikow I2C")),

    Testpoint("phy_write_byte_msb_first", Stage.V1,
      "Bajt wychodzi bit po bicie, najstarszym naprzod",
      // 0xA5 = 10100101 zmienia sie na kazdej granicy bitu, wiec
      // spoznione probkowanie widac natychmiast. 0x00 / 0xFF nie.
      stimulus = Seq("START, deterministyczny bajt 0xA5, STOP"),
      checking = Seq("Osiem zdarzen Bit w kolejnosci od bitu 7 do 0",
                     "Wartosci zgodne z 0xA5")),

    // Ten testpoint zamyka luke znaleziona przy scalaniu suit: tor rsp
    // nie byl sprawdzany nigdzie poza I2cSmoke.run, ktory nie rzuca.
    Testpoint("phy_read_data_path", Stage.V1,
      "io.rsp.data odzwierciedla poziom faktycznie obecny na SDA",
      stimulus = Seq("Slave sciaga SDA w Q0 juz przyjetej komendy (cmdWithSda)",
                     "Drugi BIT przy puszczonej linii"),
      checking = Seq("Flow rsp wystawia dokladnie dwie probki",
                     "Pierwsza false (slave trzyma), druga true (linia puszczona)")),

    Testpoint("phy_clock_stretching_basic", Stage.V1,
      "Stretching o znanej dlugosci wydluza bit zamiast go gubic",
      stimulus = Seq("Slave trzyma SCL przez 3 cwiartki po pierwszym puszczeniu linii"),
      checking = Seq("Sekwencja Start, Bit(false), Stop bez zmian",
                     "Zmierzone tHIGH nie krotsze niz 2 * quarterCycles")),

    // --- V1/V2: z i2c_testplan.hjson -------------------------------
    Testpoint("host_smoke", Stage.V1,
      "Losowe transakcje master->bus, sprawdzane przeciw modelowi magistrali",
      stimulus = Seq("8 transakcji START / 1-3 bajty / STOP",
                     "Zawartosc bajtow losowa (seed staly)",
                     "Po kazdym bajcie szczelina na ACK (BIT z data=true)"),
      checking = Seq("Monitor dekoduje dokladnie te zdarzenia, ktore wyslano",
                     "Zero naruszen niezmiennikow I2C po drodze")),

    Testpoint("host_mode_config_perf", Stage.V2,
      "Czasy tHIGH i tLOW zgadzaja sie z wyliczeniem z generykow",
      stimulus = Seq("START, bajt 0x5A, STOP w kazdej konfiguracji"),
      checking = Seq("tHIGH == sclHighCycles (2q + falszywy stretching), tol. 20 j.sim",
                     "tLOW >= 2 * quarterCycles")),

    Testpoint("host_mode_clock_stretching", Stage.V2,
      "Odpornosc na stretching o losowej dlugosci, asynchroniczny do zegara hosta",
      stimulus = Seq("Slave trzyma SCL przez 1..6 cwiartek po kazdym puszczeniu linii",
                     "Dlugosc losowana per zbocze, nie stala"),
      checking = Seq("Sekwencja zdarzen niezmieniona mimo rozciagania",
                     "Zaden bit nie ginie i zaden nie dubluje sie")),

    Testpoint("host_rx_oversample", Stage.V2,
      "Glitch krotszy niz okno filtra nie dochodzi do dekodera",
      stimulus = Seq("Sciagniecie SDA na filterWindow-1 cykli przy wysokim SCL"),
      checking = Seq("Monitor widzi Start, Bit(true), Stop - glitch niewidoczny")),

    // Bez odpowiednika w OpenTitanie - wlasnosc TEJ konstrukcji,
    // znaleziona charakteryzacja (FilterSweep). Czysto parametryczny,
    // zero symulacji, dlatego `stimulus` jest puste.
    Testpoint("filter_window_vs_quarter_boundary", Stage.V2,
      "Filtr wejsciowy nadaza za magistrala w kazdej konfiguracji suity",
      checking = Seq("filterLatency (= w + 3) <= 2 * quarterCycles dla kazdego Cfg",
                     "Okno monitora testbenchu < 2 * quarterCycles - inaczej " +
                     "sweep mierzy slepote monitora, nie awarie DUT-a")),

    // --- odlozone ---------------------------------------------------
    Testpoint("multi_controller_arbitration_lost_interference", Stage.V3,
      "Po przegranej arbitrazu DUT puszcza magistrale",
      stimulus = Seq("Testbench sciaga SDA w trakcie transakcji, gdy DUT ja puscil"),
      checking = Seq("DUT wykrywa rozjazd wystawionego i odczytanego SDA",
                     "Po wykryciu obie linie sa puszczone do konca transakcji")),

    Testpoint("multi_controller_clock_synchronization", Stage.V3,
      "Zsynchronizowany SCL ma tLOW dluzszego mastera i tHIGH krotszego",
      stimulus = Seq("Obcy master sciaga SCL juz po tym, jak nasz ja puscil"),
      checking = Seq("Licznik cwiartki przeladowuje sie na opadajacym zboczu SCL",
                     "Brak dodatkowego impulsu SCL po zwolnieniu linii")),

    Testpoint("host_stretch_timeout", Stage.V3,
      "Stretching dluzszy niz limit podnosi blad zamiast wisiec w nieskonczonosc",
      stimulus = Seq("Slave trzyma SCL dluzej niz zaprogramowany timeout"),
      checking = Seq("Sygnal stretch_timeout podnosi sie w oknie +/- 1 cyklu",
                     "Transakcja konczy sie, symulacja nie dobija do SimTimeout")),

    Testpoint("bus_free_time_after_stop", Stage.V2,
      "Po STOP magistrala jest wolna przez tBUF przed nastepnym START",
      stimulus = Seq("STOP i natychmiastowy START bez przerwy"),
      checking = Seq("Odstep miedzy narastajacym SDA w STOP a opadajacym w START >= tBUF")))

  // -------------------------------------------------------------------
  //  KONFIGURACJE
  //
  //  i2c_timing_parameters_cg + i2c_operating_mode_cg: "Cover the SCL
  //  frequency". Ostatnia pozycja to dolna granica quarterCycles -
  //  odpowiednik "ensure sufficient test coverage around lower bound
  //  of THIGH".
  // -------------------------------------------------------------------
  case class Cfg(name : String, g : I2cGenerics)

  val configs = Seq(
    Cfg("std100k",  I2cGenerics(100 MHz, 100 kHz)),
    Cfg("fast400k", I2cGenerics(100 MHz, 400 kHz)),
    // Byle `g` dawnej I2cPhySuite. Po scaleniu jeden DUT zamiast dwoch.
    Cfg("fmp1M",    I2cGenerics(100 MHz,   1 MHz)),
    // quarterCycles == 3: najkrotszy okres SCL, przy ktorym filtr jeszcze
    // nadaza (w+3 = 6 == 2q, zapas 0) i przy ktorym monitor testbenchu
    // ma czym probkowac (stan niski 6 cykli wobec okna monitora 4).
    // 96/8/4 == 3 dokladnie - bez zmiennoprzecinkowej loterii.
    Cfg("qmin", I2cGenerics(96 MHz, 8 MHz, filterWindow = 3))
  )

  for (Cfg(cfgName, g) <- configs) {

    lazy val dut : SimCompiled[I2cPhyBase] = Config.sim
      .withFstWave
      .workspaceName(s"${label}_${cfgName}_${SimBackend.default.label}")
      .compile { build(g) }

    /** Jeden testpoint w jednej konfiguracji. `name` MUSI istniec w
      * planie - inaczej lookup rzuci juz przy konstrukcji suity, zanim
      * cokolwiek sie skompiluje. To jest cala dyscyplina z §3.2. */
    def scenario(name : String)(body : (I2cPhyBase, I2cBusModel, I2cMonitor) => Unit) : Unit =
      testpoint(name, variant = cfgName) {
        dut.doSim(s"${label}_${cfgName}_$name", seed = 42) { d =>
          val (bus, mon) = setup(d)
          body(d, bus, mon)
        }
      }

    // =================================================================
    //  V1 - sanity (dawna I2cPhySuite, teraz w kazdej konfiguracji)
    // =================================================================

    scenario("phy_bus_condition_sanity") { (d, _, mon) =>
      cmd(d, START)
      cmd(d, STOP)
      d.clockDomain.waitSampling(20)
      mon.expect(Start, Stop)
    }

    scenario("phy_write_byte_msb_first") { (d, _, mon) =>
      val v = 0xA5
      cmd(d, START)
      byte(d, v)
      cmd(d, STOP)
      d.clockDomain.waitSampling(20)
      val expected = Start +: (7 downto 0).map(i => Bit(((v >> i) & 1) != 0)) :+ Stop
      mon.expect(expected : _*)
    }

    // Idzie przez cmdWithSda, nie przez `bus.sdaPull = true; cmd(...)`.
    // Powod w naglowku I2cSmoke: zaklocenie postawione PRZED handshakiem
    // oznacza, ze "slave" odpowiada, zanim master zdazyl zapytac, i filtr
    // dostaje na dojscie caly czas sprzed handshake'u. Przy q=25 to
    // uchodzilo na sucho, przy qmin (q=3) nie ma na to zapasu.
    scenario("phy_read_data_path") { (d, bus, _) =>
      val seen = mutable.Queue[Boolean]()
      FlowMonitor(d.io.rsp, d.clockDomain) { p => seen.enqueue(p.data.toBoolean) }

      cmd(d, START)
      cmdWithSda(d, bus, BIT, data = true, pull = true)  // slave odpowiada zerem
      bus.sdaPull = false
      cmd(d, BIT, data = true)                           // slave puscil -> jeden
      cmd(d, STOP)
      d.clockDomain.waitSampling(20)

      assert(seen.toSeq == Seq(false, true), s"rsp = ${seen.toSeq}")
    }

    scenario("phy_clock_stretching_basic") { (d, bus, mon) =>
      cmd(d, START)

      // slave trzyma SCL nisko przez 3 cwiartki po tym jak master ja puscil
      fork {
        d.clockDomain.waitSamplingWhere(!d.io.pins.scl.write.toBoolean)
        d.clockDomain.waitSamplingWhere(d.io.pins.scl.write.toBoolean)
        bus.stretch(3 * g.quarterCycles)
      }

      cmd(d, BIT, data = false)
      cmd(d, STOP)
      d.clockDomain.waitSampling(20)

      mon.expect(Start, Bit(false), Stop)
      assert(mon.highLen >= g.quarterCycles * 2 * 10,
             s"stan wysoki SCL za krotki: ${mon.highLen}")
    }

    // =================================================================
    //  V1/V2 - z i2c_testplan.hjson
    // =================================================================

    // ---------------------------------------------------------------
    //  host_smoke
    //  "Smoke test in which random transactions are sent to the DUT
    //   and received asynchronously with scoreboard checks."
    // ---------------------------------------------------------------
    scenario("host_smoke") { (d, _, mon) =>
      val rng  = new Random(1)
      val sent = mutable.ArrayBuffer[Event]()

      for (_ <- 0 until 8) {
        cmd(d, START); sent += Start
        val payload = Seq.fill(1 + rng.nextInt(3))(rng.nextInt(256))
        for (v <- payload) {
          byte(d, v)
          sent ++= (7 downto 0).map(i => Bit(((v >> i) & 1) != 0))
          cmd(d, BIT, data = true)          // szczelina na ACK
          sent += Bit(true)
        }
        cmd(d, STOP); sent += Stop
      }
      d.clockDomain.waitSampling(20)
      mon.expect(sent.toSeq : _*)
    }

    // ---------------------------------------------------------------
    //  host_mode_config_perf
    //  "Calculate expected bus performance based on configuration.
    //   Ensure that SCL frequency during data bytes matches expectation."
    // ---------------------------------------------------------------
    scenario("host_mode_config_perf") { (d, _, mon) =>
      cmd(d, START)
      byte(d, 0x5A)
      cmd(d, STOP)
      d.clockDomain.waitSampling(20)

      // tHIGH = dwie cwiartki + falszywy stretching w Q1: filtr SCL widzi
      // zero jeszcze przez filterWindow + 3 cykle po puszczeniu linii,
      // a `when(stretching) { timer.restart() }` doklada cykl rejestru.
      // Ten sam czlon w+4 wychodzi z FilterSweep (siatka .stretch.csv,
      // 10 puszczen SCL = 10*(w+4) cykli).
      val expectedHigh = g.sclHighCycles * 10
      assert(math.abs(mon.highLen - expectedHigh) <= 20,
             s"tHIGH = ${mon.highLen}, oczekiwano ~$expectedHigh")
      assert(mon.lowLen >= 2 * g.quarterCycles * 10,
             s"tLOW = ${mon.lowLen} za krotkie")
    }

    // ---------------------------------------------------------------
    //  host_mode_clock_stretching
    //  "Verify robust for long and short periods of clock stretching.
    //   Target-deassertion may not be synchronous to the Host's clock."
    // ---------------------------------------------------------------
    scenario("host_mode_clock_stretching") { (d, bus, mon) =>
      val rng = new Random(2)
      cmd(d, START)

      fork {
        while (true) {
          // czekamy az master puszcza SCL, potem trzymamy losowo dlugo
          d.clockDomain.waitSamplingWhere(d.io.pins.scl.write.toBoolean)
          bus.stretch(rng.nextInt(6 * g.quarterCycles) + 1)
          d.clockDomain.waitSamplingWhere(!d.io.pins.scl.write.toBoolean)
        }
      }

      byte(d, 0xC3)
      cmd(d, STOP)
      d.clockDomain.waitSampling(20)

      val expected = Start +: (7 downto 0).map(i => Bit(((0xC3 >> i) & 1) != 0)) :+ Stop
      mon.expect(expected : _*)
    }

    // ---------------------------------------------------------------
    //  host_rx_oversample (odpowiednik: filtr wejsciowy)
    //  Glitch krotszy niz okno filtra musi zostac zignorowany.
    // ---------------------------------------------------------------
    scenario("host_rx_oversample") { (d, bus, mon) =>
      cmd(d, START)

      fork {
        d.clockDomain.waitSamplingWhere(d.io.pins.scl.write.toBoolean)
        bus.sdaPull = true                        // glitch na SDA...
        d.clockDomain.waitSampling(g.filterWindow - 1)
        bus.sdaPull = false                       // ...krotszy niz okno
      }

      cmd(d, BIT, data = true)
      cmd(d, STOP)
      d.clockDomain.waitSampling(20)
      mon.expect(Start, Bit(true), Stop)          // glitch niewidoczny
    }
  }

  // -----------------------------------------------------------------
  //  filter_window_vs_quarter_boundary
  //
  //  DUT ponizej progu NADAL DZIALA - protokol i odczyt przechodza, bo
  //  opoznienie toru SDA jest kompensowane przez falszywy stretching
  //  toru SCL (oba tory maja to samo okno). Rozjezdza sie timing, nie
  //  poprawnosc. Dlatego to jest test na PARAMETRY, bez symulacji -
  //  zero kompilacji, jeden testpoint bez wariantu.
  //
  //  Granice kalibrowac przez: FilterSweep --w 1:20 --q 2:6 --backend ghdl
  //  (siatka .stretch.csv, punkt nasycenia krzywej).
  // -----------------------------------------------------------------
  testpoint("filter_window_vs_quarter_boundary") {
    configs.foreach { c =>
      val margin = 2 * c.g.quarterCycles - c.g.filterLatency
      info(f"${c.name}%-10s w=${c.g.filterWindow}%2d  2q=${2 * c.g.quarterCycles}%3d  zapas=$margin%3d")
    }

    val bad = configs.filterNot(_.g.filterTracksScl)
    assert(bad.isEmpty,
      "filtr SCL nie nadaza w konfiguracjach: " +
      bad.map(c => s"${c.name} (w=${c.g.filterWindow}, 2q=${2 * c.g.quarterCycles})").mkString(", "))

    // Ten sam warunek po stronie TESTBENCHU: filtr monitora przelacza stan
    // po `window` probkach, wiec stan linii krotszy niz okno do dekodera
    // nie dojdzie. Objaw jest mylacy - zamiast bledu timingu dostajesz
    // sekwencje Start,Stop,Start,Stop bez zadnego Bit-u.
    val blind = configs.filter(c => I2cMonitor.defaultWindow >= 2 * c.g.quarterCycles)
    assert(blind.isEmpty,
      s"monitor (okno ${I2cMonitor.defaultWindow}) nie zobaczy zbocz w: " +
      blind.map(c => s"${c.name} (2q=${2 * c.g.quarterCycles})").mkString(", "))
  }

  // -----------------------------------------------------------------
  //  Testpointy aplikowalne, ale wymagajace funkcji, ktorych I2cPhy
  //  jeszcze nie ma. unimplemented() daje zolty wpis w raporcie i - w
  //  odroznieniu od golego pending - weryfikuje nazwe wzgledem planu
  //  oraz odroznia "odlozone" od "zapomniane" w completeness.
  // -----------------------------------------------------------------

  unimplemented("multi_controller_arbitration_lost_interference",
    "I2cPhy nie ma wykrywania kolizji na SDA (brak sda_chk)")

  unimplemented("multi_controller_clock_synchronization",
    "I2cPhy nie przeladowuje licznika na opadajacym SCL obcego mastera - " +
    "po zwolnieniu linii przez obcego mastera generuje dodatkowy impuls SCL")

  unimplemented("host_stretch_timeout",
    "brak timeoutu na clock stretching - stretching moze trwac w nieskonczonosc")

  unimplemented("bus_free_time_after_stop",
    "brak tBUF po STOP w tablicy cwiartek")
}

class I2cPhyFsmTestplan   extends I2cPhyTestplan("fsm",   g => I2cPhyFsm(g))
class I2cPhyTableTestplan extends I2cPhyTestplan("table", g => I2cPhyTable(g))
