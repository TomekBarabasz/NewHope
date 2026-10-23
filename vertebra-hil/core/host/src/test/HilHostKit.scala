package newhope.vertebra.hil

import java.io.File
import org.scalatest.{Args, ConfigMap, Reporter, Suite}
import org.scalatest.events.{Event, TestCanceled, TestFailed, TestSucceeded}

// =====================================================================
//  Atrapa IP dla testow rdzenia hosta (bez I2S i FE) i helpery wspolne
//  z testami adapterow IP (ip/*/host, zaleznosc test->test).
// =====================================================================

/** Blok 0x100- atrapy: dwa klucze i walidacja calej konfiguracji. */
object TestFpgaMap extends HilFpgaMap {
  val ipId = "TST"
  val Mode = 0x100
  val Len  = 0x101
  val keys : Map[String, HilCfgKey] = Map(
    "mode" -> HilCfgKey.enumOf(Mode, "off", "on"),
    "len"  -> HilCfgKey.range(Len, 1, 63))
  def variantName(code : Long) : Option[String] = if (code == TestIp.variant) Some("t1") else None
  /** Jak `checkable` w I2S: zla kombinacja kluczy odrzuca cala konfiguracje. */
  override def validate(code : Long, get : String => Long) : Option[String] =
    if (get("len") == 13) Some("len=13 zakazane") else None
}

object TestIp extends HilIp {
  val variant = 0x7531L
  val name    = "tst"
  val fpgaMap = TestFpgaMap
  val espSources  = HilGit.espSources
  /** Rdzen FPGA jako zrodlo "bitstreamu" atrapy. */
  val fpgaSources = HilRepo.root().toSeq.map(r => HilSource(new File(r, "core/fpga"), HilSource.projectExcludes))
  val espFlashHint  = "atrapa"
  val fpgaFlashHint = "atrapa"
}

object HilHostKit {
  /** 8 cyfr HEAD repo najwyzszego poziomu (to, co ma w rejestrze build bitstream z tego drzewa). */
  def head : Option[String] =
    HilRepo.root().flatMap(HilRepo.topOf).flatMap(t => HilRepo.git(t, "rev-parse", "--short=8", "HEAD"))

  /** Opener z atrapami: nazwa portu -> port. */
  def opener(ports : Map[String, HilPort]) : HilBench.Opener =
    (name, _, _) => ports.get(name).toRight(s"nie ma portu '$name'")

  /** Wyniki testow suity po nazwie. */
  def runSuite(suite : Suite, config : ConfigMap) : Map[String, String] = {
    val events = scala.collection.mutable.ArrayBuffer[Event]()
    val rep = new Reporter { def apply(ev : Event) : Unit = synchronized { events += ev } }
    suite.run(None, Args(rep, configMap = config))
    events.collect {
      case x : TestSucceeded => x.testName -> "ok"
      case x : TestFailed    => x.testName -> s"FAILED: ${x.message}"
      case x : TestCanceled  => x.testName -> s"canceled: ${x.message}"
    }.toMap
  }

  def result(r : Map[String, String], name : String) : String =
    r.collect { case (k, v) if k.contains(name) => v }.headOption.getOrElse(s"brak testu $name")
}
