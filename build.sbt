ThisBuild / scalaVersion := "2.13.14"
ThisBuild / organization := "org.newhope"
ThisBuild / version := "0.1.0"

val spinalVersion    = "1.12.3"
val spinalCore       = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib        = "com.github.spinalhdl" %% "spinalhdl-lib"  % spinalVersion
val spinalIdslPlugin = compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)
val scalatest        = "org.scalatest" %% "scalatest" % "3.2.19"
val spinal           = Seq(spinalCore, spinalLib, spinalIdslPlugin)

lazy val wavesOn  = taskKey[Unit]("FST wlaczone dla tej sesji sbt")
lazy val wavesOff = taskKey[Unit]("FST wylaczone dla tej sesji sbt")
lazy val wavesStatus = taskKey[Unit]("Aktualny stan")

ThisBuild / wavesOn  := { System.setProperty("vertebra.waves", "1"); println("waves: ON") }
ThisBuild / wavesOff := { System.setProperty("vertebra.waves", "0"); println("waves: OFF") }
ThisBuild / wavesStatus := println(
  s"vertebra.waves = ${sys.props.getOrElse("vertebra.waves", "<nieustawione>")} " +
  s"(sprawdz przekazanie: show i2c/Test/envVars)")

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
  // To jest most miedzy JVM sbt (gdzie siedzi wavesOn/wavesOff) a forkowana
  // JVM testu. Gdyby to byl javaOptions - SettingKey - wartosc zamarzlaby
  // przy ladowaniu builda.
  Test / envVars += ("VERTEBRA_WAVES" -> sys.props.getOrElse("vertebra.waves", "0")),
  Compile / run / envVars += ("VERTEBRA_WAVES" -> sys.props.getOrElse("vertebra.waves", "0")),

  libraryDependencies ++= spinal :+ (scalatest % Test),
  publish / skip := true
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

lazy val root = (project in file("."))
  .aggregate(vertebra, asyncfifo, i2c)
  .settings(publish / skip := true)
