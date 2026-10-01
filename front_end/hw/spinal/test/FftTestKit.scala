package newhope.frontend

import spinal.core._
import spinal.core.sim._
import spinal.lib.{math => _, _}   // wszystko z spinal.lib poza math
import spinal.sim.SimThread
import scala.collection.mutable
import scala.util.Random
import newhope.vertebra._
import FftGolden.Cx

// =====================================================================
//  Model magistrali i monitor dla portow Stream. Wszystkie losowania
//  ida przez przekazany Random ze stalym ziarnem (TESTING-STRATEGY 4.6).
// =====================================================================
object FftTestKit {

  /** Parametry ruchu: prawdopodobienstwo przerwy przed kazdym
    * elementem wejscia i prawdopodobienstwo ready na wyjsciu. */
  final case class Traffic(inGap: Double, outReady: Double)
  val smooth  = Traffic(0.0, 1.0)
  val jittery = Traffic(0.3, 0.6)
  val choked  = Traffic(0.5, 0.15)

  /** Model nadajnika: wystawia kolejne elementy, trzyma payload do handshake'u. */
  def drive[T <: Data](s: Stream[T], cd: ClockDomain, rng: Random, gap: Double)
                      (items: Seq[T => Unit]): SimThread = fork {
    s.valid #= false
    for (set <- items) {
      while (gap > 0 && rng.nextDouble() < gap) cd.waitSampling()
      set(s.payload)
      s.valid #= true
      cd.waitSamplingWhere(s.ready.toBoolean)
      s.valid #= false
    }
  }

  /** Model odbiornika: losowe ready. */
  def backpressure(s: Stream[_ <: Data], cd: ClockDomain, rng: Random, p: Double): SimThread = fork {
    while (true) {
      s.ready #= rng.nextDouble() < p
      cd.waitSampling()
    }
  }

  /** Monitor: niezalezny od DUT-a odczyt handshake'ow. */
  def monitor[T <: Data, A](s: Stream[T], cd: ClockDomain)(get: T => A): mutable.ArrayBuffer[A] = {
    val buf = mutable.ArrayBuffer[A]()
    cd.onSamplings {
      if (s.valid.toBoolean && s.ready.toBoolean) buf += get(s.payload)
    }
    buf
  }

  def waitFor(cd: ClockDomain, cond: => Boolean, limit: Int, what: String): Int = {
    var c = 0
    while (!cond) {
      cd.waitSampling(); c += 1
      assert(c < limit, s"timeout ($limit cykli) czekajac na: $what")
    }
    c
  }

  // ---- scoreboard --------------------------------------------------------
  def expectSeq[A](got: collection.Seq[A], exp: collection.Seq[A], what: String): Unit = {
    assert(got.size == exp.size, s"$what: ${got.size} elementow, oczekiwano ${exp.size}")
    val bad = got.indices.find(i => got(i) != exp(i))
    bad.foreach { i =>
      assert(false, s"$what: pierwsza niezgodnosc na pozycji $i: jest ${got(i)}, oczekiwano ${exp(i)}")
    }
  }

  // ---- bodzce --------------------------------------------------------------
  def full(w: Int)   = (1L << (w - 1)) - 1
  def minV(w: Int)   = -(1L << (w - 1))

  def uniform(rng: Random, n: Int, w: Int): Vector[Cx] =
    Vector.fill(n)(Cx(rng.nextInt(1 << w) + minV(w), rng.nextInt(1 << w) + minV(w)))

  /** Szum i ton na poziomie `db` dBFS (szczyt). */
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

  // ---- konformancja portow (vertebra) ---------------------------------------
  def conformance(cd: ClockDomain, ports: Seq[StreamPortHandle]): Unit =
    ports.foreach(p => StreamConformance.all(cd, p))

  /** Testpointy StreamConformance realizowane przez wspolny scenariusz ruchu. */
  val streamChecks = Seq("payload_stable", "reset_quiet", "backpressure")

  /** Reset w trakcie bezczynnosci, pod okiem checkerow. */
  def idleReset(cd: ClockDomain): Unit = {
    cd.assertReset()
    cd.waitActiveEdge(20)      // surowe zbocza, niezależne od resetu
    cd.deassertReset()
    cd.waitSampling(5)
  }
}
