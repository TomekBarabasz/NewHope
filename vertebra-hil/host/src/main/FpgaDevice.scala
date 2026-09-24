package newhope.vertebra.hil

import HilProtocol._

// =====================================================================
//  FPGA po binarnym moscie do rejestrow (contract/commands.md, "FPGA").
//
//  Ramki i stale sa z HilProtocol - tego samego obiektu, z ktorego
//  elaboruje sie HilUartBridge - wiec rozjazd hosta i harnessu to blad
//  kompilacji. Klucze `cfg` mapuje na rejestry:
//    - wspolne (generator, reset DUT-a): FpgaDevice.commonKeys
//    - per IP (blok 0x100-): HilFpgaMap, np. I2sFpgaMap
//
//  Ponowienie (contract/commands.md): po timeoutcie albo zlej sumie ramka
//  idzie jeszcze raz; odczyty i zapisy sa idempotentne. Wyjatkiem jest
//  `ctrl` (impulsy): przed ponowieniem host czyta `status` i ponawia
//  tylko wtedy, gdy impuls nie zadzialal.
// =====================================================================

/** Klucz `cfg` FPGA: rejestr i parser wartosci (dziesietnie albo 0x...). */
case class HilCfgKey(addr : Int, parse : String => Either[String, Long])

object HilCfgKey {
  def num(s : String) : Option[Long] = scala.util.Try(
    if (s.startsWith("0x") || s.startsWith("0X")) java.lang.Long.parseLong(s.drop(2), 16) else s.toLong
  ).toOption

  def range(addr : Int, min : Long, max : Long) : HilCfgKey = HilCfgKey(addr, s =>
    num(s).filter(v => v >= min && v <= max).toRight(s"$s poza [$min, $max]"))

  def bits(addr : Int, n : Int) : HilCfgKey = range(addr, 0, (1L << n) - 1)

  /** Nazwy wartosci w kolejnosci kodow 0, 1, ... */
  def enumOf(addr : Int, names : String*) : HilCfgKey = HilCfgKey(addr, s =>
    names.indexOf(s) match {
      case -1 => Left(s"$s: dozwolone ${names.mkString("|")}")
      case i  => Right(i.toLong)
    })
}

/** Czesc FPGA znajaca IP: identyfikator, klucze bloku 0x100- i sprawdzenie
  * calej konfiguracji przed zapisem (np. `checkable` dla I2S). */
trait HilFpgaMap {
  /** Wartosc rejestru ip_id jako tekst, np. "I2S". */
  def ipId : String
  def keys : Map[String, HilCfgKey]
  def variantName(code : Long) : Option[String]
  /** None = konfiguracja poprawna; `get` daje wartosc klucza po zmianie. */
  def validate(variant : Long, get : String => Long) : Option[String] = None
}

class FpgaDevice[R <: HilFpgaMap](val link : HilLink, val map : R, val label : String = "fpga")
  extends HilDevice {
  import FpgaDevice._

  /** Ponowione ramki od otwarcia (timeout, zla suma w ktoras strone). */
  var retries : Int = 0
  /** Bajty odrzucone przy szukaniu 5A odpowiedzi. */
  var skipped : Int = 0

  val allKeys : Map[String, HilCfgKey] = {
    val dup = commonKeys.keySet intersect map.keys.keySet
    require(dup.isEmpty, s"klucze IP zaslaniaja wspolne: ${dup.mkString(", ")}")
    commonKeys ++ map.keys
  }

  /** Odpowiedz z resynchronizacja: bajty przed 5A sa pomijane. */
  private def response(timeoutMs : Long) : Either[String, Response] = {
    val end = System.nanoTime + timeoutMs * 1000000L
    def left = scala.math.max(0L, (end - System.nanoTime) / 1000000L)
    var head = link.recv(1, timeoutMs)
    while (head.nonEmpty && (head(0) & 0xFF) != RspSync) {
      skipped += 1
      head = link.recv(1, left)
    }
    if (head.isEmpty) Left(s"brak odpowiedzi w $timeoutMs ms")
    else {
      val rest = link.recv(RspBytes - 1, left)
      parseResponse((head ++ rest).toSeq.map(_ & 0xFF))
    }
  }

  /** Jedna ramka z ponowieniami. Right: odpowiedz z dowolnym statusem
    * poza zla suma; Left: opis, gdy nie udalo sie ani razu. */
  def transact(req : Seq[Int], attempts : Int = Attempts) : Either[String, Response] = {
    var last : Either[String, Response] = Left("nie wyslano")
    var k = 0
    while (k < attempts) {
      if (k > 0) {
        retries += 1
        // Most porzuca niedokonczona ramke po 10 ms ciszy; resztki
        // spoznionej odpowiedzi tez maja czas dojsc i zostac wyrzucone.
        link.drain(quietMs = BridgeTimeoutMs + 5, maxMs = 200)
        link.note(s"ponowienie ${k}: $last")
      }
      link.send(req.map(_.toByte).toArray)
      last = response(RspTimeoutMs)
      last match {
        case Right(r) if r.status != Status.BadSum => return last
        case _ => k += 1
      }
    }
    last
  }

  private def fail(what : String, r : Either[String, Response]) : Nothing = r match {
    case Left(e)  => throw new HilDeviceError(label, None, s"$what: $e (po $Attempts probach)")
    case Right(x) => throw new HilDeviceError(label, Some(x.status), s"$what: ${Status.name(x.status)}")
  }

  def read(addr : Int) : Long = {
    val r = transact(readReq(addr))
    r match {
      case Right(x) if x.ok => x.data
      case _ => fail(f"odczyt 0x$addr%03x", r)
    }
  }

  def write(addr : Int, data : Long) : Unit = {
    val d = data & 0xFFFFFFFFL
    val r = transact(writeReq(addr, d))
    r match {
      case Right(x) if x.ok && x.data == d =>
      case Right(x) if x.ok =>
        throw new HilDeviceError(label, None, f"zapis 0x$addr%03x <- $d%08x: odpowiedz niesie ${x.data}%08x")
      case _ => fail(f"zapis 0x$addr%03x <- $d%08x", r)
    }
  }

  def status() : Long = read(Addr.Status)
  def running() : Boolean = (status() & (1L << StatusBit.Running)) != 0

  /** Impuls w `ctrl`. Po nieudanej ramce czytamy status: jesli impuls
    * doszedl (zgubila sie tylko odpowiedz), nie wysylamy go drugi raz. */
  private def ctrl(bit : Int, done : Long => Boolean) : Unit = {
    val what = s"ctrl ${Seq("start", "stop", "soft_reset")(bit)}"
    val r = transact(writeReq(Addr.Ctrl, 1L << bit), attempts = 1)
    r match {
      case Right(x) if x.ok =>
      case Right(x) if x.status != Status.BadSum => fail(what, r)
      case _ =>
        if (done(status())) link.note(s"$what: odpowiedz zgubiona, impuls doszedl")
        else {
          retries += 1
          val r2 = transact(writeReq(Addr.Ctrl, 1L << bit), attempts = 1)
          if (!r2.exists(_.ok)) fail(what, r2)
        }
    }
  }

  def info() : HilInfo = {
    val magic = read(Addr.Magic)
    if (magic != Magic)
      throw new HilDeviceError(label, None,
        f"magic=$magic%08x zamiast $Magic%08x: to nie harness HIL (HilEchoTop? port programatora?)")
    val b = read(Addr.Build)
    HilInfo(read(Addr.Proto).toInt, "fpga", fromAscii4(read(Addr.IpId)).toLowerCase,
            buildString(b), Some(read(Addr.Variant)))
  }

  /** Klucze w kolejnosci podania; najpierw wszystkie parsowane i calosc
    * sprawdzona (`validate`), dopiero potem zapis - blad nie zostawia
    * polowy konfiguracji, jak `cfg` ESP32. */
  def cfg(kv : Seq[(String, String)]) : Unit = {
    val staged = kv.map { case (k, v) =>
      val key = allKeys.getOrElse(k, throw new HilDeviceError(label, Some(2),
        s"nieznany klucz '$k' (znane: ${allKeys.keys.toSeq.sorted.mkString(", ")})"))
      k -> key.parse(v).fold(e => throw new HilDeviceError(label, Some(3), s"$k=$e"), identity)
    }
    val dup = staged.groupBy(_._1).collect { case (k, xs) if xs.size > 1 => k }
    if (dup.nonEmpty) throw new HilDeviceError(label, Some(3), s"klucz podany dwa razy: ${dup.mkString(", ")}")
    val byKey = staged.toMap
    val cache = scala.collection.mutable.Map[String, Long]()
    def get(k : String) : Long = byKey.getOrElse(k, cache.getOrElseUpdate(k, read(allKeys(k).addr)))
    map.validate(read(Addr.Variant), get).foreach(e => throw new HilDeviceError(label, Some(3), e))
    staged.foreach { case (k, v) => write(allKeys(k).addr, v) }
  }

  def start() : Unit = ctrl(CtrlBit.Start, s => (s & (1L << StatusBit.Running)) != 0)

  def stop() : Unit = {
    ctrl(CtrlBit.Stop, s => (s & (1L << StatusBit.Running)) == 0)
    val end = System.nanoTime + SnapshotTimeoutMs * 1000000L
    while ((status() & (1L << StatusBit.Snapshot)) == 0)
      if (System.nanoTime - end > 0)
        throw new HilDeviceError(label, None, s"brak migawki licznikow $SnapshotTimeoutMs ms po stop")
  }

  def softReset() : Unit = ctrl(CtrlBit.SoftReset, s => (s & (1L << StatusBit.Running)) == 0)

  private def snapshotOrFail(what : String) : Unit =
    if ((status() & (1L << StatusBit.Snapshot)) == 0)
      throw new HilDeviceError(label, Some(4), s"$what: brak migawki licznikow (bieg trwa albo nie bylo stop)")

  /** Migawka z ostatniego stop (commands.md: liczniki wazne przy status.snapshot). */
  def stat() : HilStat = {
    snapshotOrFail("stat")
    val c = Counters.names.map(n => n -> read(Counters.addr(n))).toMap
    val lockAt = c("lock_at")
    HilStat(c("sent"), c("frames"), c("bad"), c("gaps"), c("relocks"),
            if (lockAt == 0xFFFFFFFFL) -1L else lockAt,
            if (c("bad") == 0) None
            else Some(HilErr(c("err_n"), c("err_got_l"), c("err_got_r"), c("err_exp_l"), c("err_exp_r"))),
            c("overflow"),
            scala.collection.immutable.ListMap("cap_count" -> c("cap_count").toString, "rst_done" -> c("rst_done").toString))
  }

  def dump() : Seq[HilDumpEntry] = {
    snapshotOrFail("dump")
    val n = read(Counters.addr("cap_count")).toInt
    if (n > Capture.Depth) throw new HilDeviceError(label, None, s"cap_count=$n > ${Capture.Depth}")
    (0 until n).map { i =>
      val w = (0 until Capture.WordsPerEntry).map(j => read(Capture.Base + i * Capture.Stride + j))
      HilDumpEntry(w(0), w(1), w(2), w(3), w(4))
    }
  }
}

object FpgaDevice {
  /** Pierwsza proba + dwie powtorki. */
  val Attempts          = 3
  /** Odpowiedz to 7 bajtow (0,6 ms przy 115200); reszta to opoznienia USB
    * (Windows: do kilkunastu ms na strone) z duzym zapasem. */
  val RspTimeoutMs      = 200L
  /** Most porzuca niedokonczona ramke po tylu ms ciszy (HilBridgeGenerics). */
  val BridgeTimeoutMs   = HilBridgeGenerics().timeoutUs / 1000
  val SnapshotTimeoutMs = 500L

  /** Wspolne klucze `cfg` (contract/commands.md, rejestry 0x020-0x044). */
  val commonKeys : Map[String, HilCfgKey] = Map(
    "seed"      -> HilCfgKey.bits(Addr.GenSeed, 32),
    "gap_mode"  -> HilCfgKey.range(Addr.GapMode, 0, 2),
    "gap_every" -> HilCfgKey.bits(Addr.GapEvery, 16),
    "gap_len"   -> HilCfgKey.bits(Addr.GapLen, 16),
    "rst_count" -> HilCfgKey.bits(Addr.RstCount, 16),
    "rst_seed"  -> HilCfgKey.bits(Addr.RstSeed, 32),
    "rst_min"   -> HilCfgKey.bits(Addr.RstMin, 32),
    "rst_mask"  -> HilCfgKey.bits(Addr.RstMask, 32),
    "rst_len"   -> HilCfgKey.bits(Addr.RstLen, 16))

  def fromAscii4(v : Long) : String =
    Seq(24, 16, 8, 0).map(s => ((v >> s) & 0xFF).toChar).takeWhile(_ != '\u0000').mkString

  /** Rejestr build: najmlodszy bit to flaga "dirty" (HilBuildInfo), wiec
    * jako hash liczy sie tylko z wyzerowanym bitem. */
  def buildString(b : Long) : String =
    f"${b & ~1L & 0xFFFFFFFFL}%08x" + (if ((b & 1L) != 0) "-dirty" else "")

}
