package newhope.mcb

import spinal.core._
import newhope.vertebra.sim.{SimEnv, SimBackend}

object Config {
  import newhope.vertebra.sim.SimBackendOps._

  def spinal = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(resetActiveLevel = HIGH),
    defaultClockDomainFrequency = FixedFrequency(100 MHz),  //to jest potrzebne dla Timeout
    onlyStdLogicVectorAtTopLevelIo = false
  )

  /** MUSI byc def. withBackend/withWorkspaceName mutuja w miejscu
    * (patrz SpinalSimConfig), wiec val oznaczalby wspoldzielony stan
    * miedzy suitami i wyscig przy FilterSweep --jobs > 1. */
  def simFor(b : SimBackend) = SimEnv(spinal).withBackend(b)

  /** Backend z otoczenia - patrz SimBackend.default. */
  def sim = simFor(SimBackend.default)
}

object McbDemoClocks {
  /** Zegary MigTop (surowy MIG, bez McbCore) sprzed przejscia na fpga-hw-lib: PLL z MIG-128b-166MHz
    * (CLKFBOUT_MULT 20, DIVCLK_DIVIDE 3, CLKOUT2 16, CLKOUT3 8), czyli
    * pamiec 166,67 MHz, UI 41,67 MHz, kalibracja 83,3 MHz - to samo, co
    * zakladal dawny MigConfig (memClkPeriod = 6000, uiClkDivider = 4).
    * W lib/mimas_v2 PLL ustawia Scala (generics S6Lpddr), wiec te liczby
    * ida wprost do s6_lpddr.v. */
  def mig(dataWidth : Int = 32) : MigConfig = MigConfig(
    dataWidth    = dataWidth,
    clkFbOutMult = 20, divClkDivide = 3,
    clkOut0Divide = 2, clkOut2Divide = 16, clkOut3Divide = 8)
}
