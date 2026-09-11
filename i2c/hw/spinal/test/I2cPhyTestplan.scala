package newhope.i2c

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import scala.util.Random

// =====================================================================
//  Suita zbudowana na testpointach z OpenTitan hw/ip/i2c/data/
//  i2c_testplan.hjson (Apache 2.0). Nazwy testow celowo takie same jak
//  tam - latwiej wrocic do zrodla po szczegoly.
//
//  Odrzucone jako nieaplikowalne: csr_*, tl_*, *_fifo_*, *_intr*,
//  alert_test, target_* (jestesmy tylko masterem), host_override.
// =====================================================================
abstract class I2cPhyTestplan(label : String,
                              build : I2cGenerics => I2cPhyBase) extends AnyFunSuite {
  import I2cEvent._
  import I2cPhyCmdMode._

  // i2c_timing_parameters_cg + i2c_operating_mode_cg: "Cover the SCL
  // frequency". Ostatnia pozycja to dolna granica quarterCycles - odpowiednik
  // "ensure sufficient test coverage around lower bound of THIGH".
  val configs = Seq(
    "std100k"  -> I2cGenerics(100 MHz, 100 kHz),
    "fast400k" -> I2cGenerics(100 MHz, 400 kHz),
    "fmp1M"    -> I2cGenerics(100 MHz,   1 MHz),
    "qmin"     -> I2cGenerics(100 MHz, 12.5 MHz)   // quarterCycles == 2
  )

  for ((cfgName, g) <- configs) {

    lazy val dut : SimCompiled[I2cPhyBase] = Config.sim
      .withFstWave
      .workspaceName(s"${label}_$cfgName")
      .compile { build(g) }

    def scenario(name : String)(body : (I2cPhyBase, I2cBusModel, I2cMonitor) => Unit) : Unit =
      test(s"$cfgName / $name") {
        dut.doSim(s"${label}_${cfgName}_$name", seed = 42) { d =>
          val bus = new I2cBusModel(d)
          val mon = new I2cMonitor(d, bus)
          d.io.cmd.valid #= false
          d.io.pins.scl.read #= true
          d.io.pins.sda.read #= true
          d.clockDomain.forkStimulus(period = 10)
          bus.start(); mon.start()
          d.clockDomain.waitSampling(5)
          body(d, bus, mon)
        }
      }

    def cmd(d : I2cPhyBase, mode : SpinalEnumElement[I2cPhyCmdMode.type],
            data : Boolean = true) : Unit = {
      d.io.cmd.valid        #= true
      d.io.cmd.payload.mode #= mode
      d.io.cmd.payload.data #= data
      d.clockDomain.waitSamplingWhere(d.io.cmd.ready.toBoolean)
      d.io.cmd.valid #= false
    }

    def byte(d : I2cPhyBase, v : Int) : Unit =
      for (i <- 7 downto 0) cmd(d, BIT, ((v >> i) & 1) != 0)

    // ---------------------------------------------------------------
    //  host_smoke
    //  "Smoke test in which random transactions are sent to the DUT
    //   and received asynchronously with scoreboard checks."
    // ---------------------------------------------------------------
    scenario("host_smoke") { (d, _, mon) =>
      val rng  = new Random(1)
      val sent = mutable.ArrayBuffer[Event]()

      for (_ <- 0 until 8) {
        cmd(d, START); sent += Start
        val payload = Seq.fill(1 + rng.nextInt(3))(rng.nextInt(256))
        for (v <- payload) {
          byte(d, v)
          sent ++= (7 downto 0).map(i => Bit(((v >> i) & 1) != 0))
          cmd(d, BIT, data = true)          // szczelina na ACK
          sent += Bit(true)
        }
        cmd(d, STOP); sent += Stop
      }
      d.clockDomain.waitSampling(20)
      mon.expect(sent.toSeq : _*)
    }

    // ---------------------------------------------------------------
    //  host_mode_config_perf
    //  "Calculate expected bus performance based on configuration.
    //   Ensure that SCL frequency during data bytes matches expectation."
    // ---------------------------------------------------------------
    scenario("host_mode_config_perf") { (d, _, mon) =>
      cmd(d, START)
      byte(d, 0x5A)
      cmd(d, STOP)
      d.clockDomain.waitSampling(20)

      // Kazda komenda to 4 cwiartki + 1 cykl na zatrzasniecie kolejnej.
      val expectedHigh = 2 * g.quarterCycles * 10   // period = 10 jednostek sim
      assert(math.abs(mon.highLen - expectedHigh) <= 10 + 10,
             s"tHIGH = ${mon.highLen}, oczekiwano ~$expectedHigh")
      assert(mon.lowLen >= 2 * g.quarterCycles * 10,
             s"tLOW = ${mon.lowLen} za krotkie")
    }

    // ---------------------------------------------------------------
    //  host_mode_clock_stretching
    //  "Verify robust for long and short periods of clock stretching.
    //   Target-deassertion may not be synchronous to the Host's clock."
    // ---------------------------------------------------------------
    scenario("host_mode_clock_stretching") { (d, bus, mon) =>
      val rng = new Random(2)
      cmd(d, START)

      fork {
        while (true) {
          // czekamy az master puszcza SCL, potem trzymamy losowo dlugo
          d.clockDomain.waitSamplingWhere(d.io.pins.scl.write.toBoolean)
          bus.stretch(rng.nextInt(6 * g.quarterCycles) + 1)
          d.clockDomain.waitSamplingWhere(!d.io.pins.scl.write.toBoolean)
        }
      }

      byte(d, 0xC3)
      cmd(d, STOP)
      d.clockDomain.waitSampling(20)

      val expected = Start +: (7 downto 0).map(i => Bit(((0xC3 >> i) & 1) != 0)) :+ Stop
      mon.expect(expected : _*)
    }

    // ---------------------------------------------------------------
    //  host_rx_oversample (odpowiednik: filtr wejsciowy)
    //  Glitch krotszy niz okno filtra musi zostac zignorowany.
    // ---------------------------------------------------------------
    scenario("host_rx_oversample") { (d, bus, mon) =>
      cmd(d, START)

      fork {
        d.clockDomain.waitSamplingWhere(d.io.pins.scl.write.toBoolean)
        bus.sdaPull = true                        // glitch na SDA...
        d.clockDomain.waitSampling(g.filterWindow - 1)
        bus.sdaPull = false                       // ...krotszy niz okno
      }

      cmd(d, BIT, data = true)
      cmd(d, STOP)
      d.clockDomain.waitSampling(20)
      mon.expect(Start, Bit(true), Stop)          // glitch niewidoczny
    }
  }

  // -----------------------------------------------------------------
  //  Ponizsze testpointy sa aplikowalne, ale wymagaja funkcji, ktorych
  //  I2cPhy jeszcze nie ma. Oznaczone jako pending, zeby byly widoczne
  //  w raporcie - tak samo jak OpenTitan trzyma "No Tests Implemented".
  // -----------------------------------------------------------------

  // multi_controller_arbitration_lost_interference
  // "The testbench pulls SDA low during the transaction (while the DUT
  //  has released it). After determining it has lost arbitration, the
  //  DUT-Controller should have released the bus."
  test("multi_controller_arbitration_lost_interference") {
    pending   // I2cPhy nie ma wykrywania kolizji na SDA (brak sda_chk)
  }

  // multi_controller_clock_synchronization
  // "Confirm the final synchronized bus SCL is comprised of tlow of the
  //  controller with the longer value, thigh of the shorter."
  test("multi_controller_clock_synchronization") {
    pending   // I2cPhy nie przeladowuje licznika na opadajacym SCL obcego mastera
  }

  // host_timeout / bus_timeout
  // "Program timeout values into TIMEOUT_CTRL. Ensure stretch_timeout
  //  is asserted." - dzis stretching moze trwac w nieskonczonosc.
  test("host_stretch_timeout") {
    pending   // brak timeoutu na clock stretching
  }

  // Nie ma odpowiednika w OpenTitanie (tam tBUF jest w CSR), ale to
  // znana luka w tablicy cwiartek.
  test("bus_free_time_after_stop") {
    pending   // brak tBUF po STOP
  }
}

class I2cPhyFsmTestplan   extends I2cPhyTestplan("fsm",   g => I2cPhyFsm(g))
class I2cPhyTableTestplan extends I2cPhyTestplan("table", g => I2cPhyTable(g))
