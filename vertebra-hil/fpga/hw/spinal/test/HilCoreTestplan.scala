package newhope.vertebra.hil

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable
import scala.util.Random
import newhope.vertebra.{Stage, Testpoint, TestplanSuite}
import newhope.vertebra.sim.SimBackend
import HilProtocol._

// =====================================================================
//  ZRODLO PLANU
//  contract/commands.md, czesc "FPGA: binarny most do rejestrow" i
//  wspolna mapa rejestrow. Nazwy nasze, prefiks core_. To jest krok 2a
//  etapu 2 (vertebra-hil.md §10).
//
//  DUT: HilCore (most + rejestry rdzenia, io.ext = reszta mapy). Strone
//  hosta gra HilUartSim w czasie symulacji, wiec baud hosta i FPGA roznia
//  sie jak na plytce (115200 nominalnie vs dzielnik FPGA).
//
//  ODRZUCONE / PRZENIESIONE
//   status Busy       - rdzen nie ma rejestrow blokowanych; testuje go
//                       I2sHarnessTestplan (blok IP w trakcie biegu)
//   bajty w trakcie odpowiedzi - host czeka na odpowiedz; flaga dropped
//                       ma tylko diagnostyczna diode
// =====================================================================
object HilCorePlan {
  val plan : Seq[Testpoint] = Seq(
    Testpoint("core_param_bounds", Stage.V1,
      "Generyki mostu legalne dla plytki i konfiguracji testowej",
      checking = Seq("|blad baud| < 2 %, dzielnik miesci sie w UartCtrl",
                     "timeout >= 10 czasow bajtu i calkowita liczba cykli na us")),
    Testpoint("core_protocol_frames", Stage.V1,
      "Ramki HilProtocol zgodne z commands.md, bez symulacji",
      checking = Seq("readReq/writeReq: bajty i suma XOR z recznego przykladu",
                     "parseResponse odrzuca zla dlugosc, zly bajt synchronizacji i zla sume")),
    Testpoint("core_read_id", Stage.V1,
      "Rejestry identyfikacyjne",
      checking = Seq("magic, proto, ip_id, build, variant == generyki", "ctrl czyta sie jako 0")),
    Testpoint("core_scratch_rw", Stage.V1,
      "Zapis i odczyt scratch",
      stimulus = Seq("0, same jedynki, 0xa5a5a5a5, 0x5a5a5a5a, 0x80000001"),
      checking = Seq("odpowiedz na zapis powtarza dane", "odczyt == ostatni zapis")),
    Testpoint("core_ctrl_status", Stage.V1,
      "ctrl start/stop/soft reset i status",
      checking = Seq("start -> jeden impuls io.start, status.running = 1",
                     "stop i soft reset -> running = 0; stop wygrywa ze startem w jednym zapisie",
                     "status.locked, status.error i status.snapshot odwzorowuja wejscia")),
    Testpoint("core_bad_sum", Stage.V1,
      "Zla suma: status bad_sum i brak efektu",
      checking = Seq("odpowiedz bad_sum z danymi 0", "scratch bez zmian")),
    Testpoint("core_bad_op", Stage.V1,
      "Nieznana operacja i zapis rejestru tylko do odczytu",
      checking = Seq("op 0x03 -> bad_op", "zapis magic -> bad_op, magic bez zmian")),
    Testpoint("core_bad_addr", Stage.V1,
      "Adres bez rejestru",
      checking = Seq("0x008..0x00f w rdzeniu -> bad_addr", "adres na io.ext -> status z io.ext")),
    Testpoint("core_ext_forward", Stage.V1,
      "Adresy >= 0x010 trafiaja na io.ext bez zmian",
      checking = Seq("jeden impuls valid na transakcje, addr/write/wdata zgodne z ramka",
                     "rdata i status z io.ext wracaja w odpowiedzi")),
    Testpoint("core_resync", Stage.V1,
      "Smieci przed ramka i porzucona ramka",
      stimulus = Seq("bajty bez 0xa5", "polowa ramki, cisza > timeout, pelna ramka"),
      checking = Seq("smieci bez odpowiedzi", "niedokonczona ramka bez odpowiedzi, flaga timeout",
                     "nastepna ramka obsluzona poprawnie")),
    Testpoint("core_back_to_back", Stage.V1,
      "Losowy ciag operacji",
      checking = Seq("kazda odpowiedz zgodna z modelem scratch i stalych", "brak bledow ramki UART")),
    Testpoint("core_baud_tolerance", Stage.V2,
      "Host z baud odchylonym o +-1,5 % od nominalu",
      checking = Seq("odczyt i zapis poprawne w obu kierunkach odchylenia"))
  )

  case class Cfg(name : String, g : HilBridgeGenerics)
  val configs = Seq(
    Cfg("board", HilBridgeGenerics()),                                  // 100 MHz, 115200, 10 ms
    // Dolna granica dzielnika z dokladnym baud: 100e6 / (8 * 8) = 1,5625 MBd.
    Cfg("fast",  HilBridgeGenerics(baud = 1562500L, timeoutUs = 100L)))

  val clkPeriod = 10                                                   // 100 MHz, jednostka = 1 ns

  /** Okres bitu hosta: nominalny baud (PIC nie zna dzielnika FPGA). */
  def hostPeriod(g : HilBridgeGenerics) : Long = scala.math.round(1e9 / g.baud)

  val ipId    = ascii4("TEST")
  val build   = 0x0badc0deL
  val variant = 0x00012345L
}

class HilCoreTestplan extends TestplanSuite {
  import HilCorePlan._
  def testplan : Seq[Testpoint] = plan

  case class Env(d : HilCore, g : HilBridgeGenerics, uart : HilUartSim, cli : HilRegClient) {
    val cd = d.clockDomain
    val starts, stops, softResets = mutable.ArrayBuffer[Long]()
    val extLog = mutable.ArrayBuffer[(Boolean, Int, Long)]()     // (write, addr, wdata)
  }

  for (Cfg(cfgName, g) <- configs) {
    lazy val dut = Config.sim
      .workspaceName(s"core_${cfgName}_${SimBackend.default.label}")
      .compile(HilCore(g, ipId, build, variant))

    def scenario(tp : String)(body : Env => Unit) : Unit =
      testpoint(tp, variant = cfgName) {
        dut.doSim(s"core_${cfgName}_$tp", seed = 42) { d =>
          d.clockDomain.forkStimulus(period = clkPeriod)
          d.io.locked #= false
          d.io.error  #= false
          d.io.snapshot #= false
          d.io.ext.rdata  #= 0
          d.io.ext.status #= Status.BadAddr
          val u = new HilUartSim(d.clockDomain, d.io.uart.rxd, d.io.uart.txd, hostPeriod(g))
          val e = Env(d, g, u, new HilRegClient(u))
          u.start()
          // Monitor impulsow i io.ext, jeden watek - kolejnosc deterministyczna.
          fork {
            while (true) {
              d.clockDomain.waitSampling()
              val t = simTime()
              if (d.io.start.toBoolean)     e.starts     += t
              if (d.io.stop.toBoolean)      e.stops      += t
              if (d.io.softReset.toBoolean) e.softResets += t
              if (d.io.ext.valid.toBoolean)
                e.extLog += ((d.io.ext.write.toBoolean, d.io.ext.addr.toInt, d.io.ext.wdata.toLong))
            }
          }
          // Najdluzszy scenariusz: ~40 transakcji po ~16 bajtow + timeout.
          SimTimeout(40L * 20 * 10 * hostPeriod(g) + 3 * g.timeoutCycles * clkPeriod)
          d.clockDomain.waitSampling(20)
          body(e)
          u.checkFraming()
        }
      }

    scenario("core_read_id") { e =>
      assert(e.cli.readOk(Addr.Magic)   == Magic)
      assert(e.cli.readOk(Addr.Proto)   == ProtoVersion)
      assert(e.cli.readOk(Addr.IpId)    == ipId)
      assert(e.cli.readOk(Addr.Build)   == build)
      assert(e.cli.readOk(Addr.Variant) == variant)
      assert(e.cli.readOk(Addr.Ctrl)    == 0)
      assert(e.cli.readOk(Addr.Status)  == 0)
    }

    scenario("core_scratch_rw") { e =>
      for (v <- Seq(0L, 0xFFFFFFFFL, 0xA5A5A5A5L, 0x5A5A5A5AL, 0x80000001L)) {
        e.cli.writeOk(Addr.Scratch, v)
        assert(e.cli.readOk(Addr.Scratch) == v, f"scratch po zapisie $v%08x")
        assert(e.d.io.scratch.toLong == v)
      }
    }

    scenario("core_ctrl_status") { e =>
      import CtrlBit._, StatusBit._
      e.cli.writeOk(Addr.Ctrl, 1L << Start)
      assert(e.starts.size == 1 && e.stops.isEmpty, s"start: ${e.starts}, stop: ${e.stops}")
      assert(e.cli.readOk(Addr.Status) == (1L << Running))
      assert(e.cli.readOk(Addr.Ctrl) == 0, "ctrl to impuls, odczyt 0")

      e.cli.writeOk(Addr.Ctrl, 1L << Stop)
      assert(e.stops.size == 1)
      assert(e.cli.readOk(Addr.Status) == 0)

      e.cli.writeOk(Addr.Ctrl, (1L << Start) | (1L << Stop))
      assert(e.cli.readOk(Addr.Status) == 0, "stop wygrywa ze startem")

      e.cli.writeOk(Addr.Ctrl, 1L << Start)
      e.cli.writeOk(Addr.Ctrl, 1L << SoftReset)
      assert(e.softResets.size == 1)
      assert(e.cli.readOk(Addr.Status) == 0, "soft reset zatrzymuje bieg")

      e.d.io.locked #= true
      assert(e.cli.readOk(Addr.Status) == (1L << Locked))
      e.d.io.error #= true
      assert(e.cli.readOk(Addr.Status) == ((1L << Locked) | (1L << Error)))
      e.d.io.snapshot #= true
      assert(e.cli.readOk(Addr.Status) == ((1L << Locked) | (1L << Error) | (1L << Snapshot)))
    }

    scenario("core_bad_sum") { e =>
      e.cli.writeOk(Addr.Scratch, 0x11223344L)
      val req = writeReq(Addr.Scratch, 0x55667788L)
      val r = e.cli.transact(req.init :+ (req.last ^ 0x01))
      assert(r == Right(Response(Status.BadSum, 0)), s"$r")
      assert(e.cli.readOk(Addr.Scratch) == 0x11223344L, "zapis ze zla suma mial efekt")
    }

    scenario("core_bad_op") { e =>
      val bad = Seq(ReqSync, 0x03, 0x00, Addr.Scratch)
      assert(e.cli.transact(bad :+ xorSum(bad)) == Right(Response(Status.BadOp, 0)))
      assert(e.cli.write(Addr.Magic, 0) == Response(Status.BadOp, 0))
      assert(e.cli.readOk(Addr.Magic) == Magic)
      assert(e.extLog.isEmpty, s"bledna operacja trafila na io.ext: ${e.extLog}")
    }

    scenario("core_bad_addr") { e =>
      for (a <- 0x008 until 0x010)
        assert(e.cli.read(a) == Response(Status.BadAddr, 0), f"$a%03x")
      assert(e.cli.read(0x0FF)  == Response(Status.BadAddr, 0))
      assert(e.cli.read(0xFFFF) == Response(Status.BadAddr, 0))
      assert(e.cli.write(0x00A, 1) == Response(Status.BadAddr, 0))
    }

    scenario("core_ext_forward") { e =>
      e.d.io.ext.rdata  #= 0xCAFEF00DL
      e.d.io.ext.status #= Status.Ok
      assert(e.cli.read(0x010) == Response(Status.Ok, 0xCAFEF00DL))
      assert(e.cli.read(0xFFFF) == Response(Status.Ok, 0xCAFEF00DL))
      assert(e.cli.write(0x123, 0x01020304L) == Response(Status.Ok, 0x01020304L), "zapis powtarza dane")
      e.d.io.ext.status #= Status.Busy
      assert(e.cli.write(0x100, 7) == Response(Status.Busy, 0), "status z io.ext")
      assert(e.extLog.size == 4, s"io.ext: ${e.extLog}")
      assert(e.extLog.toList == List(
        (false, 0x010, e.extLog(0)._3), (false, 0xFFFF, e.extLog(1)._3),
        (true, 0x123, 0x01020304L), (true, 0x100, 7L)), s"${e.extLog}")
      e.cli.read(Addr.Magic)
      assert(e.extLog.size == 4, "odczyt rdzenia nie idzie na io.ext")
    }

    scenario("core_resync") { e =>
      e.uart.send(Seq(0x00, 0xFF, RspSync, 0x13, 0x5A))
      e.cli.expectSilence(20L * 10 * e.uart.bitPeriod)
      assert(e.cli.readOk(Addr.Magic) == Magic)

      e.uart.send(readReq(Addr.Magic).take(3))
      // Po timeoutcie ramka przepada; odpowiedz nie moze przyjsc.
      e.cli.expectSilence(e.g.timeoutCycles * clkPeriod + 20L * 10 * e.uart.bitPeriod)
      assert(e.d.io.timeout.toBoolean, "brak flagi timeout")
      assert(e.cli.readOk(Addr.Magic) == Magic)
    }

    scenario("core_back_to_back") { e =>
      val rng = new Random(7)
      var scratch = 0L
      for (i <- 0 until 30) rng.nextInt(4) match {
        case 0 =>
          scratch = rng.nextLong() & 0xFFFFFFFFL
          e.cli.writeOk(Addr.Scratch, scratch)
        case 1 => assert(e.cli.readOk(Addr.Scratch) == scratch, s"krok $i")
        case 2 => assert(e.cli.readOk(Addr.Magic) == Magic, s"krok $i")
        case _ => assert(e.cli.read(0x008 + rng.nextInt(8)).status == Status.BadAddr, s"krok $i")
      }
      assert(!e.d.io.rxError.toBoolean && !e.d.io.dropped.toBoolean && !e.d.io.timeout.toBoolean)
    }

    scenario("core_baud_tolerance") { e =>
      val nominal = hostPeriod(e.g)
      for (k <- Seq(0.985, 1.015)) {
        val p = scala.math.round(nominal * k)
        e.uart.bitPeriod = p
        e.cli.writeOk(Addr.Scratch, 0x5AA5C33CL)
        assert(e.cli.readOk(Addr.Scratch) == 0x5AA5C33CL, s"okres bitu $p (nominal $nominal)")
        assert(e.cli.readOk(Addr.Magic) == Magic)
      }
      assert(!e.d.io.rxError.toBoolean)
    }
  }

  testpoint("core_param_bounds") {
    for (Cfg(n, g) <- configs) {
      info(f"$n: dzielnik ${g.clockDivider}, baud ${g.actualBaud}%.0f (${g.baudErrorPct}%+.2f %%), " +
           f"bajt ${g.byteCycles} cykli, timeout ${g.timeoutCycles} cykli")
      assert(g.isLegal, s"$n: $g")
    }
    // Plytka: 115200 przy 100 MHz wychodzi z bledem ~0,47 %.
    assert(scala.math.abs(configs.head.g.baudErrorPct) < 0.5)
  }

  testpoint("core_protocol_frames") {
    // A5 01 00 05 -> suma A5^01^00^05 = A1
    assert(readReq(Addr.Status) == Seq(0xA5, 0x01, 0x00, 0x05, 0xA1))
    // A5 02 00 07 12 34 56 78 -> A5^02^07 = A0; ^12^34^56^78 = A0^08 = A8
    assert(writeReq(Addr.Scratch, 0x12345678L) == Seq(0xA5, 0x02, 0x00, 0x07, 0x12, 0x34, 0x56, 0x78, 0xA8))
    assert(ascii4("I2S") == 0x49325300L && ascii4("HIL1") == Magic)
    val ok = response(Status.Ok, 0xDEADBEEFL)
    assert(parseResponse(ok) == Right(Response(Status.Ok, 0xDEADBEEFL)))
    assert(parseResponse(ok.init).isLeft)
    assert(parseResponse(0xA5 +: ok.tail).isLeft)
    assert(parseResponse(ok.init :+ (ok.last ^ 0x80)).isLeft)
  }
}
