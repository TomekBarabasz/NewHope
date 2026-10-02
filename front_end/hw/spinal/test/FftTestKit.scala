package newhope.frontend

import scala.util.Random
import FftGolden.Cx

// =====================================================================
//  Bodzce specyficzne dla FFT. Ogolne narzedzia Stream (drive,
//  backpressure, monitor, waitFor, expectSeq, Traffic, idleReset, guard)
//  sa w newhope.vertebra.sim.StreamSim.
// =====================================================================
object FftTestKit {

  def full(w: Int) = (1L << (w - 1)) - 1
  def minV(w: Int) = -(1L << (w - 1))

  /** Skladowe rownomierne w pelnym zakresie w bitow. */
  def uniform(rng: Random, n: Int, w: Int): Vector[Cx] =
    Vector.fill(n)(Cx(rng.nextInt(1 << w) + minV(w), rng.nextInt(1 << w) + minV(w)))

  /** Szum albo ton na poziomie `db` dBFS (szczyt). */
  def atLevel(rng: Random, n: Int, w: Int, db: Double, tone: Boolean): Vector[Cx] = {
    val raw = Vector.tabulate(n) { i =>
      if (tone) { val f = 1 + rng.nextInt(n / 2 - 1); (math.cos(2 * math.Pi * f * i / n), math.sin(2 * math.Pi * f * i / n)) }
      else (rng.nextGaussian(), rng.nextGaussian())
    }
    val pk  = raw.map { case (a, b) => math.max(math.abs(a), math.abs(b)) }.max
    val amp = full(w) * math.pow(10, db / 20) / pk
    raw.map { case (a, b) => Cx(math.round(a * amp), math.round(b * amp)) }
  }

  /** Przypadki zlosliwe dla BFP: rogi zakresu, DC, przemiennie, impuls, zero. */
  def corners(rng: Random, n: Int, w: Int): Seq[(String, Vector[Cx])] = {
    val hi = full(w); val lo = minV(w)
    Seq(
      "zero"      -> Vector.fill(n)(Cx(0, 0)),
      "lsb"       -> (Cx(1, 0) +: Vector.fill(n - 1)(Cx(0, 0))),
      "dc_max"    -> Vector.fill(n)(Cx(hi, hi)),
      "dc_min"    -> Vector.fill(n)(Cx(lo, lo)),
      "alt"       -> Vector.tabulate(n)(i => if (i % 2 == 0) Cx(hi, lo) else Cx(lo, hi)),
      "impulse"   -> (Cx(lo, lo) +: Vector.fill(n - 1)(Cx(0, 0))),
      "rot45"     -> Vector.tabulate(n)(i => Seq(Cx(hi, hi), Cx(lo, hi), Cx(lo, lo), Cx(hi, lo))(i % 4))
    ) ++ (0 until 6).map(i => s"rog$i" -> Vector.fill(n)(Cx(if (rng.nextBoolean()) hi else lo,
                                                             if (rng.nextBoolean()) hi else lo)))
  }
}
