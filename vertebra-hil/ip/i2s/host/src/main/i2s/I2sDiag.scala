package newhope.vertebra.hil.i2s

import newhope.vertebra.hil.{HilDumpEntry, HilStat}
import I2sPattern.Link

// =====================================================================
//  Dekodowanie bledu referencyjnym wzorcem (vertebra-hil.md §8): zamiast
//  "bad=1" komunikat mowi "ramka 1532: kanaly zamienione". Wejscie to
//  okno z `dump` (ESP32) albo bufora capture (FPGA): got i exp kazdej
//  ramki, exp policzony przez checker plytki.
// =====================================================================
object I2sDiag {
  private def mask(w : Int) : Long = (1L << w) - 1

  /** Rozpoznana przyczyna niezgodnosci jednej ramki. */
  def explain(link : Link, got : I2sWords, exp : I2sWords) : String = {
    val m = mask(link.wrx)
    def shifted(g : Long, e : Long, d : Int) : Boolean =
      // Opoznienie: bit poprzedniego slowa wchodzi od MSB; wyprzedzenie:
      // do LSB wchodzi bit nastepnego. Tego bitu nie znamy, wiec pomijamy.
      if (d > 0) ((g ^ (e >>> 1)) & (m >>> 1)) == 0
      else       ((g ^ ((e << 1) & m)) & (m & ~1L)) == 0
    if (got == exp) "zgodna"
    else if (got.isSilence) "cisza (luka albo brak danych od nadawcy)"
    else if (got == exp.swap) "kanaly zamienione (polaryzacja WS?)"
    else if (link.isPatternFrame(got)) {
      val sm  = 1L << link.s
      val d   = (link.seqOf(got.l) - link.seqOf(exp.l)) & (sm - 1)
      if (d == sm - 1) "zdublowana ramka (poprzednia jeszcze raz)"
      else s"poprawna ramka wzorca $d dalej: zgubione $d ramek"
    }
    else if (shifted(got.l, exp.l, 1) && shifted(got.r, exp.r, 1)) "przesuniecie o bit: odbiorca probkuje o bit za pozno"
    else if (shifted(got.l, exp.l, -1) && shifted(got.r, exp.r, -1)) "przesuniecie o bit: odbiorca probkuje o bit za wczesnie"
    else {
      val (xl, xr) = (got.l ^ exp.l, got.r ^ exp.r)
      f"przeklamane bity: L xor=$xl%08x (${java.lang.Long.bitCount(xl)}), R xor=$xr%08x (${java.lang.Long.bitCount(xr)})"
    }
  }

  /** Raport do komunikatu testu: pierwszy blad i okno wokol niego. */
  def report(side : String, link : Link, st : HilStat, dump : Seq[HilDumpEntry]) : String = {
    val first = st.firstErr.map(e => s"pierwszy blad, ramka ${e.n}: " +
      explain(link, I2sWords(e.gotL, e.gotR), I2sWords(e.expL, e.expR)))
    val lines = dump.filter(e => e.gotL != e.expL || e.gotR != e.expR).take(8).map { e =>
      val (g, x) = (I2sWords(e.gotL, e.gotR), I2sWords(e.expL, e.expR))
      s"  idx ${e.idx}: got=$g exp=$x - ${explain(link, g, x)}"
    }
    val hint =
      if (!st.locked) Seq("brak locka: zly seed / szerokosc slowa po drugiej stronie, brak zegara albo danych na linii")
      else Nil
    (Seq(s"$side (${link.label}): $st") ++ first ++ hint ++
     (if (dump.isEmpty) Nil else Seq(s"dump: ${dump.size} wpisow, niezgodne:") ++ lines)).mkString("\n")
  }
}
