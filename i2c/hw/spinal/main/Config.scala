package newhope.i2c

import spinal.core._
import newhope.vertebra.sim.SimEnv

object Config {
  def spinal = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(resetActiveLevel = HIGH),
    onlyStdLogicVectorAtTopLevelIo = false
  )

  def sim = SimEnv(spinal)
}
