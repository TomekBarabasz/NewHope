package newhope.frontend

import spinal.core._
import spinal.core.sim._
import scala.util.Random
import newhope.vertebra._
import newhope.vertebra.sim.{SimEnv,SimSeed}
import newhope.core.Conventions
import FftGolden._
import FftTestKit._

// Zrodlo: nazwy wlasne. Odrzucone: csr_*, tl_*, intr_*.
// Test integracyjny: skladowe maja wlasne testplany, tu sprawdzamy
// polaczenie, budzet czasu i numeryke calego toru. Wejscie to Flow,
// wiec StreamConformance tylko dla "out".

object RfftPlan {
  val plan: Seq[Testpoint] = Seq(
    Testpoint("rfft_param_bounds", Stage.V1,
      "Budzet czasu toru na Mimasie i iCE40",
      checking = Seq("marginCycles > 0 dla mimas512 i ice40512",
                     "marginCycles > 0 dla konfiguracji symulacyjnych")),
    Testpoint("rfft_vs_float", Stage.V2,
      "Golden toru zgodny z rfft w double okienkowanej ramki",
      stimulus = Seq("sygnal mowopodobny (F0 + harmoniczne + szum) na 0..-80 dBFS"),
      checking = Seq("SNR prazkow >= snrFloor(n) - 3 dB", "zero nasycen")),
    Testpoint("rfft_smoke", Stage.V1,
      "Tor Framer -> FftCore -> RealUnpack bit w bit",
      stimulus = Seq("fftSize + 4 hop probek: szum, potem ton; tempo = cps konfiguracji"),
      checking = Seq("wszystkie prazki wszystkich ramek == golden", "overrun == 0"))
  ) ++ StreamConformance.testpoints("out")

  /** Konfiguracje symulacyjne. cps (cykle na probke) jest najmniejszy
    * legalny wg marginCycles + 3 probki zapasu - to jest dolna granica
    * tempa, przy ktorej tor jeszcze nadaza. Do kalibracji przebiegiem
    * cps w dol, jesli wzor okaze sie zbyt ostrozny. */
  case class Cfg(name: String, g: RfftGenerics)
  def atMinCps(f: FramerGenerics): RfftGenerics = {
    val probe = RfftGenerics(f, clockHz = 16000L, sampleRate = 16000)
    val cps   = (probe.frameBusyCycles + f.hop - 1) / f.hop + 3
    RfftGenerics(f, clockHz = cps.toLong * 16000, sampleRate = 16000)
  }
  val configs = Seq(
    Cfg("r512_h160", atMinCps(FramerGenerics())),       // kontrakt, cps ~ 23 zamiast 2604
    Cfg("r16_h6",    atMinCps(FramerGenerics(16, 6)))   // dolna granica
  )

  def speechLike(rng: Random, n: Int, w: Int, db: Double): Seq[Long] = {
    val f0  = 100 + rng.nextInt(150)
    val ph  = Seq.fill(40)(rng.nextDouble() * 2 * math.Pi)
    val raw = (0 until n).map { i =>
      val t = i / 16000.0
      (1 until 40).filter(_ * f0 < 7600).map(k => math.sin(2 * math.Pi * f0 * k * t + ph(k)) / k).sum +
        0.05 * rng.nextGaussian()
    }
    val pk = raw.map(math.abs).max
    raw.map(v => math.round(v / pk * ((1L << (w - 1)) - 1) * math.pow(10, db / 20)))
  }
}

class RfftTestplan extends TestplanSuite {
  import RfftPlan._
  override def testplan = plan

  for (Cfg(cfgName, g) <- configs) {
    val L    = g.framer.fftSize
    val cps  = g.cyclesPerSample.toInt
    val bins = g.bins
    lazy val dut = SimEnv(Conventions.spinal, s"rfft_$cfgName").compile(new Rfft(g))

    def run(d: Rfft, samples: Seq[Long], t: Traffic, rng: Random, slow: Int = 1): Unit = {
      val cd = d.clockDomain
      backpressure(d.io.output, cd, rng, t.outReady)
      val got = monitor(d.io.output, cd) { p =>
        (Cx(p.fragment.re.toLong, p.fragment.im.toLong), p.fragment.exp.toInt, p.last.toBoolean)
      }
      val exp = framer(g.framer, samples).flatMap { f =>
        val u = rfftFrame(g, f)
        u.x.zipWithIndex.map { case (x, k) => (x, u.exp, k == bins - 1) }
      }
      val feeder = fork {
        for (x <- samples) {
          d.io.input.valid #= true; d.io.input.payload #= x
          cd.waitSampling()
          d.io.input.valid #= false
          cd.waitSampling(slow * cps - 1)
        }
      }
      feeder.join()
      waitFor(cd, got.size >= exp.size, 8 * g.frameBusyCycles + 1000, s"${exp.size} prazkow")
      cd.waitSampling(10)
      expectSeq(got, exp, s"Rfft $cfgName")
      assert(!d.io.overrun.toBoolean, "overrun")
    }

    def signal(rng: Random): Seq[Long] = {
      val n = L + 4 * g.framer.hop
      val w = g.framer.sampleWidth
      FftTestKit.uniform(rng, n / 2, w).map(_.re) ++ speechLike(rng, n - n / 2, w, -20)
    }

    def scenario(tp: String)(body: (Rfft, Random) => Unit): Unit =
      testpoint(tp, variant = cfgName) {
        val name = s"${cfgName}_$tp"
        val seed = SimSeed(name)
        dut.doSim(name, seed = seed) { d =>
          val cd = d.clockDomain
          d.io.input.valid #= false; d.io.input.payload #= 0
          d.io.output.ready #= false
          cd.forkStimulus(period = 10)
          StreamConformance.all(cd, StreamPortHandle(d.io.output.valid, d.io.output.ready,
            d.io.output.payload, isInput = false, "out"), stallLimit = 4 * g.frameBusyCycles)
          cd.waitSampling(5)
          body(d, new Random(seed))
        }
      }

    // Budzet toru (marginCycles) liczony jest przy ready = 1. Backpressure
    // na wyjsciu Rfft wydluza ramke (RealUnpack stoi -> rdzen stoi ->
    // Framer stoi), wiec scenariusze z backpressure podaja probki 3x
    // wolniej - sprawdzaja protokol, nie budzet.

    scenario("rfft_smoke")          { (d, rng) => run(d, signal(rng), smooth, rng) }
    scenario("out_payload_stable")  { (d, rng) => run(d, signal(rng), jittery, rng, slow = 3) }
    scenario("out_backpressure")    { (d, rng) => run(d, signal(rng), choked, rng, slow = 8) }
    scenario("out_reset_quiet")     { (d, rng) => idleReset(d.clockDomain); run(d, signal(rng), smooth, rng) }
    unimplemented("out_stress_with_rand_reset", "brak scenariusza resetu w trakcie ramki", variant = cfgName)
  }

  testpoint("rfft_param_bounds") {
    for ((name, g) <- Seq("mimas512" -> FftGenerics.mimas512, "ice40512" -> FftGenerics.ice40512) ++
                      configs.map(c => c.name -> c.g)) {
      info(f"$name%-10s cps=${g.cyclesPerSample}%5d  ramka ${g.frameBusyCycles}%5d cykli  " +
           f"zapas ${g.marginCycles}%7d cykli (${100.0 * g.marginCycles / (g.framer.hop * g.cyclesPerSample)}%.1f%%)")
      assert(g.isLegal, s"$name: ${g.problems.mkString("; ")}")
    }
  }

  testpoint("rfft_vs_float") {
    val rng = new Random(5)
    for (Cfg(name, g) <- configs) {
      val L = g.framer.fftSize
      var worst = Double.PositiveInfinity
      for (db <- Seq(0.0, -20, -40, -60, -80)) {
        val s  = speechLike(rng, L, g.framer.sampleWidth, db)
        val fr = framer(g.framer.copy(hop = L), s).head          // jedna pelna ramka
        val c  = core(g.core, fr, 0, inverse = false)
        val u  = unpack(g.core, c.natural, c.exp, c.nextShift)
        assert(c.sats == 0 && u.sats == 0, s"$name $db dBFS: nasycenia")
        val y   = fr.flatMap(z => Seq(z.re.toDouble, z.im.toDouble))   // okienkowana ramka
        val ref = dft(y.map(v => (v, 0.0)), inverse = false).take(L / 2 + 1)
        worst = math.min(worst, snrDb(scaled(u.x, u.exp), ref))
      }
      val floor = FftCorePlan.snrFloor(g.core) - 3
      info(f"$name najgorszy SNR $worst%.1f dB, prog $floor%.1f dB")
      assert(worst >= floor, s"$name: SNR $worst < $floor")
    }
  }
}
