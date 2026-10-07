package newhope.sandbox

import spinal.core._
import spinal.lib.{math => _, _}   // wszystko z spinal.lib poza math
import spinal.core.sim._
import spinal.lib.sim._
import newhope.vertebra.sim.{SimEnv,SimBackend}
import scala.collection.mutable.ArrayBuffer

case class DcFilterConfig(fs: Double, fc: Double = 30.0, G: Int = 8, nSptTerms: Int = 2) {
  val dw = 18
  val aw  = dw + 3 + G
  val k     = 2 * math.Pi * fc / fs
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

case class DCv1(c : DcFilterConfig) extends Component {
    val io = new Bundle {
        val input  = slave (Flow(UInt(c.dw bits)))
        val output = master(Flow(SInt(c.dw +1 bits)))
        val bypass = in Bool()
        val overrun = out Bool()
    }

    val x1 = RegNextWhen(io.input.payload, io.input.valid) init U(0, c.dw bits)
    val y1 = Reg(SInt(c.aw bits)) init 0

    
    val diff     = Flow(SInt(c.dw + 1 bits))
    diff.valid   := io.input.valid
    diff.payload := io.input.payload.intoSInt - x1.intoSInt
    //io.output << diff
    io.output << diff.stage()

    io.overrun := False
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

case class DCv2(c : DcFilterConfig) extends Component {
    import c._
    val io = new Bundle {
        val input  = slave (Flow(SInt(dw bits)))
        val output = master(Flow(SInt(dw+1 bits)))
        val bypass = in Bool()
        val overrun = out Bool()
    }

    val x1 = RegNextWhen(io.input.payload, io.input.valid) init S(0, dw bits)
    val y1 = Reg(SInt(aw bits)) init 0

    val stage_a = io.input.map { x =>
        val p = DcStageA(dw)
        p.d := x.resize(dw + 1) - x1.resize(dw + 1)
        p.x := x
        p
    }.stage()
    
    val stage_b = stage_a.map { pa =>
        val p = DcStageB(dw, aw, nSptTerms)
        p.acc := (pa.d << G).resize(aw) + y1
        p.x   := pa.x
        p
    }.stage()

    io.output.payload := io.output.payload.getZero
    io.output.valid   := False

    io.overrun := False
}

case class DCv3(dataWidth : Int, accWidth : Int) extends Component {
    val io = new Bundle {
        val input  = slave (Flow(SInt(dataWidth bits)))
        val output = master(Flow(SInt(dataWidth+1 bits)))
        val bypass = in Bool()
        val overrun = out Bool()
    }

    val x1 = RegNextWhen(io.input.payload, io.input.valid) init S(0, dataWidth bits)
    val y1 = Reg(SInt(accWidth bits)) init 0

    val stage_a = io.input.map { x =>
        val p = DcStageA(dataWidth)
        p.d := x.resize(dataWidth + 1) - x1.resize(dataWidth + 1)
        p.x := x
        p
    }.stage()
    

    
    io.overrun := False
}

object DcFilterDemoVerilog extends App {
    val dataWidth      = 18
    val accWidth       = 20

    val cfg = DcFilterConfig(fs = 75e6 / 4672)

    val components: Map[String, () => Component] = Map(
        "v1" -> (() => DCv1(cfg)),
        "v2" -> (() => DCv2(cfg)),
        "v3" -> (() => DCv3(dataWidth, accWidth))
    )

    val config = SpinalConfig(
        targetDirectory              = "hw/gen/verilog",
        defaultClockDomainFrequency  = FixedFrequency(100 MHz),
        defaultConfigForClockDomains = ClockDomainConfig(resetKind = BOOT),
        anonymSignalUniqueness       = true
    )
    def gen(name : String): Unit = components.get(name) match {
        case Some(factory) => config.generateVerilog(factory())
        case None =>
            println(s"Nieznany komponent: '$name'. Dostępne: ${components.keys.mkString(", ")}, all")
            sys.exit(1)
    }
    
    args.toList match {
        case Nil   => components.keys.foreach(gen)
        case names => names.foreach(gen)
    }
}

object DcFilterDemoSim extends App {
    val cfg = DcFilterConfig(fs = 75e6 / 4672)
    lazy val dut : SimCompiled[DCv1] = Config.sim
        .workspaceName(s"${DCv1.getClass.getSimpleName}_${SimBackend.default.label}")
        .withFstWave
        .compile { DCv1(cfg) }
    
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