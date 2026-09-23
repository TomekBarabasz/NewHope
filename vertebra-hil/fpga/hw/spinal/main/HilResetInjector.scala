package newhope.vertebra.hil

import spinal.core._
import spinal.lib._

// =====================================================================
//  Reset DUT-a w losowych chwilach (contract/commands.md, rst_*).
//  Dziala w biezacej domenie (dut). Harmonogram opisuje HilResetModel:
//  po starcie `rst_count` resetow po `rst_len` cykli, odstep przed k-tym
//  to rst_min + (r_k & rst_mask) cykli, r_k z xorshift32 od (rst_seed | 1).
//  Wyjscie z rejestru, bez szpilek. Stop przerywa i zwalnia reset.
//  rst_min + rst_mask < 2^32 (host).
// =====================================================================
case class HilResetInjector() extends Component {
  val io = new Bundle {
    val start    = in  Bool()           // impuls
    val stop     = in  Bool()           // impuls
    val cfg      = in(HilRunCfg())      // zatrzasniete przy starcie
    val dutReset = out Bool()           // aktywny wysoko (Config)
    val done     = out UInt(32 bits)    // wykonane resety w tym biegu
  }

  object Ph extends SpinalEnum { val idle, pause, active = newElement() }

  val ph        = RegInit(Ph.idle)
  val timer     = Reg(UInt(32 bits)) init 0
  val rand      = Reg(Bits(32 bits)) init 0
  val remaining = Reg(UInt(16 bits)) init 0
  val rst       = RegInit(False)
  val done      = Reg(UInt(32 bits)) init 0

  private def delay(r : Bits) : UInt = io.cfg.rstMin + (r.asUInt & io.cfg.rstMask)
  val lenM1 = Mux(io.cfg.rstLen === 0, U(0, 16 bits), io.cfg.rstLen - 1).resize(32 bits)

  when(io.start) {
    val r1 = HilHw.xorshift32(io.cfg.rstSeed | B(1, 32 bits))
    rand      := r1
    timer     := delay(r1)
    remaining := io.cfg.rstCount
    when(io.cfg.rstCount =/= 0) { ph := Ph.pause } otherwise { ph := Ph.idle }
    rst       := False
    done      := 0
  }.elsewhen(io.stop) {
    ph  := Ph.idle
    rst := False
  }.otherwise {
    switch(ph) {
      is(Ph.pause) {
        when(timer === 0) { rst := True; timer := lenM1; ph := Ph.active }
          .otherwise      { timer := timer - 1 }
      }
      is(Ph.active) {
        when(timer === 0) {
          rst       := False
          done      := done + 1
          remaining := remaining - 1
          when(remaining === 1) { ph := Ph.idle }
            .otherwise {
              val r = HilHw.xorshift32(rand)
              rand  := r
              timer := delay(r)
              ph    := Ph.pause
            }
        } otherwise { timer := timer - 1 }
      }
    }
  }

  io.dutReset := rst
  io.done     := done
}
