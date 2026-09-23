package newhope.vertebra.hil.i2s

import spinal.core._
import spinal.lib._
import newhope.i2s.I2sFrame
import I2sPatternHw._

// =====================================================================
//  Checker wzorca na io.rx DUT-a (contract/i2s/pattern.md, "Checker").
//  Referencja: I2sCheckerModel; oba przechodza checker_*.csv.
//
//  Jedna jednostka `expected` (2 x xorshift + przesuwniki) liczy na
//  zmiane dwa numery: oczekiwany (next) i ten z pola seq odebranej ramki
//  (czy to ramka wzorca). Wynik jest rejestrowany, wiec ramka zajmuje
//  3 albo 5 cykli; ramki przychodza najwyzej raz na kilkadziesiat
//  cykli (2 sloty po >= 8 bitow, polokres SCK >= 3 cykle). Ramka w
//  trakcie liczenia ustawia flage overrun i przepada.
// =====================================================================
case class I2sCheckStat(wrx : Int) extends Bundle {
  val frames, bad, gaps, relocks = UInt(32 bits)
  val lockAt   = UInt(32 bits)          // 0xFFFFFFFF = brak locka
  val errValid = Bool()
  val errN     = UInt(32 bits)
  val errGot   = I2sFrame(wrx)
  val errExp   = I2sFrame(wrx)
}

case class I2sPatternCheck(wrx : Int) extends Component {
  require(wrx >= 1 && wrx <= 32, s"wrx=$wrx")

  val io = new Bundle {
    val rx       = slave(Flow(I2sFrame(wrx)))
    val clear    = in(Bool())
    val cfg      = in(I2sPatternCfg())
    val stat     = out(I2sCheckStat(wrx))
    val locked   = out Bool()
    val badPulse = out Bool()           // kazda zla ramka po locku (capture, TRIG)
    val overrun  = out Bool()           // sticky
  }

  object Phase extends SpinalEnum { val hunt, confirm, locked = newElement() }
  object Step  extends SpinalEnum { val idle, wait1, cmp1, wait2, cmp2 = newElement() }

  private val none32 = U(BigInt(0xFFFFFFFFL), 32 bits)

  val phase  = RegInit(Phase.hunt)
  val step   = RegInit(Step.idle)
  val got    = Reg(I2sFrame(wrx))
  val k      = Reg(UInt(32 bits)) init 0
  val next   = Reg(UInt(32 bits)) init 0
  val seen   = Reg(UInt(2 bits))  init 0
  val start  = Reg(UInt(32 bits)) init 0
  val idx    = Reg(UInt(32 bits)) init 0
  val curIdx = Reg(UInt(32 bits)) init 0

  val frames   = Reg(UInt(32 bits)) init 0
  val bad      = Reg(UInt(32 bits)) init 0
  val gaps     = Reg(UInt(32 bits)) init 0
  val relocks  = Reg(UInt(32 bits)) init 0
  val lockAt   = Reg(UInt(32 bits)) init none32
  val errValid = RegInit(False)
  val errN     = Reg(UInt(32 bits)) init 0
  val errGot   = Reg(I2sFrame(wrx))
  val errExp   = Reg(I2sFrame(wrx))
  val ovf      = RegInit(False)

  val s     = seqBits(io.cfg.peerW)
  val sMask = seqMask(s).asUInt.resize(32 bits)

  val exp     = RegNext(expected(k, io.cfg, wrx))
  val matches = got.left === exp.left && got.right === exp.right
  val gotSeq  = seqOf(got.left, s).asUInt.resize(32 bits)

  val inSilent = !(io.rx.payload.left.orR || io.rx.payload.right.orR)

  io.badPulse := False

  switch(step) {
    is(Step.idle) {
      when(io.rx.valid) {
        got    := io.rx.payload
        curIdx := idx
        idx    := idx + 1
        when(inSilent) {
          when(phase === Phase.locked) { gaps := gaps + 1 }   // przed lockiem: bez znaczenia
        } otherwise {
          when(phase === Phase.hunt) { k := seqOf(io.rx.payload.left, s).asUInt.resize(32 bits) }
            .otherwise               { k := next }
          step := Step.wait1
        }
      }
    }
    is(Step.wait1) { step := Step.cmp1 }
    is(Step.cmp1) {
      step := Step.idle
      switch(phase) {
        is(Phase.hunt) {
          when(matches) { phase := Phase.confirm; next := k + 1; seen := 1; start := curIdx }
        }
        is(Phase.confirm) {
          when(matches) {
            next := next + 1
            seen := seen + 1
            when(seen === 2) { phase := Phase.locked; frames := frames + 3; lockAt := start }
          } otherwise {
            k := gotSeq; step := Step.wait2                     // moze zaczac nowy ciag
          }
        }
        is(Phase.locked) {
          when(matches) {
            frames := frames + 1; next := next + 1
          } otherwise {
            bad := bad + 1
            io.badPulse := True
            when(!errValid) { errValid := True; errN := next; errGot := got; errExp := exp }
            k := gotSeq; step := Step.wait2                     // ramka wzorca z innym numerem?
          }
        }
      }
    }
    is(Step.wait2) { step := Step.cmp2 }
    is(Step.cmp2) {
      step := Step.idle
      when(phase === Phase.confirm) {
        when(matches) { next := k + 1; seen := 1; start := curIdx }
          .otherwise  { phase := Phase.hunt }
      } otherwise {                                             // locked
        when(matches) { relocks := relocks + 1; next := next + ((k - next) & sMask) + 1 }
          .otherwise  { next := next + 1 }                      // przeklamana ramka zuzywa numer
      }
    }
  }

  when(io.rx.valid && step =/= Step.idle) { ovf := True }

  when(io.clear) {
    phase := Phase.hunt; step := Step.idle
    idx := 0; frames := 0; bad := 0; gaps := 0; relocks := 0
    lockAt := none32; errValid := False; errN := 0; ovf := False
  }

  io.stat.frames   := frames
  io.stat.bad      := bad
  io.stat.gaps     := gaps
  io.stat.relocks  := relocks
  io.stat.lockAt   := lockAt
  io.stat.errValid := errValid
  io.stat.errN     := errN
  io.stat.errGot   := errGot
  io.stat.errExp   := errExp
  io.locked        := phase === Phase.locked
  io.overrun       := ovf
}
