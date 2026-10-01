package newhope.frontend

import spinal.core._
import spinal.lib._

case class FramerWord(w: Int) extends Bundle {
  val y    = SInt(w bits)
  val last = Bool()
}

// =====================================================================
//  Bufor pierscieniowy fftSize probek, co `hop` probek wystawia ramke:
//  ostatnie fftSize probek (najstarsza pierwsza), okno Hanna
//  (periodyczne), spakowane parami: z[m] = y[2m] + j y[2m+1].
//  Wyjscie: fftSize/2 probek zespolonych, exp = 0, last na ostatniej.
//
//  Przed pierwszymi fftSize probkami bufor zawiera zera - pierwsza
//  ramka wychodzi po `hop` probkach i jest w wiekszosci cisza.
//
//  Wejscie to Flow, nie Stream: probek z mikrofonu nie da sie
//  wstrzymac. Jesli wyjscie stoi tak dlugo, ze nowa probka nadpisuje
//  jeszcze nieodczytana, albo nadchodzi nowa ramka, gdy poprzednia
//  jest jeszcze czytana, `overrun` zapala sie na stale (do resetu).
//  Nowa ramka jest wtedy pomijana.
//
//  Okno: ROM 0..fftSize/2 (symetria w[i] = w[L - i]), skala
//  2^(ww-1) - 1, mnozenie 18x18 z zaokragleniem.
// =====================================================================
class Framer(val g: FramerGenerics) extends Component {
  require(g.isLegal, g.problems.mkString("; "))
  import g._
  val L  = fftSize
  val SW = sampleWidth
  val WW = windowWidth

  val io = new Bundle {
    val input   = slave(Flow(SInt(SW bits)))
    val output  = master(Stream(Fragment(BfpCplx(SW, expWidth))))
    val overrun = out Bool()
  }

  val ring = Mem(SInt(SW bits), L) init Seq.fill(L)(S(0, SW bits))
  val win  = Mem(SInt(WW bits), Bfp.hannTable(L, windowScale).map(v => S(v, WW bits)))

  // ---- zapis ---------------------------------------------------------
  val wrPtr    = Reg(UInt(logL bits)) init 0
  val hopCnt   = Reg(UInt(log2Up(hop) bits)) init 0
  val frameReq = io.input.valid && hopCnt === U(hop - 1)
  ring.write(wrPtr, io.input.payload, io.input.valid)
  when(io.input.valid) {
    wrPtr  := wrPtr + 1
    hopCnt := Mux(hopCnt === U(hop - 1), U(0, hopCnt.getWidth bits), hopCnt + 1)
  }

  // ---- odczyt ramki --------------------------------------------------
  val reading  = RegInit(False)
  val rdBase   = Reg(UInt(logL bits)) init 0
  val rdIssued = Reg(UInt(logL + 1 bits)) init 0
  val overrun  = RegInit(False)
  io.overrun := overrun

  val fifo      = StreamFifo(FramerWord(SW), 4)
  val issue     = reading && rdIssued < U(L) && fifo.io.availability >= U(2)
  val idx       = rdIssued.resize(logL)
  val finishing = issue && rdIssued === U(L - 1)
  val winIdx    = Mux(idx <= U(L / 2), idx, U(0, logL bits) - idx)

  val sample = ring.readSync(rdBase + idx, issue)
  val wv     = win.readSync(winIdx.resize(log2Up(L / 2 + 1)), issue)

  when(issue)     { rdIssued := rdIssued + 1 }
  when(finishing) { reading := False }

  // nadpisanie nieodczytanej probki: offset zapisu w biezacej ramce
  // >= liczba juz wydanych odczytow
  val wrOff = (wrPtr - rdBase).resize(logL + 1)
  when(io.input.valid && reading && wrOff >= rdIssued) { overrun := True }

  when(frameReq) {
    when(reading && !finishing) {
      overrun := True
    } otherwise {
      reading  := True
      rdBase   := wrPtr + 1        // najstarsza probka po tym zapisie
      rdIssued := U(0)
    }
  }

  // ---- mnozenie przez okno, t1 -------------------------------------------
  val inflight     = RegNext(issue) init False
  val inflightLast = RegNext(finishing) init False
  fifo.io.push.valid     := inflight
  fifo.io.push.payload.y := BfpHw.roundShr(sample * wv, WW - 1).resize(SW)   // |w| < 1
  fifo.io.push.payload.last := inflightLast

  // ---- parowanie: parzysta -> re, nieparzysta -> im ---------------------
  val first = Reg(SInt(SW bits))
  val have  = RegInit(False)
  io.output.valid          := fifo.io.pop.valid && have
  io.output.fragment.re    := first
  io.output.fragment.im    := fifo.io.pop.y
  io.output.fragment.exp   := S(0)
  io.output.last           := fifo.io.pop.last
  fifo.io.pop.ready := !have || io.output.ready
  when(fifo.io.pop.fire) {
    have := !have
    when(!have) { first := fifo.io.pop.y }
  }
}
