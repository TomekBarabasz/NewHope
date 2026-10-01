package newhope.frontend

import spinal.core._
import spinal.lib._

case class FftOutWord(w: Int) extends Bundle {
  val d    = Bits(2 * w bits)
  val last = Bool()
}

// =====================================================================
//  FFT zespolone, radix-2 DIF, in-place, jeden motylek, II = 2.
//
//  Wejscie: n probek zespolonych w porzadku naturalnym (Fragment.last
//  jest ignorowany - rdzen liczy do n sam), wykladnik i `inverse`
//  brane z pierwszej probki bloku.
//  Wyjscie: kolejnosc wg g.outOrder, wspolny wykladnik w kazdym
//  elemencie. inverse = sprzezony twiddle, bez skalowania 1/n
//  (skala siedzi w wykladniku jak kazda inna).
//
//  Pamiec: jeden BRAM w trybie SDP (1 zapis + 1 odczyt na cykl), stad
//  II = 2: motylek czyta A i B w dwoch kolejnych cyklach i zapisuje je
//  w dwoch kolejnych cyklach.
//
//  Potok jednego motylka (t0 = wydanie odczytu A):
//    t0  odczyt A, odczyt twiddle
//    t1  odczyt B; A z pamieci -> rejestr
//    t2  B z pamieci; przesuniecie BFP, suma i roznica -> rejestr
//    t3  cztery iloczyny (DSP48A1) -> rejestr; suma nasycona -> rejestr
//    t4  zapis A (suma); zlozenie iloczynow, zaokraglenie, nasycenie
//    t5  zapis B
//  Zapisy A zawsze w cyklach parzystych wzgledem poczatku stopnia,
//  zapisy B w nieparzystych - port zapisu nie koliduje.
//
//  Miedzy stopniami potok jest oprozniany (DRAIN): in-place oznacza,
//  ze pierwszy motylek stopnia s+1 czyta adresy, ktore ostatni motylek
//  stopnia s jeszcze zapisuje. Koszt 6 cykli na stopien, bez znaczenia.
//
//  BFP: maksimum L1 zbierane w locie przy zapisach, decyzja o
//  przesunieciu nastepnego stopnia zapada w PREP. Zadnego osobnego
//  przebiegu skanujacego.
// =====================================================================
class FftCore(val g: FftGenerics) extends Component {
  require(g.isLegal, g.problems.mkString("; "))
  import g._
  val W  = dataWidth
  val TW = twiddleWidth
  val E  = expWidth

  val io = new Bundle {
    val inverse = in Bool()
    val input   = slave(Stream(Fragment(BfpCplx(W, E))))
    val output  = (!g.pairs) generate master(Stream(Fragment(BfpCplx(W, E))))
    val pairs   = g.pairs generate master(Stream(Fragment(BfpPair(W, E, shiftWidth))))
    val busy    = out Bool()
  }

  // ---- pamieci -------------------------------------------------------
  val mem = Mem(Bits(2 * W bits), n)
  val tw  = Mem(Bits(2 * TW bits),
                Bfp.twiddleTable(n, n / 2, twiddleScale).map { case (c, s) =>
                  B(Bfp.packPair(c, s, TW), 2 * TW bits)
                })

  object Phase extends SpinalEnum { val LOAD, PREP, RUN, DRAIN, OUT = newElement() }
  val phase = RegInit(Phase.LOAD)

  val loadCnt = Reg(UInt(logN bits)) init 0
  val stage   = Reg(UInt(log2Up(logN) bits)) init 0
  val bfly    = Reg(UInt(logN - 1 bits)) init 0
  val sub     = Reg(Bool()) init False
  val l1max   = Reg(UInt(l1Width bits)) init 0
  val l1acc   = Reg(UInt(l1Width bits)) init 0
  val shift   = Reg(SInt(shiftWidth bits)) init 0
  val expo    = Reg(SInt(E bits)) init 0
  val inv     = Reg(Bool()) init False

  io.busy := phase =/= Phase.LOAD || loadCnt =/= 0

  // ---- LOAD ----------------------------------------------------------
  io.input.ready := phase === Phase.LOAD
  val inL1 = BfpHw.l1(io.input.fragment.re, io.input.fragment.im)
  when(io.input.fire) {
    loadCnt := loadCnt + 1
    when(loadCnt === U(0)) {
      expo  := io.input.fragment.exp
      inv   := io.inverse
      l1max := inL1
    } otherwise {
      l1max := BfpHw.umax(l1max, inL1)
    }
    when(loadCnt.andR) { phase := Phase.PREP; stage := U(0) }
  }

  // ---- PREP: decyzja BFP dla stopnia ---------------------------------
  val prepShift = BfpHw.shiftFor(l1max, W, shiftWidth)
  when(phase === Phase.PREP) {
    shift := prepShift
    expo  := expo + prepShift.resize(E)
    l1acc := U(0)
    bfly  := U(0)
    sub   := False
    phase := Phase.RUN
  }

  // ---- RUN: generacja adresow ----------------------------------------
  // Motylek i w stopniu s: wstaw zero na pozycje p = logN-1-s.
  //   addrA = (i >> p) << (p+1) | (i & (2^p - 1)),  addrB = addrA | 2^p
  //   twiddle k = (i & (2^p - 1)) << s = (i << s) mod 2^(logN-1)
  val addrA, addrB = UInt(logN bits)
  addrA := U(0)
  addrB := U(0)
  for (s <- 0 until logN) {
    val p  = logN - 1 - s
    val hi = if (p >= logN - 1) U(0, logN bits) else ((bfly >> p) << (p + 1)).resize(logN)
    val lo = (bfly & U((1 << p) - 1, logN - 1 bits)).resize(logN)
    when(stage === U(s)) {
      addrA := hi | lo
      addrB := hi | lo | U(1 << p, logN bits)
    }
  }
  val twIdx = (bfly << stage).resize(logN - 1)

  val running = phase === Phase.RUN
  val issueA  = running && !sub
  val issueB  = running && sub
  when(running) {
    sub := !sub
    when(sub) {
      bfly := bfly + 1
      when(bfly.andR) { phase := Phase.DRAIN }
    }
  }

  // ---- OUT: generacja adresow odczytu wyniku -------------------------
  val totalReads = outputCycles
  val outIssued  = Reg(UInt(log2Up(totalReads + 1) bits)) init 0
  val fifo       = StreamFifo(FftOutWord(W), 4)
  val outIssue   = phase === Phase.OUT && outIssued < U(totalReads) && fifo.io.availability >= U(2)
  val outK       = if (g.pairs) (outIssued >> 1).resize(logN) else outIssued.resize(logN)
  val outSecond  = if (g.pairs) outIssued(0) else False
  val outKK      = Mux(outSecond, U(0, logN bits) - outK, outK)       // (n - k) mod n
  val outAddr    = if (outOrder == OutOrder.Bitrev) outKK else outKK.reversed
  when(outIssue) { outIssued := outIssued + 1 }

  // ---- jedyny port odczytu -------------------------------------------
  val rdAddr = Mux(running, Mux(sub, addrB, addrA), outAddr)
  val rdData = mem.readSync(rdAddr, running || outIssue)
  val twData = tw.readSync(twIdx, issueA)

  // ---- potok motylka ---------------------------------------------------
  val v1 = RegNext(issueA) init False
  val v2 = RegNext(v1) init False
  val v3 = RegNext(v2) init False
  val v4 = RegNext(v3) init False
  val v5 = RegNext(v4) init False
  val addrA4 = Delay(addrA, 4)
  val addrB5 = Delay(addrB, 5)

  // t1
  val aData = RegNextWhen(rdData, v1)
  val twReg = RegNextWhen(twData, v1)

  // t2
  def sr(x: SInt) = BfpHw.shiftRound(x, shift, shiftMin, shiftMax, W)
  val ar = sr(BfpHw.reOf(aData, W));  val ai = sr(BfpHw.imOf(aData, W))
  val br = sr(BfpHw.reOf(rdData, W)); val bi = sr(BfpHw.imOf(rdData, W))
  val tc = BfpHw.reOf(twReg, TW)
  val ts = BfpHw.imOf(twReg, TW)
  val s2SumR = RegNext(ar.resize(W + 1) + br.resize(W + 1))
  val s2SumI = RegNext(ai.resize(W + 1) + bi.resize(W + 1))
  // roznica miesci sie w W bitach z kryterium L1 - obciecie jest bezpieczne
  val s2DifR = RegNext((ar.resize(W + 1) - br.resize(W + 1)).resize(W))
  val s2DifI = RegNext((ai.resize(W + 1) - bi.resize(W + 1)).resize(W))
  val s2Wr   = RegNext(tc)
  val s2Wi   = RegNext(Mux(inv, ts, -ts))          // W = cos - j sin (w przod)

  // t3
  val mRR = RegNext(s2DifR * s2Wr)
  val mII = RegNext(s2DifI * s2Wi)
  val mRI = RegNext(s2DifR * s2Wi)
  val mIR = RegNext(s2DifI * s2Wr)
  val sumR3 = RegNext(BfpHw.satTo(s2SumR, W))
  val sumI3 = RegNext(BfpHw.satTo(s2SumI, W))

  // t4
  val PW = W + TW + 1
  val yR = mRR.resize(PW) - mII.resize(PW)
  val yI = mRI.resize(PW) + mIR.resize(PW)
  val bR5 = RegNext(BfpHw.satTo(BfpHw.roundShr(yR, TW - 1), W))
  val bI5 = RegNext(BfpHw.satTo(BfpHw.roundShr(yI, TW - 1), W))

  // ---- jedyny port zapisu ----------------------------------------------
  val wrEn   = Bool()
  val wrAddr = UInt(logN bits)
  val wrData = Bits(2 * W bits)
  wrEn   := io.input.fire
  wrAddr := loadCnt
  wrData := BfpHw.packC(io.input.fragment.re, io.input.fragment.im)
  when(v4) { wrEn := True; wrAddr := addrA4; wrData := BfpHw.packC(sumR3, sumI3) }
  when(v5) { wrEn := True; wrAddr := addrB5; wrData := BfpHw.packC(bR5, bI5) }
  mem.write(wrAddr, wrData, wrEn)

  when(v4) { l1acc := BfpHw.umax(l1acc, BfpHw.l1(sumR3, sumI3)) }
  when(v5) { l1acc := BfpHw.umax(l1acc, BfpHw.l1(bR5, bI5)) }

  // ---- DRAIN -----------------------------------------------------------
  val pipeEmpty = !(v1 || v2 || v3 || v4 || v5)
  when(phase === Phase.DRAIN && pipeEmpty) {
    l1max := l1acc
    when(stage === U(logN - 1)) {
      phase := Phase.OUT
    } otherwise {
      stage := stage + 1
      phase := Phase.PREP
    }
  }

  // ---- OUT: FIFO i wyjscie -------------------------------------------
  val outInflight = RegNext(outIssue) init False
  val inflightLast = RegNext(outIssue && outIssued === U(totalReads - 1)) init False
  fifo.io.push.valid   := outInflight
  fifo.io.push.payload.d    := rdData
  fifo.io.push.payload.last := inflightLast

  val outDone = Bool()
  if (!g.pairs) {
    io.output.valid          := fifo.io.pop.valid
    io.output.fragment.re    := BfpHw.reOf(fifo.io.pop.d, W)
    io.output.fragment.im    := BfpHw.imOf(fifo.io.pop.d, W)
    io.output.fragment.exp   := expo
    io.output.last           := fifo.io.pop.last
    fifo.io.pop.ready        := io.output.ready
    outDone := io.output.fire && io.output.last
  } else {
    val first = Reg(Bits(2 * W bits))
    val have  = RegInit(False)
    io.pairs.valid                  := fifo.io.pop.valid && have
    io.pairs.fragment.a.re          := BfpHw.reOf(first, W)
    io.pairs.fragment.a.im          := BfpHw.imOf(first, W)
    io.pairs.fragment.b.re          := BfpHw.reOf(fifo.io.pop.d, W)
    io.pairs.fragment.b.im          := BfpHw.imOf(fifo.io.pop.d, W)
    io.pairs.fragment.exp           := expo
    io.pairs.fragment.nextShift     := BfpHw.shiftFor(l1max, W, shiftWidth)
    io.pairs.last                   := fifo.io.pop.last
    fifo.io.pop.ready := !have || io.pairs.ready
    when(fifo.io.pop.fire) {
      have := !have
      when(!have) { first := fifo.io.pop.d }
    }
    outDone := io.pairs.fire && io.pairs.last
  }

  when(outDone) {
    phase     := Phase.LOAD
    outIssued := U(0)
  }
}
