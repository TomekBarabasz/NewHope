package newhope.i2s

import spinal.core._
import newhope.vertebra.sim.{SimEnv, SimBackend}

object Config {
  import newhope.vertebra.sim.SimBackendOps._

  def spinal = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(resetActiveLevel = HIGH),
    onlyStdLogicVectorAtTopLevelIo = false
  )

  /** MUSI byc def. withBackend/withWorkspaceName mutuja w miejscu
    * (patrz SpinalSimConfig), wiec val oznaczalby wspoldzielony stan
    * miedzy suitami i wyscig przy FilterSweep --jobs > 1. */
  def simFor(b : SimBackend) = SimEnv(spinal).withBackend(b)

  /** Backend z otoczenia - patrz SimBackend.default. */
  def sim = simFor(SimBackend.default)
}
