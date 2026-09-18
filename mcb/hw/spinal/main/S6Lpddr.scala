package newhope.mcb

import spinal.core._

/**
 * BlackBox wygenerowanego przez MIG 3.61 core'a "s6_lpddr" (Spartan-6 MCB, LPDDR).
 * Lista portow i generikow 1:1 z s6_lpddr.veo. Wlaczony jest tylko Port0.
 *
 * UWAGA: C3_RST_ACT_LOW = 0 -> port c3_sys_rst_n jest aktywny w stanie WYSOKIM
 *        (infrastructure.v: assign sys_rst = C_RST_ACT_LOW ? ~sys_rst_n : sys_rst_n).
 *
 * Ograniczenie czasowe w UCF celuje w siec za IBUFG, wewnatrz tej instancji:
 *   NET "* /memc3_infrastructure_inst/sys_clk_ibufg" TNM_NET = "SYS_CLK3";
 * Jesli zmienisz nazwe `val mcb` w nadrzednym module, wildcard "*" to zniesie,
 * ale nie licz na to przy bardziej szczegolowych sciezkach.
 */
class s6_lpddr(val c: MigConfig = MigConfig(), val simulation: Boolean = false)
    extends BlackBox {

  val generic = new Generic {
    val C3_P0_MASK_SIZE       = c.maskWidth
    val C3_P0_DATA_PORT_SIZE  = c.dataWidth
    // C3_P1_* celowo NIE przekazujemy. W Config-5 (jeden port 128 b) te
    // generiki w ogole nie istnieja, a przekazanie nieistniejacego to blad
    // elaboracji w ISE. W Config-1 istnieja, ale Port1 jest wylaczony, wiec
    // wartosci domyslne z s6_lpddr.v wystarcza. Dziala dla obu konfiguracji.
    val DEBUG_EN              = 0
    val C3_MEMCLK_PERIOD      = c.memClkPeriod
    val C3_CALIB_SOFT_IP      = "TRUE"
    val C3_SIMULATION         = if (simulation) "TRUE" else "FALSE"
    val C3_RST_ACT_LOW        = 0
    val C3_INPUT_CLK_TYPE     = "SINGLE_ENDED"
    val C3_MEM_ADDR_ORDER     = "ROW_BANK_COLUMN"
    val C3_NUM_DQ_PINS        = c.dqPins
    val C3_MEM_ADDR_WIDTH     = c.memAddrWidth
    val C3_MEM_BANKADDR_WIDTH = c.bankAddrWidth
  }

  val io = new Bundle {
    val c3_sys_clk   = in Bool()
    val c3_sys_rst_n = in Bool()

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

    val c3_clk0       = out Bool()
    val c3_rst0       = out Bool()
    val c3_calib_done = out Bool()

    val c3_p0_cmd_clk       = in Bool()
    val c3_p0_cmd_en        = in Bool()
    val c3_p0_cmd_instr     = in Bits (3 bits)
    val c3_p0_cmd_bl        = in Bits (6 bits)
    val c3_p0_cmd_byte_addr = in Bits (c.addrWidth bits)
    val c3_p0_cmd_empty     = out Bool()
    val c3_p0_cmd_full      = out Bool()

    val c3_p0_wr_clk      = in Bool()
    val c3_p0_wr_en       = in Bool()
    val c3_p0_wr_mask     = in Bits (c.maskWidth bits)
    val c3_p0_wr_data     = in Bits (c.dataWidth bits)
    val c3_p0_wr_full     = out Bool()
    val c3_p0_wr_empty    = out Bool()
    val c3_p0_wr_count    = out Bits (c.countWidth bits)
    val c3_p0_wr_underrun = out Bool()
    val c3_p0_wr_error    = out Bool()

    val c3_p0_rd_clk      = in Bool()
    val c3_p0_rd_en       = in Bool()
    val c3_p0_rd_data     = out Bits (c.dataWidth bits)
    val c3_p0_rd_full     = out Bool()
    val c3_p0_rd_empty    = out Bool()
    val c3_p0_rd_count    = out Bits (c.countWidth bits)
    val c3_p0_rd_overflow = out Bool()
    val c3_p0_rd_error    = out Bool()
  }

  noIoPrefix()

  // c3_clk0 (wyjscie) wraca na c3_p0_*_clk (wejscia) tej samej instancji.
  // To nie jest petla kombinacyjna, wiec wylaczamy check.
  addTag(noCombinatorialLoopCheck)

  /**
   * Domena zegarowa interfejsu uzytkownika, oddana przez MCB.
   * c3_clk0 = memclk / uiClkDivider, c3_rst0 synchroniczny, aktywny wysoki.
   */
  def uiClockDomain: ClockDomain = ClockDomain(
    clock  = io.c3_clk0,
    reset  = io.c3_rst0,
    config = ClockDomainConfig(
      clockEdge        = RISING,
      resetKind        = SYNC,
      resetActiveLevel = HIGH
    ),
    frequency = FixedFrequency(c.uiFrequency)
  )

  /**
   * Stream-owy widok Port0. Podlacza trzy wejscia zegarowe portu do podanej
   * domeny i zwraca bundle po stronie MCB (czyli odbiorce cmd/wr, zrodlo rd).
   *
   * Zegary cmd/wr/rd sa NIEZALEZNYMI wejsciami - kolejki portu robia CDC.
   * Podanie tu czegos szybszego niz c3_clk0 to jedna z dzwigni przepustowosci,
   * ale najpierw przeczytaj sekcje "Clocking" w UG388.
   */
  def p0(portCd: ClockDomain): MigPort = {
    val port = MigPort(c)

    io.c3_p0_cmd_clk := portCd.readClockWire
    io.c3_p0_wr_clk  := portCd.readClockWire
    io.c3_p0_rd_clk  := portCd.readClockWire

    io.c3_p0_cmd_en        := port.cmd.fire
    io.c3_p0_cmd_instr     := port.cmd.instr
    io.c3_p0_cmd_bl        := port.cmd.bl.asBits
    io.c3_p0_cmd_byte_addr := port.cmd.addr.asBits
    port.cmd.ready         := !io.c3_p0_cmd_full

    io.c3_p0_wr_en   := port.wr.fire
    io.c3_p0_wr_data := port.wr.data
    io.c3_p0_wr_mask := port.wr.mask
    port.wr.ready    := !io.c3_p0_wr_full

    port.rd.valid   := !io.c3_p0_rd_empty
    port.rd.payload := io.c3_p0_rd_data
    io.c3_p0_rd_en  := port.rd.fire

    port
  }

  /** Bledy zglaszane przez sam MCB - kazdy oznacza utracone albo przekrecone dane. */
  def portFault: Bool = portFaultBits.orR

  /**
   * Te same flagi rozdzielone, zeby dalo sie pokazac, KTORA zawiodla.
   * Kolejnosc od najbardziej znaczacego bitu:
   *   3 = wr_underrun (zapisane smieci)
   *   2 = wr_error
   *   1 = rd_overflow (zgubione dane odczytu)
   *   0 = rd_error
   */
  def portFaultBits: Bits =
    io.c3_p0_wr_underrun ## io.c3_p0_wr_error ##
    io.c3_p0_rd_overflow ## io.c3_p0_rd_error
}
