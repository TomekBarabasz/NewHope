package newhope.vertebra.hil

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._

// =====================================================================
//  Etap 2a na plytce: sam rdzen harnessu (most + rejestry), bez IP.
//  Sluzy do sprawdzenia mostu na sprzecie, zanim dojdzie I2S: odczyt
//  magic/ip_id/build i zapis/odczyt scratch z hosta.
//
//  Porty jak w HilEchoTop (clk, uart_rxd, uart_txd, led[7:0]), wiec
//  pasuje ten sam hw/ise/hil_echo.ucf.
//
//  Diody:
//    led[7]   heartbeat
//    led[6]   blad ramki UART (sticky)
//    led[5]   bajt zgubiony w trakcie odpowiedzi (sticky)
//    led[4]   porzucona niedokonczona ramka (sticky)
//    led[3]   running (ctrl start/stop)
//    led[2:0] mlodsze bity scratch (widac zapis z hosta)
// =====================================================================
case class HilCoreTop(g : HilBridgeGenerics = HilBridgeGenerics(),
                      build : Long = HilBuildInfo.gitHash) extends Component {
  val io = new Bundle {
    val clk  = in  Bool()
    val uart = master(Uart())
    val led  = out Bits(8 bits)
  }
  noIoPrefix()

  val sysCd = ClockDomain(
    clock     = io.clk,
    frequency = FixedFrequency(HertzNumber(BigDecimal(g.clkHz))),
    config    = ClockDomainConfig(resetKind = BOOT))

  val sys = new ClockingArea(sysCd) {
    val core = HilCore(g, HilProtocol.ascii4("CORE"), build, variant = 0)
    io.uart <> core.io.uart
    HilRegBus.tieOff(core.io.ext)
    core.io.locked := False
    core.io.error  := False
    core.io.snapshot := False

    val heartbeat = Reg(UInt(27 bits)) init 0
    heartbeat := heartbeat + 1

    io.led := heartbeat.msb ## core.io.rxError ## core.io.dropped ## core.io.timeout ##
              core.io.running ## core.io.scratch(2 downto 0)
  }
}

/** sbt "hilFpga/runMain newhope.vertebra.hil.HilCoreTopVerilog" */
object HilCoreTopVerilog {
  def main(args : Array[String]) : Unit = {
    val g = HilBridgeGenerics()
    Config.spinal.generateVerilog(HilCoreTop(g))
    println(f"HilCoreTop: baud ${g.baud} (rzeczywisty ${g.actualBaud}%.0f, ${g.baudErrorPct}%+.2f %%), " +
            f"build ${HilBuildInfo.gitHash}%08x")
  }
}
