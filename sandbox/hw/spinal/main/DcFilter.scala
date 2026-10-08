package newhope.sandbox

import spinal.core._
import spinal.lib.{math => _, _}   // wszystko z spinal.lib poza math
import spinal.core.sim._
import spinal.lib.sim._
import newhope.vertebra.sim.{SimEnv,SimBackend}
import scala.collection.mutable.ArrayBuffer
import scala.reflect.{ClassTag, classTag}

//  y[n] = x[n] - x[n-1] + a * y[n-1],  a = 1 - k,  k = 2*pi*fc/fs
//  a*Y = Y - sum(sign_i * roundShr(Y, s_i)),  sum(sign_i * 2^-s_i) ~= k
//
//  Potok (probka na wejsciu w cyklu 0):
//    A  (1)  D = x - x1
//    B  (2)  P = (D << G) + Y1,  R_i = roundShr(Y1, s_i)
//    C  (3)  Y1 := P - sum(sign_i * R_i)
//    wyj(4)  y = sat(roundShr(Y1, G))


case class DcFilterConfig(fs: Double, fc: Double = 30.0, G: Int = 8, nSptTerms: Int = 2) {
  val dw = 18
  val aw  = dw + 3 + G
  val k   = 2 * math.Pi * fc / fs
  val terms = spt(k, nSptTerms, aw - 1) // Seq[(sign, shift)]

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
}

object DcFilterHw {
    // roundShr(v,s) = roun(v / 2^s) = floor(v / 2^s + 0.5) = (v + 2^(s−1)) >> s
    // przed shiftem trzeba dodać połowę kroku, czyli 2^(s-1)
    //  S(BigInt(1) << (s - 1), w bits) to się wykonuje w scali, nie w sprzęcie
    // np v=6 i s=2: div = 2^2 = 4: 6/4 = 1.5
    // obcięcie zrobi floor -> 1, round powinno dać 2
    // 6 + 2^(2-1) = 6 + 2 = 8, 8 >> 2 = 2
    def roundShr(v: SInt, s: Int): SInt =
        if (s == 0) v
        else {
            //val w = v.getWidth + 1
            // robimy resize(w) żeby po dodawaniu v + 2^(s−1) 
            // wynik się nie przepełnił i zrobił ujemny
            // Po przesunięciu wynik ma w + 1 − s bitów, mniej niż v
            //(v.resize(w) + S(BigInt(1) << (s - 1), w bits)) >> s
            
            // można bardziej efektywnie (mniej bitów sumatora)
            // v = q·2^s + r, gdzie 0 ≤ r < 2^s. 
            // Dodanie 2^(s−1) zwiększa q o 1 dokładnie wtedy, gdy r ≥ 2^(s−1), 
            // a to jest równoważne temu, że bit s−1 jest jedynką. 
            // W kodzie U2 dolne bity r są tymi samymi bitami co dolne bity v, 
            // więc działa to również dla liczb ujemnych.
            val q = v >> s  // w − s bitów, floor
            val r = v(s-1).asUInt // 1-bitowe 0 albo 1
            q.resize(q.getWidth + 1) + r.intoSInt // +0 lub +1, bez przepełnienia
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

class DcFilter(c: DcFilterConfig) extends Component {
    val io = new Bundle {
        val input  = slave (Flow(SInt(c.dw bits)))
        val output = master(Flow(SInt(c.dw bits)))
        val bypass = in Bool()
        val overrun = out Bool()
    }
}

case class DcFilterReg(c : DcFilterConfig) extends DcFilter(c) {
    import c._

    val x1 = RegNextWhen(io.input.payload, io.input.valid) init S(0, c.dw bits)
    val y  = Reg(SInt(aw bits)) init 0

    // stage A
    val aD = RegNextWhen(io.input.payload.resize(dw + 1) - x1.resize(dw + 1), io.input.valid) init S(0, dw + 1 bits)
    val aV = RegNext(io.input.valid) init False
    val aX = RegNextWhen(io.input.payload, io.input.valid) init S(0, dw bits)

    // stage B
    val bX = RegNextWhen(aX, aV) init S(0, dw bits)
    val bAcc = RegNextWhen((aD << G).resize(aw) + y, aV) init S(0, aw bits)
    val bR   = terms.map { case (_, shift) =>
        RegNextWhen(DcFilterHw.roundShr(y, shift).resize(aw), aV) init S(0, aw bits)
    }
    val bV = RegNext(aV) init False

    // stage C
    val cX = RegNextWhen(bX, bV) init S(0, dw bits)
    val ops = (bAcc, false) +: terms.zipWithIndex.map {
        case ((sg, _), i) => (bR(i), sg > 0)
    }
    val (yNext, neg) = DcFilterHw.signedSum(ops)
    assert(!neg) // P jest pierwszy i dodatni, wiec korzen nigdy nie jest negowany
    when(bV) { y := yNext }  //stage_b.valid jest w 2gim cyklu
    val cV = RegNext(bV) init False

    // Szerokość wyniku wynosi 29 + 1 − 8 = 22 bity, 
    // czyli 18 bitów próbki i 4 bity zapasu nad zakresem wyjścia 
    // (3 bity z accWidth plus 1 bit dodany przez roundShr przeciwko przepełnieniu).
    val yRound = DcFilterHw.roundShr(y, G) // y wraca ze skali akumulatora do skali próbek wejściowych
    
    // sat(m) usuwa m najstarszych bitów z nasyceniem. Tutaj m = 22 − 18 = 4, więc wynik ma 18 bitów.
    // W tym filtrze |y| może chwilowo dojść do 2, np. przy skoku wejścia z −1 na +1
    // yRound.resize(dataWidth) zamiast sat dało by zawinięcie: resize dla węższego wyniku po prostu odcina najstarsze bity.
    val ySat = yRound.sat(yRound.getWidth - dw)

    io.output.valid   := RegNext(cV) init False
    io.output.payload := RegNextWhen( Mux(io.bypass, cX, ySat), cV) init S(0, dw bits)

    val overrun = RegInit(False) setWhen (io.input.valid && aV)
    io.overrun := overrun
}

case class DcStageA(dw: Int) extends Bundle {
  val d = SInt(dw + 1 bits)
  val x = SInt(dw bits) //potrzebne dla bypass
}

case class DcStageB(dw: Int, aw: Int, n: Int) extends Bundle {
  val acc = SInt(aw bits)
  val r = Vec(SInt(aw bits), n)
  val x = SInt(dw bits) //potrzebne dla bypass
}

case class DcFilterFlow(c : DcFilterConfig) extends DcFilter(c) {
    import c._

    val x1 = RegNextWhen(io.input.payload, io.input.valid) init S(0, dw bits)
    val y  = Reg(SInt(aw bits)) init 0

    // stage A : D = x - x1
    val stage_a = io.input.map { x =>
        val p = DcStageA(dw)
        p.d := x.resize(dw + 1) - x1.resize(dw + 1)
        p.x := x
        p
    }.stage()
    
    // stage B : P = (D << G) + Y1,  R_i = roundShr(Y1, s_i)
    val stage_b = stage_a.map { pa =>
        val p = DcStageB(dw, aw, nSptTerms)
        p.acc := (pa.d << G).resize(aw) + y
        for (((_, s), i) <- terms.zipWithIndex)
            p.r(i) := DcFilterHw.roundShr(y, s).resize(aw)
        p.x := pa.x
        p
    }.stage()

    // stage C
    // ---------------- C: Y1 := P - sum(sign_i * R_i) ----------------
    // sign_i > 0 -> R_i odejmujemy, sign_i < 0 -> dodajemy
    // ops = (P,false), (R0, true), (R1, false)
    val ops = (stage_b.payload.acc, false) +: terms.zipWithIndex.map {
        case ((sg, _), i) => (stage_b.payload.r(i), sg > 0)
    }
    val (yNext, neg) = DcFilterHw.signedSum(ops)
    assert(!neg) // P jest pierwszy i dodatni, wiec korzen nigdy nie jest negowany
    when(stage_b.valid) { y := yNext }  //stage_b.valid jest w 2gim cyklu

    val cx = stage_b.map(_.x).stage()   //przenosimy tylko x, acc i b zostały skonsumowane do wyliczenia yNext
    // cx valid jest w 3cim cyklu, i rejestr y ma już wartość yNext
    
    // Szerokość wyniku wynosi 29 + 1 − 8 = 22 bity, 
    // czyli 18 bitów próbki i 4 bity zapasu nad zakresem wyjścia 
    // (3 bity z accWidth plus 1 bit dodany przez roundShr przeciwko przepełnieniu).
    val yRound = DcFilterHw.roundShr(y, G) // y wraca ze skali akumulatora do skali próbek wejściowych
    
    // sat(m) usuwa m najstarszych bitów z nasyceniem. Tutaj m = 22 − 18 = 4, więc wynik ma 18 bitów.
    // W tym filtrze |y| może chwilowo dojść do 2, np. przy skoku wejścia z −1 na +1
    // yRound.resize(dataWidth) zamiast sat dało by zawinięcie: resize dla węższego wyniku po prostu odcina najstarsze bity.
    val ySat = yRound.sat(yRound.getWidth - dw)

    // Jeśli przychodzi z przełącznika na płytce albo z innej domeny, przepuść go najpierw przez synchronizator:
    // val bypassSync = BufferCC(io.bypass, init = False)

    io.output << cx.map(x => Mux(io.bypass, x, ySat)).stage()

    // probka w cyklu, w ktorym poprzednia jest jeszcze w A -> odstep < 2
    val overrunReg = RegInit(False) setWhen (io.input.valid && stage_a.valid)
    io.overrun := overrunReg
}

case class DcFilterPipeline(c : DcFilterConfig) extends DcFilter(c) {
   import c._

    io.output.payload := io.output.payload.getZero
    io.output.valid   := False
    io.overrun := False
}

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
    y = wrap(p - sum, c.aw)
    sat(rs(y, c.G), c.dw)
  }
}

case class DcFilterVersions(c: DcFilterConfig) {

  private case class Variant(className: String, factory: () => DcFilter)

  private def variant[T <: DcFilter : ClassTag](f: => T): Variant =
    Variant(classTag[T].runtimeClass.getSimpleName, () => f)

  private val variants: Map[String, Variant] = Map(
    "v1" -> variant(DcFilterReg(c)),
    "v2" -> variant(DcFilterFlow(c)),
    "v3" -> variant(DcFilterPipeline(c))
  )

  val names: Seq[String] = variants.keys.toSeq.sorted

  private def resolve(name: String): Variant =
    variants.getOrElse(name, throw new IllegalArgumentException(
      s"Nieznany komponent: '$name'. Dostępne: ${names.mkString(", ")}"))

  def gen(name: String): Unit = {
    val v = resolve(name)
    Config.spinal.generateVerilog(v.factory())
  }

  def compile(name: String): SimCompiled[DcFilter] = {
    val v = resolve(name)
    Config.sim
      .workspaceName(s"${v.className}_${SimBackend.default.label}")
      .withFstWave
      .compile(v.factory())
  }
}

object DcFilterVerilog extends App {
    val cfg = DcFilterConfig(fs = 75e6 / 4672)
    val versions = DcFilterVersions(cfg)
    
    args.toList match {
        case Nil   => versions.names.foreach(versions.gen)
        case names => names.foreach(versions.gen)
    }
}

object DcFilterDemoSim extends App {
    val cfg = DcFilterConfig(fs = 75e6 / 4672)
    lazy val dut : SimCompiled[DcFilterReg] = Config.sim
        .workspaceName(s"${DcFilterReg.getClass.getSimpleName}_${SimBackend.default.label}")
        .withFstWave
        .compile { DcFilterReg(cfg) }
    
    dut.doSim("DcFilter_demo", seed = 1) { dut =>
        val clk = dut.clockDomain
        clk.forkStimulus(period = 10)

        dut.io.input.valid   #= false
        dut.io.input.payload #= 0

        clk.waitSampling(2)
        val count = 11
        var payload = 1
        for (i <- 1 until count) {
          //dut.io.input.push(i)
          dut.io.input.valid   #= true
          dut.io.input.payload #= payload
          clk.waitSampling()
          dut.io.input.valid  #= false
          val step = 1 + simRandom.nextInt(3)
          clk.waitSampling(1 + step)
          payload += step
        }
    }
}

object DcFilterTest extends App {
  val cfg = DcFilterConfig(fs = 75e6 / 4672)
  val versions = DcFilterVersions(cfg)
  val name = args.headOption.getOrElse("v1")
  lazy val compiled = versions.compile(name)

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
