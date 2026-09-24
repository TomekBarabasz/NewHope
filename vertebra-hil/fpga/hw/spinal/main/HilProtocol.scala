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

    // Generator (0x020-0x023) i wstrzykiwanie resetu (0x040-0x044): HilRunRegs.
    val GenSeed  = 0x020
    val GapMode  = 0x021
    val GapEvery = 0x022
    val GapLen   = 0x023
    val RstCount = 0x040
    val RstSeed  = 0x041
    val RstMin   = 0x042
    val RstMask  = 0x043
    val RstLen   = 0x044
    val RunBase  = 0x020
    val RunSize  = 0x030

    /** Blok konfiguracji IP (np. I2sHilRegs). */
    val IpBase   = 0x100
    val IpSize   = 0x100
  }

  /** Liczniki 0x010-0x01D: migawka z domeny DUT-a przy stop (HilCounters).
    * Kolejnosc = adresy od Counters.Base. */
  object Counters {
    val Base  = 0x010
    val Size  = 0x010
    val names : Seq[String] = Seq(
      "sent", "frames", "bad", "gaps", "relocks", "lock_at",
      "err_n", "err_got_l", "err_got_r", "err_exp_l", "err_exp_r",
      "overflow", "cap_count", "rst_done")
    def addr(name : String) : Int = {
      val i = names.indexOf(name)
      require(i >= 0, s"nie ma licznika $name")
      Base + i
    }
    /** Pierwsze `fromIp` slow pochodzi z checkera/generatora IP, reszte
      * dokladaja HilCapture (cap_count) i HilResetInjector (rst_done). */
    val fromIp = names.indexOf("cap_count")
  }

  /** Bufor przechwytywania (HilCapture): wpis i pod Base + i*Stride + j,
    * j = 0 idx, 1 got_l, 2 got_r, 3 exp_l, 4 exp_r, 5..7 czytaja sie jako 0. */
  object Capture {
    val Base          = 0x1000
    val Depth         = 32
    val Stride        = 8
    val WordsPerEntry = 5
    def size : Int    = Depth * Stride
  }

  object CtrlBit   { val Start = 0; val Stop = 1; val SoftReset = 2 }
  /** Snapshot: liczniki po ostatnim stop sa gotowe do odczytu (kasuje go start). */
  object StatusBit { val Running = 0; val Locked = 1; val Error = 2; val Snapshot = 3 }

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
  * najmlodszy bit ustawiony, gdy zrodla bitstreamu maja niezacommitowane
  * zmiany, zeby host nie uznal takiego bitstreamu za zgodny z commitem.
  *
  * "Zrodla" to `sources`, a nie cale repo: wygenerowany Verilog i .bin
  * w hw/gen, kod hosta czy dokumentacja nie zmieniaja bitstreamu. Ta sama
  * lista sluzy hostowi do sprawdzenia, czy wgrany bitstream jest aktualny
  * (HilIp.fpgaSources, HilGit). */
object HilBuildInfo {
  import scala.sys.process._

  /** Pathspec gita wzgledem korzenia repo. */
  val sources : Seq[String] = Seq(
    "vertebra-hil/fpga/hw",
    ":(exclude)vertebra-hil/fpga/hw/spinal/test",
    ":(exclude)vertebra-hil/fpga/hw/gen",     // wyniki (Verilog, .bin)
    "i2s/hw/spinal/main",                     // DUT-y
    "mimas_v2/hw/spinal/main")

  lazy val gitHash : Long = scala.util.Try {
    val quiet = ProcessLogger(_ => ())                  // bez "fatal: not a git repository"
    val top   = "git rev-parse --show-toplevel".!!(quiet).trim
    val h     = "git rev-parse --short=8 HEAD".!!(quiet).trim
    val dirty = Process(Seq("git", "-C", top, "status", "--porcelain", "--") ++ sources).!!(quiet).trim.nonEmpty
    val v     = java.lang.Long.parseLong(h.take(8), 16)
    if (dirty) v | 1L else v & ~1L
  }.getOrElse(0L)
}

/** Generator liczb harnessu (wspolny, bez zwiazku z wzorcem IP). */
object HilRand {
  val U32 : Long = 0xFFFFFFFFL
  def xorshift32(x0 : Long) : Long = {
    var x = x0 & U32
    x ^= (x << 13) & U32
    x ^= x >>> 17
    x ^= (x << 5) & U32
    x
  }
}

/** Harmonogram HilResetInjector (contract/commands.md, rst_*).
  *
  * Wzgledem cyklu t0 impulsu dutGo (HilRunRegs: start w domenie DUT-a
  * z juz zatrzasnieta konfiguracja):
  *   r_1 = xorshift32(rst_seed | 1),  r_(k+1) = xorshift32(r_k)
  *   d_k = rst_min + (r_k & rst_mask)
  *   a_1 = t0 + 2 + d_1,  a_(k+1) = a_k + len + 1 + d_(k+1)
  * Reset k trwa cykle [a_k, a_k + len), len = max(rst_len, 1). Stale 2 i 1
  * to rejestry automatu (start -> licznik -> wyjscie), patrz HilResetInjector. */
object HilResetModel {
  def schedule(seed : Long, count : Int, min : Long, mask : Long, len : Int) : Seq[Long] = {
    val l = scala.math.max(len, 1)
    var r = HilRand.xorshift32(seed | 1L)
    var a = 2L + min + (r & mask)
    val out = scala.collection.mutable.ArrayBuffer[Long]()
    for (k <- 0 until count) {
      if (k > 0) { r = HilRand.xorshift32(r); a = a + l + 1 + min + (r & mask) }
      out += a
    }
    out.toSeq
  }
}
