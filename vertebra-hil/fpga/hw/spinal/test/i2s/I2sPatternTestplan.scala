package newhope.vertebra.hil.i2s

import java.io.File
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._
import scala.collection.mutable
import scala.io.Source
import scala.util.Random
import newhope.i2s.{I2sFormat, I2sFrame}
import newhope.vertebra.{Stage, Testpoint, TestplanSuite}
import newhope.vertebra.sim.SimBackend
import newhope.vertebra.hil.Config
import I2sPattern.{Link, U32}

// =====================================================================
//  ZRODLO PLANU
//  contract/i2s/pattern.md i rejestry gap_* z contract/commands.md.
//  Krok 2b etapu 2: generator i checker w Spinalu, bez DUT-ow i UART-u.
//  Wektory czytane z plikow (nie z I2sVectors): tak jak zrobi to ESP32.
//
//  I2sPatternBench (ponizej) gra DUT-a w najprostszej postaci: granica
//  ramki co `period` cykli, na niej handshake albo underrun. Prawdziwe
//  DUT-y dochodza w kroku 2d.
// =====================================================================

/** Generator + uproszczony DUT + checker (transfer wg -> wrx w slocie 32). */
case class I2sPatternBench(wg : Int, wrx : Int, period : Int) extends Component {
  require(period >= 8, "checker potrzebuje >= 5 cykli na ramke, z zapasem")
  val io = new Bundle {
    val clear, enable, hold = in Bool()
    val seed     = in Bits(32 bits)
    val gapMode  = in UInt(2 bits)
    val gapEvery = in UInt(16 bits)
    val gapLen   = in UInt(16 bits)
    val chkPeerW = in UInt(6 bits)
    val chkSlot  = in UInt(6 bits)
    val ev       = master(Flow(I2sFrame(wg)))   // granica ramki: ramka albo 0
    val evFrame  = out Bool()                   // true = ramka, false = luka
    val genValid = out Bool()
    val genData  = out(I2sFrame(wg))
    val sent     = out UInt(32 bits)
    val stat     = out(I2sCheckStat(wrx))
    val locked   = out Bool()
  }

  val gen = I2sPatternGen(wg)
  val chk = I2sPatternCheck(wrx)

  // Licznik granic startuje od clear, a w cyklu clear granicy nie ma:
  // test zaczyna zbierac zdarzenia od cyklu po clear i widzi dokladnie
  // te same granice co checker (clear zeruje jego indeks ramek).
  val cnt  = Reg(UInt(log2Up(period) bits)) init 0
  val tick = cnt === period - 1
  when(tick || io.clear) { cnt := 0 } otherwise { cnt := cnt + 1 }
  val boundary = tick && !io.hold && !io.clear

  gen.io.tx.ready := boundary
  gen.io.underrun  := boundary && !gen.io.tx.valid
  gen.io.clear     := io.clear
  gen.io.enable    := io.enable
  gen.io.seed      := io.seed
  gen.io.gapMode   := io.gapMode
  gen.io.gapEvery  := io.gapEvery
  gen.io.gapLen    := io.gapLen

  io.ev.valid   := RegNext(boundary) init False
  io.ev.payload := RegNext(Mux(gen.io.tx.valid, gen.io.tx.payload, gen.io.tx.payload.getZero))
  io.evFrame    := RegNext(gen.io.tx.valid) init False
  io.genValid   := gen.io.tx.valid
  io.genData    := gen.io.tx.payload
  io.sent       := gen.io.sent

  private def xfer(b : Bits) : Bits =
    if (wrx <= wg) b(wg - 1 downto wg - wrx) else b ## B(0, (wrx - wg) bits)

  chk.io.rx.valid         := io.ev.valid
  chk.io.rx.payload.left  := xfer(io.ev.payload.left)
  chk.io.rx.payload.right := xfer(io.ev.payload.right)
  chk.io.clear            := io.clear
  chk.io.cfg.seed         := io.seed
  chk.io.cfg.peerW        := io.chkPeerW
  chk.io.cfg.slot         := io.chkSlot
  io.stat   := chk.io.stat
  io.locked := chk.io.locked
}

object I2sPatternPlan {
  val plan : Seq[Testpoint] = Seq(
    Testpoint("pat_param_bounds", Stage.V1,
      "Szerokosci i czas na ramke w granicach konstrukcji",
      checking = Seq("generator: W w [8, 32] dla wszystkich wariantow",
                     "najkrotsza ramka w harnessie >= 5 cykli checkera (wyprowadzenie w tescie)")),
    Testpoint("pat_gen_vectors", Stage.V1,
      "Generator == pattern.csv",
      stimulus = Seq("W = 8, 16, 24, 32; wszystkie seedy z pattern.csv; 256 ramek od n = 0"),
      checking = Seq("kazdy wiersz pattern.csv (n mod 2^S) == ramka generatora")),
    Testpoint("pat_gen_backpressure", Stage.V1,
      "Losowe wstrzymanie odbiorcy",
      checking = Seq("gdy valid: payload == ramka n, n = liczba ramek juz oddanych",
                     "ciag ramek bez przerw i dubli, sent == liczba ramek")),
    Testpoint("pat_gen_gaps", Stage.V1,
      "Luki okresowe i losowe",
      stimulus = Seq("mode 1: every 5 len 2, every 1 len 3; mode 2: every 4 len 1"),
      checking = Seq("zdarzenia na granicach ramki == I2sGapModel.events")),
    Testpoint("pat_check_vectors", Stage.V1,
      "Checker == checker_cases.csv",
      stimulus = Seq("wszystkie scenariusze z checker_frames.csv, ramka co 6 cykli"),
      checking = Seq("frames, bad, gaps, relocks, lock_at i first_err == csv")),
    Testpoint("pat_loop", Stage.V1,
      "Generator -> checker z lukami",
      stimulus = Seq("16 -> 16 i 24 -> 16 (transfer), mode 1 every 7 len 2, 300 granic"),
      checking = Seq("frames == ramki, gaps == luki, bad == 0, relocks == 0",
                     "lock_at == liczba cichych ramek przed pierwsza")),
    Testpoint("pat_check_vs_model", Stage.V2,
      "Losowe strumienie: checker == I2sCheckerModel",
      stimulus = Seq("drop, dup, zamiana kanalow, przeklamany bit, cisza, smieci; 3 seedy po 300 ramek"),
      checking = Seq("wszystkie liczniki i first_err == model")),
    Testpoint("pat_check_overrun", Stage.V2,
      "Ramka w trakcie liczenia",
      checking = Seq("dwie ramki 2 cykle po sobie -> overrun", "clear kasuje overrun"))
  )

  val vectorsDir = I2sVectors.defaultDir

  def csv(name : String) : Seq[Map[String, String]] = {
    val f = new File(vectorsDir, name)
    require(f.isFile, s"brak ${f.getCanonicalPath} (etap 1: I2sVectors)")
    val src = Source.fromFile(f, "US-ASCII")
    val lines = try src.getLines().map(_.trim).filter(_.nonEmpty).toVector finally src.close()
    val head = lines.head.split(",").toSeq
    lines.tail.map(l => head.zip(l.split(",", -1).toSeq).toMap)
  }

  def hex(s : String) : Long = java.lang.Long.parseLong(s, 16)

  val genWidths = Seq(8, 16, 24, 32)
  val period    = 8
}

class I2sPatternTestplan extends TestplanSuite {
  import I2sPatternPlan._
  def testplan : Seq[Testpoint] = plan

  private def ws(n : String) = s"${n}_${SimBackend.default.label}"

  // -------------------------------------------------------------------
  //  Bench: generator (+ checker)
  // -------------------------------------------------------------------
  case class BenchEnv(d : I2sPatternBench) {
    val cd = d.clockDomain
    /** Zdarzenia od cyklu po clear: Some(ramka) albo None (luka). */
    val events = mutable.ArrayBuffer[Option[I2sWords]]()

    def setup(seed : Long, mode : Int = 0, every : Int = 0, len : Int = 0,
              peerW : Int = d.wg, slot : Int = 32) : Unit = {
      d.io.enable #= false
      d.io.hold   #= false
      d.io.seed   #= seed
      d.io.gapMode #= mode; d.io.gapEvery #= every; d.io.gapLen #= len
      d.io.chkPeerW #= peerW; d.io.chkSlot #= slot
      d.io.clear #= true
      cd.waitSampling()
      d.io.clear #= false
      cd.waitSampling()          // w tym cyklu ev.valid == 0 (brak granicy w cyklu clear)
      events.clear()
      cd.waitSampling(2)
      d.io.enable #= true
    }

    def waitEvents(n : Int) : Unit = cd.waitSamplingWhere(events.size >= n)

    /** Zdarzenia bez cichych granic sprzed pierwszej ramki (enable). */
    def fromFirstFrame : Seq[Option[I2sWords]] = events.dropWhile(_.isEmpty).toSeq
  }

  def bench(wg : Int, wrx : Int, name : String)(body : BenchEnv => Unit) : Unit = {
    val dut = benches.getOrElseUpdate((wg, wrx), Config.sim
      .workspaceName(ws(s"pat_bench_${wg}_$wrx"))
      .compile(I2sPatternBench(wg, wrx, period)))
    dut.doSim(s"pat_${wg}_${wrx}_$name", seed = 42) { d =>
      d.clockDomain.forkStimulus(period = 10)
      d.io.clear #= false; d.io.enable #= false; d.io.hold #= false
      d.io.seed #= 0; d.io.gapMode #= 0; d.io.gapEvery #= 0; d.io.gapLen #= 0
      d.io.chkPeerW #= wg; d.io.chkSlot #= 32
      val e = BenchEnv(d)
      FlowMonitor(d.io.ev, d.clockDomain) { p =>
        e.events += (if (d.io.evFrame.toBoolean) Some(I2sWords(p.left.toLong, p.right.toLong)) else None)
      }
      SimTimeout(2000L * period * 10 * 4)
      d.clockDomain.waitSampling(5)
      body(e)
    }
  }
  private val benches = mutable.Map[(Int, Int), SimCompiled[I2sPatternBench]]()

  for (w <- genWidths) {
    testpoint("pat_gen_vectors", variant = s"w$w") {
      val rows  = csv("pattern.csv").filter(_("w").toInt == w)
      val seeds = rows.map(r => hex(r("seed"))).distinct
      val sm    = 1L << I2sPattern.seqBits(w)
      bench(w, w, "vectors") { e =>
        for (seed <- seeds) {
          e.setup(seed)
          e.waitEvents(sm.toInt + 2)
          val frames = e.fromFirstFrame.take(sm.toInt).map(_.getOrElse(fail(f"seed $seed%08x: luka")))
          assert(frames.size == sm, s"${frames.size} ramek")
          for (r <- rows if hex(r("seed")) == seed) {
            val n   = r("n").toLong & (sm - 1)
            val f   = frames(n.toInt)
            val got = if (r("c") == "0") f.l else f.r
            assert(got == hex(r("word")), f"seed=$seed%08x n=${r("n")} c=${r("c")} W=$w: $got%08x != ${r("word")}")
          }
        }
      }
    }
  }

  testpoint("pat_gen_backpressure") {
    bench(16, 16, "backpressure") { e =>
      val d = e.d; val seed = 0x1234abcdL; val rng = new Random(3)
      e.setup(seed)
      val errors = mutable.ArrayBuffer[String]()
      fork {
        while (true) {
          d.io.hold #= rng.nextInt(3) == 0
          e.cd.waitSampling()
          val done = e.events.count(_.isDefined)
          if (d.io.genValid.toBoolean) {
            val exp = I2sPattern.frame(seed, done.toLong, 16)
            val got = I2sWords(d.io.genData.left.toLong, d.io.genData.right.toLong)
            if (got != exp && errors.size < 5) errors += s"po $done ramkach: $got != $exp"
          }
        }
      }
      e.waitEvents(300)
      assert(errors.isEmpty, errors.mkString("; "))
      val frames = e.fromFirstFrame.flatten
      assert(frames == frames.indices.map(n => I2sPattern.frame(seed, n.toLong, 16)), "ciag ramek")
      e.cd.waitSampling()
      assert(d.io.sent.toLong >= frames.size && d.io.sent.toLong <= frames.size + 1, s"sent=${d.io.sent.toLong}")
    }
  }

  testpoint("pat_gen_gaps") {
    val seed = 0x0000beefL
    bench(16, 16, "gaps") { e =>
      for ((mode, every, len) <- Seq((1, 5, 2), (1, 1, 3), (2, 4, 1))) {
        e.setup(seed, mode, every, len)
        e.waitEvents(210)
        val got = e.fromFirstFrame.take(200)
        val exp = I2sGapModel.events(mode, every, len, seed, 200).map(_.map(n => I2sPattern.frame(seed, n, 16)))
        // Komunikat assert w ScalaTescie jest liczony zawsze, wiec bez i.get w nim.
        got.indices.find(k => got(k) != exp(k)).foreach { k =>
          fail(s"mode=$mode every=$every len=$len: pierwsza roznica na granicy $k: ${got(k)} != ${exp(k)}")
        }
        info(s"mode=$mode every=$every len=$len: ${exp.count(_.isEmpty)} luk na 200 granic")
      }
    }
  }

  for ((wg, wrx) <- Seq((16, 16), (24, 16))) {
    testpoint("pat_loop", variant = s"${wg}_to_$wrx") {
      val seed = 0x600dcafeL
      bench(wg, wrx, "loop") { e =>
        e.setup(seed, mode = 1, every = 7, len = 2, peerW = wg, slot = 32)
        e.waitEvents(300)
        // Zatrzymac granice przed porownaniem: ostatnie zdarzenie jest juz
        // w logu, a checker liczy je jeszcze przez kilka cykli.
        e.d.io.hold #= true
        e.cd.waitSampling(3 * period)
        val all    = e.events.toSeq
        val lead   = all.takeWhile(_.isEmpty).size
        val s      = e.d.io.stat
        val nFrame = all.count(_.isDefined)
        val nGap   = all.drop(lead).count(_.isEmpty)
        assert(s.frames.toLong == nFrame, s"frames ${s.frames.toLong} != $nFrame")
        assert(s.gaps.toLong == nGap, s"gaps ${s.gaps.toLong} != $nGap")
        assert(s.bad.toLong == 0 && s.relocks.toLong == 0 && !s.errValid.toBoolean,
               s"bad=${s.bad.toLong} relocks=${s.relocks.toLong}")
        assert(s.lockAt.toLong == lead, s"lock_at ${s.lockAt.toLong} != $lead")
        info(s"$wg -> $wrx: $nFrame ramek, $nGap luk, lock_at $lead")
      }
    }
  }

  // -------------------------------------------------------------------
  //  Checker sam
  // -------------------------------------------------------------------
  private val checkers = mutable.Map[Int, SimCompiled[I2sPatternCheck]]()
  def checker(wrx : Int, name : String)(body : (I2sPatternCheck, ClockDomain) => Unit) : Unit = {
    val dut = checkers.getOrElseUpdate(wrx, Config.sim
      .workspaceName(ws(s"pat_chk_$wrx")).compile(I2sPatternCheck(wrx)))
    dut.doSim(s"pat_chk_${wrx}_$name", seed = 42) { d =>
      d.clockDomain.forkStimulus(period = 10)
      d.io.rx.valid #= false; d.io.rx.payload.left #= 0; d.io.rx.payload.right #= 0
      d.io.clear #= false
      d.io.cfg.seed #= 0; d.io.cfg.peerW #= 16; d.io.cfg.slot #= 32
      SimTimeout(1000000L)
      d.clockDomain.waitSampling(5)
      body(d, d.clockDomain)
    }
  }

  def configure(d : I2sPatternCheck, cd : ClockDomain, l : Link) : Unit = {
    d.io.cfg.seed #= l.seed; d.io.cfg.peerW #= l.wtx; d.io.cfg.slot #= l.slot
    d.io.clear #= true; cd.waitSampling(); d.io.clear #= false; cd.waitSampling(2)
  }

  def feed(d : I2sPatternCheck, cd : ClockDomain, frames : Seq[I2sWords], gap : Int = 6) : Unit = {
    for (f <- frames) {
      d.io.rx.valid #= true
      d.io.rx.payload.left #= f.l; d.io.rx.payload.right #= f.r
      cd.waitSampling()
      d.io.rx.valid #= false
      cd.waitSampling(gap - 1)
    }
    cd.waitSampling(8)
  }

  def readStat(d : I2sPatternCheck) : CheckerStat = {
    val s = d.io.stat
    val lockAt = s.lockAt.toLong
    CheckerStat(s.frames.toLong, s.bad.toLong, s.gaps.toLong, s.relocks.toLong,
      if (lockAt == U32) -1L else lockAt,
      if (!s.errValid.toBoolean) None
      else Some(CheckerErr(s.errN.toLong,
        I2sWords(s.errGot.left.toLong, s.errGot.right.toLong),
        I2sWords(s.errExp.left.toLong, s.errExp.right.toLong))))
  }

  {
    val cases  = csv("checker_cases.csv")
    val frames = csv("checker_frames.csv").groupBy(_("case"))
      .map { case (c, rs) => c -> rs.sortBy(_("idx").toInt).map(r => I2sWords(hex(r("l")), hex(r("r")))) }
    for ((wrx, cs) <- cases.groupBy(_("wrx").toInt).toSeq.sortBy(_._1)) {
      testpoint("pat_check_vectors", variant = s"wrx$wrx") {
        checker(wrx, "vectors") { (d, cd) =>
          for (c <- cs) {
            val name = c("case")
            val l = Link(hex(c("seed")), c("wtx").toInt, c("slot").toInt, wrx)
            configure(d, cd, l)
            feed(d, cd, frames(name))
            val got = readStat(d)
            val exp = CheckerStat(c("frames").toLong, c("bad").toLong, c("gaps").toLong, c("relocks").toLong,
              c("lock_at").toLong,
              if (c("err_n") == "-") None
              else Some(CheckerErr(c("err_n").toLong,
                I2sWords(hex(c("err_got_l")), hex(c("err_got_r"))),
                I2sWords(hex(c("err_exp_l")), hex(c("err_exp_r"))))))
            assert(got == exp, s"$name:\n  got $got\n  exp $exp")
            assert(!d.io.overrun.toBoolean, s"$name: overrun")
            info(s"$name: $got")
          }
        }
      }
    }
  }

  testpoint("pat_check_vs_model") {
    val l = Link(0x2aL, 16, 32, 16)
    checker(16, "vs_model") { (d, cd) =>
      for (seed <- Seq(1, 2, 3)) {
        val rng = new Random(seed)
        var n   = rng.nextInt(1000).toLong
        val stream = mutable.ArrayBuffer[I2sWords]()
        while (stream.size < 300) {
          val f = l.expected(n)
          rng.nextInt(20) match {
            case 0 => n += 1                                            // drop
            case 1 => stream += f; stream += f; n += 1                  // dup
            case 2 => stream += f.swap; n += 1
            case 3 => stream += I2sWords(f.l ^ (1L << rng.nextInt(16)), f.r); n += 1
            case 4 => stream += I2sWords(0, 0)                          // luka
            case 5 => stream += I2sWords(rng.nextInt(1 << 16), rng.nextInt(1 << 16))
            case _ => stream += f; n += 1
          }
        }
        configure(d, cd, l)
        feed(d, cd, stream.toSeq)
        val exp = I2sCheckerModel.run(l, stream.toSeq)
        val got = readStat(d)
        assert(got == exp, s"seed $seed:\n  got $got\n  exp $exp")
        info(s"seed $seed: $got")
      }
    }
  }

  testpoint("pat_check_overrun") {
    val l = Link(0x2aL, 16, 32, 16)
    checker(16, "overrun") { (d, cd) =>
      configure(d, cd, l)
      feed(d, cd, Seq(l.expected(0)), gap = 2)
      assert(!d.io.overrun.toBoolean, "overrun po jednej ramce")
      feed(d, cd, Seq(l.expected(1), l.expected(2)), gap = 2)
      assert(d.io.overrun.toBoolean, "brak overrun przy ramkach co 2 cykle")
      configure(d, cd, l)
      assert(!d.io.overrun.toBoolean, "clear nie skasowal overrun")
    }
  }

  testpoint("pat_param_bounds") {
    // Warianty harnessu (i2s/commands.md): szerokosc generatora == DUT.
    for (w <- genWidths) assert(I2sPattern.validWidth(w), s"W=$w")
    // Checker: 1 cykl rejestracji + porownanie, do 2 razy, plus cykl idle:
    //   idle -> wait1 -> cmp1 -> wait2 -> cmp2 -> idle = 5 cykli na ramke.
    // Najkrotsza ramka w harnessie: slave przy polokresie SCK > txLatency
    // (3 cykle, I2sSlaveGenerics), czyli >= 4 cykle; slot >= 8 bitow.
    val checkCycles = 5
    val minFrame    = 2 * 8 * 2 * (newhope.i2s.I2sSlaveGenerics().txLatencyCycles + 1)
    info(s"checker: $checkCycles cykli na ramke, najkrotsza ramka $minFrame cykli")
    assert(minFrame >= checkCycles)
  }
}
