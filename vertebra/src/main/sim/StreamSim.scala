// lib/vertebra/src/main/sim/StreamSim.scala
package newhope.vertebra.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib.{math => _, _}   // bez spinal.lib.math: math.* = scala.math
import spinal.sim.SimThread
import scala.collection.mutable
import scala.util.Random

// =====================================================================
//  Wspolne narzedzia symulacji dla portow Stream: model nadajnika,
//  model odbiornika, monitor, scoreboard sekwencji, reset i limit czasu.
//  Nic tu nie wie o konkretnym IP - bodzce specyficzne dla bloku
//  (np. FftTestKit.uniform/atLevel/corners) zostaja w jego projekcie.
//
//  Wszystkie losowania ida przez przekazany Random ze stalym ziarnem
//  (TESTING-STRATEGY 4.6).
//
//    import newhope.vertebra.sim.StreamSim._
// =====================================================================
object StreamSim {

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

  /** Czeka, az warunek bedzie spelniony; po `limit` cyklach rzuca. */
  def waitFor(cd: ClockDomain, cond: => Boolean, limit: Int, what: String): Int = {
    var c = 0
    while (!cond) {
      cd.waitSampling(); c += 1
      assert(c < limit, s"timeout ($limit cykli) czekajac na: $what")
    }
    c
  }

  /** Scoreboard: ta sama dlugosc, te same elementy, w tej samej kolejnosci.
    * collection.Seq, bo monitor oddaje ArrayBuffer (Scala 2.13). */
  def expectSeq[A](got: collection.Seq[A], exp: collection.Seq[A], what: String): Unit = {
    assert(got.size == exp.size, s"$what: ${got.size} elementow, oczekiwano ${exp.size}")
    got.indices.find(i => got(i) != exp(i)).foreach { i =>
      assert(false, s"$what: pierwsza niezgodnosc na pozycji $i: jest ${got(i)}, oczekiwano ${exp(i)}")
    }
  }

  /** Reset w trakcie bezczynnosci, pod okiem checkerow.
    * waitActiveEdge, NIE waitSampling: przy resecie asynchronicznym
    * waitSampling pomija zbocza w trakcie resetu i czekaloby w nieskonczonosc
    * (ten sam powod co w StreamConformance.quietDuringReset). */
  def idleReset(cd: ClockDomain, cycles: Int = 20): Unit = {
    cd.assertReset()
    cd.waitActiveEdge(cycles)
    cd.deassertReset()
    cd.waitSampling(5)
  }

  /** Twardy limit czasu symulacji: zawieszenie ma byc bledem testu, nie
    * wiszacym sbt. Wolac zaraz po forkStimulus. Domyslnie 2 mln cykli
    * przy period = 10. */
  def guard(cycles: Long = 2000000L, period: Long = 10): Unit = SimTimeout(cycles * period)
}
