package newhope.vertebra.hil

import java.io.File
import org.scalatest.{Args, Status}
import newhope.vertebra.TestplanSuite

// =====================================================================
//  TestplanSuite dla testow sprzetowych (vertebra-hil.md §8).
//
//  hwScenario rejestruje testpoint jak kazdy inny, a w ciele:
//    - bez stanowiska (zaden port nie ustawiony) -> assume: canceled,
//      nie failed, wiec `sbt test` bez plytek zostaje zielony
//    - bez plytki, ktorej scenariusz potrzebuje -> tez canceled
//    - port plytki ustawiony, ale plytka nie dziala -> failed z opisem;
//      testy, ktore jej nie potrzebuja, ida dalej
//  Ruch na obu portach idzie do target/hil-logs/<suita>/<test>/<plytka>.log.
// =====================================================================

object HilSide extends Enumeration {
  val Esp, Fpga = Value
  val Both : Set[Value] = Set(Esp, Fpga)
}

trait HilSuite extends TestplanSuite {
  def hilIp : HilIp

  @volatile private var configMap : Map[String, Any] = Map.empty

  override def run(testName : Option[String], args : Args) : Status = {
    configMap = args.configMap.toMap
    super.run(testName, args)
  }

  /** Otwierane przy pierwszym uzyciu, jedno na JVM (HilBench.get). */
  def bench : Either[String, Option[HilBench]] = HilBench.get(hilIp, HilBenchOpts.from(configMap))

  val logRoot = new File("target/hil-logs")

  private def safe(s : String) = s.replaceAll("[^A-Za-z0-9_.-]+", "_")

  def hwScenario(name : String, variant : String = "", needs : Set[HilSide.Value] = HilSide.Both)
                (body : HilBench => Unit) : Unit =
    testpoint(name, variant) {
      val bad = HilBenchOpts.unknown(configMap)
      if (bad.nonEmpty)
        fail(s"nieznane opcje suity: ${bad.map("-D" + _).mkString(", ")} " +
             s"(znane: ${HilBenchOpts.known.toSeq.sorted.map("-D" + _).mkString(", ")})")
      val b = bench match {
        case Left(e) => fail(s"stanowisko: $e")
        case Right(x) => x
      }
      assume(b.isDefined,
        s"brak stanowiska: ustaw ${HilBenchOpts.EspEnv} / ${HilBenchOpts.FpgaEnv} " +
        s"albo -D${HilBenchOpts.EspOpt}=... -D${HilBenchOpts.FpgaOpt}=...")
      val hb = b.get
      // Port ustawiony, plytka nie dziala: failed. Portu nie ma: canceled.
      def opts = HilBenchOpts.from(configMap).describe
      if (needs(HilSide.Esp))  hb.espError.foreach(e => fail(s"stanowisko: $e [$opts]"))
      if (needs(HilSide.Fpga)) hb.fpgaError.foreach(e => fail(s"stanowisko: $e [$opts]"))
      if (needs(HilSide.Esp))  assume(hb.esp.isDefined,  s"scenariusz potrzebuje ESP32 (${HilBenchOpts.EspEnv})")
      if (needs(HilSide.Fpga)) assume(hb.fpga.isDefined, s"scenariusz potrzebuje FPGA (${HilBenchOpts.FpgaEnv})")
      info(s"${hb.describe} [${HilBenchOpts.from(configMap).describe}]")
      hb.warnings.foreach(w => info(s"UWAGA: $w"))
      val dir = new File(new File(logRoot, safe(suiteName)),
                         safe(if (variant.isEmpty) name else s"${name}_$variant"))
      hb.logTo(Some(dir))
      try body(hb)
      finally {
        hb.logTo(None)
        info(s"log ruchu: ${dir.getPath}")
      }
    }
}
