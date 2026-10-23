package newhope.vertebra.hil

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable
import scala.util.Random
import newhope.vertebra.{Stage, Testpoint, TestplanSuite}
import newhope.vertebra.sim.SimBackend

// =====================================================================
//  Plan echa z etapu 0. Harness to tez IP (vertebra-hil.md §1): zanim
//  bitstream trafi na plytke, echo przechodzi w symulacji. Zrodlo planu:
//  wlasne, nie ma odpowiednika w OpenTitanie.
// =====================================================================

object HilEchoPlan {
  def plan = Seq(
    Testpoint("echo_param_bounds", Stage.V1,
      "generyki echa sa realizowalne i zgodne z EchoProbe, bez symulacji",
      checking = Seq(
        "dzielnik UartCtrl >= 1 i miesci sie w clockDividerWidth bitach",
        "|blad baudu| < 2 % (uzasadnienie w HilEchoGenerics.baudOk)",
        "fifoDepth >= HilEcho.probeChunk: EchoProbe wysyla caly chunk przed odczytem",
        "okres zegara w symulacji jest calkowita parzysta liczba ns")),

    Testpoint("echo_byte_roundtrip", Stage.V1,
      "pojedyncze bajty wracaja bez zmian",
      stimulus = Seq(
        "model hosta wysyla bajt i czeka na echo, dopiero potem nastepny",
        "fast: wszystkie 256 wartosci w losowej kolejnosci; board: wartosci brzegowe + losowe"),
      checking = Seq(
        "echo == wyslany bajt, w tej samej kolejnosci",
        "kazda ramka z DUT-a ma bit stopu, brak zboczy bez bitu startu",
        "brak nadmiarowych bajtow po zakonczeniu")),

    Testpoint("echo_burst", Stage.V1,
      "strumien ramek bez przerw przechodzi przez FIFO",
      stimulus = Seq("ramki jedna za druga, strumien dluzszy niz fifoDepth"),
      checking = Seq(
        "wszystkie bajty wracaja, w kolejnosci",
        "led[6] (przepelnienie) i led[5] (blad ramki) zostaja zgaszone"))
  )

  case class Cfg(name : String, g : HilEchoGenerics)

  val configs = Seq(
    // Dzielnik dokladny: 100e6 / 1,5625e6 / 8 = 8, zero bledu baudu.
    // Szybka konfiguracja, zeby przejsc wszystkie 256 wartosci.
    Cfg("fast",  HilEchoGenerics(baud = 1562500L)),
    // To, co idzie na plytke: 115200, blad -0,45 % (dzielnik 109 zamiast 108,5).
    // Model hosta nadaje z dokladnym baudem, jak PIC.
    Cfg("board", HilEchoGenerics())
  )
}

/** Model hosta na pinach UART. Czas symulacji w ns. */
class UartHost(d : HilEchoTop, g : HilEchoGenerics) {
  val clkPeriodNs : Long = 1000000000L / g.clkHz
  val bit         : Long = math.round(1e9 / g.baud)   // nadajemy dokladnym baudem

  val rx          = mutable.Queue[Int]()
  var framingErrs = 0
  var glitches    = 0

  def start() : Unit = {
    d.io.uart.rxd #= true
    d.io.clk      #= false
    fork {
      while (true) { sleep(clkPeriodNs / 2); d.io.clk #= !d.io.clk.toBoolean }
    }
    // Monitor TX DUT-a: srodek bitu liczony od zbocza startu.
    fork {
      sleep(2 * bit)
      while (true) {
        waitUntil(!d.io.uart.txd.toBoolean)
        sleep(bit / 2)
        if (d.io.uart.txd.toBoolean) {
          glitches += 1
        } else {
          var v = 0
          for (i <- 0 until 8) {
            sleep(bit)
            if (d.io.uart.txd.toBoolean) v |= 1 << i
          }
          sleep(bit)
          if (!d.io.uart.txd.toBoolean) framingErrs += 1
          rx += v
        }
      }
    }
    sleep(4 * bit)   // uspokojenie po starcie (BufferCC w RX ma init False)
  }

  def send(b : Int) : Unit = {
    d.io.uart.rxd #= false; sleep(bit)
    for (i <- 0 until 8) { d.io.uart.rxd #= ((b >> i) & 1) == 1; sleep(bit) }
    d.io.uart.rxd #= true;  sleep(bit)
  }

  /** Czeka na n bajtow w kolejce, najwyzej maxBits okresow bitu. */
  def awaitRx(n : Int, maxBits : Long) : Boolean = {
    var t = 0L
    while (rx.size < n && t < maxBits) { sleep(bit); t += 1 }
    rx.size >= n
  }

  def ledOverflow = (d.io.led.toInt >> 6) & 1
  def ledRxError  = (d.io.led.toInt >> 5) & 1
}

class HilEchoTopTestplan extends TestplanSuite {
  import HilEchoPlan._
  override def testplan = plan

  testpoint("echo_param_bounds") {
    configs.foreach { c =>
      info(f"${c.name}%-5s dzielnik ${c.g.clockDivider}%4d, baud ${c.g.actualBaud}%9.0f " +
           f"(${c.g.baudErrorPct}%+.2f %%), fifo ${c.g.fifoDepth}")
    }
    val bad = configs.filterNot(_.g.isLegal)
    assert(bad.isEmpty, s"nielegalne: ${bad.map(_.name).mkString(", ")}")
    val badClk = configs.filterNot { c =>
      1000000000L % c.g.clkHz == 0 && (1000000000L / c.g.clkHz) % 2 == 0
    }
    assert(badClk.isEmpty, s"okres zegara nie jest parzysta liczba ns: ${badClk.map(_.name).mkString(", ")}")
    // Konfiguracja na plytke musi byc ta, ktora bierze HilEchoTopVerilog i EchoProbe.
    assert(configs.exists(_.g == HilEchoGenerics()), "brak konfiguracji 'board' = HilEchoGenerics()")
  }

  for (Cfg(cfgName, g) <- configs) {
    lazy val dut = Config.sim
      .workspaceName(s"hil_echo_${cfgName}_${SimBackend.default.label}")
      .compile(HilEchoTop(g))

    def scenario(tp : String)(body : (HilEchoTop, UartHost) => Unit) =
      testpoint(tp, variant = cfgName) {
        dut.doSim(s"hil_echo_${cfgName}_$tp", seed = 42) { d =>
          val host = new UartHost(d, g)
          host.start()
          body(d, host)
        }
      }

    def checkClean(host : UartHost, sent : Seq[Int]) : Unit = {
      // Nic nadmiarowego: odczekaj 3 ramki po ostatnim echu.
      sleep(30 * host.bit)
      assert(host.rx.toSeq == sent, {
        val got = host.rx.toSeq
        val i   = got.zip(sent).indexWhere { case (a, b) => a != b }
        if (i >= 0) f"bajt $i: wyslano 0x${sent(i)}%02x, wrocilo 0x${got(i)}%02x"
        else s"wyslano ${sent.size}, wrocilo ${got.size}"
      })
      assert(host.framingErrs == 0, s"${host.framingErrs} ramek bez bitu stopu")
      assert(host.glitches == 0, s"${host.glitches} zboczy na TX bez bitu startu")
      assert(host.ledOverflow == 0, "led[6]: przepelnienie FIFO")
      assert(host.ledRxError == 0, "led[5]: blad ramki RX")
    }

    scenario("echo_byte_roundtrip") { (d, host) =>
      val rng  = new Random(1)
      val data = cfgName match {
        case "fast" => rng.shuffle((0 until 256).toList)
        case _      => List(0x00, 0xFF, 0x55, 0xAA, 0x01, 0x80) ++ List.fill(10)(rng.nextInt(256))
      }
      for ((b, i) <- data.zipWithIndex) {
        host.send(b)
        assert(host.awaitRx(i + 1, maxBits = 30), f"brak echa bajtu $i (0x$b%02x)")
      }
      checkClean(host, data)
    }

    scenario("echo_burst") { (d, host) =>
      val rng  = new Random(2)
      val n    = cfgName match {
        case "fast" => 2 * g.fifoDepth
        case _      => HilEcho.probeChunk          // to, co naprawde robi EchoProbe
      }
      val data = List.fill(n)(rng.nextInt(256))
      data.foreach(host.send)
      assert(host.awaitRx(n, maxBits = 30L * n), s"wrocilo ${host.rx.size}/$n bajtow")
      checkClean(host, data)
    }
  }
}
