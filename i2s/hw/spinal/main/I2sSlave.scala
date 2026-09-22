package newhope.i2s

import spinal.core._
import spinal.lib._

// =====================================================================
//  I2S SLAVE, format Philips, stereo, TX + RX.
//
//  Piny: slave(I2sPins()), czyli linie nazwane od strony MASTERA:
//    sck, ws  - wejscia
//    sdo      - WEJSCIE: dane master -> slave (jak MOSI)
//    sdi      - WYJSCIE: dane slave -> master (jak MISO)
//  Dzieki temu master i slave lacza sie wprost: m.io.pins <> s.io.pins.
//
//  Kontrakt:
//   - SCK/WS/SD sa nadprobkowane (I2sSlaveGenerics: ograniczenia),
//   - granica slotu to zmiana WS widziana na narastajacym SCK (r0),
//     granica ramki to WS 1 -> 0; przed pierwsza granica po resecie
//     slave nic nie odbiera i nadaje zera,
//   - dlugosc slotu jest DOWOLNA: slowo nadawane MSB-first, dalej zera;
//     krotszy slot obcina LSB. Odbior bierze pierwsze `width` bitow,
//     brakujace uzupelnia zerami (specyfikacja: odbiornik i nadajnik
//     nie musza znac nawzajem swoich dlugosci slowa),
//   - io.tx: bufor na jedna ramke, zatrzasniecie na granicy ramki,
//     pusty bufor = cisza + impuls underrun (jak w I2sMaster),
//   - io.rx: jedna ramka na okres, na r0 nastepnej ramki; nigdy dla
//     ramki czesciowej.
//
//  SCK nie musi byc ciagly: slave jest sterowany zboczami, nie ma
//  timeoutu, wiec zatrzymany SCK to po prostu dluzsza polowa okresu.
// =====================================================================
case class I2sSlave(g : I2sSlaveGenerics) extends Component {
  require(g.isLegal, s"nielegalne generyki: $g")
  private val w = g.width

  val io = new Bundle {
    val tx       = slave(Stream(I2sFrame(w)))
    val rx       = master(Flow(I2sFrame(w)))
    val underrun = out Bool()
    val pins     = slave(I2sPins())
  }

  // -------------------------------------------------------------------
  //  Synchronizatory. Wszystkie trzy linie przez te sama liczbe stopni,
  //  zeby probki SCK, WS i SD w danym cyklu pochodzily z tej samej chwili.
  // -------------------------------------------------------------------
  private def sync(b : Bool, init : Bool) : Bool =
    (0 until g.syncStages).foldLeft(b)((x, _) => RegNext(x) init(init))

  val sckS = sync(io.pins.sck, False)
  val wsS  = sync(io.pins.ws,  True)
  val sdS  = sync(io.pins.sdo, False)

  // Probka poprzednia. Na narastajacym zboczu to ostatnia probka z NISKIM
  // SCK - z niej bierzemy WS i SD (hold 0, patrz I2sSlaveGenerics).
  val sckP = RegNext(sckS) init False
  val wsP  = RegNext(wsS)  init True
  val sdP  = RegNext(sdS)  init False

  val rise = sckS && !sckP
  val fall = !sckS && sckP

  val wsLast  = Reg(Bool()) init True       // WS z poprzedniego narastajacego
  val wsKnown = Reg(Bool()) init False      // byl juz jakikolwiek narastajacy
  val slotStart  = rise && wsKnown && (wsP =/= wsLast)   // r0 nowego slotu
  val frameStart = slotStart && !wsP                     // r0 lewego slotu
  when(rise) { wsLast := wsP; wsKnown := True }

  // -------------------------------------------------------------------
  //  TX. Ladowanie na r0: master na r0 probkuje jeszcze LSB poprzedniego
  //  slowa (wystawiony na f0), a MSB nowego idzie dopiero na f1. Stad
  //  opoznienie o bit bez licznika i dowolna dlugosc slotu: po `width`
  //  przesunieciach wychodza zera.
  // -------------------------------------------------------------------
  val buf      = Reg(I2sFrame(w))
  val bufValid = Reg(Bool()) init False
  val cur      = Reg(I2sFrame(w))
  val txSh     = Reg(Bits(w bits)) init 0
  val sdiOut   = Reg(Bool()) init False

  val nextFrame = Mux(bufValid, buf, buf.getZero)

  // Kolejnosc jak w I2sMaster: fire po frameStart.
  when(frameStart) { cur := nextFrame; bufValid := False }
  io.tx.ready := !bufValid && !ClockDomain.current.isResetActive
  when(io.tx.fire) { buf := io.tx.payload; bufValid := True }
  io.underrun := frameStart && !bufValid

  // rise i fall nigdy w tym samym cyklu, wiec przesuniecie i ladowanie
  // sie nie gryza.
  when(fall) { sdiOut := txSh.msb; txSh := txSh |<< 1 }
  when(frameStart)     { txSh := nextFrame.left }
    .elsewhen(slotStart) { txSh := cur.right }

  // -------------------------------------------------------------------
  //  RX. Bit z r_j (j >= 1) to pozycja j-1 biezacego slowa; bit z r0 to
  //  OSTATNIA pozycja poprzedniego. Slowo buduje sie od zera, bit trafia
  //  na pozycje width-1-rxCnt, wiec krotki slot zostawia zera na LSB, a
  //  dlugi jest obcinany przez nasycenie rxCnt.
  // -------------------------------------------------------------------
  private val cntW = log2Up(w + 1)
  val rxSh  = Reg(Bits(w bits)) init 0
  val rxCnt = Reg(UInt(cntW bits)) init 0
  val pos   = (U(w - 1, cntW bits) - rxCnt).resize(log2Up(w) bits)

  val withBit = Bits(w bits)
  withBit := rxSh
  when(rxCnt < w) { withBit(pos) := sdP }

  val rxLeft    = Reg(Bits(w bits))
  val frameSeen = Reg(Bool()) init False
  val leftValid = Reg(Bool()) init False
  val rxValid   = Reg(Bool()) init False
  val rxData    = Reg(I2sFrame(w))

  when(frameStart) { frameSeen := True }

  rxValid := False
  when(rise) {
    when(slotStart) {
      rxSh := 0; rxCnt := 0
      when(!wsLast) {                    // skonczylo sie lewe slowo
        rxLeft    := withBit
        leftValid := frameSeen
      } otherwise {                      // skonczylo sie prawe: ramka
        when(leftValid) {
          rxValid      := True
          rxData.left  := rxLeft
          rxData.right := withBit
        }
        leftValid := False
      }
    } otherwise {
      rxSh := withBit
      when(rxCnt < w) { rxCnt := rxCnt + 1 }
    }
  }

  io.rx.valid   := rxValid
  io.rx.payload := rxData
  io.pins.sdi   := sdiOut
}
