package newhope.frontend

import spinal.core._

// ---------------------------------------------------------------------
//  Typy na portach. Prawdziwa wartosc = mantysa * 2^exp, mantysy to
//  liczby calkowite w jednostkach LSB wejscia (probka Q0.17 = int18).
//  Wykladnik jest wspolny dla calego bloku, ale jedzie z kazdym
//  elementem - odbiorca nie musi pamietac naglowka.
// ---------------------------------------------------------------------

case class Cplx(w: Int) extends Bundle {
  val re, im = SInt(w bits)
}

case class BfpCplx(w: Int, e: Int) extends Bundle {
  val re, im = SInt(w bits)
  val exp    = SInt(e bits)
}

/** a = Z[k], b = Z[(n-k) mod n] (jeszcze bez sprzezenia).
  * nextShift = Bfp.shiftFor(L1 max bloku) - przesuniecie, ktore
  * nastepna operacja typu motylek powinna zastosowac. */
case class BfpPair(w: Int, e: Int, sw: Int) extends Bundle {
  val a, b      = Cplx(w)
  val exp       = SInt(e bits)
  val nextShift = SInt(sw bits)
}

/** p = re^2 + im^2, prawdziwa moc = p * 2^exp (exp = 2 * exp widma). */
case class BfpPower(pw: Int, e: Int) extends Bundle {
  val p   = UInt(pw bits)
  val exp = SInt(e bits)
}

case class BfpMag(mw: Int, e: Int) extends Bundle {
  val m   = UInt(mw bits)
  val exp = SInt(e bits)
}

object BfpHw {
  def l1(re: SInt, im: SInt): UInt = {
    val w = re.getWidth + 1
    re.abs.resize(w) + im.abs.resize(w)
  }

  def umax(a: UInt, b: UInt): UInt = Mux(a > b, a, b)

  /** Sprzetowe Bfp.shiftFor. */
  def shiftFor(l1: UInt, w: Int, sw: Int): SInt = {
    val lw  = l1.getWidth
    val msb = UInt(log2Up(lw) bits)
    msb := U(0)
    for (i <- 0 until lw) when(l1(i)) { msb := U(i) }   // ostatnie wygrywa = najstarsza jedynka

    def lit(v: BigInt) = U(v, lw bits)
    val r = SInt(sw bits)
    when(l1 === U(0)) {
      r := S(0)
    } elsewhen (l1 < lit(BigInt(1) << (w - 2))) {
      r := (msb.resize(sw + 1).asSInt - S(w - 3, sw + 1 bits)).resize(sw)
    } elsewhen (l1 < lit((BigInt(1) << (w - 1)) - 2)) {
      r := S(1)
    } elsewhen (l1 < lit((BigInt(1) << w) - 4)) {
      r := S(2)
    } otherwise {
      r := S(3)
    }
    r
  }

  /** x przesuniete o stala v z zaokragleniem polowy w gore. */
  def constShiftRound(x: SInt, v: Int, outW: Int): SInt = {
    val xw = x.getWidth
    if (v < 0) (x << -v).resize(outW)
    else if (v == 0) x.resize(outW)
    else ((x.resize(xw + 1) + S(BigInt(1) << (v - 1), xw + 1 bits)) >> v).resize(outW)
  }

  /** Sprzetowe Bfp.shiftRound dla s w [minS, maxS] - multiplekser
    * przesuniec stalych. Wynik musi miescic sie w outW (gwarantuje to
    * kryterium shiftFor; tu nie ma nasycenia). */
  def shiftRound(x: SInt, s: SInt, minS: Int, maxS: Int, outW: Int): SInt = {
    val r  = SInt(outW bits)
    val sw = s.getWidth
    r := x.resize(outW)
    switch(s.asBits) {
      for (v <- minS to maxS if v != 0)
        is(B(BigInt(v) & ((BigInt(1) << sw) - 1), sw bits)) { r := constShiftRound(x, v, outW) }
    }
    r
  }

  /** (x + 2^(n-1)) >> n */
  def roundShr(x: SInt, n: Int): SInt = {
    val xw = x.getWidth
    (x.resize(xw + 1) + S(BigInt(1) << (n - 1), xw + 1 bits)) >> n
  }

  /** Nasycenie do w bitow (kontrakt: nasycenie, nie zawijanie). */
  def satTo(x: SInt, w: Int): SInt = {
    if (x.getWidth <= w) x.resize(w)
    else {
      val hi = S((BigInt(1) << (w - 1)) - 1, x.getWidth bits)
      val lo = S(-(BigInt(1) << (w - 1)), x.getWidth bits)
      val r  = SInt(w bits)
      when(x > hi) { r := hi.resize(w) } elsewhen (x < lo) { r := lo.resize(w) } otherwise { r := x.resize(w) }
      r
    }
  }

  def packC(re: SInt, im: SInt): Bits = im.asBits ## re.asBits
  def reOf(b: Bits, w: Int): SInt    = b(w - 1 downto 0).asSInt
  def imOf(b: Bits, w: Int): SInt    = b(2 * w - 1 downto w).asSInt
}
