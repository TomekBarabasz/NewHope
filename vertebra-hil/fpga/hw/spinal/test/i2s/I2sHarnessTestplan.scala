package newhope.vertebra.hil.i2s

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable
import newhope.i2s.{I2sBusMaster, I2sCodecModel}
import newhope.i2s.I2sEvent.Frame
import newhope.vertebra.{Stage, Testpoint, TestplanSuite}
import newhope.vertebra.sim.SimBackend
import newhope.vertebra.hil._
import HilProtocol._
import I2sPattern.{Link, U32}

// =====================================================================
//  ZRODLO PLANU
//  vertebra-hil.md §6 (harness), §8 (co sprawdza plytka), kontrakt
//  contract/commands.md + contract/i2s/*. Krok 2d etapu 2: harness
//  end-to-end, tak jak zobaczy go host - przez UART i piny I2S.
//
//  STRONA ESP32 w symulacji:
//    FPGA slave  -> I2sBusMaster (master I2S w czasie symulacji, SCK
//                   asynchroniczny do dut) nadaje wzorzec i odbiera
//    FPGA master -> I2sCodecModel nadaje wzorzec na SD_IN,
//                   I2sPinRx (ponizej) odbiera SD_OUT
//  Obie strony sprawdzaja wzorzec: FPGA swoim checkerem (liczniki przez
//  UART), strona ESP32 modelem I2sCheckerModel - dokladnie to, co zrobi
//  host na plytce z licznikami z obu stron.
//
//  Zegary: sys okres 10, dut okres 21 (~47,6 MHz, niecalkowity stosunek).
//  Most w konfiguracji `fast` (1,5625 MBd), zeby transakcje UART nie
//  zjadaly czasu symulacji; baud plytki sprawdza HilCoreTestplan.
//
//  ODRZUCONE / GDZIE INDZIEJ
//   reset w roli master - ta sama sciezka (injector -> rstM/rstS) co
//                         w roli slave; w roli master model kodeka nie
//                         widzi resetu DUT-a, wiec nie da sie go poprawnie
//                         odtworzyc. Na plytce: hw_master_random_reset.
//   dynamiczny zegar dut - osobny krok przed hw_clock_ratio_sweep
// =====================================================================

/** Odbiornik strony ESP32 w roli slave: probkuje SD na narastajacym SCK
  * (zegarem dut, jak I2sCodecModel), format Philipsa. Slowa `rxW` bitow
  * z poczatku slotu. Pierwszy niepelny slot jest pomijany. */
class I2sPinRx(sck : Bool, ws : Bool, sd : Bool, rxW : Int, cd : ClockDomain) {
  val frames = mutable.ArrayBuffer[I2sWords]()

  private def word(bits : Seq[Boolean]) : Long =
    (0 until rxW).foldLeft(0L)((a, p) => (a << 1) | (if (p < bits.size && bits(p)) 1L else 0L))

  def start() : Unit = fork {
    var pSck   = false
    var lastWs : Option[Boolean] = None
    var synced = false
    var left   : Option[Long] = None
    val bits   = mutable.ArrayBuffer[Boolean]()
    while (true) {
      cd.waitActiveEdge()
      sleep(1)                                   // stan PO zboczu (jak I2sCodecModel)
      val s = sck.toBoolean
      if (s && !pSck) {
        val w = ws.toBoolean; val d = sd.toBoolean
        lastWs match {
          case Some(lw) if lw != w =>
            if (synced) {
              bits += d                           // r0: ostatni bit poprzedniego slotu
              val v = word(bits.toSeq)
              if (!lw) left = Some(v)
              else left.foreach { l => frames += I2sWords(l, v); left = None }
            }
            synced = true
            bits.clear()
          case Some(_) => bits += d
          case None    =>
        }
        lastWs = Some(w)
      }
      pSck = s
    }
  }
}

object I2sHarnessPlan {
  val plan : Seq[Testpoint] = Seq(
    Testpoint("harness_param_bounds", Stage.V1,
      "Warianty legalne i z zapasem",
      checking = Seq("kazdy wariant: generyki DUT-ow legalne, halfDiv calkowity",
                     "slave: polokres SCK ESP32 (fs wariantu, slot 32) > txLatencyCycles",
                     "najkrotsza ramka >= 5 cykli checkera")),
    Testpoint("harness_regs", Stage.V1,
      "Identyfikacja i blok IP",
      checking = Seq("ip_id == 'I2S', variant == kod wariantu",
                     "role / peer_w / slot: domyslne, zapis i odczyt, busy w biegu")),
    Testpoint("harness_master", Stage.V1,
      "FPGA master, strona ESP32 = kodek slave",
      checking = Seq("FPGA: lock, frames > 0, bad == relocks == overflow == 0",
                     "ESP32: I2sCheckerModel na SD_OUT: lock, bad == relocks == gaps == 0",
                     "sent ~ frames po stronie ESP32")),
    Testpoint("harness_slave", Stage.V1,
      "FPGA slave, strona ESP32 = master z asynchronicznym SCK",
      checking = Seq("jak harness_master, w drugiej roli", "brak naruszen czasow po stronie mastera")),
    Testpoint("harness_word_length_mismatch", Stage.V1,
      "ESP32 z inna dlugoscia slowa niz DUT, w obu rolach",
      checking = Seq("checker FPGA z peer_w != width: bad == 0", "strona ESP32 przez transfer: bad == 0")),
    Testpoint("harness_gaps", Stage.V1,
      "Luki generatora widoczne na magistrali",
      stimulus = Seq("rola master, gap_mode 1, every 9, len 2"),
      checking = Seq("ramki na SD_OUT == I2sGapModel.events (cisza w lukach)")),
    Testpoint("harness_reset_injection", Stage.V1,
      "Reset DUT-a z HilResetInjector w biegu (rola slave)",
      checking = Seq("rst_done == 3", "checker FPGA zglasza przerwe (bad >= 1) i odzyskuje lock",
                     "status.locked na koncu")),
    Testpoint("harness_capture", Stage.V1,
      "Przeklamana ramka od ESP32: first_err i capture",
      checking = Seq("bad == 1, relocks == 0", "first_err == ramka przeklamana / oczekiwana",
                     "w capture jest wpis z tym samym got/exp, idx rosnace, cap_count == 32",
                     "TRIG: dokladnie jeden impuls, 256 cykli dut")),
    Testpoint("harness_soft_reset", Stage.V1,
      "Soft reset w trakcie biegu",
      checking = Seq("po ctrl.soft_reset: running == 0", "kolejny bieg czysty (bad == 0, frames > 0)"))
  )

  val bg        = HilBridgeGenerics(baud = 1562500L, timeoutUs = 100L)
  val sysPeriod = 10
  val dutPeriod = 21
  val seed      = 0x600dfeedL
  val build     = 0x0badc0deL

  /** Pelne scenariusze na dwoch wariantach, dymny test na reszcie. */
  val full  = Seq(I2sHilVariant.v16_32, I2sHilVariant.v24_32)
  val smoke = Seq(I2sHilVariant.v16_16, I2sHilVariant.v32_32)

  /** Polokres SCK ESP32 jako mastera: fs wariantu, slot espSlot, jednostka 1 ns. */
  def espSckHalf(v : I2sHilVariant, espSlot : Int) : Int =
    scala.math.round(1e9 / (v.fs.toDouble * 2 * espSlot * 2)).toInt
}

class I2sHarnessTestplan extends TestplanSuite {
  import I2sHarnessPlan._
  def testplan : Seq[Testpoint] = plan

  /** Host: to samo, co zrobi FpgaDevice w etapie 4. */
  class Host(val cli : HilRegClient, val sysCd : ClockDomain) {
    def w(a : Int, v : Long) : Unit = cli.writeOk(a, v)
    def setup(master : Boolean, peerW : Int, slot : Int,
              gap : (Int, Int, Int) = (0, 0, 0), rst : Option[(Int, Long, Long, Int)] = None) : Unit = {
      w(I2sHilRegs.Role, if (master) I2sHilRegs.RoleMaster else I2sHilRegs.RoleSlave)
      w(I2sHilRegs.PeerW, peerW); w(I2sHilRegs.Slot, slot)
      w(Addr.GenSeed, seed)
      w(Addr.GapMode, gap._1); w(Addr.GapEvery, gap._2); w(Addr.GapLen, gap._3)
      val (cnt, min, mask, len) = rst.getOrElse((0, 0L, 0L, 1))
      w(Addr.RstCount, cnt); w(Addr.RstSeed, 0x5eedL); w(Addr.RstMin, min); w(Addr.RstMask, mask); w(Addr.RstLen, len)
    }
    def start() : Unit = w(Addr.Ctrl, 1L << CtrlBit.Start)
    def stop() : Unit = {
      w(Addr.Ctrl, 1L << CtrlBit.Stop)
      var k = 0
      while ((cli.readOk(Addr.Status) & (1L << StatusBit.Snapshot)) == 0) {
        k += 1; assert(k < 20, "brak migawki licznikow po stop")
      }
    }
    def counter(n : String) : Long = cli.readOk(Counters.addr(n))
    def stat() : CheckerStat = {
      val lockAt = counter("lock_at")
      val bad    = counter("bad")
      CheckerStat(counter("frames"), bad, counter("gaps"), counter("relocks"),
        if (lockAt == U32) -1L else lockAt,
        if (bad == 0) None
        else Some(CheckerErr(counter("err_n"),
          I2sWords(counter("err_got_l"), counter("err_got_r")),
          I2sWords(counter("err_exp_l"), counter("err_exp_r")))))
    }
    def capture() : Seq[Seq[Long]] =
      (0 until counter("cap_count").toInt).map(i =>
        (0 until Capture.WordsPerEntry).map(j => cli.readOk(Capture.Base + i * Capture.Stride + j)))
  }

  case class Env(d : I2sHarness, v : I2sHilVariant, sysCd : ClockDomain, dutCd : ClockDomain,
                 uart : HilUartSim, host : Host) {
    def patternFrames(espW : Int, n : Int) : Seq[Frame] =
      (0 until n).map { k => val f = I2sPattern.frame(seed, k.toLong, espW); Frame(f.l, f.r) }

    /** Strona ESP32 jako slave: kodek nadaje wzorzec, I2sPinRx odbiera. */
    def espSlave(espW : Int, frames : Int) : (I2sCodecModel, I2sPinRx) = {
      val c = new I2sCodecModel(d.io.i2s.sckOut, d.io.i2s.wsOut, d.io.i2s.sdOut, d.io.i2s.sdIn,
                                v.width, v.slotWidth, dutCd)
      c.wordWidth = espW
      c.send(patternFrames(espW, frames) : _*)
      c.start()
      val rx = new I2sPinRx(d.io.i2s.sckOut, d.io.i2s.wsOut, d.io.i2s.sdOut, espW, dutCd)
      rx.start()
      (c, rx)
    }

    /** Strona ESP32 jako master: I2sBusMaster, SCK w czasie symulacji. */
    def espMaster(espW : Int, espSlot : Int, frames : Seq[Frame]) : I2sBusMaster = {
      val m = new I2sBusMaster(d.io.i2s.sckIn, d.io.i2s.wsIn, d.io.i2s.sdIn, d.io.i2s.sdOut,
                               espSckHalf(v, espSlot), espSlot, espW)
      m.send(frames : _*)
      m.start()
      m
    }

    def waitUntil(cond : => Boolean, what : String) : Unit = {
      var t = 0
      while (!cond) { sleep(10000); t += 1; assert(t < 5000, s"timeout: $what") }
    }

    def framesSinceNow(count : => Int, n : Int, what : String) : Unit = {
      val target = count + n
      waitUntil(count >= target, what)
    }
  }

  private val compiled = mutable.Map[String, SimCompiled[I2sHarness]]()

  def scenario(v : I2sHilVariant, tp : String, sub : String = "")(body : Env => Unit) : Unit =
    testpoint(tp, variant = v.name + sub) {
      val dut = compiled.getOrElseUpdate(v.name, Config.sim
        .workspaceName(s"harness_${v.name}_${SimBackend.default.label}")
        .compile(I2sHarness(v, bg, build)))
      dut.doSim(s"harness_${v.name}_$tp$sub", seed = 42) { d =>
        val sysCd = ClockDomain(d.io.sysClk, d.io.sysRst)
        val dutCd = ClockDomain(d.io.dutClk, d.io.dutRst)
        d.io.i2s.sckIn #= false; d.io.i2s.wsIn #= true; d.io.i2s.sdIn #= false
        sysCd.forkStimulus(period = sysPeriod)
        dutCd.forkStimulus(period = dutPeriod)
        val u = new HilUartSim(sysCd, d.io.uart.rxd, d.io.uart.txd, scala.math.round(1e9 / bg.baud))
        u.start()
        val e = Env(d, v, sysCd, dutCd, u, new Host(new HilRegClient(u), sysCd))
        SimTimeout(60L * 1000 * 1000)                       // 60 ms czasu symulacji
        sysCd.waitSampling(50)
        body(e)
        u.checkFraming()
      }
    }

  /** Sprawdzenie strony ESP32 modelem referencyjnym. Ramki po stop (cisza)
    * obcinamy - w trakcie biegu cisza bylaby bledem, po stop jest norma. */
  def espSide(link : Link, frames : Seq[I2sWords]) : CheckerStat = {
    val trimmed = frames.reverse.dropWhile(_.isSilence).reverse
    I2sCheckerModel.run(link, trimmed)
  }

  def clean(what : String, s : CheckerStat, minFrames : Long) : Unit = {
    assert(s.locked && s.frames >= minFrames && s.bad == 0 && s.relocks == 0,
           s"$what: $s (min frames $minFrames)")
  }

  // ---------------------------------------------------------------------
  def masterRun(e : Env, espW : Int) : Unit = {
    val v = e.v
    val (_, rx) = e.espSlave(espW, 2000)
    e.host.setup(master = true, peerW = espW, slot = v.slotWidth)
    e.host.start()
    e.framesSinceNow(rx.frames.size, 40, "ramki od FPGA mastera")
    e.host.stop()
    val fpga = e.host.stat()
    info(s"${v.name} master espW=$espW FPGA: $fpga")
    clean("FPGA", fpga, 20)
    assert(e.host.counter("overflow") == 0)
    val esp = espSide(Link(seed, v.width, v.slotWidth, espW), rx.frames.toSeq)
    info(s"${v.name} master espW=$espW ESP32: $esp")
    clean("ESP32", esp, 30)
    assert(esp.gaps == 0, s"luki po stronie ESP32: $esp")
    val sent = e.host.counter("sent")
    assert(scala.math.abs(sent - esp.frames) <= 3, s"sent $sent, ESP32 odebral ${esp.frames}")
  }

  def slaveRun(e : Env, espW : Int, espSlot : Int) : Unit = {
    val v = e.v
    val m = e.espMaster(espW, espSlot, e.patternFrames(espW, 3000))
    e.host.setup(master = false, peerW = espW, slot = espSlot)
    e.host.start()
    e.framesSinceNow(m.frames.size, 40, "ramki ESP32 mastera")
    e.host.stop()
    val fpga = e.host.stat()
    info(s"${v.name} slave espW=$espW/$espSlot FPGA: $fpga")
    clean("FPGA", fpga, 20)
    val got = m.frames.toSeq.flatMap(_.received).map(f => I2sWords(f.left, f.right))
    val esp = espSide(Link(seed, v.width, espSlot, espW), got)
    info(s"${v.name} slave espW=$espW/$espSlot ESP32: $esp")
    clean("ESP32", esp, 30)
    m.check()
  }

  for (v <- full ++ smoke) {
    scenario(v, "harness_master") { e => masterRun(e, v.width) }
    scenario(v, "harness_slave")  { e => slaveRun(e, v.width, 32) }
  }

  for (v <- full) {
    scenario(v, "harness_regs") { e =>
      val c = e.host.cli
      assert(c.readOk(Addr.IpId) == ascii4("I2S"))
      assert(c.readOk(Addr.Variant) == v.code)
      assert(c.readOk(I2sHilRegs.Role) == I2sHilRegs.RoleSlave, "po resecie rola slave")
      assert(c.readOk(I2sHilRegs.PeerW) == v.width && c.readOk(I2sHilRegs.Slot) == v.slotWidth)
      c.writeOk(I2sHilRegs.PeerW, 24); assert(c.readOk(I2sHilRegs.PeerW) == 24)
      assert(c.read(0x103).status == Status.BadAddr)
      e.host.start()
      assert(c.write(I2sHilRegs.Role, 1).status == Status.Busy, "zapis roli w biegu")
      assert(c.readOk(I2sHilRegs.Role) == I2sHilRegs.RoleSlave)
      e.host.stop()
      assert(!e.d.io.i2s.clkOe.toBoolean, "rola slave: SCK/WS nie sa wyjsciami")
    }

    val other = if (v.width == 16) 24 else 16
    scenario(v, "harness_word_length_mismatch", "_slave")  { e => slaveRun(e, other, 32) }
    scenario(v, "harness_word_length_mismatch", "_master") { e => masterRun(e, other) }

    scenario(v, "harness_gaps") { e =>
      val (_, rx) = e.espSlave(v.width, 2000)
      e.host.setup(master = true, peerW = v.width, slot = v.slotWidth, gap = (1, 9, 2))
      e.host.start()
      e.framesSinceNow(rx.frames.size, 60, "ramki z lukami")
      e.host.stop()
      val link = Link(seed, v.width, v.slotWidth, v.width)
      val got  = rx.frames.toSeq.dropWhile(_.isSilence).reverse.dropWhile(_.isSilence).reverse
      val exp  = I2sGapModel.events(1, 9, 2, seed, got.size).map(_.fold(I2sWords(0, 0))(n => link.expected(n)))
      got.indices.find(k => got(k) != exp(k)).foreach(k =>
        fail(s"ramka $k na SD_OUT: ${got(k)}, oczekiwana ${exp(k)}"))
      info(s"${got.size} ramek, ${got.count(_.isSilence)} cichych")
      assert(got.count(_.isSilence) >= 8)
    }

    scenario(v, "harness_reset_injection") { e =>
      val m = e.espMaster(v.width, 32, e.patternFrames(v.width, 3000))
      // Odstepy ~ 20-30 ramek (ramka ~ 1000 cykli dut), reset 100 cykli.
      e.host.setup(master = false, peerW = v.width, slot = 32, rst = Some((3, 20000L, 8191L, 100)))
      e.host.start()
      e.framesSinceNow(m.frames.size, 130, "ramki z resetami")
      val locked = (e.host.cli.readOk(Addr.Status) & (1L << StatusBit.Locked)) != 0
      e.host.stop()
      val fpga = e.host.stat()
      info(s"${v.name} FPGA po 3 resetach: $fpga")
      assert(e.host.counter("rst_done") == 3)
      assert(fpga.bad >= 1, s"reset niewidoczny dla checkera: $fpga")
      assert(locked, "brak locka po resetach")
      m.clearViolations()
    }

    scenario(v, "harness_capture") { e =>
      val badAt = 150
      val frames = e.patternFrames(v.width, 3000).zipWithIndex.map {
        case (f, k) if k == badAt => Frame(f.left, f.right ^ 1L)
        case (f, _)               => f
      }
      val m = e.espMaster(v.width, 32, frames)
      val trigHigh = mutable.ArrayBuffer[Long]()          // cykle dut z TRIG = 1
      fork {
        var c = 0L
        while (true) { e.dutCd.waitSampling(); c += 1; if (e.d.io.trig.toBoolean) trigHigh += c }
      }
      e.host.setup(master = false, peerW = v.width, slot = 32)
      e.host.start()
      assert(m.frames.size < badAt - 20, s"bieg zaczal sie za pozno (${m.frames.size})")
      e.waitUntil(m.frames.size > badAt + 40, "ramki po bledzie")
      e.host.stop()
      val fpga = e.host.stat()
      info(s"${v.name} FPGA: $fpga")
      assert(fpga.bad == 1 && fpga.relocks == 0, s"$fpga")
      // ESP32 nadaje W = width w slocie 32, wiec transfer to tozsamosc.
      val expFrame = Link(seed, v.width, 32, v.width).expected(badAt)
      val err = fpga.firstErr.getOrElse(fail("brak first_err"))
      assert(err.exp == expFrame && err.got == I2sWords(expFrame.l, expFrame.r ^ 1L),
             s"first_err $err, oczekiwane $expFrame z przeklamanym LSB prawego kanalu")
      val cap = e.host.capture()
      assert(cap.size == Capture.Depth, s"cap_count ${cap.size}")
      val hit = cap.filter(x => x(1) == err.got.l && x(2) == err.got.r && x(3) == err.exp.l && x(4) == err.exp.r)
      assert(hit.size == 1, s"wpis bledu w capture: $hit")
      val idx = cap.map(_.head).sorted
      assert(idx == (idx.head until idx.head + idx.size), s"indeksy capture nieciagle: $idx")
      assert(hit.head.head == idx.head + Capture.Depth / 2, "blad nie w polowie okna")
      assert(trigHigh.size == 256 && trigHigh.last - trigHigh.head == 255,
             s"TRIG: ${trigHigh.size} cykli, od ${trigHigh.headOption} do ${trigHigh.lastOption}")
    }

    scenario(v, "harness_soft_reset") { e =>
      val m = e.espMaster(v.width, 32, e.patternFrames(v.width, 4000))
      e.host.setup(master = false, peerW = v.width, slot = 32)
      e.host.start()
      e.framesSinceNow(m.frames.size, 20, "przed soft resetem")
      e.host.w(Addr.Ctrl, 1L << CtrlBit.SoftReset)
      assert((e.host.cli.readOk(Addr.Status) & (1L << StatusBit.Running)) == 0)
      e.host.start()
      e.framesSinceNow(m.frames.size, 40, "po soft resecie")
      e.host.stop()
      val fpga = e.host.stat()
      info(s"${v.name} po soft resecie: $fpga")
      clean("FPGA", fpga, 20)
      m.clearViolations()
    }
  }

  testpoint("harness_param_bounds") {
    for (v <- I2sHilVariant.all) {
      assert(v.isLegal, s"$v")
      val margin = v.slaveMarginCycles(v.fs, 32)
      info(f"${v.name}: dut ${v.dutHz / 1e6}%.4f MHz, halfDiv ${v.halfDiv}, " +
           f"polokres SCK ESP32 = $margin%.2f cykli dut, ramka ${v.masterG.cyclesPerFrame} cykli")
      assert(v.slaveG.supportsSckHalf(margin), s"${v.name}: slave nie nadazy za SCK ESP32")
      assert(v.masterG.cyclesPerFrame >= 5)
    }
    // Symulacja: dut 21 ns, SCK ESP32 z espSckHalf - ten sam warunek w czasie symulacji.
    for (v <- full ++ smoke)
      assert(espSckHalf(v, 32) > dutPeriod * (v.slaveG.txLatencyCycles + 1), s"${v.name}: symulacja")
  }
}
