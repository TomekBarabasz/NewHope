package newhope.asyncfifo

import spinal.core._
import spinal.core.sim._

import scala.util.Random

object SimClocks {

  /**
   * Zegar z losowa faza startowa i jitterem okresu.
   *
   * `forkStimulus` daje idealny, staly okres - a wtedy dwa zegary o wspolmiernych
   * okresach potrafia utknac w jednej relacji fazowej na cala symulacje i nigdy
   * nie odwiedzic tej niewygodnej. Losowa faza plus jitter sprawiaja, ze zbocza
   * dryfuja przez wszystkie mozliwe wzajemne polozenia.
   *
   * Uwaga: okresy podawaj duze (setki jednostek), bo jitter liczy sie na liczbach
   * calkowitych.
   */
  def forkJitterClock(cd: ClockDomain, period: Int, jitterPercent: Int, phase: Int): Unit = {
    val amp = period * jitterPercent / 100
    cd.fallingEdge()
    fork {
      sleep(phase)
      while (true) {
        val p    = period + (if (amp > 0) Random.nextInt(2 * amp + 1) - amp else 0)
        val half = p / 2
        sleep(half)
        cd.risingEdge()
        sleep(p - half)
        cd.fallingEdge()
      }
    }
  }

  /** Trzyma reset przez `duration` jednostek czasu, potem zwalnia. */
  def resetSequence(cds: Seq[ClockDomain], duration: Long): Unit = {
    cds.foreach(_.assertReset())
    sleep(duration)
    cds.foreach(_.deassertReset())
  }
}
