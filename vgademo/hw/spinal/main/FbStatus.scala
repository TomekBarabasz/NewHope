package newhope.vgademo

import spinal.core._
import spinal.lib._
import newhope.mimas_v2.{SevenSegDigit, SevenSegMux}

/** Trzycyfrowy licznik BCD. Zero dzielenia, wartosc idzie na 7-seg wprost. */
case class Bcd3() extends Component {
  val io = new Bundle {
    val inc    = in Bool ()
    val clear  = in Bool ()
    val digits = out Vec (UInt(4 bits), 3) // indeks 0 = jednosci
  }

  val d     = Vec(Reg(UInt(4 bits)) init 0, 3)
  val carry = Vec(Bool(), 3)

  for (i <- 0 until 3) {
    val step = if (i == 0) io.inc else carry(i - 1)
    carry(i) := step && d(i) === 9
    when(step) { d(i) := (d(i) === 9) ? U(0, 4 bits) | (d(i) + 1) }
  }
  when(io.clear) { d.foreach(_ := 0) }

  io.digits := d
}

/**
 * Co pokazuja trzy cyfry:
 *
 *   - - -              przed kalibracja MCB
 *   E E n              blad MCB: 1 wr_underrun, 2 wr_error, 3 rd_overflow, 4 rd_error
 *   liczba Z KROPKAMI  kilobajty odebrane przez UART (w trakcie wgrywania)
 *   liczba BEZ KROPEK  klatki na sekunde na wyjsciu VGA
 *
 * Kropka jako znacznik trybu, a nie prefiks - tablica segmentow ma tylko cyfry,
 * blank, minus i E, wiec przy trzech pozycjach marnowanie jednej na literke
 * bylo drozsze niz jeden bit.
 *
 * FPS to niezly test zdrowia calego toru: przy zegarze UI 25,000 MHz i sumie
 * 800 x 525 musi wyjsc 59 (czasem 60). Cokolwiek innego znaczy, ze zegar
 * piksela nie jest tym, czym myslisz, albo ze strumien pikseli sie zacina.
 */
case class FbStatus(c: VgaFbConfig) extends Component {
  val io = new Bundle {
    val calib      = in Bool ()
    val faults     = in Bits (4 bits)
    val frameStart = in Bool ()
    val bytePulse  = in Bool ()
    val digits     = out Vec (SevenSegDigit(), 3) // indeks 0 = od lewej
  }

  val secWidth = log2Up(BigInt(c.uiClkHz))

  // ---- klatki na sekunde ------------------------------------------------
  val secondCnt  = Reg(UInt(secWidth bits)) init 0
  val secondTick = secondCnt === U(c.uiClkHz - 1, secWidth bits)
  secondCnt := secondTick ? U(0, secWidth bits) | (secondCnt + 1)

  val fpsCnt = Bcd3()
  fpsCnt.io.inc   := io.frameStart
  fpsCnt.io.clear := secondTick

  val fps = Vec(Reg(UInt(4 bits)) init 0, 3)
  when(secondTick) { for (i <- 0 until 3) fps(i) := fpsCnt.io.digits(i) }

  // ---- kilobajty z UART -------------------------------------------------
  val byteCnt = Reg(UInt(10 bits)) init 0
  when(io.bytePulse) { byteCnt := byteCnt + 1 }

  val kbCnt = Bcd3()
  kbCnt.io.inc   := io.bytePulse && byteCnt === 1023
  kbCnt.io.clear := False

  /** Tryb "wgrywanie" trzyma sie sekunde po ostatnim bajcie, zeby po transferze
    * dalo sie odczytac koncowy rozmiar zamiast patrzec na migniecie. */
  val hold = Reg(UInt(secWidth bits)) init 0
  when(hold =/= 0) { hold := hold - 1 }
  when(io.bytePulse) { hold := U(c.uiClkHz - 1, secWidth bits) }
  val uploading = hold =/= 0

  // ---- kod bledu MCB ----------------------------------------------------
  val faultLatch = Reg(Bits(4 bits)) init 0
  faultLatch := faultLatch | io.faults
  val fault     = faultLatch.orR
  val faultCode = UInt(4 bits)
  faultCode := 0
  when(faultLatch(0)) { faultCode := 4 } // rd_error
  when(faultLatch(1)) { faultCode := 3 } // rd_overflow
  when(faultLatch(2)) { faultCode := 2 } // wr_error
  when(faultLatch(3)) { faultCode := 1 } // wr_underrun  (najpowazniejszy na wierzchu)

  // ---- zlozenie ---------------------------------------------------------
  val value = Vec(UInt(4 bits), 3)
  for (i <- 0 until 3) value(i) := uploading ? kbCnt.io.digits(i) | fps(i)

  val blankH = value(2) === 0
  val blankT = blankH && value(1) === 0
  val blank  = U(SevenSegMux.blank, 4 bits)

  io.digits(0).code := blankH ? blank | value(2)
  io.digits(1).code := blankT ? blank | value(1)
  io.digits(2).code := value(0)
  for (i <- 0 until 3) io.digits(i).dot := uploading

  when(!io.calib) {
    for (i <- 0 until 3) {
      io.digits(i).code := U(SevenSegMux.minus, 4 bits)
      io.digits(i).dot  := False
    }
  }

  when(fault) {
    io.digits(0).code := U(SevenSegMux.letterE, 4 bits)
    io.digits(1).code := U(SevenSegMux.letterE, 4 bits)
    io.digits(2).code := faultCode
    for (i <- 0 until 3) io.digits(i).dot := False
  }
}
