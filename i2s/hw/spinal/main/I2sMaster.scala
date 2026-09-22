package newhope.i2s

import spinal.core._
import spinal.lib._

// =====================================================================
//  I2S MASTER, format Philips, stereo, TX + RX.
//
//  Kontrakt (ten sam co w naglowku I2sMasterTestplan):
//   - po resecie SCK=0, WS=1, SDO=0; ramka zaczyna sie opadajacym WS,
//     wiec przed pierwsza ramka jest jeden pusty prawy slot,
//   - WS i SDO zmieniaja sie razem z opadajacym SCK, SDI jest
//     probkowane na zboczu, na ktorym SCK wstaje,
//   - io.tx ma bufor na jedna ramke: ramka przyjeta w ramce k wychodzi
//     w ramce k+1; pusty bufor na granicy daje cisze i impuls underrun,
//   - io.rx strzela raz na ramke, bit po opadajacym WS NASTEPNEJ ramki
//     (wtedy przychodzi LSB prawego kanalu); nigdy dla ramki czesciowej.
//
//  SDI nie ma synchronizatora celowo. Dane przychodza synchronicznie do
//  SCK, ktory sami generujemy, a synchronizator przesunalby probkowanie
//  o 2 cykle i przy halfDiv = 1 wypchnal je poza okno waznosci bitu
//  (patrz I2sCodecModel.minHalfDiv). Na FPGA zostaje rejestr w IOB.
// =====================================================================

case class I2sFrame(g : I2sGenerics) extends Bundle {
  val left  = Bits(g.width bits)
  val right = Bits(g.width bits)
}

case class I2sPins() extends Bundle with IMasterSlave {
  val sck, ws, sdo = Bool()
  val sdi          = Bool()
  override def asMaster() : Unit = { out(sck, ws, sdo); in(sdi) }
}

case class I2sMaster(g : I2sGenerics) extends Component {
  require(g.isLegal, s"nielegalne generyki: $g")
  require(g.slotWidth >= 2, "slotWidth >= 2 (opoznienie o bit potrzebuje dwoch pozycji)")

  val io = new Bundle {
    val tx       = slave(Stream(I2sFrame(g)))
    val rx       = master(Flow(I2sFrame(g)))
    val underrun = out Bool()
    val pins     = master(I2sPins())
  }

  private val S = g.slotWidth

  // -------------------------------------------------------------------
  //  Zegar bitowy. tick = jedno zbocze SCK co halfDiv cykli.
  //  halfDiv = 1 osobno: Counter(1) mialby licznik zerowej szerokosci.
  // -------------------------------------------------------------------
  val tick = Bool()
  if (g.halfDiv == 1) tick := True
  else tick := Counter(g.halfDiv, inc = True).willOverflow

  val sck    = Reg(Bool()) init False
  val ws     = Reg(Bool()) init True
  val sdo    = Reg(Bool()) init False
  // Opadajace SCK od poczatku slotu; na narastajacym SCK to jest zarazem
  // numer narastajacego zbocza w slocie (0 = pierwsze po zboczu WS).
  val bitCnt = Reg(UInt(log2Up(S) bits)) init 0

  val rise       = tick && !sck
  val fall       = tick &&  sck
  val slotStart  = fall && bitCnt === S - 1
  val frameStart = slotStart && ws               // WS 1 -> 0

  when(tick) { sck := !sck }
  when(fall) {
    when(slotStart) { bitCnt := 0; ws := !ws }
      .otherwise    { bitCnt := bitCnt + 1 }
  }

  // -------------------------------------------------------------------
  //  TX. Kazde opadajace SCK wystawia MSB rejestru slotu. Na poczatku
  //  slotu idzie jeszcze OSTATNI bit poprzedniego slotu i w tym samym
  //  cyklu laduje sie nowy - stad opoznienie o jeden bit bez
  //  dodatkowego licznika.
  // -------------------------------------------------------------------
  val buf      = Reg(I2sFrame(g))
  val bufValid = Reg(Bool()) init False
  val cur      = Reg(I2sFrame(g))
  val txSh     = Reg(Bits(S bits)) init 0

  val nextFrame = Mux(bufValid, buf, buf.getZero)

  // Kolejnosc blokow ma znaczenie: fire po frameStart, zeby ramka
  // przyjeta w cyklu granicy przy pustym buforze trafila do bufora
  // (ramka k+1), a nie zginela pod `bufValid := False`.
  when(frameStart) { cur := nextFrame; bufValid := False }
  // Brak handshake'u pod resetem: rejestry i tak by go nie zapamietaly.
  io.tx.ready := !bufValid && !ClockDomain.current.isResetActive
  when(io.tx.fire) { buf := io.tx.payload; bufValid := True }

  io.underrun := frameStart && !bufValid

  private def slotBits(w : Bits) : Bits =
    if (g.paddingBits == 0) w else w ## B(0, g.paddingBits bits)

  when(fall) {
    sdo  := txSh.msb
    txSh := txSh |<< 1
    when(frameStart)    { txSh := slotBits(nextFrame.left) }
      .elsewhen(slotStart) { txSh := slotBits(cur.right) }
  }

  // -------------------------------------------------------------------
  //  RX. Na narastajacym SCK nr 0 w slocie (bitCnt == 0) przychodzi
  //  ostatnia pozycja slotu POPRZEDNIEGO kanalu, wiec wtedy slowo tego
  //  kanalu jest kompletne: S-1 bitow w rejestrze + biezacy SDI.
  //  Liczymy pozycje od zbocza, a monitor stosuje regule "kanal z
  //  poprzedniego narastajacego SCK" - dwie rozne implementacje tego
  //  samego formatu, celowo.
  // -------------------------------------------------------------------
  val rxSh      = Reg(Bits(S - 1 bits)) init 0
  val rxLeft    = Reg(Bits(g.width bits))
  val frameSeen = Reg(Bool()) init False    // byla juz prawdziwa granica ramki
  val leftValid = Reg(Bool()) init False    // rxLeft pochodzi z pelnego lewego slotu
  val rxValid   = Reg(Bool()) init False
  val rxData    = Reg(I2sFrame(g))

  val full = rxSh ## io.pins.sdi                          // S bitow, MSB pierwszy
  val word = full(S - 1 downto S - g.width)

  when(frameStart) { frameSeen := True }

  rxValid := False
  when(rise) {
    rxSh := full(S - 2 downto 0)
    when(bitCnt === 0) {
      when(ws) {                       // zaczal sie prawy slot: lewe slowo gotowe
        rxLeft    := word
        leftValid := frameSeen         // slot wstepny po resecie sie nie liczy
      } otherwise {                    // zaczela sie ramka: prawe slowo gotowe
        when(leftValid) {
          rxValid      := True
          rxData.left  := rxLeft
          rxData.right := word
        }
        leftValid := False
      }
    }
  }

  io.rx.valid   := rxValid
  io.rx.payload := rxData

  io.pins.sck := sck
  io.pins.ws  := ws
  io.pins.sdo := sdo
}
