import spinal.core._
import spinal.core.sim._
import spinal.lib.{math => _, _}   // wszystko z spinal.lib poza math

import scala.collection.mutable.ArrayBuffer

//  y[n] = x[n] - x[n-1] + a * y[n-1],  a = 1 - k,  k = 2*pi*fc/fs
//  a*Y = Y - sum(sign_i * roundShr(Y, s_i)),  sum(sign_i * 2^-s_i) ~= k
//
//  Potok (probka na wejsciu w cyklu 0):
//    A  (1)  D = x - x1
//    B  (2)  P = (D << G) + Y1,  R_i = roundShr(Y1, s_i)
//    C  (3)  Y1 := P - sum(sign_i * R_i)
//    wyj(4)  y = sat(roundShr(Y1, G))

case class DcFilterConfig(fs: Double,
                          fc: Double = 30.0,
                          G: Int = 8,
                          nTerms: Int = 3) {
  val dataWidth = 18
  val accWidth  = dataWidth + 3 + G

  val k     = 2 * math.Pi * fc / fs
  val terms = DcFilter.spt(k, nTerms, accWidth - 1) // Seq[(sign, shift)]
  val kHw   = terms.map { case (sg, s) => sg * math.pow(2.0, -s) }.sum
  val fcHw  = kHw * fs / (2 * math.Pi)

  require(terms.nonEmpty && kHw > 0 && kHw < 1, s"Nie da sie przyblizyc k = $k")
  // P + nTerms operandow w drzewie o glebokosci 2 -> max 3 skladniki
  require(terms.size <= 3, "Wiecej niz 3 skladniki = 3 sumatory w takcie C")
}

object DcFilter {
  val minSpacing = 2

  /** Zachlanne przyblizenie k sumą +-2^-s (signed power of two). */
  def spt(k: Double, nTerms: Int, maxShift: Int): Seq[(Int, Int)] = {
    val terms = ArrayBuffer[(Int, Int)]()
    var r     = k
    var done  = false
    while (!done && r != 0.0 && terms.size < nTerms) {
      val sign = if (r > 0) 1 else -1
      val e    = -math.log(math.abs(r)) / math.log(2.0)
      val s = Seq(math.floor(e), math.ceil(e))
        .map(_.toInt.max(1).min(maxShift))
        .minBy(s => math.abs(math.abs(r) - math.pow(2.0, -s)))
      val next = r - sign * math.pow(2.0, -s)
      if (math.abs(next) < math.abs(r)) { terms += ((sign, s)); r = next }
      else done = true
    }
    terms.toSeq
  }

  /** (v + 2^(s-1)) >> s, bez przepelnienia. Szerokosc wyniku: w + 1 - s. */
  def roundShr(v: SInt, s: Int): SInt =
    if (s == 0) v
    else {
      val w = v.getWidth + 1
      (v.resize(w) + S(BigInt(1) << (s - 1), w bits)) >> s
    }

  /** Drzewo sumatorow z operandami ze znakiem: (wartosc, czyOdjac).
    * Glebokosc ceil(log2(n)), bez osobnych negacji. */
  def signedSum(ops: Seq[(SInt, Boolean)]): (SInt, Boolean) =
    if (ops.size == 1) ops.head
    else {
      val (l, r) = ops.splitAt((ops.size + 1) / 2)
      (signedSum(l), signedSum(r)) match {
        case ((a, false), (b, false)) => (a + b, false)
        case ((a, false), (b, true))  => (a - b, false)
        case ((a, true), (b, false))  => (b - a, false)
        case ((a, true), (b, true))   => (a + b, true)
      }
    }
}

case class DcStageA(dw: Int) extends Bundle {
  val d = SInt(dw + 1 bits)
  val x = SInt(dw bits)
}

case class DcStageB(dw: Int, aw: Int, n: Int) extends Bundle {
  val p = SInt(aw bits)
  val r = Vec(SInt(aw bits), n)
  val x = SInt(dw bits)
}

case class DcFilter(c: DcFilterConfig) extends Component {
  import DcFilter._
  import c._

  val io = new Bundle {
    val input   = slave  Flow(SInt(dataWidth bits))
    val output  = master Flow(SInt(dataWidth bits))
    val bypass  = in  Bool()
    val overrun = out Bool()
  }

  SpinalInfo(f"DcFilter: k = $k%.6f, sprzet = $kHw%.6f (fc = $fcHw%.3f Hz), " +
    terms.map { case (sg, s) => (if (sg > 0) "+" else "-") + s"2^-$s" }.mkString(" "))

  // ---------------- A: D = x - x1 ----------------
  val x1 = Reg(SInt(dataWidth bits)) init(0)
  when(io.input.valid) { x1 := io.input.payload }

  val a = io.input.map { x =>
    val p = DcStageA(dataWidth)
    p.d := x.resize(dataWidth + 1) - x1.resize(dataWidth + 1)
    p.x := x
    p
  }.stage()

  // ---------------- B: P i R_i ----------------
  val Y1 = Reg(SInt(accWidth bits)) init(0)

  val b = a.map { pa =>
    val p = DcStageB(dataWidth, accWidth, terms.size)
    p.p := (pa.d << G).resize(accWidth) + Y1
    for (((_, s), i) <- terms.zipWithIndex)
      p.r(i) := roundShr(Y1, s).resize(accWidth)
    p.x := pa.x
    p
  }.stage()

  // ---------------- C: Y1 := P - sum(sign_i * R_i) ----------------
  // sign_i > 0 -> R_i odejmujemy, sign_i < 0 -> dodajemy
  val ops = (b.payload.p, false) +: terms.zipWithIndex.map {
    case ((sg, _), i) => (b.payload.r(i), sg > 0)
  }
  val (yNext, neg) = signedSum(ops)
  assert(!neg) // P jest pierwszy i dodatni, wiec korzen nigdy nie jest negowany
  when(b.valid) { Y1 := yNext }

  val cx = b.map(_.x).stage()

  // ---------------- wyjscie ----------------
  val yRound = roundShr(Y1, G)
  val ySat   = yRound.sat(yRound.getWidth - dataWidth)

  io.output << cx.map(x => Mux(io.bypass, x, ySat)).stage()

  // ---------------- overrun ----------------
  // probka w cyklu, w ktorym poprzednia jest jeszcze w A -> odstep < 2
  val overrunReg = RegInit(False) setWhen (io.input.valid && a.valid)
  io.overrun := overrunReg
}

object DcFilterVerilog extends App {
  SpinalVerilog(DcFilter(DcFilterConfig(fs = 75e6 / 4672)))
}

// ======================= model bitowy + test =======================

class DcFilterModel(c: DcFilterConfig) {
  private var x1 = BigInt(0)
  private var y  = BigInt(0)

  private def rs(v: BigInt, s: Int) = if (s == 0) v else (v + (BigInt(1) << (s - 1))) >> s
  private def wrap(v: BigInt, w: Int) = {
    val m = BigInt(1) << w
    val r = ((v % m) + m) % m
    if (r >= m / 2) r - m else r
  }
  private def sat(v: BigInt, w: Int) = {
    val max = (BigInt(1) << (w - 1)) - 1
    v.min(max).max(-max - 1)
  }

  def step(x: BigInt): BigInt = {
    val d = x - x1
    x1 = x
    val p   = (d << c.G) + y
    val sum = c.terms.map { case (sg, s) => sg * rs(y, s) }.sum
    y = wrap(p - sum, c.accWidth)
    sat(rs(y, c.G), c.dataWidth)
  }
}

object DcFilterSim extends App {
  val cfg = DcFilterConfig(fs = 75e6 / 4672)
  val compiled = SimConfig.withWave.compile(DcFilter(cfg))

  // Test 1: bit-exact vs model, przelaczanie bypass, usuwanie DC
  compiled.doSim("bitexact", seed = 42) { dut =>
    val cd = dut.clockDomain
    dut.io.input.valid #= false
    dut.io.bypass #= false
    cd.forkStimulus(10)
    cd.waitSampling(2)

    val model    = new DcFilterModel(cfg)
    val expected = scala.collection.mutable.Queue[BigInt]()
    val got      = ArrayBuffer[BigInt]()

    cd.onSamplings {
      if (dut.io.output.valid.toBoolean) {
        val v = dut.io.output.payload.toBigInt
        assert(expected.nonEmpty, "Nadmiarowa probka na wyjsciu")
        val e = expected.dequeue()
        assert(v == e, s"Rozbieznosc: dut=$v model=$e (probka ${got.size})")
        got += v
      }
    }

    var n = 0
    def send(bypass: Boolean): Unit = {
      val xd = 0.3 + 0.5 * math.sin(2 * math.Pi * n / 37.0)
      val x  = BigInt(math.round(xd * (1 << 17))).min((1 << 17) - 1).max(-(1 << 17))
      n += 1
      val y = model.step(x)                // filtr liczy zawsze
      expected.enqueue(if (bypass) x else y)

      dut.io.input.valid   #= true
      dut.io.input.payload #= x
      cd.waitSampling()
      dut.io.input.valid #= false
      dut.io.input.payload.randomize()
      cd.waitSampling(1 + simRandom.nextInt(3)) // odstep 2..4 >= minSpacing
    }

    def phase(count: Int, bypass: Boolean): Unit = {
      cd.waitSampling(6) // oproznij potok przed zmiana bypass
      dut.io.bypass #= bypass
      for (_ <- 0 until count) send(bypass)
    }

    phase(1500, bypass = false)
    phase(300,  bypass = true)
    phase(700,  bypass = false)
    cd.waitSampling(10)

    assert(expected.isEmpty, s"Brakuje ${expected.size} probek na wyjsciu")
    assert(!dut.io.overrun.toBoolean, "overrun przy poprawnym odstepie")

    val tail = got.takeRight(37 * 10).map(_.toDouble / (1 << 17))
    println(f"Srednia wyjscia (ostatnie 10 okresow): ${tail.sum / tail.size}%.6f (wejscie DC = 0.3)")
    println("Test bit-exact OK")
  }

  // Test 2: overrun przy probkach w kolejnych cyklach
  compiled.doSim("overrun") { dut =>
    val cd = dut.clockDomain
    dut.io.input.valid #= false
    dut.io.bypass #= false
    cd.forkStimulus(10)
    cd.waitSampling(2)

    dut.io.input.valid #= true
    dut.io.input.payload #= 1000
    cd.waitSampling(2) // dwie probki pod rzad
    dut.io.input.valid #= false
    cd.waitSampling(2)
    assert(dut.io.overrun.toBoolean, "overrun sie nie zapalil")

    cd.waitSampling(20)
    assert(dut.io.overrun.toBoolean, "overrun powinien trzymac do resetu")
    println("Test overrun OK")
  }
}
