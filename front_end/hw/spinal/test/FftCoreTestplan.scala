package newhope.frontend

import spinal.core._
import spinal.core.sim._
import spinal.lib.{math => _, _}   // wszystko z spinal.lib poza math
import scala.collection.mutable
import scala.util.Random
import newhope.vertebra._
import newhope.vertebra.sim.{SimEnv,SimSeed}
import newhope.core.Conventions
import FftGolden._
import FftTestKit._
import newhope.vertebra.sim.StreamSim._

// Zrodlo: brak publicznego testplanu FFT w OpenTitanie; nazwy wlasne,
// struktura wg TESTING-STRATEGY.md. Numeryka: czat "Implementacja FFT
// na FPGA z pipeliningiem" + D-005 (BFP z kryterium L1, normalizacja).
// Odrzucone jako nieaplikowalne: csr_*, tl_*, intr_* (brak rejestrow
// i przerwan - rdzen sterowany wylacznie strumieniami).
// Porty Stream: "in" (wejscie) i "out" (output albo pairs, zaleznie od
// outOrder); checkery czytaja payload wprost z io.

object FftCorePlan {
  val plan: Seq[Testpoint] = Seq(
    Testpoint("fft_param_bounds", Stage.V1,
      "Wszystkie konfiguracje legalne, zapas wykladnika, tablice twiddle poprawne",
      checking = Seq("FftGenerics.isLegal dla kazdej konfiguracji",
                     "expMargin >= 0 dla e0 w [-8, 8]",
                     "twiddle(0) = (scale, 0), |W| <= scale + 1")),
    Testpoint("fft_golden_vs_float", Stage.V1,
      "Golden model zgodny z DFT w double, bez nasycen, mantysa wykorzystana",
      stimulus = Seq("n = 4, 8, 256, 512; poziomy 0..-100 dBFS; szum, ton, rownomierny; fwd i inv"),
      checking = Seq("SNR >= 6,02(W-4) - 3 logN dB",
                     "zero nasycen",
                     "L1 max wyniku >= 2^(W-4) dla niezerowego wejscia")),
    Testpoint("fft_smoke", Stage.V1,
      "Losowe ramki pelnozakresowe, porownanie bit w bit z golden modelem",
      stimulus = Seq("6 ramek, skladowe rownomierne w pelnym zakresie W bitow, e0 w [-4, 4]"),
      checking = Seq("mantysy, wykladnik, nextShift i last == golden, w kolejnosci outOrder")),
    Testpoint("fft_inverse", Stage.V2,
      "Kierunek odwrotny (sprzezony twiddle), przelaczany per ramka",
      stimulus = Seq("6 ramek, inverse naprzemiennie"),
      checking = Seq("wynik == golden z inverse wzietym z pierwszej probki ramki")),
    Testpoint("fft_bfp_levels", Stage.V2,
      "BFP na calym zakresie poziomow wejscia",
      stimulus = Seq("ton i szum na 0, -6, -20, -40, -60, -80, -100 dBFS"),
      checking = Seq("bit w bit z golden, w tym wykladnik (normalizacja w lewo)")),
    Testpoint("fft_bfp_corners", Stage.V2,
      "Przypadki zlosliwe dla kryterium L1",
      stimulus = Seq("zero, 1 LSB, DC na rogach, przemiennie, impuls -2^(W-1), rotacja 45 st., losowe rogi"),
      checking = Seq("golden bez nasycen", "RTL == golden bit w bit")),
    Testpoint("fft_latency", Stage.V2,
      "Czas obliczen zgodny z harmonogramem",
      stimulus = Seq("jedna ramka bez przerw, ready = 1"),
      checking = Seq("od ostatniej probki wejscia do pierwszego valid wyjscia: computeLatency +- 4"))
  ) ++ StreamConformance.testpoints("in") ++ StreamConformance.testpoints("out")

  case class Cfg(name: String, g: FftGenerics)
  val configs = Seq(
    Cfg("n256_pairs", FftGenerics(8, outOrder = OutOrder.RealPairs)),  // Mimas: rfft 512
    Cfg("n256_nat",   FftGenerics(8, outOrder = OutOrder.Natural)),
    Cfg("n512_pairs", FftGenerics(9, outOrder = OutOrder.RealPairs)),  // rfft 1024: autokorelacja z dopelnieniem
    Cfg("n4_bitrev",  FftGenerics(2, outOrder = OutOrder.Bitrev)),     // dolna granica logN
    Cfg("n4_pairs",   FftGenerics(2, outOrder = OutOrder.RealPairs))
  )

  final case class Frame(z: Vector[Cx], exp0: Int, inverse: Boolean)
  final case class Obs(a: Cx, b: Option[Cx], exp: Int, nextShift: Int, last: Boolean)

  def expected(g: FftGenerics, f: Frame): Seq[Obs] = {
    val c = core(g, f.z, f.exp0, f.inverse)
    if (g.pairs) c.pairs.zipWithIndex.map { case ((a, b), k) => Obs(a, Some(b), c.exp, c.nextShift, k == g.n - 1) }
    else c.ordered.zipWithIndex.map { case (a, k) => Obs(a, None, c.exp, 0, k == g.n - 1) }
  }

  /** Prog SNR golden vs double: szum zaokraglen na poziomie LSB wobec
    * bloku znormalizowanego do >= 2^(W-3), ~3 dB na stopien.
    * Zmierzone minimum (tools/fft_golden.py): 91,5 / 87,4 / 75,5 / 70,8 dB
    * dla n = 4 / 8 / 256 / 512 przy W = 18; prog zostawia >= 10 dB zapasu. */
  def snrFloor(g: FftGenerics): Double = 6.02 * (g.dataWidth - 4) - 3.0 * g.logN
}

class FftCoreTestplan extends TestplanSuite {
  import FftCorePlan._
  override def testplan = plan

  for (Cfg(cfgName, g) <- configs) {
    lazy val dut = SimEnv(Conventions.spinal, s"fft_$cfgName").compile(new FftCore(g))

    def ports(d: FftCore) = Seq(
      StreamPortHandle(d.io.input.valid, d.io.input.ready, d.io.input.payload, isInput = true, "in"),
      if (g.pairs) StreamPortHandle(d.io.pairs.valid, d.io.pairs.ready, d.io.pairs.payload, isInput = false, "out")
      else         StreamPortHandle(d.io.output.valid, d.io.output.ready, d.io.output.payload, isInput = false, "out"))

    def run(d: FftCore, frames: Seq[Frame], t: Traffic, rng: Random): Unit = {
      val cd = d.clockDomain
      val got =
        if (g.pairs) {
          backpressure(d.io.pairs, cd, rng, t.outReady)
          monitor(d.io.pairs, cd) { p =>
            Obs(Cx(p.fragment.a.re.toLong, p.fragment.a.im.toLong),
                Some(Cx(p.fragment.b.re.toLong, p.fragment.b.im.toLong)),
                p.fragment.exp.toInt, p.fragment.nextShift.toInt, p.last.toBoolean)
          }
        } else {
          backpressure(d.io.output, cd, rng, t.outReady)
          monitor(d.io.output, cd) { p =>
            Obs(Cx(p.fragment.re.toLong, p.fragment.im.toLong), None,
                p.fragment.exp.toInt, 0, p.last.toBoolean)
          }
        }
      val items = frames.flatMap { f =>
        f.z.map { c => (p: Fragment[BfpCplx]) => {
          d.io.inverse     #= f.inverse
          p.fragment.re    #= c.re
          p.fragment.im    #= c.im
          p.fragment.exp   #= f.exp0
          p.last           #= false
        }}
      }
      drive(d.io.input, cd, rng, t.inGap)(items)
      val exp = frames.flatMap(expected(g, _))
      val limit = frames.size * (g.busyCycles * 10 + 1000)
      waitFor(cd, got.size >= exp.size, limit, s"${exp.size} elementow wyjscia")
      cd.waitSampling(20)
      expectSeq(got, exp, s"FftCore $cfgName")
    }

    def scenario(tp: String)(body: (FftCore, Random) => Unit): Unit =
      testpoint(tp, variant = cfgName) {
        val name = s"${cfgName}_$tp"
        val seed = SimSeed(name)
        dut.doSim(name, seed = seed) { d =>
          val cd = d.clockDomain
          d.io.input.valid #= false
          d.io.inverse     #= false
          if (g.pairs) d.io.pairs.ready #= false else d.io.output.ready #= false
          cd.forkStimulus(period = 10)
          ports(d).foreach(p => StreamConformance.all(cd, p, stallLimit = g.busyCycles * 12))
          cd.waitSampling(5)
          body(d, new Random(seed))
        }
      }

    def randomFrames(rng: Random, k: Int, inv: Int => Boolean) =
      (0 until k).map(i => Frame(uniform(rng, g.n, g.dataWidth), rng.nextInt(9) - 4, inv(i)))

    scenario("fft_smoke") { (d, rng) =>
      run(d, randomFrames(rng, 6, _ => false), smooth, rng)
    }

    scenario("fft_inverse") { (d, rng) =>
      run(d, randomFrames(rng, 6, _ % 2 == 1), smooth, rng)
    }

    scenario("fft_bfp_levels") { (d, rng) =>
      val frames = for (db <- Seq(0.0, -6, -20, -40, -60, -80, -100); tone <- Seq(true, false))
        yield Frame(atLevel(rng, g.n, g.dataWidth, db, tone), 0, inverse = false)
      val used = frames.map(f => core(g, f.z, 0, false)).map(c => s"${c.exp}/${c.l1max}")
      info(s"wykladnik/L1max per ramka: ${used.mkString(" ")}")
      run(d, frames, smooth, rng)
    }

    scenario("fft_bfp_corners") { (d, rng) =>
      val cs = corners(rng, g.n, g.dataWidth)
      for ((name, z) <- cs; inv <- Seq(false, true)) {
        val c = core(g, z, 0, inv)
        assert(c.sats == 0, s"golden nasyca sie w przypadku '$name' (inv = $inv): ${c.sats} razy")
      }
      run(d, cs.flatMap { case (_, z) => Seq(Frame(z, 0, false), Frame(z, 0, true)) }, smooth, rng)
    }

    scenario("fft_latency") { (d, rng) =>
      val cd = d.clockDomain
      var cyc = 0L; var fires = 0; var tIn = -1L; var tOut = -1L
      def outValid = if (g.pairs) d.io.pairs.valid.toBoolean else d.io.output.valid.toBoolean
      cd.onSamplings {
        cyc += 1
        if (d.io.input.valid.toBoolean && d.io.input.ready.toBoolean) {
          fires += 1
          if (fires == g.n) tIn = cyc
        }
        if (tIn >= 0 && tOut < 0 && outValid) tOut = cyc
      }
      run(d, randomFrames(rng, 1, _ => false), smooth, rng)
      // computeLatency = 1 (PREP pierwszego stopnia liczony od LOAD)
      //   + logN * (n + 6)  [PREP 1 + RUN n (II = 2) + DRAIN 5]
      //   + 2 (odczyt + FIFO) albo 3 (para)
      // Tolerancja 4: latencja StreamFifo zalezy od wersji SpinalHDL.
      val lat = tOut - tIn
      info(s"latencja zmierzona $lat, wzor ${g.computeLatency}")
      assert(math.abs(lat - g.computeLatency) <= 4, s"latencja $lat, oczekiwano ${g.computeLatency} +- 4")
    }

    // ---- StreamConformance: checkery dzialaja w kazdym scenariuszu, tu
    //      ruch, ktory je faktycznie cwiczy.
    for (port <- Seq("in", "out")) {
      scenario(s"${port}_payload_stable") { (d, rng) =>
        run(d, randomFrames(rng, 4, _ % 2 == 1), jittery, rng)
      }
      scenario(s"${port}_backpressure") { (d, rng) =>
        run(d, randomFrames(rng, 3, _ => false), choked, rng)
        run(d, randomFrames(rng, 3, _ => false), smooth, rng)   // ready na stale 1
      }
      scenario(s"${port}_reset_quiet") { (d, rng) =>
        idleReset(d.clockDomain)
        run(d, randomFrames(rng, 2, _ => false), jittery, rng)
      }
      unimplemented(s"${port}_stress_with_rand_reset",
        "brak scenariusza resetu w trakcie ramki (wymaga modelu nadajnika przerywalnego resetem)",
        variant = cfgName)
    }
  }

  // ---- bez symulacji ------------------------------------------------------
  testpoint("fft_param_bounds") {
    for (Cfg(name, g) <- configs) {
      assert(g.isLegal, s"$name: ${g.problems.mkString("; ")}")
      info(f"$name%-11s n=${g.n}%4d  shiftWidth=${g.shiftWidth}  zapas wykladnika=${g.expMargin(-8, 8)}  " +
           f"busy=${g.busyCycles}  latency=${g.computeLatency}")
      val tw = Bfp.twiddleTable(g.n, g.n / 2, g.twiddleScale)
      assert(tw.head == ((g.twiddleScale, 0L)), s"$name: twiddle(0) = ${tw.head}")
      val lim = (g.twiddleScale + 1) * (g.twiddleScale + 1)
      assert(tw.forall { case (c, s) => c * c + s * s <= lim }, s"$name: |W| > scale + 1")
    }
    // Rozmiar Mimasa w liczbach: 41,67 MHz, 100 ramek/s
    val mimas = FftGenerics.mimas512
    info(s"Mimas: rdzen ${mimas.core.busyCycles} cykli na ramke z ${mimas.clockHz / 100} " +
         f"(${100.0 * mimas.core.busyCycles / (mimas.clockHz / 100)}%.2f%%)")
  }

  testpoint("fft_golden_vs_float") {
    val rng = new Random(7)
    for (logN <- Seq(2, 3, 8, 9)) {
      val g = FftGenerics(logN)
      var worst = Double.PositiveInfinity
      for (db <- Seq(0.0, -20, -40, -60, -80, -100); kind <- 0 until 3; inv <- Seq(false, true)) {
        val z = kind match {
          case 0 => uniform(rng, g.n, g.dataWidth).map(c => Cx(math.round(c.re * math.pow(10, db / 20)), math.round(c.im * math.pow(10, db / 20))))
          case 1 => atLevel(rng, g.n, g.dataWidth, db, tone = false)
          case _ => atLevel(rng, g.n, g.dataWidth, db, tone = true)
        }
        if (z.exists(_.l1 > 0)) {
          val c   = core(g, z, 0, inv)
          assert(c.sats == 0, s"n=${g.n} $db dBFS: nasycenia ${c.sats}")
          assert(c.l1max >= (1L << (g.dataWidth - 4)), s"n=${g.n} $db dBFS: L1 max ${c.l1max} < 2^(W-4)")
          val ref = dft(z.map(x => (x.re.toDouble, x.im.toDouble)), inv)
          worst = math.min(worst, snrDb(scaled(c.natural, c.exp), ref))
        }
      }
      info(f"n=${g.n}%4d najgorszy SNR $worst%.1f dB, prog ${snrFloor(g)}%.1f dB")
      assert(worst >= snrFloor(g), s"n=${g.n}: SNR $worst < ${snrFloor(g)}")
    }
  }
}
