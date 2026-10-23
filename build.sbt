// NewHope: projekty plytek i stanowisko vertebra-hil.
// Biblioteki (vertebra, core, com = i2c + i2s, front_end, mel, f0, ..., mimas_v2
// z MCB): submodule lib/ (github.com/TomekBarabasz/fpga-hw-lib), wciagane ze
// zrodel jako osobny build sbt (ProjectRef). Testy bibliotek z tego katalogu:
// prefiks {lib}, np. sbt "{lib}com/test".
// Wspolne ustawienia (fork, przelaczniki symulacji): lib/sbt/HwBuild.scala,
// dolaczone do meta-buildu przez project/build.sbt.

import HwBuild._

inThisBuild(buildSettings)
ThisBuild / organization := "org.newhope"
ThisBuild / version      := "0.1.0"

val jSerialComm = "com.fazecast" % "jSerialComm" % "2.11.0"

// ---- biblioteki (lib/build.sbt) ------------------------------------------------

lazy val hwlib = RootProject(file("lib"))
def libRef(id: String) = ProjectRef(file("lib"), id)

lazy val vertebra  = libRef("vertebra")
lazy val core      = libRef("core")
lazy val com       = libRef("com")
lazy val front_end = libRef("front_end")
// mimas_v2 z biblioteki: MCB (newhope.mcb). Lokalny mimas_v2 nizej ma wiecej
// (BinToBcd, demo 7-seg); SevenSegMux jest w obu i jest identyczny.
lazy val libMimas  = libRef("mimas_v2")

// ---- ustawienia projektow NewHope ----------------------------------------------

/** Uklad katalogow modulu sprzetowego: hw/spinal/{main,test}, RTL w hw/gen. */
def hwLayoutNh : Seq[Setting[_]] = Seq(
  Compile / scalaSource := baseDirectory.value / "hw/spinal/main",
  Test    / scalaSource := baseDirectory.value / "hw/spinal/test"
)

/** Wspolne ustawienia modulu sprzetowego. */
def hwSettings : Seq[Setting[_]] = hwLayoutNh ++ forkSettings ++ Seq(
  libraryDependencies ++= spinal :+ (scalatest % Test),
  publish / skip := true,

  // Wersja zachowujaca cache, czyli kasujaca tylko workspace'y przebiegow
  simClean := {
    val log = streams.value.log
    val ws  = baseDirectory.value / "simWorkspace"
    if (ws.exists) {
      val doomed = IO.listFiles(ws).filter(f => f.isDirectory && !f.getName.startsWith("."))
      doomed.foreach(IO.delete)
      log.info(s"usunieto ${doomed.size} workspace'ow, cache zostal")
    }
    cleanDir(baseDirectory.value / "hw" / "gen", log)
  },
)

// ---- projekty ------------------------------------------------------------------

lazy val asyncfifo = (project in file("asyncfifo"))
  .dependsOn(vertebra)
  .settings(hwSettings)

lazy val mimas_v2 = (project in file("mimas_v2"))
  .dependsOn(vertebra)
  .settings(hwSettings)

lazy val aht10 = (project in file("aht10"))
  .dependsOn(vertebra, com % "compile->compile;test->test", mimas_v2)
  .settings(hwSettings)

lazy val sandbox = (project in file("sandbox"))
  .dependsOn(vertebra)
  .settings(hwSettings)

// Dema MCB (McbDemoTop, MigTop, MIG 32 b / 100 MHz); kontroler w lib/mimas_v2.
lazy val mcb_demo = (project in file("mcb_demo"))
  .dependsOn(vertebra, mimas_v2, libMimas)
  .settings(hwSettings)

lazy val vgademo = (project in file("vgademo"))
  .dependsOn(vertebra, mimas_v2, libMimas)
  .settings(hwSettings)

lazy val uartdemo = (project in file("uart_demo"))
  .dependsOn(vertebra, mimas_v2)
  .settings(hwSettings)

// ---------------------------------------------------------------------
//  vertebra-hil: weryfikacja IP na sprzecie (vertebra-hil.md)
// ---------------------------------------------------------------------

/** Harness FPGA: Spinal -> Verilog (hw/gen) -> ISE. Zwykly modul sprzetowy,
  * symulacje harnessu moga biec rownolegle. */
lazy val hilFpga = (project in file("vertebra-hil/fpga"))
  .dependsOn(vertebra, mimas_v2, com % "compile->compile;test->test",
             front_end % "compile->compile;test->test")   // DcGolden w FeCheck
  .settings(hwSettings, name := "vertebra-hil-fpga")

/** Orkiestrator PC: porty szeregowe, suity hw_*. Kod nie jest sprzetem,
  * wiec uklad katalogow jak w vertebrze. Jedno stanowisko -> testy szeregowo. */
lazy val hil = (project in file("vertebra-hil/host"))
  .dependsOn(vertebra, hilFpga % "compile->compile;test->test")   // test: FeCheck, FeStimulus
  .settings(
    name := "vertebra-hil-host",
    srcLayout,
    forkSettings,
    libraryDependencies ++= spinal ++ Seq(scalatest, jSerialComm),
    Test / parallelExecution := false,
    publish / skip := true
  )

lazy val root = (project in file("."))
  .aggregate(asyncfifo, aht10, sandbox, hilFpga, hil)
  .settings(publish / skip := true)
