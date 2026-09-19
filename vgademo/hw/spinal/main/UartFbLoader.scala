package newhope.vgademo

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.com.uart._

/**
 * Wgrywanie obrazu przez UART prosto do LPDDR.
 *
 * RAMKA
 * -----
 *   A5 5A 01 addr[4 LE] len[4 LE] <len bajtow> crc      zapis
 *   A5 5A 02 bufor                                      wybor bufora (0/1)
 *   A5 5A 03                                            ping
 * Odpowiedz to jeden bajt: 'K' albo 'E'. crc to XOR bajtow danych.
 *
 * Bajt odpowiedzi jest jedyna kontrola przeplywu, jakiej potrzebuje host, i
 * jednoczesnie odpowiedzia na pytanie "czy dane naprawde wyladowaly" - MCB nie
 * potwierdza zapisow, wiec 'K' wysylamy dopiero po tym, jak ostatnia komenda
 * WRITE opuscila kolejke.
 *
 * DLACZEGO NIE MA FIFO NA WEJSCIU
 * -------------------------------
 * UartCtrl oddaje odebrany bajt jako Flow - nie da sie go zatrzymac. Zamiast
 * buforowac, automat jest tak zbudowany, ze KAZDY bajt obsluguje w jednym
 * takcie, a wypychanie slowa do MCB idzie obok, w osobnym rejestrze. Przy
 * 115200 bd kolejny bajt przychodzi po ~2200 taktach zegara UI, a wypchniecie
 * trwa 2 takty. Gdyby ten margines kiedys znikl, zapala sie 'E' zamiast po
 * cichu przekrecic obraz.
 *
 * ZAPIS PO JEDNYM SLOWIE (bl = 0) jest tu swiadomie nieefektywny: 16 bajtow na
 * komende. Przy 11 KB/s to 1/1000 portu, a w zamian warunek "dane przed
 * komenda" jest spelniony trywialnie i nie ma stanu do zgubienia.
 */
case class UartFbLoader(c: VgaFbConfig) extends Component {
  val io = new Bundle {
    val uart      = master(Uart())
    val bus       = master(FbBus(c))
    val enable    = in Bool()
    val show      = out Bool()
    val busy      = out Bool()
    val bytePulse = out Bool()
    val ok        = out Bool()
    val error     = out Bool()
  }

  io.bus.idleRead()

  val ctrl = UartCtrl(
    UartCtrlInitConfig(
      baudrate   = c.uartBaud,
      dataLength = 7, // 7 oznacza 8 bitow danych
      parity     = UartParityType.NONE,
      stop       = UartStopType.ONE
    )
  )
  io.uart <> ctrl.io.uart

  /**
   * UartCtrl.io.read jest STRUMIENIEM, nie Flow - ma `ready` i jest to wejscie
   * kontrolera, wiec bez tej linii elaboracja konczy sie "NO DRIVER ON
   * io_read_ready". Trzymamy stale wysoko, czyli swiadomie sprowadzamy go do
   * Flow: automat ponizej obsluguje kazdy bajt w jednym takcie, a przy 115200 bd
   * i zegarze 25 MHz miedzy bajtami jest ~2170 taktow zapasu. Naruszenie tego
   * zalozenia nie zniknie po cichu - lapie je `flowErr` i odpowiedz 'E'.
   *
   * Bajty przychodzace przed kalibracja (io.enable = False) sa odbierane
   * i wyrzucane, a nie zatrzymywane w kontrolerze - inaczej pierwszy bajt po
   * wlaczeniu bylby smieciem sprzed kalibracji.
   */
  ctrl.io.read.ready := True

  val rxValid = ctrl.io.read.valid && io.enable
  val rxData  = ctrl.io.read.payload
  val rxU     = rxData.asUInt

  val tx = Stream(Bits(8 bits))
  tx.valid   := False
  tx.payload := B(0, 8 bits)
  ctrl.io.write << tx

  // =====================================================================
  //  Wypychacz: jedno slowo + jedna komenda, niezaleznie od automatu ramki
  // =====================================================================
  val push = new Area {
    val pending  = RegInit(False)
    val dataDone = RegInit(False)
    val word     = Reg(Bits(c.mig.dataWidth bits)) init 0
    val addr     = Reg(UInt(c.mig.addrWidth bits)) init 0

    io.bus.wr.valid   := pending && !dataDone
    io.bus.wr.payload := word
    when(io.bus.wr.fire) { dataDone := True }

    io.bus.cmd.valid         := pending && dataDone
    io.bus.cmd.payload.write := True
    io.bus.cmd.payload.addr  := addr
    io.bus.cmd.payload.bl    := 0
    when(io.bus.cmd.fire) {
      pending  := False
      dataDone := False
      addr     := addr + c.wordBytes
    }
  }

  // =====================================================================
  //  Automat ramki
  // =====================================================================
  val cmdReg    = Reg(UInt(8 bits)) init 0
  val argIdx    = Reg(UInt(4 bits)) init 0
  val argCnt    = Reg(UInt(4 bits)) init 0
  val acc       = Reg(Bits(64 bits)) init 0
  val wacc      = Reg(Bits(c.mig.dataWidth bits)) init 0
  val byteIdx   = Reg(UInt(c.wordBits bits)) init 0
  val remaining = Reg(UInt(32 bits)) init 0
  val crc       = Reg(Bits(8 bits)) init 0
  val ackOk     = RegInit(False)
  val flowErr   = RegInit(False)
  val showReg   = RegInit(False)

  val accNext  = rxData ## acc(63 downto 8)
  val waccNext = rxData ## wacc(c.mig.dataWidth - 1 downto 8)

  val fsm = new StateMachine {
    val sSync0 = new State with EntryPoint
    val sSync1 = new State
    val sCmd   = new State
    val sArgs  = new State
    val sData  = new State
    val sCrc   = new State
    val sAck   = new State

    sSync0.whenIsActive {
      when(rxValid && rxU === c.Proto.sync0) { goto(sSync1) }
    }

    sSync1.whenIsActive {
      when(rxValid) {
        when(rxU === c.Proto.sync1) { goto(sCmd) }
          .elsewhen(rxU =/= c.Proto.sync0) { goto(sSync0) }
      }
    }

    sCmd.whenIsActive {
      when(rxValid) {
        cmdReg  := rxU
        argIdx  := 0
        flowErr := False
        switch(rxU) {
          is(c.Proto.cmdWrite) { argCnt := 8; goto(sArgs) }
          is(c.Proto.cmdShow)  { argCnt := 1; goto(sArgs) }
          is(c.Proto.cmdPing)  { ackOk := True; goto(sAck) }
          default              { ackOk := False; goto(sAck) }
        }
      }
    }

    sArgs.whenIsActive {
      when(rxValid) {
        acc    := accNext
        argIdx := argIdx + 1
        when(argIdx === argCnt - 1) {
          when(cmdReg === c.Proto.cmdWrite) {
            val a = accNext(31 downto 0).asUInt
            val n = accNext(63 downto 32).asUInt
            // adres i dlugosc musza byc wyrownane do slowa portu, inaczej
            // MCB zapisalby w zle miejsce bez zadnego sygnalu bledu
            val bad = a(c.wordBits - 1 downto 0) =/= 0 ||
                      n(c.wordBits - 1 downto 0) =/= 0 ||
                      n === 0 ||
                      (a +^ n) > U(c.mig.memBytes, 33 bits)
            when(bad) {
              ackOk := False
              goto(sAck)
            } otherwise {
              push.addr := a.resized
              remaining := n
              byteIdx   := 0
              crc       := 0
              goto(sData)
            }
          } otherwise { // cmdShow
            showReg := rxData.lsb
            ackOk   := True
            goto(sAck)
          }
        }
      }
    }

    sData.whenIsActive {
      when(rxValid) {
        wacc      := waccNext
        crc       := crc ^ rxData
        byteIdx   := byteIdx + 1
        remaining := remaining - 1
        when(byteIdx === c.wordBytes - 1) {
          when(push.pending) { flowErr := True }
          push.pending := True
          push.word    := waccNext
        }
        when(remaining === 1) { goto(sCrc) }
      }
    }

    sCrc.whenIsActive {
      when(rxValid) {
        ackOk := (rxData === crc) && !flowErr
        goto(sAck)
      }
    }

    sAck.whenIsActive {
      tx.valid   := !push.pending // najpierw dane w pamieci, potem 'K'
      tx.payload := ackOk ? B(c.Proto.ackOk, 8 bits) | B(c.Proto.ackErr, 8 bits)
      when(tx.fire) { goto(sSync0) }
    }
  }

  io.show      := showReg
  io.busy      := !fsm.isActive(fsm.sSync0)
  io.bytePulse := rxValid && fsm.isActive(fsm.sData)
  io.ok        := tx.fire && ackOk
  io.error     := tx.fire && !ackOk
}
