package newhope.aht10

import spinal.core._
import spinal.core.sim._
import newhope.vertebra.{Stage, Testpoint, TestplanSuite}
import newhope.vertebra.sim.SimBackend

// =====================================================================
//  Testplan DisplayFormat.
//
//  Wejscie ma 13 bitow ze znakiem plus flage bledu, czyli 16384
//  przypadki - znowu tanio na przebieg wyczerpujacy. Model referencyjny
//  koduje regule formatu wprost i jest jedynym miejscem, gdzie ta
//  regula jest zapisana drugi raz. Jesli kiedys zmienisz zasade
//  gaszenia wiodacego zera, ten model ma sie zmienic razem z RTL-em -
//  i to jest zamierzone, bo wtedy zmiana wymaga swiadomej decyzji
//  w dwoch miejscach zamiast cichego przeoczenia w jednym.
//
//  Pozostale dwa testpointy dotycza czasu, ktorego przebieg
//  wyczerpujacy nie widzi, bo grzecznie czeka na kazdy wynik.
// =====================================================================
class DisplayFormatTestplan extends TestplanSuite {

  def testplan : Seq[Testpoint] = Seq(

    Testpoint("df_exhaustive", Stage.V1,
      "Kazda wartosc wejsciowa formatowana zgodnie z regula",
      stimulus = Seq("Wszystkie wartosci od -4096 do 4095",
                     "Kazda raz bez flagi bledu"),
      checking = Seq("Trzy kody cyfr zgodne z modelem referencyjnym",
                     "Kropka wylacznie w formacie z czescia ulamkowa",
                     "Zaokraglenie przy odrzucaniu ulamka, nie obciecie")),

    Testpoint("df_error_before_first_update", Stage.V1,
      "Przed pierwsza probka wyswietlacz pokazuje kreski",
      // Zera po resecie wygladaja jak zmierzone zero stopni. To nie jest
      // kosmetyka - to roznica miedzy "czujnik milczy" a "jest zimno".
      stimulus = Seq("Brak jakiejkolwiek probki po starcie"),
      checking = Seq("Trzy kody minus", "Zadna kropka nie swieci")),

    Testpoint("df_holds_between_updates", Stage.V1,
      "Miedzy probkami wyswietlacz trzyma ostatnia wartosc",
      stimulus = Seq("Jedna probka, potem 500 taktow bezczynnosci"),
      checking = Seq("Kody i kropki niezmienne przez caly czas",
                     "Zaden przejsciowy stan konwertera nie wycieka na wyjscie")))

  // -------------------------------------------------------------------
  lazy val dut : SimCompiled[DisplayFormat] = Config.sim
    .withFstWave
    .workspaceName(s"displayformat_${SimBackend.default.label}")
    .compile { DisplayFormat() }

  lazy val dutFast : SimCompiled[DisplayFormat] = Config.sim
    .workspaceName(s"displayformat_fast_${SimBackend.default.label}")
    .compile { DisplayFormat() }

  def scenario(name : String, fast : Boolean = false)
              (body : DisplayFormat => Unit) : Unit =
    testpoint(name) {
      (if (fast) dutFast else dut).doSim(s"displayformat_$name", seed = 42) { d =>
        SimTimeout(50000000)
        d.clockDomain.forkStimulus(10)
        d.io.update.valid #= false
        d.io.update.value #= 0
        d.io.update.error #= false
        d.clockDomain.waitSampling()
        body(d)
      }
    }

  // --- model referencyjny --------------------------------------------
  val minus = SevenSegMux.minus
  val blank = SevenSegMux.blank

  def reference(v : Int, err : Boolean) : (Seq[Int], Seq[Boolean]) = {
    val dashes = (Seq(minus, minus, minus), Seq(false, false, false))
    if (err) return dashes

    val neg = v < 0
    val mag = math.abs(v)
    val big = mag >= 1000
    val m   = if (neg || big) mag + 5 else mag
    val d   = (0 until 4).map(i => (m / math.pow(10, i).toInt) % 10)

    if (neg && d(3) != 0) dashes
    else if (neg) {
      if (d(2) == 0) (Seq(blank, minus, d(1)), Seq(false, false, false))
      else           (Seq(minus, d(2), d(1)),  Seq(false, false, false))
    } else if (big) (Seq(d(3), d(2), d(1)), Seq(false, false, false))
    else {
      val head = if (d(2) == 0) blank else d(2)
      (Seq(head, d(1), d(0)), Seq(false, true, false))
    }
  }

  def readOut(d : DisplayFormat) : (Seq[Int], Seq[Boolean]) =
    ((0 until 3).map(i => d.io.digits(i).code.toInt),
     (0 until 3).map(i => d.io.digits(i).dot.toBoolean))

  /** Jedna aktualizacja plus odczekanie na konwersje BCD. */
  def push(d : DisplayFormat, v : Int, err : Boolean = false) : Unit = {
    d.io.update.valid #= true
    d.io.update.value #= v
    d.io.update.error #= err
    d.clockDomain.waitSampling()
    d.io.update.valid #= false
    d.clockDomain.waitSampling(20)   // 13 taktow double dabble z zapasem
  }

  // --- scenariusze ---------------------------------------------------

  scenario("df_exhaustive", fast = true) { d =>
    for (v <- -4096 to 4095) {
      push(d, v)
      val got = readOut(d)
      val exp = reference(v, err = false)
      assert(got == exp, s"$v -> $got, oczekiwano $exp")
    }
    // flaga bledu ma przykrywac kazda wartosc
    for (v <- Seq(-4096, -1, 0, 250, 1000, 4095)) {
      push(d, v, err = true)
      assert(readOut(d) == reference(v, err = true), s"blad przy $v")
    }
  }

  scenario("df_error_before_first_update") { d =>
    d.clockDomain.waitSampling(50)
    val (codes, dots) = readOut(d)
    assert(codes == Seq(minus, minus, minus), s"kody po starcie: $codes")
    assert(!dots.contains(true), "kropka swieci przed pierwsza probka")
  }

  scenario("df_holds_between_updates") { d =>
    push(d, 250)
    val expected = readOut(d)
    for (_ <- 0 until 500) {
      d.clockDomain.waitSampling()
      assert(readOut(d) == expected, "wyswietlacz zmienil sie bez probki")
    }
  }
}
