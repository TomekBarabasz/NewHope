package newhope.vertebra.hil

import com.fazecast.jSerialComm.SerialPort
import scala.util.{Failure, Random, Success, Try}

// =====================================================================
//  Etap 0 (vertebra-hil.md §10): czy oba porty stanowiska odpowiadaja
//  echem. Zalazek HilLink; w etapie 4 zamienia sie w testpoint hw_link.
//
//    sbt "hil/runMain newhope.vertebra.hil.EchoProbe"                 porty z env
//    sbt "hil/runMain newhope.vertebra.hil.EchoProbe --fpga_com COM5 --esp_com COM7"
//    sbt "hil/runMain newhope.vertebra.hil.EchoProbe --fpga_com=/dev/ttyACM1"
//    sbt "hil/runMain newhope.vertebra.hil.EchoProbe --list"          lista portow
//    sbt "hil/runMain newhope.vertebra.hil.EchoProbe --chunks 256 --seed 7"
//    sbt "hil/runMain newhope.vertebra.hil.EchoProbe --esp_com COM11 --show-junk"
//        --show-junk wypisuje bajty, ktore przyszly po otwarciu portu, zanim
//        cokolwiek wyslalismy (np. log ROM ESP32 po resecie przez DTR/RTS)
//
//  Porty (opcja z linii komend wygrywa ze zmienna srodowiska):
//    --fpga_com / VERTEBRA_HIL_FPGA   UART FPGA, NIE port programatora
//    --esp_com  / VERTEBRA_HIL_ESP    natywne USB S3 (USB-Serial-JTAG)
//  Kod wyjscia: 0 = wszystkie ustawione porty OK, 1 = blad echa,
//               2 = brak portow albo zle argumenty.
// =====================================================================

object EchoProbe {

  /** idleLines: DTR i RTS nieaktywne juz w chwili otwarcia portu. Na
    * USB-Serial-JTAG S3 zmiana tych linii resetuje uklad (tak dziala auto
    * reset w idf.py flash), a jSerialComm domyslnie oba aktywuje przy open. */
  case class Target(label : String, cliOpt : String, envVar : String, baud : Int,
                    idleLines : Boolean, hint : String)

  val targets = Seq(
    Target("fpga", "--fpga_com", "VERTEBRA_HIL_FPGA", HilEchoGenerics().baud.toInt, false,
      "sprawdz: wgrany HilEchoTop, firmware PIC jimmo (fabryczny: 19200 i SW7), " +
      "port UART FPGA, a nie programatora (--list)"),
    Target("esp32", "--esp_com", "VERTEBRA_HIL_ESP", 115200, true,   // USB-Serial-JTAG ignoruje baud
      "sprawdz: wgrany esp32/ (echo), port natywnego USB S3 (VID 303a), " +
      "a nie mostka USB-UART na UART0")
  )

  case class Ok(bytes : Int, ms : Long, junk : Int)

  /** Bajty wykluczone ze strumienia. Jesli ktos ustawi VERTEBRA_HIL_FPGA na
    * port programatora, CLI firmware jimmo wykona komende po CR (np. 'e' =
    * kasowanie flasha). Bez CR/LF/^C nic sie nie wykona. Wszystkie 256
    * wartosci sprawdza echo_byte_roundtrip w symulacji. */
  val unsafe : Set[Int] = Set(0x03, 0x0A, 0x0D)

  val valueOpts = targets.map(_.cliOpt).toSet ++ Set("--chunks", "--seed")
  val flagOpts  = Set("--list", "--help", "--show-junk")

  val usage =
    "EchoProbe [--fpga_com PORT] [--esp_com PORT] [--chunks N] [--seed S] [--show-junk] [--list]\n" +
    "  PORT: /dev/ttyACM1, COM5 ...; opcja nadpisuje VERTEBRA_HIL_FPGA / VERTEBRA_HIL_ESP"

  def main(args : Array[String]) : Unit = {
    val opts = parseArgs(args) match {
      case Right(o) => o
      case Left(err) => println(s"$err\n$usage"); sys.exit(2)
    }
    if (opts.contains("--help")) { println(usage); sys.exit(0) }
    if (opts.contains("--list")) { listPorts(); sys.exit(0) }

    val chunks = opts.get("--chunks").map(_.toInt).getOrElse(64)   // 64 x 64 B = 4 KiB
    val seed   = opts.get("--seed").map(_.toLong).getOrElse(42L)

    // Port: najpierw linia komend, potem env. Zrodlo idzie do logu, zeby
    // bylo widac, skad wzial sie port, gdy w shellu zostal stary eksport.
    val ports = targets.map { t =>
      t -> (opts.get(t.cliOpt).filter(_.nonEmpty).map(_ -> t.cliOpt)
             .orElse(sys.env.get(t.envVar).filter(_.nonEmpty).map(_ -> t.envVar)))
    }

    // COM5 i com5 to na Windows ten sam port
    val dup = ports.flatMap(_._2.map(_._1)).groupBy(_.toLowerCase).collect { case (_, xs) if xs.size > 1 => xs.head }
    if (dup.nonEmpty) {
      println(s"Ten sam port dla dwoch plytek: ${dup.mkString(", ")}")
      sys.exit(2)
    }

    val results = ports.map { case (t, src) =>
      src match {
        case None =>
          println(s"[${t.label}] pominiete: brak ${t.cliOpt} i ${t.envVar}")
          None
        case Some((port, from)) =>
          println(s"[${t.label}] port $port (z $from)")
          val onJunk : Array[Byte] => Unit =
            if (opts.contains("--show-junk")) printJunk(t.label, _) else _ => ()
          val r = probe(port, t.baud, chunks, seed, t.idleLines, onJunk)
          r match {
            case Right(ok) =>
              val kbps = if (ok.ms > 0) ok.bytes * 1000.0 / ok.ms / 1024 else 0.0
              println(f"[${t.label}] OK   $port: ${ok.bytes} B w ${ok.ms} ms ($kbps%.1f KiB/s)" +
                      (if (ok.junk > 0) s", ${ok.junk} B smieci przed startem (pominiete)" else ""))
            case Left(err) =>
              println(s"[${t.label}] BLAD $port: $err")
              println(s"[${t.label}]      ${t.hint}")
          }
          Some(r.isRight)
      }
    }

    val ran = results.flatten
    if (ran.isEmpty) {
      println("Brak portow. Podaj --fpga_com / --esp_com albo ustaw VERTEBRA_HIL_FPGA / VERTEBRA_HIL_ESP " +
              "(--list pokaze kandydatow).")
      sys.exit(2)
    }
    if (ran.size < targets.size)
      println("Uwaga: kryterium etapu 0 wymaga echa z OBU plytek.")
    sys.exit(if (ran.forall(identity)) 0 else 1)
  }

  /** Wysyla `chunks` porcji po HilEcho.probeChunk bajtow i porownuje echo.
    * Porcja nie przekracza FIFO echa na FPGA (echo_param_bounds). */
  def probe(portName : String, baud : Int, chunks : Int, seed : Long,
            idleLines : Boolean = false,
            onJunk : Array[Byte] => Unit = _ => ()) : Either[String, Ok] = {
    val port = resolvePort(portName) match {
      case Right(p)  => p
      case Left(err) => return Left(err)
    }
    port.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
    port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
    port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 50, 0)
    // Wywolane PRZED openPort ustawiaja stan poczatkowy linii (jSerialComm
    // stosuje go przy otwarciu), wiec linie nie zmieniaja sie wcale.
    if (idleLines) { port.clearDTR(); port.clearRTS() }
    if (!port.openPort())
      return Left(s"nie mozna otworzyc portu (zajety? uprawnienia do grupy dialout?)")

    try {
      // Po otwarciu moga przyjsc resztki: log ROM S3 po resecie, prompt,
      // stare echo. Czekamy na 300 ms ciszy, najwyzej 3 s.
      val junkBytes = drain(port, quietMs = 300, maxMs = 3000)
      if (junkBytes.nonEmpty) onJunk(junkBytes)
      val junk = junkBytes.length

      // Synchronizacja: pierwsza porcja idzie dopiero, gdy druga strona
      // odpowiada. Po resecie ESP32 ROM milknie, zanim aplikacja przejmie
      // USB, wiec sama cisza na linii nie znaczy "gotowe".
      syncEcho(port, timeoutMs = 3000) match {
        case Some(e) => return Left(e)
        case None    =>
      }

      val rng   = new Random(seed)
      val chunk = HilEcho.probeChunk
      val t0    = System.nanoTime
      var c     = 0
      var err   = Option.empty[String]

      while (c < chunks && err.isEmpty) {
        val out = Array.fill[Byte](chunk)(nextSafe(rng).toByte)
        if (port.writeBytes(out, out.length) != out.length) {
          err = Some(s"porcja $c: zapis nie powiodl sie")
        } else {
          val in = readExactly(port, chunk, timeoutMs = 1000)
          if (in.length < chunk) {
            err = Some(s"porcja $c: wrocilo ${in.length}/$chunk B w 1000 ms" +
                       (if (c == 0 && in.isEmpty) " (zero bajtow od poczatku)" else ""))
          } else {
            val i = out.indices.indexWhere(k => out(k) != in(k))
            if (i >= 0)
              err = Some(f"porcja $c, bajt $i: wyslano 0x${out(i) & 0xFF}%02x, wrocilo 0x${in(i) & 0xFF}%02x")
          }
        }
        c += 1
      }
      val ms = (System.nanoTime - t0) / 1000000

      err match {
        case Some(e) => Left(e)
        case None =>
          val extra = drain(port, quietMs = 200, maxMs = 1000).length
          if (extra > 0) Left(s"$extra B nadmiarowych po ostatniej porcji (podwojne echo?)")
          else Right(Ok(chunks * chunk, ms, junk))
      }
    } finally {
      port.closePort()
    }
  }

  /** Port po nazwie tak, jak podaje go uzytkownik na danym systemie:
    * COM10 na Windows, /dev/ttyACM1 (albo ttyACM1) na Linuksie.
    *
    * Najpierw szukamy w enumeracji jSerialComm (getCommPorts) po nazwie
    * systemowej albo pelnej sciezce, bez zgadywania formatu. Dopiero gdy
    * portu tam nie ma (np. symlink /dev/serial/by-id/...), idziemy przez
    * getCommPort, ktory rozwiazuje symlinki. getCommPort sam dokleja
    * \\.\ albo /dev/ zaleznie od tego, za jaki system uzna maszyne;
    * gdy to rozpoznanie zawiedzie, z COM10 robi /dev/COM10. */
  def resolvePort(name : String) : Either[String, SerialPort] = {
    val want = name.trim
    val base = want.stripPrefix("\\\\.\\")          // \\.\COM10 -> COM10
    Try(SerialPort.getCommPorts.toSeq) match {
      case Failure(e) =>
        Left(s"enumeracja portow nie dziala (natywna biblioteka jSerialComm?): $e")
      case Success(all) =>
        all.find(p => p.getSystemPortName.equalsIgnoreCase(base) ||
                      p.getSystemPortPath.equalsIgnoreCase(want)) match {
          case Some(p) => Right(p)
          case None =>
            Try(SerialPort.getCommPort(want)) match {
              case Success(p) => Right(p)
              case Failure(_) =>
                if (all.isEmpty)
                  Left(s"nie ma portu '$want', a enumeracja jest pusta (JVM: ${sys.props("os.name")}). " +
                       envHint(want))
                else
                  Left(s"nie ma portu '$want' (widoczne: ${all.map(_.getSystemPortName).mkString(", ")})")
            }
        }
    }
  }

  /** WSL: JVM widzi Linuksa, wiec jSerialComm szuka /dev/tty*, a porty COM
    * Windowsa sa dla niej niewidoczne. Najczestsza przyczyna pustej listy. */
  def isWsl : Boolean =
    Try(scala.io.Source.fromFile("/proc/version").mkString.toLowerCase.contains("microsoft")).getOrElse(false)

  def isContainer : Boolean = new java.io.File("/.dockerenv").exists

  private def envHint(want : String) : String = {
    val isWinName = want.toUpperCase.matches("COM\\d+")
    if (isContainer)
      "To jest kontener: widzi tylko urzadzenia przekazane przez --device. Na Windows " +
      "uruchom EchoProbe poza kontenerem (sbt na hoscie) albo patrz tools/README.md."
    else if (isWsl)
      "To jest WSL: porty COM Windowsa tu nie istnieja. Uruchom sbt na Windows albo " +
      "podepnij urzadzenie do WSL przez usbipd-win i podaj /dev/ttyACMx."
    else if (isWinName && !sys.props("os.name").toLowerCase.contains("win"))
      s"'$want' to nazwa portu Windows, a JVM dziala na innym systemie."
    else
      "System widzi porty, a jSerialComm nie? Sprawdz uprawnienia (Linux: grupa dialout)."
  }

  /** Wysyla 0x55 co 100 ms, az ktores wroci. Potem zbiera echa pozostalych
    * prob, zeby nie wmieszaly sie w pierwsza porcje. */
  private def syncEcho(port : SerialPort, timeoutMs : Long) : Option[String] = {
    val probe    = Array[Byte](0x55)
    val buf      = new Array[Byte](64)
    val deadline = System.currentTimeMillis + timeoutMs
    var tries    = 0
    var got      = false
    while (!got && System.currentTimeMillis < deadline) {
      port.writeBytes(probe, 1); tries += 1
      val until = System.currentTimeMillis + 100
      while (!got && System.currentTimeMillis < until)
        if (port.readBytes(buf, buf.length) > 0) got = true
    }
    if (!got) Some(s"brak echa na bajcie synchronizacji ($tries prob w $timeoutMs ms)")
    else { drain(port, quietMs = 200, maxMs = 1000); None }
  }

  private def nextSafe(rng : Random) : Int = {
    var b = rng.nextInt(256)
    while (unsafe(b)) b = rng.nextInt(256)
    b
  }

  /** Czyta dokladnie n bajtow albo tyle, ile przyjdzie do timeoutu. */
  private def readExactly(port : SerialPort, n : Int, timeoutMs : Long) : Array[Byte] = {
    val buf      = new Array[Byte](n)
    val tmp      = new Array[Byte](n)
    var got      = 0
    val deadline = System.currentTimeMillis + timeoutMs
    while (got < n && System.currentTimeMillis < deadline) {
      val r = port.readBytes(tmp, n - got)
      if (r > 0) { System.arraycopy(tmp, 0, buf, got, r); got += r }
      else if (r < 0) return buf.take(got)            // port zniknal
    }
    buf.take(got)
  }

  /** Zbiera przychodzace bajty, az bedzie quietMs ciszy. Zwraca je
    * (najwyzej maxKeep; nadmiar liczy sie tylko w dlugosci logu). */
  private def drain(port : SerialPort, quietMs : Long, maxMs : Long,
                    maxKeep : Int = 4096) : Array[Byte] = {
    val tmp      = new Array[Byte](256)
    val kept     = new java.io.ByteArrayOutputStream()
    val start    = System.currentTimeMillis
    var lastData = start
    while (System.currentTimeMillis - lastData < quietMs &&
           System.currentTimeMillis - start < maxMs) {
      val r = port.readBytes(tmp, tmp.length)
      if (r > 0) {
        kept.write(tmp, 0, math.min(r, math.max(0, maxKeep - kept.size)))
        lastData = System.currentTimeMillis
      }
    }
    kept.toByteArray
  }

  /** Najpierw jako tekst (log ROM to ASCII), potem hexdump - gdyby to
    * jednak nie byl tekst. */
  private def printJunk(label : String, bytes : Array[Byte]) : Unit = {
    println(s"[$label] smieci przed startem, ${bytes.length} B, jako tekst:")
    val text = bytes.map(b => (b & 0xFF) match {
      case c if c == '\n'.toInt || c == '\r'.toInt || c == '\t'.toInt => c.toChar
      case c if c >= 0x20 && c < 0x7F                                   => c.toChar
      case _                                                            => '.'
    }).mkString
    text.split("\r?\n|\r").filter(_.nonEmpty).foreach(l => println(s"[$label]   | $l"))
    println(s"[$label] hex:")
    bytes.grouped(16).zipWithIndex.foreach { case (row, i) =>
      val hex = row.map(b => f"${b & 0xFF}%02x").mkString(" ")
      val asc = row.map(b => { val c = b & 0xFF; if (c >= 0x20 && c < 0x7F) c.toChar else '.' }).mkString
      println(f"[$label]   ${i * 16}%04x  $hex%-47s  $asc")
    }
  }

  private def listPorts() : Unit = {
    val ports = SerialPort.getCommPorts
    if (ports.isEmpty) println("Brak portow szeregowych.")
    ports.foreach { p =>
      println(f"${p.getSystemPortPath}%-22s VID:PID ${p.getVendorID}%04x:${p.getProductID}%04x  " +
              s"${p.getDescriptivePortName}  sn=${p.getSerialNumber}")
    }
    println("Mimas V2 z firmware jimmo daje dwa porty (programator + UART FPGA); " +
            "ESP32-S3 natywne USB ma VID 303a.")
  }

  /** "--opt wartosc", "--opt=wartosc" albo sama flaga. Nieznana opcja to
    * blad, a nie cisza: literowka w --fpga_com cicho wzielaby port z env. */
  def parseArgs(args : Array[String]) : Either[String, Map[String, String]] = {
    val out = scala.collection.mutable.LinkedHashMap[String, String]()
    var i   = 0
    var err = Option.empty[String]
    while (i < args.length && err.isEmpty) {
      val a = args(i)
      val (key, inline) = a.indexOf('=') match {
        case k if k > 0 && a.startsWith("--") => (a.take(k), Some(a.drop(k + 1)))
        case _                                => (a, None)
      }
      if (flagOpts(key) && inline.isEmpty) {
        out(key) = ""; i += 1
      } else if (valueOpts(key)) {
        inline.orElse(args.lift(i + 1).filterNot(_.startsWith("--"))) match {
          case Some(v) => out(key) = v; i += (if (inline.isDefined) 1 else 2)
          case None    => err = Some(s"$key wymaga wartosci")
        }
      } else {
        err = Some(s"nieznany argument: $a")
      }
    }
    err.map(Left(_)).getOrElse {
      val bad = Seq("--chunks", "--seed").filter(k => out.get(k).exists(v => scala.util.Try(v.toLong).isFailure))
      if (bad.nonEmpty) Left(s"${bad.mkString(", ")}: to musi byc liczba") else Right(out.toMap)
    }
  }
}
