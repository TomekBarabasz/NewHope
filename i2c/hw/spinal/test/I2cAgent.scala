package newhope.i2c

import spinal.core.ClockDomain
import spinal.core.sim._
import scala.collection.mutable

// =====================================================================
//  AGENT I2C - driver (model magistrali) + monitor/checker + sonda
//  + programowy slave.
//
//  ZMIANA 1: okno GlitchFilter w monitorze NIE jest juz brane z
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
//
//  ZMIANA 2 (warstwa 2): I2cBusModel i I2cMonitor bior teraz
//  (ClockDomain, I2cPins) zamiast I2cPhyBase, bo ten sam agent obsluguje
//  I2cMaster - tam piny sa o poziom wyzej. Stare konstruktory zostaly
//  jako pomocnicze, wiec I2cSmoke i I2cPhyTestplan sa bez zmian.
//  FilterProbe zostaje przy I2cPhyBase: siega do sygnalow WEWNATRZ PHY.
// =====================================================================

/** Wired-AND + programowy "slave". */
class I2cBusModel(cd : ClockDomain, pins : I2cPins) {

  def this(dut : I2cPhyBase) = this(dut.clockDomain, dut.io.pins)
  def this(dut : I2cMaster)  = this(dut.clockDomain, dut.io.pins)

  var sclPull = false
  var sdaPull = false

  def scl : Boolean = pins.scl.write.toBoolean && !sclPull
  def sda : Boolean = pins.sda.write.toBoolean && !sdaPull

  def start() : Unit = cd.onSamplings {
    pins.scl.read #= scl
    pins.sda.read #= sda
  }

  /** Clock stretching: przytrzymaj SCL przez n cykli zegara systemowego. */
  def stretch(cycles : Int) : Unit = {
    sclPull = true
    cd.waitSampling(cycles)
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
class I2cMonitor(cd     : ClockDomain,
                 bus    : I2cBusModel,
                 window : Int = I2cMonitor.defaultWindow) {
  import I2cEvent._

  def this(dut : I2cPhyBase, bus : I2cBusModel) = this(dut.clockDomain, bus)
  def this(dut : I2cPhyBase, bus : I2cBusModel, window : Int) = this(dut.clockDomain, bus, window)
  def this(dut : I2cMaster,  bus : I2cBusModel) = this(dut.clockDomain, bus)

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

  def start() : Unit = cd.onSamplings {
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

  /** Po resecie / przerwanej transakcji: zapomnij co bylo. */
  def forget() : Unit = { drain(); violations.clear() }
}

// =====================================================================
//  SLAVE - to, czego brakowalo do testow warstwy 2. PHY testowalo sie
//  pojedynczymi szarpnieciami SDA (bus.sdaPull), ale przy bajtach
//  trzeba wystawiac POZIOM NA BIT, i to w odpowiednim momencie.
//
//  MODEL: kolejka slotow, jeden slot = jeden bit danych na magistrali
//  (osiem bitow bajtu + dziewiaty bit ACK). Slot wchodzi na SDA na
//  opadajacym zboczu SCL, czyli na poczatku ostatniej cwiartki
//  poprzedniego bitu. Do momentu probkowania w Q2 nastepnego bitu jest
//  wtedy 2q + falszywy stretching cykli, czyli wiecej niz filterLatency
//  nawet przy qmin - to ten sam zapas, ktory opisuje naglowek I2cSmoke.
//
//  UZBRAJANIE: bezposrednio przed komenda bajtowa (mcmd WRITE/READ).
//  Miedzy komendami SCL jest NISKO - zbocze, ktore ustawiloby pierwszy
//  bit, juz bylo - wiec pierwszy slot wchodzi natychmiast przy
//  uzbrojeniu. Dzieki temu ten sam kod dziala dla pierwszego bajtu po
//  START, po RESTART i w srodku transakcji, i nikt nie musi liczyc,
//  ktore zbocze nalezy do ktorego bitu.
//
//  Uzbrojenie przy WYSOKIM SCL (magistrala jalowa, przed START) tez
//  jest poprawne: pierwszy slot wejdzie na zboczu zamykajacym START.
//
//  Czego ten model NIE robi: nie dekoduje adresu, nie sledzi kierunku,
//  nie zna stanu protokolu. Odpowiada tym, co kazal mu test. Prawdziwy
//  slave to warstwa 3 i osobny komponent.
// =====================================================================
class I2cSlaveModel(cd : ClockDomain, pins : I2cPins, bus : I2cBusModel) {

  def this(dut : I2cMaster, bus : I2cBusModel) = this(dut.clockDomain, dut.io.pins, bus)

  private val slots = mutable.Queue[Boolean]()

  /** Open-drain: poziom 1 znaczy "puszczam linie". */
  private def put(level : Boolean) : Unit = bus.sdaPull = !level

  /** Dopisuje sloty do kolejki. Jesli SCL jest nisko, a kolejka byla
    * pusta, pierwszy slot wchodzi od razu - patrz UZBRAJANIE. */
  def drive(levels : Boolean*) : Unit = {
    val wasEmpty = slots.isEmpty
    slots ++= levels
    if (wasEmpty && !pins.scl.write.toBoolean && slots.nonEmpty) put(slots.dequeue())
  }

  private def bitsOf(v : Int) : Seq[Boolean] =
    for (i <- 7 to 0 by -1) yield ((v >> i) & 1) != 0

  /** Odpowiedz na READ: osiem bitow MSB-first, potem puszczenie linii,
    * zeby master mial gdzie wystawic swoj ACK/NACK. */
  def sendByte(v : Int) : Unit = drive(bitsOf(v) :+ true : _*)

  /** Master pisze bajt: osiem bitow nie dotykamy SDA, w dziewiatym
    * ACK (sciagniecie do zera) albo NACK (puszczenie). */
  def writeAck(ack : Boolean = true) : Unit = drive(Seq.fill(8)(true) :+ !ack : _*)

  /** Po przerwanej transakcji: kolejka do kosza, linia puszczona. */
  def clear() : Unit = { slots.clear(); put(true) }

  def pending : Int = slots.size

  def start() : Unit = fork {
    var prev = true
    while (true) {
      cd.waitSampling()
      // NIE bus.scl - stretching wstrzykiwany przez testbench nie jest
      // zboczem bitu, tylko wydluzeniem stanu wysokiego.
      val now = pins.scl.write.toBoolean
      if (prev && !now) put(if (slots.nonEmpty) slots.dequeue() else true)
      prev = now
    }
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
