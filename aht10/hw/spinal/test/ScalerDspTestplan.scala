package newhope.aht10

import spinal.core._
import spinal.core.sim._
import newhope.vertebra.{Stage, Testpoint, TestplanSuite}
import newhope.vertebra.sim.SimBackend

// =====================================================================
//  Testplan ScalerDsp.
//
//  DWIE NIEZALEZNE REFERENCJE, celowo.
//
//  ScalerDsp.reference to model bit-dokladny: te same stale, ta sama
//  arytmetyka calkowita. Lapie bledy implementacji (zla szerokosc, zle
//  przesuniecie, przekrecony znak) i nadaje sie do przebiegu
//  wyczerpujacego, bo jest tani.
//
//  Nie lapie natomiast ZLEJ STALEJ - jesli wpiszemy 2000 tam, gdzie
//  datasheet chce 3600, model i RTL zgodza sie idealnie. Dlatego jest
//  drugi testpoint, liczacy w zmiennoprzecinkowej wprost ze wzoru, i
//  trzeci, sprawdzajacy punkty znane spoza datasheetu (0 C to 32 F).
//  Ten ostatni lapie przypadek, w ktorym zle przepisalem sam wzor.
//
//  DWA SKOMPILOWANE DUT-Y. Przebieg wyczerpujacy to ~2.4 mln taktow;
//  z zapisem fali FST daloby to plik liczony w gigabajtach. dutFast
//  jest bez fali i sluzy wylacznie temu jednemu testpointowi.
// =====================================================================
class ScalerDspTestplan extends TestplanSuite {

  import ScalerDsp.{celsius, fahrenheit, humidity, Coef}

  val allModes = Seq(
    (ScalerMode.celsius,    celsius),
    (ScalerMode.fahrenheit, fahrenheit),
    (ScalerMode.humidity,   humidity))

  def testplan : Seq[Testpoint] = Seq(

    Testpoint("dsp_exhaustive_all_modes", Stage.V1,
      "Kazda wartosc wejsciowa w kazdym trybie zgodna z modelem bit-dokladnym",
      stimulus = Seq("Wszystkie 131072 rozroznialne wartosci A, po kolei",
                     "Powtorzone dla trybu C, F i RH"),
      checking = Seq("Wynik identyczny z ScalerDsp.reference",
                     "Zaden wynik nie wychodzi poza 13 bitow ze znakiem")),

    Testpoint("dsp_physical_accuracy", Stage.V1,
      "Wynik zgadza sie ze wzorem z datasheetu, nie tylko z modelem",
      // Model bit-dokladny uzywa TYCH SAMYCH stalych co RTL, wiec zla
      // stala przechodzi go bez mrugniecia. Tutaj liczymy w double
      // wprost ze wzoru S/2^20*200-50 i porownujemy z tolerancja.
      stimulus = Seq("Co 512. wartosc A w kazdym z trzech trybow"),
      checking = Seq("Roznica wzgledem wzoru nie przekracza 1 na skali dziesiatych",
                     "Blad nie ma znaku stalego - zaokraglenie jest symetryczne")),

    Testpoint("dsp_known_points", Stage.V1,
      "Punkty odniesienia spoza datasheetu",
      // Jesli zle przepisalem wzor z datasheetu, dwa poprzednie
      // testpointy tego nie zobacza. 0 C = 32 F to wiedza zewnetrzna.
      stimulus = Seq("Surowe wartosci odpowiadajace 0 C, 25 C, -50 C i pelnej skali"),
      checking = Seq("0 C daje 32.0 F",
                     "25 C daje 77.0 F",
                     "Pelna skala wilgotnosci daje 100.0 %")),

    Testpoint("dsp_low_bits_ignored", Stage.V1,
      "Trzy najmlodsze bity surowej wartosci nie wplywaja na wynik",
      // Wprost udokumentowany kompromis preShift. Testpoint istnieje po
      // to, zeby czyjas "poprawka" na >> 2 wywalila sie tutaj, a nie
      // dopiero na plytce przy 60 stopniach.
      stimulus = Seq("Pary wartosci a*8 oraz a*8+7 dla losowej probki"),
      checking = Seq("Oba warianty daja identyczny wynik")),

    Testpoint("dsp_latency_constant", Stage.V1,
      "Czas konwersji nie zalezy od danych ani od trybu",
      stimulus = Seq("Konwersje skrajnych i srodkowych wartosci we wszystkich trybach"),
      checking = Seq("Zmierzona latencja identyczna w kazdym przypadku")),

    Testpoint("dsp_backpressure", Stage.V1,
      "cmd.ready niskie przez cala konwersje",
      stimulus = Seq("Komenda, obserwacja ready az do rsp.valid"),
      checking = Seq("ready nie podnosi sie ani razu przed wynikiem",
                     "rsp.valid trwa jeden takt")),

    Testpoint("dsp_mode_switch", Stage.V2,
      "Zmiana trybu miedzy konwersjami nie zanieczyszcza wyniku",
      // Wejscia MAC-a sa zatrzasniete na czas konwersji wlasnie po to.
      // Gdyby ktos usunal zatrzask "bo i tak sie nie zmienia", potok
      // dostalby stale z dwoch roznych trybow naraz.
      stimulus = Seq("Ta sama wartosc surowa w trzech trybach, bez przerwy miedzy komendami",
                     "Tryb zmieniany na wejsciu w trakcie trwania poprzedniej konwersji"),
      checking = Seq("Kazdy wynik odpowiada trybowi z chwili przyjecia komendy")))

  // -------------------------------------------------------------------
  lazy val dut : SimCompiled[ScalerDsp] = Config.sim
    .withFstWave
    .workspaceName(s"scalerdsp_${SimBackend.default.label}")
    .compile { ScalerDsp() }

  /** Bez fali - wylacznie do przebiegu wyczerpujacego. */
  lazy val dutFast : SimCompiled[ScalerDsp] = Config.sim
    .workspaceName(s"scalerdsp_fast_${SimBackend.default.label}")
    .compile { ScalerDsp() }

  def scenario(name : String, fast : Boolean = false)
              (body : ScalerDsp => Unit) : Unit =
    testpoint(name) {
      (if (fast) dutFast else dut).doSim(s"scalerdsp_$name", seed = 42) { d =>
        SimTimeout(500000000)
        d.clockDomain.forkStimulus(10)
        d.io.cmd.valid #= false
        d.io.cmd.raw   #= 0
        d.io.cmd.mode  #= ScalerMode.celsius
        d.clockDomain.waitSampling()
        body(d)
      }
    }

  // --- pomocnicze ----------------------------------------------------
  def send(d : ScalerDsp, raw : Int, mode : ScalerMode.E) : Unit = {
    d.io.cmd.valid #= true
    d.io.cmd.raw   #= raw
    d.io.cmd.mode  #= mode
    d.clockDomain.waitSamplingWhere(d.io.cmd.ready.toBoolean)
    d.io.cmd.valid #= false
    // Handshake widziany PRZED zboczem - skutek dopiero przy nastepnym
    // probkowaniu. Ta sama pulapka co w BinToBcdTestplan.
  }

  def convert(d : ScalerDsp, raw : Int, mode : ScalerMode.E) : Int = {
    send(d, raw, mode)
    d.clockDomain.waitSamplingWhere(d.io.rsp.valid.toBoolean)
    d.io.rsp.payload.toInt
  }

  /** Wzor z datasheetu, liczony niezaleznie od stalych w RTL. */
  def physical(raw : Int, coef : Coef) : Double = coef.label match {
    case "C"  => raw.toDouble / (1 << 20) * 200.0 - 50.0
    case "F"  => (raw.toDouble / (1 << 20) * 200.0 - 50.0) * 9.0 / 5.0 + 32.0
    case "RH" => raw.toDouble / (1 << 20) * 100.0
  }

  // --- scenariusze ---------------------------------------------------

  scenario("dsp_exhaustive_all_modes", fast = true) { d =>
    for ((mode, coef) <- allModes) {
      for (a <- 0 until (1 << 17)) {
        val raw = a << ScalerDsp.preShift
        val got = convert(d, raw, mode)
        val exp = ScalerDsp.reference(raw, coef)
        assert(got == exp, s"${coef.label} raw=$raw: $got, oczekiwano $exp")
      }
    }
  }

  scenario("dsp_physical_accuracy") { d =>
    for ((mode, coef) <- allModes) {
      var positive = 0
      var negative = 0
      for (a <- 0 until (1 << 17) by 512) {
        val raw  = a << ScalerDsp.preShift
        val got  = convert(d, raw, mode)
        val want = physical(raw, coef) * 10.0
        val err  = got - want
        assert(math.abs(err) <= 1.0,
               f"${coef.label} raw=$raw: $got, wzor daje $want%.2f")
        if (err > 0.0) positive += 1
        if (err < 0.0) negative += 1
      }
      // Zaokraglenie symetryczne: blad nie moze lezec caly po jednej
      // stronie. Brak czlonu (1 << shift-1) daje tu 100% ujemnych.
      assert(positive > 0 && negative > 0,
             s"${coef.label}: blad jednostronny (+$positive / -$negative)")
    }
  }

  scenario("dsp_known_points") { d =>
    val zeroC  = 1 << 18            // S/2^20*200-50 == 0
    val p25C   = 393216             // 0.375 pelnej skali
    val fullRh = (1 << 20) - 1

    assert(convert(d, zeroC, ScalerMode.celsius)    ==   0, "0 C")
    assert(convert(d, zeroC, ScalerMode.fahrenheit) == 320, "0 C to 32.0 F")
    assert(convert(d, p25C,  ScalerMode.celsius)    == 250, "25.0 C")
    assert(convert(d, p25C,  ScalerMode.fahrenheit) == 770, "25 C to 77.0 F")
    assert(convert(d, 0,     ScalerMode.celsius)    == -500, "-50.0 C")
    assert(convert(d, 0,     ScalerMode.fahrenheit) == -580, "-58.0 F")
    assert(convert(d, fullRh, ScalerMode.humidity)  == 1000, "100.0 %RH")
  }

  scenario("dsp_low_bits_ignored") { d =>
    val rng = new scala.util.Random(1)
    for (_ <- 0 until 64) {
      val a    = rng.nextInt(1 << 17)
      val mode = allModes(rng.nextInt(3))._1
      val lo   = convert(d, a << ScalerDsp.preShift, mode)
      val hi   = convert(d, (a << ScalerDsp.preShift) | 7, mode)
      assert(lo == hi, s"a=$a: $lo vs $hi - mlodsze bity przeciekaja")
    }
  }

  scenario("dsp_latency_constant") { d =>
    val probes = for {
      (mode, _) <- allModes
      raw       <- Seq(0, 1 << 18, 393216, (1 << 20) - 1)
    } yield (raw, mode)

    val lat = probes.map { case (raw, mode) =>
      send(d, raw, mode)
      var n = 0
      while (!d.io.rsp.valid.toBoolean) { d.clockDomain.waitSampling(); n += 1 }
      d.clockDomain.waitSampling()
      n
    }
    assert(lat.distinct.size == 1, s"latencja zmienna: $lat")
  }

  scenario("dsp_backpressure") { d =>
    send(d, 393216, ScalerMode.celsius)
    d.clockDomain.waitSampling()   // skutek handshake'u widac dopiero teraz

    var guard = 16
    while (!d.io.rsp.valid.toBoolean && guard > 0) {
      assert(!d.io.cmd.ready.toBoolean, "ready wysokie w trakcie konwersji")
      d.clockDomain.waitSampling()
      guard -= 1
    }
    assert(guard > 0, "wynik nie pojawil sie w oknie 16 taktow")

    d.clockDomain.waitSampling()
    for (_ <- 0 until 20) {
      assert(!d.io.rsp.valid.toBoolean, "valid dluzszy niz jeden takt")
      d.clockDomain.waitSampling()
    }
  }

  scenario("dsp_mode_switch") { d =>
    val raw = 393216
    // Tryb zmieniany na wejsciu juz w trakcie poprzedniej konwersji.
    for ((mode, coef) <- allModes) {
      send(d, raw, mode)
      d.io.cmd.mode #= allModes((allModes.indexWhere(_._1 == mode) + 1) % 3)._1
      d.clockDomain.waitSamplingWhere(d.io.rsp.valid.toBoolean)
      val got = d.io.rsp.payload.toInt
      assert(got == ScalerDsp.reference(raw, coef),
             s"${coef.label}: $got - tryb przeciekl z nastepnej komendy")
    }
  }
}
