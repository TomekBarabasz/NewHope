package org.newhope.i2c

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import scala.util.Random

// =====================================================================
//  Coverage-guided fuzzing dla I2cPhy.
//
//  Idea (AFL przeniesione na testbench):
//    1. korpus sekwencji komend, na starcie kilka recznych ziaren
//    2. wez losowego rodzica, zmutuj
//    3. odpal symulacje, zbierz punkty pokrycia
//    4. jesli mutant odwiedzil COKOLWIEK nowego -> dopisz do korpusu
//    5. powtarzaj az pokrycie sie domknie albo skoncza sie proby
//
//  Cala roznica wzgledem zwyklego losowania jest w kroku 4: korpus
//  rosnie tylko o wejscia, ktore czegos dowiodly, wiec mutacje startuja
//  z coraz glebszych stanow zamiast zawsze od zera.
// =====================================================================

// ---------------------------------------------------------------------
//  Punkt pokrycia. Krzyz (komenda x cwiartka x warunki magistrali) plus
//  osobno przejscia miedzy komendami - to drugie lapie bledy w tablicy,
//  ktorych pierwsze nie zobaczy (np. zly stan wyjsciowy po STOP).
// ---------------------------------------------------------------------
sealed trait CovPoint
case class QuarterCov(cmd : String, phase : Int,
                      stretching : Boolean, sdaPulled : Boolean) extends CovPoint
case class TransitionCov(from : String, to : String) extends CovPoint

class CoverageDb {
  private val seen = mutable.Set[CovPoint]()

  def sample(p : CovPoint) : Unit = seen += p
  def size : Int = seen.size
  def snapshot : Set[CovPoint] = seen.toSet
  def contains(p : CovPoint) : Boolean = seen(p)

  def merge(other : Set[CovPoint]) : Int = {
    val before = seen.size
    seen ++= other
    seen.size - before
  }

  // Przestrzen docelowa liczona z tablicy cwiartek - nie z sufitu.
  def goal(cmds : Seq[String], quarters : Int) : Set[CovPoint] = {
    val q = for {
      c <- cmds; p <- 0 until quarters
      s <- Seq(false, true); d <- Seq(false, true)
    } yield QuarterCov(c, p, s, d)
    val t = for { a <- cmds; b <- cmds } yield TransitionCov(a, b)
    (q ++ t).toSet
  }

  def missing(goalSet : Set[CovPoint]) : Set[CovPoint] = goalSet -- seen
}

// ---------------------------------------------------------------------
//  Wejscie testowe: sekwencja komend + harmonogram zaklocen magistrali.
// ---------------------------------------------------------------------
case class Step(mode : String, data : Boolean,
                stretchCycles : Int, sdaPullCycles : Int)

case class TestCase(steps : Seq[Step]) {
  def mutate(rng : Random, maxStretch : Int) : TestCase = {
    val s = steps.toBuffer
    rng.nextInt(5) match {
      case 0 if s.nonEmpty =>                       // podmien tryb komendy
        val i = rng.nextInt(s.size)
        s(i) = s(i).copy(mode = TestCase.modes(rng.nextInt(TestCase.modes.size)))
      case 1 if s.nonEmpty =>                       // przelacz bit danych
        val i = rng.nextInt(s.size)
        s(i) = s(i).copy(data = !s(i).data)
      case 2 if s.nonEmpty =>                       // zmien zaklocenie
        val i = rng.nextInt(s.size)
        s(i) = s(i).copy(stretchCycles = rng.nextInt(maxStretch),
                         sdaPullCycles = rng.nextInt(maxStretch))
      case 3 =>                                     // wstaw krok
        val i = if (s.isEmpty) 0 else rng.nextInt(s.size)
        s.insert(i, TestCase.randomStep(rng, maxStretch))
      case _ if s.size > 1 =>                       // usun krok
        s.remove(rng.nextInt(s.size))
      case _ => ()
    }
    TestCase(s.toSeq)
  }
}

object TestCase {
  val modes = Seq("START", "BIT", "STOP")

  def randomStep(rng : Random, maxStretch : Int) = Step(
    mode          = modes(rng.nextInt(modes.size)),
    data          = rng.nextBoolean(),
    stretchCycles = if (rng.nextInt(4) == 0) rng.nextInt(maxStretch) else 0,
    sdaPullCycles = if (rng.nextInt(4) == 0) rng.nextInt(maxStretch) else 0)

  // Ziarna: sensowne transakcje, zeby fuzzer nie zaczynal od zera.
  def seeds : Seq[TestCase] = Seq(
    TestCase(Seq(Step("START", true, 0, 0), Step("STOP", true, 0, 0))),
    TestCase(Seq(Step("START", true, 0, 0)) ++
             Seq.fill(8)(Step("BIT", true, 0, 0)) ++
             Seq(Step("STOP", true, 0, 0))),
    TestCase(Seq(Step("START", true, 0, 0), Step("BIT", false, 20, 0),
                 Step("START", true, 0, 0), Step("STOP", true, 0, 0))))
}

// ---------------------------------------------------------------------
//  Uruchomienie jednego przypadku i zebranie pokrycia.
//
//  WYMAGA: w I2cPhyTable dopisac .simPublic() do seq.phase, seq.active
//  i cmdReg (albo do odpowiednikow w wersji FSM). Bez tego z zewnatrz
//  widac tylko piny i nie da sie probkowac cwiartek.
// ---------------------------------------------------------------------
class FuzzRunner(dut : SimCompiled[I2cPhyBase], g : I2cGenerics) {

  def run(tc : TestCase, seed : Int) : Set[CovPoint] = {
    val local = mutable.Set[CovPoint]()

    dut.doSim("fuzz", seed = seed) { d =>
      val bus = new I2cBusModel(d)
      d.io.cmd.valid #= false
      d.io.pins.scl.read #= true
      d.io.pins.sda.read #= true
      d.clockDomain.forkStimulus(period = 10)
      bus.start()
      d.clockDomain.waitSampling(5)

      var prevMode = "IDLE"

      for (step <- tc.steps) {
        // zaklocenia rownolegle z komenda
        if (step.stretchCycles > 0) fork {
          d.clockDomain.waitSamplingWhere(d.io.pins.scl.write.toBoolean)
          bus.stretch(step.stretchCycles)
        }
        if (step.sdaPullCycles > 0) fork {
          bus.sdaPull = true
          d.clockDomain.waitSampling(step.sdaPullCycles)
          bus.sdaPull = false
        }

        // probkowanie cwiartek w tle
        val sampler = fork {
          while (true) {
            d.clockDomain.waitSampling()
            if (d.seqActive.toBoolean) {
              local += QuarterCov(step.mode, d.seqPhase.toInt,
                                  !bus.scl && d.io.pins.scl.write.toBoolean,
                                  !bus.sda && d.io.pins.sda.write.toBoolean)
            }
          }
        }

        local += TransitionCov(prevMode, step.mode)
        prevMode = step.mode

        d.io.cmd.valid        #= true
        d.io.cmd.payload.mode #= I2cPhyCmdMode.elements
                                   .find(_.toString == step.mode).get
        d.io.cmd.payload.data #= step.data
        d.clockDomain.waitSamplingWhere(d.io.cmd.ready.toBoolean)
        d.io.cmd.valid #= false

        sampler.terminate()
      }
      d.clockDomain.waitSampling(20)
    }
    local.toSet
  }
}

// ---------------------------------------------------------------------
//  Petla
// ---------------------------------------------------------------------
abstract class I2cPhyFuzz(label : String,
                          build : I2cGenerics => I2cPhyBase) extends AnyFunSuite {

  val g          = I2cGenerics(100 MHz, 1 MHz)
  val iterations = 400

  lazy val dut : SimCompiled[I2cPhyBase] = Config.sim
    .workspaceName(s"${label}_fuzz")
    .compile { build(g) }

  test("coverage closure") {
    val rng    = new Random(0)
    val db     = new CoverageDb
    val goal   = db.goal(TestCase.modes, quarters = 4)
    val corpus = mutable.ArrayBuffer[TestCase]() ++ TestCase.seeds
    val runner = new FuzzRunner(dut, g)

    for (tc <- TestCase.seeds) db.merge(runner.run(tc, rng.nextInt()))

    var i = 0
    while (i < iterations && db.missing(goal).nonEmpty) {
      val parent = corpus(rng.nextInt(corpus.size))
      val child  = parent.mutate(rng, maxStretch = 6 * g.quarterCycles)
      val gained = db.merge(runner.run(child, rng.nextInt()))
      if (gained > 0) {
        corpus += child
        println(s"[$i] +$gained punktow, pokrycie ${db.size}/${goal.size}, " +
                s"korpus ${corpus.size}")
      }
      i += 1
    }

    val missing = db.missing(goal)
    if (missing.nonEmpty) {
      println(s"NIEPOKRYTE (${missing.size}):")
      missing.toSeq.sortBy(_.toString).foreach(p => println(s"  $p"))
    }
    // Czesc punktow jest nieosiagalna z definicji (np. START w cwiartce 4
    // przy 4-cwiartkowej tablicy). Prog dobrac po pierwszym przebiegu.
    assert(db.size.toDouble / goal.size > 0.7,
           s"pokrycie ${db.size}/${goal.size} za niskie")
  }
}

class I2cPhyTableFuzz extends I2cPhyFuzz("table", g => I2cPhyTable(g))
