package newhope.frontend

import spinal.core._
import spinal.core.sim._
import scala.util.Random
import newhope.vertebra._
import newhope.vertebra.sim.{SimEnv,SimSeed}
import newhope.core.Conventions
import FrontEndTestKit._

// Zrodlo: nazwy wlasne (brak publicznego testplanu dla filtra IIR).
// Odrzucone: csr_*, tl_*, intr_* - brak rejestrow i przerwan.
// Porty to Flow (nie da sie wstrzymac mikrofonu), wiec StreamConformance
// nie ma zastosowania: payload_stable i backpressure wymagaja ready.
// Zamiast reset_quiet ze StreamConformance jest dc_reset.

object DcFilterPlan {
  val plan: Seq[Testpoint] = Seq(
    Testpoint("dc_param_bounds", Stage.V1,
      "Konfiguracje legalne, odciecie i akumulator z parametrow",
      checking = Seq("DcGenerics.isLegal",
                     "|fc_eff - fc| <= cutoffTol * fc",
                     "accBound < 2^(accWidth - 1)",
                     "|H(30 Hz)| = -3 dB +- 0,5; |H(60 Hz)| > -1,5 dB; |H(1 kHz)| w 0,1 dB")),
    Testpoint("dc_golden_vs_float", Stage.V1,
      "Golden zgodny z rekurencja w Double i usuwa DC bez cyklu granicznego",
      stimulus = Seq("losowe pelnozakresowe, ton 1 kHz z offsetem, prostokat +-FS co probke, skok -FS -> +FS",
                     "staly +FS przez 20 tau"),
      checking = Seq("max |golden - float| <= 1 LSB",
                     "po 15 tau |y| <= 1 LSB (brak cyklu granicznego)",
                     "|Y| <= accBound (asercja w golden)",
                     "wzmocnienie tonu 1 kHz w 0,1 dB")),
    Testpoint("dc_smoke", Stage.V1,
      "Losowe probki co minSpacing..20 cykli, porownanie bit w bit",
      stimulus = Seq("600 probek: szum + ton + offset"),
      checking = Seq("wyjscie == golden, ta sama liczba probek", "overrun == 0")),
    Testpoint("dc_reset", Stage.V1,
      "Reset w trakcie strumienia zeruje stan, valid cicho w resecie",
      stimulus = Seq("polowa strumienia, reset, druga polowa"),
      checking = Seq("valid == 0 przez caly reset",
                     "po resecie wyjscie == golden od stanu zerowego")),
    Testpoint("dc_removal", Stage.V2,
      "Offset mikrofonu znika, ton zostaje",
      stimulus = Seq("ton 200 Hz, -40 dBFS, na offsecie +0,1 FS; 20 tau"),
      checking = Seq("wyjscie == golden",
                     "srednia z ostatnich 1000 probek (pelne okresy) |.| < 1 LSB")),
    Testpoint("dc_corners", Stage.V2,
      "Rogi zakresu",
      stimulus = Seq("prostokat +-FS co probke", "skok -2^17 -> 2^17 - 1", "same zera"),
      checking = Seq("wyjscie == golden", "nasycenie zgodne z golden", "zera daja zera")),
    Testpoint("dc_min_spacing", Stage.V2,
      "Probki dokladnie co minSpacing cykli",
      stimulus = Seq("300 losowych probek co 2 cykle"),
      checking = Seq("wyjscie == golden", "overrun == 0")),
    Testpoint("dc_overrun", Stage.V2,
      "Probki szybciej niz minSpacing zapalaja overrun",
      stimulus = Seq("dwie probki w kolejnych cyklach"),
      checking = Seq("overrun == 1 i trzyma sie do resetu")),
    Testpoint("dc_latency", Stage.V2,
      "Latencja zgodna z DcGenerics.latency",
      stimulus = Seq("pojedyncze probki z dlugimi przerwami"),
      checking = Seq("liczba zboczy od przyjecia probki do valid == latency +- 1 (semantyka odczytu po waitSampling)", "stala dla kazdej probki")),
    Testpoint("dc_bypass", Stage.V2,
      "bypass przepuszcza wejscie, filtr liczy dalej",
      stimulus = Seq("strumien z bypass = 1 w srodkowej trzeciej czesci"),
      checking = Seq("przy bypass wyjscie == wejscie", "poza nim wyjscie == golden calego strumienia"))
  )

  case class Cfg(name: String, g: DcGenerics)
  val configs = Seq(
    Cfg("mimas",  DcGenerics()),                                     // 16 053 Hz, 2^-6 - 2^-8, G = 8
    Cfg("g6_w10", DcGenerics(sampleWidth = 10, guardBits = 6)),      // dolna granica G (blad 0,65 LSB)
    Cfg("t3_16k", DcGenerics(sampleRate = 16000, maxTerms = 3))      // 3 skladniki, fs nominalne
  )

  def fsMax(g: DcGenerics): Long = (1L << (g.sampleWidth - 1)) - 1
  def fsMin(g: DcGenerics): Long = -(1L << (g.sampleWidth - 1))

  def tone(g: DcGenerics, n: Int, f: Double, amp: Double, offset: Double): Seq[Long] =
    (0 until n).map { i =>
      val v = offset + amp * math.sin(2 * math.Pi * f * i / g.sampleRate)
      DcGolden.sat(math.round(v * fsMax(g)), g.sampleWidth)
    }

  def noisy(g: DcGenerics, rng: Random, n: Int): Seq[Long] =
    (0 until n).map { i =>
      val v = 0.05 + 0.3 * math.sin(2 * math.Pi * 440 * i / g.sampleRate) + 0.2 * rng.nextGaussian()
      DcGolden.sat(math.round(v * fsMax(g)), g.sampleWidth)
    }

  def square(g: DcGenerics, n: Int) = (0 until n).map(i => if (i % 2 == 1) fsMax(g) else fsMin(g))
  def step(g: DcGenerics, n: Int)   = (0 until n).map(i => if (i < 50) fsMin(g) else fsMax(g))
}

class DcFilterTestplan extends TestplanSuite {
  import DcFilterPlan._
  override def testplan = plan

  for (Cfg(cfgName, g) <- configs) {
    lazy val dut = SimEnv(Conventions.spinal, s"dcfilter_$cfgName").compile(new DcFilter(g))

    def scenario(tp: String)(body: (DcFilter, Random) => Unit) =
      testpoint(tp, variant = cfgName) {
        val name = s"dc_${cfgName}_$tp"
        val seed = SimSeed(name)
        dut.doSim(name, seed = seed) { d =>
          d.io.input.valid   #= false
          d.io.input.payload #= 0
          d.io.bypass        #= false
          d.clockDomain.forkStimulus(period = 10)
          guard()
          d.clockDomain.waitSampling(5)
          body(d, new Random(seed))
        }
      }

    /** Strumien przez DUT; zwraca wyjscie (tyle probek, ile weszlo). */
    def run(d: DcFilter, xs: Seq[Long], gap: Int => Int): Seq[Long] = {
      val cd  = d.clockDomain
      val mon = new FlowMonitor(d.io.output, cd)
      feed(d.io.input, cd, xs, gap).join()
      waitFor(cd, mon.values.size >= xs.size, g.latency + 10, s"${xs.size} probek")
      cd.waitSampling(g.latency + 2)
      assert(mon.values.size == xs.size, s"${mon.values.size} probek na wyjsciu, weszlo ${xs.size}")
      mon.active = false
      mon.values.toList
    }

    def check(got: Seq[Long], exp: Seq[Long], what: String): Unit = {
      val bad = got.indices.find(i => got(i) != exp(i))
      bad.foreach(i => fail(s"$what: probka $i: RTL ${got(i)}, golden ${exp(i)}"))
    }

    scenario("dc_smoke") { (d, rng) =>
      val xs  = noisy(g, rng, 600)
      val got = run(d, xs, _ => g.minSpacing + rng.nextInt(19))
      check(got, DcGolden.run(g, xs).y, "smoke")
      assert(!d.io.overrun.toBoolean, "overrun przy tempie >= minSpacing")
    }

    scenario("dc_reset") { (d, rng) =>
      val cd = d.clockDomain
      quietDuringReset(cd, d.io.output.valid, "out")
      val a = noisy(g, rng, 200)
      run(d, a, _ => 3)
      idleReset(cd)
      val b   = noisy(g, rng, 200)
      val got = run(d, b, _ => 3)
      check(got, DcGolden.run(g, b).y, "po resecie")
    }

    scenario("dc_removal") { (d, rng) =>
      val n   = (20 * g.tau).toInt + 1000
      val xs  = tone(g, n, 200, 0.01, 0.1)
      val got = run(d, xs, _ => g.minSpacing)
      check(got, DcGolden.run(g, xs).y, "removal")
      // 1000 probek przy 16 kHz to niemal calkowita liczba okresow 200 Hz;
      // reszta tonu w sredniej < 0,01 * FS / 1000 * okres ~ ulamek LSB
      val tail = got.takeRight(1000)
      val mean = tail.sum.toDouble / tail.size
      val toneResidue = 0.01 * fsMax(g) * (g.sampleRate / 200) / 1000 / math.Pi
      info(f"$cfgName: srednia ogona $mean%.3f LSB (reszta tonu do $toneResidue%.2f)")
      assert(math.abs(mean) < 1 + toneResidue, f"DC nie usuniety: srednia $mean%.3f LSB")
    }

    scenario("dc_corners") { (d, rng) =>
      for ((name, xs) <- Seq("square" -> square(g, 400), "step" -> step(g, 400), "zero" -> Seq.fill(100)(0L))) {
        val gold = DcGolden.run(g, xs)
        val got  = run(d, xs, _ => g.minSpacing)
        check(got, gold.y, name)
        info(s"$cfgName/$name: nasycen ${gold.saturated}, |Y|max = ${gold.accMax} / ${g.accBound}")
        idleReset(d.clockDomain)
      }
      assert(run(d, Seq.fill(50)(0L), _ => 2).forall(_ == 0), "zera nie daja zer")
    }

    scenario("dc_min_spacing") { (d, rng) =>
      val xs  = Seq.fill(300)(fsMin(g) + (rng.nextLong() & ((1L << g.sampleWidth) - 1)))
      val got = run(d, xs, _ => g.minSpacing)
      check(got, DcGolden.run(g, xs).y, "min spacing")
      assert(!d.io.overrun.toBoolean, "overrun przy minSpacing")
    }

    scenario("dc_overrun") { (d, rng) =>
      val cd = d.clockDomain
      feed(d.io.input, cd, Seq(100L, 200L, 300L), _ => 1).join()
      cd.waitSampling(g.latency + 2)
      assert(d.io.overrun.toBoolean, "overrun nie zapalony przy probkach w kolejnych cyklach")
      feed(d.io.input, cd, Seq(1L, 2L), _ => 20).join()
      cd.waitSampling(g.latency + 2)
      assert(d.io.overrun.toBoolean, "overrun zgasl bez resetu")
    }

    scenario("dc_latency") { (d, rng) =>
      val cd  = d.clockDomain
      // Sekwencyjnie, bez callbackow: probka na zboczu E0, potem liczba
      // zboczy do valid na wyjsciu. Wzor: A, B, C i rejestr wyjsciowy =
      // 4 cykle; +-1 na to, czy odczyt rejestru po waitSampling widzi
      // wartosc sprzed zbocza, czy po nim.
      val lat = for (x <- Seq(5L, -7L, fsMax(g) / 2, fsMin(g) / 2)) yield {   // w zakresie kazdej sampleWidth
        d.io.input.payload #= x
        d.io.input.valid   #= true
        cd.waitSampling()
        d.io.input.valid   #= false
        var n = 0
        while (!d.io.output.valid.toBoolean) {
          cd.waitSampling(); n += 1
          assert(n < 50, "brak probki na wyjsciu")
        }
        cd.waitSampling(20)
        n
      }
      info(s"$cfgName: latencja zmierzona $lat, wzor ${g.latency}")
      assert(lat.forall(l => math.abs(l - g.latency) <= 1), s"latencja $lat, oczekiwano ${g.latency} +- 1")
      assert(lat.distinct.size == 1, s"latencja niestala: $lat")
    }

    scenario("dc_bypass") { (d, rng) =>
      val cd  = d.clockDomain
      val xs  = noisy(g, rng, 600)
      val mon = new FlowMonitor(d.io.output, cd)
      // bypass zmieniany tylko w dlugich przerwach, gdy potok jest pusty
      val parts = xs.grouped(200).toList
      for ((p, i) <- parts.zipWithIndex) {
        d.io.bypass #= (i == 1)
        cd.waitSampling(2)
        feed(d.io.input, cd, p, _ => 3).join()
        cd.waitSampling(g.latency + 2)
      }
      d.io.bypass #= false
      val got  = mon.values.toList
      val gold = DcGolden.run(g, xs).y
      assert(got.size == xs.size, s"${got.size} probek z ${xs.size}")
      check(got.slice(200, 400), xs.slice(200, 400), "bypass")
      check(got.take(200) ++ got.drop(400), gold.take(200) ++ gold.drop(400), "poza bypass")
    }
  }

  // ---- bez symulacji ---------------------------------------------------
  testpoint("dc_param_bounds") {
    for (Cfg(name, g) <- configs) {
      assert(g.isLegal, s"$name: ${g.problems.mkString("; ")}")
      val terms = g.terms.map(t => (if (t.sign > 0) "+" else "-") + s"2^-${t.shift}").mkString(" ")
      info(f"$name: 1 - a = $terms = ${g.oneMinusA}%.8f (idealnie ${g.oneMinusAIdeal}%.8f), " +
           f"fc = ${g.cutoffEff}%.2f Hz, tau = ${g.tau}%.1f probek, akumulator ${g.accWidth} b, " +
           s"zapas ${(BigInt(1) << (g.accWidth - 1)) - g.accBound}")
      val h30 = DcGolden.gainDb(g, 30); val h60 = DcGolden.gainDb(g, 60); val h1k = DcGolden.gainDb(g, 1000)
      info(f"$name: |H| 30 Hz $h30%.2f dB, 60 Hz $h60%.2f dB, 80 Hz ${DcGolden.gainDb(g, 80)}%.2f dB, 1 kHz $h1k%.3f dB")
      assert(math.abs(h30 + 3) <= 0.5, f"$name: |H(30 Hz)| = $h30%.2f dB")
      assert(h60 > -1.5, f"$name: |H(60 Hz)| = $h60%.2f dB (dolna granica F0)")
      assert(math.abs(h1k) <= 0.1, f"$name: |H(1 kHz)| = $h1k%.3f dB")
    }
    // kontrakt: fs Mimasa z dzielnika I2S
    val m = FrontEndGenerics.mimas
    assert(m.isLegal, m.problems.mkString("; "))
    assert(m.dc.terms == Seq(DcTerm(1, 6), DcTerm(-1, 8)), s"Mimas: ${m.dc.terms}")
  }

  testpoint("dc_golden_vs_float") {
    for (Cfg(name, g) <- configs) {
      val rng  = new Random(7)
      val n    = 6000
      val sigs = Seq(
        "rand"   -> Seq.fill(n)(fsMin(g) + (rng.nextLong() & ((1L << g.sampleWidth) - 1))),
        "tone"   -> tone(g, n, 1000, 0.25, 0.125),
        "square" -> square(g, n),
        "step"   -> step(g, n))
      for ((sname, xs) <- sigs) {
        val gold = DcGolden.run(g, xs).y
        val flt  = DcGolden.runFloat(g, xs)
        val err  = gold.zip(flt).map { case (a, b) => math.abs(a - b) }.max
        info(f"$name/$sname: max |golden - float| = $err%.3f LSB")
        assert(err <= 1.0, f"$name/$sname: blad $err%.3f LSB")
      }
      // brak cyklu granicznego: staly +FS przez 20 tau
      val dcIn  = Seq.fill((20 * g.tau).toInt)(fsMax(g))
      val resid = DcGolden.run(g, dcIn).y.drop((15 * g.tau).toInt).map(math.abs).max
      info(s"$name: resztka DC po 15 tau = $resid LSB")
      assert(resid <= 1, s"$name: cykl graniczny, |y| = $resid LSB")
      // wzmocnienie 1 kHz z golden (RMS po stanie ustalonym)
      val t   = tone(g, n, 1000, 0.25, 0.0)
      val y   = DcGolden.run(g, t).y
      def rms(v: Seq[Long]) = math.sqrt(v.map(x => x.toDouble * x).sum / v.size)
      val gdb = 20 * math.log10(rms(y.drop(2000)) / rms(t.drop(2000)))
      info(f"$name: wzmocnienie 1 kHz $gdb%.3f dB (teoria ${DcGolden.gainDb(g, 1000)}%.3f)")
      assert(math.abs(gdb) <= 0.1, f"$name: wzmocnienie 1 kHz $gdb%.3f dB")
    }
  }
}
