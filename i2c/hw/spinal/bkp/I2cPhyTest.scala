package newhope.i2c

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import newhope.vertebra.sim.SimBackend

// =====================================================================
//  Suita kierowana - najprostsze testy, jedna konfiguracja, BEZ PLANU.
//  Zostaje jako miejsce do szybkiego debugowania: gdy cos padnie w
//  testplanie, tu sie to odtwarza w jednym przebiegu z fala.
//
//  I2cBusModel / I2cMonitor / I2cEvent -> I2cAgent.scala
//  cmd / byte / setup                  -> I2cSmoke.scala
//
//  DLACZEGO TO NIE DZIEDZICZY PO TestplanSuite
//  -------------------------------------------
//  Dwa powody, oba mechaniczne.
//
//  1. `implemented` w TestplanSuite jest polem instancji, wiec test
//     "testplan completeness" liczy kompletnosc PER SUITA. To jest
//     dobra semantyka: kazda implementacja DUT-a musi domknac plan
//     osobno. Ale znaczy tez, ze jeden plan rozlozony na dwie suity
//     (smoke tutaj, reszta w I2cPhyTestplan) daje DWIE niekompletne
//     suity i completeness wywala sie zawsze. Globalny rejestr w JVM
//     plus suita agregujaca to rozwiazuja, ale wymagaja gwarancji
//     kolejnosci, ktorej ScalaTest nie daje bez Suites/Sequential.
//     To ta sama decyzja co "pokrycie per-test vs globalne" (§5.1) -
//     odkladana razem z nia.
//
//  2. Jesli KAZDY test musi miec wpis w planie, znika miejsce na
//     pieciominutowe odtworzenie awarii. Dyscyplina ma bolec tam, gdzie
//     mierzysz postep, nie tam, gdzie debugujesz.
//
//  ZMIANA: usuniety `multi_controller_clock_synchronization`. Ten sam
//  testpoint jest odlozony w I2cPhyTestplan przez unimplemented() i tam
//  liczy sie do raportu. Dwa zolte wpisy na jedna luke to szum, a nie
//  informacja. Zakomentowane cialo tego testu zostalo przeniesione do
//  komentarza przy unimplemented w tamtym pliku - tutaj bylo martwe.
// =====================================================================
abstract class I2cPhySuite(label : String,
                           build : I2cGenerics => I2cPhyBase) extends AnyFunSuite {
  import I2cEvent._
  import I2cPhyCmdMode._
  import I2cSmoke.{cmd, byte, setup}

  val g = I2cGenerics(clkFrequency = 100 MHz, sclFrequency = 1 MHz)

  lazy val dut : SimCompiled[I2cPhyBase] = Config.sim
    .withFstWave
    .workspaceName(s"${label}_${SimBackend.default.label}")
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
      val v = 0xA5
      cmd(d, START)
      byte(d, v)
      cmd(d, STOP)
      d.clockDomain.waitSampling(20)
      val expected = Start +: (7 downto 0).map(i => Bit(((v >> i) & 1) != 0)) :+ Stop
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
}

class I2cPhyFsmTest   extends I2cPhySuite("fsm",   g => I2cPhyFsm(g))
class I2cPhyTableTest extends I2cPhySuite("table", g => I2cPhyTable(g))
