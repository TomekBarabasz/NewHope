package newhope.vgademo

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/**
 * Wypelnia bufor wzorcem testowym raz, zaraz po kalibracji.
 *
 * PO CO TO JEST
 * -------------
 * Bez tego po wlaczeniu zasilania na ekranie jest zawartosc DRAM-u po
 * kalibracji, czyli snieg. Snieg wyglada identycznie jak: zla kolejnosc bajtow,
 * zly stride, niedzialajacy prefetch i pomylone piny drabinki. Wzorzec
 * generowany w FPGA rozdziela debug toru wyswietlania od debugu UART-a -
 * jesli ramka i przekatna sa proste i dochodza do rogow, to VGA, adresowanie
 * i czytanie z LPDDR dzialaja, a wszystko dalej to juz wina hosta.
 *
 * Ramka po obwodzie pokazuje wycinek widoczny, przekatne pokazuja stride
 * (bledny stride zagina je w schodki), a szachownica 8 kolorow pokazuje, ze
 * wszystkie osiem bitow koloru dociera do drabinki.
 *
 * KOLEJNOSC "dane przed komenda": pchamy cala linie (wordsPerLine slow, mniej
 * niz 64-slowowe FIFO), a dopiero potem wystawiamy jedna komende WRITE na cala
 * linie. Underrun jest wykluczony konstrukcyjnie.
 */
case class FbPainter(c: VgaFbConfig) extends Component {
  val io = new Bundle {
    val bus   = master(FbBus(c))
    val base  = in UInt (c.mig.addrWidth bits)
    val start = in Bool()
    val busy  = out Bool()
  }

  io.bus.idleRead()

  val line = Reg(UInt(log2Up(c.fbHeight + 1) bits)) init 0
  val word = Reg(UInt(log2Up(c.wordsPerLine + 1) bits)) init 0
  val addr = Reg(UInt(c.mig.addrWidth bits)) init 0

  val xWidth = log2Up(c.fbWidth) + 1
  val xBase  = (word << c.wordBits).resize(xWidth bits)

  def pixel(x: UInt, y: UInt): Bits = {
    val border = x === 0 || x === c.fbWidth - 1 || y === 0 || y === c.fbHeight - 1
    val diag   = x === y || (x + y) === c.fbWidth - 1
    val plaid  = x(5 downto 3) ## y(5 downto 3) ## (x(6) ## y(6))
    (border || diag) ? B"8'hFF" | plaid
  }

  /** Bajt 0 (piksel najbardziej na lewo) ma trafic w najmlodszy bajt slowa. */
  val wordData = (0 until c.wordBytes)
    .map(i => pixel((xBase + i).resize(xWidth bits), line.resize(log2Up(c.fbHeight) bits)))
    .reverse
    .reduce(_ ## _)

  io.bus.wr.valid   := False
  io.bus.wr.payload := wordData
  io.bus.cmd.valid  := False
  io.bus.cmd.payload.assignDontCare()

  val fsm = new StateMachine {
    val sIdle = new State with EntryPoint
    val sData = new State
    val sCmd  = new State

    sIdle.whenIsActive {
      when(io.start) {
        line := 0
        word := 0
        addr := io.base
        goto(sData)
      }
    }

    sData.whenIsActive {
      io.bus.wr.valid := True
      when(io.bus.wr.ready) {
        word := word + 1
        when(word === c.wordsPerLine - 1) { goto(sCmd) }
      }
    }

    sCmd.whenIsActive {
      io.bus.cmd.valid         := True
      io.bus.cmd.payload.write := True
      io.bus.cmd.payload.addr  := addr
      io.bus.cmd.payload.bl    := c.wordsPerLine - 1
      when(io.bus.cmd.ready) {
        word := 0
        addr := addr + c.lineStride
        line := line + 1
        when(line === c.fbHeight - 1) { goto(sIdle) } otherwise { goto(sData) }
      }
    }
  }

  io.busy := !fsm.isActive(fsm.sIdle)
}
