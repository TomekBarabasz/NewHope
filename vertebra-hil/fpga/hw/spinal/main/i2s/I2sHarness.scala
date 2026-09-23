package newhope.vertebra.hil.i2s

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import newhope.i2s.{I2sMaster, I2sSlave}
import newhope.vertebra.hil._
import HilProtocol._

// =====================================================================
//  Harness I2S bez zegarow i IO plytki (vertebra-hil.md §6).
//
//  Zegary sys i dut przychodza z zewnatrz: w symulacji z testu, na
//  plytce z I2sHarnessTop (DCM, BOOT). Piny I2S sa rozdzielone na
//  wejscia, wyjscia i enable - bufory trojstanowe robi dopiero top.
//
//  Rola (I2sHilRegs, zatrzasnieta przy starcie):
//    master: I2sMaster steruje SCK/WS/SD_OUT, czyta SD_IN; slave w resecie
//    slave : I2sSlave czyta SCK/WS/SD_IN, steruje SD_OUT; master w resecie
//  Generator, checker i reszta sa wspolne i przelaczane rola.
//
//  Reset DUT-a (z rejestru, bez szpilek): nieaktywna rola, soft reset
//  (16 cykli) albo HilResetInjector. W resecie DUT nie dostaje ramek
//  z generatora - inaczej ramka przepadlaby w DUT-cie bez sladu.
// =====================================================================
case class I2sHarnessPins() extends Bundle {
  val sckIn, wsIn, sdIn         = in  Bool()
  val sckOut, wsOut, sdOut      = out Bool()
  val clkOe                     = out Bool()     // SCK/WS jako wyjscia (rola master)
}

case class I2sHarness(v : I2sHilVariant, bg : HilBridgeGenerics, build : Long) extends Component {
  require(v.isLegal, s"nielegalny wariant $v")

  val io = new Bundle {
    val sysClk, sysRst, dutClk, dutRst = in Bool()
    val uart = master(Uart())
    val i2s  = I2sHarnessPins()
    val led  = out Bits(8 bits)
  }

  val sysCd = ClockDomain(io.sysClk, io.sysRst)
  val dutCd = ClockDomain(io.dutClk, io.dutRst)

  val counters = HilCounters(sysCd, dutCd)
  val capture  = HilCapture(sysCd, dutCd)
  val runRegs  = HilRunRegs(sysCd, dutCd)
  val ipRegs   = I2sHilRegs(v, sysCd, dutCd)

  val sys = new ClockingArea(sysCd) {
    val core = HilCore(bg, ascii4("I2S"), build, v.code)
    io.uart <> core.io.uart
    HilRegBus.decode(core.io.ext, Seq(
      (Counters.Base, Counters.Size, counters.io.bus),
      (Addr.RunBase,  Addr.RunSize,  runRegs.io.bus),
      (Addr.IpBase,   Addr.IpSize,   ipRegs.io.bus),
      (Capture.Base,  Capture.size,  capture.io.bus)))

    counters.io.start  := core.io.start
    runRegs.io.running := core.io.running
    ipRegs.io.running  := core.io.running
    core.io.snapshot   := counters.io.ready
  }

  val startDut = PulseCCByToggle(sys.core.io.start,     sysCd, dutCd)
  val stopDut  = PulseCCByToggle(sys.core.io.stop,      sysCd, dutCd)
  val softDut  = PulseCCByToggle(sys.core.io.softReset, sysCd, dutCd)

  val dut = new ClockingArea(dutCd) {
    runRegs.io.dutStart := startDut
    ipRegs.io.dutStart  := startDut
    val go = runRegs.io.dutGo                      // konfiguracja obu blokow juz zatrzasnieta
    val rc = runRegs.io.dutCfg
    val ic = ipRegs.io.dutCfg
    val isMaster = ic.master

    val running = RegInit(False)
    when(go) { running := True }
    when(stopDut || softDut) { running := False }

    val softCnt = Reg(UInt(5 bits)) init 0
    when(softDut) { softCnt := 16 } elsewhen(softCnt =/= 0) { softCnt := softCnt - 1 }
    val softRst = softCnt =/= 0 || softDut
    val clear   = go || softDut

    val inj = HilResetInjector()
    inj.io.start := go
    inj.io.stop  := stopDut || softDut
    inj.io.cfg   := rc

    val rstM = RegNext(!isMaster || softRst || inj.io.dutReset) init True
    val rstS = RegNext( isMaster || softRst || inj.io.dutReset) init True

    val master = ClockDomain(io.dutClk, rstM, config = dutCd.config)(I2sMaster(v.masterG))
    val slave  = ClockDomain(io.dutClk, rstS, config = dutCd.config)(I2sSlave(v.slaveG))

    // --- piny --------------------------------------------------------
    master.io.pins.sdi := io.i2s.sdIn
    slave.io.pins.sck  := io.i2s.sckIn
    slave.io.pins.ws   := io.i2s.wsIn
    slave.io.pins.sdo  := io.i2s.sdIn              // dane master -> slave
    io.i2s.sckOut := master.io.pins.sck
    io.i2s.wsOut  := master.io.pins.ws
    io.i2s.sdOut  := Mux(isMaster, master.io.pins.sdo, slave.io.pins.sdi)
    io.i2s.clkOe  := isMaster

    // --- generator -> aktywny DUT -------------------------------------
    val gen = I2sPatternGen(v.width)
    gen.io.clear    := clear
    gen.io.enable   := running
    gen.io.seed     := rc.seed
    gen.io.gapMode  := rc.gapMode
    gen.io.gapEvery := rc.gapEvery
    gen.io.gapLen   := rc.gapLen

    master.io.tx.valid   := isMaster && !rstM && gen.io.tx.valid
    master.io.tx.payload := gen.io.tx.payload
    slave.io.tx.valid    := !isMaster && !rstS && gen.io.tx.valid
    slave.io.tx.payload  := gen.io.tx.payload
    gen.io.tx.ready  := Mux(isMaster, master.io.tx.ready && !rstM, slave.io.tx.ready && !rstS)
    gen.io.underrun  := Mux(isMaster, master.io.underrun, slave.io.underrun)

    // --- aktywny DUT -> checker ---------------------------------------
    val chk = I2sPatternCheck(v.width)
    chk.io.clear        := clear
    chk.io.rx.valid     := running && Mux(isMaster, master.io.rx.valid, slave.io.rx.valid)
    chk.io.rx.payload   := Mux(isMaster, master.io.rx.payload, slave.io.rx.payload)
    chk.io.cfg.seed     := rc.seed
    chk.io.cfg.peerW    := ic.peerW
    chk.io.cfg.slot     := ic.slot

    // --- capture ------------------------------------------------------
    capture.io.dutClear         := clear
    capture.io.dutPush.valid    := chk.io.cap.valid
    capture.io.dutPush.payload(0) := chk.io.cap.payload.idx.asBits
    capture.io.dutPush.payload(1) := chk.io.cap.payload.got.left.resize(32)
    capture.io.dutPush.payload(2) := chk.io.cap.payload.got.right.resize(32)
    capture.io.dutPush.payload(3) := chk.io.cap.payload.exp.left.resize(32)
    capture.io.dutPush.payload(4) := chk.io.cap.payload.exp.right.resize(32)
    capture.io.dutTrigger       := chk.io.badPulse

    // --- liczniki (kolejnosc: HilProtocol.Counters.names) ---------------
    val s = chk.io.stat
    val words = Seq[Bits](
      gen.io.sent.asBits, s.frames.asBits, s.bad.asBits, s.gaps.asBits, s.relocks.asBits,
      s.lockAt.asBits, s.errN.asBits,
      s.errGot.left.resize(32), s.errGot.right.resize(32),
      s.errExp.left.resize(32), s.errExp.right.resize(32),
      (chk.io.overrun || capture.io.dutDropped).asBits.resize(32),
      capture.io.dutCount.asBits, inj.io.done.asBits)
    require(words.size == Counters.names.size)
    counters.io.dutStop := stopDut
    for ((w, i) <- words.zipWithIndex) counters.io.dutWords(i) := w
  }

  // --- status i diody (sys) -----------------------------------------------
  val sysOut = new ClockingArea(sysCd) {
    val locked = BufferCC(dut.chk.io.locked, False)
    val error  = BufferCC(dut.chk.io.stat.errValid, False)
    val master = BufferCC(dut.isMaster, False)
    sys.core.io.locked := locked
    sys.core.io.error  := error

    val heartbeat = Reg(UInt(27 bits)) init 0
    heartbeat := heartbeat + 1
    //  7 heartbeat, 6 running, 5 lock, 4 blad wzorca, 3 rola master,
    //  2 blad ramki UART, 1 porzucona ramka UART, 0 bajt zgubiony
    io.led := heartbeat.msb ## sys.core.io.running ## locked ## error ## master ##
              sys.core.io.rxError ## sys.core.io.timeout ## sys.core.io.dropped
  }
}
