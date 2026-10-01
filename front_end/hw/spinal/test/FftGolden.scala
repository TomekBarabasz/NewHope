package newhope.frontend

import scala.collection.mutable

// =====================================================================
//  Golden model bit-exact. Kazda operacja ma swoj odpowiednik w RTL
//  z ta sama kolejnoscia zaokraglen i nasycen. Lustrzana wersja
//  w Pythonie: tools/fft_golden.py (dla strony PyTorch w M0).
//
//  Asercje `require` w srodku lapia bledy KRYTERIUM, nie RTL:
//  jesli roznica w motylku nie miesci sie w W bitach, to shiftFor jest
//  zle, a nie symulacja.
// =====================================================================
object FftGolden {
  final case class Cx(re: Long, im: Long) {
    def l1 = math.abs(re) + math.abs(im)
    override def toString = s"($re, $im)"
  }

  final class Sat(w: Int) {
    var count = 0
    private val hi = (1L << (w - 1)) - 1
    private val lo = -(1L << (w - 1))
    def apply(x: Long): Long =
      if (x > hi) { count += 1; hi } else if (x < lo) { count += 1; lo } else x
  }

  def bitrev(i: Int, bits: Int): Int = Integer.reverse(i) >>> (32 - bits)

  final case class CoreOut(g: FftGenerics, mem: Vector[Cx], exp: Int, l1max: Long, sats: Int) {
    def natural: Vector[Cx]      = Vector.tabulate(g.n)(k => mem(bitrev(k, g.logN)))
    def nextShift: Int           = Bfp.shiftFor(l1max, g.dataWidth)
    def pairs: Vector[(Cx, Cx)]  = { val z = natural; Vector.tabulate(g.n)(k => (z(k), z((g.n - k) % g.n))) }
    def ordered: Vector[Cx]      = g.outOrder match {
      case OutOrder.Bitrev => mem
      case _               => natural
    }
  }

  def core(g: FftGenerics, in: Seq[Cx], exp0: Int, inverse: Boolean): CoreOut = {
    import g._
    require(in.size == n, s"ramka ma ${in.size} probek, oczekiwano $n")
    val W  = dataWidth
    val TW = twiddleWidth
    val x   = in.toArray
    val tw  = Bfp.twiddleTable(n, n / 2, twiddleScale)
    val sat = new Sat(W)
    var l1max = x.map(_.l1).max
    var e     = exp0
    for (s <- 0 until logN) {
      val sh = Bfp.shiftFor(l1max, W)
      e += sh
      val p = logN - 1 - s
      val h = 1 << p
      var nm = 0L
      for (i <- 0 until n / 2) {
        val a  = ((i >> p) << (p + 1)) | (i & (h - 1))
        val b  = a | h
        val (c, sn) = tw((i & (h - 1)) << s)
        val wr = c
        val wi = if (inverse) sn else -sn
        val ar = Bfp.shiftRound(x(a).re, sh); val ai = Bfp.shiftRound(x(a).im, sh)
        val br = Bfp.shiftRound(x(b).re, sh); val bi = Bfp.shiftRound(x(b).im, sh)
        val dr = ar - br; val di = ai - bi
        require(Bfp.fits(dr, W) && Bfp.fits(di, W),
          s"stopien $s, motylek $i: roznica ($dr, $di) poza $W bitami - blad kryterium BFP")
        val A = Cx(sat(ar + br), sat(ai + bi))
        val B = Cx(sat(Bfp.roundShr(dr * wr - di * wi, TW - 1)),
                   sat(Bfp.roundShr(dr * wi + di * wr, TW - 1)))
        x(a) = A; x(b) = B
        nm = math.max(nm, math.max(A.l1, B.l1))
      }
      l1max = nm
    }
    CoreOut(g, x.toVector, e, l1max, sat.count)
  }

  final case class UnpackOut(x: Vector[Cx], exp: Int, sats: Int)

  /** zNat: Z[k] w porzadku naturalnym, M = zNat.size. */
  def unpack(g: FftGenerics, zNat: Vector[Cx], exp: Int, nextShift: Int): UnpackOut = {
    val M  = zNat.size
    val W  = g.dataWidth
    val TW = g.twiddleWidth
    val tw  = Bfp.twiddleTable(2 * M, M + 1, g.twiddleScale)
    val sat = new Sat(W)
    val p   = nextShift
    val out = (0 to M).map { k =>
      val kk = k % M
      val zk = zNat(kk); val zr = zNat((M - kk) % M)
      val zkR = Bfp.shiftRound(zk.re, p); val zkI = Bfp.shiftRound(zk.im, p)
      val zrR = Bfp.shiftRound(zr.re, p); val zrI = Bfp.shiftRound(zr.im, p)
      val sR = zkR + zrR; val sI = zkI - zrI
      val dR = zkR - zrR; val dI = zkI + zrI
      val tR = dI;        val tI = -dR
      require(Seq(sR, sI, tR, tI).forall(Bfp.fits(_, W)), s"k = $k: S/T poza $W bitami")
      val (c, sn) = tw(k)
      val wr = c; val wi = -sn
      val yR = (sR << (TW - 1)) + tR * wr - tI * wi
      val yI = (sI << (TW - 1)) + tR * wi + tI * wr
      Cx(sat(Bfp.roundShr(yR, TW)), sat(Bfp.roundShr(yI, TW)))
    }
    UnpackOut(out.toVector, exp + p, sat.count)
  }

  /** Ramki Framera dla calego strumienia probek (zera przed poczatkiem). */
  def framer(fg: FramerGenerics, samples: Seq[Long]): Vector[Vector[Cx]] = {
    val L   = fg.fftSize
    val tab = Bfp.hannTable(L, fg.windowScale)
    val ring = Array.fill(L)(0L)
    val frames = mutable.ArrayBuffer[Vector[Cx]]()
    for ((x, t) <- samples.zipWithIndex) {
      ring(t % L) = x
      if ((t + 1) % fg.hop == 0) {
        val y = (0 until L).map { i =>
          val v = ring((t + 1 + i) % L)
          Bfp.roundShr(v * tab(if (i <= L / 2) i else L - i), fg.windowWidth - 1)
        }
        frames += Vector.tabulate(L / 2)(m => Cx(y(2 * m), y(2 * m + 1)))
      }
    }
    frames.toVector
  }

  /** Caly tor Rfft dla jednej ramki z Framera. */
  def rfftFrame(g: RfftGenerics, packed: Vector[Cx]): UnpackOut = {
    val c = core(g.core, packed, 0, inverse = false)
    unpack(g.core, c.natural, c.exp, c.nextShift)
  }

  def power(x: Cx, exp: Int): (Long, Int) = (x.re * x.re + x.im * x.im, 2 * exp)

  def isqrt(p: Long): Long = {
    var r = math.sqrt(p.toDouble).toLong
    while (r * r > p) r -= 1
    while ((r + 1) * (r + 1) <= p) r += 1
    r
  }

  def magnitude(p: Long, exp2: Int): (Long, Int) = {
    val r = isqrt(p)
    (if (p - r * r > r) r + 1 else r, exp2 >> 1)
  }

  // ---- odniesienie zmiennoprzecinkowe (do testow golden_vs_float) -------
  def dft(z: Seq[(Double, Double)], inverse: Boolean): Vector[(Double, Double)] = {
    val n = z.size
    val sgn = if (inverse) 1.0 else -1.0
    Vector.tabulate(n) { k =>
      var re = 0.0; var im = 0.0
      for (m <- 0 until n) {
        val th = sgn * 2 * math.Pi * ((k.toLong * m) % n) / n
        val c = math.cos(th); val s = math.sin(th)
        re += z(m)._1 * c - z(m)._2 * s
        im += z(m)._1 * s + z(m)._2 * c
      }
      (re, im)
    }
  }

  /** SNR w dB: energia odniesienia / energia bledu. */
  def snrDb(got: Seq[(Double, Double)], ref: Seq[(Double, Double)]): Double = {
    val sig = ref.map { case (a, b) => a * a + b * b }.sum
    val err = got.zip(ref).map { case ((a, b), (c, d)) => (a - c) * (a - c) + (b - d) * (b - d) }.sum
    if (err == 0) Double.PositiveInfinity else 10 * math.log10(sig / err)
  }

  def scaled(v: Seq[Cx], exp: Int): Vector[(Double, Double)] =
    v.map(c => (c.re * math.pow(2, exp), c.im * math.pow(2, exp))).toVector
}
