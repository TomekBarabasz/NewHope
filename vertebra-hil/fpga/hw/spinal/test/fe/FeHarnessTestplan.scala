package newhope.vertebra.hil.fe

import spinal.core._
import spinal.core.sim._
import scala.collection.mutable
import scala.util.Random
import newhope.i2s.I2sCodecModel
import newhope.i2s.I2sEvent.Frame
import newhope.vertebra.{Stage, Testpoint, TestplanSuite}
import newhope.vertebra.sim.SimBackend
import newhope.vertebra.hil._
import newhope.vertebra.hil.i2s.I2sPinRx
import HilProtocol._

// =====================================================================
//  ZRODLO PLANU
//  vertebra-hil.md §12, contract/fe/commands.md. Harness frontendu tak,
//  jak zobaczy go host: rejestry przez UART, bodziec i wynik na pinach I2S.
//
//  STRONA ESP32 w symulacji (ESP32 jest slave'em, FPGA daje SCK/WS):
//    I2sCodecModel (slot 32, slowo 32) nadaje bodziec na SD_IN:
//      L = slowo bodzca, R = ~L - dokladnie to, co nadaje fe_role.c
//    I2sPinRx odbiera ramki wyniku z SD_OUT - to, co ESP32 nagrywa w PSRAM
//  Ocena: FeCheck, ta sama funkcja co na stanowisku (FeHilTestplan).
//
//  Wariant d8 (dzielnik 8, ramka 512 cykli) skraca symulacje 9x wobec
//  mimas; numeryka toru od dzielnika nie zalezy (MicFrontEndTestplan).
//  mimas: tylko dymny test i elaboracja z prawdziwym dzielnikiem.
//
//  Zegary: sys okres 10, dut okres 21 (niecalkowity stosunek, jak I2S).
//
//  ODRZUCONE / GDZIE INDZIEJ
//   numeryka filtra w rogach (overrun, min spacing, latencja)
//              - DcFilterTestplan; tu probka co 512 cykli
//   bity I2S (opoznienie, kanal prawy) - I2sMicRxTestplan; tu tylko tyle,
//              ile widac w echu x
// =====================================================================
object FeHarnessPlan {
  val plan : Seq[Testpoint] = Seq(
    Testpoint("fe_harness_param_bounds", Stage.V1,
      "Warianty legalne, ramka powrotna i zegar",
      checking = Seq("kazdy wariant FeHilVariant legalny (polokres SCK > opoznienie nadajnika powrotnego)",
                     "DCM_CLKGEN daje zegar dut wariantu dokladnie (mimas: 75 MHz = 100 * 3 / 4)",
                     "FeFrame.encode / decode: odwrotne na losowych ramkach, cisza bez znacznika",
                     "FeCheck na 262 144 ramkach (pelny bufor ESP32) < 20 s")),
    Testpoint("fe_harness_regs", Stage.V1,
      "Identyfikacja, blok IP, SCK/WS przed startem",
      checking = Seq("ip_id == 'FE', variant == kod wariantu",
                     "bypass_from / bypass_to / tail / dump_every: domyslne, zapis i odczyt, busy w biegu",
                     "przed pierwszym startem clkOe == 0 i SCK stoi")),
    Testpoint("fe_harness_chain", Stage.V1,
      "Bodziec od ESP32 przez N0 + N1 i z powrotem",
      stimulus = Seq("mowa z offsetem DC, 300 probek"),
      checking = Seq("FeCheck: start od idx 0, ciaglosc, y == DcGolden(x), x == bodziec >> 14, zera po bodzcu",
                     "liczniki: frames == ramki z nagrania, x_sum / y_sum zgodne, overflow == dc_overrun == 0")),
    Testpoint("fe_harness_corners", Stage.V1,
      "Rogi zakresu przez caly tor",
      stimulus = Seq("+-FS co probke, skok -FS -> +FS, losowe slowa 32 b"),
      checking = Seq("FeCheck czysty dla kazdego bodzca, kazdy w osobnym biegu (stan od zera)")),
    Testpoint("fe_harness_bypass", Stage.V1,
      "Okno bypassu w srodku strumienia",
      stimulus = Seq("bypass_from 100, bypass_to 200, 300 probek"),
      checking = Seq("FeCheck: flaga bypass dokladnie w [100, 200), tam y == x, poza nim golden calego strumienia")),
    Testpoint("fe_harness_reset", Stage.V1,
      "Reset DUT-a z HilResetInjector w trakcie strumienia",
      stimulus = Seq("3 resety po 100 cykli dut, odstep 30-60 ramek"),
      checking = Seq("rst_done == 3; 3 flagi rst poza startem",
                     "FeCheck: na kazdym odcinku y == DcGolden od stanu zerowego",
                     "idx ciagle poza <= 2 ramkami tuz przed flaga rst; frames FPGA == nagranie + zgubione")),
    Testpoint("fe_harness_stop_tail", Stage.V1,
      "Stop: migawka, cisza przez tail probek, potem SCK staje; soft reset zwalnia piny",
      checking = Seq("po stop ramki bez znacznika, liczniki z migawki == nagranie",
                     "status.locked (DUT taktuje) gasnie po tail probkach na granicy ramki, clkOe zostaje",
                     "kolejny bieg zaczyna sie od idx 0 z rst",
                     "soft reset: clkOe == 0")),
    Testpoint("fe_harness_n4_chain", Stage.V1,
      "N0 -> N4 (Framer 512/160, FftCore, RealUnpack, PowerSpectrum): rekordy CRC i zrzuty",
      stimulus = Seq("mowa z offsetem, 2200 probek (d8_n4: numeryka 512/160 jak na plytce)", "dump_every 4"),
      checking = Seq("FeCheck czysty (N0, N1, ramka v2 z kanalem pomocniczym)",
                     "FeN4Check: kazda weryfikowalna ramka ma rekord CRC zgodny na N2, N3 i N4, poza koncowka nagrania",
                     "wszystkie ramki weryfikowalne (Framer: zera w miejsce pozycji niezapisanych od resetu)",
                     "zrzuty N4 zgodne z FftGolden.power prazek po prazku, co najmniej 1",
                     "n4_frames == ramki golden, n4_aux_drop == n4_order == fr_overrun == 0")),
    Testpoint("fe_harness_n4_reset", Stage.V1,
      "Reset DUT-a w trakcie strumienia z N4",
      stimulus = Seq("2 resety po 100 cykli dut, odstep 700-828 probek, 2600 probek"),
      checking = Seq("FeN4Check: golden z pierscieniem zer po kazdym resecie, rekordy zgodne, >= 4 zweryfikowane",
                     "brak rekordu najwyzej dla jednej ramki na reset, rekordy rozerwane tylko przy resetach")),
    Testpoint("fe_harness_mimas", Stage.V2,
      "Wariant plytki (dzielnik 73) w symulacji",
      stimulus = Seq("40 probek mowy"),
      checking = Seq("FeCheck czysty, liczniki zgodne")),
    Testpoint("fe_harness_n4_bounds", Stage.V1,
      "Rekordy N4 i warianty z widmem bez symulacji",
      checking = Seq("mimas_n4 i d8_n4 legalne: Rfft na zegarze i fs I2S, zapas czasu ramki",
                     "FeN4.crc == CRC32 bit po bicie (wektor znany), rekordy bytes -> parse odwrotne",
                     "przepustowosc aux: rekord CRC na hop miesci sie z zapasem, zrzut co dump_every ramek nadaza"))
  )

  val bg        = HilBridgeGenerics(baud = 1562500L, timeoutUs = 100L)
  val sysPeriod = 10
  val dutPeriod = 21
  val build     = 0x0badc0deL
}

class FeHarnessTestplan extends TestplanSuite {
  import FeHarnessPlan._
  def testplan : Seq[Testpoint] = plan

  /** Host: to, co zrobi FpgaDevice z FeFpgaMap. */
  class Host(val cli : HilRegClient) {
    def w(a : Int, v : Long) : Unit = cli.writeOk(a, v)
    def status : Long = cli.readOk(Addr.Status)
    def setup(bypass : (Long, Long) = (0, 0), tail : Int = FeHilRegs.TailDefault,
              rst : Option[(Int, Long, Long, Int)] = None, dumpEvery : Int = FeHilRegs.DumpEveryDefault) : Unit = {
      w(FeHilRegs.BypassFrom, bypass._1); w(FeHilRegs.BypassTo, bypass._2); w(FeHilRegs.Tail, tail)
      w(FeHilRegs.DumpEvery, dumpEvery)
      val (cnt, min, mask, len) = rst.getOrElse((0, 0L, 0L, 1))
      w(Addr.RstCount, cnt); w(Addr.RstSeed, 0x5eedL); w(Addr.RstMin, min); w(Addr.RstMask, mask); w(Addr.RstLen, len)
    }
    def start() : Unit = w(Addr.Ctrl, 1L << CtrlBit.Start)
    def stop() : Unit = {
      w(Addr.Ctrl, 1L << CtrlBit.Stop)
      var k = 0
      while ((status & (1L << StatusBit.Snapshot)) == 0) { k += 1; assert(k < 20, "brak migawki licznikow po stop") }
    }
    def counter(n : String) : Long = cli.readOk(FeHilRegs.counterAddr(n))
    def counters : Map[String, Long] = FeHilRegs.counters.map(n => n -> counter(n)).toMap
  }

  case class Env(d : FeHarness, v : FeHilVariant, sysCd : ClockDomain, dutCd : ClockDomain, host : Host) {
    val esp = new I2sCodecModel(d.io.i2s.sckOut, d.io.i2s.wsOut, d.io.i2s.sdOut, d.io.i2s.sdIn,
                                FeFrame.SlotBits, FeFrame.SlotBits, dutCd)
    val rec = new I2sPinRx(d.io.i2s.sckOut, d.io.i2s.wsOut, d.io.i2s.sdOut, FeFrame.SlotBits, dutCd)
    esp.start(); rec.start()

    def load(stim : Seq[Long]) : Unit = esp.send(stim.map(l => Frame(l, FeFrame.stimRight(l))) : _*)
    def recorded : Seq[(Long, Long)] = rec.frames.toSeq.map(f => (f.l, f.r))
    def frameNs : Long = v.fe.i2s.cyclesPerSample * dutPeriod

    def waitUntil(cond : => Boolean, what : String) : Unit = {
      var t = 0
      while (!cond) { sleep(10000); t += 1; assert(t < 20000, s"timeout: $what") }
    }

    /** Bieg: start, `n` ramek wyniku, stop, cisza tail. Zwraca liczniki z migawki. */
    def run(n : Int) : Map[String, Long] = {
      val before = rec.frames.size
      host.start()
      waitUntil(rec.frames.size >= before + n + 4, s"$n ramek wyniku")
      host.stop()
      host.counters
    }

    def check(stim : Seq[Long], from : Int, c : Map[String, Long], bypass : (Long, Long) = (0, 0)) : FeCheck.Report = {
      // ramki do konca ciszy po stop (tail), zeby nagranie mialo wszystko
      waitUntil((host.status & (1L << StatusBit.Locked)) == 0, "koniec tail")
      val r = FeCheck.analyze(v, stim, recorded.drop(from), bypass)
      info(s"${v.name}: ${r.summary}")
      r.info.foreach(s => info(s"  $s"))
      assert(r.ok, r.errors.mkString("\n"))
      assert(c("frames") == r.frames.size, s"FPGA frames ${c("frames")}, nagranie ${r.frames.size}")
      assert((c("x_sum"), c("y_sum")) == FeCheck.sums(r.frames), s"sumy FPGA ${c("x_sum")}/${c("y_sum")}")
      assert(c("overflow") == 0 && c("dc_overrun") == 0, s"$c")
      assert(c("sent") == c("frames"), s"sent ${c("sent")} != frames ${c("frames")}")
      r
    }
  }

  private val compiled = mutable.Map[String, SimCompiled[FeHarness]]()

  def scenario(v : FeHilVariant, tp : String, sub : String = "")(body : (Env, Random) => Unit) : Unit =
    testpoint(tp, variant = v.name + (if (sub.isEmpty) "" else s"_$sub")) {
      val dut = compiled.getOrElseUpdate(v.name, Config.sim
        .workspaceName(s"fe_harness_${v.name}_${SimBackend.default.label}")
        .compile(FeHarness(v, bg, build)))
      dut.doSim(s"fe_harness_${v.name}_$tp$sub", seed = 42) { d =>
        val sysCd = ClockDomain(d.io.sysClk, d.io.sysRst)
        val dutCd = ClockDomain(d.io.dutClk, d.io.dutRst)
        d.io.i2s.sdIn #= false
        sysCd.forkStimulus(period = sysPeriod)
        dutCd.forkStimulus(period = dutPeriod)
        val u = new HilUartSim(sysCd, d.io.uart.rxd, d.io.uart.txd, scala.math.round(1e9 / bg.baud))
        u.start()
        val e = Env(d, v, sysCd, dutCd, new Host(new HilRegClient(u)))
        SimTimeout(400L * 1000 * 1000)
        sysCd.waitSampling(50)
        body(e, new Random((tp + sub).hashCode))
        u.checkFraming()
      }
    }

  val d8 = FeHilVariant.d8

  scenario(d8, "fe_harness_regs") { (e, _) =>
    val c = e.host.cli
    assert(c.readOk(Addr.IpId) == ascii4("FE"))
    assert(c.readOk(Addr.Variant) == d8.code)
    assert(c.readOk(FeHilRegs.BypassFrom) == 0 && c.readOk(FeHilRegs.BypassTo) == 0)
    assert(c.readOk(FeHilRegs.Tail) == FeHilRegs.TailDefault)
    c.writeOk(FeHilRegs.Tail, 7); assert(c.readOk(FeHilRegs.Tail) == 7)
    assert(c.readOk(FeHilRegs.DumpEvery) == FeHilRegs.DumpEveryDefault)
    assert(c.read(0x104).status == Status.BadAddr)
    assert(!e.d.io.i2s.clkOe.toBoolean, "przed startem SCK/WS nie sa wyjsciami")
    val sck0 = mutable.Set[Boolean]()
    for (_ <- 0 until 200) { e.dutCd.waitSampling(); sck0 += e.d.io.i2s.sckOut.toBoolean }
    assert(sck0 == Set(false), "SCK taktuje przed startem")
    e.host.start()
    assert(c.write(FeHilRegs.BypassTo, 1).status == Status.Busy, "zapis w biegu")
    e.dutCd.waitSampling(100)
    assert(e.d.io.i2s.clkOe.toBoolean, "po starcie SCK/WS sa wyjsciami")
    e.host.stop()
  }

  scenario(d8, "fe_harness_chain") { (e, rng) =>
    val stim = FeStimulus.noisy(d8.fe.i2s.fs, 300, rng)
    e.load(stim)
    e.host.setup()
    val c = e.run(stim.size + 20)
    val r = e.check(stim, 0, c)
    assert(r.lead.isDefined && r.resets == 0)
    assert(c("underrun") <= 4, s"underrun ${c("underrun")}: wiecej niz rozbieg nadajnika powrotnego")
  }

  // Kazdy bodziec w osobnej symulacji: model ESP32 (I2sCodecModel) nie
  // umie zaczac od nowa po zatrzymaniu SCK, a ESP32 na plytce zaczyna kazdy
  // bieg od swiezego kanalu I2S.
  for ((name, mk) <- Seq[(String, Random => Seq[Long])](
         "square" -> (FeStimulus.square(200, _)), "step" -> (FeStimulus.step(200, _)),
         "random" -> (FeStimulus.random(200, _))))
    scenario(d8, "fe_harness_corners", name) { (e, rng) =>
      val stim = mk(rng)
      e.load(stim)
      e.host.setup()
      e.check(stim, 0, e.run(stim.size + 10))
    }

  scenario(d8, "fe_harness_bypass") { (e, rng) =>
    val stim = FeStimulus.noisy(d8.fe.i2s.fs, 300, rng)
    e.load(stim)
    e.host.setup(bypass = (100, 200))
    val c = e.run(stim.size + 10)
    val r = e.check(stim, 0, c, bypass = (100, 200))
    assert(r.bypassed == 100, s"w bypassie ${r.bypassed} ramek")
  }

  scenario(d8, "fe_harness_reset") { (e, rng) =>
    val stim = FeStimulus.noisy(d8.fe.i2s.fs, 400, rng)
    e.load(stim)
    // ramka 512 cykli dut: odstep 30-62 ramek, reset 100 cykli
    e.host.setup(rst = Some((3, 30L * 512, 16383L, 100)))
    val c = e.run(250)
    assert(c("rst_done") == 3, s"rst_done ${c("rst_done")}")
    val r = FeCheck.analyze(d8, stim, e.recorded)
    info(s"d8: ${r.summary}")
    assert(r.ok, r.errors.mkString("\n"))
    assert(r.resets == 3, s"flagi rst: ${r.resets}")
    assert(c("frames") == r.frames.size + r.lost, s"FPGA frames ${c("frames")}, nagranie ${r.frames.size} + ${r.lost}")
  }

  scenario(d8, "fe_harness_stop_tail") { (e, rng) =>
    val tail = 40
    val stim = FeStimulus.noisy(d8.fe.i2s.fs, 400, rng)
    e.load(stim)
    e.host.setup(tail = tail)
    val c = e.run(60)
    e.waitUntil((e.host.status & (1L << StatusBit.Locked)) == 0, "DUT staje po tail")
    val all     = e.recorded
    val lastOut = all.lastIndexWhere { case (l, r) => FeFrame.decode(l, r, d8.sampleWidth).isDefined }
    val silent  = all.size - 1 - lastOut
    info(s"po ostatniej ramce wyniku $silent ramek ciszy (tail $tail)")
    assert(silent >= tail - 1 && silent <= tail + 2, s"$silent ramek ciszy po stop przy tail $tail")
    assert(e.d.io.i2s.clkOe.toBoolean, "po stop SCK/WS zostaja wyjsciami")
    val sck = mutable.Set[Boolean]()
    for (_ <- 0 until 2000) { e.dutCd.waitSampling(); sck += e.d.io.i2s.sckOut.toBoolean }
    assert(sck == Set(false), "SCK taktuje po tail")
    val r1 = FeCheck.analyze(d8, stim, all)
    assert(r1.errors.forall(_.startsWith("N0: nagranie urywa")), r1.errors.mkString("\n"))
    assert(c("frames") == r1.frames.size)
    // Drugi bieg od nowa: start od idx 0 z rst i stanu zerowego. Bodziec
    // bez oceny N0 - model ESP32 nie zaczyna od nowa (patrz corners).
    val from = e.rec.frames.size
    e.esp.resync()
    val c2 = e.run(100)
    e.waitUntil((e.host.status & (1L << StatusBit.Locked)) == 0, "koniec tail")
    val r2 = FeCheck.analyze(d8, Nil, e.recorded.drop(from))
    info(s"drugi bieg: ${r2.summary}")
    assert(r2.ok && r2.frames.size == c2("frames"), r2.errors.mkString("\n"))
    e.host.w(Addr.Ctrl, 1L << CtrlBit.SoftReset)
    e.dutCd.waitSampling(50)
    assert(!e.d.io.i2s.clkOe.toBoolean, "po soft resecie SCK/WS w wysokiej impedancji")
  }

  // --- N2 - N4 ---------------------------------------------------------------
  val d8N4 = FeHilVariant.d8N4

  def n4Check(e : Env, r : FeCheck.Report, c : Map[String, Long]) : FeN4Check.Report = {
    val n = FeN4Check.analyze(e.v, r)
    info(s"${e.v.name}: ${n.summary}")
    n.info.foreach(s => info(s"  $s"))
    assert(n.ok, n.errors.mkString("\n"))
    assert(c("n4_aux_drop") == 0 && c("n4_order") == 0 && c("fr_overrun") == 0, s"$c")
    n
  }

  // Framer po resecie podaje zera zamiast pozycji niezapisanych, wiec
  // golden zna kazda ramke. Pierwszy zrzut to ramka nr dump_every = 4
  // (trig 799) i ma zdazyc wyjsc: 799 + 1291 B + kolejka -> ok. 2150 probek.
  scenario(d8N4, "fe_harness_n4_chain") { (e, rng) =>
    val stim = FeStimulus.noisy(d8N4.fe.i2s.fs, 2200, rng)
    e.load(stim)
    e.host.setup(dumpEvery = 4)
    val c = e.run(stim.size + 20)
    val r = e.check(stim, 0, c)
    val n = n4Check(e, r, c)
    assert(n.unknown == 0 && n.crcRecs + n.missingAtEnd == n.frames && n.crcRecs >= 5 &&
           n.dumps >= 1 && n.torn == 0, n.summary)
    assert(c("n4_frames") == n.frames, s"n4_frames ${c("n4_frames")}, golden ${n.frames}")
    assert(c("n4_crc") >= n.crcRecs && c("n4_dump") >= n.dumps, s"$c")
  }

  // Kazdy odcinek od pierscienia zer; ramki tuz przed resetem moga nie
  // zdazyc albo miec w oknie probki zgubione przy resecie (bez golden).
  scenario(d8N4, "fe_harness_n4_reset") { (e, rng) =>
    val stim = FeStimulus.noisy(d8N4.fe.i2s.fs, 2600, rng)
    e.load(stim)
    // ramka 512 cykli dut: resety po 700-828 probkach
    e.host.setup(rst = Some((2, 700L * 512, 65535L, 100)), dumpEvery = 4)
    val c = e.run(2600)
    e.waitUntil((e.host.status & (1L << StatusBit.Locked)) == 0, "koniec tail")
    assert(c("rst_done") == 2)
    val r = FeCheck.analyze(d8N4, stim, e.recorded)
    info(s"d8_n4: ${r.summary}")
    assert(r.ok && r.resets == 2, r.errors.mkString("\n"))
    val n = n4Check(e, r, c)
    assert(n.crcRecs >= 4 && n.missingNearReset <= 2, n.summary)
  }

  scenario(FeHilVariant.mimas, "fe_harness_mimas") { (e, rng) =>
    val stim = FeStimulus.noisy(FeHilVariant.mimas.fe.i2s.fs, 40, rng)
    e.load(stim)
    e.host.setup(tail = 4)
    val c = e.run(stim.size + 4)
    e.check(stim, 0, c)
  }

  testpoint("fe_harness_n4_bounds") {
    for (v <- Seq(FeHilVariant.mimasN4, d8N4)) {
      assert(v.isLegal, s"${v.name}: ${v.problems.mkString("; ")}")
      val g = v.rfft.get
      val hopSamples = g.framer.hop
      info(f"${v.name}: Framer ${g.framer.fftSize}/${g.framer.hop}, ${g.bins} prazkow, ramka zajmuje toru " +
           f"${g.frameBusyCycles} z ${hopSamples * g.cyclesPerSample} cykli (zapas ${g.marginCycles}), " +
           s"rekord CRC ${FeN4.CrcLen} B / hop $hopSamples B, zrzut ${FeN4.dumpLen(g.bins)} B")
      assert(FeN4.CrcLen < hopSamples / 4, "rekord CRC nie miesci sie w hopie z zapasem")
      // zrzut co dump_every ramek: ramki CRC z tego czasu + zrzut <= dump_every hopow
      val de = FeHilRegs.DumpEveryDefault
      assert(FeN4.dumpLen(g.bins) + de * FeN4.CrcLen <= de * hopSamples, s"${v.name}: zrzut co $de ramek nie nadaza")
      val duringDump = FeN4.dumpLen(g.bins) / hopSamples + 2
      assert(duringDump * FeN4.CrcLen <= 256, s"kolejka bajtow CRC (256) za mala na $duringDump rekordow w trakcie zrzutu")
    }
    // CRC32 (0x04C11DB7, start ~0, bez odbicia, bez XOR koncowego) po bajtach "123456789" = 0x0376E6E7
    assert(FeN4.crc("123456789".map(_.toLong), 8) == 0x0376E6E7L, f"${FeN4.crc("123456789".map(_.toLong), 8)}%08x")
    val rng = new Random(3)
    val c = FeN4.CrcRec(0xABCDEFL, 7, Vector.fill(3)(rng.nextLong() & FeFrame.U32), Vector(0, -3, -6))
    assert(FeN4.parse(FeN4.bytes(c), 257).contains(c))
    val d = FeN4.DumpRec(12345L, -6, Vector.fill(257)(rng.nextLong() & ((1L << 36) - 1)))
    assert(FeN4.parse(FeN4.bytes(d), 257).contains(d))
  }

  testpoint("fe_harness_param_bounds") {
    for (v <- FeHilVariant.all :+ d8) {
      assert(v.isLegal, s"${v.name}: ${v.problems.mkString("; ")}")
      info(f"${v.name}: dut ${v.dutHz / 1e6}%.4f MHz, sckDiv ${v.fe.i2s.sckDiv}, fs ${v.fe.i2s.fs}%.2f Hz, " +
           f"SCK nisko ${v.fe.i2s.sckLow} / wysoko ${v.fe.i2s.sckHigh} cykli (nadajnik powrotny > ${FeFrame.txSlave.txLatencyCycles}), " +
           s"variant ${v.code.toHexString}")
    }
    for (v <- FeHilVariant.all) {
      val (m, d, hz) = DcmClkGen.best(HilBridgeGenerics().clkHz, v.dutHz)
      info(f"${v.name}: DCM $m/$d = ${hz / 1e6}%.4f MHz")
      assert(hz == v.dutHz.toDouble, s"${v.name}: DCM nie daje dokladnie ${v.dutHz} Hz")
    }
    val rng = new Random(1)
    val sw  = FeHilVariant.mimas.sampleWidth
    for (_ <- 0 until 1000) {
      val o = FeFrame.Out(rng.nextInt(1 << FeFrame.IdxBits),
                          rng.nextInt(1 << sw) - (1 << (sw - 1)), rng.nextInt(1 << sw) - (1 << (sw - 1)),
                          rng.nextBoolean(), rng.nextBoolean(), rng.nextBoolean(),
                          if (rng.nextBoolean()) Some((rng.nextBoolean(), rng.nextInt(256))) else None)
      val (l, r) = FeFrame.encode(o, sw)
      assert(FeFrame.decode(l, r, sw).contains(o), s"$o -> $l, $r")
    }
    assert(FeFrame.decode(0, 0, sw).isEmpty)
    // FeCheck na nagraniu wielkosci pelnego bufora ESP32 (hw_fe_long): ma
    // trwac sekundy, nie minuty (na List ocena byla O(n^2)).
    val mv   = FeHilVariant.mimas
    val big  = FeStimulus.noisy(mv.fe.i2s.fs, 262144, rng).toList
    val xs   = big.map(FeFrame.micSample(_, sw))
    val ys   = newhope.frontend.DcGolden.run(mv.fe.dc, xs).y
    val raw  = xs.indices.map(k => FeFrame.encode(FeFrame.Out(k & FeFrame.IdxMask, xs(k), ys(k), k == 0, false, false), sw)).toList
    val t0   = System.nanoTime
    val rep  = FeCheck.analyze(mv, big, raw)
    val sec  = (System.nanoTime - t0) / 1e9
    info(f"FeCheck: ${raw.size} ramek w $sec%.1f s")
    assert(rep.ok && rep.frames.size == raw.size, rep.errors.mkString("; "))
    assert(sec < 20, f"FeCheck za wolny: $sec%.1f s")
    // Diagnoza N0: bodziec spozniony o 300 ramek zer (rozbieg DMA ESP32)
    val late = List.fill(300)(0L) ++ xs.take(2000)
    val gl   = newhope.frontend.DcGolden.run(mv.fe.dc, late).y
    val rawL = late.indices.map(k => FeFrame.encode(FeFrame.Out(k & FeFrame.IdxMask, late(k), gl(k), k == 0, false, false), sw)).toList
    val repL = FeCheck.analyze(mv, big.take(2000), rawL)
    info(s"spozniony bodziec: ${repL.errors.mkString("; ")}")
    assert(repL.errors.size == 1 && repL.errors.head.contains("zaczyna sie od ramki 300") &&
           repL.errors.head.contains("rozbieg"), repL.errors.mkString("; "))
    assert(FeFrame.micSample(FeStimulus.word(FeStimulus.MicMin, 0xFF), sw) == -(1L << (sw - 1)))
    assert(FeFrame.micSample(FeStimulus.word(0x00003F, 0xFF), sw) == 0, "bity [5:0] slowa 24 odrzucone")
  }
}
