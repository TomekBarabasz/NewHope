package newhope.mcb

import spinal.core._
import spinal.lib.fsm._

/**
 * Top: MCB + test "zapisz N slow, odczekaj, odczytaj N slow, porownaj".
 *
 * Mapowanie adresu przy C3_MEM_ADDR_ORDER = "ROW_BANK_COLUMN", x16, 10 bitow kolumny:
 *
 *   byte_addr[0]      - bajt w slowie 16-bit
 *   byte_addr[10:1]   - kolumna
 *   byte_addr[12:11]  - bank
 *   byte_addr[25:13]  - wiersz
 *
 * Jeden wiersz = 2 kB. Zwykly liniowy przebieg po 1 kB nie tyka ani bitow banku,
 * ani wiersza, dlatego adres skladamy z dwoch czesci:
 *
 *   byte_addr = (idx << 11) | ((idx % 512) << 2)
 *                \_______/     \______________/
 *                bank+wiersz     kolumna
 *
 * Czesc niska siedzi w bitach [10:2], wysoka od bitu 11 w gore, wiec adresy sie
 * nie nakladaja, a jeden przebieg dotyka wszystkich bankow, wielu wierszy i
 * wszystkich bitow kolumny.
 *
 * @param wordCount       ile slow 32-bit zapisac i odczytac
 * @param retentionCycles przerwa miedzy faza zapisu i odczytu, w taktach c3_clk0
 *                        (50 MHz -> 5_000_000 = 100 ms, czyli ~13x tREFI = 7,8 ms).
 *                        Jesli dane to przezyja, odswiezanie dziala.
 * @param injectFault     KONTROLA NEGATYWNA: true psuje wzorzec oczekiwany, wiec
 *                        test_error MUSI sie zapalic. Zbuduj raz z true, sprawdz,
 *                        ze dioda swieci, potem wroc do false. Test, ktory zawsze
 *                        przechodzi, wyglada identycznie jak test poprawny.
 */
class McbDemoTopV1(
    wordCount       : Int     = 4096,
    retentionCycles : Int     = 5000000,
    injectFault     : Boolean = false,
    simulation      : Boolean = false
) extends Component {

  private val idxWidth = log2Up(wordCount)
  // (idx << 11) musi zmiescic sie w 26 bitach adresu ukladu 512 Mb
  require(idxWidth + 11 <= 26,
    s"wordCount = $wordCount wychodzi poza 64 MB przy kroku 2 kB")

  val io = new Bundle {
    val c3_sys_clk   = in Bool()   // 100 MHz
    val c3_sys_rst_n = in Bool()   // UWAGA: aktywny WYSOKI (C3_RST_ACT_LOW = 0)

    val mcb3_dram_dq    = inout(Analog(Bits(16 bits)))
    val mcb3_dram_a     = out Bits (13 bits)
    val mcb3_dram_ba    = out Bits (2 bits)
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

    val calib_done = out Bool()
    val test_done  = out Bool()
    val test_error = out Bool()
    val test_phase = out Bits (2 bits)  // 00 kalibracja, 01 zapis, 10 przerwa, 11 odczyt
  }
  noIoPrefix()

  val mcb = new s6_lpddr(simulation = simulation)

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
  mcb.io.c3_p0_cmd_clk := mcb.io.c3_clk0
  mcb.io.c3_p0_wr_clk  := mcb.io.c3_clk0
  mcb.io.c3_p0_rd_clk  := mcb.io.c3_clk0

  val uiCd = ClockDomain(
    clock  = mcb.io.c3_clk0,
    reset  = mcb.io.c3_rst0,
    config = ClockDomainConfig(
      clockEdge        = RISING,
      resetKind        = SYNC,
      resetActiveLevel = HIGH
    ),
    frequency = FixedFrequency(50 MHz)
  )

  // ------------------------------------------------------------------- test
  val test = new ClockingArea(uiCd) {
    val idx   = Reg(UInt(idxWidth bits)) init (0)
    val holdCnt = Reg(UInt(log2Up(retentionCycles + 1) bits)) init (0)
    val done  = Reg(Bool()) init (False)
    val error = Reg(Bool()) init (False)
    val phase = Reg(Bits(2 bits)) init (0)

    // wzorzec uzywajacy wszystkich 32 bitow: {i, ~i}
    def pattern(i: UInt): Bits = {
      val b = i.resize(16).asBits
      b ## ~b
    }

    // adres: czesc wysoka przeskakuje bank/wiersz, niska przeciaga kolumne
    def byteAddrOf(i: UInt): UInt = {
      val high = (i << 11).resize(30)
      val low  = (i.asBits.resize(9).asUInt << 2).resize(30)
      high | low
    }

    // wartosci domyslne
    mcb.io.c3_p0_cmd_en        := False
    mcb.io.c3_p0_cmd_instr     := MigInstr.WRITE
    mcb.io.c3_p0_cmd_bl        := B(0, 6 bits)     // BL = wartosc + 1 -> 1 slowo 32-bit
    mcb.io.c3_p0_cmd_byte_addr := byteAddrOf(idx).asBits
    mcb.io.c3_p0_wr_en         := False
    mcb.io.c3_p0_wr_mask       := B(0, 4 bits)     // 0 = zapisz wszystkie bajty
    mcb.io.c3_p0_wr_data       := pattern(idx)
    mcb.io.c3_p0_rd_en         := False

    val fsm = new StateMachine {
      val sCalib  = new State with EntryPoint
      val sWrData = new State
      val sWrCmd  = new State
      val sHold   = new State
      val sRdCmd  = new State
      val sRdData = new State
      val sDone   = new State

      // 1. czekaj na kalibracje
      sCalib.whenIsActive {
        phase := B"00"
        idx   := 0
        when(mcb.io.c3_calib_done) {
          goto(sWrData)
        }
      }

      // 2. najpierw dane do write FIFO...
      sWrData.whenIsActive {
        phase := B"01"
        when(!mcb.io.c3_p0_wr_full) {
          mcb.io.c3_p0_wr_en := True
          goto(sWrCmd)
        }
      }

      // 3. ...dopiero potem komenda WRITE
      sWrCmd.whenIsActive {
        when(!mcb.io.c3_p0_cmd_full) {
          mcb.io.c3_p0_cmd_en    := True
          mcb.io.c3_p0_cmd_instr := MigInstr.WRITE
          when(idx === wordCount - 1) {
            idx  := 0
            holdCnt := 0
            goto(sHold)
          } otherwise {
            idx := idx + 1
            goto(sWrData)
          }
        }
      }

      // 4. przerwa: jesli dane to przezyja, auto-refresh dziala
      sHold.whenIsActive {
        phase := B"10"
        holdCnt := holdCnt + 1
        when(holdCnt === retentionCycles) {
          goto(sRdCmd)
        }
      }

      // 5. komenda READ
      sRdCmd.whenIsActive {
        phase := B"11"
        when(!mcb.io.c3_p0_cmd_full) {
          mcb.io.c3_p0_cmd_en    := True
          mcb.io.c3_p0_cmd_instr := MigInstr.READ
          goto(sRdData)
        }
      }

      // 6. odbior i porownanie (read FIFO jest first-word-fall-through)
      sRdData.whenIsActive {
        when(!mcb.io.c3_p0_rd_empty) {
          mcb.io.c3_p0_rd_en := True
          val expected = if (injectFault) pattern(idx) ^ B(1, 32 bits)
                         else             pattern(idx)
          when(mcb.io.c3_p0_rd_data =/= expected) {
            error := True
          }
          when(idx === wordCount - 1) {
            goto(sDone)
          } otherwise {
            idx := idx + 1
            goto(sRdCmd)
          }
        }
      }

      sDone.whenIsActive {
        done := True
      }
    }

    // bledy zglaszane przez sam MCB tez traktujemy jako porazke testu
    when(mcb.io.c3_p0_wr_error || mcb.io.c3_p0_rd_error ||
         mcb.io.c3_p0_wr_underrun || mcb.io.c3_p0_rd_overflow) {
      error := True
    }
  }

  io.test_done  := test.done
  io.test_error := test.error
  io.test_phase := test.phase
}

object MigTopVerilog extends App {
  SpinalConfig(
    targetDirectory    = "hw/gen/verilog",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetKind        = SYNC,
      resetActiveLevel = HIGH
    )
  ).generateVerilog(
    new McbDemoTopV1(
      wordCount       = 4096,
      retentionCycles = 5000000,   // 100 ms @ 50 MHz
      injectFault     = false      // <-- ustaw true na jeden build, zeby sprawdzic komparator
    )
  )
}
