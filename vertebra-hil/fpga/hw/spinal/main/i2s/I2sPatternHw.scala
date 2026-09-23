package newhope.vertebra.hil.i2s

import spinal.core._
import newhope.i2s.I2sFrame

// =====================================================================
//  Wzorzec i transfer w sprzecie (contract/i2s/pattern.md).
//
//  To samo co I2sPattern / I2sFormat.transfer, ale z szerokoscia
//  nadawcy i dlugoscia slotu jako SYGNALAMI: checker w harnessie nie zna
//  ich przy elaboracji (ESP32 nadaje 8..32 bity w slocie do 32). Stala
//  jest tylko szerokosc odbiornika wrx = szerokosc DUT-a.
//
//  Sposob liczenia: slowo nadawcy wyrownane do bitu 31 ("strumien" na
//  magistrali, pozycja 0 slotu = bit 31), maska min(slot, peerW) pozycji,
//  a na koniec stale przesuniecie do wrx bitow. Zadnego DSP, same
//  multipleksy i XOR-y.
// =====================================================================
case class I2sPatternCfg() extends Bundle {
  val seed  = Bits(32 bits)
  val peerW = UInt(6 bits)     // szerokosc slowa nadawcy, 8..32
  val slot  = UInt(6 bits)     // dlugosc slotu na magistrali, 1..32
}

object I2sPatternHw {
  private def ones32 = B(BigInt(0xFFFFFFFFL), 32 bits)   // def: literal musi powstac w komponencie

  /** xorshift32 (13, 17, 5): jedna implementacja w HilHw. */
  def xorshift32(x0 : Bits) : Bits = newhope.vertebra.hil.HilHw.xorshift32(x0)

  /** S = min(8, peerW/2 - 1), 4 bity. */
  def seqBits(peerW : UInt) : UInt = {
    val half = (peerW >> 1).resize(5 bits) - 1
    Mux(half > 8, U(8, 4 bits), half.resize(4 bits))
  }

  /** 8 bitow, jedynki na S najmlodszych pozycjach. */
  def seqMask(s : UInt) : Bits = ~(B(0xFF, 8 bits) |<< s)

  /** Slowo nadawcy dla numeru k i kanalu c, wyrownane do bitu 31. */
  def aligned(k : UInt, c : Boolean, cfg : I2sPatternCfg) : Bits = {
    val s        = seqBits(cfg.peerW)
    val seq      = k.asBits.resize(8 bits) & seqMask(s)
    val key      = (seq.resize(31 bits) ## Bool(c)) ^ cfg.seed
    val h        = xorshift32(key)
    val hBits    = cfg.peerW - 1 - s.resize(6 bits)                       // H
    val hashPart = (h & ~(ones32 |<< hBits)) |<< (U(32, 7 bits) - cfg.peerW.resize(7 bits))
    val seqPart  = seq.resize(32 bits) |<< (U(31, 5 bits) - s.resize(5 bits))
    val cPart    = if (c) B(BigInt(1) << 31, 32 bits) else B(0, 32 bits)
    cPart | seqPart | hashPart
  }

  /** transfer(word, peerW, slot, wrx) na slowie wyrownanym do bitu 31. */
  def transfer(al : Bits, cfg : I2sPatternCfg, wrx : Int) : Bits = {
    require(wrx >= 1 && wrx <= 32, s"wrx=$wrx")
    val keepN = Mux(cfg.slot < cfg.peerW, cfg.slot, cfg.peerW)
    val kept  = al & ~(ones32 |>> keepN)
    kept(31 downto 32 - wrx)
  }

  /** Para oczekiwanych slow ramki k po stronie odbiornika wrx. */
  def expected(k : UInt, cfg : I2sPatternCfg, wrx : Int) : I2sFrame = {
    val f = I2sFrame(wrx)
    f.left  := transfer(aligned(k, c = false, cfg), cfg, wrx)
    f.right := transfer(aligned(k, c = true,  cfg), cfg, wrx)
    f
  }

  /** Pole seq ze slowa odebranego (8 bitow, S najmlodszych waznych). */
  def seqOf(v : Bits, s : UInt) : Bits = {
    val wrx = v.getWidth
    ((v |>> (U(wrx - 1, 6 bits) - s.resize(6 bits))).resize(8 bits)) & seqMask(s)
  }
}

// =====================================================================
//  Luki generatora (rejestry gap_* w contract/commands.md).
//  Czysta Scala: model dla testow i hosta, ten sam co I2sPatternGen.
//
//  Luka = gap_len ramek, w ktorych generator nie daje danych, wiec DUT
//  wysyla cisze (underrun). Decyzja zapada przy handshake'u ramki n:
//    mode 1: po kazdych gap_every ramkach od poprzedniej luki (od startu)
//    mode 2: gdy (xorshift32(n ^ seed ^ GapSalt) mod 2^16) & (gap_every-1) == 0;
//            gap_every ma byc potega dwojki (srednio 1 luka na gap_every ramek)
//  gap_every == 0 albo gap_len == 0: bez luk.
// =====================================================================
object I2sGapModel {
  val GapSalt : Long = 0x9E3779B9L

  def gapAfter(mode : Int, every : Int, since : Int, n : Long, seed : Long) : Boolean =
    every != 0 && (mode match {
      case 1 => since == every
      case 2 => ((I2sPattern.xorshift32((n ^ seed ^ GapSalt) & I2sPattern.U32) & 0xFFFFL) & (every - 1)) == 0
      case _ => false
    })

  /** Zdarzenia na kolejnych granicach ramki: Some(n) = ramka n, None = luka. */
  def events(mode : Int, every : Int, len : Int, seed : Long, boundaries : Int) : Seq[Option[Long]] = {
    val out   = scala.collection.mutable.ArrayBuffer[Option[Long]]()
    var n     = 0L
    var since = 0
    var left  = 0
    for (_ <- 0 until boundaries) {
      if (left > 0) { out += None; left -= 1 }
      else {
        out += Some(n)
        since += 1
        if (len != 0 && gapAfter(mode, every, since, n, seed)) { left = len; since = 0 }
        n = (n + 1) & I2sPattern.U32
      }
    }
    out.toSeq
  }
}
