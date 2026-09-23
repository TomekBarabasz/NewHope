package newhope.vertebra.hil

// =====================================================================
//  Protokol mostu FPGA (contract/commands.md, "FPGA: binarny most do
//  rejestrow") i wspolna czesc mapy rejestrow.
//
//  Czysta Scala: te same stale i funkcje ramek uzywa HilUartBridge przy
//  elaboracji, testplan w symulacji i host (FpgaDevice, etap 4). Adres
//  w tabeli commands.md to dokumentacja tego obiektu, nie jego zrodlo.
// =====================================================================
object HilProtocol {
  val ProtoVersion = 1

  val ReqSync = 0xA5
  val RspSync = 0x5A
  val OpRead  = 0x01
  val OpWrite = 0x02

  val ReadReqBytes  = 5
  val WriteReqBytes = 9
  val RspBytes      = 7

  object Status {
    val Ok      = 0x00
    val BadSum  = 0x01
    val BadAddr = 0x02
    val BadOp   = 0x03   // nieznana operacja albo zapis do rejestru tylko do odczytu
    val Busy    = 0x04   // zapis konfiguracji w trakcie biegu

    def name(s : Int) : String = s match {
      case Ok => "ok"; case BadSum => "bad_sum"; case BadAddr => "bad_addr"
      case BadOp => "bad_op"; case Busy => "busy"; case x => f"status_$x%02x"
    }
  }

  /** Adresy (indeksy slow 32-bitowych) wspolnej czesci mapy. */
  object Addr {
    val Magic   = 0x000
    val Proto   = 0x001
    val IpId    = 0x002
    val Build   = 0x003
    val Ctrl    = 0x004
    val Status  = 0x005
    val Variant = 0x006
    val Scratch = 0x007

    /** Rejestry rdzenia zajmuja 0x000-0x00F; reszta idzie dalej (io.ext). */
    val CoreBase = 0x000
    val CoreSize = 0x010
  }

  object CtrlBit   { val Start = 0; val Stop = 1; val SoftReset = 2 }
  object StatusBit { val Running = 0; val Locked = 1; val Error = 2 }

  val Magic : Long = 0x48494C31L          // "HIL1"

  /** 4 znaki ASCII -> slowo, pierwszy znak w MSB ("I2S\0" -> 0x49325300). */
  def ascii4(s : String) : Long = {
    require(s.length <= 4 && s.forall(_ < 128), s"ascii4: '$s'")
    s.padTo(4, '\u0000').foldLeft(0L)((a, c) => (a << 8) | c.toLong)
  }

  def xorSum(bytes : Seq[Int]) : Int = bytes.foldLeft(0)(_ ^ _) & 0xFF

  private def be16(v : Int)  : Seq[Int] = Seq((v >> 8) & 0xFF, v & 0xFF)
  private def be32(v : Long) : Seq[Int] = Seq(24, 16, 8, 0).map(s => ((v >> s) & 0xFF).toInt)

  private def withSum(b : Seq[Int]) : Seq[Int] = b :+ xorSum(b)

  def readReq(addr : Int) : Seq[Int] = {
    require(addr >= 0 && addr <= 0xFFFF, f"adres $addr%x")
    withSum(Seq(ReqSync, OpRead) ++ be16(addr))
  }

  def writeReq(addr : Int, data : Long) : Seq[Int] = {
    require(addr >= 0 && addr <= 0xFFFF, f"adres $addr%x")
    withSum(Seq(ReqSync, OpWrite) ++ be16(addr) ++ be32(data))
  }

  case class Response(status : Int, data : Long) {
    def ok : Boolean = status == Status.Ok
    override def toString : String = f"${Status.name(status)} data=$data%08x"
  }

  def response(status : Int, data : Long) : Seq[Int] =
    withSum(Seq(RspSync, status & 0xFF) ++ be32(data))

  /** Odpowiedz z 7 bajtow; Left z opisem, gdy ramka jest uszkodzona. */
  def parseResponse(b : Seq[Int]) : Either[String, Response] =
    if (b.size != RspBytes) Left(s"odpowiedz ma ${b.size} bajtow, a nie $RspBytes")
    else if (b.head != RspSync) Left(f"pierwszy bajt ${b.head}%02x, a nie $RspSync%02x")
    else if (xorSum(b.init) != b.last) Left(f"zla suma: ${b.last}%02x, policzona ${xorSum(b.init)}%02x")
    else Right(Response(b(1), b.slice(2, 6).foldLeft(0L)((a, x) => (a << 8) | x)))
}

/** Hash gita w chwili elaboracji (rejestr build). 0, gdy git niedostepny;
  * najmlodszy bit ustawiony, gdy drzewo ma niezacommitowane zmiany, zeby
  * host nie uznal takiego bitstreamu za zgodny z commitem. */
object HilBuildInfo {
  import scala.sys.process._
  lazy val gitHash : Long = scala.util.Try {
    val quiet = ProcessLogger(_ => ())                  // bez "fatal: not a git repository"
    val h     = "git rev-parse --short=8 HEAD".!!(quiet).trim
    val dirty = "git status --porcelain".!!(quiet).trim.nonEmpty
    val v     = java.lang.Long.parseLong(h.take(8), 16)
    if (dirty) v | 1L else v & ~1L
  }.getOrElse(0L)
}
