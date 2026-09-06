package org.newhope.asyncfifo

import spinal.core._
import spinal.core.sim._

import spinal.lib.sim._
import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite

class AsyncFifoDemoTest extends AnyFunSuite {
  lazy val dut: SimCompiled[AsyncFifoDemo] = Config.sim
    .withFstWave
    .compile {
      new AsyncFifoDemo(dataWidth = 8, depth = 4)
    }
  test("simple burst") {
    // Okresy celowo niewspolmierne - zeby zbocza zegarow "dryfowaly" wzgledem
    // siebie i realnie odwiedzaly okna setup/hold, zamiast utrzymywac stala faze.
    val periodA = 10 // clkA ~ 100 MHz
    val periodB = 27 // clkB ~ 37 MHz

    dut.doSim("burst", seed = 42) { dut =>
      SimTimeout(10000)
      dut.clkA.forkStimulus(period = periodA)
      dut.clkB.forkStimulus(period = periodB)

      dut.io.push.valid   #= false
      dut.io.push.payload #= 0
      dut.io.pop.ready    #= false

      dut.clkA.waitSampling(5)
      assert(dut.io.full.toBoolean == false)
      assert(dut.io.empty.toBoolean == true)

      val count = 10
      val received = mutable.ArrayBuffer[Int]()
      var consumerSlow = true

      /* fork robi obiekty SimThread: senantycznie coroutine [wątek JVM ale synchronizowany]
      ** yield w miejscach wywołania: waitSampling, waitSamplingWhere, waitUntil sleep, join */
      val producer = fork {
        for (i <- 0 until count) {
          println("[" + simTime().toString + "] Producer step: " + i)
          dut.io.push.valid   #= true
          dut.io.push.payload #= i
          dut.clkA.waitSamplingWhere(dut.io.push.ready.toBoolean)
        }
        dut.io.push.valid #= false
        println("[" + simTime().toString + "] Producer done")
      }
      
      val consumer = fork {
        while (received.length < count) {
          if (consumerSlow) {
            dut.io.pop.ready #= false
            dut.clkB.waitSampling(3 + simRandom.nextInt(5))
          }
          println("[" + simTime().toString + "] Consumer ready")
          dut.io.pop.ready #= true
          dut.clkB.waitSamplingWhere(dut.io.pop.valid.toBoolean)
          received += dut.io.pop.payload.toInt
          println("[" + simTime().toString + "] Consumer received: " + received.last)
          dut.io.pop.ready #= false
        }
        println("[" + simTime().toString + "] Consumer done")
      }
      
      // przez chwile trzymamy konsumenta na wolnych obrotach - FIFO sie zapcha
      dut.clkA.waitSampling(100)

      consumerSlow = false // teraz konsument nadaza -> FIFO sie oprozni
      producer.join()
      consumer.join()
      println("[" + simTime().toString + "] Main thread done")
      dut.clkB.waitSampling(20)
      assert(
        received.toList == (0 until count).toList,
        s"dane lub kolejnosc niezgodne: ${received.toList}"
      )
      println(s"OK - $count elements transferred correctly")
    }
  }
  test("jitter") {
    // Okresy celowo niewspolmierne - zeby zbocza zegarow "dryfowaly" wzgledem
    // siebie i realnie odwiedzaly okna setup/hold, zamiast utrzymywac stala faze.
    val periodA = 100 // clkA ~ 100 MHz
    val periodB = 270 // clkB ~ 37 MHz

    var totalTransactions = 0
    val runs = 20
    
    for (seed <- 0 until runs) {
      dut.doSim(s"jitter_$seed", seed = seed) { dut =>
        SimTimeout(3000000)

        dut.io.push.valid #= false
        dut.io.pop.ready  #= false

        SimClocks.forkJitterClock(dut.clkA, periodA, jitterPercent = 5, phase = simRandom.nextInt(periodA))
        SimClocks.forkJitterClock(dut.clkB, periodB, jitterPercent = 5, phase = simRandom.nextInt(periodB))

        SimClocks.resetSequence(Seq(dut.clkA, dut.clkB), duration = periodB * 10)

        val scoreboard = ScoreboardInOrder[Int]()
        var pushed     = 0
        var popped     = 0

        var enablePush = true
        StreamDriver(dut.io.push, dut.clkA) { payload =>
          if (!enablePush) false
          else {
            payload #= simRandom.nextInt(1 << 8)
            true
          }
        }

        StreamMonitor(dut.io.push, dut.clkA) { p =>
          scoreboard.pushRef(p.toInt); pushed += 1
        }

        StreamReadyRandomizer(dut.io.pop, dut.clkB)

        StreamMonitor(dut.io.pop, dut.clkB) { p =>
          scoreboard.pushDut(p.toInt); popped += 1
        }

        dut.clkB.waitSampling(1500)

        enablePush = false
        dut.clkB.waitSampling(200)

        scoreboard.checkEmptyness()
        assert(pushed == popped, s"seed $seed: wpchniete $pushed, wyjete $popped")

        totalTransactions += popped
        println(f"[seed $seed%2d] $popped%4d transakcji, scoreboard czysty")
      }
    }
    println()
    println(s"$runs przejsc, lacznie $totalTransactions transakcji, zero rozbieznosci")
  }
}
