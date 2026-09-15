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
  val magnitude = dropsFrac ? (absolute + 5) | absolute

  // -------------------------------------------------------------------
  //  Bufor jednego zadania.
  //
  //  Bez tego io.update trafiajacy w zajety konwerter przepada bez
  //  sladu - naruszenie kontraktu Stream. W tej konfiguracji zdarza sie
  //  to raz na dwie sekundy kontra czternascie taktow konwersji, wiec
  //  praktycznie nigdy. Ale "praktycznie nigdy" opiera sie na
  //  zalozeniu o czestotliwosci wolajacego, ktore nie jest zapisane
  //  nigdzie w tym pliku i przestanie byc prawdziwe przy pierwszym
  //  szybszym uzyciu.
  // -------------------------------------------------------------------
  val request    = io.update.valid && !io.update.error
  val pending    = RegInit(False)
  val pendingMag = Reg(UInt(13 bits)) init (0)

  when(request) {
    pendingMag := magnitude
    pending    := !conv.io.cmd.ready      // nie wszedl teraz -> wejdzie pozniej
  } elsewhen (conv.io.cmd.fire) {
    pending := False
  }

  conv.io.cmd.valid   := request || pending
  conv.io.cmd.payload := request ? magnitude | pendingMag

  // -------------------------------------------------------------------
  //  Zatrzask wyniku i flag formatu.
  //
  //  Flagi przepisujemy dopiero na rsp.valid, RAZEM z cyframi. Gdyby
  //  ustawialy sie od razu na io.update, przez czternascie taktow
  //  konwersji nowy format opisywalby stare cyfry - np. "-25" zamiast
  //  "25.0" w trakcie przejscia przez zero. Przy odswiezaniu 1 kHz to
  //  niewidoczne, ale to znowu argument z predkosci, a nie z projektu.
  //
  //  Blad jest wyjatkiem: nie uruchamia konwersji, wiec musi zadzialac
  //  natychmiast. Przed pierwsza probka tez pokazujemy kreski, zeby
  //  zera nie wygladaly jak zmierzone zero stopni.
  // -------------------------------------------------------------------
  val negPend = Reg(Bool()) init (False)
  val bigPend = Reg(Bool()) init (False)

  when(io.update.valid) {
    negPend := isNeg
    bigPend := isBig
  }

  val negReg   = Reg(Bool()) init (False)
  val bigReg   = Reg(Bool()) init (False)
  val errorReg = Reg(Bool()) init (True)
  val bcd      = Vec.fill(4)(Reg(UInt(4 bits)) init (0))

  when(io.update.valid && io.update.error) { errorReg := True }

  when(conv.io.rsp.valid) {
    for (i <- 0 until 4) bcd(i) := conv.io.rsp.payload(i)
    negReg   := negPend
    bigReg   := bigPend
    errorReg := False
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
