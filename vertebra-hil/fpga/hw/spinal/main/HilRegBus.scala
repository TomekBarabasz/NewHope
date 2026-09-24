package newhope.vertebra.hil

import spinal.core._
import spinal.lib._
import scala.collection.mutable.ArrayBuffer
import HilProtocol.Status

// =====================================================================
//  Wewnetrzna magistrala rejestrow harnessu.
//
//  Zamiast Apb3: potrzebujemy kodu bledu z rejestru (zly adres, zapis
//  tylko-do-odczytu, zapis w trakcie biegu), a Apb3 ma tylko jeden bit
//  PSLVERROR. Magistrala jest jednocyklowa i celowo prymitywna:
//    - valid to impuls na jeden cykl, jedna transakcja na raz (most),
//    - odpowiedz (rdata, status) jest kombinacyjna w cyklu valid,
//    - efekt zapisu nastepuje na zboczu konczacym ten cykl,
//    - addr, write i wdata sa stabilne co najmniej od cyklu PRZED valid
//      do konca transakcji (most trzyma je w rejestrach od bajtu adresu),
//      wiec slave moze czytac pamiec synchronicznie z bus.addr
//      (HilCapture: readSync, dana gotowa w cyklu valid).
//  Adres = indeks slowa 32-bitowego, jak w contract/commands.md.
// =====================================================================
case class HilRegBus() extends Bundle with IMasterSlave {
  val valid  = Bool()
  val write  = Bool()
  val addr   = UInt(16 bits)
  val wdata  = Bits(32 bits)
  val rdata  = Bits(32 bits)
  val status = Bits(8 bits)

  override def asMaster() : Unit = { out(valid, write, addr, wdata); in(rdata, status) }
}

object HilRegBus {
  /** Rozdzial magistrali m na slave'y wg zakresow (base, liczba slow).
    * Adres poza wszystkimi zakresami: BadAddr. Slave dostaje pelny adres. */
  def decode(m : HilRegBus, slaves : Seq[(Int, Int, HilRegBus)]) : Unit = {
    val ranges = slaves.map { case (b, s, _) => (b, b + s) }.sortBy(_._1)
    require(ranges.forall { case (b, e) => b >= 0 && e <= 0x10000 && e > b }, s"zakresy $ranges")
    require(ranges.zip(ranges.drop(1)).forall { case (a, b) => a._2 <= b._1 }, s"zakresy sie nakladaja: $ranges")

    m.rdata  := 0
    m.status := B(Status.BadAddr, 8 bits)
    for ((base, size, s) <- slaves) {
      val lo  = if (base == 0) True else m.addr >= base
      val hi  = if (base + size >= 0x10000) True else m.addr < (base + size)
      val hit = lo && hi
      s.valid := m.valid && hit
      s.write := m.write
      s.addr  := m.addr
      s.wdata := m.wdata
      when(hit) { m.rdata := s.rdata; m.status := s.status }
    }
  }

  /** Slave, ktory na wszystko odpowiada BadAddr (np. nieuzywane io.ext). */
  def tieOff(s : HilRegBus) : Unit = { s.rdata := 0; s.status := B(Status.BadAddr, 8 bits) }
}

/** Mapa rejestrow nad HilRegBus - maly odpowiednik BusSlaveFactory.
  *
  * Rejestruje sie wpisy, a na koncu `build()` generuje dekoder. `lock`
  * blokuje zapis rejestrow oznaczonych `locked` (status Busy, bez efektu):
  * konfiguracja zmienia sie tylko w stanie stop (zasada CDC, §6). */
class HilRegMap(bus : HilRegBus, lock : Bool) {
  private case class Entry(addr : Int, read : Bits, write : Option[Bits => Unit], locked : Boolean)
  private val entries = ArrayBuffer[Entry]()
  private var built   = false

  private def add(e : Entry) : Unit = {
    require(!built, "HilRegMap: wpis po build()")
    require(!entries.exists(_.addr == e.addr), f"HilRegMap: adres ${e.addr}%03x zajety")
    entries += e
  }

  /** Tylko odczyt. Zapis: BadOp. */
  def ro(addr : Int, v : Data) : Unit = {
    require(v.getBitsWidth <= 32, s"ro $addr: ${v.getBitsWidth} bitow")
    add(Entry(addr, v.asBits.resize(32), None, locked = false))
  }

  /** Odczyt i zapis rejestru `r` (mlodsze bity slowa). */
  def rw[T <: Data](addr : Int, r : T, locked : Boolean) : T = {
    val w = r.getBitsWidth
    require(w <= 32, s"rw $addr: $w bitow")
    add(Entry(addr, r.asBits.resize(32), Some(d => r.assignFromBits(d.resize(w))), locked))
    r
  }

  /** Jak `rw`, a do tego impuls w cyklu przyjetego zapisu (np. start
    * operacji z zapisanymi parametrami). Odrzucony zapis (Busy) go nie daje. */
  def rwPulse[T <: Data](addr : Int, r : T, locked : Boolean) : Bool = {
    val w = r.getBitsWidth
    require(w <= 32, s"rwPulse $addr: $w bitow")
    val p = Bool()
    p := False
    add(Entry(addr, r.asBits.resize(32), Some { d => r.assignFromBits(d.resize(w)); p := True }, locked))
    p
  }

  /** Rejestr impulsowy: zapis daje valid na jeden cykl z danymi, odczyt 0. */
  def strobe(addr : Int) : Flow[Bits] = {
    val f = Flow(Bits(32 bits))
    f.valid   := False
    f.payload := bus.wdata
    add(Entry(addr, B(0, 32 bits), Some(_ => f.valid := True), locked = false))
    f
  }

  def build() : Unit = {
    require(!built, "HilRegMap: build() dwa razy")
    built = true
    bus.rdata  := 0
    bus.status := B(Status.BadAddr, 8 bits)
    for (e <- entries) when(bus.addr === e.addr) {
      bus.rdata  := e.read
      bus.status := B(Status.Ok, 8 bits)
      when(bus.write) {
        e.write match {
          case None => bus.status := B(Status.BadOp, 8 bits)
          case Some(act) =>
            if (e.locked) {
              when(lock) { bus.status := B(Status.Busy, 8 bits) }
                .elsewhen(bus.valid) { act(bus.wdata) }
            } else when(bus.valid) { act(bus.wdata) }
        }
      }
    }
  }
}
