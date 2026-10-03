package newhope.frontend

import spinal.core._
import spinal.core.sim._
import spinal.lib.{math => _, _}   // wszystko z spinal.lib poza math
import scala.collection.mutable
import scala.util.Random
import newhope.vertebra._
import newhope.vertebra.sim.{SimEnv,SimSeed}
import newhope.core.Conventions
import FrontEndTestKit._

// Tor N0 -> N1 -> N2 (Framer z newhope.fft) w jednej symulacji.
// Zrodlo: nazwy wlasne. StreamConformance portu ramek sprawdza
// FramerTestplan; tu liczy sie tylko, czy klocki do siebie pasuja.

/** Tylko do testow: MicFrontEnd wpiety we Framer tak, jak w VcTop. */
class MicFramerTop(val fe: FrontEndGenerics, val fr: FramerGenerics) extends Component {
  val io = new Bundle {
    val sck      = out Bool()
    val ws       = out Bool()
    val sd       = in Bool()
    val bypassDc = in Bool()
    val samples  = master(Flow(SInt(fe.dc.sampleWidth bits)))   // podglad wyjscia N1
    val frames   = master(Stream(Fragment(BfpCplx(fr.sampleWidth, fr.expWidth))))
    val overrun  = out Bool()
  }
  val front  = new MicFrontEnd(fe)
  val framer = new Framer(fr)

  io.sck   := front.io.sck
  io.ws    := front.io.ws
  front.io.sd       := io.sd
  front.io.bypassDc := io.bypassDc

  framer.io.input << front.io.output       // <- cale "podlaczenie" N1 do N2
  io.samples      << front.io.output
  io.frames       << framer.io.output
  io.overrun      := framer.io.overrun || front.io.dcOverrun
}

/** Odniesienie Framera: ta sama definicja co FftGolden.framer, ale tylko
  * na tablicach z main (Bfp), wiec frontend nie zalezy od testow fft. */
object FramerRef {
  def frames(fg: FramerGenerics, samples: Seq[Long]): Vector[Vector[(Long, Long)]] = {
    val L    = fg.fftSize
    val tab  = Bfp.hannTable(L, fg.windowScale)
    val ring = Array.fill(L)(0L)
    val out  = mutable.ArrayBuffer[Vector[(Long, Long)]]()
    for ((x, t) <- samples.zipWithIndex) {
      ring(t % L) = x
      if ((t + 1) % fg.hop == 0) {
        val y = (0 until L).map { i =>
          val v = ring((t + 1 + i) % L)
          Bfp.roundShr(v * tab(if (i <= L / 2) i else L - i).toLong, fg.windowWidth - 1)
        }
        out += Vector.tabulate(L / 2)(m => (y(2 * m), y(2 * m + 1)))
      }
    }
    out.toVector
  }
}

object MicFrontEndPlan {
  val plan: Seq[Testpoint] = Seq(
    Testpoint("fe_param_bounds", Stage.V1,
      "Generyki N0, N1 i Framera do siebie pasuja",
      checking = Seq("FrontEndGenerics.mimas legalne, fs filtra = fs z I2S",
                     "szerokosc probki N1 == sampleWidth Framera",
                     "hop * cykle na probke > fftSize + 8 (odczyt ramki miesci sie w hopie)")),
    Testpoint("fe_chain", Stage.V1,
      "Piny INMP441 -> I2S -> filtr DC -> Framer",
      stimulus = Seq("losowe slowa z offsetem DC przez model mikrofonu, fftSize + hop probek"),
      checking = Seq("probki N1 == DcGolden(slowo >> 6)",
                     "ramki Framera == FramerRef(DcGolden(...)), z last",
                     "overrun == 0 (Framer i filtr DC)")),
    Testpoint("fe_bypass", Stage.V2,
      "bypassDc podaje Framerowi surowe probki",
      stimulus = Seq("jak fe_chain, bypassDc = 1"),
      checking = Seq("probki == slowo >> 6", "ramki == FramerRef(slowo >> 6)"))
  )

  case class Cfg(name: String, fe: FrontEndGenerics, fr: FramerGenerics)
  // sckDiv 3 (dolna granica, nieparzysty) skraca symulacje 24x wobec
  // Mimasa; numeryka toru od dzielnika nie zalezy.
  val fast = FrontEndGenerics(I2sMicGenerics(clockHz = 3L * 64 * 16000, sckDiv = 3))
  val configs = Seq(
    Cfg("s3_l16_h6",    fast, FramerGenerics(16, 6)),
    Cfg("s3_l512_h160", fast, FramerGenerics())          // rozmiar z kontraktu
  )
  def nSamples(fr: FramerGenerics) = fr.fftSize + 2 * fr.hop
}

class MicFrontEndTestplan extends TestplanSuite {
  import MicFrontEndPlan._
  override def testplan = plan

  for (Cfg(cfgName, fe, fr) <- configs) {
    lazy val dut = SimEnv(Conventions.spinal, s"fe_$cfgName").compile(new MicFramerTop(fe, fr))

    def scenario(tp: String)(body: (MicFramerTop, Random) => Unit) =
      testpoint(tp, variant = cfgName) {
        val name = s"fe_${cfgName}_$tp"
        val seed = SimSeed(name)
        dut.doSim(name, seed = seed) { d =>
          d.io.sd           #= false
          d.io.bypassDc     #= (tp == "fe_bypass")
          d.io.frames.ready #= true
          d.clockDomain.forkStimulus(period = 10)
          guard(cycles = 4000000L)
          body(d, new Random(seed))
        }
      }

    def run(d: MicFramerTop, rng: Random, bypass: Boolean): Unit = {
      val cd  = d.clockDomain
      val n   = nSamples(fr)
      // mowa z offsetem: INMP441 ma skladowa stala, po to jest N1
      val words = (0 until n).map { i =>
        val v = 0.02 + 0.1 * math.sin(2 * math.Pi * 220 * i / fe.i2s.fs) + 0.01 * rng.nextGaussian()
        math.max(-(1L << 23), math.min((1L << 23) - 1, math.round(v * (1L << 23))))
      }
      val raw  = words.map(I2sGolden.sample(fe.i2s, _))
      val xs   = if (bypass) raw.toList else DcGolden.run(fe.dc, raw).y.toList
      val L    = fr.fftSize
      val expF = FramerRef.frames(fr, xs).flatMap(f => f.zipWithIndex.map { case (c, i) => (c, i == L / 2 - 1) })

      val samples = new FlowMonitor(d.io.samples, cd)
      val frames  = mutable.ArrayBuffer[((Long, Long), Boolean)]()
      cd.onSamplings {
        if (d.io.frames.valid.toBoolean && d.io.frames.ready.toBoolean)
          frames += (((d.io.frames.fragment.re.toLong, d.io.frames.fragment.im.toLong), d.io.frames.last.toBoolean))
      }
      val mic = new MicModel(d.io.sck, d.io.ws, d.io.sd, cd, fe.i2s, words, rng)

      waitFor(cd, samples.values.size >= n && frames.size >= expF.size,
              (n + 3) * fe.i2s.cyclesPerSample, s"$n probek i ${expF.size / (L / 2)} ramek")
      val got = samples.values.take(n).toList
      val bad = got.indices.find(i => got(i) != xs(i))
      bad.foreach(i => fail(s"probka N1 $i: RTL ${got(i)}, golden ${xs(i)}"))
      val gf  = frames.take(expF.size).toList
      val bf  = gf.indices.find(i => gf(i) != expF(i))
      bf.foreach(i => fail(s"ramka ${i / (L / 2)}, para ${i % (L / 2)}: RTL ${gf(i)}, golden ${expF(i)}"))
      assert(!d.io.overrun.toBoolean, "overrun w torze")
      info(s"$cfgName: $n probek, ${expF.size / (L / 2)} ramek zgodnych z golden")
    }

    scenario("fe_chain")  { (d, rng) => run(d, rng, bypass = false) }
    scenario("fe_bypass") { (d, rng) => run(d, rng, bypass = true) }
  }

  testpoint("fe_param_bounds") {
    val m = FrontEndGenerics.mimas
    assert(m.isLegal, m.problems.mkString("; "))
    info(f"Mimas: fs ${m.i2s.fs}%.2f Hz, filtr DC fs ${m.dc.sampleRate}%.2f Hz, fc ${m.dc.cutoffEff}%.2f Hz")
    for (Cfg(name, fe, fr) <- configs :+ Cfg("mimas_l512_h160", m, FramerGenerics())) {
      assert(fe.isLegal, s"$name: ${fe.problems.mkString("; ")}")
      assert(fr.isLegal, s"$name: ${fr.problems.mkString("; ")}")
      assert(fe.dc.sampleWidth == fr.sampleWidth, s"$name: N1 ${fe.dc.sampleWidth} b, Framer ${fr.sampleWidth} b")
      val margin = fr.hop * fe.i2s.cyclesPerSample - (fr.fftSize + 8)
      info(s"$name: zapas odczytu ramki $margin cykli")
      assert(margin > 0, s"$name: Framer nie zdazy odczytac ramki")
    }
  }
}
