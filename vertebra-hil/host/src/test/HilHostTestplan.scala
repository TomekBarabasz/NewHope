package newhope.vertebra.hil

import newhope.vertebra.{Stage, Testpoint, TestplanSuite}
import newhope.vertebra.hil.i2s.{I2sHil, I2sHilTestplan, I2sHilVariant}
import org.scalatest.{Args, Reporter}
import org.scalatest.events.{Event, TestCanceled, TestFailed, TestSucceeded}
import HilProtocol._

// =====================================================================
//  Host to tez kod pod testem (vertebra-hil.md §1: "Harness to też IP").
//  Warstwy z §8 sprawdzone na atrapach plytek (HilFakes), bez stanowiska:
//  ramki, ponowienia, parsowanie odpowiedzi, sprawdzenie wersji. Na
//  plytce to samo przechodzi hw_link (I2sHilTestplan).
// =====================================================================

object HilHostPlan {
  val plan = Seq(
    Testpoint("host_fpga_regs", Stage.V1,
      "FpgaDevice: identyfikacja, zapis i odczyt, statusy mostu",
      checking = Seq("info: magic, ip, build z flaga dirty, wariant",
                     "scratch: wartosci z bajtami A5/5A wracaja bez zmian",
                     "BadAddr / BadOp / Busy -> HilDeviceError z kodem statusu")),
    Testpoint("host_fpga_retry", Stage.V1,
      "FpgaDevice: ponowienia wedlug contract/commands.md",
      checking = Seq("zgubiona odpowiedz, zla suma w odpowiedzi i w zadaniu -> jedna powtorka",
                     "smieci przed 5A sa pomijane", "trwala cisza -> blad po 3 probach")),
    Testpoint("host_fpga_ctrl", Stage.V1,
      "FpgaDevice: start/stop/stat/dump",
      checking = Seq("zgubiona odpowiedz na ctrl: status zamiast drugiego impulsu",
                     "odrzucony ctrl: dokladnie jedna powtorka",
                     "stat przed stop -> blad; po stop: lock_at -1, first_err tylko przy bad > 0",
                     "dump: cap_count wpisow z bufora capture")),
    Testpoint("host_fpga_cfg", Stage.V1,
      "FpgaDevice.cfg: klucze -> rejestry, calosc albo nic",
      checking = Seq("klucze wspolne i I2S trafiaja pod adresy z HilProtocol / I2sHilRegs",
                     "nieznany klucz, wartosc spoza zakresu, polaczenie nie-checkable: blad bez zadnego zapisu")),
    Testpoint("host_esp_protocol", Stage.V1,
      "EspDevice: linie odpowiedzi",
      checking = Seq("ver, stat (first_err, peer_*), dump, selftest",
                     "linie '#' i log ROM pomijane; ESP-ROM liczone jako reset",
                     "err -> HilDeviceError z kodem; brak odpowiedzi -> timeout")),
    Testpoint("host_bench", Stage.V1,
      "HilBench: porty, wersje, brak stanowiska",
      checking = Seq("bez portow -> Right(None) (testy hw_* canceled)",
                     "opcja suity wygrywa ze zmienna srodowiska",
                     "ten sam port dwa razy -> Left",
                     "zly proto, zle IP, nieaktualny build -> blad tej plytki, druga dziala; allow_stale -> ostrzezenie",
                     "HilGit: commit nieznany, zmienione zrodla, brak hasha; dirty: tylko zmiany zacommitowane od builda")),
    Testpoint("host_hw_link_on_fakes", Stage.V1,
      "Cialo hw_link (I2sHilTestplan) na atrapach obu plytek",
      stimulus = Seq("I2sHilTestplan z HilBench zlozonym z FakeEsp i FakeFpga"),
      checking = Seq("hw_link (esp32) i hw_link (fpga) przechodza: test sprzetowy sam jest sprawdzony, " +
                     "zanim pierwszy raz zobaczy plytke")))
}

class HilHostTestplan extends TestplanSuite {
  def testplan : Seq[Testpoint] = HilHostPlan.plan

  val v = I2sHilVariant.v16_32

  def fpga(build : Long = 0x12345678L) : (FakeFpga, FpgaDevice[HilFpgaMap]) = {
    val f = new FakeFpga(v.code, build)
    (f, new FpgaDevice[HilFpgaMap](new HilLink(f.port, "fpga", text = false), I2sHil.fpgaMap))
  }

  def esp(respond : String => Seq[String]) : (FakeEsp, EspDevice) = {
    val e = new FakeEsp(respond)
    (e, new EspDevice(new HilLink(e.port, "esp32", text = true)))
  }

  def code[T](body : => T) : Option[Int] =
    intercept[HilDeviceError](body).code

  testpoint("host_fpga_regs") {
    val (_, d) = fpga(0x12345679L)
    val i = d.info()
    assert(i == HilInfo(1, "fpga", "i2s", "12345678-dirty", Some(v.code)), s"$i")
    assert(fpga(0xabcdef00L)._2.info().build == "abcdef00")
    for (x <- Seq(0L, 0xFFFFFFFFL, 0xA5A5A5A5L, 0x5A5A5A5AL, 0xA55A00FFL, 0x80000001L)) {
      d.write(Addr.Scratch, x)
      assert(d.read(Addr.Scratch) == x, f"scratch $x%08x")
    }
    assert(code(d.read(0x0FF)) == Some(Status.BadAddr))
    assert(code(d.write(Addr.Magic, 1)) == Some(Status.BadOp))
    d.start()
    assert(code(d.write(Addr.GenSeed, 1)) == Some(Status.Busy))
    assert(d.retries == 0)
  }

  testpoint("host_fpga_retry") {
    val (f, d) = fpga()
    f.rw(Addr.Scratch) = 0x11223344L
    f.dropResponses = 1
    assert(d.read(Addr.Scratch) == 0x11223344L && d.retries == 1)
    f.corruptResponses = 1
    assert(d.read(Addr.Scratch) == 0x11223344L && d.retries == 2)
    f.corruptRequests = 1
    d.write(Addr.Scratch, 7)
    assert(f.rw(Addr.Scratch) == 7 && d.retries == 3)
    f.junkBeforeRsp = Seq(0x00, 0xA5, 0x13)
    assert(d.read(Addr.Scratch) == 7 && d.skipped == 3 && d.retries == 3)
    f.dropResponses = 10
    val e = intercept[HilDeviceError](d.read(Addr.Scratch))
    assert(e.code.isEmpty && e.getMessage.contains("3 probach"), e.getMessage)
  }

  testpoint("host_fpga_ctrl") {
    val (f, d) = fpga()
    f.dropResponses = 1
    d.start()
    assert(f.running && f.ctrlPulses == Seq(CtrlBit.Start), s"${f.ctrlPulses}")
    assert(code(d.stat()) == Some(4), "stat w biegu")
    f.corruptRequests = 1
    f.counters ++= Seq("sent" -> 100, "frames" -> 97, "rst_done" -> 2)
    d.stop()
    assert(!f.running && f.ctrlPulses == Seq(CtrlBit.Start, CtrlBit.Stop), s"${f.ctrlPulses}")
    val s = d.stat()
    assert(s == HilStat(100, 97, 0, 0, 0, -1, None, 0, Map("cap_count" -> "0", "rst_done" -> "2")), s"$s")
    assert(!s.locked && !s.isClean(1))
    f.counters ++= Seq("bad" -> 1, "lock_at" -> 3, "err_n" -> 40, "err_got_l" -> 1, "err_got_r" -> 2,
                       "err_exp_l" -> 3, "err_exp_r" -> 4, "cap_count" -> 2)
    f.capture = Seq(Seq(39L, 5, 6, 5, 6), Seq(40L, 1, 2, 3, 4))
    val s2 = d.stat()
    assert(s2.lockAt == 3 && s2.firstErr == Some(HilErr(40, 1, 2, 3, 4)), s"$s2")
    assert(d.dump() == Seq(HilDumpEntry(39, 5, 6, 5, 6), HilDumpEntry(40, 1, 2, 3, 4)))
    d.softReset()
    assert(f.ctrlPulses.last == CtrlBit.SoftReset && d.retries == 1)
  }

  testpoint("host_fpga_cfg") {
    val (f, d) = fpga()
    d.cfg("role" -> "master", "peer_w" -> 24, "slot" -> 32, "seed" -> "0x5eed1234",
          "gap_mode" -> 1, "gap_every" -> 9, "gap_len" -> 2, "rst_count" -> 3, "rst_len" -> 100)
    assert(f.writes.toMap == Map(0x100 -> 1L, 0x101 -> 24L, 0x102 -> 32L, Addr.GenSeed -> 0x5eed1234L,
      Addr.GapMode -> 1L, Addr.GapEvery -> 9L, Addr.GapLen -> 2L, Addr.RstCount -> 3L, Addr.RstLen -> 100L))
    f.writes.clear()
    assert(code(d.cfg("role" -> "master", "rol" -> "slave")) == Some(2))
    assert(code(d.cfg("gap_mode" -> 3)) == Some(3))
    assert(code(d.cfg("role" -> "boss")) == Some(3))
    assert(code(d.cfg("seed" -> 1, "seed" -> 2)) == Some(3))
    // Wtx = 32 widziane przez slot 8: brak calego pola seq
    assert(code(d.cfg("peer_w" -> 32, "slot" -> 8)) == Some(3))
    assert(f.writes.isEmpty, s"${f.writes}")
    d.start()
    assert(code(d.cfg("seed" -> 1)) == Some(Status.Busy))
  }

  testpoint("host_esp_protocol") {
    val stat = "sent=1920 frames=1566240 bad=1 gaps=0 relocks=0 lock_at=0 " +
               "first_err=150:0000a1b2:8000a1b3:0000a1b2:8000a1b2 overflow=0 peer_sent=5 peer_frames=4 peer_first_err=-"
    val base = FakeEsp.healthy("98bcdf30", stat)
    val (e, d) = esp {
      case "dump" => Seq("ok n=2", "0 00000001 00000002 00000001 00000002", "# log w srodku dumpu",
                         "1 00000003 00000004 00000003 00000005", "ok end")
      case "ver"  => Seq("ESP-ROM:esp32s3-20210327", "rst:0x15 (USB_UART_CHIP_RESET)", "# log") ++ base("ver")
      case l      => base(l)
    }
    assert(d.info() == HilInfo(1, "esp32s3", "i2s", "98bcdf30"))
    assert(d.resets == 1 && d.junkLines == 1)
    val s = d.stat()
    assert(s.frames == 1566240 && s.bad == 1 && s.lockAt == 0 &&
           s.firstErr == Some(HilErr(150, 0xa1b2, 0x8000a1b3L, 0xa1b2, 0x8000a1b2L)), s"$s")
    assert(s.extra == Map("peer_sent" -> "5", "peer_frames" -> "4", "peer_first_err" -> "-"), s"${s.extra}")
    assert(d.dump() == Seq(HilDumpEntry(0, 1, 2, 1, 2), HilDumpEntry(1, 3, 4, 3, 5)))
    assert(d.selftest() == 702)
    d.cfg("role" -> "slave", "fs" -> 48000, "seed" -> "0x5eed1234")
    assert(e.received.last == "cfg role=slave fs=48000 seed=0x5eed1234")
    assert(code(d.cfg("rol" -> "slave")) == Some(2))
    assert(code(d.cmd("bogus")) == Some(1))
    assert(d.command("bogus") == Left(1 -> "nieznana komenda 'bogus'"))
    intercept[IllegalArgumentException](d.cfg("role" -> "a b"))

    val (_, mute) = esp(_ => Nil)
    val t = intercept[HilDeviceError](mute.cmd("ver", timeoutMs = 50))
    assert(t.code.isEmpty && t.getMessage.contains("brak odpowiedzi"), t.getMessage)
    // Uszkodzony stat to blad, a nie zera
    val (_, broken) = esp { case "stat" => Seq("ok sent=1 frames=x"); case _ => Nil }
    intercept[HilDeviceError](broken.stat())
  }

  // ---------------------------------------------------------------------
  def head : Option[String] = HilGit.toplevel.flatMap(_ =>
    scala.util.Try(scala.sys.process.Process(Seq("git", "rev-parse", "--short=8", "HEAD")).!!.trim).toOption)

  /** Opener z atrapami: nazwa portu -> port. */
  def opener(ports : Map[String, HilPort]) : HilBench.Opener =
    (name, _, _) => ports.get(name).toRight(s"nie ma portu '$name'")

  testpoint("host_bench") {
    val none = HilBenchOpts.from(Map.empty, Map.empty)
    assert(none == HilBenchOpts(None, None, allowStale = false))
    assert(HilBench.open(I2sHil, none, opener(Map.empty)) == Right(None))

    val o = HilBenchOpts.from(Map("esp_com" -> "COM11", "allow_stale" -> "1"),
                              Map("VERTEBRA_HIL_ESP" -> "COM3", "VERTEBRA_HIL_FPGA" -> "COM12"))
    assert(o == HilBenchOpts(Some("COM11" -> "-Desp_com"), Some("COM12" -> "VERTEBRA_HIL_FPGA"), allowStale = true))
    assert(HilBenchOpts.unknown(Map("esp_com" -> "x", "allow-stale" -> "1")) == Seq("allow-stale"))
    assert(o.describe == "esp=COM11 z -Desp_com, fpga=COM12 z VERTEBRA_HIL_FPGA, allow_stale=tak", o.describe)

    val dup = HilBenchOpts(Some("COM5" -> "a"), Some("com5" -> "b"), allowStale = false)
    assert(HilBench.open(I2sHil, dup, opener(Map.empty)).left.exists(_.contains("ten sam port")))
    val missing = HilBenchOpts(None, Some("COM9" -> "x"), allowStale = false)
    val m = HilBench.open(I2sHil, missing, opener(Map.empty)).toOption.flatten.getOrElse(fail("brak stanowiska?"))
    assert(m.fpgaError.exists(_.contains("nie ma portu")) && m.espError.isEmpty && m.fpga.isEmpty, s"${m.fpgaError}")

    assume(head.isDefined, "bez gita nie da sie sprawdzic build")
    val h   = head.get
    val hb  = java.lang.Long.parseLong(h, 16) & ~1L
    val opts = HilBenchOpts(Some("E" -> "t"), Some("F" -> "t"), allowStale = false)

    def run(espBuild : String, fpgaBuild : Long, ipId : String = "I2S", allow : Boolean = false,
            espProto : Int = 1) = {
      val e = new FakeEsp({
        case "ver" => Seq(s"ok proto=$espProto dev=esp32s3 ip=i2s build=$espBuild")
        case _     => Seq("ok")
      })
      val f = new FakeFpga(v.code, fpgaBuild, ipId)
      (HilBench.open(I2sHil, opts.copy(allowStale = allow), opener(Map("E" -> e.port, "F" -> f.port))), e, f)
    }

    // Commit HEAD: zrodla plytek moga byc zmienione lokalnie, wtedy Stale.
    val (ok, e0, f0) = run(h, hb)
    val fresh = Seq(I2sHil.espSources, I2sHil.fpgaSources).forall(s => HilGit.staleness(h, s) == HilGit.Fresh)
    if (fresh) {
      val b = ok.toOption.flatten.getOrElse(fail(s"$ok"))
      assert(b.warnings.isEmpty, s"${b.warnings}")
      assert(b.describe.contains("[v16_32]"), b.describe)
      b.close()
      assert(e0.port.closed && f0.port.closed)
    } else info("zrodla plytek zmienione wzgledem HEAD: sciezka Fresh pominieta")

    def benchOf(r : Either[String, Option[HilBench]]) : HilBench = r.toOption.flatten.getOrElse(fail(s"$r"))
    val i2c = benchOf(run(h, hb, ipId = "I2C")._1)
    assert(i2c.fpgaError.exists(_.contains("'i2c'")) && i2c.fpga.isEmpty, s"${i2c.fpgaError}")
    val (bad0, e1, f1) = run(h, hb, espProto = 2, allow = true)
    val bad = benchOf(bad0)
    assert(bad.espError.exists(_.contains("protokol 2")) && bad.esp.isEmpty, s"${bad.espError}")
    // Plytki sa niezalezne: zle ESP32 nie blokuje FPGA
    assert(bad.fpga.isDefined && bad.fpgaError.isEmpty && !f1.port.closed)
    bad.close()
    assert(e1.port.closed, "port zamkniety po bledzie")

    val (stale, _, _) = run("deadbee0", hb)
    assert(benchOf(stale).espError.exists(e => e.contains("nieznany") && e.contains("allow_stale")), s"$stale")
    val (allowed, _, _) = run("deadbee0", hb, allow = true)
    assert(benchOf(allowed).esp.isDefined && benchOf(allowed).warnings.exists(_.contains("dopuszczone")), s"$allowed")

    // HilGit wprost
    assert(HilGit.staleness("00000000", Seq("x")).isInstanceOf[HilGit.Unknown])
    assert(HilGit.staleness("xyz", Seq("x")).isInstanceOf[HilGit.Unknown])
    // dirty na HEAD: lokalne zmiany sa (zapewne) na plytce, commitow od tego czasu brak
    assert(HilGit.staleness(h + "-dirty", Seq("vertebra-hil")) match {
      case HilGit.Unknown(w) => w.contains("niezacommitowanymi")
      case _                 => false
    })
    val root = scala.util.Try(scala.sys.process.Process(Seq("git", "rev-list", "--max-parents=0", "HEAD")).!!
                 .trim.split('\n').head.take(8)).toOption
    root.filter(_ != h).foreach { r =>
      assert(HilGit.staleness(r, Seq("vertebra-hil")).isInstanceOf[HilGit.Stale], s"root $r")
      // dirty na starym commicie: zmiany zacommitowane od tego czasu to nadal Stale
      assert(HilGit.staleness(r + "-dirty", Seq("vertebra-hil")).isInstanceOf[HilGit.Stale], s"root $r-dirty")
    }
  }

  testpoint("host_hw_link_on_fakes") {
    val h = head.getOrElse("00000000")
    val e = new FakeEsp(FakeEsp.healthy(h, vectors = I2sHil.selftestVectors))
    val f = new FakeFpga(v.code, java.lang.Long.parseLong(h, 16) & ~1L)
    // allow_stale: lokalne zmiany w zrodlach plytek nie sa tu tematem
    val b = HilBench.open(I2sHil, HilBenchOpts(Some("E" -> "t"), Some("F" -> "t"), allowStale = true),
                          opener(Map("E" -> e.port, "F" -> f.port)))
    val suite = new I2sHilTestplan { override def bench = b }
    val events = scala.collection.mutable.ArrayBuffer[Event]()
    val rep = new Reporter { def apply(ev : Event) : Unit = synchronized { events += ev } }
    suite.run(None, Args(rep))
    def result(name : String) = events.collect {
      case x : TestSucceeded if x.testName.contains(name) => "ok"
      case x : TestFailed    if x.testName.contains(name) => s"FAILED: ${x.message}"
      case x : TestCanceled  if x.testName.contains(name) => s"canceled: ${x.message}"
    }
    for (n <- Seq("hw_link (esp32)", "hw_link (fpga)", "hw_param_bounds")) {
      val r = result(n)
      info(s"$n: ${r.mkString}")
      assert(r == Seq("ok"), s"$n: $r")
    }
    assert(f.ctrlPulses.nonEmpty && !f.running)
    b.foreach(_.foreach(_.close()))
  }
}
