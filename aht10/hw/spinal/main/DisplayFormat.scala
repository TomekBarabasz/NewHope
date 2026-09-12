package newhope.aht10

import spinal.core._
import spinal.lib._

case class DisplayValue() extends Bundle {
  /** Wartosc razy 10, ze znakiem. */
  val value = SInt(13 bits)
  val error = Bool()
}

// =====================================================================
//  Formatowanie na trzy cyfry.
//
//  Trzy cyfry to twarde ograniczenie plytki i regula musi byc jawna:
//
//    blad             ->  ---
//    wartosc ujemna   ->  minus + dwie cyfry calkowite, bez kropki
//    wartosc >= 100.0 ->  trzy cyfry calkowite, bez kropki
//    pozostale        ->  dwie cyfry + kropka + jedna po przecinku
//
//  Wiodace zero gasimy, a przy jednocyfrowej wartosci ujemnej minus
//  przesuwa sie w prawo (" -5" zamiast "-05").
//
//  DLACZEGO KONWERTER SIEDZI TUTAJ.
//
//  Przy odrzucaniu czesci ulamkowej trzeba zaokraglic - inaczej 199.6
//  pokaze sie jako 199 zamiast 200. Zaokraglenie to dodanie 5 PRZED
//  podzialem przez 10, czyli przed konwersja na BCD. A czy w ogole
//  odrzucamy ulamek, wynika z formatu. Decyzja o formacie musi wiec
//  zapasc wczesniej niz konwersja, i dlatego BinToBcd jest wewnatrz
//  tego komponentu, a nie obok niego.
//
//  Gdyby konwerter stal na zewnatrz, ktos musialby przekazac mu
//  "zaokraglaj albo nie" - czyli i tak podjac te decyzje, tylko
//  w miejscu, ktore o formacie nic nie wie.
// =====================================================================
case class DisplayFormat() extends Component {

  val io = new Bundle {
    val update = slave  Flow (DisplayValue())
    val digits = out Vec (SevenSegDigit(), 3)
  }

  // 13 bitow, nie 12: wartosc bezwzgledna z SInt(13 bits) siega 4096,
  // a po dodaniu zaokraglenia 4101. Na 12 bitach skrajne wartosci
  // zawijaja sie po cichu.
  val conv = BinToBcd(binWidth = 13, bcdDigits = 4)

  val extended  = io.update.value.resize(14)
  val absolute  = ((extended < 0) ? (-extended) | extended).asUInt.resize(13)
  val isNeg     = io.update.value < 0
  val isBig     = absolute >= 1000
  val dropsFrac = isNeg || isBig

  conv.io.cmd.valid   := io.update.valid && !io.update.error
  conv.io.cmd.payload := dropsFrac ? (absolute + 5) | absolute

  // --- zatrzask decyzji o formacie -------------------------------------
  //  Przed pierwsza probka pokazujemy ---, zeby nie sugerowac, ze zera
  //  na wyswietlaczu to zmierzona temperatura.
  val negReg   = Reg(Bool()) init (False)
  val bigReg   = Reg(Bool()) init (False)
  val errorReg = Reg(Bool()) init (True)

  when(io.update.valid) {
    negReg   := isNeg
    bigReg   := isBig
    errorReg := io.update.error
  }

  val bcd = Vec.fill(4)(Reg(UInt(4 bits)) init (0))
  when(conv.io.rsp.valid) {
    for (i <- 0 until 4) bcd(i) := conv.io.rsp.payload(i)
  }

  // Wartosc ujemna nie zmiesci sie z trzema cyframi calkowitymi.
  // Czujnik takich nie zwroci, ale przekrecony odczyt owszem.
  val overflow = negReg && bcd(3) =/= 0

  val codes = Vec(UInt(4 bits), 3)
  val dots  = Vec(Bool(), 3)
  dots.foreach(_ := False)

  when(errorReg || overflow) {
    for (i <- 0 until 3) codes(i) := SevenSegMux.minus
  } elsewhen (negReg) {
    when(bcd(2) === 0) {
      codes(0) := SevenSegMux.blank      //  -5  zamiast -05
      codes(1) := SevenSegMux.minus
      codes(2) := bcd(1)
    } otherwise {
      codes(0) := SevenSegMux.minus
      codes(1) := bcd(2)
      codes(2) := bcd(1)
    }
  } elsewhen (bigReg) {
    codes(0) := bcd(3)
    codes(1) := bcd(2)
    codes(2) := bcd(1)
  } otherwise {
    codes(0) := (bcd(2) === 0) ? U(SevenSegMux.blank, 4 bits) | bcd(2)
    codes(1) := bcd(1)
    codes(2) := bcd(0)
    dots(1)  := True
  }

  for (i <- 0 until 3) {
    io.digits(i).code := codes(i)
    io.digits(i).dot  := dots(i)
  }
}
