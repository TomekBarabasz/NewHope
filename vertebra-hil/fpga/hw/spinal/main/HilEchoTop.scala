package newhope.vertebra.hil

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import scala.math.BigDecimal.RoundingMode

// =====================================================================
//  Etap 0 (vertebra-hil.md §10): echo UART na Mimas V2.
//
//  Cel: sprawdzic lancuch Spinal -> Verilog -> ISE -> .bin -> XMODEM
//  i port UART FPGA przez PIC z firmware jimmo (115200). Kazdy odebrany
//  bajt wraca bez zmian. Nic tu nie zna protokolu mostu z §5 - ten
//  przyjdzie w etapie 2 (HilUartBridge).
//
//  EchoProbe (host) bierze baud i rozmiar chunka z HilEchoGenerics /
//  HilEcho, wiec obie strony nie moga sie rozjechac.
// =====================================================================

object HilEcho {
  /** Ile bajtow EchoProbe wysyla, zanim zaczeka na echo. FIFO echa musi
    * pomiescic caly chunk (sprawdza echo_param_bounds). */
  val probeChunk = 64
}

/** Parametry echa.
  *
  * clockDivider liczony tak samo jak UartCtrlConfig.setClockDivider, ale
  * z generykow, a nie z ClockDomain.current - dzieki temu host i testy
  * widza te sama wartosc bez elaboracji. */
case class HilEchoGenerics(clkHz     : Long = 100000000L,   // oscylator Mimas V2 (V10), do potwierdzenia w schemacie (§11)
                           baud      : Long = 115200L,      // firmware PIC jimmo; fabryczny: 19200
                           fifoDepth : Int  = 128) {
  val uartG         = UartCtrlGenerics()
  val samplesPerBit = uartG.rxSamplePerBit                  // 1 + 5 + 2 = 8

  val clockDivider : Int =
    (BigDecimal(clkHz) / baud / samplesPerBit).setScale(0, RoundingMode.HALF_DOWN).toInt - 1

  def actualBaud   : Double = clkHz.toDouble / ((clockDivider + 1).toLong * samplesPerBit)
  def baudErrorPct : Double = (actualBaud - baud) / baud * 100

  /** Granica 2 %: odbiornik probkuje srodek bitu przy 8 probkach na bit,
    * wiec laczny rozjazd nadawcy i odbiorcy na 10 bitach ramki musi byc
    * ponizej pol bitu (~5 %). Druga strona (PIC, CH340, USB-CDC) ma zwykle
    * < 2 %, zostawiamy jej reszte. */
  def baudOk       : Boolean = scala.math.abs(baudErrorPct) < 2.0
  def dividerOk    : Boolean = clockDivider >= 1 && clockDivider < (1 << uartG.clockDividerWidth)
  def fifoOk       : Boolean = fifoDepth >= HilEcho.probeChunk
  def isLegal      : Boolean = baudOk && dividerOk && fifoOk
}

/** Echo UART. Nazwy portow (noIoPrefix) pasuja do hw/ise/hil_echo.ucf:
  * clk, uart_rxd, uart_txd, led[7:0].
  *
  * Diody (aktywne wysokim):
  *   led[7]   heartbeat (~0,75 Hz przy 100 MHz) - bitstream zyje
  *   led[6]   przepelnienie FIFO (sticky) - host wyslal wiecej niz fifoDepth
  *   led[5]   blad ramki RX (sticky) - zly baud albo zly port
  *   led[4:0] mlodsze bity licznika odebranych bajtow */
case class HilEchoTop(g : HilEchoGenerics = HilEchoGenerics()) extends Component {
  require(g.isLegal, s"nielegalne generyki echa: $g (patrz echo_param_bounds)")

  val io = new Bundle {
    val clk  = in  Bool()
    val uart = master(Uart())
    val led  = out Bits(8 bits)
  }
  noIoPrefix()

  // Bez linii resetu: wartosci poczatkowe z bitstreamu (GSR). Soft reset
  // przez rejestr ctrl dojdzie razem z mostem w etapie 2.
  val sysCd = ClockDomain(
    clock     = io.clk,
    frequency = FixedFrequency(HertzNumber(BigDecimal(g.clkHz))),
    config    = ClockDomainConfig(resetKind = BOOT))

  val sys = new ClockingArea(sysCd) {
    val uart = new UartCtrl(g.uartG)
    uart.io.config.clockDivider     := g.clockDivider
    uart.io.config.frame.dataLength := 7                       // 8 bitow danych
    uart.io.config.frame.parity     := UartParityType.NONE
    uart.io.config.frame.stop       := UartStopType.ONE
    uart.io.writeBreak              := False
    io.uart <> uart.io.uart

    // UartCtrlRx wystawia valid na jeden cykl niezaleznie od ready, wiec
    // pelne FIFO gubi bajt. isStall to wlasnie ta chwila.
    uart.io.write << uart.io.read.queue(g.fifoDepth)

    val rxCount   = Reg(UInt(5 bits)) init(0)
    when(uart.io.read.fire) { rxCount := rxCount + 1 }
    val overflow  = RegInit(False) setWhen(uart.io.read.isStall)
    val rxError   = RegInit(False) setWhen(uart.io.readError)
    val heartbeat = Reg(UInt(27 bits)) init(0)
    heartbeat := heartbeat + 1

    io.led := heartbeat.msb ## overflow ## rxError ## rxCount.asBits
  }
}

/** Generacja Veriloga do vertebra-hil/fpga/hw/gen/HilEchoTop.v:
  *   sbt "hilFpga/runMain newhope.vertebra.hil.HilEchoTopVerilog" */
object HilEchoTopVerilog {
  def main(args : Array[String]) : Unit = {
    val g = HilEchoGenerics()
    Config.spinal.generateVerilog(HilEchoTop(g))
    println(f"HilEchoTop: ${g.clkHz / 1e6}%.3f MHz, baud ${g.baud} " +
            f"(rzeczywisty ${g.actualBaud}%.0f, ${g.baudErrorPct}%+.2f %%), dzielnik ${g.clockDivider}")
  }
}
