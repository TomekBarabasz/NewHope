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
                        allowStale : Boolean)

object HilBenchOpts {
  val EspOpt   = "esp_com"
  val FpgaOpt  = "fpga_com"
  val StaleOpt = "allow_stale"
  val EspEnv   = "VERTEBRA_HIL_ESP"
  val FpgaEnv  = "VERTEBRA_HIL_FPGA"
  val StaleEnv = "VERTEBRA_HIL_ALLOW_STALE"

  def from(config : Map[String, Any], env : Map[String, String] = sys.env) : HilBenchOpts = {
    def port(opt : String, envVar : String) =
      config.get(opt).map(_.toString.trim).filter(_.nonEmpty).map(_ -> s"-D$opt")
        .orElse(env.get(envVar).map(_.trim).filter(_.nonEmpty).map(_ -> envVar))
    def flag(v : Option[String]) = v.exists(x => Set("1", "true", "yes").contains(x.trim.toLowerCase))
    HilBenchOpts(port(EspOpt, EspEnv), port(FpgaOpt, FpgaEnv),
                 flag(config.get(StaleOpt).map(_.toString)) || flag(env.get(StaleEnv)))
  }
}

class HilBench(val ip : HilIp,
               val esp : Option[EspDevice],
               val fpga : Option[FpgaDevice[HilFpgaMap]],
               val espInfo : Option[HilInfo],
               val fpgaInfo : Option[HilInfo],
               val warnings : Seq[String]) {
  def devices : Seq[HilDevice] = esp.toSeq ++ fpga.toSeq

  def logTo(dir : Option[File]) : Unit = devices.foreach(d =>
    d.link.logTo(dir.map(new File(_, s"${d.label}.log"))))

  def describe : String =
    (espInfo.map(i => s"esp32 (${esp.get.link.port.name}): $i").toSeq ++
     fpgaInfo.map(i => s"fpga (${fpga.get.link.port.name}): $i" +
       i.variant.flatMap(ip.fpgaMap.variantName).fold("")(n => s" [$n]"))).mkString("; ")

  def close() : Unit = devices.foreach(d => Try(d.close()))
}

object HilBench {
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

  def open(ip : HilIp, opts : HilBenchOpts, opener : Opener) : Either[String, Option[HilBench]] = {
    if (opts.esp.isEmpty && opts.fpga.isEmpty) return Right(None)
    val names = (opts.esp.toSeq ++ opts.fpga.toSeq).map(_._1.toLowerCase)
    if (names.distinct.size < names.size)
      return Left(s"ten sam port dla obu plytek: ${opts.esp.get._1} (${opts.esp.get._2}, ${opts.fpga.get._2})")

    val warnings = Seq.newBuilder[String]
    var opened   = List.empty[HilDevice]
    def cleanup[T](e : String) : Either[String, T] = { opened.foreach(d => Try(d.close())); Left(e) }

    val esp = opts.esp.map { case (port, from) =>
      opener(port, 115200, true) match {           // USB-Serial-JTAG ignoruje baud
        case Left(e) => return cleanup(s"esp32: $e (port z $from)")
        case Right(p) =>
          val d = new EspDevice(new HilLink(p, "esp32", text = true))
          opened ::= d
          val info = Try(d.sync()).fold(e => return cleanup(s"esp32 ($port z $from): ${e.getMessage}"), identity)
          if (d.resets > 0) warnings += s"esp32: log ROM po otwarciu portu (${d.resets} linii) - uklad byl resetowany"
          checkInfo("esp32", info, ip, ip.espSources, ip.espFlashHint, opts.allowStale) match {
            case Left(e)  => return cleanup(e)
            case Right(w) => warnings ++= w
          }
          (d, info)
      }
    }

    val fpga = opts.fpga.map { case (port, from) =>
      opener(port, HilBridgeGenerics().baud.toInt, false) match {
        case Left(e) => return cleanup(s"fpga: $e (port z $from; UART FPGA, nie programator)")
        case Right(p) =>
          val d = new FpgaDevice[HilFpgaMap](new HilLink(p, "fpga", text = false), ip.fpgaMap)
          opened ::= d
          d.link.drain(quietMs = 50, maxMs = 500)
          val info = Try(d.info()).fold(e => return cleanup(s"fpga ($port z $from): ${e.getMessage}"), identity)
          val variantOk = info.variant.flatMap(ip.fpgaMap.variantName)
          if (variantOk.isEmpty)
            return cleanup(f"fpga: nieznany wariant ${info.variant.getOrElse(-1L)}%08x (bitstream innego IP?)")
          checkInfo("fpga", info, ip, ip.fpgaSources, ip.fpgaFlashHint, opts.allowStale) match {
            case Left(e)  => return cleanup(e)
            case Right(w) => warnings ++= w
          }
          (d, info)
      }
    }

    Right(Some(new HilBench(ip, esp.map(_._1), fpga.map(_._1), esp.map(_._2), fpga.map(_._2), warnings.result())))
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

  private def git(args : String*) : Option[String] =
    Try(Process("git" +: args).!!(quiet).trim).toOption

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
                git(Seq("-C", top, "diff", "--name-only", commit, "--") ++ sources : _*) match {
                  case None => Unknown("git diff nie dziala")
                  case Some(out) =>
                    val changed = out.split('\n').filter(_.nonEmpty).toSeq
                    if (changed.nonEmpty)
                      Stale(s"od ${commit.take(8)} zmienilo sie ${changed.size} plikow " +
                            s"(${changed.take(4).mkString(", ")}${if (changed.size > 4) ", ..." else ""})")
                    else if (dirty != null)
                      Unknown(s"zbudowane z niezacommitowanymi zmianami na ${commit.take(8)}")
                    else Fresh
                }
            }
        }
      case _ => Unknown(s"format '$build' (oczekiwane 8 cyfr hex, opcjonalnie -dirty)")
    }
  }
}
