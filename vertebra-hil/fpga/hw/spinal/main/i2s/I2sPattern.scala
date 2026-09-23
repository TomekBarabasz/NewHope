package newhope.vertebra.hil.i2s

import newhope.i2s.I2sFormat

// =====================================================================
//  Wzorzec danych vertebra-hil dla I2S - implementacja REFERENCYJNA.
//
//  Specyfikacja: vertebra-hil/contract/i2s/pattern.md. Ten plik ma ja
//  realizowac doslownie; kazda roznica to blad tutaj albo w specyfikacji,
//  nigdy "szczegol implementacji". Implementacje w Spinalu (I2sPatternGen,
//  etap 2) i w C (hil_pattern, etap 3) sprawdzaja sie wobec wektorow
//  generowanych stad (I2sVectors), a nie wobec tego kodu.
//
//  Arytmetyka: wszystko na Long, a 32-bitowe wartosci bez znaku sa
//  maskowane przez U32. Scala nie ma uint32; Int z >>> tez by dzialal,
//  ale Long nie myli sie przy wypisywaniu i porownaniach.
//
//  UWAGA: bez `import spinal.lib._` - przeslania scala.math (TESTING-
//  STRATEGY §5), a ten plik niczego ze Spinala nie potrzebuje.
// =====================================================================

/** Para slow jednej ramki: kanal lewy (WS=0) i prawy. */
case class I2sWords(l : Long, r : Long) {
  def isSilence : Boolean = l == 0 && r == 0
  def swap      : I2sWords = I2sWords(r, l)
  override def toString : String = f"($l%08x, $r%08x)"
}

object I2sPattern {
  val U32 : Long = 0xFFFFFFFFL

  /** Zakres szerokosci nadawanego slowa, dla ktorej wzorzec jest okreslony. */
  val MinWidth = 8
  val MaxWidth = 32
  def validWidth(w : Int) : Boolean = w >= MinWidth && w <= MaxWidth

  /** S: bity pola seq. 3 dla W=8, 7 dla W=16, 8 dla W>=18. */
  def seqBits(w : Int) : Int = {
    require(validWidth(w), s"szerokosc wzorca $w poza [$MinWidth, $MaxWidth]")
    scala.math.min(8, w / 2 - 1)
  }
  /** Bity hasha w slowie: wszystko ponizej pola seq. */
  def hashBits(w : Int) : Int = w - 1 - seqBits(w)
  def seqMask(w : Int)  : Long = (1L << seqBits(w)) - 1

  /** Jeden krok xorshift32 (13, 17, 5), przesuniecie w prawo LOGICZNE. */
  def xorshift32(x0 : Long) : Long = {
    var x = x0 & U32
    x ^= (x << 13) & U32
    x ^= x >>> 17
    x ^= (x << 5) & U32
    x
  }

  /** Hash liczony z (seq, c, seed), a NIE z pelnego n: checker po locku
    * zna tylko n mod 2^S, wiec slowo musi byc funkcja tego, co widzi
    * (contract/i2s/pattern.md, "Dlaczego hash z seq"). */
  def hash(seq : Long, c : Int, seed : Long) : Long =
    xorshift32(((seq << 1) | c.toLong) ^ (seed & U32))

  /** Slowo W-bitowe ramki n (uint32, dowolne), kanalu c (0 = L, 1 = R). */
  def word(seed : Long, n : Long, c : Int, w : Int) : Long = {
    require(c == 0 || c == 1, s"kanal $c")
    val s   = seqBits(w)
    val h   = hashBits(w)
    val seq = n & seqMask(w)
    (c.toLong << (w - 1)) | (seq << h) | (hash(seq, c, seed) & ((1L << h) - 1))
  }

  def frame(seed : Long, n : Long, w : Int) : I2sWords =
    I2sWords(word(seed, n, 0, w), word(seed, n, 1, w))

  // -------------------------------------------------------------------
  //  Polaczenie nadawca -> odbiorca: to, co checker musi wiedziec o obu
  //  stronach. Wszystko, co odbiorca porownuje, przechodzi przez transfer.
  // -------------------------------------------------------------------
  case class Link(seed : Long, wtx : Int, slot : Int, wrx : Int) {
    require(validWidth(wtx), s"wtx=$wtx poza [$MinWidth, $MaxWidth]")
    require(slot >= 1 && wrx >= 1 && wrx <= MaxWidth, s"slot=$slot wrx=$wrx")

    val s : Int = seqBits(wtx)

    /** Bity slowa nadawcy, ktore faktycznie docieraja do odbiorcy. */
    def effBits : Int = scala.math.min(wtx, scala.math.min(slot, wrx))

    /** Checker potrzebuje kanalu i CALEGO pola seq, zeby z jednej ramki
      * wyznaczyc oczekiwane slowa. Bez tego lock jest niemozliwy. */
    def checkable : Boolean = effBits >= 1 + s

    /** Bity hasha widoczne dla odbiorcy. 0 = przesuniecie o bit wykrywa
      * juz tylko pole seq (znane ograniczenie z §4 vertebra-hil.md). */
    def visibleHashBits : Int = scala.math.max(0, effBits - 1 - s)

    def expected(n : Long) : I2sWords = {
      val f = frame(seed, n, wtx)
      I2sWords(I2sFormat.transfer(f.l, wtx, slot, wrx),
               I2sFormat.transfer(f.r, wtx, slot, wrx))
    }

    /** Pole seq odczytane ze slowa odebranego (kanal L albo R). */
    def seqOf(v : Long) : Long = (v >>> (wrx - 1 - s)) & seqMask(wtx)

    /** Ramka jest poprawna ramka wzorca dla numeru z wlasnego pola seq. */
    def isPatternFrame(f : I2sWords) : Boolean =
      !f.isSilence && f == expected(seqOf(f.l))

    def label : String = f"seed=$seed%08x wtx=$wtx slot=$slot wrx=$wrx"
  }
}
