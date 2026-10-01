package newhope.frontend

import spinal.core._
import spinal.lib._

// =====================================================================
//  |X|^2 z mantysy BFP. Dokladnie, bez zaokraglen:
//    p = re^2 + im^2  (2W bitow bez znaku: max 2 * 2^(2W-2) = 2^(2W-1))
//    exp_p = 2 * exp
//  Wejscie banku mel (kontrakt po D-005). Po log2 wykladnik to dodanie
//  stalej: log2(p * 2^exp_p) = log2(p) + exp_p.
// =====================================================================
class PowerSpectrum(val w: Int, val e: Int) extends Component {
  val io = new Bundle {
    val input  = slave(Stream(Fragment(BfpCplx(w, e))))
    val output = master(Stream(Fragment(BfpPower(2 * w, e + 1))))
  }

  io.output << io.input.translateWith {
    val x = io.input.fragment
    val o = Fragment(BfpPower(2 * w, e + 1))
    o.fragment.p   := ((x.re * x.re).resize(2 * w + 1) + (x.im * x.im).resize(2 * w + 1)).asUInt.resize(2 * w)
    o.fragment.exp := (x.exp << 1).resize(e + 1)
    o.last         := io.input.last
    o
  }.m2sPipe()
}

// =====================================================================
//  |X| = round(sqrt(p)), cyfra po cyfrze (dwa bity radykandu na cykl),
//  W cykli na prazek. Zaokraglenie do najblizszego:
//    r = floor(sqrt(p)), rem = p - r^2;  |X| = r + (rem > r)
//  bo (r + 1/2)^2 = r^2 + r + 1/4.
//  exp_m = exp_p / 2 (exp_p z PowerSpectrum jest zawsze parzysty).
//
//  257 prazkow * 18 cykli = 4,6k cykli na ramke (1,1% budzetu przy
//  41,67 MHz). Do eksploracji - bank mel bierze moc, nie modul.
// =====================================================================
class Magnitude(val w: Int, val e: Int) extends Component {
  val PW = 2 * w
  val io = new Bundle {
    val input  = slave(Stream(Fragment(BfpPower(PW, e + 1))))
    val output = master(Stream(Fragment(BfpMag(w, e))))
  }

  val busy     = RegInit(False)
  val outValid = RegInit(False)
  val cnt      = Reg(UInt(log2Up(w) bits)) init 0
  val rad      = Reg(UInt(PW bits))
  val rem      = Reg(UInt(w + 2 bits))
  val root     = Reg(UInt(w bits))
  val expR     = Reg(SInt(e bits))
  val lastR    = Reg(Bool())

  io.input.ready := !busy && !outValid
  when(io.input.fire) {
    busy  := True
    rad   := io.input.fragment.p
    rem   := U(0)
    root  := U(0)
    cnt   := U(0)
    expR  := (io.input.fragment.exp >> 1).resize(e)
    lastR := io.input.last
  }

  when(busy) {
    val remN  = (rem << 2).resize(w + 4) | rad(PW - 1 downto PW - 2).resize(w + 4)
    val trial = ((root << 2) | U(1, w + 2 bits)).resize(w + 4)
    rad := rad |<< 2
    when(remN >= trial) {
      rem  := (remN - trial).resize(w + 2)
      root := ((root << 1) | U(1, w + 1 bits)).resize(w)
    } otherwise {
      rem  := remN.resize(w + 2)
      root := (root << 1).resize(w)
    }
    cnt := cnt + 1
    when(cnt === U(w - 1)) {
      busy     := False
      outValid := True
    }
  }

  io.output.valid          := outValid
  io.output.fragment.m     := Mux(rem > root.resize(w + 2), root + 1, root)
  io.output.fragment.exp   := expR
  io.output.last           := lastR
  when(io.output.fire) { outValid := False }
}
