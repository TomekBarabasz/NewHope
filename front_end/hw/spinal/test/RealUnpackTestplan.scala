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

// Zrodlo: nazwy wlasne (brak publicznego testplanu). Odrzucone: csr_*,
// tl_*, intr_* - brak rejestrow.
// Bodzce budowane wprost z wektora Z, niezaleznie od FftCore:
// rozplatanie jest poprawne dla dowolnego Z, nie tylko wyniku FFT.

object RealUnpackPlan {
  val plan: Seq[Testpoint] = Seq(
    Testpoint("unpack_param_bounds", Stage.V1,
      "Tablica W_N^k dla k = 0..M, konfiguracje legalne",
      checking = Seq("W^0 = (scale, 0), W^M = (-scale, 0), M + 1 pozycji")),
    Testpoint("unpack_vs_float", Stage.V1,
      "Tor golden (FftCore + RealUnpack) zgodny z DFT sygnalu rzeczywistego",
      stimulus = Seq("sygnal rzeczywisty 2M probek, poziomy 0..-80 dBFS, szum i ton"),
      checking = Seq("SNR prazkow 0..M >= snrFloor(M) - 3 dB", "zero nasycen")),
    Testpoint("unpack_smoke", Stage.V1,
      "Losowe Z pelnozakresowe, porownanie bit w bit",
      stimulus = Seq("6 blokow Z rownomiernych, exp w [-4, 4]"),
      checking = Seq("X[k], k = 0..M, wykladnik i last == golden")),
    Testpoint("unpack_dc_nyquist", Stage.V2,
      "Prazki DC i Nyquista sa rzeczywiste",
      stimulus = Seq("losowe Z"),
      checking = Seq("Im X[0] == 0 i Im X[M] == 0 na wyjsciu RTL")),
    Testpoint("unpack_corners", Stage.V2,
      "Przypadki zlosliwe kryterium L1 po stronie rozplatania",
      stimulus = Seq("te same rogi co fft_bfp_corners, jako Z"),
      checking = Seq("golden bez nasycen", "RTL == golden"))
  ) ++ StreamConformance.testpoints("in") ++ StreamConformance.testpoints("out")

  case class Cfg(name: String, g: FftGenerics)
  val configs = Seq(
    Cfg("m256", FftGenerics(8, outOrder = OutOrder.RealPairs)),   // Mimas: rfft 512
    Cfg("m4",   FftGenerics(2, outOrder = OutOrder.RealPairs))    // dolna granica
  )

  final case class Block(z: Vector[Cx], exp: Int) {
    def nextShift(w: Int) = Bfp.shiftFor(z.map(_.l1).max, w)
  }
}

class RealUnpackTestplan extends TestplanSuite {
  import RealUnpackPlan._
  override def testplan = plan

  for (Cfg(cfgName, g) <- configs) {
    val M = g.n
    lazy val dut = SimEnv(Conventions.spinal, s"unpack_$cfgName").compile(new RealUnpack(g))

    def run(d: RealUnpack, blocks: Seq[Block], t: Traffic, rng: Random): Seq[(Cx, Int, Boolean)] = {
      val cd = d.clockDomain
      backpressure(d.io.output, cd, rng, t.outReady)
      val got = monitor(d.io.output, cd) { p =>
        (Cx(p.fragment.re.toLong, p.fragment.im.toLong), p.fragment.exp.toInt, p.last.toBoolean)
      }
      val items = blocks.flatMap { b =>
        val ns = b.nextShift(g.dataWidth)
        (0 until M).map { k => (p: Fragment[BfpPair]) => {
          val a = b.z(k); val bb = b.z((M - k) % M)
          p.fragment.a.re #= a.re;  p.fragment.a.im #= a.im
          p.fragment.b.re #= bb.re; p.fragment.b.im #= bb.im
          p.fragment.exp  #= b.exp
          p.fragment.nextShift #= ns
          p.last #= (k == M - 1)
        }}
      }
      drive(d.io.input, cd, rng, t.inGap)(items)
      val exp = blocks.flatMap { b =>
        val u = unpack(g, b.z, b.exp, b.nextShift(g.dataWidth))
        u.x.zipWithIndex.map { case (x, k) => (x, u.exp, k == M) }
      }
      waitFor(cd, got.size >= exp.size, blocks.size * (M + 1) * 50 + 1000, s"${exp.size} prazkow")
      cd.waitSampling(10)
      expectSeq(got, exp, s"RealUnpack $cfgName")
      got.toSeq
    }

    def scenario(tp: String)(body: (RealUnpack, Random) => Unit): Unit =
      testpoint(tp, variant = cfgName) {
        val name = s"${cfgName}_$tp"
        val seed = SimSeed(name)
        dut.doSim(name, seed = seed) { d =>
          val cd = d.clockDomain
          d.io.input.valid #= false
          d.io.output.ready #= false
          cd.forkStimulus(period = 10)
          StreamConformance.all(cd, StreamPortHandle(d.io.input.valid, d.io.input.ready, d.io.input.payload, isInput = true, "in"))
          StreamConformance.all(cd, StreamPortHandle(d.io.output.valid, d.io.output.ready, d.io.output.payload, isInput = false, "out"))
          cd.waitSampling(5)
          body(d, new Random(seed))
        }
      }

    def randomBlocks(rng: Random, k: Int) =
      (0 until k).map(_ => Block(uniform(rng, M, g.dataWidth), rng.nextInt(9) - 4))

    scenario("unpack_smoke") { (d, rng) => run(d, randomBlocks(rng, 6), smooth, rng) }

    scenario("unpack_dc_nyquist") { (d, rng) =>
      val got = run(d, randomBlocks(rng, 4), smooth, rng)
      for ((x, i) <- got.zipWithIndex if i % (M + 1) == 0 || i % (M + 1) == M)
        assert(x._1.im == 0, s"prazek ${i % (M + 1)} bloku ${i / (M + 1)}: Im = ${x._1.im}")
    }

    scenario("unpack_corners") { (d, rng) =>
      val bs = corners(rng, M, g.dataWidth).map { case (name, z) =>
        val b = Block(z, 0)
        val u = unpack(g, z, 0, b.nextShift(g.dataWidth))
        assert(u.sats == 0, s"golden nasyca sie w '$name': ${u.sats}")
        b
      }
      run(d, bs, smooth, rng)
    }

    for (port <- Seq("in", "out")) {
      scenario(s"${port}_payload_stable") { (d, rng) => run(d, randomBlocks(rng, 4), jittery, rng) }
      scenario(s"${port}_backpressure") { (d, rng) =>
        run(d, randomBlocks(rng, 3), choked, rng)
        run(d, randomBlocks(rng, 3), smooth, rng)
      }
      scenario(s"${port}_reset_quiet") { (d, rng) =>
        idleReset(d.clockDomain)
        run(d, randomBlocks(rng, 2), jittery, rng)
      }
      unimplemented(s"${port}_stress_with_rand_reset",
        "brak scenariusza resetu w trakcie bloku; po resecie licznik k wraca do 0, blok trzeba powtorzyc od poczatku",
        variant = cfgName)
    }
  }

  // ---- bez symulacji ------------------------------------------------------
  testpoint("unpack_param_bounds") {
    for (Cfg(name, g) <- configs) {
      assert(g.isLegal, s"$name: ${g.problems.mkString("; ")}")
      val tw = Bfp.twiddleTable(2 * g.n, g.n + 1, g.twiddleScale)
      assert(tw.size == g.n + 1)
      assert(tw.head == ((g.twiddleScale, 0L)), s"$name: W^0 = ${tw.head}")
      assert(tw.last == ((-g.twiddleScale, 0L)), s"$name: W^M = ${tw.last}")
      info(s"$name: M = ${g.n}, ROM ${tw.size} x ${2 * g.twiddleWidth} b")
    }
  }

  testpoint("unpack_vs_float") {
    val rng = new Random(11)
    for (logM <- Seq(2, 8)) {
      val g = FftGenerics(logM, outOrder = OutOrder.RealPairs)
      val M = g.n; val N = 2 * M
      var worst = Double.PositiveInfinity
      for (db <- Seq(0.0, -20, -40, -60, -80); tone <- Seq(true, false)) {
        val c = atLevel(rng, N, g.dataWidth, db, tone)
        val x = c.map(_.re)                               // sygnal rzeczywisty N probek
        if (x.exists(_ != 0)) {
          val z  = Vector.tabulate(M)(m => Cx(x(2 * m), x(2 * m + 1)))
          val co = core(g, z, 0, inverse = false)
          val u  = unpack(g, co.natural, co.exp, co.nextShift)
          assert(co.sats == 0 && u.sats == 0, s"M=$M $db dBFS: nasycenia")
          val ref = dft(x.map(v => (v.toDouble, 0.0)), inverse = false).take(M + 1)
          worst = math.min(worst, snrDb(scaled(u.x, u.exp), ref))
        }
      }
      val floor = FftCorePlan.snrFloor(g) - 3
      info(f"M=$M%4d najgorszy SNR $worst%.1f dB, prog $floor%.1f dB")
      assert(worst >= floor, s"M=$M: SNR $worst < $floor")
    }
  }
}
