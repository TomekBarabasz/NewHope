package newhope.vertebra.hil.i2s

import java.io.File
import scala.io.Source
import newhope.i2s.I2sFormat
import newhope.vertebra.{Stage, Testpoint, TestplanSuite}
import I2sPattern.Link

// =====================================================================
//  ZRODLO PLANU
//  Kontrakt vertebra-hil (vertebra-hil.md §4, contract/i2s/pattern.md).
//  Publicznego planu nie ma; nazwy nasze, prefiks ctr_. Kazdy testpoint
//  mowi w `checking`, ktorej reguly pattern.md dotyczy.
//
//  To jest etap 1 z §10: kryterium ukonczenia to ta suita na zielono
//  i wektory w repo (ctr_vectors_fresh).
//
//  NIEZALEZNOSC
//  Oczekiwania w testach checkera sa wyprowadzone recznie ze scenariusza
//  (komentarz przy kazdym), a nie odczytane z modelu. Stale w
//  ctr_pattern_golden policzyla osobna implementacja (Python, poza repo),
//  zeby blad wspolny dla I2sPattern i I2sVectors nie przeszedl na zielono.
//
//  ODRZUCONE
//   symulacja  - kontrakt to czysta Scala; RTL generatora i checkera
//                sprawdza I2sHarnessTestplan w etapie 2 na tych wektorach
// =====================================================================
object I2sContractPlan {
  val plan : Seq[Testpoint] = Seq(
    Testpoint("ctr_pattern_layout", Stage.V1,
      "Pola slowa wzorca na wlasciwych bitach dla kazdej szerokosci",
      stimulus = Seq("W 8..32, seedy z pattern.csv, n przez zawiniecia seq i uint32"),
      checking = Seq("bit W-1 == c; pole seq == n mod 2^S; reszta == mlodsze bity hasha",
                     "slowo < 2^W; R != 0, wiec ramka nigdy nie jest cisza",
                     "S = 3 / 7 / 8 dla W = 8 / 16 / >=18")),
    Testpoint("ctr_pattern_golden", Stage.V1,
      "Wartosci slow zgodne z niezalezna implementacja",
      checking = Seq("8 slow policzonych w Pythonie == I2sPattern.word",
                     "xorshift32 z logicznym przesunieciem (0x80000000 -> znana wartosc)")),
    Testpoint("ctr_pattern_seq_wrap", Stage.V1,
      "Slowo zalezy tylko od n mod 2^S",
      checking = Seq("word(n) == word(n + 2^S) i word(n) == word(n + 2^32 - 2^S)",
                     "2^S kolejnych ramek ma parami rozne slowa w obu kanalach")),
    Testpoint("ctr_transfer", Stage.V1,
      "transfer() na przypadkach z symulacji i zgodnosc z bitowym modelem magistrali",
      stimulus = Seq("padding, slowo == slot, obciecie przez odbiornik i przez slot, rozszerzenie"),
      checking = Seq("Wartosci reczne z opisu w pattern.md",
                     "deserialize(serialize(ramki), offset 0) == transfer dla wszystkich kombinacji z transfer.csv")),
    Testpoint("ctr_link_bounds", Stage.V1,
      "Ktore polaczenia checker obsluguje i ile hasha widzi odbiorca",
      checking = Seq("checkable <=> min(wtx, slot, wrx) >= 1 + S",
                     "konfiguracje z planu §8 sa checkable; wypisane bity hasha",
                     "I2sCheckerModel odmawia polaczenia, ktore nie jest checkable")),
    Testpoint("ctr_checker_lock", Stage.V1,
      "Lock: 3 kolejne ramki, smieci i cisza przed nim sie nie licza",
      stimulus = Seq("clean, prelock (cisza, smieci, polowa ramki, cisza w potwierdzaniu), wrap, nolock"),
      checking = Seq("lock_at == indeks pierwszej ramki ciagu", "frames == liczba ramek wzorca",
                     "bez 3 kolejnych ramek: brak locka, frames == 0")),
    Testpoint("ctr_checker_gaps", Stage.V1,
      "Cisza po locku to luka i nie zuzywa numeru",
      checking = Seq("gaps == liczba cichych ramek, bad == 0, relocks == 0")),
    Testpoint("ctr_checker_discontinuity", Stage.V1,
      "Zgubiona i zdublowana ramka: bad + relock, potem dalej czysto",
      stimulus = Seq("drop i dup przy W=16, drop przy W=8 (S=3)"),
      checking = Seq("bad == 1, relocks == 1, first_err.n == numer oczekiwany",
                     "po relocku wszystkie ramki zgodne")),
    Testpoint("ctr_checker_corruption", Stage.V1,
      "Przeklamanie ramki bez utraty synchronizacji i trwale przesuniecie o bit",
      stimulus = Seq("zamiana kanalow, bit LSB, przesuniecie o bit od ramki 20"),
      checking = Seq("swap / bitflip: bad == 1, relocks == 0, numer zuzyty",
                     "bitshift: kazda ramka po przesunieciu jest bad, zadna nie daje relocka")),
    Testpoint("ctr_checker_word_length", Stage.V1,
      "Checker liczy oczekiwania przez transfer przy roznych dlugosciach slowa",
      stimulus = Seq("wtx 24 -> wrx 16, wtx 16 -> wrx 24, wtx 32 w slocie 16"),
      checking = Seq("frames == wszystkie ramki, bad == 0")),
    Testpoint("ctr_vectors_fresh", Stage.V1,
      "Wektory w contract/i2s/vectors odpowiadaja kodowi",
      checking = Seq("Kazdy plik z I2sVectors.files istnieje i jest identyczny z wygenerowanym (CRLF == LF)"))
  )
}

class I2sContractTestplan extends TestplanSuite {
  import I2sContractPlan._
  def testplan : Seq[Testpoint] = plan

  private def bit(v : Long, i : Int) : Int = ((v >> i) & 1L).toInt

  testpoint("ctr_pattern_layout") {
    assert(Seq(8, 16, 17, 18, 24, 32).map(I2sPattern.seqBits) == Seq(3, 7, 7, 8, 8, 8))
    for (seed <- I2sVectors.patternSeeds; w <- I2sPattern.MinWidth to I2sPattern.MaxWidth;
         n <- I2sVectors.patternNs(w); c <- Seq(0, 1)) {
      val v = I2sPattern.word(seed, n, c, w)
      val s = I2sPattern.seqBits(w); val h = I2sPattern.hashBits(w)
      val ctx = f"seed=$seed%08x n=$n c=$c w=$w word=$v%08x"
      assert(v >= 0 && v < (1L << w), ctx)
      assert(bit(v, w - 1) == c, s"$ctx: kanal")
      assert(((v >> h) & ((1L << s) - 1)) == (n & ((1L << s) - 1)), s"$ctx: seq")
      assert((v & ((1L << h) - 1)) == (I2sPattern.hash(n & ((1L << s) - 1), c, seed) & ((1L << h) - 1)),
             s"$ctx: hash")
      if (c == 1) assert(v != 0, s"$ctx: R == 0")
    }
  }

  testpoint("ctr_pattern_golden") {
    // (seed, n, c, W) -> slowo; policzone w Pythonie wg pattern.md.
    // seed=0, n=0, c=0 daje 0: klucz hasha 0 jest punktem stalym xorshift
    // (pattern.md, "Seed") - L moze byc zerem, R nigdy.
    val golden = Seq(
      (0x00000000L,          0L, 0,  8, 0x00000000L),
      (0x00000000L,          0L, 1,  8, 0x00000081L),
      (0x0000002aL,          1L, 0, 16, 0x0000016aL),
      (0x0000002aL,          1L, 1, 16, 0x0000814bL),
      (0xdeadbeefL,        300L, 0, 24, 0x00162b4aL),
      (0xdeadbeefL,        300L, 1, 24, 0x00960b6bL),
      (0xffffffffL, 0xffffffffL, 1, 32, 0xffc03dffL),
      (0x0000002aL,          5L, 0, 32, 0x02840462L))
    for ((seed, n, c, w, exp) <- golden) {
      val got = I2sPattern.word(seed, n, c, w)
      assert(got == exp, f"word(seed=$seed%08x, n=$n, c=$c, W=$w) = $got%08x, oczekiwane $exp%08x")
    }
    // Przesuniecie arytmetyczne (int32 w C, >> na Int w Scali) dalo by
    // tu 0xffffc000 w drugim kroku: pulapka, ktora ten przypadek wylapuje.
    val x1 = 0x80000000L                         // << 13 wypada poza 32 bity
    val x2 = x1 ^ (x1 >>> 17)                    // 0x80004000
    val x3 = x2 ^ ((x2 << 5) & I2sPattern.U32)   // 0x80084000
    assert(x2 == 0x80004000L && x3 == 0x80084000L)
    assert(I2sPattern.xorshift32(0x80000000L) == 0x80084000L)
  }

  testpoint("ctr_pattern_seq_wrap") {
    for (w <- I2sVectors.patternWidths; seed <- I2sVectors.patternSeeds; c <- Seq(0, 1)) {
      val sm = 1L << I2sPattern.seqBits(w)
      for (n <- Seq(0L, 3L, sm - 1)) {
        val v = I2sPattern.word(seed, n, c, w)
        assert(v == I2sPattern.word(seed, n + sm, c, w), s"w=$w n=$n: okres 2^S")
        assert(v == I2sPattern.word(seed, (n - sm) & I2sPattern.U32, c, w), s"w=$w n=$n: przez 2^32")
      }
      val words = (0L until sm).map(n => I2sPattern.word(seed, n, c, w))
      assert(words.distinct.size == words.size, s"w=$w c=$c: powtorzone slowo w okresie")
    }
  }

  testpoint("ctr_transfer") {
    import I2sFormat.transfer
    // Padding: 16 bitow w slocie 32, odbiornik czyta caly slot -> v << 16.
    assert(transfer(0xabcdL, 16, 32, 32) == 0xabcd0000L)
    // Slowo == slot == odbiornik: bez zmian (lsb_across_ws na poziomie slowa).
    assert(transfer(0x89abcdefL, 32, 32, 32) == 0x89abcdefL)
    // Odbiornik krotszy: zostaja MSB-y.
    assert(transfer(0x123456L, 24, 32, 16) == 0x1234L)
    // Odbiornik dluzszy: brakujace LSB-y zerami.
    assert(transfer(0x1234L, 16, 32, 24) == 0x123400L)
    // Slot krotszy niz slowo: LSB-y giną na magistrali, odbiornik dostaje zera.
    assert(transfer(0x89abcdefL, 32, 16, 32) == 0x89ab0000L)
    // Odbiornik dluzszy niz slot i slowo: zera za koncem slotu.
    assert(transfer(0xffL, 8, 8, 16) == 0xff00L)

    // Bitowy model magistrali (serialize / deserialize) == transfer.
    for (wtx <- I2sVectors.patternWidths; slot <- I2sVectors.transferSlots;
         wrx <- I2sVectors.patternWidths) {
      val raw  = I2sVectors.transferWords(wtx).grouped(2).map(p => I2sWords(p(0), p(1))).toSeq
      val bits = I2sVectors.serialize(raw, wtx, slot)
      val got  = I2sVectors.deserialize(bits, raw.size, slot, wrx, 0)
      val exp  = raw.map(f => I2sWords(transfer(f.l, wtx, slot, wrx), transfer(f.r, wtx, slot, wrx)))
      assert(got == exp, s"wtx=$wtx slot=$slot wrx=$wrx: $got != $exp")
    }
  }

  testpoint("ctr_link_bounds") {
    // Konfiguracje z planu §8 vertebra-hil.md oraz przypadki brzegowe.
    val configs = Seq(
      "48k 16 w 32"         -> Link(42, 16, 32, 16),
      "44k1 24 w 32"        -> Link(42, 24, 32, 24),
      "16/16"               -> Link(42, 16, 16, 16),
      "32/32"               -> Link(42, 32, 32, 32),
      "mismatch 24 -> 16"   -> Link(42, 24, 32, 16),
      "mismatch 16 -> 24"   -> Link(42, 16, 32, 24),
      "mismatch 32 -> 16"   -> Link(42, 32, 32, 16),
      "8 w 16"              -> Link(42,  8, 16,  8))
    for ((name, l) <- configs) {
      info(f"$name%-20s eff=${l.effBits}%2d S=${l.s} hash widoczny=${l.visibleHashBits}%2d bit(y)")
      assert(l.checkable, s"$name: ${l.label} nie jest checkable")
    }
    // Granica: odbiorca musi widziec kanal + cale pole seq.
    assert(!Link(42, 32, 32, 8).checkable)                 // 8 < 1 + 8
    assert( Link(42, 32, 32, 9).checkable && Link(42, 32, 32, 9).visibleHashBits == 0)
    assert(!Link(42, 24,  8, 24).checkable)                // slot obcina seq
    assert( Link(42, 16,  8, 16).checkable)                // S = 7: 8 bitow wystarcza
    val refused = scala.util.Try(new I2sCheckerModel(Link(42, 32, 32, 8)))
    assert(refused.isFailure, "checker przyjal polaczenie bez pelnego pola seq")
  }

  private def caseStat(name : String) : CheckerStat = {
    val c = I2sVectors.checkerCases.find(_.name == name)
              .getOrElse(throw new IllegalArgumentException(s"brak przypadku $name w I2sVectors"))
    info(s"$name (${c.link.label}, ${c.frames.size} ramek): ${c.stat}")
    c.stat
  }

  private def expectStat(name : String, frames : Long, bad : Long = 0, gaps : Long = 0,
                         relocks : Long = 0, lockAt : Long = 0) : CheckerStat = {
    val s = caseStat(name)
    assert((s.frames, s.bad, s.gaps, s.relocks, s.lockAt) == ((frames, bad, gaps, relocks, lockAt)),
      s"$name: (frames, bad, gaps, relocks, lock_at) = ${(s.frames, s.bad, s.gaps, s.relocks, s.lockAt)}, " +
      s"oczekiwane ${(frames, bad, gaps, relocks, lockAt)}")
    if (bad == 0) assert(s.firstErr.isEmpty, s"$name: first_err bez bledow")
    s
  }

  testpoint("ctr_checker_lock") {
    expectStat("clean", frames = 40)
    // 3 cisze + 2 smieci + polowa ramki = indeksy 0..5; lock od ramki 6.
    // Cisza w trakcie potwierdzania nie przerywa ciagu i nie jest luka.
    expectStat("prelock", frames = 40, lockAt = 6)
    // Start n = 2^32 - 16: przejscie przez zawiniecie uint32 i pola seq.
    expectStat("wrap", frames = 40)
    // 10 smieci, potem pary ramek wzorca przedzielone smieciem: nigdy 3 z rzedu.
    val s = expectStat("nolock", frames = 0, lockAt = -1)
    assert(!s.locked)
  }

  testpoint("ctr_checker_gaps") {
    // 40 ramek, cisza po ramce 10 i dwie po ramce 25.
    expectStat("gaps", frames = 40, gaps = 3)
  }

  testpoint("ctr_checker_discontinuity") {
    // 41 ramek bez ramki 20: 39 zgodnych po wlaczeniu relocka, 1 bad.
    val d = expectStat("drop", frames = 39, bad = 1, relocks = 1)
    assert(d.firstErr.map(_.n).contains(20L), s"drop: ${d.firstErr}")
    assert(d.firstErr.map(_.got).contains(I2sVectors.linkA.expected(21)))
    // Ramka 20 dwa razy: druga kopia to bad + relock; wszystkie 40 roznych zgodne.
    val u = expectStat("dup", frames = 40, bad = 1, relocks = 1)
    assert(u.firstErr.map(_.n).contains(21L), s"dup: ${u.firstErr}")
    assert(u.firstErr.map(_.got).contains(I2sVectors.linkA.expected(20)))
    // W = 8, S = 3: 31 ramek bez ramki 12.
    expectStat("w8_drop", frames = 29, bad = 1, relocks = 1)
  }

  testpoint("ctr_checker_corruption") {
    val sw = expectStat("swap", frames = 39, bad = 1)
    assert(sw.firstErr.map(_.n).contains(20L))
    expectStat("bitflip", frames = 39, bad = 1)
    // Od ramki 20 odbiorca czyta o bit za pozno: 20 zgodnych, 20 bad,
    // zadna przesunieta ramka nie udaje poprawnej (relocks == 0).
    val bs = expectStat("bitshift", frames = 20, bad = 20)
    assert(bs.firstErr.map(_.n).contains(20L))
  }

  testpoint("ctr_checker_word_length") {
    expectStat("trunc", frames = 40)
    expectStat("ext", frames = 40)
    expectStat("slot_trunc", frames = 40)
  }

  testpoint("ctr_vectors_fresh") {
    val dir   = new File(I2sVectors.defaultDir)
    val regen = "sbt \"hilFpga/runMain newhope.vertebra.hil.i2s.I2sVectors\""
    assert(dir.isDirectory, s"brak ${dir.getCanonicalPath} - wygeneruj: $regen")
    for ((name, exp) <- I2sVectors.files) {
      val f = new File(dir, name)
      assert(f.isFile, s"brak ${f.getCanonicalPath} - wygeneruj: $regen")
      val src = Source.fromFile(f, "US-ASCII")
      val got = try src.mkString.replace("\r\n", "\n") finally src.close()
      if (got != exp) {
        val g = got.split("\n", -1); val e = exp.split("\n", -1)
        val i = (0 until scala.math.max(g.length, e.length))
                  .find(k => g.lift(k) != e.lift(k)).getOrElse(-1)
        fail(s"$name nieaktualny, pierwsza roznica w wierszu ${i + 1}:\n" +
             s"  plik: ${g.lift(i).getOrElse("<koniec>")}\n  kod:  ${e.lift(i).getOrElse("<koniec>")}\n" +
             s"Jesli zmiana wzorca jest zamierzona: $regen, a potem przegeneruj wektory w ESP32 i Spinalu.")
      }
      info(s"$name: ${exp.count(_ == '\n') - 1} wierszy, zgodny")
    }
  }
}
