package newhope.vertebra.hil

import spinal.core._

// =====================================================================
//  Prymitywy Spartan-6 uzywane przez top-levele na plytke. Tylko do
//  generowania Verilogu dla ISE - symulacja testuje harness bez nich.
// =====================================================================

/** DCM_CLKGEN ze stalym M/D (UG382). PROG* nieuzywane - dynamiczne M/D
  * dojdzie osobnym krokiem (hw_clock_ratio_sweep). */
case class DcmClkGen(m : Int, d : Int, clkinPeriodNs : Double) extends BlackBox {
  require(m >= 2 && m <= 256 && d >= 1 && d <= 256, s"DCM_CLKGEN M/D $m/$d poza zakresem")
  setDefinitionName("DCM_CLKGEN")
  addGeneric("CLKFX_MULTIPLY", m)
  addGeneric("CLKFX_DIVIDE", d)
  addGeneric("CLKIN_PERIOD", clkinPeriodNs)
  addGeneric("SPREAD_SPECTRUM", "NONE")
  addGeneric("STARTUP_WAIT", "FALSE")

  val io = new Bundle {
    val CLKIN     = in  Bool()
    val RST       = in  Bool()
    val FREEZEDCM = in  Bool()
    val PROGCLK   = in  Bool()
    val PROGDATA  = in  Bool()
    val PROGEN    = in  Bool()
    val CLKFX     = out Bool()
    val CLKFX180  = out Bool()
    val CLKFXDV   = out Bool()
    val LOCKED    = out Bool()
    val PROGDONE  = out Bool()
    val STATUS    = out Bits(2 bits)
  }
  noIoPrefix()
}

object DcmClkGen {
  /** Najblizsze M/D dla fout z fin (oba w Hz); zwraca (M, D, fout). */
  def best(finHz : Long, foutHz : Long) : (Int, Int, Double) = {
    val c = for (m <- 2 to 256; d <- 1 to 256) yield (m, d, finHz.toDouble * m / d)
    c.minBy { case (_, _, f) => scala.math.abs(f - foutHz) }
  }
}

case class Bufg() extends BlackBox {
  setDefinitionName("BUFG")
  val io = new Bundle { val I = in Bool(); val O = out Bool() }
  noIoPrefix()
}
