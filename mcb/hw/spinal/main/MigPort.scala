package newhope.mcb

import spinal.core._
import spinal.lib._

/**
 * Konfiguracja portu MCB. Jedno miejsce, w ktorym siedza wszystkie liczby
 * zalezne od tego, co wygenerowal MIG.
 *
 * @param dataWidth    szerokosc portu uzytkownika: 32, 64 albo 128 bitow.
 *                     Zmiana wymaga PRZEGENEROWANIA core'a w MIG (inna
 *                     konfiguracja portow) i sprawdzenia nowego .veo.
 * @param dqPins       szerokosc szyny DQ pamieci
 * @param colBits      bity kolumny (u nas 10 -> wiersz 2 kB przy x16)
 * @param memClkPeriod okres zegara pamieci w ps (10000 = 100 MHz)
 * @param uiClkDivider stosunek zegara pamieci do zegara UI. MIG ustawia
 *                     CLKOUT2_DIVIDE tak, ze c3_clk0 = memclk / 4.
 *                     Potwierdzone raportem czasowym: c3_clk0 = 25 MHz.
 *                     Zmiana wymaga edycji infrastructure.v - patrz UG388,
 *                     sekcja "Modifying the Clock Setup".
 * @param countWidth   szerokosc wr_count / rd_count. Dla portu 32-bitowego
 *                     MIG generuje 7 bitow; przy 64/128 SPRAWDZ w .veo.
 */
case class MigConfig(
    dataWidth     : Int  = 32,
    dqPins        : Int  = 16,
    colBits       : Int  = 10,
    memAddrWidth  : Int  = 13,
    bankAddrWidth : Int  = 2,
    // clock period : 10000 -> 100MHz
    // clock period :  8000 -> 125MHz
    // clock period :  6666 -> 150MHz
    // clock period :  6000 -> 166MHz
    memClkPeriod  : Int  = 6000,
    uiClkDivider  : Int  = 4,
    countWidth    : Int  = 7,
    addrWidth     : Int  = 30
) {
  require(Set(32, 64, 128) contains dataWidth,
    "MCB obsluguje port uzytkownika 32, 64 albo 128 bitow")

  def maskWidth    = dataWidth / 8
  def bytesPerWord = dataWidth / 8

  /** Najdluzszy burst MCB: pole bl ma 6 bitow, a BL = bl + 1. */
  def maxBurst  = 64
  /** Glebokosc FIFO danych portu, w slowach. */
  def fifoDepth = 64
  /** Bajtow na wiersz pamieci - burst nie powinien tego przekraczac. */
  def rowBytes  = (1 << colBits) * (dqPins / 8)

  /**
   * Pojemnosc kosci policzona z geometrii, a nie wpisana recznie - dzieki temu
   * niezgodnosc miedzy generikami z .veo a rzeczywista pamiecia wychodzi przy
   * elaboracji, a nie po cichu jako aliasowanie adresow.
   *
   *   13 bitow wiersza -> 64 MB  (512 Mb, MT46H32M16, Mimas V2)
   *   14 bitow wiersza -> 128 MB (1 Gb,   MT46H64M16)
   */
  def memBytes: Long =
    (1L << memAddrWidth) * (1L << bankAddrWidth) * (1L << colBits) * (dqPins / 8)

  def memClkHz: Long = 1000000000000L / memClkPeriod
  def uiClkHz : Long = memClkHz / uiClkDivider
  def uiFrequency    = HertzNumber(BigDecimal(uiClkHz))

  /** Szczyt pamieci: DDR, wiec dwa transfery na takt. */
  def dramPeakBytesPerSec: Long = memClkHz * 2 * (dqPins / 8)
  /** Szczyt portu uzytkownika. Przy 32 b i 25 MHz to tylko 1/4 pamieci. */
  def portPeakBytesPerSec: Long = uiClkHz * bytesPerWord
}

object MigInstr {
  def WRITE    = B"3'b000"
  def READ     = B"3'b001"
  def WRITE_AP = B"3'b010"   // z auto-precharge
  def READ_AP  = B"3'b011"
  def REFRESH  = B"3'b100"
}

/** Komenda do kolejki komend portu. */
case class MigCmd(c: MigConfig) extends Bundle {
  val instr = Bits(3 bits)
  /** Dlugosc burstu MINUS JEDEN. 0 = jedno slowo, 63 = 64 slowa. */
  val bl    = UInt(6 bits)
  /** Adres bajtowy, wyrownany do bytesPerWord. */
  val addr  = UInt(c.addrWidth bits)
}

/** Slowo do kolejki zapisu. */
case class MigWrData(c: MigConfig) extends Bundle {
  val data = Bits(c.dataWidth bits)
  /** Maska aktywna wysokim: bit = 1 oznacza bajt POMINIETY. */
  val mask = Bits(c.maskWidth bits)
}

/**
 * Stream-owy widok jednego portu MCB.
 *
 * Kolejki MCB maja dokladnie semantyke Stream, wiec warstwa jest cienka:
 *   cmd.ready = !cmd_full,  cmd_en = cmd.fire
 *   wr.ready  = !wr_full,   wr_en  = wr.fire
 *   rd.valid  = !rd_empty,  rd_en  = rd.fire   (FIFO jest first-word-fall-through)
 *
 * Czego ta warstwa NIE robi, a o czym musi pamietac kazdy master:
 *  - dane zapisu musza znalezc sie w kolejce PRZED komenda WRITE (albo nadazac
 *    za nia), inaczej MCB zglosi wr_underrun i zapisze smieci,
 *  - nie zamawiaj wiecej odczytow, niz zmiesci sie w 64-slowowym FIFO, bo
 *    dostaniesz rd_overflow,
 *  - dane odczytu wracaja w kolejnosci komend i BEZ tagu,
 *  - zapis nie ma potwierdzenia - o uporzadkowaniu decyduje tylko kolejnosc
 *    komend na tym samym porcie.
 */
case class MigPort(c: MigConfig) extends Bundle with IMasterSlave {
  val cmd = Stream(MigCmd(c))
  val wr  = Stream(MigWrData(c))
  val rd  = Stream(Bits(c.dataWidth bits))

  override def asMaster(): Unit = {
    master(cmd, wr)
    slave(rd)
  }

  /**
   * Podlacza mastera do tej strony portu. Uzywane od strony MCB:
   *   mcb.port.driveFrom(engine.io.port)
   *
   * Nie uzywamy tu `<>`, bo strona MCB to zwykly bundle bez kierunkow
   * (siedzi wewnatrz modulu), wiec SpinalHDL nie mialby jak ich wywnioskowac.
   */
  def driveFrom(m: MigPort): Unit = {
    this.cmd << m.cmd
    this.wr  << m.wr
    m.rd     << this.rd
  }
}
