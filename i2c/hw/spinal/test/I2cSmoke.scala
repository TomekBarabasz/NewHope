package newhope.i2c

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.FlowMonitor
import newhope.vertebra.sim.SimBackend
import scala.collection.mutable

// =====================================================================
//  JEDEN przebieg smoke - kompilacja DUT-a dla zadanego generyka plus
//  jedna transakcja zapis/odczyt. NIE rzuca: wszystkie awarie wracaja
//  w Result. To jest cala roznica miedzy tym a zwyklym testem - sweep
//  musi przejsc przez komorki, ktore z zalozenia nie dzialaja.
//
//  Przebieg dotyka OBU torow filtra wejsciowego:
//
//   - tor SCL: po puszczeniu linii `filter.scl` widzi zero jeszcze przez
//     filterLatency cykli, wiec `stretching` (sclReg && !filter.scl) jest
//     falszywie aktywne i `when(stretching) { timer.restart() }` rozciaga
//     cwiartke. Koszt: filterLatency + 1 cykl na kazde puszczenie SCL.
//
//   - tor SDA: io.rsp.data := filter.sda probkowane w Q2, a poziom
//     wystawiony w Q0.
//
//  KOMPENSACJA (zmierzona, nie zalozona). Okno na ustalenie SDA to NIE
//  sa dwie cwiartki, tylko 2*quarterCycles + filterLatency - bo Q1 jest
//  rozciagane opoznieniem toru SCL. Oba tory maja to samo okno, wiec
//  opoznienie SDA jest kompensowane co do cyklu i readOk nie moze paść
//  z powodu filtra. FilterSweep --w 1:20 --q 4:4 daje same kropki.
//
//  UWAGA: kompensacja znika, jesli tory dostana rozne okna filtra.
//  Wtedy granica odczytu pojawia sie naprawde i ten przebieg zacznie
//  zwracac "R".
//
//  Skoro werdykt jest slepy na filtr, wlasciwym wynikiem sa METRYKI:
//  sdaLatency i stretchCycles z FilterProbe. Granice widac dopiero w
//  siatce stretch - krzywa 10*(w+4) nasyca sie w punkcie, w ktorym
//  filterLatency przekracza polokres SCL i filtr przestaje sledzic
//  magistrale (patrz I2cGenerics.filterTracksScl).
//
//  Model konstrukcyjny (I2cGenerics): filterLatency = filterWindow + 3,
//  czyli BufferCC(2) + napelnienie okna(w) + rejestr value(1). Zmierzone
//  co do cyklu dla w = 1..20. Kalibracja: FilterSweep --model.
//
//  Sekcja drivera (cmd / byte / cmdWithSda / setup) jest JEDYNA w
//  projekcie - wolaja ja I2cPhySuite, I2cPhyTestplan i `run` ponizej.
//  Gdyby byla napisana trzy razy, za pol roku bylyby to trzy lekko
//  rozjechane drivery i sweep przestalby mowic cokolwiek o regresji.
// =====================================================================
object I2cSmoke {
  import I2cEvent._
  import I2cPhyCmdMode._

  /** Bajt wysylany w torze zapisu. 0xA5 = 10100101: zmienia sie na
    * kazdej granicy bitu, wiec spoznione probkowanie widac natychmiast.
    * 0x00 albo 0xFF nie wykrylyby opoznienia filtra w ogole. */
  val payload = 0xA5

  case class Result(protocolOk : Boolean,
                    readOk     : Boolean,
                    timedOut   : Boolean,
                    compiled   : Boolean = true,
                    note       : String  = "",
                    // METRYKI z FilterProbe. -1 == nie zmierzono (np. gdy
                    // kompilacja padla). Ida do osobnych siatek w sweepie,
                    // zeby dalo sie je trzymac w baseline razem z werdyktem.
                    sdaLatency    : Int = -1,
                    stretchCycles : Int = -1) {

    def ok : Boolean = compiled && protocolOk && readOk && !timedOut

    /** Symbol do siatki. Kolejnosc sprawdzania od najciezszej awarii. */
    def verdict : String =
      if (!compiled)   "C"      // elaboracja/kompilacja padla
      else if (timedOut)    "T" // symulacja nie skonczyla sie w budzecie
      else if (!protocolOk) "P" // niezmiennik I2C zlamany albo zla sekwencja
      else if (!readOk)     "R" // rsp nie zgadza sie z linia
      else                  "."
  }

  // --- driver komend (jedyna definicja w projekcie) ------------------
  def cmd(d : I2cPhyBase, mode : SpinalEnumElement[I2cPhyCmdMode.type],
          data : Boolean = true) : Unit = {
    d.io.cmd.valid        #= true
    d.io.cmd.payload.mode #= mode
    d.io.cmd.payload.data #= data
    d.clockDomain.waitSamplingWhere(d.io.cmd.ready.toBoolean)
    d.io.cmd.valid #= false
  }
  
  /** Jak cmd, ale zaklocenie SDA wchodzi dopiero w Q0 juz przyjetej
    * komendy. Bez tego "slave" odpowiada, zanim master zdazyl zapytac,
    * i filtr dostaje na dojscie caly czas sprzed handshake'u. */
  def cmdWithSda(d : I2cPhyBase, bus : I2cBusModel,
                 mode : SpinalEnumElement[I2cPhyCmdMode.type],
                 data : Boolean, pull : Boolean) : Unit = {
    d.io.cmd.valid        #= true
    d.io.cmd.payload.mode #= mode
    d.io.cmd.payload.data #= data
    d.clockDomain.waitSampling()      // DUT zatrzasnal valid -> Q0 trwa
    bus.sdaPull = pull
    d.clockDomain.waitSamplingWhere(d.io.cmd.ready.toBoolean)
    d.io.cmd.valid #= false
  }

  def byte(d : I2cPhyBase, v : Int) : Unit =
    for (i <- 7 downto 0) cmd(d, BIT, ((v >> i) & 1) != 0)

  /** Monitor ma STALE okno filtra, niezalezne od g.filterWindow - patrz
    * naglowek I2cAgent.scala. Przypiecie go do generyka DUT-a sprawialo,
    * ze sweep mierzyl sume opoznien DUT-a i testbenchu zamiast samego
    * DUT-a. Dlatego `g` zniknelo z sygnatury. */
  def setup(d : I2cPhyBase) : (I2cBusModel, I2cMonitor) = {
    val bus = new I2cBusModel(d)
    val mon = new I2cMonitor(d, bus)
    d.io.cmd.valid     #= false
    d.io.pins.scl.read #= true
    d.io.pins.sda.read #= true
    d.clockDomain.forkStimulus(period = 10)
    bus.start()
    mon.start()
    d.clockDomain.waitSampling(5)
    (bus, mon)
  }

  // --- przebieg ------------------------------------------------------
  /** Kompiluje DUT-a dla podanego generyka i odpala jeden smoke.
    * NIE rzuca - wszystkie awarie wracaja w Result. To jest cala roznica
    * miedzy tym a zwyklym testem: sweep musi przejsc przez komorki,
    * ktore z zalozenia nie dzialaja. */
  def run(g         : I2cGenerics,
          build     : I2cGenerics => I2cPhyBase,
          workspace : String,
          wave      : Boolean = false,
          backend   : SimBackend = SimBackend.Verilator) : Result = {

    // Elaboracja tez moze sie wywalic - np. assert(quarterCycles >= 2)
    // w I2cGenerics. To osobny werdykt, nie awaria filtra.
    val compiled : Either[String, SimCompiled[I2cPhyBase]] =
      try {
        val base = Config.simFor(backend).workspaceName(workspace)
        Right((if (wave) base.withFstWave else base).compile { build(g) })
      } catch {
        case e : Throwable => Left(s"${e.getClass.getSimpleName}: ${e.getMessage}")
      }

    // .left.get / .right.get sa deprecated w 2.13 - Either jest
    // right-biased, wiec bierzemy to wzorcem.
    val sim = compiled match {
      case Left(err) =>
        return Result(protocolOk = false, readOk = false, timedOut = false,
                      compiled = false, note = err)
      case Right(s) => s
    }

    var proto    = false
    var read     = false
    var timedOut = false
    var note     = ""
    var latency  = -1
    var stretch  = -1

    try {
      sim.doSim("smokeAndRead", seed = 42) { d =>
        // Bez tego sweep nie failuje, tylko WISI. waitSamplingWhere na
        // cmd.ready nie ma wlasnego limitu, a przy zlym filtrze
        // sekwencer potrafi nie domknac cwiartki.
        SimTimeout(budget(g))

        val (bus, mon) = setup(d)
        val probe = new FilterProbe(d)
        probe.start()
        val seen = mutable.Queue[Boolean]()
        FlowMonitor(d.io.rsp, d.clockDomain) { p => seen.enqueue(p.data.toBoolean) }

        cmd(d, START)
        byte(d, payload)
        cmdWithSda(d, bus, BIT, data = true, pull = true)   // tor odczytu
        bus.sdaPull = false
        cmd(d, STOP)
        d.clockDomain.waitSampling(20)

        val bits     = (7 downto 0).map(i => ((payload >> i) & 1) != 0)
        val expected = (Start +: bits.map(Bit(_))) ++ Seq(Bit(false), Stop)

        val got = mon.drain()
        proto = mon.violations.isEmpty && got == expected
        read  = seen.toSeq == (bits :+ false)

        latency = probe.maxSdaLatency
        stretch = probe.stretchCycles

        if (!proto) note = s"got=$got viol=${mon.violations.mkString("|")}"
        else if (!read) note = s"rsp=${seen.toSeq}"
      }
    } catch {
      // proto/read zostaja false - wyjatek to zawsze porazka komorki.
      // Rozroznienie timeoutu robimy po lancuchu przyczyn, a NIE przez
      // `case e : SimFailure`. Ten typ mieszka w spinal.sim (nie w
      // spinal.core.sim), a jego nazwa i pakiet zmienialy sie miedzy
      // wersjami; do tego SimManager potrafi opakowac wyjatek z watku
      // symulacji. Dopasowanie po typie jest tu krucha zaleznoscia.
      case e : Throwable =>
        timedOut = looksLikeTimeout(e)
        note     = s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"
    }

    Result(proto, read, timedOut, compiled = true, note = note,
           sdaLatency = latency, stretchCycles = stretch)
  }

  /** 11 komend x 4 cwiartki x quarterCycles x 10 jednostek sim,
    * razy zapas na stretching i dojscie filtra. */
  private def budget(g : I2cGenerics) : Long =
    11L * 4 * g.quarterCycles * 10 * 40

  /** SimTimeout woła simFailure(..), ktore rzuca spinal.sim.SimFailure.
    * Idziemy po getCause, bo wyjatek z watku symulacji bywa opakowany.
    * Limit glebokosci na wypadek cyklu w lancuchu przyczyn. */
  private def looksLikeTimeout(e : Throwable) : Boolean = {
    var t     = e
    var depth = 0
    var hit   = false
    while (t != null && depth < 8 && !hit) {
      val name = t.getClass.getName
      val msg  = Option(t.getMessage).getOrElse("").toLowerCase
      hit   = name.contains("SimFailure") || msg.contains("timeout")
      t     = t.getCause
      depth += 1
    }
    hit
  }
}
