package org.newhope.i2c

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._
import org.scalatest.funsuite.AnyFunSuite

class I2cPhySuite(label: String, 
                 build: I2cGenerics => I2cPhyBase) extends AnyFunSuite {
  lazy val dut: SimCompiled[I2cPhyBase] = Config.sim
    .withFstWave
    .compile { build(I2cGenerics(clkFrequency = 100 MHz)) }

  test("dummy") {
    dut.doSim("dummy") { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      assert(dut.io.cmd.ready.toBoolean == false)
    }
  }
}

class I2cPhyFsmTest   extends I2cPhySuite("fsm",   g => new I2cPhyFsm(g))
class I2cPhyTableTest extends I2cPhySuite("table", g => new I2cPhyTable(g))
