// vertebra/src/main/scala/vertebra/sim/SimEnv.scala
package newhope.vertebra.sim

import spinal.core.SpinalConfig
import spinal.core.sim._
import spinal.sim.GhdlFlags

sealed trait SimBackend { def label : String }
object SimBackend {
  case object Verilator extends SimBackend { val label = "vrl"  }
  case object Ghdl      extends SimBackend { val label = "ghdl" }

  def parse(s : String) : SimBackend = s match {
    case "verilator" | "vrl"  => Verilator
    case "ghdl"               => Ghdl
    case other => throw new IllegalArgumentException(
      s"--backend verilator|ghdl, nie $other")
  }
}

object SimBackendOps {
  /** Metody SpinalSimConfig mutuja w miejscu; withGHDL nie ma adnotacji
  * this.type, wiec ignorujemy jego wynik i zwracamy oryginal. */
  implicit class Ops[T <: SpinalSimConfig](val c : T) extends AnyVal {

    def simBackendVerilator : T = { c.withVerilator; c }

    def simBackendGhdl : T = {
      // Puste flagi celowo. Dokladac dopiero pod konkretny blad GHDL-a,
      // i pamietac, ze to flagi ELABORACJI (-e), nie analizy (-a).
      c.withGHDL(GhdlFlags())
      c
    }

    def withBackend(b : SimBackend) : T = b match {
      case SimBackend.Verilator => simBackendVerilator
      case SimBackend.Ghdl      => simBackendGhdl
    }
  }
}

object SimEnv {
  private def truthy(s : Option[String]) : Boolean =
    s.exists(v => v == "1" || v.equalsIgnoreCase("true"))

  /** MUSI byc def: property bywa ustawiane w runtime (FilterSweep --wave,
    * alias wavesOn w sbt), a val zamrozilby wartosc przy inicjalizacji. */
  def waves : Boolean = truthy(sys.props.get("vertebra.waves"))

  def apply(spinal : SpinalConfig, workspace : String = "simWorkspace") : SpinalSimConfig = {
    val c = SimConfig.withConfig(spinal).workspacePath(workspace)
    if (waves) c.withFstWave else c
  }
}
