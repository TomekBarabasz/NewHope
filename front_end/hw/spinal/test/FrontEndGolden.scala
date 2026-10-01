package newhope.frontend

// =====================================================================
//  Golden model N0 i N1, bit-exact z RTL. Kazda operacja ma odpowiednik
//  w DcFilter / I2sMicRx z ta sama kolejnoscia zaokraglen i nasycen.
//  Asercje w srodku lapia bledy PARAMETROW (za waski akumulator), nie RTL.
// =====================================================================
object I2sGolden {
  /** Slowo 24-bitowe ze znakiem -> probka: gorne sampleWidth bitow (obciecie). */
  def sample(g: I2sMicGenerics, word: Long): Long = word >> g.dropBits

  /** Bit k (1 = MSB) slowa w kolejnosci nadawania. */
  def bit(g: I2sMicGenerics, word: Long, k: Int): Boolean = ((word >> (g.wordBits - k)) & 1) == 1
}

object DcGolden {
  def roundShr(v: Long, s: Int): Long = if (s == 0) v else (v + (1L << (s - 1))) >> s

  def sat(v: Long, w: Int): Long = {
    val hi = (1L << (w - 1)) - 1
    val lo = -(1L << (w - 1))
    math.max(lo, math.min(hi, v))
  }

  final case class Out(y: Vector[Long], accMax: Long, saturated: Int)

  def run(g: DcGenerics, xs: Seq[Long]): Out = {
    val G = g.guardBits
    var x1 = 0L; var y1 = 0L; var accMax = 0L; var nsat = 0
    val ys = xs.map { x =>
      val d = (x - x1) << G
      val y = g.terms.foldLeft(d + y1) { (s, t) =>
        if (t.sign > 0) s - roundShr(y1, t.shift) else s + roundShr(y1, t.shift)
      }
      require(BigInt(math.abs(y)) <= g.accBound, s"|Y| = ${math.abs(y)} > accBound ${g.accBound}")
      accMax = math.max(accMax, math.abs(y))
      x1 = x; y1 = y
      val r = roundShr(y, G)
      val o = sat(r, g.sampleWidth)
      if (o != r) nsat += 1
      o
    }.toVector
    Out(ys, accMax, nsat)
  }

  /** Ta sama rekurencja w Double z a = 1 - oneMinusA (bez kwantyzacji
    * stanu), w jednostkach LSB probki, przycieta do zakresu wyjscia. */
  def runFloat(g: DcGenerics, xs: Seq[Long]): Vector[Double] = {
    val hi = (1L << (g.sampleWidth - 1)) - 1.0
    val lo = -(1L << (g.sampleWidth - 1)).toDouble
    var x1 = 0.0; var y1 = 0.0
    xs.map { xi =>
      val x = xi.toDouble
      val y = x - x1 + g.a * y1
      x1 = x; y1 = y
      math.max(lo, math.min(hi, y))
    }.toVector
  }

  /** Teoretyczne wzmocnienie |H(f)| w dB dla a efektywnego. */
  def gainDb(g: DcGenerics, f: Double): Double = {
    val w  = 2 * math.Pi * f / g.sampleRate
    // H = (1 - z^-1) / (1 - a z^-1)
    val nr = 1 - math.cos(w); val ni = math.sin(w)
    val dr = 1 - g.a * math.cos(w); val di = g.a * math.sin(w)
    10 * math.log10((nr * nr + ni * ni) / (dr * dr + di * di))
  }
}
