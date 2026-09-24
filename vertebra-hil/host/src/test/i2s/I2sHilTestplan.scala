package newhope.vertebra.hil.i2s

import newhope.vertebra.{Stage, Testpoint}
import newhope.vertebra.hil._
import HilProtocol._
import scala.util.chaining._

// =====================================================================
//  ZRODLO PLANU
//  vertebra-hil.md §8 ("Plan I2S"), §10 etap 4. Testy sprzetowe:
//  prefiks hw_, w `checking` odsylaja do testpointu symulacyjnego, ktory
//  uzupelniaja.
//
//  Plan rosnie z etapami. Testpointy z §8 dochodza do planu w etapie,
//  ktory je robi (5: hw_slv_*, hw_la_crosscheck; 6: reszta V1 i V2;
//  7: V3), a nie wczesniej: `testplan completeness` wymaga kompletnego
//  V1, wiec wpisanie hw_mst_rx_frame juz teraz zrobiloby `sbt test`
//  czerwonym bez stanowiska.
//
//  Uruchamianie (porty: vertebra-hil.md §8, HilBench):
//    sbt "hil/testOnly *I2sHilTestplan"                                   bez plytek: canceled
//    sbt "hil/testOnly *I2sHilTestplan -- -Desp_com=COM11 -Dfpga_com=COM12"
//    sbt "hil/testOnly *I2sHilTestplan -- -z param"                       bez sprzetu
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
        "fpga: stop daje migawke, stat sie parsuje")))

  val seed = 0x5eed1234L
}

class I2sHilTestplan extends HilSuite {
  import I2sHilPlan._
  def testplan : Seq[Testpoint] = plan
  def hilIp : HilIp = I2sHil

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
    for (c <- I2sBenchCfg.all) {
      val v = c.v
      // ESP32: zakresy z contract/i2s/commands.md
      assert(Set(8, 16, 24, 32)(c.espW) && Set(8, 16, 24, 32)(c.espSlot) && c.espSlot >= c.espW, c.name)
      assert(c.espFs >= 8000 && c.espFs <= 96000, c.name)
      assert(c.espSlot == v.slotWidth, s"${c.name}: ESP32 jako slave musi miec slot mastera FPGA")
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

      for (master <- Seq(true, false); (dir, l) <- Seq("->esp32" -> c.linkToEsp(seed, master),
                                                       "->fpga"  -> c.linkToFpga(seed, master))) {
        val role = if (master) "FPGA master" else "FPGA slave"
        assert(l.checkable, s"${c.name} $role $dir: ${l.label} nie jest checkable")
        if (l.visibleHashBits == 0) blind :+= s"${c.name} $role $dir"
        if (master) info(s"  $dir ${l.label}: widoczne bity hasha ${l.visibleHashBits}")
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

}
