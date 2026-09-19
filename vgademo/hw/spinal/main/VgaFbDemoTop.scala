package newhope.vgademo

import spinal.core._
import spinal.lib._
import spinal.lib.graphic.vga.VgaCtrl
import newhope.mcb.{McbCore, MigConfig, MigDramPins}
import newhope.mimas_v2.SevenSegMux

/**
 * Demo: framebuffer w LPDDR na wyjsciu VGA, obrazek wgrywany przez UART.
 *
 * NAZWY PORTOW SA KONTRAKTEM Z UCF-em. Kazde pole io ponizej ma odpowiednik
 * w mimas_v2.ucf - lacznie z dziwactwami w rodzaju `HSync` z duza litera.
 * Zmiana nazwy pola to zerwanie LOC-ow, nie kosmetyka.
 *
 * Jedyna zmiana w UCF-ie, ktora trzeba zrobic, dotyczy niebieskiego: Numato
 * nazywa dwa piny Blue[2] i Blue[1] (zostawiajac dziure po Blue[0]), a bundle
 * Rgb ma pole b szerokie na 2 bity, czyli Blue[1:0]. Patrz ucf/mimas_v2_vgafb.ucf.
 *
 * DOMENA ZEGAROWA: wszystko ponizej siedzi w ClockingArea(mcb.uiCd). Zegar UI
 * MCB to memclk/4 = 25,000 MHz i jest jednoczesnie zegarem piksela. Pierwszy
 * rejestr dolozony poza ta domena trafi na zegar systemowy i zrobi przejscie
 * miedzydomenowe bez synchronizacji.
 */
class VgaFbDemoTop(c: VgaFbConfig) extends Component {
  val io = new Bundle {
    // ---- MCB / LPDDR ----
    val c3_sys_clk   = in Bool ()
    val c3_sys_rst_n = in Bool () // aktywny WYSOKIM, MIG dal C3_RST_ACT_LOW = 0
    val mcb3_dram    = MigDramPins(c.mig)
    val mcb3_rzq     = inout(Analog(Bool()))

    // ---- VGA ----
    val HSync = out Bool ()
    val VSync = out Bool ()
    val Red   = out Bits (3 bits)
    val Green = out Bits (3 bits)
    val Blue  = out Bits (2 bits)

    // ---- UART ----
    val UART_RX = in Bool ()
    val UART_TX = out Bool ()

    // ---- 7-seg ----
    val seg = out Bits (8 bits)
    val en  = out Bits (3 bits)

    // ---- diody (nazwy juz sa w UCF po projekcie MCB) ----
    val calib_done = out Bool ()
    val test_done  = out Bool () // przyjeto co najmniej jeden pakiet
    val test_error = out Bool () // blad protokolu albo CRC
    val data_error = out Bool () // niedobieg pikseli (underflow bufora linii)
    val mcb_fault  = out Bool () // wr_underrun / wr_error / rd_overflow / rd_error
  }
  noIoPrefix()

  val mcb = McbCore(c.mig, io.c3_sys_clk, io.c3_sys_rst_n, io.mcb3_dram, io.mcb3_rzq)

  val core = new ClockingArea(mcb.uiCd) {

    val calib     = BufferCC(mcb.calibDone, False)
    val calibRise = calib && !RegNext(calib).init(False)

    // ---- generator czasowania VGA ---------------------------------------
    val vga = VgaCtrl(c.rgb, timingsWidth = 12)
    c.applyTimings(vga.io.timings)
    /** Do kalibracji nie ma czego pokazywac - monitor widzi po prostu brak
      * sygnalu zamiast smieci z niezainicjowanego DRAM-u. */
    vga.io.softReset := !calib

    // ---- masterzy pamieci -------------------------------------------------
    val reader  = FbLineReader(c)
    val painter = FbPainter(c)
    val loader  = UartFbLoader(c)
    val arb     = FbArbiter(c)

    arb.io.read  <> reader.io.bus
    arb.io.paint <> painter.io.bus
    arb.io.host  <> loader.io.bus

    /** Jedyne dotkniecie MigPort w calym demo. */
    mcb.port.driveFrom(FbBus.toMigPort(arb.io.mem))

    // ---- podwojne buforowanie --------------------------------------------
    /** Przelaczenie bufora tylko na poczatku klatki - inaczej zobaczylbys
      * polowe starego obrazu i polowe nowego (tearing). */
    val showB = RegInit(False)
    when(vga.io.frameStart) { showB := loader.io.show }

    reader.io.base       := showB ? U(c.bufferB, c.mig.addrWidth bits) | U(c.bufferA, c.mig.addrWidth bits)
    reader.io.frameStart := vga.io.frameStart
    /** Bez tego czytnik wystawia READ do MCB juz w resecie, przed kalibracja. */
    reader.io.enable     := calib
    vga.io.pixels << reader.io.pixels

    painter.io.base  := U(c.bufferA, c.mig.addrWidth bits)
    painter.io.start := calibRise

    /** Host dostaje dostep do pamieci dopiero, gdy painter skonczy - dwoch
      * piszacych naraz pomieszaloby dane w kolejce zapisu MCB. */
    loader.io.enable := calib && !painter.io.busy

    // ---- wyjscia VGA ------------------------------------------------------
    /**
     * PULAPKA: VgaCtrl NIE zeruje koloru poza obszarem widocznym - podaje
     * wprost payload strumienia. Bez maski colorEn monitor dostaje "piedestal"
     * w czasie wygaszania i albo gubi synchronizacje, albo pokazuje sprany
     * obraz. Maska jest tutaj, w jednym miejscu.
     */
    val colorEn = vga.io.vga.colorEn
    io.HSync := vga.io.vga.hSync
    io.VSync := vga.io.vga.vSync
    io.Red   := (colorEn ? vga.io.vga.color.r | U(0, 3 bits)).asBits
    io.Green := (colorEn ? vga.io.vga.color.g | U(0, 3 bits)).asBits
    io.Blue  := (colorEn ? vga.io.vga.color.b | U(0, 2 bits)).asBits

    // ---- UART -------------------------------------------------------------
    loader.io.uart.rxd := io.UART_RX
    io.UART_TX         := loader.io.uart.txd

    // ---- status -----------------------------------------------------------
    val status = FbStatus(c)
    status.io.calib      := calib
    status.io.faults     := mcb.faults
    status.io.frameStart := vga.io.frameStart
    status.io.bytePulse  := loader.io.bytePulse

    val ss = SevenSegMux(clkFrequency = c.mig.uiFrequency, frameRate = 1 kHz)
    ss.io.digits := status.io.digits
    io.seg := ss.io.seg
    io.en  := ss.io.en

    // ---- diody ------------------------------------------------------------
    val okLatch    = RegInit(False) setWhen loader.io.ok
    val errLatch   = RegInit(False) setWhen loader.io.error
    /**
     * Bramka `calib` nie jest ozdobna. VgaCtrl robi `io.pixels.ready := colorEn
     * || io.softReset`, wiec w czasie softResetu (czyli przed kalibracja)
     * strumien jest odsysany na sucho i `reader.io.underflow` zapalilby sie
     * natychmiast po konfiguracji, zanim cokolwiek zaczelo dzialac. Dioda
     * data_error ma znaczyc "obraz sie sypie", a nie "wlasnie wstalem".
     */
    val underLatch = RegInit(False) setWhen (calib && (reader.io.underflow || vga.io.error))
    val faultLatch = RegInit(False) setWhen mcb.faults.orR

    io.calib_done := calib
    io.test_done  := okLatch
    io.test_error := errLatch
    io.data_error := underLatch
    io.mcb_fault  := faultLatch
  }
}

/**
 * Generowanie Verilogu.
 *
 *   sbt "runMain newhope.vgademo.VgaFbDemoTopVerilog"          # 320x240, 19200
 *   sbt "runMain newhope.vgademo.VgaFbDemoTopVerilog full"     # 640x480
 *   sbt "runMain newhope.vgademo.VgaFbDemoTopVerilog 115200"   # po wymianie
 *                                                              # firmware PIC-a
 *
 * 19200 to NIE jest ostrozny domysl, tylko sztywna predkosc mostka USB-UART
 * na Mimasie V2. Fabryczny firmware PIC-a nie zna innej - patrz §7.7 w README.
 */
object VgaFbDemoTopVerilog extends App {
  val scale = if (args.contains("full")) 1 else 2
  val baud  = args.find(_.forall(_.isDigit)).map(_.toInt).getOrElse(19200)

  val cfg = VgaFbConfig(
    mig        = MigConfig(dataWidth = 128, memClkPeriod = 10000),
    scale      = scale,
    burstWords = 20,
    uartBaud   = baud
  )
  cfg.report()

  SpinalConfig(
    targetDirectory = s"hw/gen/verilog/vgafb${cfg.fbWidth}x${cfg.fbHeight}",
    defaultConfigForClockDomains = ClockDomainConfig(
      clockEdge        = RISING,
      resetKind        = SYNC,
      resetActiveLevel = HIGH
    )
  ).generateVerilog(new VgaFbDemoTop(cfg))
}
