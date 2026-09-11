// vertebra/src/main/scala/vertebra/sim/SimEnv.scala
package newhope.vertebra.sim

import spinal.core.SpinalConfig
import spinal.core.sim._

sealed trait SimBackend { def label : String }
object SimBackend {
  case object Verilator extends SimBackend { val label = "vrl"  }
  case object GhdlMcode extends SimBackend { val label = "ghdl" }

  def parse(s : String) : SimBackend = s match {
    case "verilator" | "vrl"  => Verilator
    case "ghdl"      | "mcode"=> GhdlMcode
    case other => throw new IllegalArgumentException(
      s"--backend verilator|ghdl, nie $other")
  }
}

object SimEnv {

  /** Fale kosztuja czas i dysk. Domyslnie wylaczone, VERTEBRA_WAVES=1 wlacza. */
  val waves : Boolean =
    sys.env.get("VERTEBRA_WAVES").exists(v => v == "1" || v.equalsIgnoreCase("true"))

  def apply(spinal : SpinalConfig, workspace : String = "simWorkspace") : SpinalSimConfig = {
    val c = SimConfig.withConfig(spinal).workspacePath(workspace)
    if (waves) c.withFstWave else c
  }
}
