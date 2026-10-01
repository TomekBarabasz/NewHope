package newhope.frontend

import spinal.core._
import spinal.lib._

// =====================================================================
//  N0 -> N1: piny INMP441 na wejsciu, Flow[SInt(18)] na wyjsciu.
//
//  Wyjscie ma dokladnie typ wejscia Framera / Rfft z newhope.fft:
//
//    val fe   = new MicFrontEnd(FrontEndGenerics.mimas)
//    val rfft = new Rfft(FftGenerics.mimas512)
//    rfft.io.input << fe.io.output
//
//  Ten sam blok karmi petle przelotowa z M4 (I2S RX -> HPF -> TX/PWM).
// =====================================================================
class MicFrontEnd(val g: FrontEndGenerics) extends Component {
  require(g.isLegal, g.problems.mkString("; "))

  val io = new Bundle {
    val sck       = out Bool()
    val ws        = out Bool()
    val sd        = in Bool()
    val bypassDc  = in Bool()
    val output    = master(Flow(SInt(g.dc.sampleWidth bits)))
    val dcOverrun = out Bool()
  }

  val rx = new I2sMicRx(g.i2s)
  val dc = new DcFilter(g.dc)

  io.sck   := rx.io.sck
  io.ws    := rx.io.ws
  rx.io.sd := io.sd

  dc.io.input << rx.io.output
  dc.io.bypass := io.bypassDc
  io.output << dc.io.output
  io.dcOverrun := dc.io.overrun
}
