package newhope.vertebra.hil.fe

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import newhope.vertebra.hil._

// =====================================================================
//  FeHarness na Mimas V2. Jeden top na wariant: definicja
//  FeHarnessTop_<wariant>, plik hw/gen/FeHarnessTop_<wariant>.v,
//  ograniczenia hw/fe_harness.ucf (te same piny co I2S, zegar dut 75 MHz).
//
//  Zegary jak w I2sHarnessTop:
//    sys - 100 MHz z oscylatora, BOOT + reset po wlaczeniu
//    dut - DCM_CLKGEN -> BUFG; dla mimas 100 * 3 / 4 = 75 MHz dokladnie,
//          czyli zegar c3_clk0 urzadzenia (D-006)
//  Piny: SCK i WS trojstanowe (wyjscia od pierwszego startu), SD_OUT
//  wyjscie, SD_IN wejscie, TRIG = DUT w resecie z HilResetInjector.
// =====================================================================
case class FeHarnessTop(v : FeHilVariant,
                        bg : HilBridgeGenerics = HilBridgeGenerics(),
                        build : Long = HilBuildInfo.gitHashFe) extends Component {
  setDefinitionName(s"FeHarnessTop_${v.name}")

  val (dcmM, dcmD, dutHzReal) = DcmClkGen.best(bg.clkHz, v.dutHz)

  val io = new Bundle {
    val clk        = in  Bool()
    val uart       = master(Uart())
    val led        = out Bits(8 bits)
    val i2s_sck    = inout(Analog(Bool()))
    val i2s_ws     = inout(Analog(Bool()))
    val i2s_sd_out = out Bool()      // FPGA -> ESP32 DIN   (SD_F2E)
    val i2s_sd_in  = in  Bool()      // ESP32 DOUT -> FPGA  (SD_E2F)
    val i2s_trig   = out Bool()
  }
  noIoPrefix()

  val bootCd = ClockDomain(io.clk, config = ClockDomainConfig(resetKind = BOOT))
  val por = new ClockingArea(bootCd) {
    val cnt = Reg(UInt(4 bits)) init 0
    when(cnt =/= 15) { cnt := cnt + 1 }
    val rst = RegNext(cnt =/= 15) init True
  }

  val dcm = DcmClkGen(dcmM, dcmD, 1e9 / bg.clkHz)
  dcm.io.CLKIN     := io.clk
  dcm.io.RST       := False
  dcm.io.FREEZEDCM := False
  dcm.io.PROGCLK   := False
  dcm.io.PROGDATA  := False
  dcm.io.PROGEN    := False

  val bufg = Bufg()
  bufg.io.I := dcm.io.CLKFX
  // KEEP: fe_harness.ucf przypina do tej sieci grupe TN_dut (jak I2S).
  val dut_clk = Bool()
  dut_clk.addAttribute("KEEP", "TRUE")
  dut_clk := bufg.io.O

  val dutBoot = ClockDomain(dut_clk, config = ClockDomainConfig(resetKind = BOOT))
  val dutRst = new ClockingArea(dutBoot) {
    val locked = BufferCC(dcm.io.LOCKED, False)
    val rst    = RegNext(!locked) init True
  }

  val h = FeHarness(v, bg, build)
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
  h.io.i2s.sdIn := io.i2s_sd_in
  io.i2s_sd_out := h.io.i2s.sdOut
  io.i2s_trig   := h.io.trig
}

/** sbt "hilFpga/runMain newhope.vertebra.hil.fe.FeHarnessTopVerilog [wariant...]"
  * Bez argumentow: wszystkie warianty z FeHilVariant.all. */
object FeHarnessTopVerilog {
  def main(args : Array[String]) : Unit = {
    val chosen = if (args.isEmpty) FeHilVariant.all
                 else args.toSeq.map(n => FeHilVariant.all.find(_.name == n)
                        .getOrElse(sys.error(s"nie ma wariantu $n; sa: ${FeHilVariant.all.map(_.name).mkString(", ")}")))
    for (v <- chosen) {
      val r = Config.spinal.generateVerilog(FeHarnessTop(v))
      val t = r.toplevel
      println(f"${v.name}: dut nominalnie ${v.dutHz / 1e6}%.4f MHz, DCM ${t.dcmM}/${t.dcmD} = " +
              f"${t.dutHzReal / 1e6}%.4f MHz (${(t.dutHzReal - v.dutHz) / v.dutHz * 100}%+.4f %%), " +
              f"fs ${v.fe.i2s.fs}%.2f Hz, build ${HilBuildInfo.gitHashFe}%08x")
    }
  }
}
