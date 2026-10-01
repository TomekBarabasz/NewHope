package newhope.frontend

import spinal.core._
import spinal.lib._

// =====================================================================
//  Rozplatanie widma sygnalu rzeczywistego spakowanego jako
//  z[m] = x[2m] + j x[2m+1], m = 0..M-1, N = 2M.
//
//    Zmk  = conj(Z[(M-k) mod M])
//    S    = Z[k] + Zmk,   T = -j (Z[k] - Zmk)
//    X[k] = (S + W_N^k T) / 2,           k = 0..M
//  (dla k = M uzywana jest para k = 0 i W_N^M = -1)
//
//  To jest jeden motylek z przesunieciem BFP `nextShift` wyliczonym
//  przez rdzen. Po nim skladowe S i T mieszcza sie w W bitach
//  (to samo kryterium L1 co w rdzeniu), wiec mnozenie jest 18x18,
//  a dzielenie przez 2 wchodzi w zaokraglenie iloczynu:
//    X = round((S * 2^(TW-1) + W * T) / 2^TW)
//  Wykladnik wyjscia = exp + nextShift.
//
//  Wyjscie: M + 1 prazkow w porzadku rosnacym, last na k = M.
//  Im X[0] i Im X[M] sa dokladnie zero (patrz unpack_dc_nyquist).
//  Przepustowosc: 1 prazek na cykl, trzy rejestry potoku.
// =====================================================================
class RealUnpack(val g: FftGenerics) extends Component {
  require(g.isLegal, g.problems.mkString("; "))
  val M  = g.n
  val W  = g.dataWidth
  val TW = g.twiddleWidth
  val E  = g.expWidth
  val SW = g.shiftWidth

  val io = new Bundle {
    val input  = slave(Stream(Fragment(BfpPair(W, E, SW))))
    val output = master(Stream(Fragment(BfpCplx(W, E))))
  }

  // W_N^k, k = 0..M; W_N^M = (-scale, 0)
  val rom = Mem(Bits(2 * TW bits),
                Bfp.twiddleTable(2 * M, M + 1, g.twiddleScale).map { case (c, s) =>
                  B(Bfp.packPair(c, s, TW), 2 * TW bits)
                })

  // ---- zrodlo: wejscie albo zapamietana para k = 0 dla prazka M ------
  case class Cmd() extends Bundle {
    val pair = BfpPair(W, E, SW)
    val k    = UInt(log2Up(M + 1) bits)
    val last = Bool()
  }
  val nyq   = RegInit(False)
  val saved = Reg(BfpPair(W, E, SW))
  val k     = Reg(UInt(log2Up(M + 1) bits)) init 0

  val cmd = Stream(Cmd())
  cmd.valid        := nyq || io.input.valid
  cmd.payload.pair := Mux(nyq, saved, io.input.fragment)
  cmd.payload.k    := k
  cmd.payload.last := nyq
  io.input.ready   := !nyq && cmd.ready

  when(cmd.fire) {
    k := k + 1
    when(!nyq && k === U(0)) { saved := io.input.fragment }
    when(!nyq && io.input.last) { nyq := True }
    when(nyq) { nyq := False; k := U(0) }
  }

  // ---- stopien 1: rejestr + odczyt twiddle (wyrownane) ----------------
  val st1 = cmd.m2sPipe()
  val w   = rom.readSync(cmd.payload.k, cmd.ready)

  case class St2() extends Bundle {
    val sr, si, tr, ti = SInt(W bits)
    val wr, wi         = SInt(TW bits)
    val exp            = SInt(E bits)
    val last           = Bool()
  }

  val st2 = st1.translateWith {
    val p = st1.payload.pair
    def sh(x: SInt) = BfpHw.shiftRound(x, p.nextShift, g.shiftMin, g.shiftMax, W).resize(W + 1)
    val zkR = sh(p.a.re); val zkI = sh(p.a.im)
    val zrR = sh(p.b.re); val zrI = sh(p.b.im)
    // Zmk = conj(zr): (zrR, -zrI)
    val sR = zkR + zrR
    val sI = zkI - zrI
    val dR = zkR - zrR
    val dI = zkI + zrI
    val o = St2()
    // miesci sie w W bitach z kryterium L1 (nextShift)
    o.sr   := sR.resize(W)
    o.si   := sI.resize(W)
    o.tr   := dI.resize(W)          // T = -j D = (D.im, -D.re)
    o.ti   := (-dR).resize(W)
    o.wr   := BfpHw.reOf(w, TW)
    o.wi   := -BfpHw.imOf(w, TW)    // W = cos - j sin
    o.exp  := (p.exp + p.nextShift.resize(E))
    o.last := st1.payload.last
    o
  }.m2sPipe()

  // ---- stopien 2: iloczyny w rejestrach (MREG DSP48A1) -----------------
  // Osobny rejestr po mnozeniu: mnozenie + suma trzech skladnikow +
  // zaokraglenie + nasycenie w jednym cyklu nie zamyka timingu przy
  // 75 MHz na Spartan-6. Wynik bit w bit bez zmian, latencja +1 cykl.
  case class St3() extends Bundle {
    val sr, si             = SInt(W bits)
    val pRR, pII, pRI, pIR = SInt(W + TW bits)
    val exp                = SInt(E bits)
    val last               = Bool()
  }
  val st3 = st2.translateWith {
    val q = st2.payload
    val o = St3()
    o.sr   := q.sr
    o.si   := q.si
    o.pRR  := q.tr * q.wr
    o.pII  := q.ti * q.wi
    o.pRI  := q.tr * q.wi
    o.pIR  := q.ti * q.wr
    o.exp  := q.exp
    o.last := q.last
    o
  }.m2sPipe()

  // ---- stopien 3: suma, zaokraglenie, nasycenie --------------------------
  val PW = W + TW + 1
  val out = st3.translateWith {
    val q  = st3.payload
    val yR = (q.sr << (TW - 1)).resize(PW) + q.pRR.resize(PW) - q.pII.resize(PW)
    val yI = (q.si << (TW - 1)).resize(PW) + q.pRI.resize(PW) + q.pIR.resize(PW)
    val o  = Fragment(BfpCplx(W, E))
    o.fragment.re  := BfpHw.satTo(BfpHw.roundShr(yR, TW), W)
    o.fragment.im  := BfpHw.satTo(BfpHw.roundShr(yI, TW), W)
    o.fragment.exp := q.exp
    o.last         := q.last
    o
  }.m2sPipe()

  io.output << out
}
