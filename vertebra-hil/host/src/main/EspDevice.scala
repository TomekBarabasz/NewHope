package newhope.vertebra.hil

import scala.collection.immutable.ListMap

// =====================================================================
//  ESP32 po protokole tekstowym (contract/commands.md, "ESP32").
//
//  Jedna linia komendy, jedna linia odpowiedzi `ok ...` / `err <kod> ...`
//  (dump: n + 2 linie). Po drodze moga przyjsc linie, ktore nie sa
//  odpowiedzia, i sa pomijane:
//    "# ..."    log firmware (hil_cmd_log)
//    "ESP-ROM:" i reszta logu ROM po resecie - liczymy je w `resets`,
//               bo reset kasuje cfg (vertebra-hil.md §7, "DTR/RTS")
// =====================================================================
class EspDevice(val link : HilLink, val label : String = "esp32") extends HilDevice {
  import EspDevice._

  /** Linie logu ROM widziane od otwarcia portu: > 0 znaczy, ze uklad sie
    * zresetowal (i zgubil cfg). Sprawdza je hw_link i HilSuite. */
  var resets : Int = 0
  /** Linie, ktore nie byly ani odpowiedzia, ani logiem (#). */
  var junkLines : Int = 0

  private def noteJunk(s : String) : Unit =
    if (s.contains("ESP-ROM:")) { resets += 1; link.note(s"reset ESP32: $s") }
    else if (s.startsWith("#")) ()
    else if (s.nonEmpty) { junkLines += 1; link.note(s"pominieta linia: $s") }

  /** Po otwarciu portu: zbiera resztki (log ROM, stare odpowiedzi) i czeka,
    * az firmware odpowie na `ver`. Po resecie ROM milknie, zanim aplikacja
    * przejmie USB, wiec cisza na linii jeszcze nie znaczy "gotowe". */
  def sync(timeoutMs : Long = 3000) : HilInfo = {
    val junk = new String(link.drain(quietMs = 200, maxMs = 2000), "ISO-8859-1")
    junk.split("\r?\n|\r").foreach(noteJunk)
    val end = System.nanoTime + timeoutMs * 1000000L
    var got : Option[HilInfo] = None
    var last = ""
    while (got.isEmpty && System.nanoTime - end < 0) {
      try got = Some(info(timeoutMs = 300))
      catch { case e : HilDeviceError if e.code.isEmpty => last = e.getMessage }
    }
    got.getOrElse(throw new HilDeviceError(label, None,
      s"brak odpowiedzi na ver przez $timeoutMs ms ($last); port natywnego USB S3, a nie CH343?"))
  }

  /** Nastepna linia odpowiedzi (ok / err), z pominieciem logow. */
  private def reply(what : String, timeoutMs : Long) : String = {
    val end = System.nanoTime + timeoutMs * 1000000L
    var out : Option[String] = None
    while (out.isEmpty) {
      val left = scala.math.max(0L, (end - System.nanoTime) / 1000000L)
      link.recvLine(left) match {
        case None => throw new HilDeviceError(label, None, s"$what: brak odpowiedzi w $timeoutMs ms")
        case Some(l) if l == "ok" || l.startsWith("ok ") || l.startsWith("err ") => out = Some(l)
        case Some(l) => noteJunk(l)
      }
    }
    out.get
  }

  /** Komenda i odpowiedz. Right: pola `ok k=v ...` (slowa bez '=' jako
    * klucz z pusta wartoscia, np. "end"); Left: (kod, opis) z `err`. */
  def command(line : String, timeoutMs : Long = CmdTimeoutMs) : Either[(Int, String), ListMap[String, String]] = {
    require(line.length <= LineMax && !line.exists(c => c == '\n' || c == '\r'),
            s"komenda '$line' za dluga albo z koncem linii")
    link.send(line + "\n")
    parseReply(line, reply(line.takeWhile(_ != ' '), timeoutMs))
  }

  private def parseReply(cmd : String, l : String) : Either[(Int, String), ListMap[String, String]] =
    if (l.startsWith("err ")) {
      val rest = l.drop(4)
      val code = rest.takeWhile(_.isDigit)
      if (code.isEmpty) throw new HilDeviceError(label, None, s"$cmd: zla linia bledu '$l'")
      Left(code.toInt -> rest.drop(code.length).trim)
    } else Right(ListMap(l.split(' ').toSeq.drop(1).filter(_.nonEmpty).map { t =>
      t.indexOf('=') match {
        case -1 => t -> ""
        case i  => t.take(i) -> t.drop(i + 1)
      }
    } : _*))

  /** Jak `command`, ale `err` to wyjatek z kodem. */
  def cmd(line : String, timeoutMs : Long = CmdTimeoutMs) : ListMap[String, String] =
    command(line, timeoutMs).fold(
      { case (c, m) => throw new HilDeviceError(label, Some(c), s"'$line' -> err $c ${errName(c)}: $m") },
      identity)

  def info() : HilInfo = info(CmdTimeoutMs)

  private def info(timeoutMs : Long) : HilInfo = {
    val r = cmd("ver", timeoutMs)
    def field(k : String) = r.getOrElse(k, throw new HilDeviceError(label, None, s"ver bez pola $k: $r"))
    val proto = field("proto")
    HilInfo(proto.toIntOption.getOrElse(throw new HilDeviceError(label, None, s"proto=$proto")),
            field("dev"), field("ip"), field("build"))
  }

  def cfg(kv : Seq[(String, String)]) : Unit = {
    kv.foreach { case (k, v) =>
      require(k.matches("[a-z_0-9]+") && v.nonEmpty && !v.exists(c => c == ' ' || c == '='),
              s"cfg: '$k=$v'")
    }
    cmd(("cfg" +: kv.map { case (k, v) => s"$k=$v" }).mkString(" "))
  }

  def start() : Unit = cmd("start")
  def stop()  : Unit = cmd("stop")

  def stat() : HilStat = parseStat(label, cmd("stat"))

  def dump() : Seq[HilDumpEntry] = {
    val head = cmd("dump")
    val n = head.get("n").flatMap(_.toIntOption)
      .getOrElse(throw new HilDeviceError(label, None, s"dump: '$head' bez n="))
    val entries = (0 until n).map { i =>
      var line = Option.empty[String]
      while (line.isEmpty) link.recvLine(CmdTimeoutMs) match {
        case None                        => throw new HilDeviceError(label, None, s"dump: brak linii $i z $n")
        case Some(l) if l.startsWith("#") => noteJunk(l)
        case Some(l)                     => line = Some(l)
      }
      parseDumpLine(line.get).getOrElse(throw new HilDeviceError(label, None, s"dump: zla linia '${line.get}'"))
    }
    val end = reply("dump", CmdTimeoutMs)
    if (end != "ok end") throw new HilDeviceError(label, None, s"dump: po $n liniach '$end' zamiast 'ok end'")
    entries
  }

  /** Wektory kontraktu i test wlasny roli; zwraca liczbe wektorow. */
  def selftest(timeoutMs : Long = SelftestTimeoutMs) : Int = {
    val r = cmd("selftest", timeoutMs)
    r.get("vectors").flatMap(_.toIntOption)
      .getOrElse(throw new HilDeviceError(label, None, s"selftest: '$r' bez vectors="))
  }
}

object EspDevice {
  val LineMax           = 255
  val CmdTimeoutMs      = 2000L
  /** Wektory + petla wewnetrzna: 5 konfiguracji po >= 2000 ramek, z zapasem. */
  val SelftestTimeoutMs = 60000L

  def errName(code : Int) : String = code match {
    case 1 => "nieznana komenda"; case 2 => "nieznany klucz"; case 3 => "zakres"
    case 4 => "zly stan"; case 5 => "peryferium"; case 6 => "selftest"; case _ => "?"
  }

  val statFields = Seq("sent", "frames", "bad", "gaps", "relocks", "lock_at", "first_err", "overflow")

  def parseStat(dev : String, r : ListMap[String, String]) : HilStat = {
    def bad(m : String) = throw new HilDeviceError(dev, None, s"stat: $m ($r)")
    def num(k : String) : Long =
      r.get(k).flatMap(_.toLongOption).getOrElse(bad(s"brak albo zle pole $k"))
    HilStat(num("sent"), num("frames"), num("bad"), num("gaps"), num("relocks"), num("lock_at"),
            HilErr.parse(r.getOrElse("first_err", bad("brak first_err"))).fold(bad, identity),
            num("overflow"), r -- statFields)
  }

  def parseDumpLine(l : String) : Option[HilDumpEntry] = l.split(' ').filter(_.nonEmpty) match {
    case Array(i, gl, gr, el, er) =>
      scala.util.Try(HilDumpEntry(i.toLong, h(gl), h(gr), h(el), h(er))).toOption
    case _ => None
  }

  private def h(s : String) : Long = { require(s.length <= 8); java.lang.Long.parseLong(s, 16) }
}
