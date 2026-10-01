package newhope.frontend

import spinal.core._
import spinal.lib._

// =====================================================================
//  N1 · Filtr DC                                      tor probkowy
//
//  Liczy:  y[n] = x[n] - x[n-1] + a * y[n-1], a = 1 - 2*pi*30/fs
//  We/wy:  Flow[SInt(18)] Q0.17 -> Flow[SInt(18)] Q0.17
//          stan Y w accWidth = 18 + 3 + G bitach (G = 8: 29 b)
//  Kiedy:  co probke; zero mnoznikow, a * Y = Y - sum(+-(Y >> s))
//
//  Wejscie i wyjscie to Flow, jak wejscie Framera: wystarczy
//  `framer.io.input << dc.io.output` (albo `rfft.io.input << ...`).
//
//  Potok (probka na wejsciu w cyklu 0):
//    A  (1)  D = x - x1                           rejestr aD
//    B  (2)  P = (D << G) + Y1,  R_i = roundShr(Y1, s_i)
//    C  (3)  Y1 := P - sum(sign_i * R_i)
//    wyj(4)  y = sat(roundShr(Y1, G)), valid
//  Rekurencja przechodzi przez B i C, wiec kolejna probka moze przyjsc
//  najwczesniej 2 cykle po poprzedniej (minSpacing). Z I2S przychodzi
//  co 4 672 cykle. Rozbicie na dwa takty trzyma w kazdym najwyzej dwa
//  sumatory 29-bitowe, z zapasem na 75 MHz.
//
//  `overrun` zapala sie na stale (do resetu), gdy probki przyjda
//  szybciej niz co minSpacing cykli - wtedy wynik jest bledny, jak
//  overrun we Framerze.
//
//  `bypass` (wariant "dc_filter = false" z architektury) przepuszcza
//  wejscie z ta sama latencja. Filtr liczy dalej, wiec po wylaczeniu
//  bypass nie ma stanu przejsciowego.
// =====================================================================
class DcFilter(val g: DcGenerics) extends Component {
  require(g.isLegal, g.problems.mkString("; "))
  val SW = g.sampleWidth
  val G  = g.guardBits
  val AW = g.accWidth

  val io = new Bundle {
    val input   = slave(Flow(SInt(SW bits)))
    val output  = master(Flow(SInt(SW bits)))
    val bypass  = in Bool()
    val overrun = out Bool()
  }

  // ---- A: roznica ------------------------------------------------------
  val x1 = Reg(SInt(SW bits)) init 0
  val aV = RegNext(io.input.valid) init False
  val aX = RegNextWhen(io.input.payload, io.input.valid) init S(0, SW bits)
  val aD = RegNextWhen(io.input.payload.resize(SW + 1) - x1.resize(SW + 1), io.input.valid) init S(0, SW + 1 bits)
  when(io.input.valid) { x1 := io.input.payload }

  // ---- B: suma czesciowa i skladniki sprzezenia ------------------------
  val y1 = Reg(SInt(AW bits)) init 0
  val bV = RegNext(aV) init False
  val bX = RegNextWhen(aX, aV) init S(0, SW bits)
  val bP = RegNextWhen((aD << G).resize(AW) + y1, aV) init S(0, AW bits)
  val bR = g.terms.map(t => RegNextWhen(DcHw.roundShr(y1, t.shift).resize(AW), aV) init S(0, AW bits))

  // ---- C: domkniecie rekurencji ----------------------------------------
  val cV = RegNext(bV) init False
  val cX = RegNextWhen(bX, bV) init S(0, SW bits)
  val yNext = g.terms.zip(bR).foldLeft(bP) { case (s, (t, r)) =>
    if (t.sign > 0) s - r else s + r
  }
  when(bV) { y1 := yNext }

  // ---- wyjscie ---------------------------------------------------------
  io.output.valid   := RegNext(cV) init False
  io.output.payload := RegNextWhen(Mux(io.bypass, cX, DcHw.roundSat(y1, G, SW)), cV) init S(0, SW bits)

  val overrun = RegInit(False) setWhen (io.input.valid && aV)
  io.overrun := overrun
}

/** Sprzetowe odpowiedniki DcGolden.roundShr / sat. */
object DcHw {
  /** floor((v + 2^(s-1)) / 2^s): zaokraglenie polowy w gore, szerokosc w - s. */
  def roundShr(v: SInt, s: Int): SInt =
    if (s == 0) v else (v + S(BigInt(1) << (s - 1), v.getWidth bits)) >> s

  /** roundShr i nasycenie do w bitow. */
  def roundSat(v: SInt, s: Int, w: Int): SInt = {
    val r  = roundShr(v, s)
    val rw = r.getWidth
    val hi = S((BigInt(1) << (w - 1)) - 1, rw bits)
    val lo = S(-(BigInt(1) << (w - 1)), rw bits)
    Mux(r > hi, hi, Mux(r < lo, lo, r)).resize(w)
  }
}
