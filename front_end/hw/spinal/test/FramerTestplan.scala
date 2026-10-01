package newhope.frontend

import spinal.core._
import spinal.core.sim._
import spinal.sim.SimThread
import scala.util.Random
import newhope.vertebra._
import newhope.vertebra.sim.{SimEnv,SimSeed}
import newhope.core.Conventions
import FftGolden._
import FftTestKit._

// Zrodlo: nazwy wlasne. Odrzucone: csr_*, tl_*, intr_*.
// Wejscie jest Flow (probek z mikrofonu nie da sie wstrzymac), wiec
// testpointy StreamConformance dotycza tylko portu "out".

object FramerPlan {
  val plan: Seq[Testpoint] = Seq(
    Testpoint("framer_param_bounds", Stage.V1,
      "Konfiguracje legalne, okno = periodyczny Hann, tempo symulacji nie przepelnia bufora",
      checking = Seq("FramerGenerics.isLegal",
                     "w[0] = 0, w[L/2] = scale, |w[i] - wzor| <= 0,5 LSB",
                     "hop * cyklePerProbke > L + 8 (odczyt ramki przy ready = 1)")),
    Testpoint("framer_smoke", Stage.V1,
      "Ramki co hop probek, okno, pakowanie par",
      stimulus = Seq("L + 3 hop probek rownomiernych, potem ton"),
      checking = Seq("kazda ramka == golden (najstarsza probka pierwsza, zera przed startem)",
                     "last na ostatniej probce ramki", "overrun == 0")),
    Testpoint("framer_overrun", Stage.V2,
      "Zatkane wyjscie zapala overrun",
      stimulus = Seq("ready = 0 na stale, 3 hopy probek"),
      checking = Seq("overrun == 1 po nadpisaniu nieodczytanej probki"))
  ) ++ StreamConformance.testpoints("out")

  /** cps: cykle zegara na probke w symulacji. Dolna granica: odczyt ramki
    * (L cykli + ~8 na potok i FIFO) musi sie zmiescic w hop * cps przy
    * ready = 1; przy ruchu `jittery` (ready 0,6) potrzeba ~1,7x wiecej,
    * dlatego scenariusze z backpressure uzywaja 2 * cps. */
  case class Cfg(name: String, g: FramerGenerics, cps: Int)
  val configs = Seq(
    Cfg("l512_h160", FramerGenerics(),          cps = 4),   // kontrakt: 512 / 160
    Cfg("l16_h6",    FramerGenerics(16, 6),     cps = 5),
    Cfg("l8_h2",     FramerGenerics(8, 2),      cps = 9)    // dolna granica hop i L
  )
  def readoutCycles(g: FramerGenerics) = g.fftSize + 8
}

class FramerTestplan extends TestplanSuite {
  import FramerPlan._
  override def testplan = plan

  for (Cfg(cfgName, g, cps) <- configs) {
    val L = g.fftSize
    lazy val dut = SimEnv(Conventions.spinal, s"framer_$cfgName").compile(new Framer(g))

    def feed(d: Framer, samples: Seq[Long], cyclesPerSample: Int): SimThread = fork {
      val cd = d.clockDomain
      for (x <- samples) {
        d.io.input.valid   #= true
        d.io.input.payload #= x
        cd.waitSampling()
        d.io.input.valid   #= false
        cd.waitSampling(cyclesPerSample - 1)
      }
    }

    def run(d: Framer, samples: Seq[Long], t: Traffic, cyclesPerSample: Int, rng: Random): Unit = {
      val cd = d.clockDomain
      backpressure(d.io.output, cd, rng, t.outReady)
      val got = monitor(d.io.output, cd) { p =>
        (Cx(p.fragment.re.toLong, p.fragment.im.toLong), p.fragment.exp.toInt, p.last.toBoolean)
      }
      val exp = framer(g, samples).flatMap { f =>
        f.zipWithIndex.map { case (c, i) => (c, 0, i == L / 2 - 1) }
      }
      feed(d, samples, cyclesPerSample).join()
      waitFor(cd, got.size >= exp.size, 20 * L + 1000, s"${exp.size} probek wyjscia")
      cd.waitSampling(10)
      expectSeq(got, exp, s"Framer $cfgName")
      assert(!d.io.overrun.toBoolean, "overrun przy tempie w granicach")
    }

    def signal(rng: Random, n: Int): Seq[Long] = {
      val hi = (1 << (g.sampleWidth - 1)) - 1
      Seq.fill(n / 2)((rng.nextInt(2 * hi + 2) - hi - 1).toLong) ++
        (0 until n - n / 2).map(i => math.round(hi * 0.7 * math.sin(2 * math.Pi * 440 * i / 16000)))
    }

    def scenario(tp: String)(body: (Framer, Random) => Unit): Unit =
      testpoint(tp, variant = cfgName) {
        val name = s"${cfgName}_$tp"
        val seed = SimSeed(name)
        dut.doSim(name, seed = seed) { d =>
          val cd = d.clockDomain
          d.io.input.valid #= false
          d.io.input.payload #= 0
          d.io.output.ready #= false
          cd.forkStimulus(period = 10)
          StreamConformance.all(cd, StreamPortHandle(d.io.output.valid, d.io.output.ready,
            d.io.output.payload, isInput = false, "out"))
          cd.waitSampling(5)
          body(d, new Random(seed))
        }
      }

    val nSamples = L + 3 * g.hop

    scenario("framer_smoke") { (d, rng) => run(d, signal(rng, nSamples), smooth, cps, rng) }

    scenario("framer_overrun") { (d, rng) =>
      d.io.output.ready #= false
      feed(d, signal(rng, 3 * g.hop), cps).join()
      d.clockDomain.waitSampling(5)
      assert(d.io.overrun.toBoolean, "overrun nie zapalil sie przy zatkanym wyjsciu")
    }

    scenario("out_payload_stable") { (d, rng) => run(d, signal(rng, nSamples), jittery, 2 * cps, rng) }
    scenario("out_backpressure")   { (d, rng) =>
      // choked (ready 0,15) wymaga ~7x dluzszego odczytu
      run(d, signal(rng, nSamples), choked, 8 * cps, rng)
    }
    scenario("out_reset_quiet") { (d, rng) =>
      idleReset(d.clockDomain)
      run(d, signal(rng, nSamples), smooth, cps, rng)
    }
    unimplemented("out_stress_with_rand_reset",
      "brak scenariusza resetu w trakcie odczytu ramki; zawartosc bufora przezywa reset, liczniki nie",
      variant = cfgName)
  }

  testpoint("framer_param_bounds") {
    for (Cfg(name, g, cps) <- configs) {
      assert(g.isLegal, s"$name: ${g.problems.mkString("; ")}")
      val L = g.fftSize
      val t = Bfp.hannTable(L, g.windowScale)
      assert(t.head == 0 && t.last == g.windowScale, s"$name: w[0] = ${t.head}, w[L/2] = ${t.last}")
      val err = t.indices.map(i => math.abs(t(i) - (0.5 - 0.5 * math.cos(2 * math.Pi * i / L)) * g.windowScale)).max
      assert(err <= 0.5, s"$name: blad okna $err LSB")
      val margin = g.hop * cps - readoutCycles(g)
      info(s"$name: zapas tempa $margin cykli (hop * cps = ${g.hop * cps}, odczyt ${readoutCycles(g)})")
      assert(margin > 0, s"$name: cps = $cps za male")
    }
  }
}
