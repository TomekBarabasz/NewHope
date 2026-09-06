package org.newhope.i2c

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._
import scala.collection.mutable

// =====================================================================
//  1. MODEL MAGISTRALI (wired-AND + programowy "slave")
//
//  Alternatywa z biblioteki: spinal.lib.com.i2c.sim.OpenDrainInterconnect
//  (addHard / newSoftConnection / evaluate). Tutaj recznie, zeby bylo
//  widac co sie dzieje i zeby dalo sie wstrzykiwac bledy.
// =====================================================================
class I2cBusModel(dut : I2cPhyBase) {

  // Programowe sciaganie linii do masy - to jest nasz "slave".
  var sclPull = false
  var sdaPull = false

  def scl : Boolean = dut.io.pins.scl.write.toBoolean && !sclPull
  def sda : Boolean = dut.io.pins.sda.write.toBoolean && !sdaPull

  def start() : Unit = dut.clockDomain.onSamplings {
    dut.io.pins.scl.read #= scl
    dut.io.pins.sda.read #= sda
  }

  // Clock stretching: przytrzymaj SCL przez n cykli zegara systemowego.
  def stretch(cycles : Int) : Unit = {
    sclPull = true
    dut.clockDomain.waitSampling(cycles)
    sclPull = false
  }
}

// =====================================================================
//  2. MONITOR / CHECKER PROTOKOLU
//
//  Dekoduje piny na zdarzenia i po drodze sprawdza dwa niezmienniki I2C:
//   - bit jest wazny na zboczu narastajacym SCL,
//   - SDA nie moze sie zmienic przy wysokim SCL, chyba ze to START/STOP.
// =====================================================================
object I2cEvent {
  sealed trait Event
  case object Start            extends Event
  case object Stop             extends Event
  case class  Bit(v : Boolean) extends Event
}

class I2cMonitor(dut : I2cPhyBase, bus : I2cBusModel) {
  import I2cEvent._

  val events = mutable.Queue[Event]()

  private var sclOld  = true
  private var sdaOld  = true
  private var bitAtRise = true
  private var inHigh  = false

  // Ile cykli trwal ostatni stan wysoki / niski SCL - do kontroli timingu.
  private var lastEdge = 0L
  var highLen = 0L
  var lowLen  = 0L

  def start() : Unit = dut.clockDomain.onSamplings {
    val now = simTime()
    val scl = bus.scl
    val sda = bus.sda

    if (scl && sclOld && sda != sdaOld) {
      // zmiana SDA przy wysokim SCL == warunek START albo STOP
      events.enqueue(if (sdaOld) Start else Stop)
      inHigh = false          // po START/STOP nie sprawdzamy juz stabilnosci
    }

    if (scl && !sclOld) {     // zbocze narastajace: bit staje sie wazny
      bitAtRise = sda
      inHigh    = true
      lowLen    = now - lastEdge
      lastEdge  = now
    }

    if (!scl && sclOld) {     // zbocze opadajace: bit sie konczy
      if (inHigh) {
        assert(sda == bitAtRise,
               s"SDA zmienilo sie w trakcie wysokiego SCL (t=$now)")
        events.enqueue(Bit(bitAtRise))
      }
      inHigh   = false
      highLen  = now - lastEdge
      lastEdge = now
    }

    sclOld = scl
    sdaOld = sda
  }

  def expect(expected : Event*) : Unit = {
    val got = events.dequeueAll(_ => true).toSeq
    assert(got == expected.toSeq, s"oczekiwano ${expected.toSeq}, dostano $got")
  }
}
