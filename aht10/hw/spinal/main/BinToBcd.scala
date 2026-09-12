package newhope.aht10

import spinal.core._
import spinal.lib._

// =====================================================================
//  Binarny -> BCD, double dabble.
//
//  Algorytm: przesuwaj wartosc binarna bit po bicie w lewo do rejestru
//  BCD, a przed kazdym przesunieciem dodaj 3 do kazdej cyfry, ktora ma
//  wartosc 5 lub wieksza. Dodanie 3 przed podwojeniem daje ten sam
//  efekt co odjecie 10 po podwojeniu, czyli przeniesienie do sasiedniej
//  cyfry - tylko bez odejmowania i bez porownania z 10.
//
//  Wersja sekwencyjna, binWidth taktow. Kombinacyjnie tez sie da (to
//  tylko drabinka sumatorow), ale przy 2 sekundach miedzy pomiarami
//  bylby to czysty koszt bez zadnego zysku.
//
//  Wejscie bez znaku. Znak i format sa problemem DisplayFormat -
//  tutaj wchodzi wartosc bezwzgledna.
// =====================================================================
case class BinToBcd(binWidth : Int = 11, bcdDigits : Int = 4) extends Component {

  require(BigInt(10).pow(bcdDigits) > (BigInt(1) << binWidth) - 1,
    s"$bcdDigits cyfr BCD nie pomiesci $binWidth bitow " +
    s"(max ${(BigInt(1) << binWidth) - 1})")

  val io = new Bundle {
    val cmd = slave  Stream (UInt(binWidth bits))
    /** Indeks 0 = jednosci, indeks n-1 = najstarsza cyfra. */
    val rsp = master Flow (Vec(UInt(4 bits), bcdDigits))
  }

  val bin     = Reg(UInt(binWidth bits))
  val bcd     = Vec.fill(bcdDigits)(Reg(UInt(4 bits)) init (0))
  val counter = Reg(UInt(log2Up(binWidth + 1) bits)) init (0)
  val busy    = RegInit(False)
  val done    = RegInit(False)

  // `done` blokuje przyjecie nowej komendy przez jeden takt. Bez tego
  // konwersja moglaby wystartowac w tym samym takcie, w ktorym odbiorca
  // czyta wynik - dziala, ale zeby to uzasadnic, trzeba rozumowac o
  // tym, ze rejestr czytany kombinacyjnie ma jeszcze stara wartosc.
  // Jeden takt przestoju co dwie sekundy nie jest tego wart.
  io.cmd.ready   := !busy && !done
  io.rsp.valid   := done
  io.rsp.payload := bcd

  done := False

  when(!busy) {
    when(io.cmd.fire) {
      bin     := io.cmd.payload
      bcd.foreach(_ := 0)
      counter := 0
      busy    := True
    }
  } otherwise {
    // Korekta przed przesunieciem. Przy pierwszej iteracji BCD jest
    // wyzerowane, wiec korekta nic nie robi - to nie jest przypadek
    // szczegolny, tylko naturalna konsekwencja.
    val adjusted = Vec(bcd.map(dgt => (dgt >= 5) ? (dgt + 3) | dgt))

    // Jeden lancuch przesuwny: bin.msb -> cyfra 0 -> cyfra 1 -> ...
    for (i <- 0 until bcdDigits) {
      val shiftIn = if (i == 0) bin.msb else adjusted(i - 1).msb
      bcd(i) := (adjusted(i)(2 downto 0) ## shiftIn).asUInt
    }
    bin     := bin |<< 1
    counter := counter + 1

    when(counter === binWidth - 1) {
      busy := False
      done := True
    }
  }
}
