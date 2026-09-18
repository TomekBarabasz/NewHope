package newhope.mcb

import spinal.core._
import spinal.lib._

/**
 * Piny pamieci LPDDR.
 *
 * WAZNE dla UCF: nazwa pola w io nadaje prefiks generowanym portom. Zadeklaruj
 * to jako `val mcb3_dram = MigDramPins(c)`, a wyjda dokladnie `mcb3_dram_dq`,
 * `mcb3_dram_a`, `mcb3_dram_ras_n` i tak dalej - czyli nazwy, ktore juz masz
 * w mimas_v2.ucf. Zmiana nazwy tego pola zepsuje wszystkie LOC-i naraz.
 *
 * `mcb3_rzq` celowo NIE jest tutaj - w bundlu dostalby prefiks i wyszedlby
 * `mcb3_dram_rzq`, co wymusiloby poprawke w UCF. Zostaje osobnym sygnalem.
 *
 * Kierunki sa wpisane w pola, wiec bundle uzywa sie w io wprost, bez
 * master/slave - piny pamieci i tak zawsze ida w jedna strone.
 */
case class MigDramPins(c: MigConfig) extends Bundle {
  val dq    = inout(Analog(Bits(c.dqPins bits)))
  val a     = out Bits (c.memAddrWidth bits)
  val ba    = out Bits (c.bankAddrWidth bits)
  val ras_n = out Bool()
  val cas_n = out Bool()
  val we_n  = out Bool()
  val cke   = out Bool()
  val ck    = out Bool()
  val ck_n  = out Bool()
  val dqs   = inout(Analog(Bool()))
  val udqs  = inout(Analog(Bool()))
  val dm    = out Bool()
  val udm   = out Bool()
}

/**
 * Cala obsluga MCB w jednej linii.
 *
 *   val mcb = McbCore(c, io.c3_sys_clk, io.c3_sys_rst_n, io.mcb3_dram, io.mcb3_rzq)
 *
 *   val logic = new ClockingArea(mcb.uiCd) {
 *     val engine = MigBurstEngine(...)
 *     mcb.port.driveFrom(engine.io.port)
 *   }
 *
 * `ClockingArea` zostaje widoczne celowo. Zegar interfejsu uzytkownika wychodzi
 * Z MCB (c3_clk0 = memclk/4), wiec twoja logika MUSI siedziec w tej domenie,
 * a nie w domenie zegara systemowego. Ukrycie tego pod dywanem tylko czekaloby,
 * az ktos dolozy rejestr w zlej domenie.
 *
 * UWAGA na polaryzacje resetu: sysRst jest aktywny WYSOKIM, mimo ze port
 * blackboxa nazywa sie c3_sys_rst_n (MIG wygenerowal C3_RST_ACT_LOW = 0).
 */
case class McbCore(
    c          : MigConfig,
    sysClk     : Bool,
    sysRst     : Bool,
    dram       : MigDramPins,
    rzq        : Bool,
    simulation : Boolean = false
) extends Area {

  val mcb = new s6_lpddr(c, simulation)

  /** Domena zegarowa oddana przez MCB: c3_clk0 + c3_rst0, reset SYNC/HIGH. */
  val uiCd = mcb.uiClockDomain

  /** Strona uzytkownika: Stream-y komend, danych zapisu i danych odczytu. */
  val port = mcb.p0(uiCd)

  /** Kalibracja zakonczona. Sygnal z domeny mcb_drp_clk - zsynchronizuj. */
  def calibDone: Bool = mcb.io.c3_calib_done

  /** Flagi bledow portu: 3 wr_underrun, 2 wr_error, 1 rd_overflow, 0 rd_error. */
  def faults: Bits = mcb.portFaultBits

  // ---- okablowanie, ktore wczesniej siedzialo w topie -------------------
  mcb.io.c3_sys_clk   := sysClk
  mcb.io.c3_sys_rst_n := sysRst

  dram.dq   <> mcb.io.mcb3_dram_dq
  dram.dqs  <> mcb.io.mcb3_dram_dqs
  dram.udqs <> mcb.io.mcb3_dram_udqs
  rzq       <> mcb.io.mcb3_rzq

  dram.a     := mcb.io.mcb3_dram_a
  dram.ba    := mcb.io.mcb3_dram_ba
  dram.ras_n := mcb.io.mcb3_dram_ras_n
  dram.cas_n := mcb.io.mcb3_dram_cas_n
  dram.we_n  := mcb.io.mcb3_dram_we_n
  dram.cke   := mcb.io.mcb3_dram_cke
  dram.ck    := mcb.io.mcb3_dram_ck
  dram.ck_n  := mcb.io.mcb3_dram_ck_n
  dram.dm    := mcb.io.mcb3_dram_dm
  dram.udm   := mcb.io.mcb3_dram_udm
}
