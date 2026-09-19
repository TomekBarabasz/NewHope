ThisBuild / scalaVersion := "2.13.14"
ThisBuild / organization := "org.newhope"
ThisBuild / version := "0.1.0"

val spinalVersion    = "1.12.3"
val spinalCore       = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib        = "com.github.spinalhdl" %% "spinalhdl-lib"  % spinalVersion
val spinalIdslPlugin = compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)
val scalatest        = "org.scalatest" %% "scalatest" % "3.2.19"
val spinal           = Seq(spinalCore, spinalLib, spinalIdslPlugin)

lazy val wavesOn     = taskKey[Unit]("FST wlaczone dla tej sesji sbt")
lazy val wavesOff    = taskKey[Unit]("FST wylaczone dla tej sesji sbt")
lazy val wavesStatus = taskKey[Unit]("Aktualny stan")

lazy val backendVrl  = taskKey[Unit]("Symulacje na Verilatorze")
lazy val backendGhdl = taskKey[Unit]("Symulacje na GHDL")
lazy val simStatus   = taskKey[Unit]("Aktualne ustawienia symulacji")
lazy val simClean    = taskKey[Unit]("Usuwa simWorkspace i wygenerowany RTL")

ThisBuild / wavesOn  := { System.setProperty("vertebra.waves", "1"); println("waves: ON") }
ThisBuild / wavesOff := { System.setProperty("vertebra.waves", "0"); println("waves: OFF") }
ThisBuild / backendVrl  := { System.setProperty("vertebra.backend", "verilator"); println("backend: verilator") }
ThisBuild / backendGhdl := { System.setProperty("vertebra.backend", "ghdl");      println("backend: ghdl") }
ThisBuild / simStatus   := println(
  s"waves   = ${sys.props.getOrElse("vertebra.waves",   "<domyslne>")}\n" +
  s"backend = ${sys.props.getOrElse("vertebra.backend", "<domyslne>")}")

def cleanDir(d : File, log : Logger) : Unit =
  if (d.exists) {
    val n = (d ** "*").get.size
    IO.delete(d)
    log.info(s"usunieto ${d.getName} ($n plikow)")
  } else log.info(s"${d.getName}: nie ma czego usuwac")

/** Wspolne ustawienia modulu sprzetowego. */
def hwSettings : Seq[Setting[_]] = Seq(
  Compile / scalaSource := baseDirectory.value / "hw/spinal/main",
  Test    / scalaSource := baseDirectory.value / "hw/spinal/test",

  // fork MUSI byc tutaj, nie luzem na koncu pliku - patrz wyzej
  Test / fork          := true,
  Test / baseDirectory := baseDirectory.value,
  // --jobs > 1
  Test / javaOptions += "-Xmx4g",

  Compile / run / fork          := true,
  Compile / run / baseDirectory := baseDirectory.value,

  // envVars jest TASKIEM, wiec sys.props czyta sie przy kazdym uruchomieniu.
  // To most miedzy JVM sbt (gdzie siedza wavesOn/backendGhdl) a forkowana
  // JVM testu. javaOptions byloby SettingKey i zamrozilo wartosc.
  Test / envVars ++= Map(
    "VERTEBRA_WAVES"   -> sys.props.getOrElse("vertebra.waves",   "0"),
    "VERTEBRA_BACKEND" -> sys.props.getOrElse("vertebra.backend", "verilator")),
  Compile / run / envVars ++= Map(
    "VERTEBRA_WAVES"   -> sys.props.getOrElse("vertebra.waves",   "0"),
    "VERTEBRA_BACKEND" -> sys.props.getOrElse("vertebra.backend", "verilator")),

  libraryDependencies ++= spinal :+ (scalatest % Test),
  publish / skip := true,
  
  // Wersja zachowująca cache, czyli kasująca tylko workspace'y przebiegów
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

lazy val vertebra = (project in file("vertebra"))
  .settings(
    name := "vertebra",
    Compile / scalaSource := baseDirectory.value / "src/main",
    Test    / scalaSource := baseDirectory.value / "src/test",
    libraryDependencies ++= spinal :+ scalatest
  )
  
lazy val asyncfifo = (project in file("asyncfifo"))
  .dependsOn(vertebra)
  .settings(hwSettings)

lazy val i2c = (project in file("i2c"))
  .dependsOn(vertebra)
  .settings(hwSettings)

lazy val mimas_v2 = (project in file("mimas_v2"))
  .dependsOn(vertebra)
  .settings(hwSettings)

lazy val aht10 = (project in file("aht10"))
  .dependsOn(vertebra, i2c % "compile->compile;test->test", mimas_v2)
  .settings(hwSettings)

lazy val sandbox = (project in file("sandbox"))
  .dependsOn(vertebra)
  .settings(hwSettings)

lazy val mcb = (project in file("mcb"))
  .dependsOn(vertebra, mimas_v2)
  .settings(hwSettings)

lazy val vgademo = (project in file("vgademo"))
  .dependsOn(vertebra, mimas_v2, mcb)
  .settings(hwSettings)

lazy val root = (project in file("."))
  .aggregate(vertebra, asyncfifo, i2c, aht10, sandbox)
  .settings(publish / skip := true)
