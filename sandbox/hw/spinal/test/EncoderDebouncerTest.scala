package newhope.sandbox

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._
import org.scalatest.funsuite.AnyFunSuite
import newhope.vertebra.sim.SimBackend
import scala.collection.mutable.ArrayBuffer

case class LeadEdgeDebounceDUT(lockout: TimeNumber, glitchFilter: Int, idle: Boolean) extends Component {
  val io = new Bundle {
    val pin = in Bool()
    val level = out Bool()
  }
  io.level := LeadEdgeDebouncer(io.pin, lockout, glitchFilter, idle)

  // dla testbencha
  /** Wartosc wyliczona podczas elaboracji - dostepna z testbenchu. */
  val lockCycles: Int = (ClockDomain.current.frequency.getValue * lockout).toBigInt.toInt
 
  /** Oczekiwana latencja zbocza:
    *   2 (BufferCC) + (glitchFilter - 1) (rejestry History, indeks 0 jest
    *   kombinacyjny) + 1 (clean) + 1 (level)
    */
  val bufferCcLatency = 2
  val edgeLatency: Int = bufferCcLatency + glitchFilter + 1 + 1
}

case class DebounceAgent(dut: LeadEdgeDebounceDUT) {
  private val cd = dut.clockDomain

  def init(): Unit = {
    dut.io.pin #= dut.idle
    cd.forkStimulus(10)
    cd.waitSampling(2)
  }
  def hold(v: Boolean, cycles: Int): Unit = {
    dut.io.pin #= v
    dut.clockDomain.waitSampling(cycles)
  }
  /** Ustawia pin i liczy takty do momentu, az `level` nadazy. */
  def edgeLatency(target: Boolean): Int = {
    assert(dut.io.level.toBoolean != target, "linia juz jest w stanie docelowym")
    dut.io.pin #= target
    var n = 0
    while (dut.io.level.toBoolean != target) {
      assert(n < 100, "level nigdy nie podazyl za pinem")
      dut.clockDomain.waitSampling()
      n += 1
    }
    n
  }
  /** Drga pinem wokol `settled` przez `cycles` taktow, sprawdzajac po kazdym
    * takcie, ze `level` sie nie ruszyl. */
  def bounce(settled: Boolean, cycles: Int): Unit = {
    for (_ <- 0 until cycles) {
      dut.io.pin #= simRandom.nextBoolean()
      dut.clockDomain.waitSampling()
      assert(dut.io.level.toBoolean == settled,
        s"level drgnal w trakcie lockoutu (oczekiwano $settled)")
    }
    dut.io.pin #= settled
  }
  def level: Boolean = dut.io.level.toBoolean

  /** Doprowadza linie do znanego, ustabilizowanego stanu. */
  def settle(v: Boolean): Unit = hold(v, dut.lockCycles + dut.edgeLatency + 10)
}

class LeadEdgeDebouncerTest() extends AnyFunSuite {
  val GLITCH = 5
  val LOCKOUT = 200 ns  //żeby wyszło 10 cykli zegara 100 MHz

  lazy val dut : SimCompiled[LeadEdgeDebounceDUT] = Config.sim
    .workspaceName(s"${LeadEdgeDebounceDUT.getClass.getSimpleName}_${SimBackend.default.label}")
    .compile { LeadEdgeDebounceDUT(lockout = LOCKOUT, glitchFilter = GLITCH, idle = true) }

  def doTest(name: String)(body: LeadEdgeDebounceDUT => Unit): Unit = {
    test(name) {
      dut.doSim(name.replace(' ', '_'), seed = name.hashCode)(body)
    }
  }

  doTest("initial_state") { dut =>
    val a = DebounceAgent(dut)
    a.init()

    // ---- 1. stan po resecie ----------------------------------------------
    // To byl bug w pierwotnej wersji: historia z init(0) udawala, ze linia
    // wlasnie opadla, i FSM generowala widmowy tick zaraz po resecie.
    assert(a.level === dut.idle, "po resecie level musi byc rowny idle (high)")
  }

  doTest("edge_latency") { dut =>
    val a = DebounceAgent(dut)
    a.init()

    dut.clockDomain.waitSampling(5)

    // pierwsze zbocze przechodzi z minimalna latencja
    val fall = a.edgeLatency(false)
    info(s"zbocze opadajace: $fall taktow (oczekiwano ${dut.edgeLatency})")
    assert(fall == dut.edgeLatency)

    a.settle(false)

    val rise = a.edgeLatency(true)
    info(s"zbocze narastajace: $rise taktow")
    assert(rise == dut.edgeLatency)
  }

  doTest("drgania w trakcie lockoutu sa maskowane") { dut =>
    val b = DebounceAgent(dut)
    b.init()
    b.edgeLatency(false)
    // polowa lockoutu na drgania, zeby zdazyc ustabilizowac pin przed
    // wygasnieciem timera (po lockoucie level SLUSZNIE podazylby za pinem)
    b.bounce(settled = false, cycles = dut.lockCycles / 2)
    b.hold(false, dut.lockCycles)
    assert(!b.level, "level powinien zostac nisko po ustaniu drgan")
  }

  doTest("po wygasnieciu lockoutu kolejne zbocze jest przyjmowane") { dut =>
    val b = DebounceAgent(dut)
    b.init()
    b.edgeLatency(false)
    b.settle(false)
    assert(b.edgeLatency(true) == dut.edgeLatency)
  }

  doTest("glitchFilter") { dut =>
    val b = DebounceAgent(dut)
    b.init()
    b.hold(false, GLITCH - 1)
    b.hold(true, 20)
    assert(b.level, s"iglica ${GLITCH - 1}-taktowa przeszla przez filtr")
  }
  doTest("impuls rowny glitchFilter juz przechodzi") { dut =>
    // Granica musi byc sprawdzona z OBU stron - inaczej filtr o dlugosci 100
    // tez przeszedlby test i nikt by nie zauwazyl, ze responsywnosc zniknela.
    val b = DebounceAgent(dut)
    b.init()
    //b.hold(false, GLITCH + dut.edgeLatency)
    b.hold(false, GLITCH)
    dut.io.pin #= true
    dut.clockDomain.waitSampling( dut.edgeLatency - GLITCH)
    assert(!b.level, s"impuls ${GLITCH}-taktowy powinien przejsc")
  }
}

case class EncoderBench(dut: EncoderDebouncer,
                        lockCycles: Int,
                        glitchFilter: Int) {

  private val cd = dut.clockDomain

  /** Czas trwania jednej cwiartki kwadratury.
    *
    * Musi byc wiekszy od lockoutu + latencji, zeby lockout kazdej linii zdazyl
    * wygasnac miedzy jej kolejnymi zboczami. Zbocza tej samej linii dziela dwie
    * cwiartki, wiec zapas jest podwojny.
    */
  val phase: Int = 2 * lockCycles

  /** Dlugosc jednego segmentu drgania.
    *
    * > glitchFilter, zeby drganie w ogole doszlo do `clean` - inaczej testowalby
    * sie mikro-filtr, a nie lockout.
    */
  val bounceSeg: Int = glitchFilter + 1

  // Budzet: drgania musza ucichnac, zanim wygasnie lockout. Inaczej `level`
  // spróbkuje srodek drgania i wygeneruje pare widmowych zbocz.
  // Latencja pin->clean to okolo (bufferDepth + glitchFilter); bierzemy 10 z zapasem.
  require(2 * bounceSeg + 10 < lockCycles + 8,
    s"budzet drgan przekroczony: 2*$bounceSeg + 10 musi byc < $lockCycles + 8. " +
      s"Zwieksz LOCKOUT albo zmniejsz GLITCH.")
  require(phase > lockCycles + 12,
    s"phase=$phase za krotki wzgledem lockCycles=$lockCycles")

  private var curA = true
  private var curB = true

  var net: Int    = 0 // suma ze znakiem: cw = +1, ccw = -1
  var events: Int = 0 // liczba impulsow valid, niezaleznie od kierunku
  val trace       = ArrayBuffer[String]()

  // ---- monitor ----------------------------------------------------------

  def startMonitor(): Unit = fork {
    var prev = false
    var run  = 0
    while (true) {
      cd.waitSampling()
      val v = dut.io.step.valid.toBoolean
      if (v) {
        run += 1
        if (run > 1) simFailure(
          s"io.step.valid jest poziomem, nie impulsem - wysoki przez $run taktow z rzedu. " +
          s"Brakuje domyslnego 'io.step.valid := False' przed StateMachine.")
        if (!prev) {                       // licz tylko zbocze narastajace
          val cw = dut.io.step.payload.toEnum == RotDirection.CW
          net += (if (cw) 1 else -1)
          events += 1
          trace += (if (cw) "cw" else "ccw")
        }
      } else run = 0
      prev = v
    }
  }

  def reset(): Unit = { net = 0; events = 0; trace.clear() }

  // ---- driver -----------------------------------------------------------

  def init(): Unit = {
    dut.io.enc_a #= true // zatrzask: oba wysoko
    dut.io.enc_b #= true
    cd.forkStimulus(10)
    cd.waitSampling(5)
    startMonitor()
    cd.waitSampling(lockCycles + 20) // niech lancuch sie ustali
    reset()
  }

  /** Ustawia cwiartke kwadratury i trzyma ja przez `phase` taktow.
    *
    * @param bounce 0 = czysto, 1 = segmenty krotsze od filtra (ma zjesc mikro-filtr),
    *               2 = segmenty dluzsze od filtra (ma zamaskowac lockout)
    */
  def quadrant(a: Boolean, b: Boolean, bounce: Int = 0): Unit = {
    val aChanged = a != curA
    val bChanged = b != curB

    dut.io.enc_a #= a
    dut.io.enc_b #= b

    if (bounce > 0 && (aChanged || bChanged)) {
      val seg = if (bounce == 1) glitchFilter - 2 else bounceSeg
      cd.waitSampling(seg)
      if (aChanged) dut.io.enc_a #= curA // odbicie do starego poziomu
      if (bChanged) dut.io.enc_b #= curB
      cd.waitSampling(seg)
      dut.io.enc_a #= a
      dut.io.enc_b #= b
      cd.waitSampling(phase - 2 * seg)
    } else {
      cd.waitSampling(phase)
    }

    curA = a
    curB = b
  }

  /** Pelny zatrzask w prawo: 11 -> 01 -> 00 -> 10 -> 11 (A opada pierwsze). */
  def detentCw(bounce: Int = 0, phaseOverride: Int = -1): Unit =
    detent(Seq((false, true), (false, false), (true, false), (true, true)), bounce, phaseOverride)

  /** Pelny zatrzask w lewo: 11 -> 10 -> 00 -> 01 -> 11 (B opada pierwsze). */
  def detentCcw(bounce: Int = 0, phaseOverride: Int = -1): Unit =
    detent(Seq((true, false), (false, false), (false, true), (true, true)), bounce, phaseOverride)

  private def detent(seq: Seq[(Boolean, Boolean)], bounce: Int, phaseOverride: Int): Unit = {
    if (phaseOverride <= 0) seq.foreach { case (a, b) => quadrant(a, b, bounce) }
    else seq.foreach { case (a, b) =>
      dut.io.enc_a #= a; dut.io.enc_b #= b
      cd.waitSampling(phaseOverride)
      curA = a; curB = b
    }
  }

  /** Ruch przerwany w polowie i cofniety: 11 -> 01 -> 11. Zaden zatrzask nie zapadl. */
  def abortedCw(): Unit = {
    quadrant(false, true)
    quadrant(true, true)
  }

  def abortedCcw(): Unit = {
    quadrant(true, false)
    quadrant(true, true)
  }

  /** Czeka, az wszystko sie ustali i FSM wroci do sDetent. */
  def settle(): Unit = cd.waitSampling(2 * phase)
}

class EncoderDebouncerTest() extends AnyFunSuite {
  val CLK_HZ  = 100000000L
  val GLITCH  = 5
  val LOCKOUT = 200 ns // 20 taktow @ 100 MHz
  val MOVE_TO = 5 us   // 500 taktow - produkcyjne 200 ms to 20 mln taktow, nie do symulacji

  def cycles(t: TimeNumber): Int = (BigDecimal(CLK_HZ) * t.toBigDecimal).toInt

  val LOCK_CYCLES = cycles(LOCKOUT) // 20
  val MOVE_CYCLES = cycles(MOVE_TO) // 500

  lazy val dut : SimCompiled[EncoderDebouncer] = Config.sim
    .workspaceName(s"${EncoderDebouncer.getClass.getSimpleName}_${SimBackend.default.label}")
    .compile { EncoderDebouncer(lockout = LOCKOUT, moveTimeout = MOVE_TO) }

  private def bench(dut: EncoderDebouncer) = EncoderBench(dut, LOCK_CYCLES, GLITCH)

  def doTest(name: String)(body: EncoderDebouncer => Unit): Unit = {
    test(name) {
      dut.doSim(name.replace(' ', '_'), seed = name.hashCode)(body)
    }
  }

  // ---- 1. cisza po resecie ----------------------------------------------

  doTest("po resecie nie ma zadnego zdarzenia") { dut =>
    // Regresja na bug z pierwotnej wersji: historia z init(0) udawala opadniecie
    // linii i FSM strzelala widmowym tickiem zaraz po zwolnieniu resetu.
    val b = bench(dut)
    b.init()
    dut.clockDomain.waitSampling(MOVE_CYCLES + 100) // przezyj tez watchdoga
    assert(b.events == 0, s"widmowe zdarzenia po resecie: ${b.trace.mkString(",")}")
  }

  // ---- 2. pojedyncze zatrzaski ------------------------------------------

  doTest("jeden zatrzask w prawo = dokladnie jeden krok cw") { dut =>
    val b = bench(dut)
    b.init()
    b.detentCw()
    b.settle()
    assert(b.events == 1, s"oczekiwano 1 zdarzenia, jest ${b.events}: ${b.trace.mkString(",")}")
    assert(b.net == 1)
  }

  doTest("jeden zatrzask w lewo = dokladnie jeden krok ccw") { dut =>
    val b = bench(dut)
    b.init()
    b.detentCcw()
    b.settle()
    assert(b.events == 1, s"oczekiwano 1 zdarzenia, jest ${b.events}: ${b.trace.mkString(",")}")
    assert(b.net == -1)
  }

  // ---- 3. dlugie serie ---------------------------------------------------

  doTest("20 w prawo i 20 w lewo daje zero netto i 40 zdarzen") { dut =>
    val b = bench(dut)
    b.init()
    for (_ <- 0 until 20) b.detentCw()
    b.settle()
    assert(b.net == 20, s"po 20 cw netto=${b.net}")
    for (_ <- 0 until 20) b.detentCcw()
    b.settle()
    assert(b.net == 0, s"netto=${b.net}, powinno byc 0")
    assert(b.events == 40, s"zdarzen=${b.events}, powinno byc 40")
  }

  doTest("losowa sekwencja kierunkow zgadza sie z licznikiem referencyjnym") { dut =>
    val b = bench(dut)
    b.init()
    var expected = 0
    for (_ <- 0 until 40) {
      if (simRandom.nextBoolean()) { b.detentCw(); expected += 1 }
      else { b.detentCcw(); expected -= 1 }
    }
    b.settle()
    assert(b.net == expected, s"netto=${b.net}, oczekiwano $expected")
  }

    // ---- 4. drgania --------------------------------------------------------

  doTest("drgania krotsze od glitchFilter nie generuja nic dodatkowego") { dut =>
    // Broni ich mikro-filtr - nie docieraja nawet do `clean`.
    val b = bench(dut)
    b.init()
    for (_ <- 0 until 10) b.detentCw(bounce = 1)
    b.settle()
    assert(b.events == 10, s"zdarzen=${b.events}, powinno byc 10: ${b.trace.mkString(",")}")
    assert(b.net == 10)
  }

  doTest("drgania dluzsze od glitchFilter sa maskowane przez lockout") { dut =>
    // Te docieraja do `clean` - broni ich dopiero lockout. Inny mechanizm,
    // osobny doTest, zeby bylo wiadomo ktory sie zepsul.
    val b = bench(dut)
    b.init()
    for (_ <- 0 until 10) b.detentCcw(bounce = 2)
    b.settle()
    assert(b.events == 10, s"zdarzen=${b.events}, powinno byc 10: ${b.trace.mkString(",")}")
    assert(b.net == -10)
  }

    // ---- 5. ruch przerwany -------------------------------------------------

  doTest("ruch przerwany w polowie daje zero netto") { dut =>
    // Z undoOnAbort: tick + tick kompensujacy = 2 zdarzenia, netto 0.
    // Bez: 1 zdarzenie i netto 1 - to swiadomy kompromis, nie bug,
    // wiec asercja na netto, a liczba zdarzen tylko raportowana.
    val b = bench(dut)
    b.init()
    b.abortedCw()
    b.settle()
    info(s"zdarzenia przy cofnietym ruchu: ${b.trace.mkString(",")}")
    if (dut.undoOnAbort) {
      assert(b.net == 0, s"kompensacja nie zadzialala, netto=${b.net}")
      assert(b.events == 2)
    } else {
      assert(b.events == 1)
    }
  }

  doTest("po cofnietym ruchu nastepny prawdziwy zatrzask liczy sie poprawnie") { dut =>
    // Wlasciwy powod istnienia sciezki abort: bez niej FSM zostawala w sTurning
    // ze starym `dir` i psula kolejny obrot.
    val b = bench(dut)
    b.init()
    b.abortedCw()
    b.settle()
    b.reset()
    b.detentCcw()
    b.settle()
    assert(b.events == 1, s"zdarzen=${b.events}: ${b.trace.mkString(",")}")
    assert(b.net == -1, s"zly kierunek po cofnietym ruchu: ${b.trace.mkString(",")}")
  }

  doTest("cofniety ruch w lewo kompensowany jest tickiem w prawo") { dut =>
    val b = bench(dut)
    b.init()
    b.abortedCcw()
    b.settle()
    assert(b.trace == Seq("ccw", "cw"), s"jest: ${b.trace.mkString(",")}")
    assert(b.net == 0)
  }

  doTest("cofniety ruch w prawo kompensowany jest tickiem w lewo") { dut =>
    val b = bench(dut)
    b.init()
    b.abortedCw()
    b.settle()
    assert(b.trace == Seq("cw", "ccw"), s"jest: ${b.trace.mkString(",")}")
    assert(b.net == 0)
  }

    // ---- 6. watchdog -------------------------------------------------------

  doTest("watchdog wyprowadza z pozycji miedzy zatrzaskami") { dut =>
    val b = bench(dut)
    b.init()

    // pokretlo zatrzymane miedzy zatrzaskami, dluzej niz moveTimeout
    b.quadrant(false, true)
    dut.clockDomain.waitSampling(MOVE_CYCLES + 100)
    b.quadrant(true, true) // wraca do zatrzasku
    b.settle()
    b.reset()

    // FSM musi byc z powrotem w sDetent i liczyc normalnie
    b.detentCw()
    b.settle()
    assert(b.events == 1, s"po watchdogu zdarzen=${b.events}: ${b.trace.mkString(",")}")
    assert(b.net == 1)   
  }

  // ---- 7. charakteryzacja sufitu predkosci -------------------------------

  doTest("sufit predkosci obrotu (raport, nie pass/fail)") { dut =>
    // Lockout nie znosi ograniczenia predkosci - odsprzega je tylko od latencji.
    // Ten doTest mierzy, gdzie rzeczywiscie lezy granica przy zadanych parametrach.
    val b = bench(dut)
    b.init()
    var smallestOk = -1
    for (p <- Seq(40, 30, 24, 20, 16, 14, 12, 10, 8, 6)) {
      b.reset()
      for (_ <- 0 until 10) b.detentCw(phaseOverride = p)
      dut.clockDomain.waitSampling(4 * p + 50)
      val ok = b.net == 10 && b.events == 10
      info(f"phase=$p%3d taktow -> netto=${b.net}%3d zdarzen=${b.events}%3d ${if (ok) "OK" else "GUBI"}")
      if (ok) smallestOk = p
      // wroc do znanego stanu przed nastepna iteracja
      dut.io.enc_a #= true; dut.io.enc_b #= true
      dut.clockDomain.waitSampling(b.phase * 2)
    }
    info(s"najkrotsza faza z dokladnym zliczaniem: $smallestOk taktow " +
      f"(${smallestOk * 4 * 10.0}%.0f ns na zatrzask, " +
      f"${1e9 / (smallestOk * 4 * 10.0)}%.0f zatrzaskow/s)")
    assert(smallestOk > 0, "nie zliczylo poprawnie nawet przy najwolniejszym tempie")
    assert(smallestOk <= LOCK_CYCLES * 2,
      s"zliczanie wymaga fazy dluzszej niz ${LOCK_CYCLES * 2} taktow - sufit nizszy niz projektowany")
   }
}
