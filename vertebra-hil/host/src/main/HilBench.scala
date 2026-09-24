package newhope.vertebra.hil

import java.io.File
import scala.sys.process._
import scala.util.Try

// =====================================================================
//  Stanowisko (vertebra-hil.md §8): porty obu plytek, sprawdzenie wersji
//  (§5, "Pierwsza komenda sesji"), jedna instancja na JVM.
//
//  Porty: opcja suity (-Desp_com=..., -Dfpga_com=...) wygrywa ze
//  zmienna srodowiska (VERTEBRA_HIL_ESP, VERTEBRA_HIL_FPGA):
//    sbt "hil/testOnly *I2sHilTestplan -- -Desp_com=COM11 -Dfpga_com=COM12"
//
//  Wynik HilBench.get:
//    Right(None)    nie ustawiono zadnego portu: brak stanowiska, testy
//                   hw_* sa canceled, a nie failed (§8)
//    Right(Some(b)) co najmniej jedna plytka odpowiada i ma zgodna wersje
//    Left(opis)     port ustawiony, ale plytka nie dziala albo ma zla
//                   wersje: to jest blad, bo ktos chcial uzyc stanowiska
// =====================================================================

/** Czesc stanowiska znajaca IP: mapa rejestrow FPGA i sciezki zrodel,
  * od ktorych zalezy firmware i bitstream (do sprawdzenia `build`). */
trait HilIp {
  def name : String                     // "i2s": ver ESP32 i ip_id FPGA (malymi literami)
  def fpgaMap : HilFpgaMap
  /** Sciezki (pathspec gita, wzgledem korzenia repo), z ktorych budowany
    * jest firmware ESP32 / bitstream. Zmiana w nich od commita z `build`
    * znaczy, ze plytka ma stary kod. */
  def espSources  : Seq[String]
  def fpgaSources : Seq[String]
  /** Jak przebudowac i wgrac, do komunikatu o nieaktualnej plytce. */
  def espFlashHint  : String
  def fpgaFlashHint : String
}

case class HilBenchOpts(esp        : Option[(String, String)],     // (port, skad)
                        fpga       : Option[(String, String)],
                        allowStale : Boolean) {
  /** Do komunikatow: skad wziely sie ustawienia (opcja suity czy env). */
  def describe : String = {
    def p(n : String, x : Option[(String, String)]) = x.fold(s"$n: brak")(v => s"$n=${v._1} z ${v._2}")
    s"${p("esp", esp)}, ${p("fpga", fpga)}, allow_stale=${if (allowStale) "tak" else "nie"}"
  }
}

object HilBenchOpts {
  val EspOpt   = "esp_com"
  val FpgaOpt  = "fpga_com"
  val StaleOpt = "allow_stale"
  val EspEnv   = "VERTEBRA_HIL_ESP"
  val FpgaEnv  = "VERTEBRA_HIL_FPGA"
  val StaleEnv = "VERTEBRA_HIL_ALLOW_STALE"

  val known : Set[String] = Set(EspOpt, FpgaOpt, StaleOpt)

  /** Opcje suity spoza `known`. Literowka (np. allow-stale) ma przerwac
    * test, a nie zostac po cichu zignorowana - jak nieznany klucz w cfg. */
  def unknown(config : Map[String, Any], extra : Set[String] = Set.empty) : Seq[String] =
    (config.keySet -- known -- extra).toSeq.sorted

  def from(config : Map[String, Any], env : Map[String, String] = sys.env) : HilBenchOpts = {
    def port(opt : String, envVar : String) =
      config.get(opt).map(_.toString.trim).filter(_.nonEmpty).map(_ -> s"-D$opt")
        .orElse(env.get(envVar).map(_.trim).filter(_.nonEmpty).map(_ -> envVar))
    def flag(v : Option[String]) = v.exists(x => Set("1", "true", "yes").contains(x.trim.toLowerCase))
    HilBenchOpts(port(EspOpt, EspEnv), port(FpgaOpt, FpgaEnv),
                 flag(config.get(StaleOpt).map(_.toString)) || flag(env.get(StaleEnv)))
  }
}

/** Plytki sa niezalezne: blad jednej (brak odpowiedzi, stary build) nie
  * blokuje testow drugiej. `espError` / `fpgaError`: port ustawiony, ale
  * plytka nie do uzycia - scenariusz, ktory jej potrzebuje, jest failed. */
class HilBench(val ip : HilIp,
               val esp : Option[EspDevice],
               val fpga : Option[FpgaDevice[HilFpgaMap]],
               val espInfo : Option[HilInfo],
               val fpgaInfo : Option[HilInfo],
               val warnings : Seq[String],
               val espError : Option[String] = None,
               val fpgaError : Option[String] = None) {
  def devices : Seq[HilDevice] = esp.toSeq ++ fpga.toSeq

  def logTo(dir : Option[File]) : Unit = devices.foreach(d =>
    d.link.logTo(dir.map(new File(_, s"${d.label}.log"))))

  def describe : String =
    (espInfo.map(i => s"esp32 (${esp.get.link.port.name}): $i").toSeq ++
     fpgaInfo.map(i => s"fpga (${fpga.get.link.port.name}): $i" +
       i.variant.flatMap(ip.fpgaMap.variantName).fold("")(n => s" [$n]"))).mkString("; ")

  def close() : Unit = devices.foreach(d => Try(d.close()))
}

/** Postep na stdout, na biezaco. Wyniki ScalaTest (info) sbt pokazuje
  * dopiero po zakonczeniu testu, a biegi hw_* trwaja dziesiatki sekund:
  * bez tego zawieszenie i dlugi bieg wygladaja tak samo. */
object HilProgress {
  /** Po tylu sekundach bez postepu watchdog wypisuje stos watku testu. */
  val StallS = 60

  @volatile private var last    = System.nanoTime
  @volatile private var watched : Option[(Thread, String)] = None
  @volatile private var warned  = false

  def apply(msg : String) : Unit = {
    val t = java.time.LocalTime.now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
    println(s"[hil $t] $msg")
    System.out.flush()
    last = System.nanoTime; warned = false
  }

  private lazy val watchdog : Thread = {
    val th = new Thread(() => while (true) {
      Thread.sleep(5000)
      watched.foreach { case (t, what) =>
        if (!warned && System.nanoTime - last > StallS * 1000000000L) {
          warned = true
          apply(s"$what: brak postepu od $StallS s, watek testu stoi w:\n" +
                t.getStackTrace.take(25).map("    at " + _).mkString("\n"))
        }
      }
    }, "hil-watchdog")
    th.setDaemon(true); th.start(); th
  }

  /** Pilnuje biezacego watku do `unwatch` (HilSuite.hwScenario). */
  def watch(what : String) : Unit = { watchdog; last = System.nanoTime; warned = false
                                      watched = Some(Thread.currentThread -> what) }
  def unwatch() : Unit = watched = None
}

object HilBench {
  /** Wzgledem katalogu roboczego testow, czyli vertebra-hil/host (forkSettings). */
  val logRoot = new File("target/hil-logs")
  private val cache = scala.collection.mutable.Map[String, Either[String, Option[HilBench]]]()

  /** Jedna instancja na JVM i IP: porty sa otwierane raz, przy pierwszym
    * tescie, i zamykane przy wyjsciu z JVM. */
  def get(ip : HilIp, opts : HilBenchOpts) : Either[String, Option[HilBench]] = synchronized {
    cache.getOrElseUpdate(ip.name, {
      val b = open(ip, opts, HilSerial.open)
      b.foreach(_.foreach(x => sys.addShutdownHook(x.close())))
      b
    })
  }

  type Opener = (String, Int, Boolean) => Either[String, HilPort]

  /** Otwarta i sprawdzona plytka albo opis, czemu nie. */
  private case class Opened[D <: HilDevice](dev : D, info : HilInfo, warnings : Seq[String])

  /** Otwiera port i uruchamia `check`; przy bledzie zamyka port. */
  private def openOne[D <: HilDevice](label : String, port : String, from : String, baud : Int, idle : Boolean,
                                      opener : Opener, portHint : String)
                                     (mk : HilPort => D)(check : D => Either[String, Opened[D]])
                                     : Either[String, Opened[D]] =
    opener(port, baud, idle) match {
      case Left(e) => HilProgress(s"$label: $e"); Left(s"$label: $e (port z $from$portHint)")
      case Right(p) =>
        val d = mk(p)
        val log = new File(new File(logRoot, "_open"), s"$label.log")
        d.link.logTo(Some(log))
        HilProgress(s"$label: port $port otwarty, sprawdzam plytke (log: ${log.getAbsolutePath})")
        val r = Try(check(d)).fold(e => Left(s"$label ($port z $from): ${e.getMessage}"), identity)
        r.fold(e => HilProgress(s"$label: BLAD $e"), o => HilProgress(s"$label: ${o.info}"))
        if (r.isLeft) Try(d.close())
        r
    }

  def open(ip : HilIp, opts : HilBenchOpts, opener : Opener) : Either[String, Option[HilBench]] = {
    if (opts.esp.isEmpty && opts.fpga.isEmpty) return Right(None)
    val names = (opts.esp.toSeq ++ opts.fpga.toSeq).map(_._1.toLowerCase)
    if (names.distinct.size < names.size)
      return Left(s"ten sam port dla obu plytek: ${opts.esp.get._1} (${opts.esp.get._2}, ${opts.fpga.get._2})")

    val esp = opts.esp.map { case (port, from) =>
      openOne("esp32", port, from, 115200, idle = true, opener, "")(      // USB-Serial-JTAG ignoruje baud
        p => new EspDevice(new HilLink(p, "esp32", text = true))) { d =>
        val info  = d.sync()
        val reset = if (d.resets > 0) Seq(s"esp32: log ROM po otwarciu portu (${d.resets} linii) - uklad byl resetowany")
                    else Nil
        checkInfo("esp32", info, ip, ip.espSources, ip.espFlashHint, opts.allowStale)
          .map(w => Opened(d, info, reset ++ w))
      }
    }

    val fpga = opts.fpga.map { case (port, from) =>
      openOne("fpga", port, from, HilBridgeGenerics().baud.toInt, idle = false, opener, "; UART FPGA, nie programator")(
        p => new FpgaDevice[HilFpgaMap](new HilLink(p, "fpga", text = false), ip.fpgaMap)) { d =>
        d.link.drain(quietMs = 50, maxMs = 500)
        val info = d.info()
        if (info.variant.flatMap(ip.fpgaMap.variantName).isEmpty)
          Left(f"fpga: nieznany wariant ${info.variant.getOrElse(-1L)}%08x (bitstream innego IP?)")
        else checkInfo("fpga", info, ip, ip.fpgaSources, ip.fpgaFlashHint, opts.allowStale)
          .map(w => Opened(d, info, w))
      }
    }

    val e = esp.flatMap(_.toOption); val f = fpga.flatMap(_.toOption)
    Right(Some(new HilBench(ip, e.map(_.dev), f.map(_.dev), e.map(_.info), f.map(_.info),
                            e.toSeq.flatMap(_.warnings) ++ f.toSeq.flatMap(_.warnings),
                            esp.flatMap(_.left.toOption), fpga.flatMap(_.left.toOption))))
  }

  /** proto i ip musza sie zgadzac; build jest porownywany z repo przez
    * HilGit.staleness. Left = plytka nie do uzycia (albo nieaktualna bez
    * allow_stale), Right = ostrzezenia do raportu. */
  def checkInfo(dev : String, info : HilInfo, ip : HilIp, sources : Seq[String], hint : String,
                allowStale : Boolean) : Either[String, Seq[String]] = {
    if (info.proto != HilProtocol.ProtoVersion)
      return Left(s"$dev: protokol ${info.proto}, host zna ${HilProtocol.ProtoVersion}. $hint")
    if (info.ip != ip.name)
      return Left(s"$dev: plytka ma IP '${info.ip}', suita potrzebuje '${ip.name}'. $hint")
    HilGit.staleness(info.build, sources) match {
      case HilGit.Fresh           => Right(Nil)
      case HilGit.Unknown(why)    => Right(Seq(s"$dev: nie da sie sprawdzic build=${info.build}: $why"))
      case HilGit.Stale(why)      =>
        val msg = s"$dev: nieaktualny build=${info.build}: $why. $hint"
        if (allowStale) Right(Seq(s"$msg (dopuszczone przez ${HilBenchOpts.StaleOpt})"))
        else Left(s"$msg; albo -D${HilBenchOpts.StaleOpt}=1 / ${HilBenchOpts.StaleEnv}=1, zeby mimo to uruchomic")
    }
  }
}

/** Czy plytka ma kod zgodny z repo. `build` to 8 cyfr hash commita
  * (+ "-dirty"); plytka jest aktualna, gdy od tego commita nic sie nie
  * zmienilo w jej zrodlach (lacznie z niezacommitowanymi zmianami). */
object HilGit {
  sealed trait Staleness
  case object Fresh extends Staleness
  case class Stale(why : String) extends Staleness
  case class Unknown(why : String) extends Staleness

  private val quiet = ProcessLogger(_ => (), _ => ())

  /** Wyjscie gita bez \r: na Windows linie koncza sie \r\n, a \r w
    * komunikacie cofa kursor i terminal pokazuje tylko koniec linii. */
  private def git(args : String*) : Option[String] =
    Try(Process("git" +: args).!!(quiet).replace("\r", "").trim).toOption

  lazy val toplevel : Option[String] = git("rev-parse", "--show-toplevel")

  def staleness(build : String, sources : Seq[String]) : Staleness = {
    val Build = """([0-9a-f]{8})(-dirty)?""".r
    build match {
      case Build("00000000", _) => Unknown("zbudowane bez gita")
      case Build(hash, dirty) =>
        toplevel match {
          case None => Unknown("git niedostepny")
          case Some(top) =>
            // FPGA ma zamaskowany najmlodszy bit (flaga dirty), wiec
            // commit szukamy po 7 cyfrach: w obu przypadkach wystarcza.
            git("-C", top, "rev-parse", "--verify", "-q", s"${hash.take(7)}^{commit}") match {
              case None => Stale(s"commit ${hash.take(7)} nieznany w tym repo (inna galaz? git fetch?)")
              case Some(commit) =>
                // Build czysty: zrodla na plytce == commit, porownujemy z drzewem
                // roboczym (lacznie z niezacommitowanymi zmianami). Build dirty:
                // plytka ma commit + nieznane lokalne zmiany - zwykle wlasnie te
                // z drzewa roboczego, wiec liczy sie tylko to, co zacommitowano
                // od tego czasu; reszta to ostrzezenie (Unknown).
                val range = if (dirty != null) Seq(commit, "HEAD") else Seq(commit)
                git(Seq("-C", top, "diff", "--name-only") ++ range ++ Seq("--") ++ sources : _*) match {
                  case None => Unknown("git diff nie dziala")
                  case Some(out) =>
                    val changed = out.split('\n').filter(_.nonEmpty).toSeq
                    if (changed.nonEmpty)
                      Stale(s"od ${commit.take(8)} zmienilo sie ${changed.size} plikow" +
                            (if (dirty != null) " w commitach" else "") + " " +
                            s"(${changed.take(4).mkString(", ")}${if (changed.size > 4) ", ..." else ""})")
                    else if (dirty != null)
                      Unknown(s"zbudowane z niezacommitowanymi zmianami na ${commit.take(8)} " +
                              "(od tego commita bez zmian w zrodlach plytki)")
                    else Fresh
                }
            }
        }
      case _ => Unknown(s"format '$build' (oczekiwane 8 cyfr hex, opcjonalnie -dirty)")
    }
  }
}
