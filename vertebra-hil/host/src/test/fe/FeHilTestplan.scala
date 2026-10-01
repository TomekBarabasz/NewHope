package newhope.vertebra.hil.fe

import scala.util.Random
import newhope.vertebra.{Stage, Testpoint}
import newhope.vertebra.hil._
import newhope.frontend.DcGolden
import HilProtocol._

// =====================================================================
//  ZRODLO PLANU
//  vertebra-hil.md §12, contract/fe/commands.md. Frontend N0 + N1
//  (MicFrontEnd) na krzemie: FPGA master I2S jak w urzadzeniu (75 MHz /
//  73 / 64 = 16 053 Hz), ESP32 slave udaje INMP441. Testpointy hw_fe_*
//  w `checking` odsylaja do testpointow symulacyjnych z front_end, ktore
//  uzupelniaja (MicFrontEndTestplan, DcFilterTestplan).
//
//  Wyrocznia: DcGolden na PC, bit w bit, liczony z echa x - tego, co
//  filtr naprawde dostal (FeCheck). Bodziec wgrywa PC (`load`), wynik
//  ESP32 nagrywa w PSRAM i oddaje przez `rec`.
//
//  Uruchamianie (porty jak I2S, HilBench; firmware ESP32 z sdkconfig.fe,
//  bitstream FeHarnessTop_mimas):
//    sbt "hil/testOnly *FeHilTestplan"                                   bez plytek: canceled
//    sbt "hil/testOnly *FeHilTestplan -- -Desp_com=COM11 -Dfpga_com=COM12"
//    sbt "hil/testOnly *FeHilTestplan -- -z param"                       bez sprzetu
//    sbt "hil/testOnly *FeHilTestplan -- -Desp_com=COM11 -Dfpga_com=COM12 -Dsamples=20000"
//        krotszy bieg (domyslnie 100 000 probek, ok. 6 s na hw_fe_chain)
//    ... -Dlong=1   hw_fe_long: pelny bufor bodzca (262 144 probek, ok. 16 s)
// =====================================================================
object FeHilPlan {
  val plan = Seq(
    Testpoint("hw_param_bounds", Stage.V1,
      "Granice stanowiska FE policzone bez sprzetu",
      checking = Seq(
        "wariant legalny, DCM_CLKGEN daje dokladnie 75 MHz",
        "ESP32 slave: fs w zakresie cfg, 8 x BCLK <= 160 MHz (esp-idf #9513)",
        "linia `load` (29 slow) i `rec` (15 ramek) <= 255 znakow",
        "tail >= 2 bufory DMA ESP32 (ostatnie ramki wyniku dochodza do nagrania)",
        "kazdy scenariusz miesci sie w buforach PSRAM (bodziec, nagranie)")),

    Testpoint("hw_link", Stage.V1,
      "Lacze z obiema plytkami FE",
      stimulus = Seq("esp32: ver, bledne komendy, selftest (petla 32/32 + PSRAM), load/rec z bledami, 50 x ver",
                     "fpga: identyfikacja, scratch, klucze FE, bieg bez partnera"),
      checking = Seq("esp32: ip=fe; err 1, 2, 3, 4 z kodem; load: stim i stim_sum zgodne; brak resetu i obcych linii",
                     "fpga: ip_id FE, wariant mimas; busy w biegu; status.locked: DUT taktuje w biegu i staje po tail")),

    Testpoint("hw_fe_chain", Stage.V1,
      "INMP441 (ESP32) -> N0 -> N1 -> ESP32 na krzemie; uzupelnia fe_chain, dc_smoke",
      stimulus = Seq("mowa z offsetem DC, -Dsamples probek (domyslnie 100 000)"),
      checking = Seq("FeCheck: y == DcGolden(x) bit w bit, x == slowo >> 14 (N0), idx ciagly od 0",
                     "FPGA: frames == nagranie, x_sum / y_sum zgodne, overflow == dc_overrun == 0",
                     "ESP32: sent == bodziec, overflow == 0")),

    Testpoint("hw_fe_corners", Stage.V2,
      "Rogi zakresu na krzemie; uzupelnia dc_corners",
      stimulus = Seq("+-FS co probke, skok -FS -> +FS, losowe slowa 32 b; kazdy w osobnym biegu"),
      checking = Seq("jak hw_fe_chain dla kazdego bodzca (nasycenie zgodne z golden)")),

    Testpoint("hw_fe_removal", Stage.V2,
      "Offset mikrofonu znika, ton zostaje; uzupelnia dc_removal i dc_golden_vs_float",
      stimulus = Seq("ton 200 Hz, -40 dBFS, na offsecie +0,1 FS; 20 tau + 1000 probek"),
      checking = Seq("jak hw_fe_chain", "srednia ostatnich 1000 probek wyniku |.| < 1 LSB + reszta tonu",
                     "max |y - float| <= 1 LSB na krzemie")),

    Testpoint("hw_fe_bypass", Stage.V2,
      "Okno bypassu w srodku strumienia; uzupelnia fe_bypass, dc_bypass",
      stimulus = Seq("bypass_from = n/3, bypass_to = 2n/3"),
      checking = Seq("flaga bypass dokladnie w oknie, tam y == x, poza nim golden calego strumienia")),

    Testpoint("hw_fe_reset", Stage.V3,
      "Reset DUT-a w losowych chwilach w trakcie strumienia; uzupelnia dc_reset",
      stimulus = Seq("HilResetInjector: 10 resetow po 500 cykli dut co 20-76 ms"),
      checking = Seq("rst_done == 10, 10 flag rst w nagraniu",
                     "na kazdym odcinku y == DcGolden od stanu zerowego (I2sMicRx i filtr wracaja same)",
                     "przerwy w idx tylko tuz przed flaga rst, najwyzej 2 ramki na reset")),

    Testpoint("hw_fe_long", Stage.V3,
      "Pelny bufor bodzca (ok. 16 s); bez -Dlong=1 canceled",
      stimulus = Seq("262 144 probek: na zmiane mowa i losowe slowa"),
      checking = Seq("jak hw_fe_chain na calym biegu")))

  /** Nominal fs dla sterownika ESP32 (slave idzie za SCK FPGA). */
  def espFs(v : FeHilVariant) : Int = scala.math.round(v.fe.i2s.fs).toInt

  /** Pojemnosci PSRAM ESP32 (fe_role.c). */
  val StimCap = 256 * 1024
  val RecCap  = 640 * 1024
  /** Bufory DMA ESP32 (fe_role.c): 8 x 240 ramek. */
  val DmaFrames = 8 * 240
}

class FeHilTestplan extends HilSuite {
  import FeHilPlan._
  def testplan : Seq[Testpoint] = plan
  def hilIp : HilIp = FeHil

  override def suiteOptions : Set[String] = Set("samples", "long")

  def samples : Int = option("samples").map { v =>
    v.replace("_", "").toIntOption.filter(n => n >= 3000 && n <= StimCap)
      .getOrElse(fail(s"-Dsamples=$v: dozwolone 3000..$StimCap"))
  }.getOrElse(100000)

  val v = FeHilVariant.mimas

  // -------------------------------------------------------------------
  testpoint("hw_param_bounds") {
    assert(v.isLegal, v.problems.mkString("; "))
    val (m, d, hz) = DcmClkGen.best(HilBridgeGenerics().clkHz, v.dutHz)
    info(f"${v.name}: DCM $m/$d = ${hz / 1e6}%.4f MHz, fs ${v.fe.i2s.fs}%.2f Hz, BCLK ${v.fe.i2s.bclkHz / 1e6}%.4f MHz")
    assert(hz == v.dutHz.toDouble)
    assert(espFs(v) >= 8000 && espFs(v) <= 96000)
    assert(8 * v.fe.i2s.bclkHz <= 160e6, "ESP32 slave: 8 x BCLK > 160 MHz")
    val load = FeEsp.loadLines(Seq.fill(FeEsp.LoadWords * 2)(0xFFFFFFFFL)).map(_.length).max
    val longest = s"load off=${StimCap - 1} data=".length + 8 * FeEsp.LoadWords
    info(s"linia load: $longest znakow, rec: ${16 * FeEsp.RecLineFrames}")
    assert(longest <= EspDevice.LineMax && load <= EspDevice.LineMax && 16 * FeEsp.RecLineFrames <= EspDevice.LineMax)
    info(s"tail ${FeHilRegs.TailDefault} probek, bufory DMA ESP32 $DmaFrames ramek")
    assert(FeHilRegs.TailDefault >= 2 * 240, "tail krotszy niz dwa deskryptory DMA ESP32")
    for ((name, n) <- Seq("chain" -> samples, "long" -> StimCap, "reset" -> resetSamples, "bypass" -> bypassSamples))
      assert(n <= StimCap && n + 3 * DmaFrames + FeHilRegs.TailDefault <= RecCap, s"$name: $n probek nie miesci sie")
    // ten sam golden co MicFrontEndTestplan / DcFilterTestplan
    assert(v.fe.dc == newhope.frontend.FrontEndGenerics.mimas.dc)
    info(f"filtr: 1 - a = ${v.fe.dc.oneMinusA}%.6f, fc ${v.fe.dc.cutoffEff}%.2f Hz, tau ${v.fe.dc.tau}%.1f probek, " +
         f"|H(60 Hz)| ${DcGolden.gainDb(v.fe.dc, 60)}%.2f dB")
  }

  // -------------------------------------------------------------------
  hwScenario("hw_link", "esp32", needs = Set(HilSide.Esp)) { b =>
    val d = b.esp.get
    val (resets0, junk0) = (d.resets, d.junkLines)
    val i = d.info()
    assert(i == b.espInfo.get && i.dev == "esp32s3" && i.ip == "fe", s"$i")
    d.stop()
    assert(d.command("hw_link_bogus").left.map(_._1) == Left(1))
    assert(intercept[HilDeviceError](d.cfg("fss" -> 16053)).code == Some(2))
    d.cfg("fs" -> espFs(v), "rec" -> 1)

    HilProgress("esp32: selftest (petla 32/32, PSRAM)")
    val t0 = System.nanoTime
    assert(d.selftest() == 0, "FE nie ma wektorow kontraktu")
    info(f"selftest: ${(System.nanoTime - t0) / 1e9}%.1f s")

    val stim = FeStimulus.random(100, new Random(1))
    FeEsp.load(d, stim)
    assert(d.command("load off=7 data=00000000").left.map(_._1) == Left(3), "load z przerwa")
    assert(d.command("load off=0 data=0000000").left.map(_._1) == Left(3), "load: 7 cyfr")
    assert(d.command("load off=0 data=000000zz").left.map(_._1) == Left(3), "load: zly hex")
    assert(d.command("load of=0 data=00000000").left.map(_._1) == Left(2), "load: nieznany klucz")
    assert(d.stat().extra("stim") == "100", "bledny load zmienil bodziec")
    assert(d.command("rec off=0 n=1").left.map(_._1) == Left(3), "rec po selftescie: nagranie puste")
    d.start()
    assert(d.command("load off=0 data=00000000").left.map(_._1) == Left(4), "load w biegu")
    d.stop()

    val t1 = System.nanoTime
    for (_ <- 0 until 50) assert(d.info() == i)
    info(f"ver: ${(System.nanoTime - t1) / 50e6}%.2f ms na komende")
    assert(d.resets == resets0, "ESP32 zresetowal sie w trakcie testu (log ROM)")
    assert(d.junkLines == junk0, "linie spoza protokolu w trakcie testu")
  }

  hwScenario("hw_link", "fpga", needs = Set(HilSide.Fpga)) { b =>
    val d = b.fpga.get
    val retries0 = d.retries
    val i = d.info()
    assert(i == b.fpgaInfo.get && i.ip == "fe", s"$i")
    assert(FeFpgaMap.variant(i.variant.get).contains(v), s"wariant ${i.variant}")
    stopFpga(d)
    val rng = new Random(2)
    for (x <- Seq(0L, 0xFFFFFFFFL, 0xA5A5A5A5L, 0x5A5A5A5AL) ++ Seq.fill(100)(rng.nextLong() & 0xFFFFFFFFL)) {
      d.write(Addr.Scratch, x); assert(d.read(Addr.Scratch) == x, f"scratch $x%08x")
    }
    assert(d.retries == retries0, s"ponowienia na zdrowym laczu: ${d.retries - retries0}")
    d.cfg("bypass_from" -> 5, "bypass_to" -> 9, "tail" -> FeHilRegs.TailDefault)
    assert(d.read(FeHilRegs.BypassTo) == 9)
    assert(intercept[HilDeviceError](d.cfg("bypass_from" -> 10)).code == Some(3), "from > to")
    d.cfg("bypass_from" -> 0, "bypass_to" -> 0, "rst_count" -> 0)
    // bieg bez partnera: SD_IN nisko, DUT liczy zera
    d.start()
    Thread.sleep(100)
    assert(d.running() && dutClocking(d), "w biegu DUT taktuje (status.locked)")
    assert(intercept[HilDeviceError](d.write(FeHilRegs.Tail, 1)).code == Some(Status.Busy))
    stopFpga(d)
    val c = d.counters()
    info(s"bieg bez partnera: ${d.map.toStat(c)}")
    assert(c("frames") > 1000 && c("overflow") == 0 && c("dc_overrun") == 0, s"$c")
    assert(c("x_sum") == 0 && c("y_sum") == 0, "SD_IN w zerach: x = y = 0")
  }

  // -------------------------------------------------------------------
  //  Bieg
  // -------------------------------------------------------------------

  def dutClocking(d : FpgaDevice[HilFpgaMap]) : Boolean = (d.status() & (1L << StatusBit.Locked)) != 0

  /** Stop i czekanie, az DUT stanie (tail + granica ramki, ok. 32 ms). */
  def stopFpga(d : FpgaDevice[HilFpgaMap]) : Unit = {
    d.stop()
    val end = System.currentTimeMillis + 2000
    while (dutClocking(d))
      if (System.currentTimeMillis > end) fail("FPGA: DUT taktuje 2 s po stop (tail?)")
  }

  case class ResetPlan(count : Int, seed : Long, min : Long, mask : Long, len : Int)

  case class Run(report : FeCheck.Report, fpga : Map[String, Long], esp : HilStat, raw : Seq[(Long, Long)])

  /** Jeden bieg: bodziec przez ESP32 -> N0 -> N1 -> ESP32, ocena FeCheck. */
  def run(b : HilBench, what : String, stim : Seq[Long], bypass : (Long, Long) = (0, 0),
          rst : Option[ResetPlan] = None) : Run = {
    val (esp, fpga) = (b.esp.get, b.fpga.get)
    try {
      esp.stop(); stopFpga(fpga)
      val r = rst.getOrElse(ResetPlan(0, 0, 0, 0, 1))
      fpga.cfg("bypass_from" -> bypass._1, "bypass_to" -> bypass._2, "tail" -> FeHilRegs.TailDefault,
               "rst_count" -> r.count, "rst_seed" -> f"0x${r.seed}%08x", "rst_min" -> r.min,
               "rst_mask" -> r.mask, "rst_len" -> r.len)
      esp.cfg("fs" -> espFs(v), "rec" -> 1)
      val t0 = System.currentTimeMillis
      FeEsp.load(esp, stim)
      HilProgress(f"$what: bodziec ${stim.size} slow wgrany w ${(System.currentTimeMillis - t0) / 1000.0}%.1f s")

      // ESP32 slave czeka na zegar, potem FPGA (§3: najpierw strona bez zegara)
      esp.start(); fpga.start()
      val need  = stim.size.toLong + 2 * DmaFrames
      val limit = System.currentTimeMillis + need * 1000 / espFs(v) * 3 / 2 + 5000
      var st = esp.stat()
      var shown = 0L
      while (st.frames < need) {
        if (System.currentTimeMillis > limit) fail(s"$what: ESP32 odebral ${st.frames} z $need ramek; $st")
        Thread.sleep(scala.math.min(1000L, scala.math.max(50L, (need - st.frames) * 1000 / espFs(v))))
        st = esp.stat()
        if (System.currentTimeMillis - shown > 5000) { shown = System.currentTimeMillis; HilProgress(s"$what: ${st.frames} / $need ramek") }
      }
      stopFpga(fpga)
      esp.stop()
      val es = esp.stat()
      val fc = fpga.counters()
      info(s"$what: ESP32 $es")
      info(s"$what: FPGA ${fpga.map.toStat(fc)}")
      val nrec = es.extra.get("rec").flatMap(_.toLongOption).getOrElse(fail(s"stat bez rec: $es"))
      val t1 = System.currentTimeMillis
      val raw = FeEsp.record(esp, nrec)
      HilProgress(f"$what: nagranie $nrec ramek odczytane w ${(System.currentTimeMillis - t1) / 1000.0}%.1f s")

      val rep = FeCheck.analyze(v, stim, raw, bypass)
      info(s"$what: ${rep.summary}")
      rep.info.foreach(s => info(s"  $s"))
      if (!rep.ok) fail(s"$what: FeCheck:\n" + rep.errors.mkString("\n"))
      assert(es.sent == stim.size, s"ESP32 sent ${es.sent}, bodziec ${stim.size}")
      assert(es.overflow == 0, s"ESP32: przepelnienie DMA RX ${es.overflow}")
      assert(fc("overflow") == 0 && fc("dc_overrun") == 0, s"FPGA: $fc")
      assert(fc("sent") == fc("frames"), s"FPGA sent ${fc("sent")} != frames ${fc("frames")}")
      assert(fc("underrun") <= 4, s"FPGA underrun ${fc("underrun")}: wiecej niz rozbieg nadajnika powrotnego")
      assert(nrec < RecCap, "nagranie pelne: wynik niekompletny")
      assert(fc("frames") == rep.frames.size + rep.lost, s"FPGA frames ${fc("frames")}, nagranie ${rep.frames.size} + ${rep.lost}")
      if (rep.lost == 0)
        assert((fc("x_sum"), fc("y_sum")) == FeCheck.sums(rep.frames), "sumy FPGA != sumy nagrania")
      assert(rep.maxFloatErr <= 1.0, f"max |y - float| = ${rep.maxFloatErr}%.3f LSB")
      Run(rep, fc, es, raw)
    } finally {
      scala.util.Try(esp.stop()); scala.util.Try(fpga.stop())
    }
  }

  def fs : Double = v.fe.i2s.fs
  def resetSamples  : Int = scala.math.max(samples / 2, 20000)
  def bypassSamples : Int = scala.math.max(samples / 3, 3000)

  hwScenario("hw_fe_chain") { b =>
    val stim = FeStimulus.noisy(fs, samples, new Random(11))
    val r = run(b, "mowa", stim)
    assert(r.report.lead.isDefined && r.report.resets == 0)
  }

  hwScenario("hw_fe_corners") { b =>
    val n = scala.math.max(samples / 5, 3000)
    val rng = new Random(12)
    for ((name, stim) <- Seq("square" -> FeStimulus.square(n, rng), "step" -> FeStimulus.step(n, rng),
                             "random" -> FeStimulus.random(n, rng))) {
      val r = run(b, name, stim)
      info(s"$name: nasycen w golden ${DcGolden.run(v.fe.dc, r.report.xs).saturated}")
    }
  }

  hwScenario("hw_fe_removal") { b =>
    val n    = (20 * v.fe.dc.tau).toInt + 1000
    val stim = FeStimulus.tone(fs, n, 200, 0.01, 0.1, new Random(13))
    val r    = run(b, "ton 200 Hz + 0,1 FS", stim)
    val ys   = r.report.ys.slice(r.report.lead.get, r.report.lead.get + n)
    val tail = ys.takeRight(1000)
    val mean = tail.sum.toDouble / tail.size
    val residue = 0.01 * ((1L << (v.sampleWidth - 1)) - 1) * (fs / 200) / 1000 / scala.math.Pi
    info(f"srednia ostatnich 1000 probek $mean%.3f LSB (reszta tonu do $residue%.2f)")
    assert(scala.math.abs(mean) < 1 + residue, f"DC nie usuniety na krzemie: $mean%.3f LSB")
  }

  hwScenario("hw_fe_bypass") { b =>
    val n = bypassSamples
    val stim = FeStimulus.noisy(fs, n, new Random(14))
    val win = (n / 3L, 2L * n / 3)
    val r = run(b, s"bypass [${win._1}, ${win._2})", stim, bypass = win)
    assert(r.report.bypassed == win._2 - win._1, s"w bypassie ${r.report.bypassed} ramek")
  }

  hwScenario("hw_fe_reset") { b =>
    val stim = FeStimulus.noisy(fs, resetSamples, new Random(15))
    // 75 MHz: 1,5 M cykli = 20 ms, maska 2^22 - 1 = 56 ms; reset 500 cykli (6,7 us)
    val plan = ResetPlan(10, 0x5eedfe00L, 1500000L, 0x3FFFFFL, 500)
    val r = run(b, "10 resetow", stim, rst = Some(plan))
    assert(r.fpga("rst_done") == 10, s"rst_done ${r.fpga("rst_done")}")
    assert(r.report.resets == 10, s"flagi rst w nagraniu: ${r.report.resets}")
    assert(r.report.lost <= 2 * 10, s"zgubione ramki ${r.report.lost}")
  }

  hwScenario("hw_fe_long") { b =>
    assume(option("long").contains("1"), "hw_fe_long: -Dlong=1 (ok. 16 s biegu i odczyt 4 MB nagrania)")
    val rng = new Random(16)
    val stim = (0 until StimCap / 8192).flatMap(k =>
      if (k % 2 == 0) FeStimulus.noisy(fs, 8192, rng) else FeStimulus.random(8192, rng))
    run(b, s"${stim.size} probek", stim)
  }
}
