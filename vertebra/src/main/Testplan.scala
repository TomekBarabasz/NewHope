package vertebra

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import scala.util.Random

// =====================================================================
//  Odpowiednik hw/dv/tools/dvsim/testplans/*.hjson z OpenTitana:
//  testpointy, ktore dotycza KAZDEGO IP, wyfaktoryzowane raz.
//
//  OpenTitan faktoryzuje po CSR / TL-UL / interrupts, bo to jego wspolny
//  mianownik. Nasz wspolny mianownik to bundle z spinal.lib.
// =====================================================================

// ---------------------------------------------------------------------
//  1. TESTPLAN JAKO DANE
//
//  Testplan jest osobnym artefaktem od testow. Piszesz go PRZED kodem,
//  a testy sie do niego odwoluja. Niezaimplementowane zostaja widoczne.
// ---------------------------------------------------------------------
object Stage extends Enumeration {
  val V1, V2, V3 = Value   // V1: smoke + sanity, V2: pelna funkcjonalnosc,
}                          // V3: stres, losowy reset, przypadki brzegowe

case class Testpoint(name     : String,
                     stage    : Stage.Value,
                     desc     : String,
                     stimulus : Seq[String] = Nil,
                     checking : Seq[String] = Nil)

trait TestplanSuite extends AnyFunSuite {

  def testplan : Seq[Testpoint]

  private val implemented = mutable.Set[String]()

  /** Test realizujacy testpoint z planu. Nazwa musi w nim istniec. */
  def testpoint(name : String)(body : => Unit) : Unit = {
    require(testplan.exists(_.name == name),
            s"'$name' nie ma w testplanie - dopisz go najpierw")
    implemented += name
    val tp = testplan.find(_.name == name).get
    test(s"[${tp.stage}] $name") { body }
  }

  /** Testpoint znany, ale jeszcze nie zrobiony - odpowiednik
    * "No Tests Implemented" w raporcie OpenTitana. */
  def unimplemented(name : String, reason : String) : Unit = {
    require(testplan.exists(_.name == name), s"'$name' nie ma w testplanie")
    val tp = testplan.find(_.name == name).get
    test(s"[${tp.stage}] $name") {
      info(reason)
      pending
    }
  }

  test("testplan completeness") {
    val declared = testplan.map(_.name).toSet
    val missing  = declared -- implemented
    info(s"zaimplementowane: ${implemented.size}/${declared.size}")
    missing.toSeq.sorted.foreach(n => info(s"  brak: $n"))
    // Prog per stage - V1 musi byc kompletny, zeby w ogole ruszyc dalej.
    val v1 = testplan.filter(_.stage == Stage.V1).map(_.name).toSet
    assert((v1 -- implemented).isEmpty,
           s"niekompletny stage V1: ${(v1 -- implemented).mkString(", ")}")
  }
}

// ---------------------------------------------------------------------
//  2. INSTRUMENTACJA
//
//  Zeby sprawdzic kontrakt Stream'a z symulacji, trzeba widziec payload
//  jako jedna wartosc. Robimy to podczas ELABORACJI - to jest ta rzecz,
//  ktorej SystemVerilog nie umie i dlatego ma `bind`.
// ---------------------------------------------------------------------
object Instrument {
  /** Dodaje do komponentu obserwowalna kopie payloadu strumienia. */
  def stream[T <: Data](s : Stream[T], name : String) : Bits = {
    val bits = s.payload.asBits.simPublic().setName(s"${name}_payload_bits")
    bits
  }
}

// ---------------------------------------------------------------------
//  3. WSPOLNE TESTPOINTY DLA PORTU Stream
//
//  Odpowiednik csr_testplan.hjson: dolaczasz do dowolnego IP i dostajesz
//  komplet testow kontraktu za darmo.
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

  /** stream_valid_deasserted_in_reset
    * Zaden port nie moze zglaszac valid w trakcie resetu. */
  def quietDuringReset(cd : ClockDomain, p : StreamPortHandle) : Unit = fork {
    while (true) {
      cd.waitSampling()
      if (cd.isResetAsserted) assert(!p.valid.toBoolean,
        s"${p.name}: valid podniesione w trakcie resetu")
    }
  }

  /** stream_no_deadlock
    * Przy losowym backpressure transakcja musi w koncu przejsc. */
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

// ---------------------------------------------------------------------
//  4. PRZYKLAD UZYCIA
// ---------------------------------------------------------------------
//
//  class MyIpSuite extends TestplanSuite {
//
//    val testplan =
//      StreamConformance.testpoints("cmd") ++
//      StreamConformance.testpoints("rsp") ++
//      Seq(
//        Testpoint("smoke", Stage.V1, "Podstawowa transakcja end-to-end",
//          stimulus = Seq("..."), checking = Seq("...")),
//        Testpoint("protocol_timing", Stage.V2, "...")
//      )
//
//    testpoint("cmd_payload_stable") { dut.doSim { d =>
//      StreamConformance.all(d.clockDomain, cmdHandle(d)); ... } }
//
//    unimplemented("cmd_stress_with_rand_reset",
//                  "brak jeszcze wsparcia dla resetu w trakcie transakcji")
//  }
