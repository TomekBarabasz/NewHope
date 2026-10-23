import sbt._
import sbt.Keys._
import HwBuild._

// =====================================================================
//  vertebra-hil w buildzie projektu (NewHope, voice-changer, ...).
//  vertebra-hil nie buduje sie samodzielnie: projekt dolacza ten plik do
//  meta-buildu (project/build.sbt: unmanagedSourceDirectories +=
//  <projekt>/vertebra-hil/sbt, obok lib/sbt z HwBuild) i sam sklada
//  rdzen z adapterami IP, ktorych potrzebuje:
//
//    lazy val hilCoreFpga = HilBuild.coreFpga(hilDir, vertebra)
//    lazy val hilCoreHost = HilBuild.coreHost(hilDir, hilCoreFpga)
//    lazy val hilI2sFpga  = HilBuild.ipFpga(hilDir, "i2s", hilCoreFpga, com % "compile->compile;test->test")
//    lazy val hilI2sHost  = HilBuild.ipHost(hilDir, "i2s", hilCoreHost, hilI2sFpga)
//
//  Adapter, ktorego projekt nie wlaczyl, w ogole sie nie kompiluje, wiec
//  ip/fe nie potrzebuje front_end w projekcie bez frontendu. Wyjatek:
//  testy ip/fe uzywaja odbiornika pinow I2S z ip/i2s (I2sPinRx), wiec
//  projekt z FE wlacza tez I2S (hilI2sFpga % "test->test").
//
//  Zrodla bitstreamu (rejestr build, sprawdzanie aktualnosci plytki) to
//  katalogi projektow sbt, z ktorych bitstream jest zbudowany: adapter,
//  rdzen i ich zaleznosci przechodnie (bez vertebry - to infrastruktura
//  testow). Lista idzie zmienna VERTEBRA_HIL_FPGA_SRC do generatora
//  (ipFpga, run i test) i do hosta tego samego IP (ipHost), wiec obie
//  strony licza z tych samych katalogow, w ktoremkolwiek repo leza.
// =====================================================================
object HilBuild {

  val jSerialComm = "com.fazecast" % "jSerialComm" % "2.11.0"

  val hilFpgaSources = settingKey[Seq[File]]("Katalogi zrodel bitstreamu (VERTEBRA_HIL_FPGA_SRC)")

  /** Projekt i zaleznosci przechodnie, przez ktore idzie Compile (bez
    * "test->test": takie wchodza tylko do testow, nie do bitstreamu). */
  private def compileClosure(bd : sbt.internal.BuildDependencies, self : ProjectRef) : Seq[ProjectRef] = {
    def inCompile(d : ClasspathDep[ProjectRef]) =
      d.configuration.forall(_.split(";").exists(_.trim.split("->").head.trim == "compile"))
    val seen = scala.collection.mutable.LinkedHashSet[ProjectRef]()
    def walk(r : ProjectRef) : Unit = if (seen.add(r)) bd.classpath.getOrElse(r, Nil).filter(inCompile).foreach(d => walk(d.project))
    walk(self)
    seen.toSeq
  }

  /** Korzen vertebra-hil dla kodu (HilRepo.root): VERTEBRA_HIL_ROOT. */
  private def rootEnv(hilDir : File) : Map[String, String] =
    Map("VERTEBRA_HIL_ROOT" -> hilDir.getAbsoluteFile.toString)

  private def srcEnv(dirs : Seq[File]) : Map[String, String] =
    Map("VERTEBRA_HIL_FPGA_SRC" -> dirs.map(_.getAbsoluteFile.toString).distinct.mkString(java.io.File.pathSeparator))

  private def envSettings(m : Def.Initialize[Map[String, String]]) : Seq[Setting[_]] = Seq(
    Test          / envVars ++= m.value,
    Compile / run / envVars ++= m.value
  )

  /** Modul sprzetowy harnessu: hw/spinal/{main,test}, RTL i .bin w hw/gen. */
  private def fpgaSettings(hilDir : File) : Seq[Setting[_]] = forkSettings ++ Seq(
    Compile / scalaSource := baseDirectory.value / "hw" / "spinal" / "main",
    Test    / scalaSource := baseDirectory.value / "hw" / "spinal" / "test",
    libraryDependencies ++= spinal :+ (scalatest % Test),
    publish / skip := true,
    // Kasuje workspace'y przebiegow, zostawia cache; RTL w hw/gen zostaje
    // (sa tam bitstreamy .bin z gita).
    simClean := {
      val log = streams.value.log
      val ws  = baseDirectory.value / "simWorkspace"
      if (ws.exists) {
        val doomed = IO.listFiles(ws).filter(f => f.isDirectory && !f.getName.startsWith("."))
        doomed.foreach(IO.delete)
        log.info(s"${name.value}: usunieto ${doomed.size} workspace'ow, cache zostal")
      }
    },
    // Projekt i jego zaleznosci przechodnie w Compile, bez vertebry.
    hilFpgaSources := Def.settingDyn {
      val self = thisProjectRef.value
      val refs = compileClosure(buildDependencies.value, self)
      refs.map(r => Def.setting((r / name).value -> (r / baseDirectory).value)).join
        .apply(_.collect { case (n, d) if n != "vertebra" => d })
    }.value
  ) ++ envSettings(Def.setting(rootEnv(hilDir) ++ srcEnv(hilFpgaSources.value)))

  /** Orkiestrator PC: src/{main,test}, porty szeregowe, jedno stanowisko. */
  private def hostSettings(hilDir : File) : Seq[Setting[_]] = srcLayout ++ forkSettings ++ Seq(
    libraryDependencies ++= spinal ++ Seq(scalatest, jSerialComm),
    Test / parallelExecution := false,
    publish / skip := true
  )

  /** Rdzen FPGA: protokol, rejestry, UART, liczniki, HilEchoTop. Tylko vertebra. */
  def coreFpga(hilDir : File, vertebra : ProjectReference) : Project =
    Project("hil-core-fpga", hilDir / "core" / "fpga")
      .dependsOn(vertebra)
      .settings(fpgaSettings(hilDir))

  /** Rdzen hosta: HilBench, HilLink, HilGit, urzadzenia, trait HilIp. */
  def coreHost(hilDir : File, coreFpga : ProjectReference) : Project =
    Project("hil-core-host", hilDir / "core" / "host")
      .dependsOn(coreFpga)
      .settings(hostSettings(hilDir), envSettings(Def.setting(rootEnv(hilDir))))

  /** Adapter FPGA IP `ip`: harness, rejestry, top-levele, .ucf. `deps` to IP z lib. */
  def ipFpga(hilDir : File, ip : String, coreFpga : ProjectReference, deps : ClasspathDep[ProjectReference]*) : Project =
    Project(s"hil-$ip-fpga", hilDir / "ip" / ip / "fpga")
      .dependsOn((coreFpga % "compile->compile;test->test") +: deps : _*)
      .settings(fpgaSettings(hilDir))

  /** Adapter hosta IP `ip`: HilIp, testy hw_* i ich atrapy. */
  def ipHost(hilDir : File, ip : String, coreHost : ProjectReference, ipFpga : Project) : Project =
    Project(s"hil-$ip-host", hilDir / "ip" / ip / "host")
      .dependsOn(coreHost % "compile->compile;test->test", ipFpga % "compile->compile;test->test")
      .settings(hostSettings(hilDir),
                envSettings(Def.setting(rootEnv(hilDir) ++ srcEnv((ipFpga / hilFpgaSources).value))))
}
