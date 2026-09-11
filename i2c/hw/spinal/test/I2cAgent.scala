package org.newhope.i2c

import spinal.core.sim._
import scala.collection.mutable

// =====================================================================
//  AGENT I2C - driver (model magistrali) + monitor/checker + sonda.
//
//  ZMIANA: okno GlitchFilter w monitorze NIE jest juz brane z
//  g.filterWindow. Powod jest empiryczny. W FilterSweep `w` jest osia,
//  wiec razem z DUT-em zmienialo sie okno testbenchu, a GlitchFilter
//  przelacza stan dopiero po `w` kolejnych roznych probkach. Stan
//  wysoki SCL trwa 2*quarterCycles, wiec dla w > 2q monitor NIGDY nie
//  widzial wysokiego SCL: zero zdarzen, protocolOk=false. Caly obszar
//  "P" w siatce byl artefaktem monitora, nie awaria DUT-a.
//
//  Okno monitora ma bronic przed glitchami WSTRZYKIWANYMI przez
//  testbench (host_rx_oversample), a te maja dlugosc znana z testu,
//  nie z generyka DUT-a. Stad stala.
// =====================================================================

/** Wired-AND + programowy "slave". */
class I2cBusModel(dut : I2cPhyBase) {

  var sclPull = false
  var sdaPull = false

  def scl : Boolean = dut.io.pins.scl.write.toBoolean && !sclPull
  def sda : Boolean = dut.io.pins.sda.write.toBoolean && !sdaPull

  def start() : Unit = dut.clockDomain.onSamplings {
    dut.io.pins.scl.read #= scl
    dut.io.pins.sda.read #= sda
  }

  /** Clock stretching: przytrzymaj SCL przez n cykli zegara systemowego. */
  def stretch(cycles : Int) : Unit = {
    sclPull = true
    dut.clockDomain.waitSampling(cycles)
    sclPull = false
  }
}

/** Odpowiednik I2cInputFilter, ale napisany niezaleznie:
  * N kolejnych probek roznych od stanu biezacego przelacza stan.
  *
  * UWAGA: to NIE jest ten sam filtr co w DUT-cie i celowo. DUT ma
  * BufferCC (2 cykle) + pelne okno + rejestr value, czyli opoznienie
  * ok. w+3. Tutaj jest ok. w. Rozjazd rosnie z w - dlatego nie wolno
  * wiazac tych dwoch okien ze soba. */
class GlitchFilter(window : Int, init : Boolean = true) {
  private var value = init
  private var count = 0
  def update(raw : Boolean) : Boolean = {
    if (raw == value) count = 0
    else { count += 1; if (count >= window) { value = raw; count = 0 } }
    value
  }
}

object I2cEvent {
  sealed trait Event
  case object Start            extends Event
  case object Stop             extends Event
  case class  Bit(v : Boolean) extends Event
}

object I2cMonitor {
  /** Okno filtra monitora. Stala, NIE g.filterWindow - patrz naglowek.
    * Dobrane pod glitch wstrzykiwany w host_rx_oversample. */
  val defaultWindow = 4
}

// ---------------------------------------------------------------------
//  Dekoduje piny na zdarzenia i sprawdza dwa niezmienniki I2C:
//   - bit jest wazny na zboczu narastajacym SCL,
//   - SDA nie moze sie zmienic przy wysokim SCL, chyba ze to START/STOP.
//
//  Naruszenia sa REJESTROWANE, a rzucane dopiero w expect/check - czyli
//  w watku testu, w miejscu, ktore o nie pyta. Wyjatek z callbacku
//  symulacji przyjezdza opakowany i sweep nie odroznilby "protokol sie
//  zepsul" od "symulacja sie zawiesila".
// ---------------------------------------------------------------------
class I2cMonitor(dut    : I2cPhyBase,
                 bus    : I2cBusModel,
                 window : Int = I2cMonitor.defaultWindow) {
  import I2cEvent._

  val events     = mutable.Queue[Event]()
  val violations = mutable.ArrayBuffer[String]()

  private var sclOld    = true
  private var sdaOld    = true
  private var bitAtRise = true
  private var inHigh    = false
  private val fScl = new GlitchFilter(window)
  private val fSda = new GlitchFilter(window)

  // Ile czasu trwal ostatni stan wysoki / niski SCL - do kontroli timingu.
  // UWAGA: mierzone na sygnale PO filtrze monitora, wiec obarczone jego
  // opoznieniem. Roznica (highLen vs oczekiwane) jest wiarygodna,
  // wartosc bezwzgledna ma blad rzedu `window`.
  private var lastEdge = 0L
  var highLen = 0L
  var lowLen  = 0L

  def start() : Unit = dut.clockDomain.onSamplings {
    val now = simTime()
    val scl = fScl.update(bus.scl)
    val sda = fSda.update(bus.sda)

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
        if (sda != bitAtRise)
          violations += s"SDA zmienilo sie w trakcie wysokiego SCL (t=$now)"
        events.enqueue(Bit(bitAtRise))
      }
      inHigh   = false
      highLen  = now - lastEdge
      lastEdge = now
    }

    sclOld = scl
    sdaOld = sda
  }

  /** Same niezmienniki protokolu, bez patrzenia na tresc transakcji. */
  def check() : Unit =
    assert(violations.isEmpty, violations.mkString("; "))

  def drain() : Seq[Event] = events.dequeueAll(_ => true).toSeq

  def expect(expected : Event*) : Unit = {
    check()
    val got = drain()
    assert(got == expected.toSeq, s"oczekiwano ${expected.toSeq}, dostano $got")
  }
}

// =====================================================================
//  SONDA - to, po co mielismy ogladac fale.
//
//  Mierzy DWIE liczby, ktore rozstrzygaja, dlaczego granica skaluje sie
//  jak 3 cwiartki na okno filtra, a nie 2:
//    1. sdaLatency    - cykle od zmiany na pinie do zmiany filter.sda,
//                       czyli L. Model konstrukcyjny mowi w+3.
//    2. stretchCycles - ile cykli `stretching` bylo wysokie. Jesli rosnie
//                       z w, to falszywy stretching wydluza Q1 i okno na
//                       ustalenie SDA nie jest rowne 2q, tylko 2q+L_scl.
//
//  WYMAGA w I2cPhyBase (plik w main/):
//      sclReg.simPublic(); sdaReg.simPublic()
//      filter.scl.simPublic(); filter.sda.simPublic()
//      stretching.simPublic()
//  Bez tego Verilator wytnie te sygnaly i .toBoolean rzuci.
//
//  DOKLADNOSC: kolejnosc callbackow onSamplings wzgledem forkniętych
//  watkow nie jest gwarantowana, wiec wartosc bezwzgledna moze byc o 1
//  cykl obok. Roznica miedzy komorkami (w=5 vs w=6) jest wiarygodna -
//  i to ona nas interesuje.
// =====================================================================
class FilterProbe(d : I2cPhyBase) {

  var lastSdaLatency = -1
  var maxSdaLatency  = -1
  var stretchCycles  = 0

  def start() : Unit = fork {
    var cycle      = 0L
    var lastPin    = true
    var pendingVal = true
    var pendingAt  = 0L
    var armed      = false

    while (true) {
      d.clockDomain.waitSampling()
      cycle += 1

      if (d.stretching.toBoolean) stretchCycles += 1

      val pin = d.io.pins.sda.read.toBoolean
      if (pin != lastPin) {
        // nowa zmiana kasuje poprzedni pomiar - glitch krotszy niz okno
        // nigdy nie dojdzie do filtra i nie ma czego mierzyc
        lastPin = pin; pendingVal = pin; pendingAt = cycle; armed = true
      }
      if (armed && d.filter.sda.toBoolean == pendingVal) {
        lastSdaLatency = (cycle - pendingAt).toInt
        if (lastSdaLatency > maxSdaLatency) maxSdaLatency = lastSdaLatency
        armed = false
      }
    }
  }
}
