package newhope.aht10

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable
import newhope.vertebra.{Stage, Testpoint, TestplanSuite}
import newhope.vertebra.sim.SimBackend

// =====================================================================
//  Testplan BinToBcd.
//
//  Przestrzen wejsciowa ma 2048 punktow i model referencyjny miesci sie
//  w trzech linijkach - nie ma powodu testowac tego inaczej niz w
//  pelni. bcd_exhaustive zalatwia cala poprawnosc arytmetyczna i
//  dlatego NIE MA tu osobnego testpointu na wartosci brzegowe: 0, 999
//  i 1000 sa w tym samym przebiegu co reszta, a osobny wpis
//  sugerowalby, ze pokrywa cos, czego exhaustive nie pokrywa.
//
//  Pozostale testpointy dotycza handshake'u, czyli tego, czego
//  exhaustive nie widzi, bo sam go przestrzega. Tam mieszkaja bledy,
//  ktore w integracji objawiaja sie jako "czasem gubi wynik".
//
//  KONFIGURACJE: docelowa (11 bitow, 4 cyfry) plus wezsza, zeby
//  parametryzacja nie byla teoretyczna. Wariant w8d3 lapie
//  zaszyte na sztywno 11 albo 4.
// =====================================================================
class BinToBcdTestplan extends TestplanSuite {

  def testplan : Seq[Testpoint] = Seq(

    Testpoint("bcd_exhaustive", Stage.V1,
      "Kazda wartosc wejsciowa daje poprawne cyfry BCD",
      stimulus = Seq("Wszystkie wartosci od 0 do 2^binWidth - 1, po kolei"),
      checking = Seq("Kazda cyfra zgodna z modelem referencyjnym w Scali",
                     "Cyfry powyzej zakresu wejscia zawsze zerowe")),

    Testpoint("bcd_latency_constant", Stage.V1,
      "Czas konwersji nie zalezy od danych",
      // Double dabble ma stala liczbe iteracji z definicji, ale latwo
      // to zepsuc przedwczesnym wyjsciem przy bin == 0. Wtedy Aht10Ctrl
      // dostaje wynik w innym takcie dla temperatur ujemnych niz
      // dodatnich i objaw wyglada na blad skalowania.
      stimulus = Seq("Konwersja 0, 1, wartosci srodkowej i maksymalnej"),
      checking = Seq("Zmierzona latencja identyczna dla wszystkich czterech",
                     "Miesci sie w binWidth + 2 taktow od przyjecia komendy")),

    Testpoint("bcd_single_pulse", Stage.V1,
      "rsp.valid trwa dokladnie jeden takt",
      stimulus = Seq("Jedna konwersja, potem 50 taktow bezczynnosci"),
      checking = Seq("Dokladnie jedno podniesienie valid",
                     "Zaden kolejny impuls bez nowej komendy")),

    Testpoint("bcd_backpressure", Stage.V1,
      "cmd.ready jest niskie przez cala konwersje",
      stimulus = Seq("Komenda, potem obserwacja ready az do wyniku"),
      checking = Seq("ready opada natychmiast po przyjeciu komendy",
                     "ready nie podnosi sie ani razu przed rsp.valid")),

    Testpoint("bcd_back_to_back", Stage.V1,
      "Komendy bez przerwy nie gubia sie i nie zamieniaja kolejnoscia",
      stimulus = Seq("Osiem roznych wartosci wysylanych natychmiast po zwolnieniu ready"),
      checking = Seq("Osiem wynikow, w kolejnosci wysylania",
                     "Zaden wynik nie jest powtorzeniem poprzedniego")),

    Testpoint("bcd_idle_after_reset", Stage.V1,
      "Bez komendy konwerter milczy",
      stimulus = Seq("50 taktow po starcie, cmd.valid caly czas nisko"),
      checking = Seq("rsp.valid nigdy sie nie podnosi",
                     "cmd.ready wysokie od pierwszego taktu")))

  // -------------------------------------------------------------------
  case class Cfg(name : String, binWidth : Int, bcdDigits : Int)

  val configs = Seq(
    Cfg("w11d4", 11, 4),   // docelowa: 0..1850 dla stopni Fahrenheita x10
    Cfg("w8d3",   8, 3)    // kontrola parametryzacji
  )

  for (Cfg(cfgName, binWidth, bcdDigits) <- configs) {

    lazy val dut : SimCompiled[BinToBcd] = Config.sim
      .withFstWave
      .workspaceName(s"bintobcd_${cfgName}_${SimBackend.default.label}")
      .compile { BinToBcd(binWidth, bcdDigits) }

    def scenario(name : String)(body : BinToBcd => Unit) : Unit =
      testpoint(name, variant = cfgName) {
        dut.doSim(s"bintobcd_${cfgName}_$name", seed = 42) { d =>
          SimTimeout(50000000)
          d.clockDomain.forkStimulus(10)
          d.io.cmd.valid #= false
          d.io.cmd.payload #= 0
          d.clockDomain.waitSampling()
          body(d)
        }
      }

    // --- pomocnicze ------------------------------------------------
    def refBcd(v : Int) : Seq[Int] = {
      var x = v
      (0 until bcdDigits).map { _ => val dgt = x % 10; x /= 10; dgt }
    }

    def readRsp(d : BinToBcd) : Seq[Int] =
      (0 until bcdDigits).map(i => d.io.rsp.payload(i).toInt)
    
    /** Zwraca w chwili probkowania handshake'u - sygnaly maja tu jeszcze
      * stan sprzed zbocza. Kto chce obserwowac SKUTEK przyjecia komendy,
      * musi najpierw wykonac waitSampling. */
    def send(d : BinToBcd, v : Int) : Unit = {
      d.io.cmd.valid   #= true
      d.io.cmd.payload #= v
      d.clockDomain.waitSamplingWhere(d.io.cmd.ready.toBoolean)
      d.io.cmd.valid #= false
    }

    def convert(d : BinToBcd, v : Int) : Seq[Int] = {
      send(d, v)
      d.clockDomain.waitSamplingWhere(d.io.rsp.valid.toBoolean)
      readRsp(d)
    }

    // --- scenariusze -----------------------------------------------

    scenario("bcd_exhaustive") { d =>
      for (v <- 0 until (1 << binWidth)) {
        val got = convert(d, v)
        assert(got == refBcd(v), s"$v -> $got, oczekiwano ${refBcd(v)}")
      }
    }

    scenario("bcd_latency_constant") { d =>
      val probes = Seq(0, 1, (1 << binWidth) / 2, (1 << binWidth) - 1)
      val lat = probes.map { v =>
        send(d, v)
        var n = 0
        while (!d.io.rsp.valid.toBoolean) { d.clockDomain.waitSampling(); n += 1 }
        d.clockDomain.waitSampling()
        n
      }
      assert(lat.distinct.size == 1, s"latencja zalezna od danych: ${probes.zip(lat)}")
      assert(lat.head <= binWidth + 2, s"latencja ${lat.head}, oczekiwano <= ${binWidth + 2}")
    }

    scenario("bcd_single_pulse") { d =>
      send(d, 42 % (1 << binWidth))
      d.clockDomain.waitSamplingWhere(d.io.rsp.valid.toBoolean)
      d.clockDomain.waitSampling()
      for (_ <- 0 until 50) {
        assert(!d.io.rsp.valid.toBoolean, "valid podniesiony bez komendy")
        d.clockDomain.waitSampling()
      }
    }

    scenario("bcd_backpressure") { d =>
      send(d, (1 << binWidth) - 1)

      // send wraca w chwili probkowania, w ktorej handshake DOPIERO sie
      // odbywa - rejestry maja jeszcze stan sprzed zbocza, wiec ready
      // czyta sie tu jako wysokie. Skutek przyjecia komendy widac
      // dopiero przy nastepnym probkowaniu.
      d.clockDomain.waitSampling()

      var guard = binWidth + 5
      while (!d.io.rsp.valid.toBoolean && guard > 0) {
        assert(!d.io.cmd.ready.toBoolean, "ready wysokie w trakcie konwersji")
        d.clockDomain.waitSampling()
        guard -= 1
      }
      assert(guard > 0, "wynik nie pojawil sie w oczekiwanym oknie")
    }

    scenario("bcd_back_to_back") { d =>
      val values = Seq(0, 1, 9, 10, 99, 100, (1 << binWidth) - 1, 7)
        .map(_ % (1 << binWidth))

      val got = mutable.Queue[Seq[Int]]()
      fork {
        while (true) {
          d.clockDomain.waitSamplingWhere(d.io.rsp.valid.toBoolean)
          got.enqueue(readRsp(d))
        }
      }

      for (v <- values) send(d, v)
      d.clockDomain.waitSampling(binWidth + 5)

      assert(got.size == values.size, s"${got.size} wynikow zamiast ${values.size}")
      for ((v, r) <- values.zip(got.toSeq))
        assert(r == refBcd(v), s"$v -> $r, oczekiwano ${refBcd(v)}")
    }

    scenario("bcd_idle_after_reset") { d =>
      for (_ <- 0 until 50) {
        assert(!d.io.rsp.valid.toBoolean, "valid bez komendy")
        assert(d.io.cmd.ready.toBoolean, "ready niskie w bezczynnosci")
        d.clockDomain.waitSampling()
      }
    }
  }
}
