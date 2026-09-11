package newhope.asyncfifo

import spinal.core._
import spinal.core.sim._

//import spinal.lib.sim._
//import org.scalatest.funsuite.AnyFunSuite
//class CdcInjectDemoSim extends AnyFunSuite {
object CdcInjectDemoSim extends App {
  val width      = 6
  val srcDivider = 16

  lazy val compiled: SimCompiled[CdcInjectDemo] = Config.sim
    .withFstWave
    .compile {
      new CdcInjectDemo(width = width, srcDivider = srcDivider)
    }

  val simRandom = new scala.util.Random(42)

  /** Losowa maska: kazdy bit ustawiony niezaleznie z prawdopodobienstwem p. */
  def randomMask(w: Int, p: Double): BigInt = {
    var v = BigInt(0)
    for (i <- 0 until w) if (simRandom.nextDouble() < p) v |= BigInt(1) << i
    v
  }

  /** @return (bledy sciezki Graya, bledy sciezki binarnej) */
  def run(name: String, p: Double, updates: Int = 400): (Int, Int) = {
    var result = (0, 0)

    compiled.doSim(name, seed = 1) { dut =>
      SimTimeout(4000000)

      dut.io.chaosGray #= 0
      dut.io.chaosBin  #= 0

      dut.clkA.forkStimulus(period = 100) // 100 MHz
      dut.clkB.forkStimulus(period = 270) // ~37 MHz, niewspolmierny

      // Zaklocanie: co cykl clkB losujemy, ktore przerzutniki "nie zdazyly".
      // Obie sciezki dostaja niezalezne losowania z tego samego rozkladu.
      fork {
        while (true) {
          dut.clkB.waitSampling()
          dut.io.chaosGray #= randomMask(width, p)
          dut.io.chaosBin  #= randomMask(width, p)
        }
      }

      dut.clkA.waitSampling(srcDivider * updates)

      val g = dut.io.grayErrors.toInt
      val b = dut.io.binErrors.toInt
      result = (g, b)

      println(f"[$name%-16s] p=$p%.2f  ->  bledy Gray: $g%4d   bledy binarne: $b%4d")
    }

    result
  }

  println()
  println("=" * 70)
  println(s"Licznik $width-bitowy, $srcDivider cykli clkA na inkrementacje, 400 inkrementacji")
  println("=" * 70)

  // Kontrola: bez zaklocen obie sciezki musza byc czyste.
  // Jesli tu cokolwiek wyskoczy, to blad w stanowisku, nie w kodowaniu.
  val (grayClean, binClean) = run("bez_zaklocen", 0.0)

  // Wlasciwy eksperyment.
  val (grayNoisy, binNoisy) = run("z_zaklocenia", 0.5)

  println()
  assert(grayClean == 0 && binClean == 0, "stanowisko szumi samo z siebie - napraw to najpierw")
  assert(grayNoisy == 0, s"sciezka Graya nie powinna nigdy pekac, a zlapala $grayNoisy bledow")
  assert(binNoisy > 0, "sciezka binarna nie pekla - zwieksz liczbe inkrementacji albo p")

  println(s"Gray:     $grayNoisy bledow  -> kodowanie robi robote")
  println(s"Binarnie: $binNoisy bledow  -> wartosci, ktore nigdy nie istnialy w domenie zrodlowej")
  println()
  println("Przebiegi: simWorkspace/CdcInjectDemo/z_zaklocenia/wave.vcd")
  println("Porownaj io_srcValue, io_graySynced i io_binSynced na jednej osi czasu.")
}
