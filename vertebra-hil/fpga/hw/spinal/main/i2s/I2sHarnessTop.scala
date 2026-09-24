package newhope.vertebra.hil.i2s

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import newhope.vertebra.hil._

// =====================================================================
//  I2sHarness na Mimas V2 (krok 2e). Jeden top na wariant:
//  definicja I2sHarnessTop_<wariant>, plik hw/gen/I2sHarnessTop_<wariant>.v.
//
//  Zegary:
//    sys - 100 MHz z oscylatora, BOOT + reset po wlaczeniu (16 cykli),
//          zeby rejestry z resetem asynchronicznym dostaly wartosci init
//    dut - DCM_CLKGEN (M/D najblizsze I2sHilVariant.dutHz) -> BUFG;
//          reset do czasu LOCKED, zwalniany synchronicznie
//  Piny I2S (header P7, vertebra-hil.md §9): SCK i WS trojstanowe
//  (wyjscia tylko w roli master), SD_OUT zawsze wyjscie, SD_IN wejscie,
//  TRIG - impuls przy pierwszym bledzie checkera dla analizatora.
// =====================================================================
case class I2sHarnessTop(v : I2sHilVariant,
                         bg : HilBridgeGenerics = HilBridgeGenerics(),
                         build : Long = HilBuildInfo.gitHash) extends Component {
  setDefinitionName(s"I2sHarnessTop_${v.name}")

  val (dcmM, dcmD, dutHzReal) = DcmClkGen.best(bg.clkHz, v.dutHz)

  val io = new Bundle {
    val clk       = in  Bool()
    val uart      = master(Uart())
    val led       = out Bits(8 bits)
    val i2s_sck   = inout(Analog(Bool()))
    val i2s_ws    = inout(Analog(Bool()))
    val i2s_sd_out = out Bool()      // FPGA -> ESP32 DIN   (SD_F2E w §9)
    val i2s_sd_in  = in  Bool()      // ESP32 DOUT -> FPGA  (SD_E2F w §9)
    val i2s_trig   = out Bool()      // do analizatora stanow
  }
  noIoPrefix()

  // --- sys: BOOT + reset po wlaczeniu ------------------------------------
  val bootCd = ClockDomain(io.clk, config = ClockDomainConfig(resetKind = BOOT))
  val por = new ClockingArea(bootCd) {
    val cnt = Reg(UInt(4 bits)) init 0
    when(cnt =/= 15) { cnt := cnt + 1 }
    val rst = RegNext(cnt =/= 15) init True
  }

  // --- dut: DCM_CLKGEN -> BUFG ---------------------------------------------
  val dcm = DcmClkGen(dcmM, dcmD, 1e9 / bg.clkHz)
  dcm.io.CLKIN     := io.clk
  dcm.io.RST       := False
  dcm.io.FREEZEDCM := False
  dcm.io.PROGCLK   := False
  dcm.io.PROGDATA  := False
  dcm.io.PROGEN    := False

  val bufg = Bufg()
  bufg.io.I := dcm.io.CLKFX
  // KEEP: XST zachowuje siec pod ta nazwa, bo i2s_harness.ucf przypina do
  // niej grupe czasowa TN_dut (TIG miedzy domenami). Bez tego siec zlewa
  // sie z wyjsciem BUFG i ngdbuild jej nie znajduje.
  val dut_clk = Bool()
  dut_clk.addAttribute("KEEP", "TRUE")
  dut_clk := bufg.io.O

  val dutBoot = ClockDomain(dut_clk, config = ClockDomainConfig(resetKind = BOOT))
  val dutRst = new ClockingArea(dutBoot) {
    val locked = BufferCC(dcm.io.LOCKED, False)       // LOCKED jest asynchroniczny
    val rst    = RegNext(!locked) init True
  }

  // --- harness -------------------------------------------------------------
  val h = I2sHarness(v, bg, build)
  h.io.sysClk := io.clk
  h.io.sysRst := por.rst
  h.io.dutClk := dut_clk
  h.io.dutRst := dutRst.rst
  io.uart <> h.io.uart
  io.led  := h.io.led

  when(h.io.i2s.clkOe) {
    io.i2s_sck := h.io.i2s.sckOut
    io.i2s_ws  := h.io.i2s.wsOut
  }
  h.io.i2s.sckIn := io.i2s_sck
  h.io.i2s.wsIn  := io.i2s_ws
  h.io.i2s.sdIn  := io.i2s_sd_in
  io.i2s_sd_out  := h.io.i2s.sdOut
  io.i2s_trig    := h.io.trig
}

/** sbt "hilFpga/runMain newhope.vertebra.hil.i2s.I2sHarnessTopVerilog [wariant...]"
  * Bez argumentow: wszystkie warianty. */
object I2sHarnessTopVerilog {
  def main(args : Array[String]) : Unit = {
    val chosen = if (args.isEmpty) I2sHilVariant.all
                 else args.toSeq.map(n => I2sHilVariant.all.find(_.name == n)
                        .getOrElse(sys.error(s"nie ma wariantu $n; sa: ${I2sHilVariant.all.map(_.name).mkString(", ")}")))
    for (v <- chosen) {
      val r = Config.spinal.generateVerilog(I2sHarnessTop(v))
      val t = r.toplevel
      println(f"${v.name}: dut nominalnie ${v.dutHz / 1e6}%.4f MHz, DCM ${t.dcmM}/${t.dcmD} = " +
              f"${t.dutHzReal / 1e6}%.4f MHz (${(t.dutHzReal - v.dutHz) / v.dutHz * 100}%+.4f %%), " +
              f"build ${HilBuildInfo.gitHash}%08x")
    }
  }
}
