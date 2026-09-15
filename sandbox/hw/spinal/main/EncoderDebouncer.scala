package newhope.sandbox

import spinal.core._
import spinal.lib._
import spinal.lib.io.InOutWrapper
import spinal.lib.fsm._

object RotDirection extends SpinalEnum(binarySequential) {
  val CW, CCW = newElement()
}

/** Debouncer "lead-edge + lockout" dla pojedynczej linii.
  *
  * Filozofia: PIERWSZE zbocze styku mechanicznego jest zawsze czyste - drgania
  * przychodza dopiero po nim. Wiec zbocze przepuszczamy natychmiast, a potem
  * maskujemy linie na czas `lockout`. Odwrotnie niz filtr calkujacy, ktory
  * czeka na potwierdzenie i przez to:
  *   - doklada latencje rowna dlugosci okna,
  *   - CALKOWICIE GUBI impulsy krotsze od okna (szybkie krecenie).
  *
  * Mikro-filtr `glitchFilter` (kilka taktow) leci PRZED detektorem zbocza i
  * zabija iglice EMI, nie dotykajac responsywnosci: 4 takty @ 100 MHz = 40 ns.
  *
  * Lockout jest osobny dla kazdej linii - drga tylko ten styk, ktory sie
  * poruszyl, wiec maskowanie A nie moze oslepiac B.
  *
  * Wymaga ustawionej czestotliwosci ClockDomain (ClockDomain.current.frequency).
  * Przeznaczony do domeny szybkiej (100 MHz), NIE do SlowArea.
  *
  * @param pin          surowy, asynchroniczny sygnal z pinu
  * @param lockout      jak dlugo ignorujemy zmiany po wykrytym zboczu
  * @param glitchFilter dlugosc mikro-filtra antyiglicowego w taktach
  * @param idle         poziom spoczynkowy (enkoder z pull-up: true)
  */
object LeadEdgeDebouncer {
  def apply(pin: Bool, lockout: TimeNumber, glitchFilter: Int, idle: Boolean): Bool = 
    new Composite(pin, "deb") {
      def idleLvl = Bool(idle)
      val level = RegInit(idleLvl)
      
      // 1) CDC - 2FF, obowiazkowo na wejsciu asynchronicznym
      val sync = BufferCC(pin, init = idleLvl, bufferDepth = Some(2)) //ale default też jest 2
      
      // 2) mikro-filtr anty-glitch-owy (nanosekundy, nie milisekundy)
      val hist = History(sync, glitchFilter, init = idleLvl).asBits
      val clean = RegInit(idleLvl)
      when(hist.andR) { clean := True }
      when(!hist.orR) { clean := False }

      // 3) lead-edge + lockout
      // FrequencyNumber * TimeNumber = liczba taktow blokady
      val lockCycles = (ClockDomain.current.frequency.getValue * lockout).toBigInt
      val lockTimer = Reg(UInt(log2Up(lockCycles + 1) bits)) init (0)   // 0 = wwhen(lockTimer =/= 0) {
      when(lockTimer =/= 0) {
        lockTimer := lockTimer - 1              // maskujemy drgania
      } otherwise {
        when(clean =/= level) {
          level     := clean                    // pierwsze zbocze -> natychmiast
          lockTimer := U(lockCycles)
        }
      }  
    }.level
}

case class EncoderDebouncer(lockout: TimeNumber = 2 ms, 
                            moveTimeout: TimeNumber = 200 ms,
                            undoOnAbort: Boolean = true) extends Component {
    val io = new Bundle {
        val enc_a = in Bool()
        val enc_b = in Bool()
        val step  = master Flow(RotDirection())
    }
    
    io.step.valid.setAsReg() init(False)
    io.step.valid   := False

    val dir = Reg(RotDirection) init(RotDirection.CW)
    io.step.payload := dir

    val a = LeadEdgeDebouncer(io.enc_a, lockout = lockout, glitchFilter = 4, idle = true)
    val b = LeadEdgeDebouncer(io.enc_b, lockout = lockout, glitchFilter = 4, idle = true)

    val fsm = new StateMachine {
      val guardTm = Timeout(moveTimeout)
      val timeout : Bool = guardTm
      val sIdle: State = new State with EntryPoint {
        whenIsActive {
          guardTm.clear()
          when(!a && b) {
            dir := RotDirection.CW
            io.step.valid := True
            goto(sTurning)
          } elsewhen(a && !b) {
            dir := RotDirection.CCW
            io.step.valid := True
            goto(sTurning)
          }
        }
      }

      val sTurning: State = new State {
        whenIsActive {
          when(!a && !b) {
            goto(sWaitEnd)
          } elsewhen(a && b) {
            if(undoOnAbort) {
              // obrót przerwany - wystawiliśmy już valid
              // musimy cofnąć krok (wystawiamy przeciwny kierunek)
              dir           := Mux(dir === RotDirection.CW, RotDirection.CCW, RotDirection.CW)
              io.step.valid := True
            }
            goto(sIdle)
          } elsewhen (timeout) {
            goto(sIdle)
          }
        }
      }

      val sWaitEnd: State = new State {
        whenIsActive {
          when(a && b) {
            goto(sIdle)
          } elsewhen (timeout) {
            goto(sTurning)
          }
        }
      }
    }
}

object EncoderDebouncerVerilog extends App {
  val cfg = SpinalConfig(
    targetDirectory             = "hw/gen/verilog",
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    anonymSignalUniqueness      = true
  )
  cfg.generateVerilog(InOutWrapper(
    EncoderDebouncer(lockout = 2 ms).setDefinitionName("EncoderDebouncer")))
}
