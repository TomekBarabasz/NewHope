package newhope.vertebra.hil.i2s

import spinal.core._
import spinal.lib._
import newhope.i2s.I2sFrame
import I2sPatternHw._

// =====================================================================
//  Generator wzorca -> io.tx DUT-a (contract/i2s/pattern.md).
//
//  Szerokosc slowa = szerokosc DUT-a (stala). Ramka n jest liczona
//  rejestrem po kazdej zmianie n, wiec po handshake'u valid spada na
//  jeden cykl - DUT bierze najwyzej jedna ramke na okres ramki, wiec to
//  nic nie kosztuje.
//
//  Luki (I2sGapModel): generator wstrzymuje valid na gap_len ramek,
//  liczac impulsy underrun DUT-a. Cisza na magistrali pochodzi wiec z
//  prawdziwego underrunu DUT-a, a nie z generatora wysylajacego zera.
// =====================================================================
case class I2sPatternGen(width : Int) extends Component {
  require(I2sPattern.validWidth(width), s"szerokosc generatora $width")

  val io = new Bundle {
    val tx       = master(Stream(I2sFrame(width)))
    val underrun = in  Bool()          // impuls z DUT-a: granica ramki bez danych
    val clear    = in  Bool()          // start biegu: n = 0, sent = 0, bez luki
    val enable   = in  Bool()
    val seed     = in  Bits(32 bits)
    val gapMode  = in  UInt(2 bits)
    val gapEvery = in  UInt(16 bits)
    val gapLen   = in  UInt(16 bits)
    val sent     = out UInt(32 bits)
  }

  val cfg = I2sPatternCfg()
  cfg.seed  := io.seed
  cfg.peerW := U(width, 6 bits)
  cfg.slot  := U(32, 6 bits)           // transfer(word, W, 32, W) == word

  val n     = Reg(UInt(32 bits)) init 0
  val sent  = Reg(UInt(32 bits)) init 0
  val frame = Reg(I2sFrame(width))
  val fresh = RegInit(False)           // frame == ramka n
  frame := expected(n, cfg, width)
  fresh := !io.tx.fire

  // --- luki -------------------------------------------------------------
  val inGap   = RegInit(False)
  val gapLeft = Reg(UInt(16 bits)) init 0
  val since   = Reg(UInt(16 bits)) init 0

  val gapHash  = xorshift32(n.asBits ^ io.seed ^ B(BigInt(I2sGapModel.GapSalt), 32 bits))
  val gapsOn   = io.gapEvery =/= 0 && io.gapLen =/= 0
  val periodic = io.gapMode === 1 && (since + 1) === io.gapEvery
  val random   = io.gapMode === 2 && (gapHash(15 downto 0).asUInt & (io.gapEvery - 1)) === 0
  val startGap = gapsOn && (periodic || random)

  when(io.tx.fire) {
    n     := n + 1
    sent  := sent + 1
    since := since + 1
    when(startGap) { inGap := True; gapLeft := io.gapLen; since := 0 }
  }
  when(inGap && io.underrun) {
    gapLeft := gapLeft - 1
    when(gapLeft === 1) { inGap := False }
  }

  when(io.clear) {
    n := 0; sent := 0; since := 0; inGap := False; fresh := False
  }

  io.tx.valid   := io.enable && fresh && !inGap && !io.clear
  io.tx.payload := frame
  io.sent        := sent
}
