package newhope.frontend

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.sim.SimThread
import scala.collection.mutable
import scala.util.Random

object FrontEndTestKit {

  /** waitActiveEdge, NIE waitSampling: pod resetem asynchronicznym
    * waitSampling nie widzi zboczy (lekcja z newhope.fft). */
  def idleReset(cd: ClockDomain): Unit = {
    cd.assertReset()
    cd.waitActiveEdge(20)
    cd.deassertReset()
    cd.waitSampling(5)
  }

  /** Zawieszenie ma byc bledem testu, nie wiszacym sbt. */
  def guard(cycles: Long = 2000000L, period: Long = 10): Unit = SimTimeout(cycles * period)

  def waitFor(cd: ClockDomain, cond: => Boolean, limit: Long, what: String): Unit = {
    var n = 0L
    while (!cond) {
      cd.waitSampling()
      n += 1
      assert(n <= limit, s"$what: brak po $limit cyklach")
    }
  }

  /** Zbiera payload Flow przy kazdym valid. `cycle` - indeks probkowania. */
  final class FlowMonitor(f: Flow[SInt], cd: ClockDomain) {
    val values = mutable.ArrayBuffer[Long]()
    val cycles = mutable.ArrayBuffer[Long]()
    var cycle  = 0L
    var active = true
    cd.onSamplings {
      if (active && f.valid.toBoolean) { values += f.payload.toLong; cycles += cycle }
      cycle += 1
    }
  }

  /** valid nisko przez caly czas resetu. */
  def quietDuringReset(cd: ClockDomain, valid: Bool, name: String): Unit = fork {
    while (true) {
      cd.waitActiveEdge()
      if (cd.isResetAsserted) assert(!valid.toBoolean, s"$name: valid w trakcie resetu")
    }
  }

  /** Wejscie Flow: probka, potem gap(i) - 1 cykli przerwy (gap >= 1). */
  def feed(f: Flow[SInt], cd: ClockDomain, xs: Seq[Long], gap: Int => Int): SimThread = fork {
    for ((x, i) <- xs.zipWithIndex) {
      f.valid   #= true
      f.payload #= x
      cd.waitSampling()
      val gp = gap(i)
      if (gp > 1) {
        f.valid #= false
        cd.waitSampling(gp - 1)
      }
    }
    f.valid #= false
  }

  // -------------------------------------------------------------------
  //  Model INMP441 na pinach. Patrzy na SCK i WS jak mikrofon: WS
  //  probkuje na narastajacym SCK, zmiana WS otwiera slot, na kolejnych
  //  opadajacych zboczach k = 1..24 wystawia bity slowa MSB-first.
  //  Bit opoznienia (k = 0), bity 25..31 i drugi kanal to losowe smieci:
  //  prawdziwy INMP441 je trzyma w Hi-Z, ale drugi mikrofon na tej samej
  //  linii (L/R = VDD) nadawalby wlasnie tam.
  // -------------------------------------------------------------------
  final class MicModel(sck: Bool, ws: Bool, sd: Bool, cd: ClockDomain,
                       g: I2sMicGenerics, words: Seq[Long], rng: Random) {
    var active  = true
    var sent    = 0                       // slow wyslanych w naszym kanale
    private var prevSck = false
    private var prevWs  : Option[Boolean] = None
    private var k       = -1
    private var mine    = false
    private var cur     : Option[Long] = None

    sd #= false
    cd.onSamplings {
      if (active) {
        val s = sck.toBoolean
        val w = ws.toBoolean
        if (s && !prevSck) {                       // narastajace
          if (prevWs.exists(_ != w)) {
            k    = 0
            mine = (!w) == g.leftChannel
            cur  = if (mine && sent < words.size) { sent += 1; Some(words(sent - 1)) } else None
          }
          prevWs = Some(w)
        }
        if (!s && prevSck && k >= 0) {             // opadajace
          k += 1
          val b = cur match {
            case Some(word) if k >= 1 && k <= g.wordBits => I2sGolden.bit(g, word, k)
            case _                                       => rng.nextBoolean()
          }
          sd #= b
        }
        prevSck = s
      }
    }
  }
}
