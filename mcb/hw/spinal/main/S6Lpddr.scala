package newhope.mcb

import spinal.core._

/**
 * BlackBox wygenerowanego przez MIG 3.61 core'a "s6_lpddr" (Spartan-6 MCB, LPDDR).
 *
 * Lista portow i generikow 1:1 z s6_lpddr.veo.
 * Wlaczony jest tylko Port0 (32-bit bi-directional).
 *
 * UWAGA: C3_RST_ACT_LOW = 0 -> port c3_sys_rst_n jest aktywny w stanie WYSOKIM
 *        (patrz infrastructure.v: assign sys_rst = C_RST_ACT_LOW ? ~sys_rst_n : sys_rst_n).
 */
class s6_lpddr(
    val dqPins        : Int     = 16,
    val memAddrWidth  : Int     = 13,
    val bankAddrWidth : Int     = 2,
    val memClkPeriod  : Int     = 10000,   // ps
    val simulation    : Boolean = false
) extends BlackBox {

  val generic = new Generic {
    val C3_P0_MASK_SIZE       = 4
    val C3_P0_DATA_PORT_SIZE  = 32
    val C3_P1_MASK_SIZE       = 4
    val C3_P1_DATA_PORT_SIZE  = 32
    val DEBUG_EN              = 0
    val C3_MEMCLK_PERIOD      = memClkPeriod
    val C3_CALIB_SOFT_IP      = "TRUE"
    val C3_SIMULATION         = if (simulation) "TRUE" else "FALSE"
    val C3_RST_ACT_LOW        = 0
    val C3_INPUT_CLK_TYPE     = "SINGLE_ENDED"
    val C3_MEM_ADDR_ORDER     = "ROW_BANK_COLUMN"
    val C3_NUM_DQ_PINS        = dqPins
    val C3_MEM_ADDR_WIDTH     = memAddrWidth
    val C3_MEM_BANKADDR_WIDTH = bankAddrWidth
  }

  val io = new Bundle {
    // ---- system ----
    val c3_sys_clk   = in Bool()
    val c3_sys_rst_n = in Bool()

    // ---- piny DRAM ----
    val mcb3_dram_dq    = inout(Analog(Bits(dqPins bits)))
    val mcb3_dram_a     = out Bits (memAddrWidth bits)
    val mcb3_dram_ba    = out Bits (bankAddrWidth bits)
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

    // ---- zegar/reset UI + status ----
    val c3_clk0       = out Bool()
    val c3_rst0       = out Bool()
    val c3_calib_done = out Bool()

    // ---- Port 0: command FIFO ----
    val c3_p0_cmd_clk       = in Bool()
    val c3_p0_cmd_en        = in Bool()
    val c3_p0_cmd_instr     = in Bits (3 bits)
    val c3_p0_cmd_bl        = in Bits (6 bits)
    val c3_p0_cmd_byte_addr = in Bits (30 bits)
    val c3_p0_cmd_empty     = out Bool()
    val c3_p0_cmd_full      = out Bool()

    // ---- Port 0: write FIFO ----
    val c3_p0_wr_clk      = in Bool()
    val c3_p0_wr_en       = in Bool()
    val c3_p0_wr_mask     = in Bits (4 bits)
    val c3_p0_wr_data     = in Bits (32 bits)
    val c3_p0_wr_full     = out Bool()
    val c3_p0_wr_empty    = out Bool()
    val c3_p0_wr_count    = out Bits (7 bits)
    val c3_p0_wr_underrun = out Bool()
    val c3_p0_wr_error    = out Bool()

    // ---- Port 0: read FIFO ----
    val c3_p0_rd_clk      = in Bool()
    val c3_p0_rd_en       = in Bool()
    val c3_p0_rd_data     = out Bits (32 bits)
    val c3_p0_rd_full     = out Bool()
    val c3_p0_rd_empty    = out Bool()
    val c3_p0_rd_count    = out Bits (7 bits)
    val c3_p0_rd_overflow = out Bool()
    val c3_p0_rd_error    = out Bool()
  }

  noIoPrefix()

  // c3_clk0 (wyjscie) wraca na c3_p0_*_clk (wejscia) tego samego blackboxa -
  // to nie jest petla kombinacyjna, wiec wylaczamy check.
  addTag(noCombinatorialLoopCheck)
}

object MigInstr {
  val WRITE    = B"3'b000"
  val READ     = B"3'b001"
  val WRITE_AP = B"3'b010"
  val READ_AP  = B"3'b011"
  val REFRESH  = B"3'b100"
}
