package newhope.aht10

import spinal.core.ClockDomain
import spinal.core.sim._
import newhope.i2c.{I2cBusModel, I2cPins, I2cSlaveModel}

// =====================================================================
//  Model czujnika AHT10 - warstwa 3 nad I2cSlaveModel.
//
//  PODZIAL ODPOWIEDZIALNOSCI (patrz naglowek I2cSlaveModel w
//  I2cAgent.scala, ktory ten podzial zapowiada):
//
//    I2cBusModel    - wired-AND, wstrzykiwanie poziomow na piny
//    I2cSlaveModel  - kolejka slotow, jeden slot na bit, wejscie slotu
//                     na opadajacym zboczu SCL
//    Aht10SlaveModel - dekodowanie adresu i kierunku, stan czujnika,
//                     komendy 0xE1 / 0xAC / 0xBA, ramka odpowiedzi
//
//  Ten model NIE dotyka pinow bezposrednio i NIE liczy cwiartek. Cale
//  ryzykowne miejsce - kiedy dokladnie wystawic bit, zeby zdazyl przed
//  probkowaniem w Q2 - jest juz rozwiazane w I2cSlaveModel i przetestowane
//  przez I2cMasterTestplan.
//
//  UZBRAJANIE. Sloty kolejkujemy na zboczu NARASTAJACYM, bo wtedy
//  wiadomo juz, co przyszlo, a do opadajacego zbocza (na ktorym slot
//  wchodzi na linie) zostaje pol okresu SCL:
//
//    * po osmym bicie bajtu -> slot ACK
//    * po bicie ACK adresu odczytu -> osiem bitow pierwszego bajtu
//    * po bicie ACK kazdego wyslanego bajtu -> nastepny bajt
//
//  Gdy kolejka jest pusta, I2cSlaveModel sam puszcza linie na kazdym
//  opadajacym zboczu, wiec slotow "nie dotykam SDA" nie trzeba
//  kolejkowac wcale.
// =====================================================================
class Aht10SlaveModel(cd      : ClockDomain,
                      pins    : I2cPins,
                      bus     : I2cBusModel,
                      address : Int = 0x38) {

  def this(dut : Aht10Ctrl, bus : I2cBusModel) =
    this(dut.clockDomain, dut.io.pins, bus)

  private val slave = new I2cSlaveModel(cd, pins, bus)

  // --- stan czujnika, ustawiany przez testbench -----------------------
  var rawT       : Int     = 0x40000    //  0.0 C
  var rawRh      : Int     = 0x40000    // 25.0 %
  var calibrated : Boolean = true
  /** Przez ile taktow po komendzie 0xAC status ma miec bit busy. */
  var busyCycles : Int     = 0
  /** Nie potwierdzaj adresu - symulacja braku czujnika. */
  var nakAddress : Boolean = false

  /** Martwa magistrala: trzymamy SCL nisko przez I2cBusModel. */
  def hang(v : Boolean) : Unit = bus.sclPull = v

  // --- obserwacje dla asercji w testach -------------------------------
  var initCount    : Int  = 0
  var measCount    : Int  = 0
  var resetCount   : Int  = 0
  var startCount   : Int  = 0
  var stopCount    : Int  = 0
  /** Bajty odebrane miedzy ostatnim START a STOP. */
  var lastCmdBytes : Seq[Int] = Seq()
  /** Czy master potwierdzil kolejne WYSLANE bajty. Dla ramki pomiaru
    * ma byc piec razy true i raz false - stad aht10_read_nack_last. */
  var ackedBytes   : Seq[Boolean] = Seq()

  var measTime : Long = 0
  var readTime : Long = 0
  private var now : Long = 0

  // --- stan protokolu --------------------------------------------------
  private object Phase extends Enumeration {
    val Idle, Addr, Rx, Tx = Value
  }
  private var phase     = Phase.Idle
  private var bitCount  = 0
  private var shiftIn   = 0
  private var isRead    = false
  private var matched   = false
  private var masterAck = false
  private var txIndex   = 0
  private var busyLeft  = 0
  private var rxBuffer  = Seq[Int]()

  private var prevScl = true
  private var prevSda = true

  private def statusByte : Int =
    (if (busyLeft > 0) 0x80 else 0x00) | (if (calibrated) 0x08 else 0x00)

  /** Ramka odpowiedzi wedlug datasheetu: bajt 3 dzieli sie na pol -
    * gorne cztery bity koncza wilgotnosc, dolne zaczynaja temperature. */
  private def responseByte(i : Int) : Int = i match {
    case 0 => statusByte
    case 1 => (rawRh >> 12) & 0xff
    case 2 => (rawRh >>  4) & 0xff
    case 3 => ((rawRh & 0x0f) << 4) | ((rawT >> 16) & 0x0f)
    case 4 => (rawT  >>  8) & 0xff
    case 5 =>  rawT         & 0xff
    case _ => 0xff              // master powinien byl zakonczyc NACK-iem
  }

  private def handleByte(b : Int) : Unit = {
    rxBuffer = rxBuffer :+ b
    b match {
      case 0xE1 => initCount += 1
      case 0xAC => measCount += 1; measTime = now; busyLeft = busyCycles
      case 0xBA => resetCount += 1; busyLeft = 0
      case _    => ()
    }
  }

  /** ACK to sciagniecie linii, czyli poziom false w konwencji slotow. */
  private def ack()     : Unit = slave.drive(false)
  private def nack()    : Unit = slave.drive(true)

  def start() : Unit = {
    slave.start()

    fork {
      while (true) {
        cd.waitSampling()
        now += 1
        if (busyLeft > 0) busyLeft -= 1

        val scl = bus.scl
        val sda = bus.sda

        // --- START / STOP: zmiana SDA przy wysokim SCL ----------------
        if (scl && prevScl && sda != prevSda) {
          if (prevSda) {                       // opadniecie -> START
            startCount += 1
            phase    = Phase.Addr
            bitCount = 0
            shiftIn  = 0
            rxBuffer = Seq()
            slave.clear()
          } else {                             // narosniecie -> STOP
            stopCount += 1
            if (rxBuffer.nonEmpty) lastCmdBytes = rxBuffer
            phase = Phase.Idle
            slave.clear()
          }
        }

        val rising = scl && !prevScl

        if (rising && phase != Phase.Idle) {
          if (bitCount < 8) {
            if (phase != Phase.Tx)
              shiftIn = ((shiftIn << 1) | (if (sda) 1 else 0)) & 0xff
            bitCount += 1

            // Osiem bitow w komplecie - kolejkujemy bit ACK. W fazie Tx
            // slot puszczenia linii dolozyl juz sendByte.
            if (bitCount == 8) phase match {
              case Phase.Addr =>
                matched = ((shiftIn >> 1) & 0x7f) == address
                isRead  = (shiftIn & 1) == 1
                if (matched && !nakAddress) ack() else nack()
              case Phase.Rx =>
                handleByte(shiftIn)
                ack()
              case _ => ()
            }
          } else {
            // Dziewiaty bit. Przy Tx to ACK od mastera, przy Rx nasz
            // wlasny - juz wystawiony, nie ma czego czytac.
            if (phase == Phase.Tx) {
              masterAck  = !sda
              ackedBytes = ackedBytes :+ masterAck
            }
            bitCount = 0
            shiftIn  = 0

            phase match {
              case Phase.Addr =>
                // nakAddress wylacza tylko POTWIERDZANIE. Dekodowanie
                // idzie dalej, bo model jest tez obserwatorem magistrali -
                // inaczej po odmowie ACK nie zobaczylby soft resetu,
                // ktory kontroler i tak wysyla.
                if (!matched) {
                  phase = Phase.Idle
                } else if (isRead) {
                  phase      = Phase.Tx
                  txIndex    = 0
                  readTime   = now
                  ackedBytes = Seq()
                  slave.sendByte(responseByte(0))
                } else {
                  phase = Phase.Rx
                }
              case Phase.Tx =>
                if (masterAck) {
                  txIndex += 1
                  slave.sendByte(responseByte(txIndex))
                } else {
                  phase = Phase.Idle       // master zamknal transmisje
                }
              case _ => ()                 // Rx zostaje Rx
            }
          }
        }

        prevScl = scl
        prevSda = sda
      }
    }
  }
}
