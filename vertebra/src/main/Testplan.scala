package newhope.vertebra

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable

// =====================================================================
//  Odpowiednik hw/dv/tools/dvsim/testplans/*.hjson z OpenTitana:
//  testpointy, ktore dotycza KAZDEGO IP, wyfaktoryzowane raz.
//
//  ZMIANY wzgledem poprzedniej wersji:
//   1. pakiet newhope.vertebra (byl org.newhope.vertebra, a reszta
//      biblioteki siedzi w newhope.vertebra.sim - to sie rozjezdzalo)
//   2. guard na kolejnosc inicjalizacji `testplan` w podklasie
//   3. unimplemented() bierze wariant, tak jak testpoint()
//   4. completeness wywala sie na testpointach bez testu I bez
//      unimplemented - "cichy brak" byl dotad tylko info()
//   5. Testpoint wymaga niepustego `checking`
// =====================================================================

object Stage extends Enumeration {
  val V1, V2, V3 = Value   // V1: smoke + sanity, V2: pelna funkcjonalnosc,
}                          // V3: stres, losowy reset, przypadki brzegowe

// ---------------------------------------------------------------------
//  Stimulus i checking sa rozdzielone celowo (patrz metodologia §3.4):
//  to jest wykrywacz testow, ktore cos odpalaja i nie sprawdzaja
//  niczego poza tym, ze symulacja nie padla. Pole opcjonalne niczego
//  nie wykrywa, wiec `checking` jest wymagane.
//
//  `stimulus` moze byc puste - istnieja testpointy czysto statyczne
//  (filter_window_vs_quarter_boundary liczy sie z generykow, bez
//  zadnej symulacji), ale one nadal MAJA co sprawdzac.
// ---------------------------------------------------------------------
case class Testpoint(name     : String,
                     stage    : Stage.Value,
                     desc     : String,
                     stimulus : Seq[String] = Nil,
                     checking : Seq[String] = Nil) {
  require(name.nonEmpty, "testpoint bez nazwy")
  require(checking.nonEmpty,
    s"testpoint '$name' nie ma sekcji checking - co ten test ma stwierdzic?")
}

trait TestplanSuite extends AnyFunSuite {

  /** UWAGA: w podklasie deklaruj to jako `def` albo `lazy val`.
    *
    * `val testplan = Seq(...)` inicjalizuje sie dopiero w miejscu swojej
    * deklaracji w ciele klasy, a testpoint(...) wolane wyzej zobaczy
    * jeszcze null. Guard `plan` ponizej to lapie i mowi, co zrobic. */
  def testplan : Seq[Testpoint]

  // nazwa -> warianty, w kolejnosci rejestracji (do raportu)
  private val implemented = mutable.LinkedHashMap[String, mutable.Buffer[String]]()
  // nazwa -> powod odlozenia
  private val deferred    = mutable.LinkedHashMap[String, String]()

  private def plan : Seq[Testpoint] = testplan match {
    case null => throw new IllegalStateException(
      "testplan == null w chwili rejestracji testu.\n" +
      "Przyczyna: `val testplan` w podklasie inicjalizuje sie dopiero w miejscu\n" +
      "swojej deklaracji, a testpoint(...) wolane wyzej widzi jeszcze null.\n" +
      "Napraw: zadeklaruj `def testplan` albo `lazy val testplan`.")
    case p => p
  }

  private def lookup(name : String) : Testpoint = {
    val hits = plan.filter(_.name == name)
    if (hits.isEmpty)
      throw new IllegalArgumentException(
        s"'$name' nie ma w testplanie - dopisz go najpierw " +
        s"(plan ma ${plan.size} pozycji)")
    if (hits.size > 1)
      throw new IllegalArgumentException(
        s"'$name' wystepuje w testplanie ${hits.size} razy - nazwy musza byc unikalne")
    hits.head
  }

  /** Test realizujacy testpoint z planu. Nazwa musi w nim istniec.
    *
    * variant rozroznia wielokrotne wykonania tego samego testpointu
    * (rozne konfiguracje generyka, rozne implementacje DUT-a). ScalaTest
    * wymaga unikalnych nazw testow, ale kompletnosc liczy sie po samej
    * nazwie testpointu - jedna konfiguracja wystarczy, zeby uznac go za
    * zrobiony. Czy to wlasciwa semantyka, to osobne pytanie; dzis tak. */
  def testpoint(name : String, variant : String = "")(body : => Unit) : Unit = {
    val tp = lookup(name)
    implemented.getOrElseUpdate(name, mutable.Buffer()) += variant
    val suffix = if (variant.isEmpty) "" else s" ($variant)"
    test(s"[${tp.stage}] $name$suffix") { body }
  }

  /** Testpoint znany, ale jeszcze nie zrobiony - odpowiednik
    * "No Tests Implemented" w raporcie OpenTitana. NIE liczy sie do
    * kompletnosci, celowo: zolty wpis ma bolec.
    *
    * Roznica wzgledem samego `pending`: tu nazwa jest weryfikowana
    * wzgledem planu, a completeness odroznia "swiadomie odlozone" od
    * "zapomniane". Gole `pending` daje ten sam kolor w raporcie i zadnej
    * z tych dwoch informacji. */
  def unimplemented(name : String, reason : String, variant : String = "") : Unit = {
    val tp = lookup(name)
    deferred(name) = reason
    val suffix = if (variant.isEmpty) "" else s" ($variant)"
    test(s"[${tp.stage}] $name$suffix") {
      info(reason)
      pending
    }
  }

  // -------------------------------------------------------------------
  //  Ten test jest rejestrowany w ciele traita, czyli PRZED testpointami
  //  podklasy, wiec pojdzie pierwszy. To nie szkodzi: `implemented` i
  //  `deferred` sa wypelniane podczas KONSTRUKCJI (testpoint dopisuje do
  //  zbioru od razu), a cialo testu wykonuje sie dopiero w fazie run.
  // -------------------------------------------------------------------
  test("testplan completeness") {
    val byStage = plan.groupBy(_.stage)

    Stage.values.toSeq.sortBy(_.id).foreach { s =>
      val tps = byStage.getOrElse(s, Nil)
      if (tps.nonEmpty) {
        val done = tps.count(t => implemented.contains(t.name))
        val pend = tps.count(t => deferred.contains(t.name))
        info(f"$s%-3s ${done}%2d/${tps.size}%2d zrobione, $pend%2d odlozone")
      }
    }

    implemented.toSeq.sortBy(_._1).foreach { case (n, vs) =>
      val v = vs.filter(_.nonEmpty)
      info(s"  ok:       $n" + (if (v.isEmpty) "" else v.mkString(" [", ", ", "]")))
    }
    deferred.toSeq.sortBy(_._1).foreach { case (n, r) =>
      info(s"  odlozone: $n - $r")
    }

    // Testpoint, ktory nie ma ani testu, ani unimplemented(). To jest ta
    // rzecz, ktora ma sie nie zdarzac po cichu - dopisales pozycje do
    // planu i nikt jej nie tknal.
    val ghosts = plan.map(_.name).toSet -- implemented.keySet -- deferred.keySet
    ghosts.toSeq.sorted.foreach(n => info(s"  BRAK:     $n"))

    // Prog per stage - V1 musi byc kompletny, zeby w ogole ruszyc dalej.
    val v1      = plan.filter(_.stage == Stage.V1).map(_.name).toSet
    val v1gap   = v1 -- implemented.keySet
    assert(v1gap.isEmpty,
           s"niekompletny stage V1: ${v1gap.toSeq.sorted.mkString(", ")}")

    assert(ghosts.isEmpty,
           "testpointy bez testu i bez unimplemented(...): " +
           ghosts.toSeq.sorted.mkString(", ") +
           " - dopisz test albo unimplemented(nazwa, powod)")
  }
}

// ---------------------------------------------------------------------
//  INSTRUMENTACJA
//
//  Zeby sprawdzic kontrakt Stream'a z symulacji, trzeba widziec payload
//  jako jedna wartosc. Robimy to podczas ELABORACJI - to jest ta rzecz,
//  ktorej SystemVerilog nie umie i dlatego ma `bind`.
//
//  OGRANICZENIE: musi byc wywolane wewnatrz komponentu albo w rework.
//  Dopoki I2cPhy tego nie wola, testpointy StreamConformance nie moga
//  byc dolaczone do planu I2C - patrz komentarz w I2cPhyTestplan.
// ---------------------------------------------------------------------
object Instrument {
  /** Dodaje do komponentu obserwowalna kopie payloadu strumienia. */
  def stream[T <: Data](s : Stream[T], name : String) : Bits =
    s.payload.asBits.simPublic().setName(s"${name}_payload_bits")
}

// ---------------------------------------------------------------------
//  WSPOLNE TESTPOINTY DLA PORTU Stream
// ---------------------------------------------------------------------
case class StreamPortHandle(valid   : Bool,
                            ready   : Bool,
                            payload : Bits,
                            isInput : Boolean,
                            name    : String)

object StreamConformance {

  /** stream_payload_stable
    * Payload nie moze sie zmienic dopoki valid && !ready. */
  def payloadStable(cd : ClockDomain, p : StreamPortHandle) : Unit = fork {
    var held : Option[BigInt] = None
    while (true) {
      cd.waitSampling()
      if (p.valid.toBoolean && !p.ready.toBoolean) {
        val now = p.payload.toBigInt
        held match {
          case Some(v) => assert(v == now,
            s"${p.name}: payload zmienil sie przy valid && !ready ($v -> $now)")
          case None    => held = Some(now)
        }
      } else held = None
    }
  }

  /** stream_valid_deasserted_in_reset */
  def quietDuringReset(cd : ClockDomain, p : StreamPortHandle) : Unit = fork {
    while (true) {
      cd.waitActiveEdge()        // NIE waitSampling: to filtruje zbocza
      if (cd.isResetAsserted)    // po isSamplingEnable, wiec warunek
        assert(!p.valid.toBoolean,// ponizej nigdy nie byl prawdziwy
          s"${p.name}: valid podniesione w trakcie resetu")
    }
  }

  /** stream_no_deadlock */
  def noStall(cd : ClockDomain, p : StreamPortHandle, limit : Int) : Unit = fork {
    var stuck = 0
    while (true) {
      cd.waitSampling()
      if (p.valid.toBoolean && !p.ready.toBoolean) {
        stuck += 1
        assert(stuck < limit, s"${p.name}: valid trzyma sie $stuck cykli bez ready")
      } else stuck = 0
    }
  }

  def all(cd : ClockDomain, p : StreamPortHandle, stallLimit : Int = 10000) : Unit = {
    payloadStable(cd, p)
    quietDuringReset(cd, p)
    noStall(cd, p, stallLimit)
  }

  /** Wspolne testpointy do wklejenia w testplan kazdego IP. */
  def testpoints(portName : String) : Seq[Testpoint] = Seq(
    Testpoint(s"${portName}_payload_stable", Stage.V1,
      "Payload strumienia jest stabilny przez caly czas valid && !ready",
      stimulus = Seq("Losowy backpressure na ready",
                     "Losowe opoznienia po stronie producenta"),
      checking = Seq("Payload probkowany co cykl nie zmienia sie do handshake'u")),

    Testpoint(s"${portName}_reset_quiet", Stage.V1,
      "Port nie zglasza valid w trakcie aktywnego resetu",
      stimulus = Seq("Reset asynchroniczny w losowym momencie transakcji"),
      checking = Seq("valid nisko przez caly czas trwania resetu")),

    Testpoint(s"${portName}_backpressure", Stage.V2,
      "IP dziala poprawnie przy dowolnym wzorcu ready",
      stimulus = Seq("ready sterowane losowo, wlacznie z dlugimi zerami",
                     "ready przypiete na stale do 1 (maksymalna przepustowosc)"),
      checking = Seq("Brak zgubionych i zduplikowanych transakcji",
                     "Brak zakleszczenia w limicie cykli")),

    Testpoint(s"${portName}_stress_with_rand_reset", Stage.V3,
      "Losowy reset w trakcie ruchu na porcie",
      stimulus = Seq("Reset wstrzykiwany w losowych momentach",
                     "Po resecie wznowienie normalnego ruchu"),
      checking = Seq("IP wraca do stanu jalowego",
                     "Transakcje po resecie przechodza poprawnie")))
}
