// vertebra/src/main/scala/vertebra/sim/SimEnv.scala
package vertebra.sim

import spinal.core.SpinalConfig
import spinal.core.sim._

object SimEnv {

  /** Fale kosztuja czas i dysk. Domyslnie wylaczone, VERTEBRA_WAVES=1 wlacza. */
  val waves : Boolean =
    sys.env.get("VERTEBRA_WAVES").exists(v => v == "1" || v.equalsIgnoreCase("true"))

  def apply(spinal : SpinalConfig, workspace : String = "simWorkspace") : SpinalSimConfig = {
    val c = SimConfig.withConfig(spinal).workspacePath(workspace)
    if (waves) c.withFstWave else c
  }
}
