package newhope.vertebra.hil

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import scala.math.BigDecimal.RoundingMode
import HilProtocol._

// =====================================================================
//  Most UART -> HilRegBus (contract/commands.md, "FPGA").
//
//  Zadanie:   A5 op addr_hi addr_lo [d3 d2 d1 d0] sum
//  Odpowiedz: 5A status d3 d2 d1 d0 sum
//  Kazda kompletna ramka dostaje dokladnie jedna odpowiedz, takze
//  z bledem (zla suma, nieznana operacja). Nieznana operacja ma dlugosc
//  odczytu: most nie zgaduje, ile bajtow jeszcze przyjdzie.
//
//  Resynchronizacja: bajty inne niz A5 poza ramka sa ignorowane, a
//  niedokonczona ramka przepada po `timeoutUs` ciszy. Bajty, ktore
//  przychodza w trakcie wykonania i wysylania odpowiedzi, sa gubione
//  (flaga dropped) - host czeka na odpowiedz, zanim wysle nastepne.
// =====================================================================

/** Parametry mostu. Dzielnik jak w HilEchoGenerics (ten sam wzor). */
case class HilBridgeGenerics(clkHz     : Long = 100000000L,   // oscylator Mimas V2
                             baud      : Long = 115200L,      // firmware PIC jimmo
                             timeoutUs : Long = 10000L) {     // commands.md: 10 ms
  val uartG         = UartCtrlGenerics()
  val samplesPerBit = uartG.rxSamplePerBit

  val clockDivider : Int =
    (BigDecimal(clkHz) / baud / samplesPerBit).setScale(0, RoundingMode.HALF_DOWN).toInt - 1

  def actualBaud   : Double = clkHz.toDouble / ((clockDivider + 1).toLong * samplesPerBit)
  def baudErrorPct : Double = (actualBaud - baud) / baud * 100

  /** Cykle zegara na bajt na linii (start + 8 + stop). */
  def byteCycles    : Long = (clockDivider + 1).toLong * samplesPerBit * 10
  def timeoutCycles : Long = clkHz / 1000000L * timeoutUs

  /** Jak w HilEchoGenerics: < 2 %, reszte zapasu (~5 %) zostawiamy drugiej stronie. */
  def baudOk    : Boolean = scala.math.abs(baudErrorPct) < 2.0
  def dividerOk : Boolean = clockDivider >= 1 && clockDivider < (1 << uartG.clockDividerWidth)
  /** Timeout musi przezyc przerwe miedzy bajtami jednej ramki z zapasem:
    * host pisze ramke jednym write(), ale USB-UART moze ja podzielic. */
  def timeoutOk : Boolean = timeoutCycles >= 10 * byteCycles && clkHz % 1000000L == 0
  def isLegal   : Boolean = baudOk && dividerOk && timeoutOk
}

case class HilUartBridge(g : HilBridgeGenerics) extends Component {
  require(g.isLegal, s"nielegalne generyki mostu: $g")

  val io = new Bundle {
    val uart    = master(Uart())
    val bus     = master(HilRegBus())
    val rxError = out Bool()     // sticky: blad ramki UART (zly baud, zly port)
    val dropped = out Bool()     // sticky: bajt w trakcie odpowiedzi
    val timeout = out Bool()     // sticky: porzucona niedokonczona ramka
  }

  val uart = new UartCtrl(g.uartG)
  uart.io.config.clockDivider     := g.clockDivider
  uart.io.config.frame.dataLength := 7                        // 8 bitow danych
  uart.io.config.frame.parity     := UartParityType.NONE
  uart.io.config.frame.stop       := UartStopType.ONE
  uart.io.writeBreak              := False
  io.uart <> uart.io.uart

  object St extends SpinalEnum {
    val sync, op, addrHi, addrLo, data, sum, exec, resp = newElement()
  }
  val st = RegInit(St.sync)

  val op     = Reg(Bits(8 bits))  init 0
  val addr   = Reg(UInt(16 bits)) init 0
  val data   = Reg(Bits(32 bits)) init 0
  val chk    = Reg(Bits(8 bits))  init 0
  val cnt    = Reg(UInt(3 bits))  init 0
  val status = Reg(Bits(8 bits))  init 0
  val rdata  = Reg(Bits(32 bits)) init 0

  // UartCtrlRx wystawia valid na jeden cykl niezaleznie od ready.
  val rx = uart.io.read
  rx.ready := True
  val b = rx.payload

  val receiving = st =/= St.sync && st =/= St.exec && st =/= St.resp

  // --- timeout niedokonczonej ramki ------------------------------------
  val idle = Reg(UInt(log2Up(g.timeoutCycles + 1) bits)) init 0
  val idleMax = U(BigInt(g.timeoutCycles), idle.getWidth bits)
  when(!receiving || rx.valid) { idle := 0 }
    .elsewhen(idle =/= idleMax) { idle := idle + 1 }
  val timedOut = receiving && idle === idleMax

  // --- flagi ------------------------------------------------------------
  val rxErr   = RegInit(False) setWhen(uart.io.readError)
  val drop    = RegInit(False) setWhen(rx.valid && (st === St.exec || st === St.resp))
  val tmo     = RegInit(False) setWhen(timedOut)
  io.rxError := rxErr
  io.dropped := drop
  io.timeout := tmo

  // --- magistrala -------------------------------------------------------
  val isWrite = op === B(OpWrite, 8 bits)
  val isRead  = op === B(OpRead,  8 bits)
  io.bus.valid := st === St.exec
  io.bus.write := isWrite
  io.bus.addr  := addr
  io.bus.wdata := data

  // --- odpowiedz --------------------------------------------------------
  val rspSum = B(RspSync, 8 bits) ^ status ^
               rdata(31 downto 24) ^ rdata(23 downto 16) ^ rdata(15 downto 8) ^ rdata(7 downto 0)
  val rspBytes = Vec(B(RspSync, 8 bits), status,
                     rdata(31 downto 24), rdata(23 downto 16), rdata(15 downto 8), rdata(7 downto 0),
                     rspSum)
  uart.io.write.valid   := st === St.resp
  uart.io.write.payload := rspBytes(cnt)

  // --- automat ----------------------------------------------------------
  switch(st) {
    is(St.sync) {
      when(rx.valid && b === B(ReqSync, 8 bits)) { chk := b; st := St.op }
    }
    is(St.op) {
      when(rx.valid) { op := b; chk := chk ^ b; st := St.addrHi }
    }
    is(St.addrHi) {
      when(rx.valid) { addr(15 downto 8) := b.asUInt; chk := chk ^ b; st := St.addrLo }
    }
    is(St.addrLo) {
      when(rx.valid) {
        addr(7 downto 0) := b.asUInt
        chk := chk ^ b
        cnt := 0
        when(isWrite) { st := St.data } otherwise { st := St.sum }
      }
    }
    is(St.data) {
      when(rx.valid) {
        data := data(23 downto 0) ## b
        chk  := chk ^ b
        cnt  := cnt + 1
        when(cnt === 3) { st := St.sum }
      }
    }
    is(St.sum) {
      when(rx.valid) {
        cnt   := 0
        rdata := 0
        when(b =/= chk) {
          status := B(Status.BadSum, 8 bits); st := St.resp
        }.elsewhen(!isRead && !isWrite) {
          status := B(Status.BadOp, 8 bits);  st := St.resp
        }.otherwise {
          st := St.exec
        }
      }
    }
    is(St.exec) {
      // Jeden cykl na magistrali; odpowiedz kombinacyjna (HilRegBus).
      status := io.bus.status
      rdata  := Mux(io.bus.status =/= B(Status.Ok, 8 bits), B(0, 32 bits),
                    Mux(isWrite, data, io.bus.rdata))
      st := St.resp
    }
    is(St.resp) {
      when(uart.io.write.fire) {
        cnt := cnt + 1
        when(cnt === RspBytes - 1) { st := St.sync }
      }
    }
  }
  when(timedOut) { st := St.sync }
}

// =====================================================================
//  Rejestry rdzenia 0x000-0x00F (contract/commands.md).
// =====================================================================
case class HilCoreRegs(ipId : Long, build : Long, variant : Long) extends Component {
  val io = new Bundle {
    val bus       = slave(HilRegBus())
    val start     = out Bool()      // impulsy z rejestru ctrl
    val stop      = out Bool()
    val softReset = out Bool()
    val running   = out Bool()
    val locked    = in  Bool()      // z checkera IP (etap 2d)
    val error     = in  Bool()
    val snapshot  = in  Bool()      // HilCounters.ready
    val scratch   = out Bits(32 bits)
  }

  private def u32(v : Long) : Bits = B(BigInt(v & 0xFFFFFFFFL), 32 bits)

  val running = RegInit(False)
  val scratch = Reg(Bits(32 bits)) init 0

  val map  = new HilRegMap(io.bus, lock = False)
  map.ro(Addr.Magic,   u32(Magic))
  map.ro(Addr.Proto,   u32(ProtoVersion))
  map.ro(Addr.IpId,    u32(ipId))
  map.ro(Addr.Build,   u32(build))
  val ctrl = map.strobe(Addr.Ctrl)
  map.ro(Addr.Status,  io.snapshot ## io.error ## io.locked ## running)   // bity 3..0
  map.ro(Addr.Variant, u32(variant))
  map.rw(Addr.Scratch, scratch, locked = false)
  map.build()

  io.start     := ctrl.valid && ctrl.payload(CtrlBit.Start)
  io.stop      := ctrl.valid && ctrl.payload(CtrlBit.Stop)
  io.softReset := ctrl.valid && ctrl.payload(CtrlBit.SoftReset)

  // Stop i soft reset wygrywaja ze startem w tym samym zapisie.
  when(io.start) { running := True }
  when(io.stop || io.softReset) { running := False }
  io.running := running
  io.scratch := scratch
}

// =====================================================================
//  Rdzen harnessu: most + rejestry rdzenia, adresy >= 0x010 idą na io.ext
//  (liczniki, generator, reset, blok IP). Wspolny dla wszystkich IP.
// =====================================================================
case class HilCore(g : HilBridgeGenerics, ipId : Long, build : Long, variant : Long) extends Component {
  val io = new Bundle {
    val uart      = master(Uart())
    val ext       = master(HilRegBus())
    val start     = out Bool()
    val stop      = out Bool()
    val softReset = out Bool()
    val running   = out Bool()
    val locked    = in  Bool()
    val error     = in  Bool()
    val snapshot  = in  Bool()
    val rxError   = out Bool()
    val dropped   = out Bool()
    val timeout   = out Bool()
    val scratch   = out Bits(32 bits)
  }

  val bridge = HilUartBridge(g)
  val regs   = HilCoreRegs(ipId, build, variant)

  io.uart <> bridge.io.uart
  HilRegBus.decode(bridge.io.bus, Seq(
    (Addr.CoreBase, Addr.CoreSize, regs.io.bus),
    (Addr.CoreSize, 0x10000 - Addr.CoreSize, io.ext)))

  io.start     := regs.io.start
  io.stop      := regs.io.stop
  io.softReset := regs.io.softReset
  io.running   := regs.io.running
  regs.io.locked := io.locked
  regs.io.error  := io.error
  regs.io.snapshot := io.snapshot
  io.rxError := bridge.io.rxError
  io.dropped := bridge.io.dropped
  io.timeout := bridge.io.timeout
  io.scratch := regs.io.scratch
}
