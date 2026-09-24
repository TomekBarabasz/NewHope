package newhope.vertebra.hil

import com.fazecast.jSerialComm.SerialPort
import java.io.{File, PrintWriter}
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

// =====================================================================
//  Warstwa transportu hosta (vertebra-hil.md §8, "Warstwy"): bajty w obie
//  strony, timeouty i log calego ruchu do pliku per test. Nic tu nie zna
//  protokolu: tekst ESP32 sklada EspDevice, ramki mostu FPGA FpgaDevice.
//
//  HilPort to sam port. Na plytce SerialHilPort (jSerialComm), w testach
//  hosta atrapy plytek (HilHostTestplan), zeby protokol dalo sie
//  sprawdzic bez stanowiska.
// =====================================================================

/** Surowy port. `read` wraca najpozniej po krotkim czasie odpytywania
  * (dziesiatki ms), takze bez danych; timeouty liczy HilLink. */
trait HilPort {
  def name : String
  def write(b : Array[Byte]) : Boolean
  /** Liczba bajtow w `buf`, 0 = nic w tym oknie, -1 = port zniknal. */
  def read(buf : Array[Byte]) : Int
  def close() : Unit
}

class HilLinkError(msg : String) extends RuntimeException(msg)

class HilLink(val port : HilPort, val label : String, val text : Boolean) {
  private val pending = mutable.Queue[Byte]()
  private val tmp     = new Array[Byte](256)
  private val t0      = System.nanoTime
  private var log     : Option[PrintWriter] = None
  private var lineBuf = new StringBuilder

  /** Log ruchu do pliku (None = bez logu). Poprzedni plik jest zamykany. */
  def logTo(f : Option[File]) : Unit = synchronized {
    log.foreach(_.close())
    log = f.map { file =>
      Option(file.getParentFile).foreach(_.mkdirs())
      new PrintWriter(file, "UTF-8")
    }
    note(s"$label: port ${port.name}")
  }

  private def stamp : String = f"${(System.nanoTime - t0) / 1e9}%10.4f"

  def note(s : String) : Unit = log.foreach { w => w.println(s"$stamp # $s"); w.flush() }

  private def logBytes(dir : String, b : Array[Byte]) : Unit = log.foreach { w =>
    if (b.nonEmpty) {
      val body =
        if (text) b.map(x => (x & 0xFF) match {
          case '\n' => "\\n"; case '\r' => "\\r"
          case c if c >= 0x20 && c < 0x7F => c.toChar.toString
          case c => f"\\x$c%02x"
        }).mkString
        else b.map(x => f"${x & 0xFF}%02x").mkString(" ")
      w.println(s"$stamp $dir $body"); w.flush()
    }
  }

  def send(b : Array[Byte]) : Unit = {
    logBytes(">", b)
    if (!port.write(b))
      throw new HilLinkError(s"$label: zapis do ${port.name} nie powiodl sie w ${HilSerial.WriteTimeoutMs} ms " +
        "(plytka nie odbiera. ESP32: tryb pobierania po flash, RST albo odlaczenie zasilania. " +
        "Mimas V2: PIC czeka, az ktos odczyta port programatora po wgraniu bitstreamu - " +
        "tools/programmer.py robi to sam, inaczej otworz i odczytaj ten port albo odlacz USB)")
  }

  def send(s : String) : Unit = send(s.getBytes("US-ASCII"))

  /** Jedno odpytanie portu do kolejki. false = port zniknal. */
  private def poll() : Boolean = {
    val r = port.read(tmp)
    if (r > 0) {
      val got = java.util.Arrays.copyOf(tmp, r)
      logBytes("<", got)
      pending ++= got
    }
    r >= 0
  }

  private def deadline(ms : Long) : Long = System.nanoTime + ms * 1000000L
  private def before(d : Long) : Boolean = System.nanoTime - d < 0

  /** Do n bajtow; mniej, jesli minie timeout. */
  def recv(n : Int, timeoutMs : Long) : Array[Byte] = {
    val d = deadline(timeoutMs)
    while (pending.size < n && before(d))
      if (!poll()) throw new HilLinkError(s"$label: port ${port.name} zniknal")
    val k = scala.math.min(n, pending.size)
    Array.fill(k)(pending.dequeue())
  }

  /** Nastepna linia bez \n i \r. None po timeoutcie (niedokonczona linia
    * zostaje w buforze do nastepnego wywolania). */
  def recvLine(timeoutMs : Long) : Option[String] = {
    val d = deadline(timeoutMs)
    var out : Option[String] = None
    while (out.isEmpty && (pending.nonEmpty || before(d))) {
      if (pending.isEmpty) {
        if (!poll()) throw new HilLinkError(s"$label: port ${port.name} zniknal")
      } else {
        pending.dequeue().toChar match {
          case '\n' => out = Some(lineBuf.toString); lineBuf = new StringBuilder
          case '\r' =>
          case c    => lineBuf += c
        }
      }
    }
    out
  }

  /** Zbiera wszystko, az bedzie quietMs ciszy (najwyzej maxMs). Czysci
    * tez bufor linii. Zwraca zebrane bajty. */
  def drain(quietMs : Long, maxMs : Long) : Array[Byte] = {
    val out  = new java.io.ByteArrayOutputStream()
    out.write(lineBuf.toString.getBytes("ISO-8859-1"))
    lineBuf = new StringBuilder
    val end  = deadline(maxMs)
    var quiet = deadline(quietMs)
    while (before(quiet) && before(end)) {
      val before0 = pending.size
      if (!poll()) throw new HilLinkError(s"$label: port ${port.name} zniknal")
      if (pending.size > before0) quiet = deadline(quietMs)
      while (pending.nonEmpty) out.write(pending.dequeue().toInt)
    }
    out.toByteArray
  }

  def close() : Unit = synchronized {
    log.foreach(_.close()); log = None
    port.close()
  }
}

/** Porty szeregowe stanowiska (jSerialComm). */
object HilSerial {
  /** Okno odpytywania portu: read wraca najpozniej po tym czasie. */
  val PollMs = 20
  /** Zapis, ktory nie skonczy sie w tym czasie, jest bledem, a nie zawieszeniem. */
  val WriteTimeoutMs = 2000

  private class SerialHilPort(p : SerialPort) extends HilPort {
    def name : String = p.getSystemPortName
    def write(b : Array[Byte]) : Boolean = p.writeBytes(b, b.length) == b.length
    def read(buf : Array[Byte]) : Int = p.readBytes(buf, buf.length)
    def close() : Unit = p.closePort()
  }

  /** idleLines: DTR i RTS nieaktywne juz w chwili otwarcia. Na
    * USB-Serial-JTAG S3 zmiana tych linii resetuje uklad (auto reset
    * z idf.py flash), a jSerialComm domyslnie oba aktywuje przy open. */
  def open(name : String, baud : Int, idleLines : Boolean) : Either[String, HilPort] =
    resolvePort(name).flatMap { port =>
      port.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
      port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
      // Zapis z timeoutem: bez niego writeBytes na Windows czeka bez konca,
      // gdy druga strona nie odbiera (np. ESP32 w trybie pobierania po flash).
      port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                              PollMs, WriteTimeoutMs)
      // Wywolane PRZED openPort ustawiaja stan poczatkowy linii (jSerialComm
      // stosuje go przy otwarciu), wiec linie nie zmieniaja sie wcale.
      if (idleLines) { port.clearDTR(); port.clearRTS() }
      if (port.openPort()) Right(new SerialHilPort(port))
      else Left(s"nie mozna otworzyc portu $name (zajety? uprawnienia do grupy dialout?)")
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
      "uruchom sbt na hoscie, poza kontenerem (vertebra-hil.md §9)."
    else if (isWsl)
      "To jest WSL: porty COM Windowsa tu nie istnieja. Uruchom sbt na Windows albo " +
      "podepnij urzadzenie do WSL przez usbipd-win i podaj /dev/ttyACMx."
    else if (isWinName && !sys.props("os.name").toLowerCase.contains("win"))
      s"'$want' to nazwa portu Windows, a JVM dziala na innym systemie."
    else
      "System widzi porty, a jSerialComm nie? Sprawdz uprawnienia (Linux: grupa dialout)."
  }

  def listPorts() : Unit = {
    val ports = SerialPort.getCommPorts
    if (ports.isEmpty) println("Brak portow szeregowych.")
    ports.foreach { p =>
      println(f"${p.getSystemPortPath}%-22s VID:PID ${p.getVendorID}%04x:${p.getProductID}%04x  " +
              s"${p.getDescriptivePortName}  sn=${p.getSerialNumber}")
    }
    println("Mimas V2 z firmware jimmo daje dwa porty (programator + UART FPGA); " +
            "ESP32-S3 natywne USB ma VID 303a.")
  }
}
