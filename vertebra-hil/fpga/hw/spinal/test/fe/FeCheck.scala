package newhope.vertebra.hil.fe

import scala.util.Random
import newhope.frontend.{DcGenerics, DcGolden}

// =====================================================================
//  Ocena nagrania strony ESP32 (contract/fe/commands.md, "Ocena").
//  Ta sama funkcja ocenia nagranie w symulacji harnessu (FeHarnessTestplan)
//  i nagranie z PSRAM ESP32 na stanowisku (FeHilTestplan) - jak
//  I2sCheckerModel dla I2S.
//
//  Kroki:
//   1. dekodowanie ramek (FeFrame.decode); ramki bez znacznika to cisza
//   2. poczatek biegu: pierwsza ramka z idx 0 i rst; wczesniejsze ramki
//      ze znacznikiem to resztki poprzedniego biegu albo smieci z DMA
//   3. ciaglosc idx (mod 2^14): zgubiona albo zdublowana ramka powrotna;
//      przerwa dozwolona tylko tuz przed flaga rst (reset DUT-a tnie ramke)
//   4. N1: y == DcGolden(x) na kazdym odcinku miedzy flagami rst (reset
//      DUT-a zeruje stan), z y == x tam, gdzie flaga bypass; golden liczy
//      po calym odcinku, bo filtr w bypassie liczy dalej
//   5. N0: x == micSample(bodziec) od przesuniecia k <= maxLead, potem
//      zera (koniec bodzca) - tylko w pierwszym odcinku, bo reset DUT-a
//      gubi probki bodzca w trakcie resetu
//   6. bypass: flaga dokladnie w oknie [from, to) idx (gdy bez resetow)
//  Do raportu: max |y - float| na odcinkach bez bypassu.
// =====================================================================
object FeCheck {
  /** Ile ramek moze wyprzedzic bodziec (pierwsze ramki ESP32 slave'a). */
  val MaxLead = 16
  /** Ramki powrotne, ktore moze zjesc jeden reset DUT-a (ramka w toku + przycieta). */
  val MaxLostAtReset = 2
  /** Jak daleko szukac poczatku bodzca przy diagnozie (8 buforow DMA ESP32 + zapas). */
  val MaxSearch = 4096

  case class Report(frames : Seq[FeFrame.Out], lost : Int, lead : Option[Int], resets : Int, bypassed : Int,
                    maxFloatErr : Double, info : Seq[String], errors : Seq[String]) {
    def ok : Boolean = errors.isEmpty
    def xs : Seq[Long] = frames.map(_.x)
    def ys : Seq[Long] = frames.map(_.y)
    def summary : String =
      s"${frames.size} ramek (+$lost zgubionych przy resetach), przesuniecie bodzca ${lead.fold("?")(_.toString)}, resetow $resets, " +
      f"w bypassie $bypassed, max |y - float| = $maxFloatErr%.3f LSB" +
      (if (errors.isEmpty) "" else s"; BLEDY: ${errors.take(5).mkString("; ")}")
  }

  /** `raw`: ramki (L, R) w kolejnosci odbioru. `stim`: slowa L bodzca.
    * `bypass`: okno idx z rejestrow (from, to). */
  def analyze(v : FeHilVariant, stim : Seq[Long], raw : Seq[(Long, Long)],
              bypass : (Long, Long) = (0L, 0L)) : Report = {
    val sw   = v.sampleWidth
    val dc   = v.fe.dc
    val errs = scala.collection.mutable.ArrayBuffer[String]()
    val inf  = scala.collection.mutable.ArrayBuffer[String]()
    def err(s : String) : Unit = if (errs.size < 20) errs += s

    // Vector: ponizej indeksowanie po pozycji, a nagranie z plytki to 10^5+ ramek
    // (na List ocena byla O(n^2): 90 s przy 10^5 probek).
    val decoded = raw.iterator.flatMap { case (l, r) => FeFrame.decode(l, r, sw) }.toVector
    val start   = decoded.indexWhere(o => o.idx == 0 && o.rst)
    if (start < 0) {
      err(s"brak poczatku biegu (ramki idx 0 z rst) w ${decoded.size} ramkach ze znacznikiem, ${raw.size} wszystkich")
      return Report(Nil, 0, None, 0, 0, 0.0, inf.toSeq, errs.toSeq)
    }
    if (start > 0) inf += s"pominiete $start ramek ze znacznikiem przed poczatkiem biegu"
    val fr = decoded.drop(start)

    // --- 3. ciaglosc ------------------------------------------------------
    // Reset DUT-a zatrzymuje SCK w polowie ramki, wiec ramka powrotna w toku
    // ginie albo przychodzi przycieta (zly idx). Przerwa jest dozwolona tylko
    // tuz przed ramka z flaga rst (najwyzej MaxLostAtReset ramek pomiedzy);
    // licznik `frames` FPGA liczy te ramki, nagranie nie.
    val okB  = scala.collection.mutable.ArrayBuffer[FeFrame.Out]()
    var lost = 0
    var i    = 0
    var want  = 0
    var broken = false
    while (i < fr.size && !broken) {
      if (fr(i).idx == (want & FeFrame.IdxMask)) { okB += fr(i); want += 1; i += 1 }
      else (i to scala.math.min(i + MaxLostAtReset, fr.size - 1)).find(j => fr(j).rst && j > 0) match {
        case Some(j) =>
          val skip = (fr(j).idx - want) & FeFrame.IdxMask
          if (skip > MaxLostAtReset + 1) { err(s"ramka $i: przerwa $skip ramek przed resetem DUT-a"); broken = true }
          else { lost += skip; want += skip; i = j }
        case None =>
          err(s"ramka ${okB.size}: idx ${fr(i).idx}, oczekiwany ${want & FeFrame.IdxMask} " +
              "(zgubiona albo zdublowana ramka powrotna)")
          broken = true
      }
    }
    if (lost > 0) inf += s"ramki powrotne zgubione przy resetach DUT-a: $lost"
    val ok = okB.toVector

    // --- 4. N1 na odcinkach miedzy resetami -------------------------------
    val cuts = ok.indices.filter(i => ok(i).rst) :+ ok.size
    var maxFloat = 0.0
    for (Seq(a, b) <- cuts.sliding(2) if b > a) {
      val seg  = ok.slice(a, b)
      val gold = DcGolden.run(dc, seg.map(_.x)).y
      val flt  = DcGolden.runFloat(dc, seg.map(_.x))
      for (j <- seg.indices) {
        val o   = seg(j)
        val exp = if (o.bypass) o.x else gold(j)
        if (o.y != exp)
          err(s"ramka ${a + j} (odcinek od $a, probka ${j}): y ${o.y}, oczekiwane $exp " +
              s"(${if (o.bypass) "bypass: y == x" else "DcGolden"}), x ${o.x}")
        if (!o.bypass) maxFloat = scala.math.max(maxFloat, scala.math.abs(o.y - flt(j)))
      }
      if (seg.exists(_.overrun)) err(s"odcinek od $a: flaga overrun filtra")
    }
    val resets = ok.count(_.rst) - 1

    // --- 5. N0: echo x == bodziec ------------------------------------------
    val first = ok.take(cuts.drop(1).headOption.getOrElse(ok.size))
    val exp   = stim.iterator.map(FeFrame.micSample(_, sw)).toVector
    def matches(k : Int) : Boolean = first.indices.forall { i =>
      val j = i - k
      j < 0 || (if (j < exp.size) first(i).x == exp(j) else first(i).x == 0)
    }
    val lead = (0 to scala.math.min(MaxLead, first.size)).find(matches)
    if (stim.nonEmpty) lead match {
      case Some(k) =>
        val seen = scala.math.min(exp.size, first.size - k)
        inf += s"N0: bodziec od ramki $k, $seen z ${exp.size} slow zgodnych (x == slowo >> ${FeFrame.SlotBits - sw})"
        if (seen < exp.size && resets == 0) err(s"N0: nagranie urywa sie po $seen z ${exp.size} slow bodzca")
      case None =>
        // Diagnoza: gdzie naprawde zaczyna sie bodziec (szukane dalej niz
        // MaxLead) i co bylo przed nim - zera z pustego DMA ESP32 to rozbieg
        // partnera, a nie blad N0.
        val far = (0 until scala.math.min(first.size, MaxSearch)).find(k => k > MaxLead && matches(k))
        val pre = (k : Int) => first.take(k).map(_.x)
        far match {
          case Some(k) =>
            val zeros = pre(k).count(_ == 0)
            err(s"N0: bodziec zaczyna sie od ramki $k (dozwolone <= $MaxLead); przed nim $zeros zer i " +
                s"${k - zeros} innych wartosci (${pre(k).filter(_ != 0).take(4).mkString(", ")}); " +
                s"od ramki $k echo x zgodne z bodzcem w calosci" +
                (if (zeros == k) " - same zera: rozbieg nadawania ESP32 (DMA), nie N0" else ""))
          case None =>
            // pierwsza niezgodnosc przy najlepszym przesunieciu, do komunikatu
            val k = (0 to scala.math.min(MaxLead, first.size)).maxBy(k =>
              first.indices.drop(k).takeWhile(i => i - k < exp.size && first(i).x == exp(i - k)).size)
            val i = first.indices.drop(k).find(i => if (i - k < exp.size) first(i).x != exp(i - k) else first(i).x != 0)
            val nz = first.indexWhere(_.x != 0)
            err(s"N0: echo x nie pasuje do bodzca przy zadnym przesunieciu <= $MaxSearch; najlepsze $k, " +
                i.fold("?")(i => s"ramka $i: x ${first(i).x}, oczekiwane ${if (i - k < exp.size) exp(i - k) else 0L}" +
                                 (if (i - k < stim.size) f" (slowo ${stim(i - k)}%08x)" else " (po koncu bodzca)")) +
                s"; pierwsze niezerowe x w ramce $nz: ${first.slice(nz, nz + 4).map(_.x).mkString(", ")}, " +
                s"bodziec zaczyna sie od ${exp.take(4).mkString(", ")}")
        }
    }

    // --- 6. okno bypassu -----------------------------------------------------
    val bypassed = ok.count(_.bypass)
    if (resets == 0) {
      val (from, to) = bypass
      ok.indices.find(i => ok(i).bypass != (i >= from && i < to)).foreach { i =>
        err(s"ramka $i: bypass ${ok(i).bypass}, okno [$from, $to)")
      }
    }

    Report(ok, lost, lead, resets, bypassed, maxFloat, inf.toSeq, errs.toSeq)
  }

  /** Sumy licznikow FPGA (x_sum, y_sum) z nagrania: u32 sum wartosci ze znakiem. */
  def sums(frames : Seq[FeFrame.Out]) : (Long, Long) =
    (frames.map(_.x).sum & FeFrame.U32, frames.map(_.y).sum & FeFrame.U32)
}

// =====================================================================
//  Bodzce (slowa L dla `load`). ESP32 udaje INMP441: 24-bitowe slowo
//  mikrofonu w gornych bitach slotu 32, ponizej 8 bitow smieci, ktore
//  N0 musi odrzucic (jak bity 25..32 w MicModel). Mlodsze bity slowa 24
//  ponizej probki tez sa losowe - obciecie widac w echu x.
// =====================================================================
object FeStimulus {
  val MicMax = (1L << 23) - 1
  val MicMin = -(1L << 23)

  def word(mic24 : Long, junk8 : Int) : Long = (((mic24 & 0xFFFFFFL) << 8) | (junk8 & 0xFF)) & FeFrame.U32

  def clampMic(v : Double) : Long = scala.math.max(MicMin, scala.math.min(MicMax, scala.math.round(v * (1L << 23))))

  /** Mowa z offsetem DC: jak fe_chain w MicFrontEndTestplan. */
  def noisy(fs : Double, n : Int, rng : Random) : Seq[Long] = (0 until n).map { i =>
    val v = 0.02 + 0.1 * scala.math.sin(2 * scala.math.Pi * 220 * i / fs) + 0.01 * rng.nextGaussian()
    word(clampMic(v), rng.nextInt(256))
  }

  def tone(fs : Double, n : Int, f : Double, amp : Double, offset : Double, rng : Random) : Seq[Long] =
    (0 until n).map(i => word(clampMic(offset + amp * scala.math.sin(2 * scala.math.Pi * f * i / fs)), rng.nextInt(256)))

  /** +-FS co probke: najwieksza roznica x - x1, nasycenie wyjscia. */
  def square(n : Int, rng : Random) : Seq[Long] =
    (0 until n).map(i => word(if (i % 2 == 1) MicMax else MicMin, rng.nextInt(256)))

  /** Skok -FS -> +FS po 50 probkach, potem staly +FS (DC do usuniecia). */
  def step(n : Int, rng : Random) : Seq[Long] =
    (0 until n).map(i => word(if (i < 50) MicMin else MicMax, rng.nextInt(256)))

  /** Pelne losowe slowa 32 bity. */
  def random(n : Int, rng : Random) : Seq[Long] = Seq.fill(n)(rng.nextLong() & FeFrame.U32)

  def zeros(n : Int) : Seq[Long] = Seq.fill(n)(0L)
}
