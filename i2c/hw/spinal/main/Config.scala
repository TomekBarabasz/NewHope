package org.newhope.i2c

import spinal.core._
import vertebra.sim.SimEnv

object Config {
  def spinal = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(resetActiveLevel = HIGH),
    onlyStdLogicVectorAtTopLevelIo = false
  )

  def sim = SimEnv(spinal)
}
