package newhope.vertebra.hil

import com.fazecast.jSerialComm.SerialPort
import scala.util.Random

// =====================================================================
//  Etap 0 (vertebra-hil.md §10): czy oba porty stanowiska odpowiadaja
//  echem. Zalazek HilLink; w etapie 4 zamienia sie w testpoint hw_link.
//
//    sbt "hil/runMain newhope.vertebra.hil.EchoProbe"            oba porty z env
//    sbt "hil/runMain newhope.vertebra.hil.EchoProbe --list"     lista portow
//    sbt "hil/runMain newhope.vertebra.hil.EchoProbe --chunks 256 --seed 7"
//
//  Porty: VERTEBRA_HIL_FPGA (UART FPGA, NIE port programatora),
//         VERTEBRA_HIL_ESP  (natywne USB S3, USB-Serial-JTAG).
//  Kod wyjscia: 0 = wszystkie ustawione porty OK, 1 = blad, 2 = brak portow.
// =====================================================================

object EchoProbe {

  case class Target(label : String, envVar : String, baud : Int, hint : String)

  val targets = Seq(
    Target("fpga", "VERTEBRA_HIL_FPGA", HilEchoGenerics().baud.toInt,
      "sprawdz: wgrany HilEchoTop, firmware PIC jimmo (fabryczny: 19200 i SW7), " +
      "port UART FPGA, a nie programatora (--list)"),
    Target("esp32", "VERTEBRA_HIL_ESP", 115200,   // USB-Serial-JTAG ignoruje baud
      "sprawdz: wgrany esp32/ (echo), port natywnego USB S3 (VID 303a), " +
      "a nie mostka USB-UART na UART0")
  )

  case class Ok(bytes : Int, ms : Long, junk : Int)

  /** Bajty wykluczone ze strumienia. Jesli ktos ustawi VERTEBRA_HIL_FPGA na
    * port programatora, CLI firmware jimmo wykona komende po CR (np. 'e' =
    * kasowanie flasha). Bez CR/LF/^C nic sie nie wykona. Wszystkie 256
    * wartosci sprawdza echo_byte_roundtrip w symulacji. */
  val unsafe : Set[Int] = Set(0x03, 0x0A, 0x0D)

  def main(args : Array[String]) : Unit = {
    if (args.contains("--list")) { listPorts(); sys.exit(0) }

    val chunks = argValue(args, "--chunks").map(_.toInt).getOrElse(64)   // 64 x 64 B = 4 KiB
    val seed   = argValue(args, "--seed").map(_.toLong).getOrElse(42L)

    val results = targets.map { t =>
      sys.env.get(t.envVar).filter(_.nonEmpty) match {
        case None =>
          println(s"[${t.label}] pominiete: brak ${t.envVar}")
          None
        case Some(port) =>
          val r = probe(port, t.baud, chunks, seed)
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
      println("Brak portow. Ustaw VERTEBRA_HIL_FPGA i/lub VERTEBRA_HIL_ESP (--list pokaze kandydatow).")
      sys.exit(2)
    }
    if (ran.size < targets.size)
      println("Uwaga: kryterium etapu 0 wymaga echa z OBU plytek.")
    sys.exit(if (ran.forall(identity)) 0 else 1)
  }

  /** Wysyla `chunks` porcji po HilEcho.probeChunk bajtow i porownuje echo.
    * Porcja nie przekracza FIFO echa na FPGA (echo_param_bounds). */
  def probe(portName : String, baud : Int, chunks : Int, seed : Long) : Either[String, Ok] = {
    val port = SerialPort.getCommPort(portName)
    port.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
    port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
    port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 50, 0)
    // DTR/RTS zostawiamy jak po otwarciu: na USB-Serial-JTAG S3 niektore
    // kombinacje resetuja uklad (tak robi idf.py flash).
    if (!port.openPort())
      return Left(s"nie mozna otworzyc portu (zajety? uprawnienia do grupy dialout?)")

    try {
      // Po otwarciu moga przyjsc resztki: log ROM S3 po resecie, prompt,
      // stare echo. Czekamy na 300 ms ciszy, najwyzej 3 s.
      val junk = drain(port, quietMs = 300, maxMs = 3000)

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
          val extra = drain(port, quietMs = 200, maxMs = 1000)
          if (extra > 0) Left(s"$extra B nadmiarowych po ostatniej porcji (podwojne echo?)")
          else Right(Ok(chunks * chunk, ms, junk))
      }
    } finally {
      port.closePort()
    }
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

  /** Odrzuca przychodzace bajty, az bedzie quietMs ciszy. Zwraca ich liczbe. */
  private def drain(port : SerialPort, quietMs : Long, maxMs : Long) : Int = {
    val tmp      = new Array[Byte](256)
    var total    = 0
    val start    = System.currentTimeMillis
    var lastData = start
    while (System.currentTimeMillis - lastData < quietMs &&
           System.currentTimeMillis - start < maxMs) {
      val r = port.readBytes(tmp, tmp.length)
      if (r > 0) { total += r; lastData = System.currentTimeMillis }
    }
    total
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

  private def argValue(args : Array[String], key : String) : Option[String] =
    args.sliding(2).collectFirst { case Array(`key`, v) => v }
}
