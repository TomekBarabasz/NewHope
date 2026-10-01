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

// Zrodlo: nazwy wlasne, w duchu i2s z testplanow OpenTitana nie ma
// (OpenTitan nie ma I2S). Odrzucone: csr_*, tl_*, intr_* - brak rejestrow;
// tx_* - to tylko odbiornik. Wyjscie to Flow, wiec StreamConformance nie
// ma zastosowania; zamiast reset_quiet jest i2s_reset.

object I2sMicRxPlan {
  val plan: Seq[Testpoint] = Seq(
    Testpoint("i2s_param_bounds", Stage.V1,
      "Konfiguracje legalne, fs i odstrojenie z dzielnika",
      checking = Seq("I2sMicGenerics.isLegal (w tym sckLow >= syncStages + 2)",
                     "Mimas: fs = 75 MHz / 73 / 64, w zakresie INMP441, odstrojenie < 10 centow")),
    Testpoint("i2s_pin_timing", Stage.V1,
      "SCK i WS zgodne z dzielnikiem i z I2S",
      stimulus = Seq("3 ramki I2S bez danych"),
      checking = Seq("okres SCK = bclkDiv, wysoko sckHigh, nisko sckLow",
                     "WS zmienia sie tylko razem z opadajacym SCK",
                     "WS: slotBits taktow SCK nisko, slotBits wysoko")),
    Testpoint("i2s_smoke", Stage.V1,
      "Losowe slowa 24-bitowe w naszym kanale, smieci w drugim",
      stimulus = Seq("24 losowe slowa; drugi kanal, bit opoznienia i bity 25..32 losowe"),
      checking = Seq("probki == slowo >> (24 - sampleWidth), w kolejnosci",
                     "pierwsza probka to pierwszy pelny slot po resecie (bez polowek)")),
    Testpoint("i2s_reset", Stage.V1,
      "Reset w trakcie odbioru",
      stimulus = Seq("reset w srodku slowa, potem nowy strumien"),
      checking = Seq("valid == 0 i SCK == 0 przez caly reset",
                     "po resecie probki == golden nowego strumienia, bez polowki starego slowa")),
    Testpoint("i2s_corners", Stage.V2,
      "Rogi zakresu slowa",
      stimulus = Seq("0x7FFFFF, -0x800000, 0, -1, 0x00003F, 0x000040, -0x000040, naprzemienne bity"),
      checking = Seq("probki == golden (obciecie w strone -inf)"))
  )

  case class Cfg(name: String, g: I2sMicGenerics)
  val configs = Seq(
    Cfg("mimas",    I2sMicGenerics()),                                   // 75 MHz / 73, nieparzysty dzielnik
    Cfg("d8",       I2sMicGenerics(clockHz = 8L * 64 * 16000, bclkDiv = 8)),                // dolna granica: sckLow = 4
    Cfg("d9_right", I2sMicGenerics(clockHz = 9L * 64 * 16000, bclkDiv = 9, leftChannel = false)),
    Cfg("d8_w24",   I2sMicGenerics(clockHz = 8L * 64 * 16000, bclkDiv = 8, sampleWidth = 24)) // cale slowo
  )

  def randWord(rng: Random) = (rng.nextInt(1 << 24) - (1 << 23)).toLong
}

class I2sMicRxTestplan extends TestplanSuite {
  import I2sMicRxPlan._
  override def testplan = plan

  for (Cfg(cfgName, g) <- configs) {
    lazy val dut = SimEnv(Conventions.spinal, s"i2s_$cfgName").compile(new I2sMicRx(g))

    // Bez waitSampling przed cialem: model mikrofonu musi zobaczyc
    // pierwsze zbocze SCK po resecie, zeby zlapac pierwszy slot.
    def scenario(tp: String)(body: (I2sMicRx, Random) => Unit) =
      testpoint(tp, variant = cfgName) {
        val name = s"i2s_${cfgName}_$tp"
        val seed = SimSeed(name)
        dut.doSim(name, seed = seed) { d =>
          d.io.sd #= false
          d.clockDomain.forkStimulus(period = 10)
          guard()
          body(d, new Random(seed))
        }
      }

    def receive(d: I2sMicRx, words: Seq[Long], rng: Random): Seq[Long] = {
      val cd  = d.clockDomain
      val mon = new FlowMonitor(d.io.output, cd)
      val mic = new MicModel(d.io.sck, d.io.ws, d.io.sd, cd, g, words, rng)
      waitFor(cd, mon.values.size >= words.size, (words.size + 3) * g.cyclesPerSample, s"${words.size} probek")
      mic.active = false
      mon.active = false
      mon.values.take(words.size).toList
    }

    def check(got: Seq[Long], words: Seq[Long], what: String): Unit = {
      val exp = words.map(I2sGolden.sample(g, _))
      val bad = got.indices.find(i => got(i) != exp(i))
      bad.foreach(i => fail(f"$what: probka $i: RTL ${got(i)}, golden ${exp(i)} (slowo 0x${words(i) & 0xFFFFFF}%06X)"))
    }

    scenario("i2s_pin_timing") { (d, rng) =>
      val cd = d.clockDomain
      waitFor(cd, d.io.sck.toBoolean, 4L * g.bclkDiv, "pierwsze SCK")
      val sck = mutable.ArrayBuffer[Boolean]()
      val ws  = mutable.ArrayBuffer[Boolean]()
      for (_ <- 0L until 3 * g.cyclesPerSample) {
        cd.waitSampling()
        sck += d.io.sck.toBoolean
        ws  += d.io.ws.toBoolean
      }
      val rises = (1 until sck.size).filter(i => sck(i) && !sck(i - 1))
      val falls = (1 until sck.size).filter(i => !sck(i) && sck(i - 1))
      val per   = rises.zip(rises.tail).map { case (a, b) => b - a }.distinct
      assert(per == Seq(g.bclkDiv), s"okres SCK $per, oczekiwano ${g.bclkDiv}")
      val highs = rises.flatMap(r => falls.find(_ > r).map(_ - r)).distinct
      val lows  = falls.flatMap(f => rises.find(_ > f).map(_ - f)).distinct
      assert(highs == Seq(g.sckHigh) && lows == Seq(g.sckLow), s"SCK wysoko $highs, nisko $lows")
      val wsCh  = (1 until ws.size).filter(i => ws(i) != ws(i - 1))
      assert(wsCh.forall(falls.contains), s"WS zmienia sie poza opadajacym SCK: ${wsCh.filterNot(falls.contains)}")
      val wsPer = wsCh.zip(wsCh.tail).map { case (a, b) => b - a }.distinct
      assert(wsPer == Seq(g.slotBits * g.bclkDiv), s"slot WS $wsPer cykli, oczekiwano ${g.slotBits * g.bclkDiv}")
      info(s"$cfgName: SCK ${g.sckHigh}/${g.sckLow} cykli, slot ${g.slotBits * g.bclkDiv} cykli")
    }

    scenario("i2s_smoke") { (d, rng) =>
      val words = Seq.fill(24)(randWord(rng))
      check(receive(d, words, rng), words, "smoke")
    }

    scenario("i2s_corners") { (d, rng) =>
      val words = Seq(0x7FFFFFL, -0x800000L, 0L, -1L, 0x3FL, 0x40L, -0x40L, 0x555555L, -0x555556L)
      check(receive(d, words, rng), words, "corners")
    }

    scenario("i2s_reset") { (d, rng) =>
      val cd = d.clockDomain
      quietDuringReset(cd, d.io.output.valid, "out")
      // stary strumien: zatrzymany w polowie slowa
      val old = new MicModel(d.io.sck, d.io.ws, d.io.sd, cd, g, Seq.fill(100)(randWord(rng)), rng)
      cd.waitSampling((2.5 * g.cyclesPerSample).toInt + g.slotBits / 2 * g.bclkDiv)
      cd.assertReset()
      cd.waitActiveEdge(20)
      assert(!d.io.sck.toBoolean, "SCK w resecie")
      old.active = false
      // nowy model rusza przed zwolnieniem resetu, jak przy starcie
      val words = Seq.fill(8)(randWord(rng))
      val mon   = new FlowMonitor(d.io.output, cd)
      val mic   = new MicModel(d.io.sck, d.io.ws, d.io.sd, cd, g, words, rng)
      cd.deassertReset()
      waitFor(cd, mon.values.size >= words.size, (words.size + 3) * g.cyclesPerSample, "probki po resecie")
      check(mon.values.take(words.size).toList, words, "po resecie")
    }
  }

  // ---- bez symulacji ---------------------------------------------------
  testpoint("i2s_param_bounds") {
    for (Cfg(name, g) <- configs) {
      assert(g.isLegal, s"$name: ${g.problems.mkString("; ")}")
      info(f"$name: BCLK ${g.bclkHz / 1e6}%.4f MHz, fs ${g.fs}%.2f Hz (${g.cents()}%+.2f ct), " +
           s"${g.cyclesPerSample} cykli/probke, zapas probkowania SD ${g.sampleMargin} cykli")
    }
    val m = I2sMicGenerics()
    assert(m.cyclesPerSample == 73L * 64, s"Mimas: ${m.cyclesPerSample} cykli na probke")
    assert(math.abs(m.fs - 75e6 / (73 * 64)) < 1e-6, s"Mimas: fs ${m.fs}")
    assert(m.micInRange, s"Mimas: fs ${m.fs} poza zakresem INMP441")
    assert(math.abs(m.cents()) < 10, f"Mimas: odstrojenie ${m.cents()}%.2f ct")
  }
}
