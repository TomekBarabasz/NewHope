package newhope.vertebra.hil

// =====================================================================
//  Wspolny interfejs plytki (vertebra-hil.md §8). Scenariusz nie wie, czy
//  rozmawia z ESP32 (tekst, EspDevice), czy z FPGA (rejestry, FpgaDevice):
//  oba maja cfg / start / stop / stat / dump z contract/commands.md.
// =====================================================================

/** Odpowiedz na `ver` / rejestry 0x000-0x006. `build`: 8 cyfr hex hasha
  * gita, "-dirty" gdy drzewo mialo niezacommitowane zmiany. */
case class HilInfo(proto : Int, dev : String, ip : String, build : String,
                   variant : Option[Long] = None) {
  override def toString : String =
    s"proto=$proto dev=$dev ip=$ip build=$build" + variant.fold("")(v => f" variant=$v%08x")
}

/** Pierwszy blad checkera (`first_err`, rejestry err_*). */
case class HilErr(n : Long, gotL : Long, gotR : Long, expL : Long, expR : Long) {
  override def toString : String = f"$n:$gotL%08x:$gotR%08x:$expL%08x:$expR%08x"
}

object HilErr {
  /** "-" albo "n:got_l:got_r:exp_l:exp_r" (n dziesietnie, slowa hex). */
  def parse(s : String) : Either[String, Option[HilErr]] =
    if (s == "-") Right(None)
    else s.split(':') match {
      case Array(n, gl, gr, el, er) =>
        scala.util.Try(HilErr(n.toLong, hex(gl), hex(gr), hex(el), hex(er))).toOption
          .map(e => Right(Some(e))).getOrElse(Left(s"first_err=$s: zly format"))
      case _ => Left(s"first_err=$s: oczekiwane '-' albo n:got_l:got_r:exp_l:exp_r")
    }

  private def hex(s : String) : Long = {
    require(s.nonEmpty && s.length <= 8)
    java.lang.Long.parseLong(s, 16)
  }
}

/** Liczniki z `stat` w ukladzie z contract/commands.md. `extra`: pola spoza
  * wspolnego ukladu (peer_* ESP32 przy loop=1, cap_count i rst_done FPGA). */
case class HilStat(sent     : Long,
                   frames   : Long,
                   bad      : Long,
                   gaps     : Long,
                   relocks  : Long,
                   lockAt   : Long,            // -1 = brak locka
                   firstErr : Option[HilErr],
                   overflow : Long,
                   extra    : Map[String, String] = Map.empty) {
  def locked : Boolean = lockAt >= 0

  /** Bieg bez jednego bledu: lock, co najmniej minFrames zgodnych ramek. */
  def isClean(minFrames : Long) : Boolean =
    locked && frames >= minFrames && bad == 0 && gaps == 0 && relocks == 0 && overflow == 0

  override def toString : String =
    s"sent=$sent frames=$frames bad=$bad gaps=$gaps relocks=$relocks lock_at=$lockAt " +
    s"first_err=${firstErr.fold("-")(_.toString)} overflow=$overflow" +
    extra.map { case (k, v) => s" $k=$v" }.mkString
}

/** Wpis okna wokol pierwszego bledu (`dump`, bufor capture FPGA). */
case class HilDumpEntry(idx : Long, gotL : Long, gotR : Long, expL : Long, expR : Long) {
  override def toString : String = f"$idx $gotL%08x $gotR%08x $expL%08x $expR%08x"
}

/** Plytka odmowila albo nie odpowiedziala. `code`: kod `err` ESP32 albo
  * status mostu FPGA, None przy bledzie transportu. */
class HilDeviceError(val device : String, val code : Option[Int], msg : String)
  extends RuntimeException(s"$device: $msg")

trait HilDevice {
  def label : String
  def link  : HilLink

  def info() : HilInfo
  /** Klucze niepodane zachowuja poprzednia wartosc. Nieznany klucz to blad. */
  def cfg(kv : Seq[(String, String)]) : Unit
  def cfg(kv : (String, Any)*)(implicit d : DummyImplicit) : Unit =
    cfg(kv.map { case (k, v) => k -> v.toString })
  def start() : Unit
  /** Zatrzymuje bieg; po powrocie liczniki sa gotowe do odczytu. */
  def stop() : Unit
  def stat() : HilStat
  def dump() : Seq[HilDumpEntry]

  def close() : Unit = link.close()
}
