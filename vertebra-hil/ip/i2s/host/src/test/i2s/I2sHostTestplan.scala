package newhope.vertebra.hil.i2s

import newhope.vertebra.{Stage, Testpoint, TestplanSuite}
import newhope.vertebra.hil._
import newhope.vertebra.hil.HilHostKit._
import org.scalatest.ConfigMap

// =====================================================================
//  Host adaptera I2S na atrapach (bez stanowiska): klucze bloku 0x100-,
//  diagnoza ramek i cale I2sHilTestplan na FakeI2sBus. Rdzen hosta:
//  HilHostTestplan (core/host).
// =====================================================================

object I2sHostPlan {
  val plan = Seq(
    Testpoint("host_i2s_cfg", Stage.V1,
      "FpgaDevice.cfg z mapa I2S: klucze -> rejestry, calosc albo nic",
      checking = Seq("role, peer_w, slot trafiaja pod adresy z I2sHilRegs, wspolne pod HilProtocol",
                     "polaczenie nie-checkable (Wtx = 32 przez slot 8): blad bez zadnego zapisu")),
    Testpoint("host_i2s_diag", Stage.V1,
      "I2sDiag: przyczyna niezgodnej ramki z got i exp",
      checking = Seq("zgodna, cisza, zamiana kanalow, zgubione k ramek, zdublowana ramka",
                     "przesuniecie o bit w obie strony (z nieznanym bitem sasiedniego slowa)",
                     "przeklamane bity z maska xor")),
    Testpoint("host_hw_on_fakes", Stage.V1,
      "Testy sprzetowe (I2sHilTestplan) na atrapach obu plytek",
      stimulus = Seq("I2sHilTestplan z HilBench na FakeI2sBus (ramki plyna z fs, gdy pracuja obie strony)",
                     "drugi bieg: przeklamana ramka po stronie FPGA, zamienione kanaly po stronie ESP32"),
      checking = Seq("czysty bieg: hw_link, hw_slv_rx_frame, hw_slv_tx_frame przechodza",
                     "bieg z bledami: hw_slv_* failed, komunikat nazywa przyczyne i numer ramki (I2sDiag)")))
}

class I2sHostTestplan extends TestplanSuite {
  def testplan : Seq[Testpoint] = I2sHostPlan.plan

  val v = I2sHilVariant.v16_32

  def code[T](body : => T) : Option[Int] = intercept[HilDeviceError](body).code

  testpoint("host_i2s_cfg") {
    val f = new FakeFpga(v.code, 0x12345678L)
    val d = new FpgaDevice[HilFpgaMap](new HilLink(f.port, "fpga", text = false), I2sHil.fpgaMap)
    d.cfg("role" -> "master", "peer_w" -> 24, "slot" -> 32, "seed" -> "0x5eed1234", "gap_mode" -> 1)
    assert(f.writes.toMap == Map(I2sHilRegs.Role -> 1L, I2sHilRegs.PeerW -> 24L, I2sHilRegs.Slot -> 32L,
      HilProtocol.Addr.GenSeed -> 0x5eed1234L, HilProtocol.Addr.GapMode -> 1L))
    f.writes.clear()
    assert(code(d.cfg("role" -> "master", "rol" -> "slave")) == Some(2))
    assert(code(d.cfg("role" -> "boss")) == Some(3))
    // Wtx = 32 widziane przez slot 8: brak calego pola seq
    assert(code(d.cfg("peer_w" -> 32, "slot" -> 8)) == Some(3))
    assert(f.writes.isEmpty, s"${f.writes}")
  }

  testpoint("host_i2s_diag") {
    val link = I2sPattern.Link(I2sHilPlan.seed, 16, 32, 16)
    val e = link.expected(100)
    def x(got : I2sWords) = I2sDiag.explain(link, got, e)
    assert(x(e) == "zgodna")
    assert(x(I2sWords(0, 0)).startsWith("cisza"))
    assert(x(e.swap).startsWith("kanaly zamienione"))
    assert(x(link.expected(103)) == "poprawna ramka wzorca 3 dalej: zgubione 3 ramek")
    assert(x(link.expected(99)).startsWith("zdublowana"))
    // opoznienie: MSB z poprzedniego slowa (tu 1), reszta przesunieta w prawo
    assert(x(I2sWords((e.l >>> 1) | 0x8000L, e.r >>> 1)).contains("za pozno"), x(I2sWords(e.l >>> 1, e.r >>> 1)))
    assert(x(I2sWords(((e.l << 1) & 0xFFFFL) | 1L, (e.r << 1) & 0xFFFFL)).contains("za wczesnie"))
    assert(x(I2sWords(e.l ^ 0x0101L, e.r)).contains("L xor=00000101 (2)"))
  }

  /** I2sHilTestplan na FakeI2sBus; zwraca wynik kazdego testu po nazwie. */
  def runOnFakes(setup : FakeI2sBus => Unit) : Map[String, String] = {
    val h   = head.getOrElse("00000000")
    val bus = new FakeI2sBus(I2sBenchCfg.all.head, I2sHilPlan.seed, h, java.lang.Long.parseLong(h, 16) & ~1L,
                             I2sHil.selftestVectors)
    setup(bus)
    // allow_stale: lokalne zmiany w zrodlach plytek nie sa tu tematem
    val b = HilBench.open(I2sHil, HilBenchOpts(Some("E" -> "t"), Some("F" -> "t"), allowStale = true),
                          opener(Map("E" -> bus.esp.port, "F" -> bus.fpga.port)))
    val suite = new I2sHilTestplan { override def bench = b }
    try runSuite(suite, ConfigMap("frames" -> "5000", "frames_v2" -> "5000"))
    finally b.foreach(_.foreach(_.close()))
  }

  testpoint("host_hw_on_fakes") {
    val clean = runOnFakes(_ => ())
    for (n <- Seq("hw_link (esp32)", "hw_link (fpga)", "hw_param_bounds", "hw_slv_rx_frame", "hw_slv_tx_frame",
                  "hw_mst_rx_frame", "hw_mst_tx_frame",
                  "hw_full_duplex (fpga_slave)", "hw_full_duplex (fpga_master)", "hw_padding", "hw_lsb_across_ws",
                  "hw_word_length_mismatch", "hw_tx_underrun (fpga_slave)", "hw_tx_underrun (fpga_master)",
                  "hw_fs_fractional", "hw_startup_mid_frame",
                  "hw_random_reset (fpga_slave)", "hw_random_reset (fpga_master)")) {
      info(s"$n: ${result(clean, n)}")
      assert(result(clean, n) == "ok", s"$n: ${result(clean, n)}")
    }

    val faulty = runOnFakes { bus =>
      bus.fpgaRxFault = Some(150L -> (w => I2sWords(w.l, w.r ^ 1L)))
      bus.espRxFault  = Some(200L -> (w => w.swap))
    }
    val rx = result(faulty, "hw_slv_rx_frame")
    val tx = result(faulty, "hw_slv_tx_frame")
    info(s"hw_slv_rx_frame z bledem: $rx")
    info(s"hw_slv_tx_frame z bledem: $tx")
    assert(rx.startsWith("FAILED") && rx.contains("ramka 150: przeklamane bity") && rx.contains("R xor=00000001"), rx)
    assert(tx.startsWith("FAILED") && tx.contains("ramka 200: kanaly zamienione") && tx.contains("idx 200"), tx)
    for (n <- Seq("hw_mst_rx_frame", "hw_mst_tx_frame"))
      assert(result(faulty, n).startsWith("FAILED"), s"$n: ${result(faulty, n)}")
  }
}
