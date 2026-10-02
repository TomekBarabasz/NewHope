package newhope.vertebra.hil.fe

import newhope.frontend.{Bfp, FftGolden}

// =====================================================================
//  Ocena N2 - N4 (contract/fe/commands.md, "N2 - N4"): rekordy z kanalu
//  pomocniczego nagrania kontra golden z echa y. Ta sama funkcja
//  w symulacji harnessu (FeHarnessTestplan) i na stanowisku (FeHilTestplan).
//
//  Kroki:
//   1. sklejanie rekordow z bajtow aux kolejnych ramek FeCheck (sof otwiera
//      rekord, dlugosc z typu); przerwa w abs (ramki zgubione przy resecie)
//      albo sof w srodku rekordu = rekord rozerwany
//   2. golden jak sprzet: Framer po kazdym resecie podaje zera w miejsce
//      pozycji niezapisanych od resetu (licznik fill), wiec kazdy odcinek
//      (od flagi rst) startuje z pierscieniem zer. Ramka f odcinka ma
//      trig = abs probki (f + 1) * hop - 1 i okno z ostatnich fftSize
//      zapisow. Probki zgubione przy resecie (koniec odcinka przed flaga
//      rst) robia ramke nieweryfikowalna. Potem FftGolden.rfftFrame i power.
//   3. rekord CRC: flagi == 7, CRC i wykladnik N2, N3, N4 == golden
//      (rozjazd nazywa pierwszy niezgodny wezel); rekord dla trig, ktorego
//      golden nie zna, to blad
//   4. rekord zrzutu: widmo mocy == golden prazek po prazku
//   5. kazda ramka golden musi miec rekord CRC, chyba ze jej trig jest tuz
//      przed resetem (DUT nie zdazyl), pod koniec nagrania (rekord jeszcze
//      w kolejce, `tailSlack` probek) albo rekord rozerwal reset (brakow
//      najwyzej tyle, ile rekordow rozerwanych)
// =====================================================================
object FeN4Check {
  case class Report(crcRecs : Int, dumps : Int, frames : Int, unknown : Int, torn : Int, missingNearReset : Int,
                    missingAtEnd : Int, info : Seq[String], errors : Seq[String]) {
    def verified : Int = frames - unknown
    def ok : Boolean = errors.isEmpty
    def summary : String =
      s"N2-N4: $frames ramek golden ($unknown nieweryfikowalnych: probki zgubione przy resecie), $crcRecs rekordow CRC zgodnych, " +
      s"$dumps zrzutow zgodnych, rozerwanych $torn, bez rekordu: $missingNearReset przy resecie, $missingAtEnd na koncu" +
      (if (errors.isEmpty) "" else s"; BLEDY: ${errors.take(5).mkString("; ")}")
  }

  /** `known` = false: okno zawiera probke zgubiona przy resecie. */
  case class Gold(trig : Long, known : Boolean, crc : Vector[Long], exp : Vector[Int], power : Vector[Long],
                  powerExp : Int, segEnd : Long)

  /** Golden calego biegu. `ys(abs)`: probka N1 albo None (zgubiona przy
    * resecie); `starts`: abs pierwszych probek odcinkow (flagi rst). */
  def golden(v : FeHilVariant, ys : Long => Option[Long], first : Long, last : Long, starts : Seq[Long]) : Vector[Gold] = {
    val g   = v.rfft.get
    val fg  = g.framer
    val L   = fg.fftSize
    val tab = Bfp.hannTable(L, fg.windowScale)
    val ring = new Array[Option[Long]](L)
    val out = scala.collection.mutable.ArrayBuffer[Gold]()
    val segs = (starts.sorted :+ (last + 1)).sliding(2).collect { case Seq(a, b) if b > a => (a, b - 1) }.toSeq
    for ((a, b) <- segs) {
      for (i <- 0 until L) ring(i) = Some(0L)   // zera po resecie
      var wr = 0
      for (t <- 0L to (b - a)) {
        ring(wr) = ys(a + t)
        wr = (wr + 1) % L
        if ((t + 1) % fg.hop == 0) {
          val win = (0 until L).map(i => ring((wr + i) % L))
          val trig = (a + t) & FeN4.TrigMask
          if (win.exists(_.isEmpty)) out += Gold(trig, false, Vector.empty, Vector.empty, Vector.empty, 0, b)
          else {
            val y = win.zipWithIndex.map { case (vv, i) =>
              Bfp.roundShr(vv.get * tab(if (i <= L / 2) i else L - i), fg.windowWidth - 1) }
            val f  = Vector.tabulate(L / 2)(m => FftGolden.Cx(y(2 * m), y(2 * m + 1)))
            val u  = FftGolden.rfftFrame(g, f)
            val pw = u.x.map(c => FftGolden.power(c, u.exp)._1)
            out += Gold(trig, true,
              Vector(FeN4.crc(f.map(c => FeN4.pair(c.re, c.im))), FeN4.crc(u.x.map(c => FeN4.pair(c.re, c.im))), FeN4.crc(pw)),
              Vector(0, u.exp, 2 * u.exp), pw, 2 * u.exp, b)
          }
        }
      }
    }
    out.toVector
  }

  /** Probki od trig do rekordu w kolejce: przetworzenie ramki, zrzut
    * w toku i kilka rekordow CRC przed nim. */
  def tailSlack(v : FeHilVariant) : Int = {
    val g = v.rfft.get
    (g.frameBusyCycles / g.cyclesPerSample).toInt + 2 + FeN4.dumpLen(g.bins) + 4 * FeN4.CrcLen
  }

  def analyze(v : FeHilVariant, r : FeCheck.Report) : Report = {
    require(v.hasN4, s"${v.name} bez N2 - N4")
    val g    = v.rfft.get
    val bins = g.bins
    val errs = scala.collection.mutable.ArrayBuffer[String]()
    val inf  = scala.collection.mutable.ArrayBuffer[String]()
    def err(s : String) : Unit = if (errs.size < 20) errs += s

    // --- 1. rekordy --------------------------------------------------------
    val recs = scala.collection.mutable.ArrayBuffer[FeN4.Rec]()
    var cur  = scala.collection.mutable.ArrayBuffer[Int]()
    var need = 0
    var torn = 0
    var orphans = 0
    var lastAbs = -1L
    for ((o, a) <- r.frames.zip(r.abs)) {
      if (need > 0 && a != lastAbs + 1) { torn += 1; need = 0; cur.clear() }   // ramki zgubione w srodku rekordu
      lastAbs = a
      o.aux.foreach { case (sof, b) =>
        if (sof) {
          if (need > 0) torn += 1
          cur = scala.collection.mutable.ArrayBuffer(b)
          need = FeN4.recLen(b, bins).getOrElse { err(f"ramka $a: nieznany typ rekordu $b%02x"); 0 } - 1
        } else if (need > 0) { cur += b; need -= 1 }
        else orphans += 1
        if (need == 0 && cur.nonEmpty) {
          FeN4.parse(cur.toSeq, bins) match {
            case Some(x) => recs += x
            case None    => err(s"ramka $a: rekord nie parsuje sie (${cur.size} B, typ ${cur.head})")
          }
          cur.clear()
        }
      }
    }
    if (orphans > 0) inf += s"bajty aux poza rekordem: $orphans (start w srodku rekordu)"

    // --- 2. golden calego biegu ----------------------------------------------
    val yOf    = r.abs.zip(r.frames.map(_.y)).toMap
    val starts = r.frames.indices.filter(i => r.frames(i).rst).map(r.abs)
    val endAbs = r.abs.lastOption.getOrElse(0L)
    val gold   = if (r.abs.isEmpty) Vector.empty[Gold]
                 else golden(v, yOf.get, r.abs.head, endAbs, starts)
    val byTrig = gold.map(x => x.trig -> x).toMap
    val lat    = (g.frameBusyCycles / g.cyclesPerSample).toInt + 2

    // --- 3., 4. rekordy kontra golden ---------------------------------------
    var crcOk = 0
    var dumpOk = 0
    val seen = scala.collection.mutable.Set[Long]()
    // probki zgubione przy resetach (DUT je przetworzyl, nagranie ich nie ma):
    // ramka z trig w takiej luce nie ma golden, jej rekord pomijamy
    val gaps = r.abs.zip(r.abs.drop(1)).collect { case (a, b) if b > a + 1 => (a + 1, b - 1) }
    def inGap(t : Long) = gaps.exists { case (a, b) => t >= (a & FeN4.TrigMask) && t <= (b & FeN4.TrigMask) }
    var gapRecs = 0
    for (rec <- recs) byTrig.get(rec.trig) match {
      case None if inGap(rec.trig) => gapRecs += 1
      case None => err(s"rekord ${rec.getClass.getSimpleName} dla trig ${rec.trig}: brak takiej ramki w golden")
      case Some(x) if !x.known => if (rec.isInstanceOf[FeN4.CrcRec]) seen += rec.trig
      case Some(x) => rec match {
        case FeN4.CrcRec(t, flags, crc, exp) =>
          if (!seen.add(t)) err(s"trig $t: drugi rekord CRC")
          val bad = (0 until 3).find(k => crc(k) != x.crc(k) || exp(k) != x.exp(k))
          if (flags != 7) err(s"trig $t: flagi liczby elementow $flags (N2, N3, N4 = bity 0, 1, 2), oczekiwane 7")
          else bad match {
            case Some(k) =>
              err(f"trig $t: ${FeN4.Nodes(k)} CRC ${crc(k)}%08x exp ${exp(k)}, golden ${x.crc(k)}%08x exp ${x.exp(k)}" +
                  (if (k > 0) s" (${FeN4.Nodes(k - 1)} zgodny)" else ""))
            case None => crcOk += 1
          }
        case FeN4.DumpRec(t, e, p) =>
          val k = p.indices.find(i => p(i) != x.power(i))
          if (e != x.powerExp) err(s"trig $t: zrzut N4 exp $e, golden ${x.powerExp}")
          else k match {
            case Some(i) => err(s"trig $t: zrzut N4 prazek $i: p ${p(i)}, golden ${x.power(i)}")
            case None    => dumpOk += 1
          }
      }
    }

    // --- 5. kompletnosc -----------------------------------------------------
    var nearReset = 0
    var atEnd = 0
    val missing = scala.collection.mutable.ArrayBuffer[Long]()
    for (x <- gold if !seen(x.trig)) {
      if (x.segEnd != endAbs && x.trig > ((x.segEnd - lat) & FeN4.TrigMask)) nearReset += 1
      else if (x.trig > endAbs - tailSlack(v)) atEnd += 1
      else missing += x.trig
    }
    // rekord rozerwany przez reset (w drodze, w kolejce za zrzutem) to brak
    // rekordu dla jakiejs ramki; wiecej brakow niz rozerwanych to blad
    if (missing.size > torn) err(s"brak rekordu CRC dla trig ${missing.take(5).mkString(", ")} " +
                                 s"(${missing.size} ramek, rozerwanych rekordow $torn)")
    else if (missing.nonEmpty) inf += s"bez rekordu (rozerwany przy resecie): trig ${missing.mkString(", ")}"
    if (nearReset > r.resets) err(s"bez rekordu przy resetach: $nearReset ramek, resetow ${r.resets}")
    if (gapRecs > 0) inf += s"rekordy dla ramek z probek zgubionych przy resetach (bez golden): $gapRecs"

    Report(crcOk, dumpOk, gold.size, gold.count(!_.known), torn, nearReset, atEnd, inf.toSeq, errs.toSeq)
  }

  /** Rekordy dla atrap (FakeFeBus): bajty rekordow po trig, jak wysylalby
    * je sprzet. */
  def records(v : FeHilVariant, ys : Long => Option[Long], first : Long, last : Long, starts : Seq[Long],
              dumpEvery : Int) : Vector[(Long, Seq[Int])] =
    golden(v, ys, first, last, starts).zipWithIndex.flatMap { case (x, k) =>
      val crc  = (x.trig, FeN4.bytes(FeN4.CrcRec(x.trig, 7, x.crc, x.exp)))
      val dump = if (dumpEvery > 0 && k > 0 && k % dumpEvery == 0) Some((x.trig, FeN4.bytes(FeN4.DumpRec(x.trig, x.powerExp, x.power)))) else None
      crc +: dump.toSeq
    }
}
