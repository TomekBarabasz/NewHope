package newhope.vertebra.hil

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.mutable
import HilProtocol._

// =====================================================================
//  Atrapy plytek dla HilHostTestplan: HilPort w pamieci i modele obu
//  stron kontraktu (contract/commands.md). Odpowiedz powstaje od razu
//  w write(), wiec testy sa deterministyczne i nie potrzebuja watkow.
// =====================================================================

class FakePort(val name : String) extends HilPort {
  private val in = new ConcurrentLinkedQueue[Byte]()
  var onWrite : Array[Byte] => Unit = _ => ()
  var closed  = false

  def write(b : Array[Byte]) : Boolean = { onWrite(b); true }
  def push(b : Seq[Int]) : Unit = b.foreach(x => in.add(x.toByte))
  def push(s : String) : Unit = push(s.getBytes("US-ASCII").toSeq.map(_ & 0xFF))

  def read(buf : Array[Byte]) : Int = {
    if (in.isEmpty) Thread.sleep(1)
    var n = 0
    while (n < buf.length && !in.isEmpty) { buf(n) = in.poll(); n += 1 }
    n
  }
  def close() : Unit = closed = true
}

/** Most i rejestry harnessu I2S wedlug contract/commands.md. */
class FakeFpga(val variantCode : Long, val build : Long, val ipId : String = "I2S") {
  val port = new FakePort("fake-fpga")
  port.onWrite = b => b.foreach(x => feed(x & 0xFF))

  var running  = false
  /** Po kazdym impulsie ctrl (model magistrali: FakeI2sBus). */
  var onCtrl : Int => Unit = _ => ()
  /** Dodatkowe bity status (model magistrali: lock). */
  var extraStatus : () => Long = () => 0L
  var snapshot = false
  val ctrlPulses = mutable.ArrayBuffer[Int]()             // bity ctrl, ktore zadzialaly
  val writes     = mutable.ArrayBuffer[(Int, Long)]()     // udane zapisy RW

  val rw = mutable.Map[Int, Long](Addr.Scratch -> 0L) ++
    (Seq(Addr.GenSeed, Addr.GapMode, Addr.GapEvery, Addr.GapLen,
         Addr.RstCount, Addr.RstSeed, Addr.RstMin, Addr.RstMask, Addr.RstLen).map(_ -> 0L)) ++
    Seq(0x100 -> 0L, 0x101 -> (variantCode & 0xFF), 0x102 -> ((variantCode >> 8) & 0xFF))

  val counters = mutable.Map[String, Long]().withDefaultValue(0L)
  counters("lock_at") = 0xFFFFFFFFL
  var capture : Seq[Seq[Long]] = Nil

  // --- wstrzykiwanie bledow (licza sie ramki, ktore przyszly) --------------
  var dropResponses    = 0         // most wykonuje, odpowiedz ginie
  var corruptResponses = 0         // odpowiedz ze zla suma
  var corruptRequests  = 0         // ramka dochodzi przeklamana: status BadSum, bez wykonania
  var junkBeforeRsp    : Seq[Int] = Nil

  private val buf = mutable.ArrayBuffer[Int]()
  private var lastByte = 0L

  private def feed(b : Int) : Unit = {
    // Most porzuca niedokonczona ramke po 10 ms ciszy (HilBridgeGenerics.timeoutUs).
    val now = System.nanoTime
    if (buf.nonEmpty && now - lastByte > HilBridgeGenerics().timeoutUs * 1000L) buf.clear()
    lastByte = now
    if (buf.isEmpty && b != ReqSync) return
    buf += b
    val len = if (buf.size >= 2 && buf(1) == OpWrite) WriteReqBytes else ReadReqBytes
    if (buf.size == len) { val f = buf.toSeq; buf.clear(); exec(f) }
  }

  private def readReg(a : Int) : Either[Int, Long] = a match {
    case Addr.Magic   => Right(Magic)
    case Addr.Proto   => Right(ProtoVersion.toLong)
    case Addr.IpId    => Right(ascii4(ipId))
    case Addr.Build   => Right(build)
    case Addr.Ctrl    => Right(0L)
    case Addr.Status  => Right((if (running) 1L << StatusBit.Running else 0L) |
                               (if (snapshot) 1L << StatusBit.Snapshot else 0L) | extraStatus())
    case Addr.Variant => Right(variantCode)
    case x if x >= Counters.Base && x < Counters.Base + Counters.names.size =>
      Right(counters(Counters.names(x - Counters.Base)))
    case x if x >= Capture.Base && x < Capture.Base + Capture.size =>
      val (i, j) = ((x - Capture.Base) / Capture.Stride, (x - Capture.Base) % Capture.Stride)
      Right(if (i < capture.size && j < Capture.WordsPerEntry) capture(i)(j) else 0L)
    case x => rw.get(x).toRight(Status.BadAddr)
  }

  private def exec(f : Seq[Int]) : Unit = {
    val status : Int = if (corruptRequests > 0) { corruptRequests -= 1; Status.BadSum }
                       else if (xorSum(f.init) != f.last) Status.BadSum else Status.Ok
    val addr = (f(2) << 8) | f(3)
    val (st, data) =
      if (status != Status.Ok) (status, 0L)
      else f(1) match {
        case OpRead => readReg(addr).fold(e => (e, 0L), v => (Status.Ok, v))
        case OpWrite =>
          val d = f.slice(4, 8).foldLeft(0L)((a, x) => (a << 8) | x)
          if (addr == Addr.Ctrl) {
            for (bit <- 0 to 2 if (d & (1L << bit)) != 0) {
              ctrlPulses += bit
              bit match {
                case CtrlBit.Start => running = true; snapshot = false
                case CtrlBit.Stop  => snapshot = true; running = false     // migawka zawsze (HilCounters)
                case _             => running = false
              }
              onCtrl(bit)
            }
            (Status.Ok, d)
          } else if (rw.contains(addr)) {
            if (running && addr != Addr.Scratch) (Status.Busy, 0L)
            else { rw(addr) = d; writes += addr -> d; (Status.Ok, d) }
          } else if (readReg(addr).isRight) (Status.BadOp, 0L)
          else (Status.BadAddr, 0L)
        case _ => (Status.BadOp, 0L)
      }
    if (dropResponses > 0) { dropResponses -= 1; return }
    port.push(junkBeforeRsp); junkBeforeRsp = Nil
    val rsp = response(st, data)
    if (corruptResponses > 0) { corruptResponses -= 1; port.push(rsp.init :+ (rsp.last ^ 0x40)) }
    else port.push(rsp)
  }
}

/** ESP32 w postaci skryptu: linia komendy -> linie odpowiedzi. */
class FakeEsp(respond : String => Seq[String]) {
  val port = new FakePort("fake-esp")
  val received = mutable.ArrayBuffer[String]()
  private val line = new StringBuilder
  port.onWrite = b => b.foreach { x =>
    x.toChar match {
      case '\n' | '\r' =>
        if (line.nonEmpty) {
          val l = line.toString; line.clear(); received += l
          respond(l).foreach(r => port.push(r + "\n"))
        }
      case c => line += c
    }
  }
}

object FakeEsp {
  /** Zdrowy firmware: ver, cfg, start, stop, stat z podanym tekstem. */
  def healthy(build : String, stat : String = "sent=0 frames=0 bad=0 gaps=0 relocks=0 lock_at=-1 first_err=- overflow=0",
              vectors : Int = 702) : String => Seq[String] = {
    case "ver"                   => Seq(s"ok proto=1 dev=esp32s3 ip=i2s build=$build")
    case l if l.startsWith("cfg") =>
      if (l.split(' ').drop(1).forall(kv => Set("role", "fs", "w", "slot", "seed", "peer_w", "tx", "rx", "loop")(kv.takeWhile(_ != '='))))
        Seq("ok") else Seq("err 2 nieznany klucz")
    case "start" | "stop"        => Seq("ok")
    case "stat"                  => Seq(s"ok $stat")
    case "selftest"              => Seq("# petla w=16 slot=32 fs=48000: frames=2400 lock_at=0 ok", s"ok vectors=$vectors")
    case "dump"                  => Seq("ok n=0", "ok end")
    case c                       => Seq(s"err 1 nieznana komenda '$c'")
  }
}
