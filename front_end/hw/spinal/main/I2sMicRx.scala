package newhope.frontend

import spinal.core._
import spinal.lib._

// =====================================================================
//  N0 · I2S RX (INMP441)                              tor probkowy
//
//  Liczy:  x[n] = slowo24[23:6], Q0.17
//  We/wy:  piny SCK, WS (wyjscia), SD (wejscie) -> Flow[SInt(18)]
//  Kiedy:  jedna probka na ramke I2S, czyli co bclkDiv * 64 cykli
//
//  Licznik divCnt dzieli zegar na takt SCK: zbocze narastajace przy
//  divCnt = sckLow - 1, opadajace przy divCnt = bclkDiv - 1. Licznik
//  bitCnt (0..63) rosnie na opadajacym zboczu, a WS to jego najstarszy
//  bit, wiec WS zmienia sie dokladnie z opadajacym SCK, jak wymaga I2S.
//
//  W slocie: narastajace zbocze z bitCnt mod 32 = 0 to bit opoznienia
//  I2S (smiec), zbocza 1..sampleWidth niosa bity 23..(24 - sampleWidth).
//  Po ostatnim potrzebnym bicie probka wychodzi o jeden cykl pozniej;
//  reszta slowa jest ignorowana.
//
//  Po resecie bitCnt = 63 (WS = 1), wiec pierwsze opadajace zbocze
//  otwiera lewy slot prawdziwa zmiana WS i pierwsza probka jest pelna.
//
//  Wyjscie to Flow: mikrofonu nie da sie wstrzymac. Wszystkie piny
//  wyjsciowe prosto z rejestrow.
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

  // ---- zegar bitowy ----------------------------------------------------
  val divCnt = Reg(UInt(log2Up(bclkDiv) bits)) init 0
  val rise   = divCnt === U(sckLow - 1)
  val fall   = divCnt === U(bclkDiv - 1)
  when(fall) { divCnt := 0 } otherwise { divCnt := divCnt + 1 }

  val bitCnt = Reg(UInt(log2Up(frameBits) bits)) init U(frameBits - 1, log2Up(frameBits) bits)
  val sck    = RegInit(False)
  when(rise) { sck := True }
  when(fall) { sck := False; bitCnt := bitCnt + 1 }

  io.sck := sck
  io.ws  := bitCnt.msb

  // ---- dane ------------------------------------------------------------
  // SD jest asynchroniczne wzgledem c3_clk0 (mikrofon zmienia je
  // z opoznieniem wzgledem naszego SCK): zwykly synchronizator.
  val sdSync = (0 until syncStages).foldLeft(io.sd) { (s, i) =>
    RegNext(s).init(False).setName(s"sdSync_$i")
  }

  val slotIdx = bitCnt(log2Up(slotBits) - 1 downto 0)
  val inSlot  = if (leftChannel) !bitCnt.msb else bitCnt.msb
  val take    = rise && inSlot && slotIdx >= U(1) && slotIdx <= U(sampleWidth)
  val last    = rise && inSlot && slotIdx === U(sampleWidth)

  val shift = Reg(Bits(sampleWidth bits)) init 0
  when(take) { shift := (shift ## sdSync).resize(sampleWidth) }

  io.output.valid   := RegNext(last) init False
  io.output.payload := shift.asSInt
}
