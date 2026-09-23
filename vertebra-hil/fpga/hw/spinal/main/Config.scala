package newhope.vertebra.hil

import spinal.core._
import newhope.vertebra.sim.{SimEnv, SimBackend}

/** Config modulu hilFpga (TESTING-STRATEGY.md §2.3).
  *
  * Reset aktywny wysoko, jak w IP: DUT-y instancjonowane w harnessie
  * (etap 2) i wstrzykiwanie resetu (HilResetInjector) zakladaja te
  * polaryzacje, tak samo jak *_random_reset w symulacji IP.
  *
  * Top-levele na plytke (HilEchoTop, pozniej I2sHarness) NIE uzywaja
  * domyslnej domeny: Mimas V2 nie ma linii resetu, wiec buduja wlasna
  * domene z resetKind = BOOT (wartosci poczatkowe z bitstreamu). */
object Config {
  import newhope.vertebra.sim.SimBackendOps._

  def spinal = SpinalConfig(
    targetDirectory = "hw/gen",   // wzgledem katalogu roboczego = vertebra-hil/fpga (fork)
    defaultConfigForClockDomains = ClockDomainConfig(resetActiveLevel = HIGH),
    onlyStdLogicVectorAtTopLevelIo = false
  )

  /** MUSI byc def: withBackend mutuje SpinalSimConfig w miejscu. */
  def simFor(b : SimBackend) = SimEnv(spinal).withBackend(b)

  def sim = simFor(SimBackend.default)
}
