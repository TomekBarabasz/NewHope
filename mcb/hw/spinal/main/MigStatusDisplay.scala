package newhope.mcb

import spinal.core._
import spinal.lib._
import newhope.mimas_v2._

object Bcd {
  /**
   * Licznik dziesietny. Kazda cyfra to osobne 4 bity przenoszone przy 9,
   * wiec wartosc idzie na 7-seg wprost - zero dzielenia, zero double-dabble.
   * Przy trzech cyfrach lancuch przeniesienia ma glebokosc 3, co przy 25 MHz
   * jest nieistotne.
   */
  def counter(digits: Int, inc: Bool, clear: Bool): Vec[UInt] = {
    val regs = Vec.fill(digits)(Reg(UInt(4 bits)) init 0)
    var carry: Bool = inc
    for (i <- 0 until digits) {
      val atNine = regs(i) === 9
      when(clear) {
        regs(i) := 0
      } elsewhen (carry) {
        regs(i) := atNine ? U(0, 4 bits) | (regs(i) + 1)
      }
      carry = carry && atNine
    }
    regs
  }
}

/**
 * Status na trzech cyfrach 7-seg.
 *
 *   przed kalibracja     - - -        (trzy minusy)
 *   blad MCB             E E n        n = 1 wr_underrun, 2 wr_error,
 *                                        3 rd_overflow, 4 rd_error
 *   niezgodnosc danych   indeks dziesietnie, WSZYSTKIE TRZY KROPKI
 *   normalna praca       przebiegi na sekunde, bez kropek
 *
 * Kropki sa znacznikiem trybu, wiec nie trzeba marnowac cyfry na litere:
 * trzy zapalone kropki znacza "to jest indeks bledu", zgaszone - "to jest
 * szybkosc". Pomylic sie nie da.
 *
 * Przebiegi na sekunde to bezposredni pomiar przepustowosci, bez oscyloskopu:
 *   MB/s = przebiegi_na_sekunde * bajtow_na_przebieg / 1e6
 * Wspolczynnik wypisuje generator przy elaboracji.
 */
case class MigStatusDisplay(c: MigConfig, reverseDigits: Boolean = true)
    extends Component {

  val io = new Bundle {
    val calib     = in  Bool()
    val dataError = in  Bool()
    val mcbFault  = in  Bool()
    val faultCode = in  UInt (3 bits)
    val errBcd    = in  Vec(UInt(4 bits), 3)
    val passPulse = in  Bool()
    val digits    = out Vec(SevenSegDigit(), 3)
  }

  // ---- okno jednej sekundy -------------------------------------------
  val tickCnt  = Reg(UInt(log2Up(c.uiClkHz.toInt + 1) bits)) init 0
  val tick     = tickCnt === (c.uiClkHz.toInt - 1)
  tickCnt := tick ? U(0) | (tickCnt + 1)

  val rateNow   = Bcd.counter(3, inc = io.passPulse, clear = tick)
  val rateShown = Vec.fill(3)(Reg(UInt(4 bits)) init 0)
  when(tick) { for (i <- 0 until 3) rateShown(i) := rateNow(i) }

  // ---- wybor tego, co pokazac ----------------------------------------
  import SevenSegMux.{blank, minus, letterE}

  val codes = Vec(UInt(4 bits), 3)
  val dots  = Bool()

  // Domyslnie szybkosc, z wygaszeniem wiodacych zer.
  codes(0) := rateShown(0)
  codes(1) := (rateShown(2) === 0 && rateShown(1) === 0) ? U(blank, 4 bits) | rateShown(1)
  codes(2) := (rateShown(2) === 0) ? U(blank, 4 bits) | rateShown(2)
  dots     := False

  // Niezgodnosc danych: indeks dziesietnie, wiodace zera ZOSTAJA (007 czyta
  // sie lepiej niz __7, gdy wiadomo, ze to indeks), kropki zapalone.
  when(io.dataError) {
    codes(0) := io.errBcd(0)
    codes(1) := io.errBcd(1)
    codes(2) := io.errBcd(2)
    dots     := True
  }

  // Blad MCB ma wyzszy priorytet - oznacza utracone dane, a nie tylko zle.
  when(io.mcbFault) {
    codes(0) := io.faultCode.resize(4)
    codes(1) := U(letterE, 4 bits)
    codes(2) := U(letterE, 4 bits)
    dots     := False
  }

  when(!io.calib) {
    codes(0) := U(minus, 4 bits)
    codes(1) := U(minus, 4 bits)
    codes(2) := U(minus, 4 bits)
    dots     := False
  }

  // codes(0) to jednosci, codes(2) setki. Na plytce en(0) okazal sie cyfra
  // NAJBARDZIEJ NA LEWO, wiec bez odwrocenia liczby wyswietlaly sie wspak
  // (381 pokazywalo sie jako 183). Potwierdzone pomiarem przepustowosci.
  for (i <- 0 until 3) {
    val src = if (reverseDigits) 2 - i else i
    io.digits(i).code := codes(src)
    io.digits(i).dot  := dots
  }
}
