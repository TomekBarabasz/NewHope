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
/**
 * Top: MCB + silnik burstow + status na wyswietlaczu.
 *
 * Cala obsluga kontrolera siedzi w McbCore, wiec tutaj zostaje tylko to, co
 * naprawde nalezy do tego konkretnego projektu: piny plytki i podlaczenie
 * silnika do portu.
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
    val c3_sys_clk   = in Bool()   // 100 MHz z oscylatora
    val c3_sys_rst_n = in Bool()   // UWAGA: aktywny WYSOKI (C3_RST_ACT_LOW = 0)

    // nazwa pola daje prefiks portow: mcb3_dram_dq, mcb3_dram_a, ...
    val mcb3_dram = MigDramPins(c)
    val mcb3_rzq  = inout(Analog(Bool()))

    val calib_done  = out Bool()
    val test_done   = out Bool()
    val test_error  = out Bool()
    val test_phase  = out Bits (2 bits)
    val pass_toggle = out Bool()
    val data_error  = out Bool()
    val mcb_fault   = out Bool()
    val err_idx     = out UInt (errIdxWidth bits)
    val seg         = out Bits (8 bits)
    val en          = out Bits (3 bits)
  }
  noIoPrefix()

  val mcb = McbCore(c, io.c3_sys_clk, io.c3_sys_rst_n, io.mcb3_dram, io.mcb3_rzq, simulation)
  io.calib_done := mcb.calibDone

  // Zegar UI wychodzi Z MCB, wiec logika uzytkownika musi siedziec w jego domenie.
  val core = new ClockingArea(mcb.uiCd) {
    val engine = MigBurstEngine(
      c = c, burstLen = burstLen, burstCount = burstCount, stride = stride,
      sweepColumns = sweepColumns, holdCycles = holdCycles, injectFault = injectFault,
      maxOutstanding = maxOutstanding, strictWrite = strictWrite,
      singlePass = singlePass, errIdxWidth = errIdxWidth
    )

    mcb.port.driveFrom(engine.io.port)
    engine.io.calib := BufferCC(mcb.calibDone, False)  // z domeny mcb_drp_clk
    engine.io.fault := mcb.faults

    val status = MigStatusDisplay(c)
    status.io.calib     := engine.io.calib
    status.io.dataError := engine.io.dataError
    status.io.mcbFault  := engine.io.mcbFault
    status.io.faultCode := engine.io.faultCode
    status.io.errBcd    := engine.io.errBcd
    status.io.passPulse := engine.io.passPulse

    // SevenSegMux liczy szczeliny z podanej czestotliwosci, wiec MUSI dostac
    // zegar UI, a nie domyslne 100 MHz.
    val sevenSeg = SevenSegMux(c.uiHertz, frameRate = 1 kHz, blankCycles = 64)
    sevenSeg.io.digits := status.io.digits
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

  // ---------------------------------------------------------------- zegary
  // JEDNO miejsce na cala konfiguracje zegarow. Dzielniki PLL ida stad wprost
  // do parametrow s6_lpddr.v, wiec Verilogu nie trzeba dotykac.
  //
  //   MigConfig.mem150ui100                      gotowy preset
  //   MigConfig.forClocks(memMHz = 150, uiMHz = 100)   solver
  //   MigConfig.forClocks(166.67, 100, allowClkOut0 = Seq(1,2,3,4))
  //                                              lepszy jitter, CLKOUT0 = 3,
  //                                              ale sprawdz w Clocking Wizard
  //
  // Zmiana dataWidth wymaga PRZEGENEROWANIA core'a w MIG - to nie jest
  // parametr, ktory da sie przelaczyc samym Verilogiem.
  val cfg = MigConfig.forClocks(memMHz = 150, uiMHz = 75)

  // ---------------------------------------------------------------- tryby
  /**
   * Benchmark. burstCount dobrany tak, by na przebieg przypadlo dokladnie
   * 1 MiB NIEZALEZNIE od szerokosci portu - wtedy odczyt z wyswietlacza to
   * wprost MiB/s i konfiguracje sa porownywalne.
   *
   * Bez tego pulapka jest podstepna: przy szerszym porcie na przebieg idzie
   * proporcjonalnie wiecej bajtow, wiec wyswietlacz pokazalby TO SAMO mimo
   * wiekszej przepustowosci.
   */
  def bench(burstLen: Int = 32) = {
    val n = 1048576 / (2 * burstLen * cfg.bytesPerWord)
    new McbDemoTop(
      c = cfg, burstLen = burstLen, burstCount = n,
      stride = burstLen * cfg.bytesPerWord, sweepColumns = false, holdCycles = 0
    )
  }

  /** Regresja: krok 2 kB przez banki i wiersze, 200 ms na odswiezanie. */
  def regression = new McbDemoTop(
    c = cfg, burstLen = 1, burstCount = 4096, stride = 2048,
    sweepColumns = true, holdCycles = (cfg.uiClkHz / 5).toInt, errIdxWidth = 13
  )

  /** Bisekcja: kazdy krok zmienia dokladnie jeden element, jeden przebieg. */
  def diag(outstanding: Int, strict: Boolean) = new McbDemoTop(
    c = cfg, burstLen = 1, burstCount = 16, stride = 2048,
    sweepColumns = true, holdCycles = 0,
    maxOutstanding = outstanding, strictWrite = strict, singlePass = true
  )

  val mode = if (args.isEmpty) "bench" else args(0)
  val top: () => McbDemoTop = mode match {
    case "bench"   => () => bench()
    case "bench16" => () => bench(16)
    case "bench64" => () => bench(64)
    case "regress" => () => regression
    case "diag1"   => () => diag(1, true)
    case "diag2"   => () => diag(0, true)
    case "diag3"   => () => diag(0, false)
    case other     => sys.error(s"nieznany tryb '$other'")
  }

  SpinalConfig(
    targetDirectory = s"hw/gen/$mode",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind        = SYNC,
      resetActiveLevel = HIGH
    )
  ).generateVerilog(top())

  // ------------------------------------------------------------ podsumowanie
  val burstLen     = mode match { case "bench16" => 16; case "bench64" => 64; case _ => 32 }
  val bytesPerPass = 2L * burstLen * (1048576 / (2 * burstLen * cfg.bytesPerWord)) * cfg.bytesPerWord
  val peak         = cfg.portPeakBytesPerSec min cfg.dramPeakBytesPerSec
  val limiter      = if (cfg.portPeakBytesPerSec < cfg.dramPeakBytesPerSec) "port" else "pamiec"

  println(s"""
    |tryb: $mode  ->  hw/gen/$mode/McbDemoTop.v
    |
    |${cfg.report}
    |  zapas portu : ${"%.2f".format(cfg.uiClkHz.toDouble / cfg.uiClkMinHz)}x ponad minimum
    |
    |  ogranicza   : $limiter (${peak / 1000000} MB/s)
    |  na przebieg : ${bytesPerPass / 1024} KiB
    |  oczekiwany odczyt przy pelnym wysyceniu: ${peak / bytesPerPass}
    |
    |wyswietlacz: --- przed kalibracja, EEn blad MCB (1 wr_underrun,
    |             2 wr_error, 3 rd_overflow, 4 rd_error),
    |             liczba z kropkami = indeks bledu, bez kropek = MiB/s
    |""".stripMargin)
}
