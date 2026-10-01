package newhope.frontend

import spinal.core._

// =====================================================================
//  Parametry i jedyne zrodlo prawdy o numeryce BFP.
//
//  Funkcje w `Bfp` sa czysta Scala: z nich generuja sie zawartosci
//  ROM-ow w RTL i z nich korzysta golden model w testach. Logika
//  sprzetowa (BfpHw) implementuje te same funkcje osobno - zgodnosc
//  sprawdza test bit-exact, nie wspolny kod.
// =====================================================================

object Bfp {
  def roundHalfUp(x: Double): Long = math.floor(x + 0.5).toLong

  def msb(x: Long): Int = 63 - java.lang.Long.numberOfLeadingZeros(x)

  /** Przesuniecie bloku przed kolejnym motylkiem (dodatnie = w prawo).
    *
    * Kryterium L1 (|re|+|im|), bo L1 ogranicza modul zespolony:
    * jesli L1(a), L1(b) < 2^(w-2), to kazda skladowa a+b oraz
    * (a-b)*W jest < 2^(w-1). Kryterium na max(|re|,|im|) tego nie
    * gwarantuje: (a-b)*W moze urosnac 2*sqrt(2) raza w jednej skladowej.
    *
    * Progi dla s > 0 uwzgledniaja zaokraglenie przy przesuwaniu:
    * po przesunieciu o s kazda skladowa rosnie o najwyzej 1/2,
    * wiec L1 o najwyzej 1 -> warunek L1/2^s + 1 < 2^(w-2).
    *
    * Blok ponizej 2^(w-3) jest normalizowany w lewo (s < 0) do
    * przedzialu [2^(w-3), 2^(w-2)). To utrzymuje SNR ~75 dB od 0 do
    * -80 dBFS (tools/fft_golden.py, sekcja "levels"). */
  def shiftFor(l1: Long, w: Int): Int = {
    require(l1 >= 0)
    if (l1 == 0) 0
    else if (l1 < (1L << (w - 2))) msb(l1) - (w - 3)
    else if (l1 < (1L << (w - 1)) - 2) 1
    else if (l1 < (1L << w) - 4) 2
    else {
      require(l1 < (1L << (w + 1)) - 8, s"L1 = $l1 poza zakresem dla w = $w")
      3
    }
  }

  /** Przesuniecie z zaokragleniem do najblizszego, polowy w gore.
    * Ujemne s = przesuniecie w lewo (bez strat). */
  def shiftRound(x: Long, s: Int): Long =
    if (s < 0) x << -s
    else if (s == 0) x
    else (x + (1L << (s - 1))) >> s

  def roundShr(x: Long, n: Int): Long = (x + (1L << (n - 1))) >> n

  def fits(x: Long, w: Int): Boolean = x >= -(1L << (w - 1)) && x < (1L << (w - 1))

  /** (round(cos(2*pi*k/n)*scale), round(sin(2*pi*k/n)*scale)), k = 0 until count.
    * StrictMath, zeby wynik nie zalezal od JVM. Skala 2^(tw-1)-1, bo
    * cos(0) = 1 nie miesci sie w Q0.(tw-1). */
  def twiddleTable(n: Int, count: Int, scale: Long): IndexedSeq[(Long, Long)] =
    (0 until count).map { k =>
      val th = 2.0 * math.Pi * k / n
      (roundHalfUp(StrictMath.cos(th) * scale), roundHalfUp(StrictMath.sin(th) * scale))
    }

  /** Periodyczny Hann (sym=False), probki 0..L/2; reszta z symetrii
    * w[i] = w[L - i]. */
  def hannTable(L: Int, scale: Long): IndexedSeq[Long] =
    (0 to L / 2).map { i =>
      roundHalfUp((0.5 - 0.5 * StrictMath.cos(2.0 * math.Pi * i / L)) * scale)
    }

  def packPair(re: Long, im: Long, w: Int): BigInt = {
    val mask = (BigInt(1) << w) - 1
    ((BigInt(im) & mask) << w) | (BigInt(re) & mask)
  }
}

object OutOrder extends Enumeration {
  /** Natural:   X[k], k = 0..n-1
    * Bitrev:    kolejnosc w pamieci (surowy wynik DIF)
    * RealPairs: (Z[k], Z[(n-k) mod n]), k = 0..n-1 - wejscie RealUnpack */
  val Natural, Bitrev, RealPairs = Value
}

case class FftGenerics(logN        : Int,
                       dataWidth   : Int = 18,
                       twiddleWidth: Int = 18,
                       expWidth    : Int = 8,
                       outOrder    : OutOrder.Value = OutOrder.Natural) {
  def n            = 1 << logN
  def butterflies  = n / 2
  def twiddleScale = (1L << (twiddleWidth - 1)) - 1
  def l1Width      = dataWidth + 1
  def shiftMin     = -(dataWidth - 3)   // L1 = 1 -> msb 0
  def shiftMax     = 3
  def shiftWidth   = log2Up(math.max(-shiftMin, shiftMax) + 1) + 1
  def pairs        = outOrder == OutOrder.RealPairs

  // ---- harmonogram (patrz FftCore) -----------------------------------
  // Stopien: PREP (1) + RUN (n, bo II = 2 i n/2 motylkow) + DRAIN (5,
  // opoznienie potoku t1..t5). Wyjscie: pierwszy element po 2 cyklach
  // od wejscia w OUT, para po 3.
  def stageCycles     = n + 6
  def computeLatency  = 1 + logN * stageCycles + (if (pairs) 3 else 2)
  def outputCycles    = if (pairs) 2 * n else n
  def busyCycles      = n + computeLatency + outputCycles

  // ---- zakres wykladnika ---------------------------------------------
  // Normalizacja w lewo mozliwa w pelni tylko raz (blok >= 2^(w-3)
  // po normalizacji; kolejny stopien nie zmniejsza max modulu, a L1
  // spada najwyzej sqrt(2) raza -> kolejne przesuniecia >= -1).
  // Wzrost: najwyzej 3 na stopien. Plus pre-shift RealUnpack.
  def expDeltaMin = shiftMin - logN - 1
  def expDeltaMax = shiftMax * (logN + 1)
  def expMin      = -(1 << (expWidth - 1))
  def expMax      = (1 << (expWidth - 1)) - 1

  /** Zapas wykladnika przy wejsciu z wykladnikiem e0 w [e0min, e0max]. */
  def expMargin(e0min: Int, e0max: Int): Int =
    math.min((e0min + expDeltaMin) - expMin, expMax - (e0max + expDeltaMax))

  // ---- legalnosc -------------------------------------------------------
  def dspFits   = dataWidth <= 18 && twiddleWidth <= 18   // DSP48A1 18x18
  def problems: Seq[String] = Seq(
    (logN >= 2)                 -> s"logN = $logN < 2",
    (dataWidth >= 6)            -> s"dataWidth = $dataWidth < 6",
    (twiddleWidth >= 4)         -> s"twiddleWidth = $twiddleWidth < 4",
    dspFits                     -> "mnozenie nie miesci sie w 18x18",
    (expMargin(-8, 8) >= 0)     -> s"expWidth = $expWidth za maly"
  ).collect { case (false, msg) => msg }
  def isLegal = problems.isEmpty
}

case class FramerGenerics(fftSize    : Int = 512,
                          hop        : Int = 160,
                          sampleWidth: Int = 18,
                          windowWidth: Int = 18,
                          expWidth   : Int = 8) {
  def logL        = log2Up(fftSize)
  def windowScale = (1L << (windowWidth - 1)) - 1
  def problems: Seq[String] = Seq(
    isPow2(fftSize)                  -> s"fftSize = $fftSize nie jest potega 2",
    (fftSize >= 8)                   -> s"fftSize = $fftSize < 8",
    (hop >= 2 && hop <= fftSize)     -> s"hop = $hop poza [2, fftSize]",
    (sampleWidth <= 18 && windowWidth <= 18) -> "mnozenie okna nie miesci sie w 18x18"
  ).collect { case (false, msg) => msg }
  def isLegal = problems.isEmpty
}

/** Caly tor: Framer -> FftCore(RealPairs) -> RealUnpack. */
case class RfftGenerics(framer: FramerGenerics, clockHz: Long, sampleRate: Int) {
  def core = FftGenerics(logN         = framer.logL - 1,
                         dataWidth    = framer.sampleWidth,
                         twiddleWidth = framer.windowWidth,
                         expWidth     = framer.expWidth,
                         outOrder     = OutOrder.RealPairs)
  def cyclesPerSample = clockHz / sampleRate
  def bins            = framer.fftSize / 2 + 1

  /** Czas zajetosci toru na ramke: odczyt ramki z Framera (fftSize
    * cykli, pakowane do n = fftSize/2 wejsc rdzenia, ktore w tym czasie
    * laduja sie rownolegle) + obliczenia + wyjscie par przez RealUnpack
    * (1 para na cykl przy braku backpressure) + probka Nyquista + potok. */
  def frameBusyCycles = framer.fftSize + core.computeLatency + core.outputCycles + 8

  /** Kolejna ramka zaczyna sie po hop probkach. Jesli poprzednia jeszcze
    * blokuje rdzen, Framer stoi, a nowe probki nadpisuja bufor. */
  def marginCycles = framer.hop * cyclesPerSample - frameBusyCycles

  def problems: Seq[String] = framer.problems ++ core.problems ++
    Seq((marginCycles > 0) -> s"brak zapasu czasu: $marginCycles cykli na ramke")
      .collect { case (false, msg) => msg }
  def isLegal = problems.isEmpty
}

object FftGenerics {
  val mimas512 = RfftGenerics(FramerGenerics(), clockHz = 75000000L, sampleRate = 16000)
  val ice40512 = RfftGenerics(FramerGenerics(), clockHz = 48000000L, sampleRate = 16000)
}
