package newhope.mimas_v2

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable
import newhope.vertebra.{Stage, Testpoint, TestplanSuite}
import newhope.vertebra.sim.SimBackend

// =====================================================================
//  Testplan SevenSegMux.
//
//  Zakres celowo waski. Multiplekser ma dwie warstwy: logike (kto,
//  kiedy, jaki wzorzec) i zjawiska analogowe (jasnosc, duchowanie).
//  Symulacja pokrywa pierwsza i nie moze dotknac drugiej - stad dwa
//  wpisy unimplemented na koncu zamiast udawania, ze plan jest pelny.
//
//  KONFIGURACJE: frameRate 1 MHz zamiast plytkowego 1 kHz. Przy 1 kHz
//  jedna ramka to 100 000 cykli i kazdy testpoint mielilby po kilkaset
//  tysiecy taktow bez dokladania czegokolwiek do pokrycia. Przy 1 MHz
//  szczelina ma 33 cykle i cala suita idzie w sekundy. To jest wlasnie
//  powod, dla ktorego frameRate jest generykiem, a nie stala.
//
//  Wariant noblank istnieje po to, zeby wygaszenie bylo widoczne jako
//  decyzja: te same testpointy musza przejsc bez niego, a jedyna
//  roznica ma byc szczelina w seg_blanking_gap.
// =====================================================================
class SevenSegMuxTestplan extends TestplanSuite {

  def testplan : Seq[Testpoint] = Seq(

    Testpoint("seg_one_digit_at_a_time", Stage.V1,
      "W zadnym takcie nie jest aktywna wiecej niz jedna cyfra",
      stimulus = Seq("Trzy rozne kody na wejsciu, przebieg dwoch pelnych ramek"),
      checking = Seq("Liczba aktywnych bitow en nigdy nie przekracza jednego")),

    Testpoint("seg_digit_rotation", Stage.V1,
      "Cyfry zapalaja sie cyklicznie 0, 1, 2 i wracaja do 0",
      stimulus = Seq("Dwie pelne ramki przy stalych danych"),
      checking = Seq("Sekwencja aktywnych indeksow to dokladnie 0,1,2,0,1,2")),

    Testpoint("seg_duty_cycle_equal", Stage.V1,
      "Kazda cyfra dostaje ten sam czas swiecenia",
      // Nierowny duty cycle to najczestsza przyczyna jednej ciemniejszej
      // cyfry na plytce. Na oko widac to dopiero przy roznicy ~20%.
      stimulus = Seq("Dwie pelne ramki"),
      checking = Seq("Liczba taktow z aktywnym enable rowna dla trzech cyfr, tolerancja 1",
                     "Wartosc zgodna z cyclesPerDigit - blankCycles")),

    Testpoint("seg_decode_table", Stage.V1,
      "Kody 0-9 daja wzorce segmentow zgodne z tablica",
      stimulus = Seq("Kolejno kazdy kod od 0 do 9 na wszystkich trzech pozycjach"),
      checking = Seq("Odwrocony io.seg zgadza sie z SevenSegMux.lut",
                     "Bit kropki zgaszony, gdy dot = false")),

    Testpoint("seg_blank_and_minus", Stage.V1,
      "Kody niecyfrowe: blank gasi wszystko, minus zapala samo g",
      stimulus = Seq("Kod blank, potem minus, potem kod spoza tablicy"),
      checking = Seq("blank -> zero zapalonych segmentow",
                     "minus -> dokladnie segment g",
                     "kod 15 -> zgaszone, nie przypadkowy wzorzec")),

    Testpoint("seg_dot_independent", Stage.V1,
      "Kropka jest niezalezna od kodu cyfry",
      stimulus = Seq("Ten sam kod raz z dot = false, raz z dot = true"),
      checking = Seq("Roznica dotyczy wylacznie bitu 7",
                     "Kropka dziala takze przy kodzie blank")),

    Testpoint("seg_active_low_idle", Stage.V1,
      "Polaryzacja wyjsc zgadza sie z common anode",
      // Ten testpoint istnieje, bo pomylka tutaj daje obraz, ktory
      // wyglada na dzialajacy: swieci sie cos, tylko nie to.
      stimulus = Seq("Wszystkie trzy cyfry na blank, dot = false"),
      checking = Seq("io.seg == 0xFF przez caly czas",
                     "io.en ma zawsze co najmniej dwa bity wysokie")),

    Testpoint("seg_blanking_gap", Stage.V2,
      "Miedzy cyframi jest okno, w ktorym nie swieci nic",
      stimulus = Seq("Dwie pelne ramki w obu wariantach"),
      checking = Seq("blankCycles > 0: dokladnie blankCycles taktow bez aktywnego enable",
                     "blankCycles = 0: przejscie bez ani jednego taktu przerwy")))

  // -------------------------------------------------------------------
  case class Cfg(name : String, frameRate : HertzNumber, blank : Int)

  val configs = Seq(
    Cfg("blank4",  1 MHz, 4),
    Cfg("noblank", 1 MHz, 0)
  )

  for (Cfg(cfgName, frameRate, blank) <- configs) {

    lazy val dut : SimCompiled[SevenSegMux] = Config.sim
      .withFstWave
      .workspaceName(s"sevenseg_${cfgName}_${SimBackend.default.label}")
      .compile { SevenSegMux(100 MHz, frameRate, blank) }

    val perDigit = (100000000 / frameRate.toDouble).toInt / 3

    def scenario(name : String)(body : SevenSegMux => Unit) : Unit =
      testpoint(name, variant = cfgName) {
        dut.doSim(s"sevenseg_${cfgName}_$name", seed = 42) { d =>
          d.clockDomain.forkStimulus(10)
          poke(d, Seq.fill(3)(SevenSegMux.blank), Seq.fill(3)(false))
          d.clockDomain.waitSampling()
          body(d)
        }
      }

    // --- pomocnicze ------------------------------------------------
    def poke(d : SevenSegMux, codes : Seq[Int], dots : Seq[Boolean]) : Unit =
      for (i <- 0 until 3) {
        d.io.digits(i).code #= codes(i)
        d.io.digits(i).dot  #= dots(i)
      }

    /** Indeks aktywnej cyfry albo -1 przy wygaszeniu. Rzuca, gdy
      * aktywna jest wiecej niz jedna - stad seg_one_digit_at_a_time
      * dziala niejawnie w kazdym scenariuszu. */
    def activeDigit(d : SevenSegMux) : Int = {
      val en = (~d.io.en.toInt) & 0x7
      assert(Integer.bitCount(en) <= 1, s"aktywne enable: 0x${en.toHexString}")
      en match { case 0 => -1; case 1 => 0; case 2 => 1; case 4 => 2 }
    }

    /** Segmenty w konwencji aktywne-wysokim, czyli tak jak w tablicy. */
    def segments(d : SevenSegMux) : Int = (~d.io.seg.toInt) & 0xff

    /** Czeka na najblizszy takt, w ktorym cokolwiek swieci. */
    def waitLit(d : SevenSegMux) : Int = {
      var guard = perDigit * 4
      while (activeDigit(d) < 0 && guard > 0) { d.clockDomain.waitSampling(); guard -= 1 }
      assert(guard > 0, "nic nie zapalilo sie przez cztery szczeliny")
      segments(d)
    }

    // --- scenariusze -----------------------------------------------

    scenario("seg_one_digit_at_a_time") { d =>
      poke(d, Seq(1, 2, 3), Seq(true, false, false))
      for (_ <- 0 until perDigit * 6) {
        d.clockDomain.waitSampling()
        activeDigit(d)   // sam assert w srodku
      }
    }

    scenario("seg_digit_rotation") { d =>
      poke(d, Seq(1, 2, 3), Seq(true, false, false))
      val seen = mutable.ArrayBuffer[Int]()
      for (_ <- 0 until perDigit * 6) {
        d.clockDomain.waitSampling()
        val a = activeDigit(d)
        if (a >= 0 && (seen.isEmpty || seen.last != a)) seen += a
      }
      assert(seen.toSeq.take(6) == Seq(0, 1, 2, 0, 1, 2), s"kolejnosc = ${seen.toSeq}")
    }

    scenario("seg_duty_cycle_equal") { d =>
      poke(d, Seq(1, 2, 3), Seq(false, false, false))
      // start od granicy ramki, zeby nie liczyc uciętej szczeliny
      while (activeDigit(d) != 0) d.clockDomain.waitSampling()
      while (activeDigit(d) == 0) d.clockDomain.waitSampling()
      while (activeDigit(d) != 0) d.clockDomain.waitSampling()

      val count = Array(0, 0, 0)
      for (_ <- 0 until perDigit * 6) {
        val a = activeDigit(d)
        if (a >= 0) count(a) += 1
        d.clockDomain.waitSampling()
      }
      val expected = 2 * (perDigit - blank)
      for (i <- 0 until 3)
        assert(math.abs(count(i) - expected) <= 1,
               s"cyfra $i swiecila ${count(i)} taktow, oczekiwano ~$expected")
    }

    scenario("seg_decode_table") { d =>
      for (code <- 0 to 9) {
        poke(d, Seq.fill(3)(code), Seq.fill(3)(false))
        d.clockDomain.waitSampling()
        val got = waitLit(d)
        assert(got == SevenSegMux.lut(code),
               f"kod $code: 0x$got%02x, oczekiwano 0x${SevenSegMux.lut(code)}%02x")
      }
    }

    scenario("seg_blank_and_minus") { d =>
      for ((code, expected) <- Seq(SevenSegMux.blank -> 0x00,
                                   SevenSegMux.minus -> 0x40,
                                   15                -> 0x00)) {
        poke(d, Seq.fill(3)(code), Seq.fill(3)(false))
        d.clockDomain.waitSampling()
        val got = waitLit(d)
        assert(got == expected, f"kod $code: 0x$got%02x, oczekiwano 0x$expected%02x")
      }
    }

    scenario("seg_dot_independent") { d =>
      for (code <- Seq(0, 5, SevenSegMux.blank)) {
        poke(d, Seq.fill(3)(code), Seq.fill(3)(false))
        d.clockDomain.waitSampling()
        val without = waitLit(d)

        poke(d, Seq.fill(3)(code), Seq.fill(3)(true))
        d.clockDomain.waitSampling()
        val with_ = waitLit(d)

        assert((with_ ^ without) == 0x80,
               f"kod $code: bez kropki 0x$without%02x, z kropka 0x${with_}%02x")
      }
    }

    scenario("seg_active_low_idle") { d =>
      poke(d, Seq.fill(3)(SevenSegMux.blank), Seq.fill(3)(false))
      for (_ <- 0 until perDigit * 4) {
        d.clockDomain.waitSampling()
        assert(d.io.seg.toInt == 0xff, f"seg = 0x${d.io.seg.toInt}%02x, oczekiwano 0xff")
        assert(Integer.bitCount(d.io.en.toInt & 0x7) >= 2, "wiecej niz jeden enable nisko")
      }
    }

    scenario("seg_blanking_gap") { d =>
      poke(d, Seq(1, 2, 3), Seq(false, false, false))
      while (activeDigit(d) != 0) d.clockDomain.waitSampling()
      while (activeDigit(d) == 0) d.clockDomain.waitSampling()

      var gap = 0
      while (activeDigit(d) < 0) { gap += 1; d.clockDomain.waitSampling() }
      assert(gap == blank, s"przerwa $gap taktow, oczekiwano $blank")
    }
  }
}
