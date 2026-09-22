package newhope.uartdemo

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import newhope.mimas_v2._

class UartDemoTop(cfg: UartCtrlInitConfig) extends Component {
  val io = new Bundle {
    //val UART_RX = in Bool ()
    //val UART_TX = out Bool ()
    val uart = master(Uart())
    val led  = out Bits(8 bits)
    val seg  = out Bits (8 bits)
    val en   = out Bits (3 bits)
    val test = out Bool()
  }
  noIoPrefix()

  val mux  = SevenSegMux(ClockDomain.current.frequency.getValue)
  val conv = BinToBcd(binWidth = 10, bcdDigits = 4)
  val ctrl = UartCtrl(cfg)

  io.uart <> ctrl.io.uart
  
  // RX path
  // Assign io.led with a register loaded each time a byte is received
  io.led := ctrl.io.read.toFlow.toReg()

  // Write the value of switch on the uart each 2000 cycles
  val timeout = Timeout(1000 ms)
  val value = Reg(UInt(10 bits)) init(0)
  val next  = (value === 999) ? U(0, 10 bits) | (value + 1)
  val tick  = RegInit(False)

  when(timeout) {
    value := next
    tick := !tick
    timeout.clear()
  }
  io.test := tick

  // Konwersja trwa 12 taktow, tick przychodzi co 10 000 000 - cmd.ready
  // nie ma prawa byc niskie. Gdyby bylo, komenda przepadnie po cichu;
  // przy tej dysproporcji nie warto na to wydawac logiki.
  conv.io.cmd.valid   := timeout
  conv.io.cmd.payload := next
  // --- zatrzask wyniku -------------------------------------------------
  val shown = Vec.fill(3)(Reg(UInt(4 bits)) init (0))
  when(conv.io.rsp.valid) {
    for (i <- 0 until 3) shown(i) := conv.io.rsp.payload(i)
  }

  // Cyfra 0 to jednosci, wiec na wyswietlaczu ida od prawej.
  mux.io.digits(0).code := shown(2)
  mux.io.digits(1).code := shown(1)
  mux.io.digits(2).code := shown(0)
  mux.io.digits.foreach(_.dot := False)

  io.seg := mux.io.seg
  io.en  := mux.io.en

  val write = Stream(Bits(8 bits))
  write.valid := timeout
  write.payload := B(value.asBits, 8 bits)
  write >-> ctrl.io.write
}

object UartDemoTopVerilog extends App {
  val baud = args.headOption.flatMap(_.toIntOption).getOrElse(115200)
  println(s"Using baudrate = ${baud}")
  val cfg = UartCtrlInitConfig(
    baudrate = baud,
    dataLength = 7, //8 bits
    parity = UartParityType.NONE,
    stop = UartStopType.ONE
  )
  
  SpinalConfig(
    targetDirectory = "hw/gen/",
    defaultClockDomainFrequency  = FixedFrequency(100 MHz),
    defaultConfigForClockDomains = ClockDomainConfig(
      clockEdge        = RISING,
      resetKind        = BOOT
    )
  ).generateVerilog(new UartDemoTop(cfg))
}


