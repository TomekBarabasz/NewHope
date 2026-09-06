ThisBuild / scalaVersion := "2.13.14"
ThisBuild / organization := "org.newhope"
ThisBuild / version := "0.1.0"

val spinalVersion    = "1.12.3"
val spinalCore       = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib        = "com.github.spinalhdl" %% "spinalhdl-lib"  % spinalVersion
val spinalIdslPlugin = compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)
val scalatest        = "org.scalatest" %% "scalatest" % "3.2.19"
val spinal           = Seq(spinalCore, spinalLib, spinalIdslPlugin)

addCommandAlias("wavesOn",  """set every Test / envVars := Map("VERTEBRA_WAVES" -> "1")""")
addCommandAlias("wavesOff", """set every Test / envVars := Map("VERTEBRA_WAVES" -> "0")""")

/** Wspolne ustawienia modulu sprzetowego. */
def hwSettings : Seq[Setting[_]] = Seq(
  Compile / scalaSource := baseDirectory.value / "hw/spinal/main",
  Test    / scalaSource := baseDirectory.value / "hw/spinal/test",

  // fork MUSI byc tutaj, nie luzem na koncu pliku - patrz wyzej
  Test / fork          := true,
  Test / baseDirectory := baseDirectory.value,
  Compile / run / fork          := true,
  Compile / run / baseDirectory := baseDirectory.value,

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
