package newhope.vertebra.hil.fe

import spinal.core._
import spinal.lib._
import newhope.frontend.{FramerGenerics, FrontEndGenerics, I2sMicGenerics, RfftGenerics}
import newhope.vertebra.hil.{HilProtocol, HilRegMap}

// =====================================================================
//  Wariant bitstreamu, ramki na magistrali i rejestry harnessu frontendu
//  N0 + N1, opcjonalnie z N2 - N4 (Framer, Rfft, PowerSpectrum)
//  (contract/fe/commands.md, vertebra-hil.md §12, §13).
//
//  Czysta Scala (poza komponentem FeHilRegs): ten sam obiekt czyta
//  harness przy elaboracji i host przy dekodowaniu nagrania ESP32, wiec
//  rozjazd ukladu ramki to blad kompilacji, a nie wieczor z analizatorem.
// =====================================================================

/** Wariant: generyki DUT-a (MicFrontEnd, opcjonalnie Rfft + PowerSpectrum)
  * ustalone przy elaboracji. Zegar `dut` to zegar, dla ktorego liczony jest
  * dzielnik I2S (clockHz). `rfft` = None: tylko N0 + N1. */
case class FeHilVariant(name : String, fe : FrontEndGenerics, rfft : Option[RfftGenerics] = None) {
  def dutHz : Long = fe.i2s.clockHz
  def sampleWidth : Int = fe.dc.sampleWidth
  def hasN4 : Boolean = rfft.isDefined

  /** Rejestr variant (0x006): bclkDiv | guardBits << 8 | sampleWidth << 16 |
    * 0xFE << 24 (N0 + N1) albo 0xF4 << 24 (N0 - N4). */
  def code : Long =
    fe.i2s.bclkDiv.toLong | (fe.dc.guardBits.toLong << 8) | (fe.dc.sampleWidth.toLong << 16) |
    ((if (hasN4) 0xF4L else 0xFEL) << 24)

  def problems : Seq[String] = fe.problems ++ rfft.toSeq.flatMap(_.problems) ++ Seq(
    (fe.i2s.slotBits == FeFrame.SlotBits) -> s"slot I2S ${fe.i2s.slotBits}, ramka powrotna ma ${FeFrame.SlotBits}",
    (fe.dc.sampleWidth <= FeFrame.MaxSampleWidth) -> s"probka ${fe.dc.sampleWidth} b > ${FeFrame.MaxSampleWidth} (pola tagu)",
    // Nadajnik powrotny (I2sSlave) widzi wewnetrzny SCK przez synchronizator:
    // polokres SCK > txLatencyCycles (I2sSlaveGenerics.supportsSckHalf).
    (fe.i2s.sckLow > FeFrame.txSlave.txLatencyCycles) ->
      s"polokres SCK ${fe.i2s.sckLow} cykli <= opoznienie nadajnika powrotnego ${FeFrame.txSlave.txLatencyCycles}",
    fe.i2s.leftChannel -> "harness zaklada mikrofon w lewym kanale (L/R = GND)",
    rfft.forall(_.framer.sampleWidth == fe.dc.sampleWidth) -> "szerokosc probki N1 != wejscie Framera",
    rfft.forall(_.cyclesPerSample == fe.i2s.cyclesPerSample) ->
      s"RfftGenerics: ${rfft.map(_.cyclesPerSample)} cykli na probke, I2S daje ${fe.i2s.cyclesPerSample}",
    rfft.forall(_.bins <= FeN4.MaxBins) -> s"prazkow ${rfft.map(_.bins)} > ${FeN4.MaxBins} (bufor zrzutu)"
  ).collect { case (false, m) => m }
  def isLegal : Boolean = problems.isEmpty
}

object FeHilVariant {
  /** Rfft na zegarze i fs wariantu I2S: 512 / 160 jak w urzadzeniu. */
  def withN4(name : String, fe : FrontEndGenerics, framer : FramerGenerics = FramerGenerics()) : FeHilVariant =
    FeHilVariant(name, fe, Some(RfftGenerics(framer, fe.i2s.clockHz, (fe.i2s.clockHz / fe.i2s.cyclesPerSample).toInt)))

  /** Mimas V2: 75 MHz / 73 / 64 = 16 053 Hz, jak w urzadzeniu. */
  val mimas = FeHilVariant("mimas", FrontEndGenerics.mimas)
  /** To samo + Framer 512/160, FftCore 256, RealUnpack, PowerSpectrum (N2 - N4). */
  val mimasN4 = withN4("mimas_n4", FrontEndGenerics.mimas)
  /** Dolna granica dzielnika (8): tylko symulacja harnessu, 9x krotsza. */
  val d8 = FeHilVariant("d8", FrontEndGenerics(I2sMicGenerics(clockHz = 8L * 64 * 16000, bclkDiv = 8)))
  /** d8 z pelnym torem widma 512/160 (numeryka jak na plytce). */
  val d8N4 = withN4("d8_n4", d8.fe)

  /** Warianty z bitstreamem na plytke. */
  val all = Seq(mimas, mimasN4)
}

// =====================================================================
//  Ramki na magistrali (contract/fe/commands.md, "Ramki"), uklad v2.
//  Obie strony maja slot 32 i slowo 32 bity.
//
//  ESP32 -> FPGA (bodziec, ESP32 udaje INMP441):
//    L = slowo z bufora `load` (PC: mic24 << 8 | 8 bitow smieci)
//    R = ~L (mikrofon L/R = GND w prawym slocie milczy; tu smieci,
//            ktore DUT musi zignorowac)
//    N0 bierze L[31 : 32 - sampleWidth], czyli mic24[23 : 24 - sampleWidth].
//
//  FPGA -> ESP32 (wynik, jedna ramka na probke N1):
//    L = y << 14 | idx[7:0] << 6 | rst << 5 | bypass << 4 | overrun << 3 |
//        aux_valid << 2 | aux_sof << 1 | 0
//    R = x << 14 | aux_byte << 6 | 0x2D
//  y - wyjscie DcFilter, x - probka N0, z ktorej y policzono (echo),
//  idx - numer probki od startu mod 2^8, rst - pierwsza probka po resecie
//  DUT-a (takze po starcie), bypass - ta probka przeszla obok filtra,
//  overrun - lepki overrun filtra albo Framera. Kanal pomocniczy (aux):
//  strumien bajtow rekordow N2 - N4 (FeN4), bajt na ramke; sof na pierwszym
//  bajcie rekordu. Znacznik 0x2D jest na KONCU ramki (ostatnie bity prawego
//  slotu): reset DUT-a zatrzymuje SCK w polowie ramki, a ucieta ramka ma
//  zera zamiast reszty bitow, wiec traci znacznik i host ja pomija. Ramki
//  bez znacznika to tez cisza. Probka ma najwyzej 18 bitow; mlodsze bity
//  pola probki (sw < 18) sa zerami.
// =====================================================================
object FeFrame {
  val SlotBits       = 32
  val TagBits        = 14
  val MaxSampleWidth = SlotBits - TagBits
  val IdxBits        = 8
  val IdxMask        = (1 << IdxBits) - 1
  val IdxShift       = 6
  val Marker         = 0x2D
  val MarkerBits     = 6
  // bity lewego slotu
  val RstBit         = 5
  val BypassBit      = 4
  val OverrunBit     = 3
  val AuxValidBit    = 2
  val AuxSofBit      = 1
  // prawy slot: bajt aux nad znacznikiem
  val AuxShift       = 6

  /** Nadajnik powrotny: I2sSlave ze slowem 32 bity (newhope.i2s). */
  val txSlave = newhope.i2s.I2sSlaveGenerics(width = SlotBits)

  val U32 : Long = 0xFFFFFFFFL

  def signExtend(v : Long, w : Int) : Long = (v << (64 - w)) >> (64 - w)

  /** Probka N0 ze slowa lewego kanalu (obciecie, jak I2sGolden.sample). */
  def micSample(left : Long, sw : Int) : Long = signExtend((left & U32) >>> (SlotBits - sw), sw)

  /** Prawy kanal bodzca. */
  def stimRight(left : Long) : Long = ~left & U32

  /** Jedna ramka wyniku. `aux`: (sof, bajt), gdy aux_valid. */
  case class Out(idx : Int, x : Long, y : Long, rst : Boolean, bypass : Boolean, overrun : Boolean,
                 aux : Option[(Boolean, Int)] = None)

  def encode(o : Out, sw : Int) : (Long, Long) = {
    val m = (1L << sw) - 1
    val l = ((o.y & m) << (SlotBits - sw)) | ((o.idx & IdxMask).toLong << IdxShift) |
            (if (o.rst) 1L << RstBit else 0L) | (if (o.bypass) 1L << BypassBit else 0L) |
            (if (o.overrun) 1L << OverrunBit else 0L) |
            o.aux.fold(0L) { case (sof, _) => (1L << AuxValidBit) | (if (sof) 1L << AuxSofBit else 0L) }
    val r = ((o.x & m) << (SlotBits - sw)) | (o.aux.fold(0L)(_._2 & 0xFFL) << AuxShift) | Marker
    (l & U32, r & U32)
  }

  /** None: ramka nie niesie znacznika (cisza, ucieta ramka albo smieci z DMA). */
  def decode(l : Long, r : Long, sw : Int) : Option[Out] =
    if ((r & ((1 << MarkerBits) - 1)) != Marker) None
    else Some(Out(((l >> IdxShift) & IdxMask).toInt,
                  signExtend((r & U32) >>> (SlotBits - sw), sw),
                  signExtend((l & U32) >>> (SlotBits - sw), sw),
                  ((l >> RstBit) & 1) == 1, ((l >> BypassBit) & 1) == 1, ((l >> OverrunBit) & 1) == 1,
                  if (((l >> AuxValidBit) & 1) == 1) Some((((l >> AuxSofBit) & 1) == 1, ((r >> AuxShift) & 0xFF).toInt))
                  else None))
}

// =====================================================================
//  Rekordy N2 - N4 w kanale pomocniczym (contract/fe/commands.md, "N2 - N4").
//
//  Ramka widma to deterministyczna funkcja strumienia y (echo N1), wiec
//  host liczy golden sam (FftGolden.framer -> rfftFrame -> power), a FPGA
//  przesyla dowod zgodnosci: CRC32 kazdej ramki na trzech wezlach i co
//  `dump_every` ramek pelne widmo mocy.
//
//  CRC32: wielomian 0x04C11DB7, start 0xFFFFFFFF, bez odbicia i bez XOR
//  na koncu; kazdy element to slowo 36 bitow podawane od MSB:
//    N2 (wyjscie Framera, 256 par):     re[17:0] ## im[17:0]
//    N3 (Rfft, 257 prazkow):            re[17:0] ## im[17:0]
//    N4 (PowerSpectrum, 257 prazkow):   p[35:0]
//  Wykladnik bloku idzie osobno (pierwszy element ramki).
//
//  Rekord CRC (typ 0x01, 23 B):
//    typ, trig[7:0], trig[15:8], trig[23:16], flagi,
//    3 x (crc32 LE 4 B, exp LE 2 B)  dla N2, N3, N4
//  trig - numer probki N1 (licznik ramek wyniku od startu, mod 2^24), po
//  ktorej Framer wydal te ramke, czyli ostatnia probka okna.
//  flagi: bit k = wezel k (N2, N3, N4) mial dokladnie oczekiwana liczbe
//  elementow z `last` na ostatnim.
//
//  Rekord zrzutu (typ 0x02, 6 + 5 * bins B): typ, trig 3 B, exp N4 LE 2 B,
//  potem bins x p (36 b w 5 B LE, gorne 4 bity zero).
// =====================================================================
object FeN4 {
  val TypeCrc   = 0x01
  val TypeDump  = 0x02
  val CrcLen    = 23
  val ElemBits  = 36
  val Nodes     = Seq("N2", "N3", "N4")
  val MaxBins   = 1024
  def dumpLen(bins : Int) : Int = 6 + 5 * bins
  val TrigMask  = (1L << 24) - 1

  /** CRC32 slowa `bits` bitow od MSB (jak sprzet, FeHarness.crcStep). */
  def crcStep(crc : Long, word : Long, bits : Int = ElemBits) : Long = {
    var c = crc & FeFrame.U32
    for (i <- bits - 1 to 0 by -1) {
      val fb = ((c >>> 31) ^ (word >>> i)) & 1
      c = ((c << 1) & FeFrame.U32) ^ (if (fb == 1) 0x04C11DB7L else 0L)
    }
    c
  }
  def crc(words : Seq[Long], bits : Int = ElemBits) : Long = words.foldLeft(0xFFFFFFFFL)(crcStep(_, _, bits))

  def pair(re : Long, im : Long) : Long = ((re & 0x3FFFFL) << 18) | (im & 0x3FFFFL)

  sealed trait Rec { def trig : Long }
  case class CrcRec(trig : Long, flags : Int, crc : Vector[Long], exp : Vector[Int]) extends Rec
  case class DumpRec(trig : Long, exp : Int, p : Vector[Long]) extends Rec

  private def le(b : Seq[Int]) : Long = b.reverse.foldLeft(0L)((a, x) => (a << 8) | (x & 0xFF))
  private def s16(v : Long) : Int = FeFrame.signExtend(v, 16).toInt

  /** Bajty rekordu -> rekord; None, gdy typ albo dlugosc sie nie zgadza. */
  def parse(b : Seq[Int], bins : Int) : Option[Rec] = b.headOption match {
    case Some(TypeCrc) if b.size == CrcLen =>
      Some(CrcRec(le(b.slice(1, 4)), b(4),
        Vector.tabulate(3)(k => le(b.slice(5 + 6 * k, 9 + 6 * k))),
        Vector.tabulate(3)(k => s16(le(b.slice(9 + 6 * k, 11 + 6 * k))))))
    case Some(TypeDump) if b.size == dumpLen(bins) =>
      Some(DumpRec(le(b.slice(1, 4)), s16(le(b.slice(4, 6))),
        Vector.tabulate(bins)(k => le(b.slice(6 + 5 * k, 11 + 5 * k)))))
    case _ => None
  }

  def recLen(typ : Int, bins : Int) : Option[Int] =
    if (typ == TypeCrc) Some(CrcLen) else if (typ == TypeDump) Some(dumpLen(bins)) else None

  /** Bajty do wyslania (do testow i atrap). */
  def bytes(r : Rec) : Seq[Int] = {
    def le(v : Long, n : Int) = (0 until n).map(i => ((v >> (8 * i)) & 0xFF).toInt)
    r match {
      case CrcRec(t, f, c, e) => Seq(TypeCrc) ++ le(t, 3) ++ Seq(f & 0xFF) ++ (0 until 3).flatMap(k => le(c(k), 4) ++ le(e(k), 2))
      case DumpRec(t, e, p)   => Seq(TypeDump) ++ le(t, 3) ++ le(e, 2) ++ p.flatMap(le(_, 5))
    }
  }
}

/** Konfiguracja IP w bloku 0x100 (contract/fe/commands.md). */
case class FeHilCfg() extends Bundle {
  val bypassFrom = UInt(32 bits)   // bypass dla probek idx w [from, to)
  val bypassTo   = UInt(32 bits)
  val tail       = UInt(16 bits)   // probki DUT-a po stop: cisza, ktora oproznia DMA ESP32
  val dumpEvery  = UInt(16 bits)   // N4: zrzut co tyle ramek widma (0 = bez zrzutu)
}

object FeHilRegs {
  val BypassFrom = 0x100
  val BypassTo   = 0x101
  val Tail       = 0x102
  val DumpEvery  = 0x103
  val TailDefault = 512
  val DumpEveryDefault = 16

  /** Liczniki 0x010- w ukladzie FE (migawka przy stop, HilCounters). */
  val counters : Seq[String] = Seq(
    "sent",        // ramki oddane nadajnikowi powrotnemu (handshake)
    "frames",      // probki N1 w biegu
    "underrun",    // ramki ciszy nadajnika powrotnego w biegu (poczatek: kilka)
    "overflow",    // probka N1, gdy kolejka do nadajnika pelna (ma byc 0)
    "dc_overrun",  // lepki overrun filtra (ma byc 0)
    "x_sum",       // suma x (u32, ze znakiem rozszerzonym do 32 b) wyslanych ramek
    "y_sum",       // suma y jw.
    "rst_done",    // resety DUT-a z HilResetInjector
    // N2 - N4 (wariant z Rfft; inaczej zera)
    "n4_frames",   // ramki widma mocy zakonczone w biegu
    "n4_crc",      // rekordy CRC wstawione do kanalu pomocniczego
    "n4_dump",     // rekordy zrzutu
    "n4_aux_drop", // rekordy CRC odrzucone (pelna kolejka); ma byc 0
    "n4_order",    // N4 zakonczyl ramke bez ramki N2/N3 w kolejce; ma byc 0
    "fr_overrun")  // lepki overrun Framera; ma byc 0
  def counterAddr(n : String) : Int = {
    val i = counters.indexOf(n)
    require(i >= 0, s"nie ma licznika FE $n")
    HilProtocol.Counters.Base + i
  }
}

/** Rejestry 0x100-: zapis w stop, zatrzasniecie w domenie dut przy starcie
  * (jak I2sHilRegs). Po resecie bez bypassu, tail = 512, dump_every = 16. */
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
    c.dumpEvery  := DumpEveryDefault
    c
  }

  val sys = new ClockingArea(sysCd) {
    val cfg = Reg(FeHilCfg()) init(initCfg)
    cfg.flatten.foreach(_.addTag(crossClockDomain))
    val map = new HilRegMap(io.bus, lock = io.running)
    map.rw(BypassFrom, cfg.bypassFrom, locked = true)
    map.rw(BypassTo,   cfg.bypassTo,   locked = true)
    map.rw(Tail,       cfg.tail,       locked = true)
    map.rw(DumpEvery,  cfg.dumpEvery,  locked = true)
    map.build()
  }

  val dut = new ClockingArea(dutCd) {
    val latched = Reg(FeHilCfg()) init(initCfg)
    when(io.dutStart) { latched := sys.cfg }
    io.dutCfg := latched
  }
}
