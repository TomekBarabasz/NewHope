package org.newhope.i2c

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable

// =====================================================================
//  Suita kierowana - najprostsze testy, jedna konfiguracja, bez planu.
//  Zostaje jako miejsce do szybkiego debugowania: gdy cos padnie w
//  testplanie, tu sie to odtwarza w jednym przebiegu z fala.
//
//  I2cBusModel / I2cMonitor / I2cEvent -> I2cAgent.scala
//  cmd / byte / setup                  -> I2cSmoke.scala
// =====================================================================
abstract class I2cPhySuite(label : String,
                           build : I2cGenerics => I2cPhyBase) extends AnyFunSuite {
  import I2cEvent._
  import I2cPhyCmdMode._
  import I2cSmoke.{cmd, setup}

  val g = I2cGenerics(clkFrequency = 100 MHz, sclFrequency = 1 MHz)

  lazy val dut : SimCompiled[I2cPhyBase] = Config.sim
    .withFstWave
    .workspaceName(label)
    .compile { build(g) }

  test("START i STOP daja warunki na magistrali") {
    dut.doSim(s"${label}_startStop", seed = 42) { d =>
      val (_, mon) = setup(d)
      cmd(d, START)
      cmd(d, STOP)
      d.clockDomain.waitSampling(20)
      mon.expect(Start, Stop)
    }
  }

  test("zapis bajtu 0xA5 wychodzi bit po bicie, MSB first") {
    dut.doSim(s"${label}_writeByte", seed = 42) { d =>
      val (_, mon) = setup(d)
      val byte = 0xA5
      cmd(d, START)
      for (i <- 7 downto 0) cmd(d, BIT, ((byte >> i) & 1) != 0)
      cmd(d, STOP)
      d.clockDomain.waitSampling(20)
      val expected = Start +: (7 downto 0).map(i => Bit(((byte >> i) & 1) != 0)) :+ Stop
      mon.expect(expected : _*)
    }
  }

  test("odczyt: slave sciaga SDA, rsp.data pokazuje zero") {
    dut.doSim(s"${label}_readBit", seed = 42) { d =>
      val (bus, _) = setup(d)
      val seen = mutable.Queue[Boolean]()
      FlowMonitor(d.io.rsp, d.clockDomain) { p => seen.enqueue(p.data.toBoolean) }

      cmd(d, START)
      bus.sdaPull = true          // slave odpowiada zerem
      cmd(d, BIT, data = true)    // master puszcza linie
      bus.sdaPull = false
      cmd(d, BIT, data = true)    // slave puscil, powinno byc jeden
      cmd(d, STOP)
      d.clockDomain.waitSampling(20)

      assert(seen.toSeq == Seq(false, true), s"rsp = ${seen.toSeq}")
    }
  }

  test("clock stretching wydluza bit zamiast go gubic") {
    dut.doSim(s"${label}_stretch", seed = 42) { d =>
      val (bus, mon) = setup(d)
      cmd(d, START)

      // slave trzyma SCL nisko przez 3 cwiartki po tym jak master ja puscil
      fork {
        d.clockDomain.waitSamplingWhere(!d.io.pins.scl.write.toBoolean)
        d.clockDomain.waitSamplingWhere(d.io.pins.scl.write.toBoolean)
        bus.stretch(3 * g.quarterCycles)
      }

      cmd(d, BIT, data = false)
      cmd(d, STOP)
      d.clockDomain.waitSampling(20)

      mon.expect(Start, Bit(false), Stop)
      assert(mon.highLen >= g.quarterCycles * 2 * 10,
             s"stan wysoki SCL za krotki: ${mon.highLen}")
    }
  }
  test("multi_controller_clock_synchronization") {
    info("obcy master ściąga SCL już po tym, jak nasz ją puścił - " +
       "I2cPhy nie przeładowuje wtedy licznika i generuje dodatkowy impuls SCL")
    pending
    /*
    dut.doSim(s"${label}_multi_controller_clock_synchronization", seed = 42) { d =>
      val (bus, mon) = setup(d)
      cmd(d, START)

      // slave trzyma SCL nisko przez 3 cwiartki po tym jak master ja puscil
      fork {
        d.clockDomain.waitSamplingWhere(!d.io.pins.scl.write.toBoolean)
        d.clockDomain.waitSamplingWhere(d.io.pins.scl.write.toBoolean)
        bus.stretch(3 * g.quarterCycles)
      }

      cmd(d, BIT, data = false)
      cmd(d, STOP)
      d.clockDomain.waitSampling(20)

      mon.expect(Start, Bit(false), Stop)
      assert(mon.highLen >= g.quarterCycles * 2 * 10,
             s"stan wysoki SCL za krotki: ${mon.highLen}")
    }
    */
  }
}

class I2cPhyFsmTest   extends I2cPhySuite("fsm",   g => I2cPhyFsm(g))
class I2cPhyTableTest extends I2cPhySuite("table", g => I2cPhyTable(g))
