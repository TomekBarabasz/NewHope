package newhope.vertebra.hil

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import scala.collection.mutable
import scala.util.Random
import newhope.vertebra.{Stage, Testpoint, TestplanSuite}
import newhope.vertebra.sim.SimBackend
import HilProtocol._

// =====================================================================
//  ZRODLO PLANU
//  contract/commands.md: liczniki (migawka), capture, rst_*, gen/gap_*
//  i zasada CDC z vertebra-hil.md §6. Krok 2c etapu 2.
//
//  DUT: HilSideBench - czesci harnessu miedzy domenami sys i dut, bez
//  mostu UART (magistrale steruje test, zeby bylo szybko) i bez IP
//  (slowa licznikow i wpisy capture podaje test w domenie dut).
//  Zegary niezalezne: sys okres 10, dut z konfiguracji - wolniejszy
//  i szybszy od sys, stosunek niecalkowity, faza dryfuje (§4.2).
// =====================================================================

/** Czesci harnessu wokol przejscia sys <-> dut, jak zlozy je I2sHarness. */
case class HilSideBench() extends Component {
  val nIp = Counters.fromIp

  val io = new Bundle {
    val sysClk, sysRst, dutClk, dutRst = in Bool()
    val bus       = slave(HilRegBus())                      // sys
    val start     = in  Bool()                              // sys, impulsy (zamiast ctrl)
    val stop      = in  Bool()
    val running   = out Bool()
    val snapshot  = out Bool()
    val dutStat   = in  Vec(Bits(32 bits), nIp)             // dut: slowa od IP
    val capPush   = slave(Flow(Vec(Bits(32 bits), Capture.WordsPerEntry)))
    val capTrig   = in  Bool()
    val dutStart  = out Bool()                              // dut: dutGo (start z konfiguracja)
    val dutReset  = out Bool()
    val dutCfg    = out(HilRunCfg())
  }

  val sysCd = ClockDomain(io.sysClk, io.sysRst)
  val dutCd = ClockDomain(io.dutClk, io.dutRst)

  val counters = HilCounters(sysCd, dutCd)
  val capture  = HilCapture(sysCd, dutCd)
  val runRegs  = HilRunRegs(sysCd, dutCd)

  val sys = new ClockingArea(sysCd) {
    val running = RegInit(False)
    when(io.start) { running := True }
    when(io.stop)  { running := False }
    io.running  := running
    io.snapshot := counters.io.ready
    counters.io.start := io.start
    runRegs.io.running := running

    HilRegBus.decode(io.bus, Seq(
      (Counters.Base, Counters.Size, counters.io.bus),
      (Addr.RunBase,  Addr.RunSize,  runRegs.io.bus),
      (Capture.Base,  Capture.size,  capture.io.bus)))
  }

  val startDut = PulseCCByToggle(io.start, sysCd, dutCd)
  val stopDut  = PulseCCByToggle(io.stop,  sysCd, dutCd)

  val dut = new ClockingArea(dutCd) {
    val inj = HilResetInjector()
    inj.io.start := runRegs.io.dutGo
    inj.io.stop  := stopDut
    inj.io.cfg   := runRegs.io.dutCfg

    runRegs.io.dutStart := startDut
    capture.io.dutClear := runRegs.io.dutGo
    capture.io.dutPush  << io.capPush
    capture.io.dutTrigger := io.capTrig

    counters.io.dutStop := stopDut
    for (i <- 0 until nIp) counters.io.dutWords(i) := io.dutStat(i)
    counters.io.dutWords(Counters.fromIp)     := capture.io.dutCount.asBits
    counters.io.dutWords(Counters.fromIp + 1) := inj.io.done.asBits

    io.dutStart := runRegs.io.dutGo
    io.dutReset := inj.io.dutReset
    io.dutCfg   := runRegs.io.dutCfg
  }
}

object HilSidePlan {
  val plan : Seq[Testpoint] = Seq(
    Testpoint("side_param_bounds", Stage.V1,
      "Mapa i rozmiary bez symulacji",
      checking = Seq("zakresy liczniki / run / capture / IP rozlaczne i w 16 bitach",
                     "liczniki mieszcza sie w 0x010-0x01F, capture wyrownany do rozmiaru",
                     "HilResetModel zgodny z recznym przykladem")),
    Testpoint("side_counters_snapshot", Stage.V1,
      "Migawka licznikow przy stop, spojna mimo niezaleznych zegarow",
      stimulus = Seq("slowa zmieniaja sie co cykl dut: w_i = 16 * cykl + i", "20 stopow w losowych chwilach"),
      checking = Seq("po status.snapshot: w_i - w_0 == i dla wszystkich slow (jeden cykl)",
                     "start kasuje status.snapshot, migawka stala do nastepnego stop")),
    Testpoint("side_run_regs", Stage.V1,
      "Konfiguracja biegu: zapis w stop, blokada w biegu, zatrzasniecie przy starcie",
      checking = Seq("odczyt == zapis dla wszystkich rejestrow gen_*/rst_*",
                     "zapis w biegu: status busy, wartosc bez zmian",
                     "po starcie konfiguracja w domenie dut == rejestry")),
    Testpoint("side_capture", Stage.V1,
      "Okno przechwytywania wokol pierwszego wyzwolenia",
      stimulus = Seq("100 wpisow, wyzwolenie przy 40", "100 wpisow bez wyzwolenia", "10 wpisow"),
      checking = Seq("cap_count == min(wpisy, 32)", "indeksy: 24..55 / 68..99 / 0..9",
                     "slowa wpisow == podane, kolumny 5..7 == 0, zapis -> bad_op")),
    Testpoint("side_reset_injector", Stage.V1,
      "Harmonogram resetow DUT-a",
      stimulus = Seq("count 5, min 40, mask 31, len 7", "stop w trakcie serii"),
      checking = Seq("poczatki resetow wzgledem impulsu startu == HilResetModel, dlugosc len",
                     "rst_done w licznikach == 5", "stop zwalnia reset i konczy serie"))
  )

  case class Cfg(name : String, dutPeriod : Int)
  // sys: okres 10. dut wolniejszy (37 ~ 27 MHz, jak zegar audio) i szybszy (7).
  val configs = Seq(Cfg("dut_slow", 37), Cfg("dut_fast", 7))
}

class HilSideTestplan extends TestplanSuite {
  import HilSidePlan._
  def testplan : Seq[Testpoint] = plan

  case class Env(d : HilSideBench, sysCd : ClockDomain, dutCd : ClockDomain) {
    var dutCycle = 0L
    var dutStartAt = -1L
    val resetCycles = mutable.ArrayBuffer[Long]()

    def read(addr : Int) : (Int, Long) = {
      d.io.bus.addr #= addr; d.io.bus.write #= false; d.io.bus.valid #= false
      sysCd.waitSampling()                      // adres stabilny cykl przed valid (readSync)
      d.io.bus.valid #= true
      sysCd.waitSampling()
      val r = (d.io.bus.status.toInt, d.io.bus.rdata.toLong)
      d.io.bus.valid #= false
      r
    }
    def readOk(addr : Int) : Long = {
      val (s, v) = read(addr)
      assert(s == Status.Ok, f"read $addr%03x: status $s")
      v
    }
    def write(addr : Int, data : Long) : Int = {
      d.io.bus.addr #= addr; d.io.bus.write #= true; d.io.bus.wdata #= data; d.io.bus.valid #= false
      sysCd.waitSampling()
      d.io.bus.valid #= true
      sysCd.waitSampling()
      val s = d.io.bus.status.toInt
      d.io.bus.valid #= false; d.io.bus.write #= false
      s
    }
    def pulse(b : Bool) : Unit = { b #= true; sysCd.waitSampling(); b #= false; sysCd.waitSampling() }
    def start() : Unit = pulse(d.io.start)
    def stop()  : Unit = {
      pulse(d.io.stop)
      sysCd.waitSamplingWhere(d.io.snapshot.toBoolean)
    }
    def counter(name : String) : Long = readOk(Counters.addr(name))
  }

  for (Cfg(cfgName, dutPeriod) <- configs) {
    lazy val dut = Config.sim
      .workspaceName(s"side_${cfgName}_${SimBackend.default.label}")
      .compile(HilSideBench())

    def scenario(tp : String)(body : Env => Unit) : Unit =
      testpoint(tp, variant = cfgName) {
        dut.doSim(s"side_${cfgName}_$tp", seed = 42) { d =>
          val sysCd = ClockDomain(d.io.sysClk, d.io.sysRst)
          val dutCd = ClockDomain(d.io.dutClk, d.io.dutRst)
          d.io.bus.valid #= false; d.io.bus.write #= false; d.io.bus.addr #= 0; d.io.bus.wdata #= 0
          d.io.start #= false; d.io.stop #= false
          d.io.dutStat.foreach(_ #= 0)
          d.io.capPush.valid #= false; d.io.capPush.payload.foreach(_ #= 0); d.io.capTrig #= false
          sysCd.forkStimulus(period = 10)
          dutCd.forkStimulus(period = dutPeriod)
          val e = Env(d, sysCd, dutCd)
          fork {                                    // licznik cykli dut i zdarzenia
            while (true) {
              dutCd.waitSampling()
              e.dutCycle += 1
              if (d.io.dutStart.toBoolean) e.dutStartAt = e.dutCycle
              if (d.io.dutReset.toBoolean) e.resetCycles += e.dutCycle
            }
          }
          SimTimeout(5000000L)
          sysCd.waitSampling(20); dutCd.waitSampling(20)
          body(e)
        }
      }

    scenario("side_counters_snapshot") { e =>
      val d = e.d; val rng = new Random(11)
      fork {
        while (true) {
          e.dutCd.waitSampling()
          for (i <- 0 until d.nIp) d.io.dutStat(i) #= ((e.dutCycle * 16 + i) & 0xFFFFFFFFL)
        }
      }
      for (k <- 0 until 20) {
        e.start()
        assert(!d.io.snapshot.toBoolean, "start nie skasowal snapshot")
        e.sysCd.waitSampling(20 + rng.nextInt(200))
        e.stop()
        val w = (0 until d.nIp).map(i => e.readOk(Counters.Base + i))
        assert(w(0) != 0, s"proba $k: migawka pusta")
        for (i <- 1 until d.nIp)
          assert(w(i) - w(0) == i, f"proba $k: slowo $i = ${w(i)}%08x, slowo 0 = ${w(0)}%08x - rozne cykle")
        e.sysCd.waitSampling(50)
        assert(e.readOk(Counters.Base) == w(0), s"proba $k: migawka zmienila sie po stop")
      }
    }

    scenario("side_run_regs") { e =>
      val vals = Seq(
        Addr.GenSeed -> 0xdeadbeefL, Addr.GapMode -> 2L, Addr.GapEvery -> 0x8000L, Addr.GapLen -> 3L,
        Addr.RstCount -> 0x1234L, Addr.RstSeed -> 0xcafe0001L, Addr.RstMin -> 0x10000L,
        Addr.RstMask -> 0xffL, Addr.RstLen -> 0x0f0fL)
      for ((a, v) <- vals) assert(e.write(a, v) == Status.Ok, f"zapis $a%03x")
      for ((a, v) <- vals) assert(e.readOk(a) == v, f"odczyt $a%03x")
      assert(e.read(0x024)._1 == Status.BadAddr && e.read(0x045)._1 == Status.BadAddr)

      e.start()
      e.dutCd.waitSampling(10)
      val c = e.d.io.dutCfg
      assert(c.seed.toLong == 0xdeadbeefL && c.gapMode.toInt == 2 && c.gapEvery.toLong == 0x8000L &&
             c.gapLen.toLong == 3 && c.rstCount.toLong == 0x1234L && c.rstSeed.toLong == 0xcafe0001L &&
             c.rstMin.toLong == 0x10000L && c.rstMask.toLong == 0xffL && c.rstLen.toLong == 0x0f0fL,
             "konfiguracja w domenie dut")

      assert(e.write(Addr.GenSeed, 1) == Status.Busy, "zapis w biegu")
      assert(e.readOk(Addr.GenSeed) == 0xdeadbeefL)
      e.stop()
      assert(e.write(Addr.GenSeed, 1) == Status.Ok)
      assert(e.d.io.dutCfg.seed.toLong == 0xdeadbeefL, "domena dut zmienila sie bez startu")
    }

    scenario("side_capture") { e =>
      val d = e.d
      def entry(j : Int) : Seq[Long] = j.toLong +: (1 to 4).map(k => (j.toLong << 8) | k)

      def run(n : Int, trigAt : Option[Int]) : Unit = {
        e.start()
        e.dutCd.waitSampling(5)
        for (j <- 0 until n) {
          d.io.capPush.valid #= true
          entry(j).zipWithIndex.foreach { case (v, k) => d.io.capPush.payload(k) #= v }
          d.io.capTrig #= trigAt.contains(j)
          e.dutCd.waitSampling()
          d.io.capPush.valid #= false; d.io.capTrig #= false
          e.dutCd.waitSampling(20)
        }
        e.stop()
      }

      def check(n : Int, trigAt : Option[Int], expIdx : Seq[Int]) : Unit = {
        run(n, trigAt)
        val cnt = e.counter("cap_count")
        assert(cnt == expIdx.size, s"n=$n trig=$trigAt: cap_count $cnt")
        val got = (0 until cnt.toInt).map { i =>
          (0 until 8).map(j => e.readOk(Capture.Base + i * Capture.Stride + j))
        }
        for (g <- got) {
          assert(g.drop(5).forall(_ == 0), s"kolumny 5..7: $g")
          assert(g.take(5) == entry(g.head.toInt), s"wpis ${g.head}: $g")
        }
        assert(got.map(_.head.toInt).sorted == expIdx, s"n=$n trig=$trigAt: ${got.map(_.head).sorted}")
      }

      check(100, Some(40), 24 to 55)
      check(100, None, 68 to 99)
      check(10, None, 0 to 9)
      assert(e.write(Capture.Base, 1) == Status.BadOp)
      assert(e.counter("overflow") == 0)
    }

    scenario("side_reset_injector") { e =>
      val (seed, count, min, mask, len) = (0x1234L, 5, 40L, 31L, 7)
      e.write(Addr.RstCount, count); e.write(Addr.RstSeed, seed)
      e.write(Addr.RstMin, min); e.write(Addr.RstMask, mask); e.write(Addr.RstLen, len)
      e.resetCycles.clear()
      e.start()
      val sched = HilResetModel.schedule(seed, count, min, mask, len)
      e.dutCd.waitSampling((sched.last + len + 20).toInt)
      val t0 = e.dutStartAt
      val rel = e.resetCycles.map(_ - t0)
      val starts = rel.filter(c => !rel.contains(c - 1))
      assert(starts == sched, s"poczatki resetow $starts, model $sched")
      for (a <- sched) assert((0 until len).forall(k => rel.contains(a + k)) && !rel.contains(a + len),
                               s"reset od $a nie trwa $len cykli")
      e.stop()
      assert(e.counter("rst_done") == count)

      // Stop w trakcie serii: reset zwolniony, seria przerwana.
      e.write(Addr.RstCount, 100)
      e.resetCycles.clear()
      e.start()
      e.dutCd.waitSampling(200)
      e.stop()
      val afterStop = e.dutCycle
      e.dutCd.waitSampling(500)
      assert(e.resetCycles.forall(_ <= afterStop), "reset po stop")
      val done = e.counter("rst_done")
      assert(done > 0 && done < 100, s"rst_done $done")
    }
  }

  testpoint("side_param_bounds") {
    val ranges = Seq(
      "core"     -> (Addr.CoreBase, Addr.CoreSize),
      "counters" -> (Counters.Base, Counters.Size),
      "run"      -> (Addr.RunBase,  Addr.RunSize),
      "ip"       -> (Addr.IpBase,   Addr.IpSize),
      "capture"  -> (Capture.Base,  Capture.size)).sortBy(_._2._1)
    for (((a, (ba, sa)), (b, (bb, _))) <- ranges.zip(ranges.drop(1)))
      assert(ba + sa <= bb, s"$a nachodzi na $b")
    assert(ranges.last._2._1 + ranges.last._2._2 <= 0x10000)
    assert(Counters.names.size <= Counters.Size, "liczniki poza 0x010-0x01F")
    assert(Counters.names.size == Counters.fromIp + 2)
    assert((Capture.Base % Capture.size) == 0)
    for ((n, (b, s)) <- ranges) info(f"$n%-8s 0x$b%04x-0x${b + s - 1}%04x")

    // Reczny przyklad: seed 0 -> r1 = xorshift32(1) = 0x00042021.
    assert(HilRand.xorshift32(1) == 0x00042021L)
    val s = HilResetModel.schedule(0, 2, 10, 0, 3)
    assert(s == Seq(12L, 12L + 3 + 1 + 10), s"$s")
  }
}
