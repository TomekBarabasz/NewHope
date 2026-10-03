package newhope.vertebra.hil.fe

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import newhope.frontend.{MicFrontEnd, PowerSpectrum, Rfft}
import newhope.i2s.{I2sFrame, I2sSlave}
import newhope.vertebra.hil._
import HilProtocol._

// =====================================================================
//  Harness frontendu N0 + N1 (opcjonalnie N2 - N4) bez zegarow i IO
//  plytki (vertebra-hil.md §12, §13, contract/fe/commands.md).
//
//  DUT to MicFrontEnd (I2sMicRx + DcFilter), dokladnie ten blok, ktory
//  trafi do urzadzenia. FPGA jest masterem I2S: SCK/WS daje I2sMicRx
//  z dzielnika, ESP32 jest slave'em i udaje INMP441 na SD_IN.
//
//    SD_IN  -> MicFrontEnd -> (y, x) -> kolejka -> I2sSlave.tx -> SD_OUT
//    SCK/WS z MicFrontEnd -> piny i wejscia I2sSlave (nadajnik powrotny)
//
//  Wariant z N4: za MicFrontEnd stoja Rfft (Framer + FftCore + RealUnpack)
//  i PowerSpectrum, w tej samej domenie resetu co DUT. FeN4Tap liczy CRC
//  ramek na N2 (wyjscie Framera), N3 i N4 i wysyla rekordy kanalem
//  pomocniczym ramki powrotnej (bajt na probke).
//
//  Nadajnik powrotny to I2sSlave z newhope.i2s zegarowany wewnetrznym
//  SCK/WS DUT-a: ESP32 jako slave odbiera wynik na tym samym zegarze,
//  na ktorym nadaje bodziec (full duplex jednego kontrolera).
//
//  x (echo probki N0) idzie obok potoku filtra: zatrzask przy probce N0,
//  odczyt przy probce N1 4 cykle pozniej (kolejna probka przychodzi po
//  tysiacach cykli). Host liczy golden z x, a nie z bodzca, wiec blad
//  transportu nigdy nie udaje bledu filtra.
//
//  Bieg:
//    start - DUT 16 cykli w resecie (stan filtra zerowy, I2sMicRx od
//            WS = 1), potem taktuje; ramki ida na SD_OUT, liczniki licza
//    stop  - migawka licznikow; DUT taktuje jeszcze `tail` probek
//            z cisza na SD_OUT (ESP32 domyka bufory DMA), potem do granicy
//            ramki, reset i SCK staje. status.locked = DUT taktuje.
//  Przed pierwszym startem i po soft resecie SCK/WS sa w wysokiej
//  impedancji (clkOe = 0): FPGA nie walczy z ESP32 z innym firmware'em.
// =====================================================================
case class FeHarnessPins() extends Bundle {
  val sdIn          = in  Bool()
  val sckOut, wsOut = out Bool()
  val sdOut         = out Bool()
  val clkOe         = out Bool()
}

case class FeHarness(v : FeHilVariant, bg : HilBridgeGenerics, build : Long) extends Component {
  require(v.isLegal, s"nielegalny wariant ${v.name}: ${v.problems.mkString("; ")}")
  val sw = v.sampleWidth

  val io = new Bundle {
    val sysClk, sysRst, dutClk, dutRst = in Bool()
    val uart = master(Uart())
    val i2s  = FeHarnessPins()
    val led  = out Bits(8 bits)
    val trig = out Bool()        // do analizatora: DUT w resecie z HilResetInjector
  }

  val sysCd = ClockDomain(io.sysClk, io.sysRst)
  val dutCd = ClockDomain(io.dutClk, io.dutRst)

  val counters = HilCounters(sysCd, dutCd, FeHilRegs.counters)
  val runRegs  = HilRunRegs(sysCd, dutCd)
  val ipRegs   = FeHilRegs(sysCd, dutCd)

  val sys = new ClockingArea(sysCd) {
    val core = HilCore(bg, ascii4("FE"), build, v.code)
    io.uart <> core.io.uart
    HilRegBus.decode(core.io.ext, Seq(
      (Counters.Base, Counters.Size, counters.io.bus),
      (Addr.RunBase,  Addr.RunSize,  runRegs.io.bus),
      (Addr.IpBase,   Addr.IpSize,   ipRegs.io.bus)))

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
    val go = runRegs.io.dutGo                      // konfiguracja juz zatrzasnieta
    val rc = runRegs.io.dutCfg
    val ic = ipRegs.io.dutCfg

    val running = RegInit(False)
    when(go) { running := True }
    when(stopDut || softDut) { running := False }

    // SCK/WS jako wyjscia od pierwszego startu do soft resetu.
    val clkOe = RegInit(False)
    when(go) { clkOe := True }
    when(softDut) { clkOe := False }

    // Reset DUT-a na poczatku biegu i przy soft resecie: 16 cykli.
    val clrCnt = Reg(UInt(5 bits)) init 0
    when(go || softDut) { clrCnt := 16 } elsewhen(clrCnt =/= 0) { clrCnt := clrCnt - 1 }
    val clearing = clrCnt =/= 0 || go || softDut

    val inj = HilResetInjector()
    inj.io.start := go
    inj.io.stop  := stopDut || softDut
    inj.io.cfg   := rc

    // Po stop DUT taktuje jeszcze `tail` probek (cisza na SD_OUT), a potem
    // do granicy ramki: SCK staje tuz po narastajacym zboczu, ktore zamyka
    // prawy slot (r0 lewego), wiec ESP32 slave nie zostaje z polowa ramki.
    val tailLeft = Reg(UInt(16 bits)) init 0
    val draining = RegInit(False)
    val active   = running || draining

    val rstDut = RegNext(!active || clearing || inj.io.dutReset) init True
    val dutDom = ClockDomain(io.dutClk, rstDut, config = dutCd.config)
    val front  = dutDom(new MicFrontEnd(v.fe))
    val rfft   = v.rfft.map(g => dutDom(new Rfft(g)))
    val power  = v.rfft.map(g => dutDom(new PowerSpectrum(g.core.dataWidth, g.core.expWidth)))
    for (r <- rfft; p <- power) {
      r.io.input << front.io.output
      p.io.input << r.io.output
      p.io.output.ready := True                    // widmo opróżniane na biezaco, Framer bez backpressure
    }

    // Nadajnik powrotny: reset tylko przy starcie i soft resecie, reset
    // DUT-a z injectora go nie dotyczy (to harness, nie DUT).
    val rstTx = RegNext(clearing) init True
    val tx    = ClockDomain(io.dutClk, rstTx, config = dutCd.config)(I2sSlave(FeFrame.txSlave))

    // --- SCK dla ESP32 ------------------------------------------------
    // I2sMaster po resecie robi pusty prawy slot (WS = 1 przez slotBits
    // okresow SCK) i dopiero potem pierwsza ramke. ESP32 slave przy takim
    // starcie wysyla w pierwszym lewym slocie slowo prawe (~L) - na plytce
    // pierwsza probka N0 kazdego biegu byla zanegowana. Dawny I2sMicRx
    // opuszczal WS po jednym okresie SCK i ESP32 startowal dobrze, wiec
    // ESP32 dostaje SCK dopiero od ostatniego bitu slotu wstepnego: widzi
    // jedno narastajace zbocze przy WS = 1, potem ramke - jak wczesniej.
    // DUT (i nadajnik powrotny) chodzi na pelnym SCK; slot wstepny DUT
    // i tak odrzuca. Po kazdym resecie DUT-a od nowa (domena dut).
    val espGate = new ClockingArea(dutDom) {
      val S     = v.fe.i2s.slotBits
      val sckD  = RegNext(front.io.sck) init False
      val falls = Reg(UInt(log2Up(S) bits)) init 0
      val open  = RegInit(False)
      when(sckD && !front.io.sck && !open) {
        falls := falls + 1
        when(falls === U(S - 2)) { open := True }   // po S - 1 opadajacych: zostaje ostatni bit slotu
      }
    }

    // --- piny --------------------------------------------------------
    front.io.sd := io.i2s.sdIn
    io.i2s.sckOut := front.io.sck && espGate.open   // open zmienia sie przy SCK = 0
    io.i2s.wsOut  := front.io.ws
    io.i2s.clkOe  := clkOe
    tx.io.pins.sck := front.io.sck
    tx.io.pins.ws  := front.io.ws
    tx.io.pins.sdo := False                       // RX nadajnika powrotnego nieuzywany
    io.i2s.sdOut   := tx.io.pins.sdi

    // --- probka N0 (echo x) i bypass -----------------------------------
    val n0 = front.rx.io.output.pull()
    val inIdx  = Reg(UInt(32 bits)) init 0
    val lastX  = Reg(SInt(sw bits)) init 0
    val bypass = RegInit(False)
    when(n0.valid) {
      lastX  := n0.payload
      inIdx  := inIdx + 1
      bypass := inIdx >= ic.bypassFrom && inIdx < ic.bypassTo
    }
    front.io.bypassDc := bypass

    // --- probka N1 -> ramka --------------------------------------------
    val rstSeen = RegInit(False)                  // reset DUT-a od ostatniej wyslanej ramki
    when(inj.io.dutReset) { rstSeen := True }

    val outIdx = Reg(UInt(FeFrame.IdxBits bits)) init 0
    val y      = front.io.output.payload
    val fwd    = running && front.io.output.valid
    val frames = Reg(UInt(32 bits)) init 0

    // --- N2 - N4: CRC i zrzut, bajty kanalem pomocniczym ---------------
    val n4 = v.rfft.map { g =>
      val t   = FeN4Tap(g)
      val r   = rfft.get
      val fr  = r.framer.io.output.pull()
      t.io.start     := go
      t.io.dutRst    := rstDut
      t.io.n2.valid  := fr.fire
      t.io.n2.payload := fr.payload
      t.io.n3.valid  := r.io.output.fire
      t.io.n3.payload := r.io.output.payload
      t.io.n4.valid  := power.get.io.output.fire
      t.io.n4.payload := power.get.io.output.payload
      t.io.trig      := (frames - 1).resize(24)
      t.io.dumpEvery := ic.dumpEvery
      t.io.aux.ready := fwd
      t
    }
    val frOverrun = RegInit(False)
    for (r <- rfft) when(running && r.io.overrun) { frOverrun := True }
    val auxValid = n4.fold(False)(_.io.aux.valid)
    val auxWord  = n4.fold(B(0, 9 bits))(_.io.aux.payload)
    val overrunAny = front.io.dcOverrun || frOverrun

    val frame = I2sFrame(FeFrame.SlotBits)
    frame.left  := (y.asBits ## B(0, FeFrame.MaxSampleWidth - sw bits) ## outIdx.asBits ##
                    rstSeen ## bypass ## overrunAny ## auxValid ## (auxValid && auxWord(8)) ## False)
    frame.right := (lastX.asBits ## B(0, FeFrame.MaxSampleWidth - sw bits) ##
                    Mux(auxValid, auxWord(7 downto 0), B(0, 8 bits)) ## B(FeFrame.Marker, FeFrame.MarkerBits bits))
    require(frame.left.getWidth == 32 && frame.right.getWidth == 32)

    val queue = StreamFifo(I2sFrame(FeFrame.SlotBits), 4)
    queue.io.flush       := clearing
    queue.io.push.valid   := fwd
    queue.io.push.payload := frame
    tx.io.tx << queue.io.pop

    // --- liczniki ------------------------------------------------------
    val sent      = Reg(UInt(32 bits)) init 0
    val underrun  = Reg(UInt(32 bits)) init 0
    val overflow  = Reg(UInt(32 bits)) init 0
    val xSum      = Reg(UInt(32 bits)) init 0
    val ySum      = Reg(UInt(32 bits)) init 0

    when(fwd) {
      frames := frames + 1
      outIdx := outIdx + 1
      rstSeen := False
      xSum := xSum + lastX.resize(32 bits).asUInt
      ySum := ySum + y.resize(32 bits).asUInt
      when(!queue.io.push.ready) { overflow := overflow + 1 }
    }
    when(running && tx.io.tx.fire) { sent := sent + 1 }
    when(running && tx.io.underrun) { underrun := underrun + 1 }

    val sckD     = RegNext(front.io.sck) init False
    val sckRise  = front.io.sck && !sckD
    val wsAtRise = RegInit(True)
    when(sckRise) { wsAtRise := front.io.ws }
    val frameEnd = sckRise && !front.io.ws && wsAtRise      // r0 lewego slotu

    when(stopDut && running) { tailLeft := ic.tail; draining := True }
      .elsewhen(!running && tailLeft =/= 0 && front.io.output.valid) { tailLeft := tailLeft - 1 }
      .elsewhen(draining && tailLeft === 0 && frameEnd) { draining := False }
    when(softDut) { tailLeft := 0; draining := False }

    when(go) {
      sent := 0; frames := 0; underrun := 0; overflow := 0; xSum := 0; ySum := 0
      outIdx := 0; inIdx := 0; rstSeen := True; frOverrun := False
    }

    def n4w(f : FeN4Tap => UInt) : Bits = n4.fold(B(0, 32 bits))(t => f(t).asBits)
    val words = Seq[Bits](sent.asBits, frames.asBits, underrun.asBits, overflow.asBits,
                          front.io.dcOverrun.asBits.resize(32), xSum.asBits, ySum.asBits, inj.io.done.asBits,
                          n4w(_.io.frames), n4w(_.io.crcRecs), n4w(_.io.dumps), n4w(_.io.drops), n4w(_.io.order),
                          frOverrun.asBits.resize(32))
    require(words.size == FeHilRegs.counters.size)
    counters.io.dutStop := stopDut
    for ((w, i) <- words.zipWithIndex) counters.io.dutWords(i) := w

    io.trig := RegNext(inj.io.dutReset) init False

    // do statusu: rejestry w dut, w sys przez BufferCC
    val dutRunning = RegNext(active && !rstDut) init False
    val anyError   = RegNext(overflow =/= 0 || overrunAny || n4w(_.io.drops) =/= 0 || n4w(_.io.order) =/= 0) init False
  }

  // --- status i diody (sys) -----------------------------------------------
  val sysOut = new ClockingArea(sysCd) {
    val active = BufferCC(dut.dutRunning, False)
    val error  = BufferCC(dut.anyError, False)
    sys.core.io.locked := active
    sys.core.io.error  := error

    val heartbeat = Reg(UInt(27 bits)) init 0
    heartbeat := heartbeat + 1
    //  7 heartbeat, 6 running, 5 DUT taktuje, 4 overflow/overrun, 3 0,
    //  2 blad ramki UART, 1 porzucona ramka UART, 0 bajt zgubiony
    io.led := heartbeat.msb ## sys.core.io.running ## active ## error ## False ##
              sys.core.io.rxError ## sys.core.io.timeout ## sys.core.io.dropped
  }
}
