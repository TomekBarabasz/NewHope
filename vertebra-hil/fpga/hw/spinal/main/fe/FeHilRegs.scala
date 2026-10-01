package newhope.vertebra.hil.fe

import spinal.core._
import spinal.lib._
import newhope.frontend.{FrontEndGenerics, I2sMicGenerics}
import newhope.vertebra.hil.{HilProtocol, HilRegMap}

// =====================================================================
//  Wariant bitstreamu, ramki na magistrali i rejestry harnessu frontendu
//  N0 + N1 (contract/fe/commands.md, vertebra-hil.md §12).
//
//  Czysta Scala (poza komponentem FeHilRegs): ten sam obiekt czyta
//  harness przy elaboracji i host przy dekodowaniu nagrania ESP32, wiec
//  rozjazd ukladu ramki to blad kompilacji, a nie wieczor z analizatorem.
// =====================================================================

/** Wariant: generyki DUT-a (MicFrontEnd) ustalone przy elaboracji. Zegar
  * `dut` to zegar, dla ktorego liczony jest dzielnik I2S (clockHz). */
case class FeHilVariant(name : String, fe : FrontEndGenerics) {
  def dutHz : Long = fe.i2s.clockHz
  def sampleWidth : Int = fe.dc.sampleWidth

  /** Rejestr variant (0x006): bclkDiv | guardBits << 8 | sampleWidth << 16 | 0xFE << 24. */
  def code : Long =
    fe.i2s.bclkDiv.toLong | (fe.dc.guardBits.toLong << 8) | (fe.dc.sampleWidth.toLong << 16) | (0xFEL << 24)

  def problems : Seq[String] = fe.problems ++ Seq(
    (fe.i2s.slotBits == FeFrame.SlotBits) -> s"slot I2S ${fe.i2s.slotBits}, ramka powrotna ma ${FeFrame.SlotBits}",
    (fe.dc.sampleWidth <= FeFrame.MaxSampleWidth) -> s"probka ${fe.dc.sampleWidth} b > ${FeFrame.MaxSampleWidth} (pola tagu)",
    // Nadajnik powrotny (I2sSlave) widzi wewnetrzny SCK przez synchronizator:
    // polokres SCK > txLatencyCycles (I2sSlaveGenerics.supportsSckHalf).
    (fe.i2s.sckLow > FeFrame.txSlave.txLatencyCycles) ->
      s"polokres SCK ${fe.i2s.sckLow} cykli <= opoznienie nadajnika powrotnego ${FeFrame.txSlave.txLatencyCycles}",
    fe.i2s.leftChannel -> "harness zaklada mikrofon w lewym kanale (L/R = GND)"
  ).collect { case (false, m) => m }
  def isLegal : Boolean = problems.isEmpty
}

object FeHilVariant {
  /** Mimas V2: 75 MHz / 73 / 64 = 16 053 Hz, jak w urzadzeniu. */
  val mimas = FeHilVariant("mimas", FrontEndGenerics.mimas)
  /** Dolna granica dzielnika (8): tylko symulacja harnessu, 9x krotsza. */
  val d8 = FeHilVariant("d8", FrontEndGenerics(I2sMicGenerics(clockHz = 8L * 64 * 16000, bclkDiv = 8)))

  /** Warianty z bitstreamem na plytke. */
  val all = Seq(mimas)
}

// =====================================================================
//  Ramki na magistrali (contract/fe/commands.md, "Ramki"). Obie strony
//  maja slot 32 i slowo 32 bity.
//
//  ESP32 -> FPGA (bodziec, ESP32 udaje INMP441):
//    L = slowo z bufora `load` (PC: mic24 << 8 | 8 bitow smieci)
//    R = ~L (mikrofon L/R = GND w prawym slocie milczy; tu smieci,
//            ktore DUT musi zignorowac)
//    N0 bierze L[31 : 32 - sampleWidth], czyli mic24[23 : 24 - sampleWidth].
//
//  FPGA -> ESP32 (wynik, jedna ramka na probke N1):
//    L = y << (32 - sw)  | idx[13:0]
//    R = x << (32 - sw)  | rst << 13 | bypass << 12 | overrun << 11 | 0x5A5
//  y - wyjscie DcFilter, x - probka N0, z ktorej y policzono (echo),
//  idx - numer probki od startu mod 2^14, rst - pierwsza probka po
//  resecie DUT-a (takze po starcie), bypass - ta probka przeszla obok
//  filtra, overrun - lepki overrun filtra. Ramki bez znacznika 0x5A5 to
//  cisza (przed startem, po stop, luki) i host je pomija.
// =====================================================================
object FeFrame {
  val SlotBits       = 32
  val TagBits        = 14
  val MaxSampleWidth = SlotBits - TagBits
  val IdxMask        = (1 << TagBits) - 1
  val Marker         = 0x5A5
  val MarkerBits     = 11
  val RstBit         = 13
  val BypassBit      = 12
  val OverrunBit     = 11

  /** Nadajnik powrotny: I2sSlave ze slowem 32 bity (newhope.i2s). */
  val txSlave = newhope.i2s.I2sSlaveGenerics(width = SlotBits)

  val U32 : Long = 0xFFFFFFFFL

  def signExtend(v : Long, w : Int) : Long = (v << (64 - w)) >> (64 - w)

  /** Probka N0 ze slowa lewego kanalu (obciecie, jak I2sGolden.sample). */
  def micSample(left : Long, sw : Int) : Long = signExtend((left & U32) >>> (SlotBits - sw), sw)

  /** Prawy kanal bodzca. */
  def stimRight(left : Long) : Long = ~left & U32

  /** Jedna ramka wyniku. */
  case class Out(idx : Int, x : Long, y : Long, rst : Boolean, bypass : Boolean, overrun : Boolean)

  def encode(o : Out, sw : Int) : (Long, Long) = {
    val m = (1L << sw) - 1
    val l = ((o.y & m) << (SlotBits - sw)) | (o.idx & IdxMask)
    val r = ((o.x & m) << (SlotBits - sw)) | (if (o.rst) 1L << RstBit else 0L) |
            (if (o.bypass) 1L << BypassBit else 0L) | (if (o.overrun) 1L << OverrunBit else 0L) | Marker
    (l & U32, r & U32)
  }

  /** None: ramka nie niesie znacznika (cisza albo smieci z poczatku DMA). */
  def decode(l : Long, r : Long, sw : Int) : Option[Out] =
    if ((r & ((1 << MarkerBits) - 1)) != Marker) None
    else Some(Out((l & IdxMask).toInt,
                  signExtend((r & U32) >>> (SlotBits - sw), sw),
                  signExtend((l & U32) >>> (SlotBits - sw), sw),
                  ((r >> RstBit) & 1) == 1, ((r >> BypassBit) & 1) == 1, ((r >> OverrunBit) & 1) == 1))
}

/** Konfiguracja IP w bloku 0x100 (contract/fe/commands.md). */
case class FeHilCfg() extends Bundle {
  val bypassFrom = UInt(32 bits)   // bypass dla probek idx w [from, to)
  val bypassTo   = UInt(32 bits)
  val tail       = UInt(16 bits)   // probki DUT-a po stop: cisza, ktora oproznia DMA ESP32
}

object FeHilRegs {
  val BypassFrom = 0x100
  val BypassTo   = 0x101
  val Tail       = 0x102
  val TailDefault = 512

  /** Liczniki 0x010- w ukladzie FE (migawka przy stop, HilCounters). */
  val counters : Seq[String] = Seq(
    "sent",        // ramki oddane nadajnikowi powrotnemu (handshake)
    "frames",      // probki N1 w biegu
    "underrun",    // ramki ciszy nadajnika powrotnego w biegu (poczatek: kilka)
    "overflow",    // probka N1, gdy kolejka do nadajnika pelna (ma byc 0)
    "dc_overrun",  // lepki overrun filtra (ma byc 0)
    "x_sum",       // suma x (u32, ze znakiem rozszerzonym do 32 b) wyslanych ramek
    "y_sum",       // suma y jw.
    "rst_done")    // resety DUT-a z HilResetInjector
  def counterAddr(n : String) : Int = {
    val i = counters.indexOf(n)
    require(i >= 0, s"nie ma licznika FE $n")
    HilProtocol.Counters.Base + i
  }
}

/** Rejestry 0x100-: zapis w stop, zatrzasniecie w domenie dut przy starcie
  * (jak I2sHilRegs). Po resecie bez bypassu, tail = 512. */
case class FeHilRegs(sysCd : ClockDomain, dutCd : ClockDomain) extends Component {
  import FeHilRegs._

  val io = new Bundle {
    val bus      = slave(newhope.vertebra.hil.HilRegBus())
    val running  = in  Bool()
    val dutStart = in  Bool()
    val dutCfg   = out(FeHilCfg())
  }

  private def initCfg : FeHilCfg = {
    val c = FeHilCfg()
    c.bypassFrom := 0
    c.bypassTo   := 0
    c.tail       := TailDefault
    c
  }

  val sys = new ClockingArea(sysCd) {
    val cfg = Reg(FeHilCfg()) init(initCfg)
    cfg.flatten.foreach(_.addTag(crossClockDomain))
    val map = new HilRegMap(io.bus, lock = io.running)
    map.rw(BypassFrom, cfg.bypassFrom, locked = true)
    map.rw(BypassTo,   cfg.bypassTo,   locked = true)
    map.rw(Tail,       cfg.tail,       locked = true)
    map.build()
  }

  val dut = new ClockingArea(dutCd) {
    val latched = Reg(FeHilCfg()) init(initCfg)
    when(io.dutStart) { latched := sys.cfg }
    io.dutCfg := latched
  }
}
