package newhope.mimas_v2

import spinal.core._
import spinal.lib._

// =====================================================================
//  Kod cyfry podawany do multipleksera.
//
//  Nie UInt(4 bits) traktowany jako liczba, tylko kod - bo poza cyframi
//  0-9 potrzebne sa jeszcze pusta pozycja (wiodace zero przy "9.5") i
//  minus (temperatura ujemna). Ujecie tego jako "cyfra plus dwa
//  wyjatki" oznaczaloby oddzielny sygnal blank i oddzielny sygnal
//  minus ciagnace sie przez caly tor.
// =====================================================================
case class SevenSegDigit() extends Bundle {
  val code = UInt(4 bits)
  val dot  = Bool()
}

object SevenSegMux {
  // Kody niecyfrowe
  val blank   = 10
  val minus   = 11
  val letterE = 12

  // -------------------------------------------------------------------
  //  Tablica segmentow. Bit 0 = a, 1 = b, 2 = c, 3 = d, 4 = e, 5 = f,
  //  6 = g. Ta kolejnosc MUSI zgadzac sie z UCF-em:
  //
  //     seg[0] = A3 = a        seg[4] = C5 = e
  //     seg[1] = B4 = b        seg[5] = D6 = f
  //     seg[2] = A4 = c        seg[6] = C6 = g
  //     seg[3] = C4 = d        seg[7] = A5 = kropka
  //
  //  Wartosci sa "aktywne wysokim" - inwersja pod common anode dzieje
  //  sie na samym koncu, w jednym miejscu. Trzymanie tablicy juz
  //  odwroconej jest kuszace (o jedna bramke mniej), ale wtedy kazdy,
  //  kto na nia patrzy, musi liczyc w glowie.
  // -------------------------------------------------------------------
  private val base = Seq(
    0x3f, // 0 : a b c d e f
    0x06, // 1 : b c
    0x5b, // 2 : a b d e g
    0x4f, // 3 : a b c d g
    0x66, // 4 : b c f g
    0x6d, // 5 : a c d f g
    0x7d, // 6 : a c d e f g
    0x07, // 7 : a b c
    0x7f, // 8 : wszystkie
    0x6f, // 9 : a b c d f g
    0x00, // blank
    0x40, // minus : samo g
    0x79  // E     : a d e f g
  )

  /** Dopelnione do 16 pozycji, bo indeks jest 4-bitowy i Vec musi
    * pokrywac caly zakres. Nieuzywane kody gasza cyfre. */
  val lut : Seq[Int] = base.padTo(16, 0x00)
}

// =====================================================================
//  Multiplekser trzech cyfr.
//
//  Mimas V2 ma trzy modulki 7-seg ze wspolna anoda, przelaczane
//  tranzystorami PNP. Konsekwencje:
//
//   * segmenty aktywne w stanie NISKIM
//   * enable'y takze aktywne w stanie NISKIM
//
//  To drugie jest najczestszym bledem na tej plytce - latwo odwrocic
//  segmenty i zapomniec o enable'ach, i wtedy swieca wszystkie trzy
//  cyfry naraz ta sama wartoscia.
//
//  frameRate to czestotliwosc PELNEJ ramki (trzy cyfry), nie pojedynczej
//  cyfry. 1 kHz daje 333 us na cyfre - powyzej progu migotania z duzym
//  zapasem, ponizej czasu, w ktorym tranzystory zaczynaja byc waskim
//  gardlem.
//
//  blankCycles gasi wszystko na koncu szczeliny. Bez tego pojemnosc
//  segmentow rozladowuje sie juz po przelaczeniu enable'a i sasiednia
//  cyfra swieci slabym echem poprzedniej ("duchowanie"). W symulacji
//  tego nie zobaczysz - to zjawisko analogowe. Ustaw 0 i porownaj na
//  plytce, warto zobaczyc roznice raz w zyciu.
// =====================================================================
case class SevenSegMux(clkFrequency : HertzNumber = 100 MHz,
                       frameRate    : HertzNumber = 1 kHz,
                       blankCycles  : Int         = 64) extends Component {

  val io = new Bundle {
    /** położenie cyfr
      en[0] = najstarsza (pierwsza od lewej)
      en[1] = środek 
      en[2] = najmłodsza (piersza od prawej)
       */
    val digits = in  Vec(SevenSegDigit(), 3)
    val seg    = out Bits (8 bits)
    val en     = out Bits (3 bits)
  }

  val cyclesPerDigit = (clkFrequency / frameRate).toInt / 3
  require(cyclesPerDigit > blankCycles + 1,
    s"szczelina $cyclesPerDigit cykli nie pomiesci wygaszenia $blankCycles")

  // Licznik szczeliny i indeks aktywnej cyfry.
  val slot     = Reg(UInt(log2Up(cyclesPerDigit) bits)) init (0)
  val slotLast = slot === cyclesPerDigit - 1
  slot := slotLast ? U(0) | (slot + 1)

  val index = Reg(UInt(2 bits)) init (0)
  when(slotLast) { index := (index === 2) ? U(0) | (index + 1) }

  val blanking = if (blankCycles == 0) False else slot >= cyclesPerDigit - blankCycles
  val lit      = !blanking

  val lut     = Vec(SevenSegMux.lut.map(v => B(v, 7 bits)))
  val current = io.digits(index)
  val shown   = current.dot ## lut(current.code)     // bit 7 = kropka

  // Jedyne miejsce z inwersja. Wszystko powyzej jest aktywne wysokim.
  io.seg := ~(lit ? shown            | B(0, 8 bits))
  io.en  := ~(lit ? UIntToOh(index, 3) | B(0, 3 bits))
}
