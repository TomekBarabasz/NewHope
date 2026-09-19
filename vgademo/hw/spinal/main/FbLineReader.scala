package newhope.vgademo

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.graphic.Rgb

/**
 * Zamienia framebuffer w LPDDR na Stream pikseli dla VgaCtrl.
 *
 * DLACZEGO BUFOR LINII
 * --------------------
 * VgaCtrl zabiera dokladnie jeden piksel na takt przez cale 640 taktow linii
 * i nie ma jak czekac. MCB ma latencje zalezna od trafienia w wiersz, stanu
 * odswiezania i tego, co robia inni masterzy. Miedzy jednym a drugim musi stac
 * bufor. Jedna linia (320 albo 640 B) to jeden BRAM i caly problem znika.
 *
 * SCHEMAT KREDYTOWY
 * -----------------
 * Dwa banki, ping-pong. Zamiast liczyc "czy zdazylem", trzymamy licznik
 * kredytow = ile bankow jest wolnych do wypelnienia:
 *   frameStart          -> kredyt = 2 (oba banki wolne, jestesmy w vblank)
 *   koniec linii fb     -> kredyt + 1 (bank wlasnie sie zwolnil)
 *   start pobierania    -> kredyt - 1
 * Kredyt nie moze przekroczyc 2, wiec nadpisanie wyswietlanego banku jest
 * niemozliwe konstrukcyjnie, a nie przez dobranie opoznien.
 *
 * Zapas czasu: pobranie linii to komenda + 20 slow, czyli ~30 taktow. Na
 * wykonanie jest cala linia ekranu (800 taktow) albo - przy scale = 2 - dwie.
 *
 * POZYCJA NA EKRANIE liczona jest z io.pixels.fire, a nie z wlasnego licznika
 * taktow. Dzieki temu licznik x/y NIE MOZE rozjechac sie z tym, co VgaCtrl
 * faktycznie wyswietla, nawet gdy strumien sie zatnie - a rozjazd bylby
 * dokladnie tym bledem, ktorego nie widac na oscyloskopie.
 */
case class FbLineReader(c: VgaFbConfig) extends Component {
  val io = new Bundle {
    val bus        = master(FbBus(c))
    val base       = in UInt (c.mig.addrWidth bits)
    val frameStart = in Bool()
    /**
     * Zgoda na dotykanie pamieci, czyli po prostu `calib`. Bez tego automat
     * pobierania rusza z resetu i wystawia READ do MCB przed kalibracja. Dane
     * na taka komende nie wracaja, automat zawisa w sData, `bankReady` nigdy
     * sie nie ustawia i strumien pikseli jest martwy do konca swiata - a MCB
     * nie zglasza przy tym ZADNEJ flagi bledu, bo z jego strony nic zlego sie
     * nie stalo. Objaw: czarny ekran, calib_done swieci, mcb_fault ciemna.
     */
    val enable     = in Bool()
    val pixels     = master Stream (Rgb(c.rgb))
    val underflow  = out Bool()
  }

  io.bus.idleWrite()
  io.bus.cmd.valid := False
  io.bus.cmd.payload.assignDontCare()
  io.bus.rd.ready := False

  // =====================================================================
  //  Strona wyswietlania
  // =====================================================================
  val x = Reg(UInt(log2Up(c.h.visible) bits)) init 0
  val y = Reg(UInt(log2Up(c.v.visible) bits)) init 0

  val lineEnd = io.pixels.fire && x === (c.h.visible - 1)

  val xNext = cloneOf(x)
  val yNext = cloneOf(y)
  xNext := x
  yNext := y
  when(io.pixels.fire) { xNext := (x === c.h.visible - 1) ? U(0) | (x + 1) }
  when(lineEnd) { yNext := y + 1 }
  when(io.frameStart) { xNext := 0; yNext := 0 }
  x := xNext
  y := yNext

  /** Linia framebuffera - przy scale = 2 dwie linie ekranu na jedna linie fb. */
  val fbLine     = y >> c.scaleLog
  val fbLineNext = yNext >> c.scaleLog
  val bank       = fbLine.lsb
  val bankNext   = fbLineNext.lsb

  val memAddrWidth = log2Up(2 * c.wordsPerLine)
  val lineMem      = Mem(Bits(c.mig.dataWidth bits), 2 * c.wordsPerLine)

  /**
   * Adres czytania podajemy z WYPRZEDZENIEM jednego taktu (z xNext, nie z x),
   * bo readSync rejestruje wyjscie. Gdyby uzyc x, slowo docieraloby o takt za
   * pozno przy kazdym przekroczeniu granicy 16 pikseli.
   */
  val rdAddr = UInt(memAddrWidth bits)
  rdAddr := (((xNext >> c.scaleLog) >> c.wordBits) +
             (bankNext ? U(c.wordsPerLine, memAddrWidth bits) | U(0, memAddrWidth bits))).resized
  val rdWord = lineMem.readSync(rdAddr)

  /** Bajt piksela w slowie: piksel 0 siedzi w NAJMLODSZYM bajcie. */
  val byteSel = (x >> c.scaleLog)(c.wordBits - 1 downto 0)
  val px      = rdWord(byteSel << 3, 8 bits)

  io.pixels.payload.r := px(7 downto 5).asUInt
  io.pixels.payload.g := px(4 downto 2).asUInt
  io.pixels.payload.b := px(1 downto 0).asUInt

  val bankReady = Vec(RegInit(False), 2)
  io.pixels.valid := bankReady(bank.asUInt)

  /** VgaCtrl zglosi to samo na io.error; trzymamy wlasne wyjscie na diode. */
  io.underflow := io.pixels.ready && !io.pixels.valid

  // =====================================================================
  //  Strona pobierania
  // =====================================================================
  val fetch = new Area {
    val line   = Reg(UInt(log2Up(c.fbHeight + 1) bits)) init 0
    val bankId = RegInit(False)
    val credit = Reg(UInt(2 bits)) init 0
    val addr   = Reg(UInt(c.mig.addrWidth bits)) init 0
    val burst  = Reg(UInt(log2Up(c.burstsPerLine + 1) bits)) init 0
    val word   = Reg(UInt(log2Up(c.burstWords + 1) bits)) init 0
    val wrPtr  = Reg(UInt(memAddrWidth bits)) init 0

    /**
     * frameStart lapiemy w zatrzask zamiast reagowac od razu: gdyby wypadl
     * w srodku burstu, zerowanie `line` przestawiloby bank pod lecacymi
     * danymi. Obsluga czeka do stanu sIdle - w vblank jest na to 35 linii.
     */
    val resync = RegInit(True) setWhen io.frameStart

    val start       = False
    val creditReset = False

    val fbLineDone =
      if (c.scaleLog == 0) lineEnd
      else lineEnd && y(c.scaleLog - 1 downto 0) === U((1 << c.scaleLog) - 1)

    val fsm = new StateMachine {
      val sIdle = new State with EntryPoint
      val sCmd  = new State
      val sData = new State

      sIdle.whenIsActive {
        when(!io.enable) {
          /** Przed kalibracja nie ruszamy portu i trzymamy sie w gotowosci do
            * resynchronizacji z pierwsza klatka, ktora przyjdzie po niej. */
          resync         := True
          bankReady(0)   := False
          bankReady(1)   := False
        } elsewhen (resync) {
          resync      := False
          line        := 0
          bankId      := False
          creditReset := True
        } elsewhen (credit =/= 0 && line < c.fbHeight) {
          start := True
          bankReady(bankId.asUInt) := False
          addr  := (io.base + (line << c.strideLog)).resized
          burst := 0
          word  := 0
          wrPtr := bankId ? U(c.wordsPerLine, memAddrWidth bits) | U(0, memAddrWidth bits)
          goto(sCmd)
        }
      }

      sCmd.whenIsActive {
        io.bus.cmd.valid         := True
        io.bus.cmd.payload.write := False
        io.bus.cmd.payload.addr  := addr
        io.bus.cmd.payload.bl    := c.burstWords - 1
        when(io.bus.cmd.ready) { goto(sData) }
      }

      sData.whenIsActive {
        io.bus.rd.ready := True
        when(io.bus.rd.valid) {
          lineMem.write(wrPtr, io.bus.rd.payload)
          wrPtr := wrPtr + 1
          word  := word + 1
          when(word === c.burstWords - 1) {
            word  := 0
            addr  := addr + c.burstWords * c.wordBytes
            burst := burst + 1
            when(burst === c.burstsPerLine - 1) {
              bankReady(bankId.asUInt) := True
              bankId := !bankId
              line   := line + 1
              goto(sIdle)
            } otherwise {
              goto(sCmd)
            }
          }
        }
      }
    }

    /**
     * Aktualizacja kredytu MUSI stac za automatem, bo czyta `start`, ktore
     * automat wystawia. SpinalHDL czyta sygnal kombinacyjny w kolejnosci
     * zapisu kodu, wiec gdyby ten blok stal wyzej, `start` bylby tu zawsze
     * falszem i kredyt nigdy by nie malal - czyli ochrona przed nadpisaniem
     * wyswietlanego banku istnialaby tylko na papierze.
     *
     * Zbieg fbLineDone i start zeruje sie sam: pierwszy warunek odpada przez
     * `start`, drugi przez `fbLineDone`. Kredyt zostaje bez zmian i tak ma byc.
     */
    when(creditReset) {
      credit := 2
    } elsewhen (fbLineDone && !start && credit =/= 2) {
      credit := credit + 1
    } elsewhen (!fbLineDone && start) {
      credit := credit - 1
    }
  }
}
