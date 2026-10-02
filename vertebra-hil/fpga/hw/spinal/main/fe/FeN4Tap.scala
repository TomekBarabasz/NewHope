package newhope.vertebra.hil.fe

import spinal.core._
import spinal.lib._
import newhope.frontend.{BfpCplx, BfpPower, RfftGenerics}

// =====================================================================
//  Podsluch N2 - N4 w harnessie frontendu (contract/fe/commands.md,
//  "N2 - N4"; uklad rekordow: FeN4).
//
//  Dziala w domenie dut harnessu. Wejscia to transakcje (fire) trzech
//  strumieni DUT-a: wyjscie Framera (N2), Rfft (N3) i PowerSpectrum (N4).
//  Dla kazdego wezla CRC32 elementow ramki, liczba elementow i wykladnik;
//  N4 zamyka ramke i sklada rekord CRC (23 B) w kolejce bajtow. Ramki
//  nr dumpEvery, 2 dumpEvery, ... (gdy bufor wolny) trafiaja tez do bufora
//  i wychodza jako rekord zrzutu. Bajty wychodza strumieniem `aux` (sof ## bajt), jeden na ramke
//  wyniku na magistrali.
//
//  `dutRst` (reset DUT-a, takze z HilResetInjector) kasuje ramki w toku;
//  rekordy juz w kolejce zostaja. Rekord CRC jest wstawiany w calosci
//  albo wcale (`drops`), zrzut nie przerywa rekordu CRC i odwrotnie.
// =====================================================================
case class FeN4Tap(g : RfftGenerics) extends Component {
  val W    = g.framer.sampleWidth
  val E    = g.core.expWidth
  val n2N  = g.framer.fftSize / 2
  val bins = g.bins
  require(W <= 18, "element 36 b: re i im po 18 b")

  val io = new Bundle {
    val start     = in  Bool()                          // poczatek biegu: wszystko od zera
    val dutRst    = in  Bool()
    val n2        = slave(Flow(Fragment(BfpCplx(W, E))))
    val n3        = slave(Flow(Fragment(BfpCplx(W, E))))
    val n4        = slave(Flow(Fragment(BfpPower(2 * W, E + 1))))
    val trig      = in  UInt(24 bits)                   // ostatnia wyslana probka N1
    val dumpEvery = in  UInt(16 bits)
    val aux       = master(Stream(Bits(9 bits)))        // sof ## bajt
    val frames, crcRecs, dumps, drops, order = out UInt(32 bits)
  }

  def crcStep(crc : Bits, word : Bits) : Bits = {
    var c = crc
    for (i <- word.getWidth - 1 to 0 by -1) {
      val fb = c.msb ^ word(i)
      c = (c |<< 1) ^ Mux(fb, B(0x04C11DB7L, 32 bits), B(0, 32 bits))
    }
    c
  }
  def pair(re : SInt, im : SInt) : Bits = re.resize(18).asBits ## im.resize(18).asBits

  /** CRC jednego wezla: wynik w chwili `done` (ostatni element ramki). */
  case class Node(n : Int, valid : Bool, last : Bool, word : Bits, exp : SInt) extends Area {
    val crc   = Reg(Bits(32 bits)) init B(0xFFFFFFFFL, 32 bits)
    val count = Reg(UInt(log2Up(MaxN + 1) bits)) init 0
    val expR  = Reg(SInt(16 bits)) init 0
    val next  = crcStep(crc, word)
    val done  = valid && last
    val ok    = count === U(n - 1, count.getWidth bits)
    val fin   = Bits(32 bits)
    fin := next
    when(valid) {
      crc   := next
      count := count + 1
      when(count === 0) { expR := exp.resize(16) }
    }
    when(done || io.dutRst || io.start) { crc := B(0xFFFFFFFFL, 32 bits); count := 0 }
    val expOut = Mux(count === 0, exp.resize(16), expR)
  }
  private def MaxN = scala.math.max(n2N, bins) + 1

  val n2 = Node(n2N, io.n2.valid, io.n2.last, pair(io.n2.fragment.re, io.n2.fragment.im), io.n2.fragment.exp)
  val n3 = Node(bins, io.n3.valid, io.n3.last, pair(io.n3.fragment.re, io.n3.fragment.im), io.n3.fragment.exp)
  val n4 = Node(bins, io.n4.valid, io.n4.last, io.n4.fragment.p.asBits.resize(36), io.n4.fragment.exp)

  // trig: zatrzask przy pierwszym elemencie ramki N2
  val trigR = Reg(UInt(24 bits)) init 0
  when(io.n2.valid && n2.count === 0) { trigR := io.trig }

  case class R2() extends Bundle { val trig = UInt(24 bits); val crc = Bits(32 bits); val exp = SInt(16 bits); val ok = Bool() }
  case class R3() extends Bundle { val crc = Bits(32 bits); val exp = SInt(16 bits); val ok = Bool() }

  val q2 = StreamFifo(R2(), 2)
  val q3 = StreamFifo(R3(), 2)
  q2.io.flush := io.dutRst || io.start
  q3.io.flush := io.dutRst || io.start
  q2.io.push.valid := n2.done
  q2.io.push.payload.trig := Mux(n2.count === 0, io.trig, trigR)
  q2.io.push.payload.crc  := n2.fin
  q2.io.push.payload.exp  := n2.expOut
  q2.io.push.payload.ok   := n2.ok
  q3.io.push.valid := n3.done
  q3.io.push.payload.crc := n3.fin
  q3.io.push.payload.exp := n3.expOut
  q3.io.push.payload.ok  := n3.ok

  // Koniec ramki N4 obslugiwany kilka cykli pozniej, na zatrzasnietych
  // wartosciach: PowerSpectrum konczy ramke cykl po Rfft, a wpis N3 jest
  // widoczny na wyjsciu StreamFifo dopiero 1-2 cykle po zapisie (bez tego
  // pierwsza ramka nie miala pary, a kolejne rekordy laczyly N2/N3 ramki k
  // z N4 ramki k + 1).
  val n4Fin  = RegNextWhen(n4.fin, n4.done) init 0
  val n4Ok   = RegNextWhen(n4.ok, n4.done) init False
  val n4Exp  = RegNextWhen(n4.expOut, n4.done) init 0
  val n4End  = Delay(n4.done && !io.dutRst, 3, init = False)

  val both = q2.io.pop.valid && q3.io.pop.valid
  q2.io.pop.ready := n4End && both
  q3.io.pop.ready := n4End && both

  // --- rekord CRC -> kolejka bajtow ------------------------------------
  val bytes = StreamFifo(Bits(9 bits), 256)
  bytes.io.flush := io.start
  val rec    = Reg(Bits(FeN4.CrcLen * 8 bits)) init 0
  val recPos = Reg(UInt(5 bits)) init 0
  val recOn  = RegInit(False)

  def le(v : Bits, n : Int) : Seq[Bits] = (0 until n).map(i => v.resize(8 * n)(8 * i + 7 downto 8 * i))
  val r2 = q2.io.pop.payload; val r3 = q3.io.pop.payload
  val flags = B(0, 5 bits) ## n4Ok ## r3.ok ## r2.ok
  val recBytes : Seq[Bits] = Seq(B(FeN4.TypeCrc, 8 bits)) ++ le(r2.trig.asBits, 3) ++ Seq(flags) ++
    le(r2.crc, 4) ++ le(r2.exp.asBits, 2) ++ le(r3.crc, 4) ++ le(r3.exp.asBits, 2) ++ le(n4Fin, 4) ++ le(n4Exp.asBits, 2)
  require(recBytes.size == FeN4.CrcLen)

  val frames = Reg(UInt(32 bits)) init 0
  val crcRecs, dumps, drops, order = Reg(UInt(32 bits)) init 0
  when(n4End) {
    frames := frames + 1
    when(!both) { order := order + 1 }
      .elsewhen(recOn || bytes.io.availability < FeN4.CrcLen) { drops := drops + 1 }
      .otherwise {
        rec    := recBytes.reverse.reduce(_ ## _)       // bajt 0 w najmlodszych bitach
        recOn  := True
        recPos := 0
        crcRecs := crcRecs + 1
      }
  }
  bytes.io.push.valid   := recOn
  bytes.io.push.payload := (recPos === 0) ## rec.subdivideIn(8 bits)(recPos.resize(log2Up(FeN4.CrcLen)))
  when(recOn) {
    recPos := recPos + 1
    when(recPos === FeN4.CrcLen - 1) { recOn := False }
  }

  // --- zrzut N4 ----------------------------------------------------------
  // Decyzja przy pierwszym elemencie ramki, zapis do bufora w trakcie,
  // zatwierdzenie razem z rekordem CRC (n4End), gdy ramka kompletna.
  val mem      = Mem(Bits(36 bits), bins)
  val dumpCnt  = Reg(UInt(16 bits)) init 0        // ramek do nastepnego zrzutu
  val capture  = RegInit(False)                   // biezaca ramka N4 idzie do bufora
  val capDone  = RegInit(False)                   // ... i jest juz cala w buforze
  val dumpRdy  = RegInit(False)                   // bufor pelny, czeka na wyslanie
  val dumpSend = RegInit(False)
  val dumpTrig = Reg(UInt(24 bits)) init 0
  val dumpExp  = Reg(SInt(16 bits)) init 0
  val n4First  = io.n4.valid && n4.count === 0
  val capNow   = io.dumpEvery =/= 0 && dumpCnt === 0 && !dumpRdy && !dumpSend
  val capThis  = Mux(n4First, capNow, capture)
  when(n4First) { capture := capNow }
  mem.write(n4.count.resize(log2Up(bins)), io.n4.fragment.p.asBits.resize(36), io.n4.valid && capThis)
  when(n4.done) {
    dumpCnt := Mux(dumpCnt === 0, io.dumpEvery - 1, dumpCnt - 1)
    capDone := capThis
    capture := False
  }
  when(n4End) {
    when(capDone && n4Ok && both) {
      dumpRdy  := True
      dumpTrig := r2.trig
      dumpExp  := n4Exp
      dumps    := dumps + 1
    }
    capDone := False
  }
  when(io.dutRst) { capture := False; capDone := False }

  // --- wyjscie aux: rekord CRC w calosci, potem zrzut w calosci ----------
  val inCrc   = RegInit(False)
  val crcLeft = Reg(UInt(5 bits)) init 0
  val dPos    = Reg(UInt(log2Up(FeN4.dumpLen(bins) + 1) bits)) init 0
  val dBin    = Reg(UInt(log2Up(bins + 1) bits)) init 0
  val dByte   = Reg(UInt(3 bits)) init 0
  val rd      = mem.readSync(dBin.resize(log2Up(bins)))
  val dHead   = Seq(B(FeN4.TypeDump, 8 bits)) ++ le(dumpTrig.asBits, 3) ++ le(dumpExp.asBits, 2)
  val dData   = (B(0, 4 bits) ## rd).subdivideIn(8 bits)(dByte.resize(3))
  val dOut    = Mux(dPos < 6, Vec(dHead)(dPos.resize(3)), dData)

  val useCrc  = inCrc || (!dumpSend && bytes.io.pop.valid)
  io.aux.valid   := Mux(useCrc, bytes.io.pop.valid, dumpSend || dumpRdy)
  io.aux.payload := Mux(useCrc, bytes.io.pop.payload, (dPos === 0) ## dOut)
  bytes.io.pop.ready := useCrc && io.aux.ready

  when(useCrc && io.aux.fire) {
    when(!inCrc) { inCrc := True; crcLeft := FeN4.CrcLen - 2 }
      .elsewhen(crcLeft === 0) { inCrc := False }
      .otherwise { crcLeft := crcLeft - 1 }
  }
  when(!useCrc && io.aux.fire) {
    when(!dumpSend) { dumpSend := True; dumpRdy := False }
    dPos := dPos + 1
    when(dPos >= 6) {
      when(dByte === 4) { dByte := 0; dBin := dBin + 1 } otherwise { dByte := dByte + 1 }
    }
    when(dPos === FeN4.dumpLen(bins) - 1) { dumpSend := False; dPos := 0; dBin := 0; dByte := 0 }
  }

  when(io.start) {
    frames := 0; crcRecs := 0; dumps := 0; drops := 0; order := 0
    // pierwszy zrzut: ramka nr dumpEvery (ramka 0 to prawie same zera)
    recOn := False; dumpCnt := io.dumpEvery; capture := False; capDone := False; dumpRdy := False; dumpSend := False
    inCrc := False; dPos := 0; dBin := 0; dByte := 0
  }

  io.frames := frames; io.crcRecs := crcRecs; io.dumps := dumps; io.drops := drops; io.order := order
}
