package newhope.vertebra.hil.fe

import newhope.vertebra.{Stage, Testpoint, TestplanSuite}
import newhope.vertebra.hil._
import newhope.vertebra.hil.HilHostKit._
import org.scalatest.ConfigMap

// =====================================================================
//  Host adaptera frontendu na atrapach (bez stanowiska): cale
//  FeHilTestplan na FakeFeBus. Rdzen hosta: HilHostTestplan (core/host).
// =====================================================================

object FeHostPlan {
  val plan = Seq(
    Testpoint("host_fe_on_fakes", Stage.V1,
      "Testy sprzetowe frontendu (FeHilTestplan) na atrapach obu plytek",
      stimulus = Seq("FeHilTestplan z HilBench na FakeFeBus (load, rec, liczniki FE; nagranie z DcGolden)",
                     "drugi bieg: przeklamane y ramki 500, echo x ramki 700, zgubiona ramka 900"),
      checking = Seq("czysty bieg: hw_link, hw_param_bounds, hw_fe_* przechodza, hw_fe_long canceled bez -Dlong",
                     "bieg z bledami: hw_fe_chain failed, komunikat nazywa ramke 500 (DcGolden), N0 i przerwe idx",
                     "wariant mimas_n4: hw_n4_* przechodza; przeklamane CRC N3 -> failed z nazwa wezla",
                     "wariant mimas: hw_n4_* canceled")))
}

class FeHostTestplan extends TestplanSuite {
  def testplan : Seq[Testpoint] = FeHostPlan.plan

  /** FeHilTestplan na FakeFeBus. */
  def runFeOnFakes(setup : FakeFeBus => Unit, v : FeHilVariant = FeHilVariant.mimas) : Map[String, String] = {
    val h   = head.getOrElse("00000000")
    val bus = new FakeFeBus(v, h, java.lang.Long.parseLong(h, 16) & ~1L)
    setup(bus)
    val b = HilBench.open(FeHil, HilBenchOpts(Some("E" -> "t"), Some("F" -> "t"), allowStale = true),
                          opener(Map("E" -> bus.esp.port, "F" -> bus.fpga.port)))
    val suite = new FeHilTestplan { override def bench = b }
    try runSuite(suite, ConfigMap("samples" -> "3000"))
    finally b.foreach(_.foreach(_.close()))
  }

  testpoint("host_fe_on_fakes") {
    val clean = runFeOnFakes(_ => ())
    for (n <- Seq("hw_link (esp32)", "hw_link (fpga)", "hw_param_bounds", "hw_fe_chain", "hw_fe_corners",
                  "hw_fe_removal", "hw_fe_bypass", "hw_fe_reset")) {
      info(s"$n: ${result(clean, n)}")
      assert(result(clean, n) == "ok", s"$n: ${result(clean, n)}")
    }
    assert(result(clean, "hw_fe_long").startsWith("canceled"), result(clean, "hw_fe_long"))

    val faulty = runFeOnFakes { bus => bus.yFault = Some(500); bus.xFault = Some(700); bus.dropAt = Some(900) }
    val chain = result(faulty, "hw_fe_chain")
    info(s"hw_fe_chain z bledami: ${chain.take(600)}")
    assert(chain.startsWith("FAILED"), chain)
    assert(chain.contains("ramka 500 (odcinek od 0, probka 500)") && chain.contains("DcGolden"), chain)
    assert(chain.contains("N0: echo x nie pasuje") && chain.contains("ramka 700"), chain)
    assert(chain.contains("ramka 900: idx 133, oczekiwany 132"), chain)

    // bitstream z N2 - N4
    val n4 = runFeOnFakes(_ => (), FeHilVariant.mimasN4)
    for (n <- Seq("hw_link (fpga)", "hw_fe_chain", "hw_fe_reset", "hw_n4_chain", "hw_n4_reset")) {
      info(s"mimas_n4 $n: ${result(n4, n)}")
      assert(result(n4, n) == "ok", s"mimas_n4 $n: ${result(n4, n)}")
    }
    val n3bad = runFeOnFakes(_.n3Fault = Some(5), FeHilVariant.mimasN4)
    val n4c = result(n3bad, "hw_n4_chain")
    info(s"hw_n4_chain z bledem N3: ${n4c.take(400)}")
    assert(n4c.startsWith("FAILED") && n4c.contains("N3 CRC") && n4c.contains("(N2 zgodny)"), n4c)
    assert(result(runFeOnFakes(_ => ()), "hw_n4_chain").startsWith("canceled"), "bitstream bez N4: hw_n4_* canceled")
  }
}
