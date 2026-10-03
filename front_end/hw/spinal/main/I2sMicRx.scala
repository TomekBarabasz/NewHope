package newhope.frontend

import spinal.core._
import spinal.lib._
import newhope.i2s.I2sMaster

// =====================================================================
//  N0 · I2S RX (INMP441)                              tor probkowy
//
//  Liczy:  x[n] = slowo24[23:6], Q0.17
//  We/wy:  piny SCK, WS (wyjscia), SD (wejscie) -> Flow[SInt(18)]
//  Kiedy:  jedna probka na ramke I2S, co 2 * slotBits * sckDiv cykli
//
//  Cienka obudowa newhope.com.i2s.I2sMaster:
//   - master ma width = sampleWidth, wiec z kazdego slotu bierze
//     sampleWidth najstarszych pozycji = gorne bity slowa INMP441,
//   - io.rx strzela raz na ramke, na poczatku NASTEPNEJ ramki (po prawym
//     slocie); nigdy dla ramki czesciowej, wiec pierwsza probka po
//     resecie jest pelna,
//   - z ramki bierzemy kanal mikrofonu (lewy dla L/R = GND),
//   - TX nieuzywany: tx.valid = 0, SDO i underrun niepodlaczone.
//
//  SDI bez synchronizatora - decyzja I2sMastera (dane synchroniczne do
//  SCK, ktory sami generujemy; na FPGA rejestr w IOB).
// =====================================================================
class I2sMicRx(val g: I2sMicGenerics) extends Component {
  require(g.isLegal, g.problems.mkString("; "))
  import g._

  val io = new Bundle {
    val sck    = out Bool()
    val ws     = out Bool()
    val sd     = in Bool()
    val output = master(Flow(SInt(sampleWidth bits)))
  }

  val bus = I2sMaster(g.i2s)

  bus.io.tx.valid   := False
  bus.io.tx.payload := bus.io.tx.payload.getZero

  io.sck           := bus.io.pins.sck
  io.ws            := bus.io.pins.ws
  bus.io.pins.sdi  := io.sd

  val word = if (leftChannel) bus.io.rx.payload.left else bus.io.rx.payload.right
  io.output.valid   := bus.io.rx.valid
  io.output.payload := word.asSInt
}
