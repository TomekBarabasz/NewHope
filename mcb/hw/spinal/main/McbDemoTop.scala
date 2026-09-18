package newhope.mcb

import spinal.core._
import spinal.lib._
import newhope.mimas_v2._

/**
 * Top: MCB + silnik burstow na warstwie Stream.
 *
 * Nazwy portow sa te same co wczesniej, zeby mimas_v2.ucf dzialal bez zmian.
 * DOSZEDL jeden pin: pass_toggle - trzeba mu dac LOC.
 *
 * Pomiar przepustowosci bez ChipScope i bez UART-a:
 *   pass_toggle przelacza stan po kazdym pelnym przebiegu, wiec OKRES sygnalu
 *   to DWA przebiegi. Zmierz czestotliwosc miernikiem albo oscyloskopem i licz:
 *
 *     bajtow na przebieg = 2 * burstLen * burstCount * bytesPerWord
 *                          (raz zapisane, raz odczytane)
 *     czas przebiegu     = 1 / (2 * f_toggle)
 *     przepustowosc      = bajtow na przebieg * 2 * f_toggle
 *
 *   Dla domyslnego benchmarku: 2 * 32 * 1024 * 4 = 256 kB na przebieg.
 *   Sufit portu przy 32 b i 25 MHz to 100 MB/s, wiec przy pelnej wydajnosci
 *   spodziewaj sie okolo 380 przebiegow na sekunde (f_toggle ~190 Hz).
 */
class McbDemoTop(
    c            : MigConfig = MigConfig(),
    burstLen     : Int       = 32,
    burstCount   : Int       = 1024,
    stride       : Int       = 128,
    sweepColumns : Boolean   = false,
    holdCycles   : Int       = 0,
    injectFault  : Boolean   = false,
    maxOutstanding : Int     = 0,
    strictWrite    : Boolean = false,
    singlePass     : Boolean = false,
    errIdxWidth    : Int     = 5,
    simulation   : Boolean   = false
) extends Component {

  val io = new Bundle {
    val c3_sys_clk   = in Bool()   // 100 MHz
    val c3_sys_rst_n = in Bool()   // UWAGA: aktywny WYSOKI (C3_RST_ACT_LOW = 0)

    val mcb3_dram_dq    = inout(Analog(Bits(c.dqPins bits)))
    val mcb3_dram_a     = out Bits (c.memAddrWidth bits)
    val mcb3_dram_ba    = out Bits (c.bankAddrWidth bits)
    val mcb3_dram_ras_n = out Bool()
    val mcb3_dram_cas_n = out Bool()
    val mcb3_dram_we_n  = out Bool()
    val mcb3_dram_cke   = out Bool()
    val mcb3_dram_ck    = out Bool()
    val mcb3_dram_ck_n  = out Bool()
    val mcb3_dram_dqs   = inout(Analog(Bool()))
    val mcb3_dram_udqs  = inout(Analog(Bool()))
    val mcb3_dram_dm    = out Bool()
    val mcb3_dram_udm   = out Bool()
    val mcb3_rzq        = inout(Analog(Bool()))

    val calib_done  = out Bool()
    val test_done   = out Bool()
    val test_error  = out Bool()          // dataError || mcbFault
    val test_phase  = out Bits (2 bits)
    val pass_toggle = out Bool()
    // --- diagnostyka: nowe piny ---
    val data_error  = out Bool()          // NIEZGODNOSC danych
    val mcb_fault   = out Bool()          // flaga bledu z MCB
    val err_idx     = out UInt (errIdxWidth bits)
    // --- wyswietlacz 3 x 7-seg (Mimas V2, wspolna anoda) ---
    val seg = out Bits (8 bits)
    val en  = out Bits (3 bits)
  }
  noIoPrefix()

  val mcb = new s6_lpddr(c, simulation)

  // ---------------------------------------------------------------- piny DRAM
  mcb.io.c3_sys_clk   := io.c3_sys_clk
  mcb.io.c3_sys_rst_n := io.c3_sys_rst_n

  io.mcb3_dram_dq   <> mcb.io.mcb3_dram_dq
  io.mcb3_dram_dqs  <> mcb.io.mcb3_dram_dqs
  io.mcb3_dram_udqs <> mcb.io.mcb3_dram_udqs
  io.mcb3_rzq       <> mcb.io.mcb3_rzq

  io.mcb3_dram_a     := mcb.io.mcb3_dram_a
  io.mcb3_dram_ba    := mcb.io.mcb3_dram_ba
  io.mcb3_dram_ras_n := mcb.io.mcb3_dram_ras_n
  io.mcb3_dram_cas_n := mcb.io.mcb3_dram_cas_n
  io.mcb3_dram_we_n  := mcb.io.mcb3_dram_we_n
  io.mcb3_dram_cke   := mcb.io.mcb3_dram_cke
  io.mcb3_dram_ck    := mcb.io.mcb3_dram_ck
  io.mcb3_dram_ck_n  := mcb.io.mcb3_dram_ck_n
  io.mcb3_dram_dm    := mcb.io.mcb3_dram_dm
  io.mcb3_dram_udm   := mcb.io.mcb3_dram_udm

  io.calib_done := mcb.io.c3_calib_done

  // ------------------------------------------------- domena zegarowa z MCB
  val uiCd = mcb.uiClockDomain

  val core = new ClockingArea(uiCd) {
    val port = mcb.p0(uiCd)

    val engine = MigBurstEngine(
      c            = c,
      burstLen     = burstLen,
      burstCount   = burstCount,
      stride       = stride,
      sweepColumns = sweepColumns,
      holdCycles     = holdCycles,
      injectFault    = injectFault,
      maxOutstanding = maxOutstanding,
      strictWrite    = strictWrite,
      singlePass     = singlePass,
      errIdxWidth    = errIdxWidth
    )

    // calib_done przychodzi z domeny mcb_drp_clk - dwa przerzutniki za 2 LUT-y
    engine.io.calib := BufferCC(mcb.io.c3_calib_done, False)
    // flagi bledow portu sa juz w domenie portu, wiec bez synchronizacji
    engine.io.fault := mcb.portFaultBits

    val status = MigStatusDisplay(c)
    status.io.calib     := BufferCC(mcb.io.c3_calib_done, False)
    status.io.dataError := engine.io.dataError
    status.io.mcbFault  := engine.io.mcbFault
    status.io.faultCode := engine.io.faultCode
    status.io.errBcd    := engine.io.errBcd
    status.io.passPulse := engine.io.passPulse

    // SevenSegMux liczy szczeliny z podanej czestotliwosci, wiec MUSI dostac
    // zegar UI (25 MHz), a nie domyslne 100 MHz - inaczej ramka bylaby 4x wolniejsza.
    val sevenSeg = SevenSegMux(
      clkFrequency = c.uiFrequency,
      frameRate    = 1 kHz,
      blankCycles  = 64
    )
    sevenSeg.io.digits := status.io.digits

    port.cmd << engine.io.port.cmd
    port.wr  << engine.io.port.wr
    engine.io.port.rd << port.rd
  }

  io.test_done   := core.engine.io.done
  io.test_error  := core.engine.io.error
  io.test_phase  := core.engine.io.phase
  io.pass_toggle := core.engine.io.pass
  io.data_error  := core.engine.io.dataError
  io.mcb_fault   := core.engine.io.mcbFault
  io.err_idx     := core.engine.io.errIdx
  io.seg         := core.sevenSeg.io.seg
  io.en          := core.sevenSeg.io.en
}

object McbDemoTopVerilog extends App {

  val cfg32  = MigConfig(dataWidth = 32)    // Config-1, port 32 b  -> 100 MB/s
  // clock period : 10000 -> 100MHz
  // clock period :  8000 -> 125MHz
  // clock period :  6666 -> 150MHz
  // clock period :  6000 -> 166MHz
  val cfg128 = MigConfig(dataWidth = 128, memClkPeriod = 6000)   // Config-5, port 128 b -> 400 MB/s + 125MHz

  /**
   * Benchmarki sa tak dobrane, zeby na KAZDEJ szerokosci portu przerzucic
   * dokladnie 1 MiB na przebieg. Dzieki temu odczyt z wyswietlacza to wprost
   * MiB/s i wyniki roznych konfiguracji mozna porownywac bez przeliczania.
   *
   * Bez tego zabiegu pulapka jest podstepna: przy 128 bitach i tym samym
   * burstCount na przebieg idzie 4x wiecej bajtow, wiec wyswietlacz pokazalby
   * TO SAMO 381 mimo czterokrotnie wiekszej przepustowosci.
   */
  def bench(cfg: MigConfig) = {
    val bl = 32
    val n  = 1048576 / (2 * bl * cfg.bytesPerWord)   // tak, by wyszlo 1 MiB
    new McbDemoTop(
      c = cfg, burstLen = bl, burstCount = n,
      stride = bl * cfg.bytesPerWord, sweepColumns = false, holdCycles = 0
    )
  }

  /**
   * Regresja: krok 2 kB rusza banki i wiersze, przerwa sprawdza odswiezanie.
   * Przerwa liczona Z ZEGARA, nie wpisana na sztywno - przy zmianie
   * czestotliwosci pamieci zostaje 200 ms, a nie zmienia sie razem z nia.
   */
  def regression(cfg: MigConfig) = new McbDemoTop(
    c = cfg, burstLen = 1, burstCount = 4096, stride = 2048,
    sweepColumns = true, holdCycles = (cfg.uiClkHz / 5).toInt, errIdxWidth = 13
  )

  /** Bisekcja z poprzedniego kroku, na dowolnej szerokosci. */
  def diag(cfg: MigConfig, outstanding: Int, strict: Boolean) = new McbDemoTop(
    c = cfg, burstLen = 1, burstCount = 16, stride = 2048,
    sweepColumns = true, holdCycles = 0,
    maxOutstanding = outstanding, strictWrite = strict, singlePass = true
  )

  val mode = if (args.isEmpty) "bench32" else args(0)
  val (cfg, top) = mode match {
    case "bench32"    => (cfg32,  () => bench(cfg32))
    case "bench128"   => (cfg128, () => bench(cfg128))
    case "regress32"  => (cfg32,  () => regression(cfg32))
    case "regress128" => (cfg128, () => regression(cfg128))
    case "diag1"      => (cfg32,  () => diag(cfg32, 1, true))
    case "diag2"      => (cfg32,  () => diag(cfg32, 0, true))
    case "diag3"      => (cfg32,  () => diag(cfg32, 0, false))
    case other        => sys.error(s"nieznany tryb '$other'")
  }

  SpinalConfig(
    targetDirectory = s"hw/gen/verilog/$mode",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind        = SYNC,
      resetActiveLevel = HIGH
    )
  ).generateVerilog(top())

  val bytesPerPass = 2L * 32 * (1048576 / (2 * 32 * cfg.bytesPerWord)) * cfg.bytesPerWord
  println(s"""
    |tryb            : $mode  ->  rtl/$mode/McbDemoTop.v
    |szerokosc portu : ${cfg.dataWidth} b (${cfg.bytesPerWord} B/slowo)
    |zegar pamieci   : ${cfg.memClkHz / 1000000} MHz (okres ${cfg.memClkPeriod} ps)
    |zegar UI        : ${cfg.uiClkHz / 1000000} MHz  <- ZALOZENIE memclk/${cfg.uiClkDivider}!
    |                  sprawdz C3_CLKOUT2_DIVIDE w infrastructure.v
    |                  i wyprowadzone ograniczenia w .twr
    |sufit portu     : ${cfg.portPeakBytesPerSec / 1000000} MB/s
    |szczyt pamieci  : ${cfg.dramPeakBytesPerSec / 1000000} MB/s
    |na przebieg     : ${bytesPerPass / 1024} KiB
    |oczekiwany odczyt przy pelnym wysyceniu portu:
    |                  ${cfg.portPeakBytesPerSec / bytesPerPass} (czyli MiB/s)
    |
    |wyswietlacz: --- przed kalibracja, EEn blad MCB (1 wr_underrun,
    |             2 wr_error, 3 rd_overflow, 4 rd_error),
    |             liczba z kropkami = indeks bledu, bez kropek = MiB/s
    |""".stripMargin)
}
