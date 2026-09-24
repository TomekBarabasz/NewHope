package newhope.vertebra.hil.i2s

import newhope.vertebra.{Stage, Testpoint}
import newhope.vertebra.hil._
import HilProtocol._
import scala.util.chaining._

// =====================================================================
//  ZRODLO PLANU
//  vertebra-hil.md §8 ("Plan I2S"), §10 etapy 4-5. Testy sprzetowe:
//  prefiks hw_, w `checking` odsylaja do testpointu symulacyjnego, ktory
//  uzupelniaja.
//
//  Plan rosnie z etapami. Testpointy z §8 dochodza do planu w etapie,
//  ktory je robi (5: hw_slv_*, hw_la_crosscheck; 6: hw_mst_*, reszta V2;
//  7: V3), a nie wczesniej: `testplan completeness` wymaga kompletnego
//  V1, wiec wpisanie hw_mst_rx_frame juz teraz zrobiloby `sbt test`
//  czerwonym bez stanowiska.
//
//  Uruchamianie (porty: vertebra-hil.md §8, HilBench):
//    sbt "hil/testOnly *I2sHilTestplan"                                   bez plytek: canceled
//    sbt "hil/testOnly *I2sHilTestplan -- -Desp_com=COM11 -Dfpga_com=COM12"
//    sbt "hil/testOnly *I2sHilTestplan -- -z param"                       bez sprzetu
//    sbt "hil/testOnly *I2sHilTestplan -- -Desp_com=COM11 -Dfpga_com=COM12 -Dframes=100000"
//        krotszy bieg hw_slv_* / hw_mst_* (domyslnie 10^6 ramek)
//
//  Scenariusze end-to-end: jedna konfiguracja, ta z I2sBenchCfg, ktora
//  pasuje do wgranego wariantu (ESP32 z ta sama szerokoscia slowa).
//  Kolejnosc jak w §3: start odbiornika przed nadawca, a stop odbiornika
//  przed nadawca, zeby migawka byla z ciaglego strumienia, a nie z ciszy
//  przy wylaczaniu. Obie strony najpierw w stop (SCK/WS w Z, §9).
// =====================================================================

object I2sHilPlan {
  val plan = Seq(
    Testpoint("hw_param_bounds", Stage.V1,
      "Granice stanowiska policzone bez sprzetu",
      checking = Seq(
        "most: baud i timeout legalne, timeout odpowiedzi hosta > porzucenie ramki przez most",
        "DCM_CLKGEN: M/D w zakresie, zegar dut w granicach 0,1 % od nominalu wariantu",
        "FPGA master -> ESP32 slave: 8 x BCLK <= 160 MHz (esp-idf #9513)",
        "ESP32 master -> FPGA slave: polokres SCK przy realnym zegarze dut, pomniejszony " +
          "o okres PLL ESP32 (jitter dzielnika ulamkowego), spelnia supportsSckHalf",
        "kazda konfiguracja z listy: klucze cfg w zakresach obu plytek, oba kierunki checkable",
        "wypisuje widoczne bity hasha (granica wykrywalnosci, vertebra-hil.md §4)")),

    Testpoint("hw_link", Stage.V1,
      "Lacze z obiema plytkami: wersje, protokol, selftest",
      stimulus = Seq(
        "esp32: ver, bledne komendy, selftest, 50 x ver",
        "fpga: rejestry identyfikacji, scratch (wartosci A5/5A + losowe), ramki uszkodzone, " +
          "niedokonczone i nieznane, bieg bez partnera (start/stop)"),
      checking = Seq(
        "esp32: proto/dev/ip/build jak przy otwarciu; err 1 i err 2 z kodem; selftest == wektory kontraktu",
        "esp32: brak resetu (log ROM) i obcych linii w trakcie testu",
        "fpga: magic, proto, ip_id, wariant; scratch wraca bez zmian bez jednego ponowienia",
        "fpga: statusy bad_sum, bad_addr, bad_op, busy; most porzuca niedokonczona ramke po 10 ms",
        "fpga: stop daje migawke, stat sie parsuje")),

    Testpoint("hw_slv_rx_frame", Stage.V1,
      "ESP32 master nadaje wzorzec, FPGA slave (DUT) odbiera; uzupelnia slv_rx_frame",
      stimulus = Seq("FPGA role=slave, ESP32 role=master tx=1 rx=0, konfiguracja wariantu",
                     "-Dframes ramek (domyslnie 10^6: ok. 21 s przy 48 kHz)"),
      checking = Seq("checker FPGA: lock, frames >= -Dframes, bad = gaps = relocks = overflow = 0",
                     "przy bledzie: capture FPGA zdekodowany wzorcem (I2sDiag)")),

    Testpoint("hw_slv_tx_frame", Stage.V1,
      "FPGA slave (DUT) nadaje wzorzec, ESP32 master odbiera; uzupelnia slv_tx_frame",
      stimulus = Seq("FPGA role=slave, ESP32 role=master tx=0 rx=1, konfiguracja wariantu",
                     "-Dframes ramek"),
      checking = Seq("checker ESP32: lock, frames >= -Dframes, bad = gaps = relocks = overflow = 0",
                     "0 <= sent FPGA - frames ESP32 <= bufory DMA ESP32 z zapasem",
                     "przy bledzie: dump ESP32 zdekodowany wzorcem (I2sDiag)")),

    Testpoint("hw_mst_rx_frame", Stage.V1,
      "ESP32 slave nadaje wzorzec, FPGA master (DUT) odbiera; uzupelnia i2s_rx_frame",
      stimulus = Seq("FPGA role=master (SCK/WS z DCM), ESP32 role=slave tx=1 rx=0", "-Dframes ramek"),
      checking = Seq("checker FPGA: lock, frames >= -Dframes, bad = gaps = relocks = overflow = 0",
                     "ESP32 slave z obcym zegarem nadaje bez przesuniecia kanalow (esp-idf #9513, §11)")),

    Testpoint("hw_mst_tx_frame", Stage.V1,
      "FPGA master (DUT) nadaje wzorzec, ESP32 slave odbiera; uzupelnia i2s_tx_frame",
      stimulus = Seq("FPGA role=master, ESP32 role=slave tx=0 rx=1", "-Dframes ramek"),
      checking = Seq("checker ESP32: lock, frames >= -Dframes, bad = gaps = relocks = overflow = 0",
                     "0 <= sent FPGA - frames ESP32 <= bufory DMA ESP32 z zapasem")),

    Testpoint("hw_full_duplex", Stage.V2,
      "Obie strony nadaja i sprawdzaja naraz; uzupelnia *_full_duplex",
      stimulus = Seq("FPGA slave i FPGA master; ESP32 tx=1 rx=1, -Dframes_v2 ramek",
                     "start slave przed masterem, stop master pierwszy (zegar staje dla obu kierunkow)"),
      checking = Seq("oba checkery: lock, frames >= -Dframes_v2, bad = gaps = relocks = overflow = 0")),

    Testpoint("hw_padding", Stage.V2,
      "Slowo DUT-a w slocie 32; uzupelnia *_padding",
      stimulus = Seq("FPGA slave, ESP32 master: w = width wariantu, slot 32, oba kierunki"),
      checking = Seq("oba kierunki czyste; wariant v32_32: canceled (brak paddingu)")),

    Testpoint("hw_lsb_across_ws", Stage.V2,
      "Slot rowny slowu: LSB tuz przed zmiana WS; uzupelnia *_lsb_across_ws",
      stimulus = Seq("FPGA slave, ESP32 master: slot = w = width wariantu, oba kierunki"),
      checking = Seq("oba kierunki czyste")),

    Testpoint("hw_word_length_mismatch", Stage.V2,
      "ESP32 z inna szerokoscia slowa niz DUT; uzupelnia *_word_length_mismatch",
      stimulus = Seq("ESP32 w = 24 (DUT 16), 16 (DUT 24/32) albo 8 (slot 16); obie role, oba kierunki"),
      checking = Seq("porownanie przez transfer(): wszystkie cztery biegi czyste")),

    Testpoint("hw_tx_underrun", Stage.V2,
      "Luki generatora FPGA na magistrali; uzupelnia *_tx_underrun",
      stimulus = Seq("gap_mode 1: 2 ramki ciszy co 64, DUT nadaje, ESP32 sprawdza; obie role"),
      checking = Seq("ESP32: lock, bad = relocks = overflow = 0", "gaps ~ frames * 2 / 64 (+- 4)")),

    Testpoint("hw_fs_fractional", Stage.V2,
      "ESP32 master na 44,1 kHz z dzielnika ulamkowego; uzupelnia slv_sck_jitter",
      stimulus = Seq("FPGA slave, ESP32 master fs 44100, w/slot wariantu, oba kierunki"),
      checking = Seq("oba kierunki czyste mimo jittera BCLK")),

    Testpoint("hw_la_crosscheck", Stage.V2,
      "Logic analyzer zgadza sie z obiema plytkami",
      stimulus = Seq("nagranie sigrok linii SCK/WS/SD w trakcie hw_slv_*"),
      checking = Seq("SigrokI2sCheck na nagraniu: lock, zero bledow, ten sam wzorzec co liczniki plytek")))

  val seed = 0x5eed1234L
}

class I2sHilTestplan extends HilSuite {
  import I2sHilPlan._
  def testplan : Seq[Testpoint] = plan
  def hilIp : HilIp = I2sHil

  override def suiteOptions : Set[String] = Set("frames", "frames_v2")

  private def frameOpt(k : String, dflt : Long) : Long = option(k).map { v =>
    v.replace("_", "").toLongOption.filter(_ > 0).getOrElse(fail(s"-D$k=$v: to nie liczba > 0"))
  }.getOrElse(dflt)

  /** Ramki biegu V1 hw_slv_* / hw_mst_* (kryterium etapow 5-6: 10^6). */
  def runFrames : Long = frameOpt("frames", 1000000L)
  /** Ramki biegu V2: krotszy, bo scenariuszy jest kilkanascie na wariant. */
  def v2Frames : Long = frameOpt("frames_v2", 200000L)

  /** Takt PLL ESP32-S3 (bez APLL, vertebra-hil.md §7). */
  val espPllHz = 160e6

  testpoint("hw_param_bounds") {
    val bg = HilBridgeGenerics()
    assert(bg.isLegal, s"$bg")
    val rspMs = RspBytes * bg.byteCycles * 1000.0 / bg.clkHz
    info(f"most: ${bg.baud} Bd (${bg.baudErrorPct}%+.2f %%), odpowiedz $rspMs%.2f ms, porzucenie ramki ${bg.timeoutUs / 1000} ms")
    assert(FpgaDevice.RspTimeoutMs > 10 * rspMs && FpgaDevice.RspTimeoutMs > 2 * FpgaDevice.BridgeTimeoutMs)
    assert(FpgaDevice.BridgeTimeoutMs == bg.timeoutUs / 1000)

    // Zegar dut kazdego wariantu: to, co naprawde da DCM ze 100 MHz.
    val dutReal = I2sHilVariant.all.map { v =>
      val (m, d, hz) = DcmClkGen.best(bg.clkHz, v.dutHz)
      val dev = (hz - v.dutHz) / v.dutHz
      val fs  = hz / (4.0 * v.halfDiv * v.slotWidth)
      val bclk = fs * 2 * v.slotWidth
      info(f"${v.name}: DCM M/D = $m/$d -> ${hz / 1e6}%.4f MHz (${dev * 100}%+.4f %%), fs ${fs}%.1f Hz, " +
           f"BCLK FPGA mastera ${bclk / 1e6}%.4f MHz")
      assert(m >= 2 && m <= 256 && d >= 1 && d <= 256)
      assert(scala.math.abs(dev) <= 0.001, s"${v.name}: DCM dalej niz 0,1 % od nominalu")
      assert(8 * bclk <= espPllHz, s"${v.name}: ESP32 slave potrzebuje 8 x BCLK <= 160 MHz (#9513)")
      v.name -> hz
    }.toMap

    var blind = Seq.empty[String]
    val v2 = I2sHilVariant.all.flatMap(I2sBenchCfg.v2)
    for ((c, masterOk) <- I2sBenchCfg.all.map(_ -> true) ++ v2) {
      val v = c.v
      // ESP32: zakresy z contract/i2s/commands.md
      assert(Set(8, 16, 24, 32)(c.espW) && Set(8, 16, 24, 32)(c.espSlot) && c.espSlot >= c.espW, c.name)
      assert(c.espFs >= 8000 && c.espFs <= 96000, c.name)
      if (masterOk) assert(c.espSlot == v.slotWidth, s"${c.name}: ESP32 jako slave musi miec slot mastera FPGA")
      // FPGA: te same wartosci przechodza przez klucze cfg
      for ((k, x) <- Seq("peer_w" -> c.espW, "slot" -> c.espSlot))
        assert(I2sFpgaMap.keys(k).parse(x.toString).isRight, s"${c.name}: $k=$x")

      // ESP32 master -> FPGA slave: polokres SCK w cyklach dut, minus
      // jeden okres PLL ESP32 (dzielnik ulamkowy skraca pojedyncze polokresy).
      val hz     = dutReal(v.name)
      val half   = BigDecimal(hz / (c.espFs.toDouble * 2 * c.espSlot * 2))
      val worst  = half - BigDecimal(hz / espPllHz)
      info(f"${c.name}: FPGA slave, polokres SCK ${half.toDouble}%.2f cykli dut (najgorzej ${worst.toDouble}%.2f), " +
           s"wymagane > ${v.slaveG.txLatencyCycles}")
      assert(v.slaveG.supportsSckHalf(worst), s"${c.name}: slave nie nadazy za SCK ESP32")

      for (master <- Seq(true, false) if master == false || masterOk; (dir, l) <- Seq("->esp32" -> c.linkToEsp(seed, master),
                                                       "->fpga"  -> c.linkToFpga(seed, master))) {
        val role = if (master) "FPGA master" else "FPGA slave"
        assert(l.checkable, s"${c.name} $role $dir: ${l.label} nie jest checkable")
        if (l.visibleHashBits == 0) blind :+= s"${c.name} $role $dir"
        if (master || !masterOk) info(s"  $dir ${l.label}: widoczne bity hasha ${l.visibleHashBits}")
      }
    }
    info(if (blind.isEmpty) "wszystkie polaczenia widza czesc hasha"
         else s"tylko pole seq (przesuniecie o bit slabo wykrywalne): ${blind.mkString(", ")}")
  }

  // -------------------------------------------------------------------
  hwScenario("hw_link", "esp32", needs = Set(HilSide.Esp)) { b =>
    val d = b.esp.get
    val (resets0, junk0) = (d.resets, d.junkLines)
    val i = d.info()
    assert(i == b.espInfo.get, s"ver zmienil sie od otwarcia: $i")
    assert(i.dev == "esp32s3")
    d.stop()                                               // stan znany: bez biegu, SCK/WS w Z
    assert(d.command("hw_link_bogus").left.map(_._1) == Left(1))
    val typo = intercept[HilDeviceError](d.cfg("rolee" -> "slave"))
    assert(typo.code == Some(2), typo.getMessage)
    val stat = d.stat()
    info(s"stat: $stat")

    val t0 = System.nanoTime
    HilProgress("esp32: selftest (wektory + petla wewnetrzna, kilka sekund)")
    val n  = d.selftest()
    info(f"selftest: $n wektorow w ${(System.nanoTime - t0) / 1e9}%.1f s")
    assert(n == I2sHil.selftestVectors, s"firmware ma $n wektorow, kontrakt ${I2sHil.selftestVectors}")

    val t1 = System.nanoTime
    for (_ <- 0 until 50) assert(d.info() == i)
    info(f"ver: ${(System.nanoTime - t1) / 50e6}%.2f ms na komende")
    assert(d.resets == resets0, "ESP32 zresetowal sie w trakcie testu (log ROM)")
    assert(d.junkLines == junk0, "linie spoza protokolu w trakcie testu - konsola IDF na USB? (vertebra-hil.md §7)")
  }

  hwScenario("hw_link", "fpga", needs = Set(HilSide.Fpga)) { b =>
    val d = b.fpga.get
    val retries0 = d.retries
    val i = d.info()
    assert(i == b.fpgaInfo.get, s"rejestry identyfikacji zmienily sie od otwarcia: $i")
    val v = I2sFpgaMap.variant(i.variant.get).get
    info(s"wariant ${v.name}, build ${i.build}")

    d.stop()
    d.cfg("role" -> "slave")                               // FPGA nie steruje SCK/WS

    // scratch: bajty rowne znacznikom ramek i losowe
    val rng    = new scala.util.Random(seed)
    val fixed  = Seq(0L, 0xFFFFFFFFL, 0xA5A5A5A5L, 0x5A5A5A5AL, 0xA5010007L, 0x5A00A55AL) ++
                 (0 until 32).map(1L << _)
    val values = fixed ++ Seq.fill(200)(rng.nextLong() & 0xFFFFFFFFL)
    val t0 = System.nanoTime
    for (x <- values) {
      d.write(Addr.Scratch, x)
      assert(d.read(Addr.Scratch) == x, f"scratch $x%08x")
    }
    val ms = (System.nanoTime - t0) / 1e6 / (2 * values.size)
    info(f"${2 * values.size} transakcji, $ms%.2f ms na transakcje")
    assert(d.retries == retries0, s"ponowienia na zdrowym laczu: ${d.retries - retries0} (log ruchu)")

    // statusy mostu
    def raw(frame : Seq[Int]) = d.transact(frame, attempts = 1)
    val badSum = readReq(Addr.Scratch)
    assert(raw(badSum.init :+ (badSum.last ^ 0xFF)).map(_.status) == Right(Status.BadSum))
    assert(raw(Seq(ReqSync, 0x07, 0x00, Addr.Scratch).pipe(f => f :+ xorSum(f))).map(_.status) == Right(Status.BadOp))
    assert(intercept[HilDeviceError](d.read(0x0FF)).code == Some(Status.BadAddr))
    assert(intercept[HilDeviceError](d.write(Addr.Magic, 0)).code == Some(Status.BadOp))

    // resynchronizacja: smieci poza ramka i porzucona niedokonczona ramka
    d.link.send(Array(0x00, 0x13, RspSync, 0xFF).map(_.toByte))
    assert(d.read(Addr.Magic) == Magic)
    d.link.send(Array(ReqSync, OpRead).map(_.toByte))
    Thread.sleep(3 * FpgaDevice.BridgeTimeoutMs)
    assert(d.read(Addr.Scratch) == values.last)
    assert(d.retries == retries0, "most nie porzucil niedokonczonej ramki po 10 ms")

    // bieg bez partnera: rejestry zablokowane, stop daje migawke
    d.start()
    assert(d.running())
    assert(intercept[HilDeviceError](d.write(Addr.GenSeed, 1)).code == Some(Status.Busy))
    d.stop()
    val s = d.stat()
    info(s"stat po biegu bez partnera: $s")
    assert(s.bad == 0 && s.relocks == 0, s"$s")
  }


  // -------------------------------------------------------------------
  //  hw_slv_*, hw_mst_*: jeden kierunek, jedna rola FPGA
  // -------------------------------------------------------------------

  /** Bufory DMA ESP32 w drodze (8 x 240 ramek, etap 3) z zapasem. */
  val espInFlight = 4096L

  /** Konfiguracja pasujaca do wgranego bitstreamu. */
  def benchCfg(b : HilBench) : I2sBenchCfg = {
    val v = b.fpgaInfo.flatMap(_.variant).flatMap(I2sFpgaMap.variant).getOrElse(fail("brak wariantu FPGA"))
    I2sBenchCfg.all.find(c => c.v == v && c.espW == v.width).getOrElse(fail(s"brak konfiguracji dla ${v.name}"))
  }

  val seedStr = f"0x$seed%08x"

  /** Obie strony w stop (SCK/WS w Z, §9), potem cfg: FPGA w roli
    * `fpgaMaster`, ESP32 w przeciwnej. Slot na magistrali daje master. */
  def setup(b : HilBench, c : I2sBenchCfg, fpgaMaster : Boolean, espTx : Boolean, espRx : Boolean,
            n : Long = runFrames, gap : (Int, Int, Int) = (0, 0, 0)) : Unit = {
    val (esp, fpga) = (b.esp.get, b.fpga.get)
    esp.stop(); fpga.stop()
    val slot = if (fpgaMaster) c.v.slotWidth else c.espSlot
    fpga.cfg("role" -> (if (fpgaMaster) "master" else "slave"), "peer_w" -> c.espW, "slot" -> slot,
             "seed" -> seedStr, "gap_mode" -> gap._1, "gap_every" -> gap._2, "gap_len" -> gap._3, "rst_count" -> 0)
    // ESP32 slave: fs to tylko nominal do DMA i zegara modulu (§7), ESP32 idzie za SCK FPGA.
    esp.cfg("role" -> (if (fpgaMaster) "slave" else "master"), "fs" -> c.espFs, "w" -> c.espW, "slot" -> slot,
            "seed" -> seedStr, "peer_w" -> c.v.width, "tx" -> (if (espTx) 1 else 0), "rx" -> (if (espRx) 1 else 0),
            "loop" -> 0)
    val roles = if (fpgaMaster) "FPGA master, ESP32 slave" else "ESP32 master, FPGA slave"
    val dir   = (if (espTx) Seq("ESP32->FPGA") else Nil) ++ (if (espRx) Seq("FPGA->ESP32") else Nil)
    info(s"${c.name}: ${c.v.name} (DUT width ${c.v.width}), $roles, ESP32 fs=${c.espFs} w=${c.espW} slot=$slot, " +
         s"${dir.mkString(" + ")}, $n ramek (~${n / c.espFs} s)")
  }

  /** Czeka, az `count(stat ESP32)` dojdzie do n. Timeout z fs i zapasem. */
  def waitEsp(b : HilBench, c : I2sBenchCfg, n : Long, what : String)(count : HilStat => Long) : Unit = {
    val limitMs = n * 1000 / c.espFs * 3 / 2 + 10000
    val t0 = System.currentTimeMillis
    var last = b.esp.get.stat()
    while (count(last) < n) {
      if (System.currentTimeMillis - t0 > limitMs)
        fail(s"$what: po ${limitMs / 1000} s tylko ${count(last)} z $n ramek; ESP32: $last")
      Thread.sleep(scala.math.min(2000L, scala.math.max(100L, (n - count(last)) * 1000 / c.espFs)))
      last = b.esp.get.stat()
      HilProgress(f"$what: ${count(last)}%d / $n%d ramek, ${(System.currentTimeMillis - t0) / 1000}%d s " +
                  s"(limit ${limitMs / 1000} s)")
    }
    info(f"$what: $n ramek w ${(System.currentTimeMillis - t0) / 1000.0}%.1f s")
  }

  /** Bieg bez bledu albo fail z dumpem zdekodowanym wzorcem. */
  def expectClean(side : String, d : HilDevice, st : HilStat, link : I2sPattern.Link,
                  n : Long = runFrames) : Unit = {
    info(s"$side: $st")
    if (!st.isClean(n)) failWithDump(side, d, st, link,
      s"wymagane: lock, frames >= $n, bad = gaps = relocks = overflow = 0")
  }

  def failWithDump(side : String, d : HilDevice, st : HilStat, link : I2sPattern.Link, what : String) : Nothing = {
    val dump = scala.util.Try(d.dump()).getOrElse(Nil)
    fail(s"bieg z bledem ($what)\n" + I2sDiag.report(side, link, st, dump))
  }

  /** Na koniec zawsze obie strony w stop, nawet po bledzie. */
  def safely(b : HilBench)(body : => Unit) : Unit =
    try body finally { scala.util.Try(b.esp.get.stop()); scala.util.Try(b.fpga.get.stop()) }

  /** Jeden kierunek: `dutRx` = DUT odbiera (nadaje ESP32), inaczej DUT
    * nadaje, a sprawdza ESP32. Kolejnosc: start odbiornika przed nadawca
    * (takze gdy odbiornik daje zegar), stop odbiornika przed nadawca -
    * migawka z ciaglego strumienia, a nie z ciszy przy wylaczaniu. */
  def oneWay(b : HilBench, c : I2sBenchCfg, fpgaMaster : Boolean, dutRx : Boolean, n : Long = runFrames) : Unit = {
    val (esp, fpga) = (b.esp.get, b.fpga.get)
    val linkOk = if (dutRx) c.linkToFpga(seed, fpgaMaster) else c.linkToEsp(seed, fpgaMaster)
    assert(linkOk.checkable, s"${c.name}: ${linkOk.label} nie jest checkable")
    safely(b) {
      setup(b, c, fpgaMaster, espTx = dutRx, espRx = !dutRx, n)
      if (dutRx) {
        fpga.start(); esp.start()
        waitEsp(b, c, n + espInFlight, "ESP32 nadal")(_.sent)
        fpga.stop()
        val es = esp.stat()
        esp.stop()
        info(s"ESP32 (nadawca): $es")
        val fs = fpga.stat()
        expectClean("FPGA", fpga, fs, c.linkToFpga(seed, fpgaMaster), n)
        assert(fs.frames <= es.sent, s"FPGA policzyl ${fs.frames} ramek, ESP32 nadal ${es.sent}")
      } else {
        esp.start(); fpga.start()
        waitEsp(b, c, n, "ESP32 odebral")(_.frames)
        esp.stop(); fpga.stop()
        val es = esp.stat()
        val fs = fpga.stat()
        info(s"FPGA (nadawca): sent=${fs.sent}")
        expectClean("ESP32", esp, es, c.linkToEsp(seed, fpgaMaster), n)
        val lag = fs.sent - es.frames
        info(s"sent FPGA - frames ESP32 = $lag (ramki w DMA ESP32 w chwili stop)")
        assert(lag >= 0 && lag <= espInFlight, s"sent FPGA ${fs.sent}, frames ESP32 ${es.frames}")
      }
    }
  }

  hwScenario("hw_slv_rx_frame") { b => oneWay(b, benchCfg(b), fpgaMaster = false, dutRx = true) }
  hwScenario("hw_slv_tx_frame") { b => oneWay(b, benchCfg(b), fpgaMaster = false, dutRx = false) }
  hwScenario("hw_mst_rx_frame") { b => oneWay(b, benchCfg(b), fpgaMaster = true,  dutRx = true) }
  hwScenario("hw_mst_tx_frame") { b => oneWay(b, benchCfg(b), fpgaMaster = true,  dutRx = false) }

  // -------------------------------------------------------------------
  //  V2
  // -------------------------------------------------------------------

  /** Wariant wgranego bitstreamu. */
  def variantOf(b : HilBench) : I2sHilVariant = benchCfg(b).v

  /** Full duplex: obie strony nadaja i sprawdzaja. Start: slave przed
    * masterem (§3). Stop kazdej strony robi cisze dla checkera drugiej:
    * ESP32 przestaje nadawac, a FPGA master po stop dalej daje SCK/WS
    * (clkOe = rola, nie bieg), tylko z generatorem wylaczonym. Dlatego
    * liczniki ESP32 czytamy jeszcze w biegu (stat dziala w kazdym stanie),
    * potem stop FPGA (migawka z ciaglego strumienia), na koncu ESP32. */
  def duplex(b : HilBench, c : I2sBenchCfg, fpgaMaster : Boolean, n : Long) : Unit = {
    val (esp, fpga) = (b.esp.get, b.fpga.get)
    val (master, slave) = if (fpgaMaster) (fpga : HilDevice, esp : HilDevice) else (esp : HilDevice, fpga : HilDevice)
    safely(b) {
      setup(b, c, fpgaMaster, espTx = true, espRx = true, n)
      slave.start(); master.start()
      waitEsp(b, c, n + espInFlight, "ESP32 odebral")(_.frames)
      val es = esp.stat()
      fpga.stop(); esp.stop()
      val fs = fpga.stat()
      expectClean("ESP32", esp, es, c.linkToEsp(seed, fpgaMaster), n)
      expectClean("FPGA", fpga, fs, c.linkToFpga(seed, fpgaMaster), n)
    }
  }

  hwScenario("hw_full_duplex", "fpga_slave")  { b => duplex(b, benchCfg(b), fpgaMaster = false, v2Frames) }
  hwScenario("hw_full_duplex", "fpga_master") { b => duplex(b, benchCfg(b), fpgaMaster = true,  v2Frames) }

  /** Padding: ESP32 master, slowo DUT-a w slocie 32 (16/32, 24/32). */
  hwScenario("hw_padding") { b =>
    val v = variantOf(b)
    val c = I2sBenchCfg.padding(v).getOrElse(cancel(s"${v.name}: slowo 32 bity nie ma paddingu w slocie 32"))
    oneWay(b, c, fpgaMaster = false, dutRx = true, v2Frames)
    oneWay(b, c, fpgaMaster = false, dutRx = false, v2Frames)
  }

  /** Bez paddingu: LSB slowa tuz przed zmiana WS (slot == slowo). */
  hwScenario("hw_lsb_across_ws") { b =>
    val v = variantOf(b)
    val c = I2sBenchCfg.lsbAcrossWs(v)
    oneWay(b, c, fpgaMaster = false, dutRx = true, v2Frames)
    oneWay(b, c, fpgaMaster = false, dutRx = false, v2Frames)
  }

  /** ESP32 z inna szerokoscia slowa niz DUT; obie role, oba kierunki.
    * FPGA master narzuca slot wariantu, wiec przy slocie 16 inne slowo to 8. */
  hwScenario("hw_word_length_mismatch") { b =>
    val v = variantOf(b)
    val c = I2sBenchCfg.mismatch(v)
    for (master <- Seq(false, true); dutRx <- Seq(true, false))
      oneWay(b, c, master, dutRx, v2Frames)
  }

  /** Luki generatora FPGA: DUT nadaje cisze (underrun), checker ESP32
    * liczy je jako gaps, bez bledu i bez relocka; obie role. */
  def underrun(b : HilBench, c : I2sBenchCfg, fpgaMaster : Boolean, n : Long) : Unit = {
    val (every, len) = (64, 2)
    val (esp, fpga) = (b.esp.get, b.fpga.get)
    safely(b) {
      setup(b, c, fpgaMaster, espTx = false, espRx = true, n, gap = (1, every, len))
      esp.start(); fpga.start()
      waitEsp(b, c, n, "ESP32 odebral")(_.frames)
      esp.stop(); fpga.stop()
      val (es, fs) = (esp.stat(), fpga.stat())
      info(s"ESP32: $es; FPGA sent=${fs.sent}")
      val link = c.linkToEsp(seed, fpgaMaster)
      if (!es.locked || es.bad != 0 || es.relocks != 0 || es.overflow != 0 || es.frames < n)
        failWithDump("ESP32", esp, es, link, s"wymagane: lock, frames >= $n, bad = relocks = overflow = 0")
      // Luka po kazdych `every` ramkach: gaps ~ frames * len / every; tolerancja
      // na luke rozcieta przez start/stop.
      val exp = es.frames * len / every
      info(s"gaps ${es.gaps}, oczekiwane ~$exp (luka $len ramek co $every)")
      assert(scala.math.abs(es.gaps - exp) <= 2 * len, s"gaps ${es.gaps}, oczekiwane $exp +- ${2 * len}")
    }
  }

  hwScenario("hw_tx_underrun", "fpga_slave")  { b => underrun(b, benchCfg(b), fpgaMaster = false, v2Frames) }
  hwScenario("hw_tx_underrun", "fpga_master") { b => underrun(b, benchCfg(b), fpgaMaster = true,  v2Frames) }

  /** ESP32 master na 44,1 kHz: PLL 160 MHz nie dzieli sie rowno, BCLK z
    * dzielnika ulamkowego ma prawdziwy jitter (§2). */
  hwScenario("hw_fs_fractional") { b =>
    val v = variantOf(b)
    val c = I2sBenchCfg.fsFractional(v)
    oneWay(b, c, fpgaMaster = false, dutRx = true, v2Frames)
    oneWay(b, c, fpgaMaster = false, dutRx = false, v2Frames)
  }

  unimplemented("hw_la_crosscheck",
    "brak analizatora stanow (vertebra-hil.md §11); SigrokI2sCheck jest gotowy (etap 3)")
}
