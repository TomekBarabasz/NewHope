package newhope.vertebra.hil

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable
import HilProtocol._

// =====================================================================
//  Strona hosta w symulacji: UART 8N1 w czasie symulacji (sleep), bez
//  zegara DUT-a - jak PIC/USB-UART, ktory ma wlasny zegar. Okres bitu
//  jest parametrem, zeby testowac tolerancje baud (core_baud_tolerance).
//
//  Uzywaja go HilCoreTestplan i I2sHarnessTestplan (etap 2d).
// =====================================================================
class HilUartSim(cd : ClockDomain, toDut : Bool, fromDut : Bool, var bitPeriod : Long) {
  val received      = mutable.Queue[Int]()
  val framingErrors = mutable.ArrayBuffer[String]()

  toDut #= true

  /** Odbiornik: start wykryty zegarem DUT-a (+-1 cykl), potem srodki bitow. */
  def start() : Unit = fork {
    while (true) {
      cd.waitSamplingWhere(!fromDut.toBoolean)
      sleep(bitPeriod / 2)
      if (!fromDut.toBoolean) {                  // prawdziwy bit startu, nie szpilka
        var v = 0
        for (i <- 0 until 8) {
          sleep(bitPeriod)
          if (fromDut.toBoolean) v |= 1 << i
        }
        sleep(bitPeriod)
        if (!fromDut.toBoolean) framingErrors += f"brak bitu stopu po $v%02x w t=${simTime()}"
        received.enqueue(v)
      }
    }
  }

  def sendByte(b : Int, period : Long = bitPeriod) : Unit = {
    toDut #= false; sleep(period)
    for (i <- 0 until 8) { toDut #= ((b >> i) & 1) == 1; sleep(period) }
    toDut #= true; sleep(period)
  }

  def send(bytes : Seq[Int], period : Long = bitPeriod) : Unit = bytes.foreach(sendByte(_, period))

  /** Do n bajtow; mniej, jesli minie `timeout` (czas symulacji). */
  def recv(n : Int, timeout : Long) : Seq[Int] = {
    val deadline = simTime() + timeout
    while (received.size < n && simTime() < deadline) sleep(bitPeriod)
    (0 until scala.math.min(n, received.size)).map(_ => received.dequeue())
  }

  def checkFraming() : Unit =
    assert(framingErrors.isEmpty, framingErrors.take(3).mkString("; "))
}

/** Klient rejestrow nad HilUartSim - odpowiednik FpgaDevice z etapu 4. */
class HilRegClient(val uart : HilUartSim) {
  /** Odpowiedz: 7 bajtow na linii + wykonanie; 12 bajtow to spory zapas. */
  def rspTimeout : Long = 12L * 10 * uart.bitPeriod

  def transact(req : Seq[Int], period : Long = uart.bitPeriod) : Either[String, Response] = {
    uart.send(req, period)
    parseResponse(uart.recv(RspBytes, rspTimeout))
  }

  private def ok(what : String, r : Either[String, Response]) : Response =
    r.fold(e => throw new AssertionError(s"$what: $e"), x => x)

  def read(addr : Int, period : Long = uart.bitPeriod) : Response =
    ok(f"read $addr%03x", transact(readReq(addr), period))

  def write(addr : Int, data : Long, period : Long = uart.bitPeriod) : Response =
    ok(f"write $addr%03x", transact(writeReq(addr, data), period))

  def readOk(addr : Int) : Long = {
    val r = read(addr)
    assert(r.ok, f"read $addr%03x: $r")
    r.data
  }

  def writeOk(addr : Int, data : Long) : Unit = {
    val r = write(addr, data)
    assert(r.ok && r.data == (data & 0xFFFFFFFFL), f"write $addr%03x <- $data%08x: $r")
  }

  /** Brak jakiejkolwiek odpowiedzi przez `t`. */
  def expectSilence(t : Long) : Unit = {
    val got = uart.recv(1, t)
    assert(got.isEmpty, s"nieoczekiwane bajty: ${got.map(b => f"$b%02x").mkString(" ")}")
  }
}
