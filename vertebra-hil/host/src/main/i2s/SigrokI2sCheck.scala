package newhope.vertebra.hil.i2s

import scala.io.Source
import scala.util.Try
import I2sPattern.Link

// =====================================================================
//  Etap 3 (vertebra-hil.md §10): wyjscie ESP32 mastera zdekodowane w
//  sigroku i sprawdzone referencyjnym checkerem (I2sCheckerModel).
//
//  Nagranie i dekodowanie (analizator fx2lafw, D0 = SCK, D1 = WS, D2 = SD):
//    sigrok-cli -d fx2lafw --config samplerate=24m --samples 4m -o cap.sr
//    sigrok-cli -i cap.sr -P i2s:sck=D0:ws=D1:sd=D2 -A i2s=left:right > cap.txt
//  Sprawdzenie:
//    sbt "hil/runMain newhope.vertebra.hil.i2s.SigrokI2sCheck cap.txt --seed 0x5eed1234 --w 16 --slot 32"
//
//  Dekoder i2s sigroka (libsigrokdecode, decoders/i2s/pd.py) wysyla slowo
//  przy zmianie WS, liczac bity od poprzedniej zmiany; dla formatu Philips
//  to dokladnie caly slot, MSB pierwszy. Kanal: WS = 0 -> "Left". Sigrok
//  jest wiec odbiornikiem o Wrx = slot, a checker liczy
//  Link(seed, Wtx = w, slot, Wrx = slot): sprawdza przy okazji zera
//  paddingu. Pierwsza niepelna ramka nagrania to "smieci przed lockiem".
//
//  Linie wyjscia sigrok-cli: "i2s-1: Left channel: 0000a1b2" (albo krotsze
//  formy "Left: ...", "L: ..."). Ostrzezenia o dlugosci slowa sa liczone
//  osobno: kazde to blad formatu na linii. Wyjatek to pierwsze slowo:
//  dekoder startuje z oldws = 1, wiec nagranie zaczete przy WS = 0 albo w
//  polowie slowa daje krotkie pierwsze slowo i ostrzezenie przy drugim.
//  Pierwsze slowo i to ostrzezenie sa pomijane.
//
//  Kod wyjscia: 0 = lock, zero bledow i luk, co najmniej --min-frames;
//               1 = niezgodnosc; 2 = zle argumenty albo pusty plik.
// =====================================================================

object SigrokI2sCheck {
  val usage =
    "SigrokI2sCheck <plik|-> --seed S --w W --slot SLOT [--wrx N] [--min-frames N] [--show N]\n" +
    "  plik: wyjscie `sigrok-cli -P i2s:... -A i2s=left:right`, '-' = stdin\n" +
    "  --seed: jak w cfg ESP32 (dziesietnie albo 0x...); --w: szerokosc nadawcy\n" +
    "  --wrx: ile najstarszych bitow slotu porownywac (domyslnie caly slot)"

  private val Word = """(?:^|\s)(Left|Right|L|R)(?: channel)?:\s*([0-9a-fA-F]+)\s*$""".r.unanchored
  private val LenWarning = """Received (\d+)-bit word, expected (\d+)-bit""".r.unanchored

  case class Parsed(words : Seq[(Char, Long)], lenWarnings : Int)

  def parse(lines : Iterator[String]) : Parsed = {
    val words = Seq.newBuilder[(Char, Long)]
    var n    = 0
    var warn = 0
    lines.foreach {
      case LenWarning(_, _) => if (n > 2) warn += 1        // porownanie z pierwszym slowem
      case Word(ch, hex)    =>
        n += 1
        if (n > 1) words += ((ch.head, java.lang.Long.parseLong(hex, 16)))
      case _                =>
    }
    Parsed(words.result(), warn)
  }

  /** Pary (L, R) w kolejnosci nagrania. R bez poprzedzajacego L (poczatek
    * nagrania) jest pomijane; dwa L z rzedu daja ramke z R = 0, zeby
    * checker policzyl ja jako blad, a nie zgubil po cichu. */
  def frames(words : Seq[(Char, Long)], slot : Int, wrx : Int) : Seq[I2sWords] = {
    val shift = slot - wrx
    def rx(v : Long) = (v >>> shift) & ((1L << wrx) - 1)
    val out = Seq.newBuilder[I2sWords]
    var left : Option[Long] = None
    words.foreach {
      case ('L', v) =>
        left.foreach(l => out += I2sWords(rx(l), 0))
        left = Some(v)
      case (_, v) =>
        left.foreach(l => out += I2sWords(rx(l), rx(v)))
        left = None
    }
    out.result()
  }

  def main(args : Array[String]) : Unit = {
    val (file, opts) = parseArgs(args.toList) match {
      case Right(x) => x
      case Left(e)  => println(s"$e\n$usage"); sys.exit(2)
    }
    def num(k : String) : Option[Long] = opts.get(k).map { v =>
      Try(if (v.startsWith("0x") || v.startsWith("0X")) java.lang.Long.parseLong(v.drop(2), 16) else v.toLong)
        .getOrElse { println(s"$k=$v: to nie liczba"); sys.exit(2) }
    }
    val (seed, w, slot) = (num("--seed"), num("--w"), num("--slot")) match {
      case (Some(s), Some(w), Some(sl)) => (s, w.toInt, sl.toInt)
      case _ => println(s"--seed, --w i --slot sa wymagane\n$usage"); sys.exit(2)
    }
    val wrx       = num("--wrx").map(_.toInt).getOrElse(slot)
    val minFrames = num("--min-frames").getOrElse(100L)
    val show      = num("--show").map(_.toInt).getOrElse(8)

    val link = Try(Link(seed, w, slot, wrx)).filter(_.checkable).getOrElse {
      println(f"polaczenie seed=$seed%08x w=$w slot=$slot wrx=$wrx nie jest checkable"); sys.exit(2)
    }
    val src = if (file == "-") Source.stdin else Source.fromFile(file)
    val parsed = try parse(src.getLines()) finally src.close()
    if (parsed.words.isEmpty) {
      println(s"$file: brak slow I2S (czy to wyjscie -A i2s=left:right?)"); sys.exit(2)
    }
    val fs = frames(parsed.words, slot, wrx)
    val st = I2sCheckerModel.run(link, fs)

    println(s"${link.label}: ${parsed.words.size} slow, ${fs.size} ramek, " +
            s"ostrzezen o dlugosci slowa: ${parsed.lenWarnings}")
    println(s"  $st")
    println(s"  widoczne bity hasha: ${link.visibleHashBits}")

    // Bez locka pomagaja typowe przyczyny: zamiana kanalow albo inny seed.
    if (!st.locked) {
      // Odwrocony WS zmienia etykiety kanalow, a przez to i parowanie slow w ramki.
      val flipped = parsed.words.map { case (c, v) => (if (c == 'L') 'R' else 'L', v) }
      val swapped = I2sCheckerModel.run(link, frames(flipped, slot, wrx))
      if (swapped.locked) println(s"  po zamianie L/R jest lock ($swapped): odwrocona polaryzacja WS?")
      fs.take(show).zipWithIndex.foreach { case (f, i) =>
        val n = link.seqOf(f.l)
        println(s"  #$i got=$f exp(seq=$n)=${link.expected(n)}")
      }
    }
    st.firstErr.foreach(e => println(s"  pierwszy blad: $e"))

    val ok = st.locked && st.bad == 0 && st.gaps == 0 && st.relocks == 0 &&
             st.frames >= minFrames && parsed.lenWarnings == 0
    println(if (ok) "OK" else s"NIEZGODNE (wymagane: lock, bad = gaps = relocks = 0, frames >= $minFrames, bez ostrzezen)")
    sys.exit(if (ok) 0 else 1)
  }

  private val valueOpts = Set("--seed", "--w", "--slot", "--wrx", "--min-frames", "--show")

  private def parseArgs(args : List[String]) : Either[String, (String, Map[String, String])] = {
    @annotation.tailrec
    def go(rest : List[String], file : Option[String], acc : Map[String, String]) : Either[String, (String, Map[String, String])] =
      rest match {
        case Nil => file.toRight("brak pliku").map(_ -> acc)
        case k :: tail if k.contains("=") && valueOpts(k.takeWhile(_ != '=')) =>
          go(tail, file, acc + (k.takeWhile(_ != '=') -> k.dropWhile(_ != '=').drop(1)))
        case k :: v :: tail if valueOpts(k) => go(tail, file, acc + (k -> v))
        case k :: _ if k.startsWith("--") && k != "-" => Left(s"nieznana opcja $k")
        case f :: tail if file.isEmpty => go(tail, Some(f), acc)
        case f :: _ => Left(s"drugi plik: $f")
      }
    go(args, None, Map.empty)
  }
}
