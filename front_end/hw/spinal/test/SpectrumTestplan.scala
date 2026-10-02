package newhope.frontend

import spinal.core._
import spinal.core.sim._
import spinal.lib.{math => _, _}   // wszystko z spinal.lib poza math
import scala.util.Random
import newhope.vertebra._
import newhope.vertebra.sim.{SimEnv,SimSeed}
import newhope.core.Conventions
import FftGolden._
import FftTestKit._
import newhope.vertebra.sim.StreamSim._

// Zrodlo: nazwy wlasne. Odrzucone: csr_*, tl_*, intr_*.
// Oba bloki sa czysto arytmetyczne, wiec cala weryfikacja to
// porownanie bit w bit plus wlasnosci liczbowe golden modelu.

object SpectrumPlan {
  case class Cfg(name: String, w: Int, e: Int)
  val configs = Seq(Cfg("w18", 18, 8), Cfg("w6", 6, 4))   // typowa i dolna granica

  def values(rng: Random, w: Int, k: Int): Seq[(Cx, Int)] = {
    val hi = (1L << (w - 1)) - 1; val lo = -(1L << (w - 1))
    val corner = for (a <- Seq(lo, -1L, 0L, 1L, hi); b <- Seq(lo, 0L, hi)) yield (Cx(a, b), 0)
    corner ++ uniform(rng, k, w).map(c => (c, rng.nextInt(9) - 4))
  }
}

object PowerSpectrumPlan {
  val plan: Seq[Testpoint] = Seq(
    Testpoint("power_param_bounds", Stage.V1, "Szerokosci wystarczaja dla rogu zakresu",
      checking = Seq("max p = 2^(2W-1) miesci sie w 2W bitach bez znaku", "2 * exp miesci sie w e+1 bitach")),
    Testpoint("power_exact", Stage.V1, "Moc bit w bit",
      stimulus = Seq("rogi zakresu (w tym (-2^(W-1), -2^(W-1))) i 500 losowych probek, last co 16"),
      checking = Seq("p, exp i last == golden"))
  ) ++ StreamConformance.testpoints("in") ++ StreamConformance.testpoints("out")
}

object MagnitudePlan {
  val plan: Seq[Testpoint] = Seq(
    Testpoint("mag_param_bounds", Stage.V1, "Szerokosci wystarczaja",
      checking = Seq("round(sqrt(2^(2W-1))) < 2^W")),
    Testpoint("mag_rounding", Stage.V1, "Golden zaokragla do najblizszego",
      stimulus = Seq("p = 0, 1, 2, r^2 - 1, r^2, r^2 + r, r^2 + r + 1, max, losowe"),
      checking = Seq("|m - sqrt(p)| <= 0,5")),
    Testpoint("mag_exact", Stage.V1, "Modul bit w bit",
      stimulus = Seq("moce z tych samych wartosci co power_exact"),
      checking = Seq("m, exp i last == golden"))
  ) ++ StreamConformance.testpoints("in") ++ StreamConformance.testpoints("out")
}

class PowerSpectrumTestplan extends TestplanSuite {
  import SpectrumPlan._
  override def testplan = PowerSpectrumPlan.plan

  for (Cfg(cfgName, w, e) <- configs) {
    lazy val dut = SimEnv(Conventions.spinal, s"power_$cfgName").compile(new PowerSpectrum(w, e))

    def run(d: PowerSpectrum, t: Traffic, rng: Random): Unit = {
      val cd = d.clockDomain
      val xs = values(rng, w, 500)
      backpressure(d.io.output, cd, rng, t.outReady)
      val got = monitor(d.io.output, cd)(p => (p.fragment.p.toLong, p.fragment.exp.toInt, p.last.toBoolean))
      drive(d.io.input, cd, rng, t.inGap)(xs.zipWithIndex.map { case ((c, ex), i) => (p: Fragment[BfpCplx]) => {
        p.fragment.re #= c.re; p.fragment.im #= c.im; p.fragment.exp #= ex; p.last #= (i % 16 == 15)
      }})
      val exp = xs.zipWithIndex.map { case ((c, ex), i) => val (pp, e2) = power(c, ex); (pp, e2, i % 16 == 15) }
      waitFor(cd, got.size >= exp.size, 100 * xs.size, s"${exp.size} probek")
      expectSeq(got, exp, s"PowerSpectrum $cfgName")
    }

    def scenario(tp: String)(body: (PowerSpectrum, Random) => Unit): Unit =
      testpoint(tp, variant = cfgName) {
        val name = s"${cfgName}_$tp"
        val seed = SimSeed(name)
        dut.doSim(name, seed = seed) { d =>
          val cd = d.clockDomain
          d.io.input.valid #= false; d.io.output.ready #= false
          cd.forkStimulus(period = 10)
          StreamConformance.all(cd, StreamPortHandle(d.io.input.valid, d.io.input.ready, d.io.input.payload, isInput = true, "in"))
          StreamConformance.all(cd, StreamPortHandle(d.io.output.valid, d.io.output.ready, d.io.output.payload, isInput = false, "out"))
          cd.waitSampling(5)
          body(d, new Random(seed))
        }
      }

    scenario("power_exact") { (d, rng) => run(d, smooth, rng) }
    for (port <- Seq("in", "out")) {
      scenario(s"${port}_payload_stable") { (d, rng) => run(d, jittery, rng) }
      scenario(s"${port}_backpressure")   { (d, rng) => run(d, choked, rng) }
      scenario(s"${port}_reset_quiet")    { (d, rng) => idleReset(d.clockDomain); run(d, jittery, rng) }
      unimplemented(s"${port}_stress_with_rand_reset", "brak scenariusza resetu w trakcie ruchu", variant = cfgName)
    }
  }

  testpoint("power_param_bounds") {
    for (Cfg(name, w, e) <- configs) {
      val (p, e2) = power(Cx(-(1L << (w - 1)), -(1L << (w - 1))), (1 << (e - 1)) - 1)
      assert(p < (1L << (2 * w)), s"$name: p = $p nie miesci sie w ${2 * w} bitach")
      assert(e2 < (1 << e), s"$name: 2 * exp = $e2 nie miesci sie w ${e + 1} bitach")
    }
  }
}

class MagnitudeTestplan extends TestplanSuite {
  import SpectrumPlan._
  override def testplan = MagnitudePlan.plan

  for (Cfg(cfgName, w, e) <- configs) {
    lazy val dut = SimEnv(Conventions.spinal, s"mag_$cfgName").compile(new Magnitude(w, e))

    def run(d: Magnitude, t: Traffic, rng: Random): Unit = {
      val cd = d.clockDomain
      val ps = values(rng, w, 200).map { case (c, ex) => power(c, ex) }
      backpressure(d.io.output, cd, rng, t.outReady)
      val got = monitor(d.io.output, cd)(p => (p.fragment.m.toLong, p.fragment.exp.toInt, p.last.toBoolean))
      drive(d.io.input, cd, rng, t.inGap)(ps.zipWithIndex.map { case ((pp, e2), i) => (p: Fragment[BfpPower]) => {
        p.fragment.p #= pp; p.fragment.exp #= e2; p.last #= (i % 16 == 15)
      }})
      val exp = ps.zipWithIndex.map { case ((pp, e2), i) => val (m, em) = magnitude(pp, e2); (m, em, i % 16 == 15) }
      waitFor(cd, got.size >= exp.size, (w + 10) * 20 * ps.size, s"${exp.size} probek")
      expectSeq(got, exp, s"Magnitude $cfgName")
    }

    def scenario(tp: String)(body: (Magnitude, Random) => Unit): Unit =
      testpoint(tp, variant = cfgName) {
        val name = s"${cfgName}_$tp"
        val seed = SimSeed(name)
        dut.doSim(name, seed = seed) { d =>
          val cd = d.clockDomain
          d.io.input.valid #= false; d.io.output.ready #= false
          cd.forkStimulus(period = 10)
          StreamConformance.all(cd, StreamPortHandle(d.io.input.valid, d.io.input.ready, d.io.input.payload, isInput = true, "in"))
          StreamConformance.all(cd, StreamPortHandle(d.io.output.valid, d.io.output.ready, d.io.output.payload, isInput = false, "out"))
          cd.waitSampling(5)
          body(d, new Random(seed))
        }
      }

    scenario("mag_exact") { (d, rng) => run(d, smooth, rng) }
    for (port <- Seq("in", "out")) {
      scenario(s"${port}_payload_stable") { (d, rng) => run(d, jittery, rng) }
      scenario(s"${port}_backpressure")   { (d, rng) => run(d, choked, rng) }
      scenario(s"${port}_reset_quiet")    { (d, rng) => idleReset(d.clockDomain); run(d, jittery, rng) }
      unimplemented(s"${port}_stress_with_rand_reset", "brak scenariusza resetu w trakcie obliczen", variant = cfgName)
    }
  }

  testpoint("mag_param_bounds") {
    for (Cfg(name, w, _) <- configs) {
      val (m, _) = magnitude(power(Cx(-(1L << (w - 1)), -(1L << (w - 1))), 0)._1, 0)
      assert(m < (1L << w), s"$name: modul $m nie miesci sie w $w bitach")
    }
  }

  testpoint("mag_rounding") {
    val rng = new Random(3)
    val maxP = 1L << 35
    val special = Seq(0L, 1L, 2L, 3L, maxP) ++ (1L to 2000L).flatMap(r => Seq(r * r - 1, r * r, r * r + r, r * r + r + 1))
    for (p <- special ++ Seq.fill(10000)((rng.nextDouble() * maxP).toLong)) {
      val (m, _) = magnitude(p, 0)
      assert(math.abs(m - math.sqrt(p.toDouble)) <= 0.5 + 1e-9, s"p = $p: m = $m, sqrt = ${math.sqrt(p.toDouble)}")
    }
  }
}
