package newhope.mcb

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/**
 * Master portu MCB: zapisuje region, odczytuje go i porownuje, w petli.
 *
 * Jeden silnik obsluguje dwa rozne zadania, roznica siedzi w parametrach:
 *
 *  BENCHMARK (ciagly region, dlugie bursty):
 *    burstLen = 32, burstCount = 1024, stride = 128, sweepColumns = false
 *    -> 128 kB ciagle, mierzy przepustowosc
 *
 *  REGRESJA (pokrycie bankow i wierszy, test odswiezania):
 *    burstLen = 1, burstCount = 4096, stride = 2048, sweepColumns = true,
 *    holdCycles = 5000000
 *    -> to samo, co przechodzilo wczesniej na sprzecie
 *
 * @param burstLen     slow na komende, potega dwojki, 1..64 (BL = burstLen - 1)
 * @param burstCount   ile burstow na przebieg, potega dwojki
 * @param stride       odstep w bajtach miedzy kolejnymi burstami, potega dwojki
 * @param sweepColumns dodaje do adresu czesc niska, zeby ruszyc bity kolumny;
 *                     tylko dla burstLen = 1, inaczej bursty by sie nalozyly
 * @param holdCycles   przerwa miedzy faza zapisu i odczytu, w taktach.
 *                     0 wylacza. Przy 25 MHz 5_000_000 to 200 ms, czyli
 *                     ~25x tREFI - jesli dane przezyja, odswiezanie dziala.
 * @param injectFault  KONTROLA NEGATYWNA: psuje wzorzec oczekiwany, wiec error
 *                     MUSI sie zapalic. Zbuduj raz z true i sprawdz.
 */
case class MigBurstEngine(
    c            : MigConfig,
    burstLen     : Int,
    burstCount   : Int,
    stride       : Int,
    sweepColumns : Boolean = false,
    holdCycles   : Int     = 0,
    injectFault  : Boolean = false,
    // --- przelaczniki diagnostyczne ---
    /** Ile burstow moze byc jednoczesnie zamowionych na odczyt.
      * 1 = zero potokowania, czyli zachowanie starego testu. */
    maxOutstanding : Int     = 0,     // 0 = maksimum, jakie zmiesci FIFO
    /** true = nie wypychaj danych przed komende; scisle naprzemiennie,
      * czyli zachowanie starego testu. */
    strictWrite    : Boolean = false,
    /** true = zatrzymaj sie po pierwszym przebiegu, nie zapetlaj. */
    singlePass     : Boolean = false,
    /** Ile bitow indeksu pierwszego bledu wyprowadzic. */
    errIdxWidth    : Int     = 5
) extends Component {

  private def isPow2(x: Int) = x > 0 && (x & (x - 1)) == 0

  require(isPow2(burstLen) && burstLen <= c.maxBurst,
    s"burstLen = $burstLen musi byc potega dwojki nie wieksza niz ${c.maxBurst}")
  require(isPow2(burstCount), s"burstCount = $burstCount musi byc potega dwojki")
  require(isPow2(stride), s"stride = $stride musi byc potega dwojki")
  require(stride >= burstLen * c.bytesPerWord,
    s"stride = $stride za maly - bursty po ${burstLen * c.bytesPerWord} B by sie nalozyly")
  require(burstLen * c.bytesPerWord <= c.rowBytes,
    s"burst po ${burstLen * c.bytesPerWord} B przekracza wiersz ${c.rowBytes} B")
  require(!sweepColumns || burstLen == 1,
    "sweepColumns wymaga burstLen = 1")
  require(stride.toLong * burstCount <= c.memBytes,
    s"region ${stride.toLong * burstCount} B nie miesci sie w ${c.memBytes} B pamieci")

  val io = new Bundle {
    val port  = master(MigPort(c))
    val done  = out Bool()          // pierwszy przebieg zakonczony
    val error = out Bool()          // dataError || mcbFault (zgodnosc z UCF)
    val dataError = out Bool()      // NIEZGODNOSC DANYCH
    val mcbFault  = out Bool()      // flaga bledu z samego MCB
    val errIdx    = out UInt (errIdxWidth bits)  // indeks pierwszej niezgodnosci
    val fault     = in  Bits (4 bits)            // rozdzielone flagi MCB
    val faultCode = out UInt (3 bits)            // 1..4, ktora flaga zawiodla
    val errBcd    = out Vec(UInt(4 bits), 3)     // indeks bledu DZIESIETNIE
    val passPulse = out Bool()                   // impuls na kazdy przebieg
    val phase = out Bits (2 bits)   // 00 kalibracja, 01 zapis, 10 przerwa, 11 odczyt
    val pass  = out Bool()          // PRZELACZA sie po kazdym pelnym przebiegu
    val calib = in  Bool()
  }

  private val logBL    = log2Up(burstLen)
  private val logBytes = log2Up(c.bytesPerWord)
  private val logStr   = log2Up(stride)

  val totalWords = burstLen * burstCount
  // Dla potegi dwojki log2Up(N+1) = log2(N)+1, wiec wordW = burstW + logBL.
  private val wordW  = log2Up(totalWords + 1)
  private val burstW = log2Up(burstCount + 1)

  require(burstW + logStr <= c.addrWidth,
    s"adres potrzebuje ${burstW + logStr} bitow, port ma ${c.addrWidth}")

  /**
   * Gorna granica slow zamowionych a nieodebranych.
   *
   * Ograniczona do totalWords, bo inFlight fizycznie nie moze byc wiekszy,
   * a licznik ma tylko wordW bitow. Bez tego ograniczenia przy malym regionie
   * prog nie mieszcilby sie w szerokosci operandu i porownanie byloby
   * zawsze prawdziwe - SpinalHDL zglasza to jako OUT OF RANGE CONSTANT.
   */
  private val outstandingCap = {
    val byFifo = c.fifoDepth - burstLen
    val byUser = if (maxOutstanding <= 0) byFifo else (maxOutstanding - 1) * burstLen
    scala.math.min(scala.math.min(byFifo, byUser), totalWords)
  }
  require(outstandingCap >= 0)

  val pushIdx  = Reg(UInt(wordW bits)) init 0    // slowa wypchniete do write FIFO
  val wrCmdIdx = Reg(UInt(burstW bits)) init 0   // komendy WRITE wystawione
  val rdCmdIdx = Reg(UInt(burstW bits)) init 0   // komendy READ wystawione
  val rdIdx    = Reg(UInt(wordW bits)) init 0    // slowa odebrane i sprawdzone
  val holdCnt  = Reg(UInt(log2Up(holdCycles + 2) bits)) init 0

  val done      = Reg(Bool()) init False
  val dataError = Reg(Bool()) init False
  val mcbFault  = Reg(Bool()) init False
  val faultCode = Reg(UInt(3 bits)) init 0
  val errIdx    = Reg(UInt(errIdxWidth bits)) init 0
  val pass  = Reg(Bool()) init False
  val phase = Reg(Bits(2 bits)) init 0

  /**
   * Wzorzec uzywajacy wszystkich bitow slowa: {i, ~i} powtorzone na kazde
   * 32 bity, kazda czesc z inna stala. Przy totalWords > 65536 wzorzec sie
   * powtarza, ale nadal wykrywa przekrecone i zamienione slowa.
   */
  def pattern(i: UInt): Bits = {
    val base = i.resize(16).asBits
    val w32  = base ## ~base
    (0 until c.dataWidth / 32)
      .map(k => w32 ^ B(k * 0x01010101, 32 bits))
      .reduce(_ ## _)
  }

  /** Adres bajtowy burstu k. */
  def burstAddr(k: UInt): UInt = {
    val high = (k << logStr).resize(c.addrWidth)
    if (!sweepColumns) high
    else {
      // czesc niska mieszka w bitach [logStr-1 : logBytes], wiec nie koliduje
      val low = (k.resize(logStr - logBytes) << logBytes).resize(c.addrWidth)
      high | low
    }
  }

  // ---------------------------------------------------------------- wartosci domyslne
  io.port.cmd.valid := False
  io.port.cmd.instr := MigInstr.WRITE
  io.port.cmd.bl    := burstLen - 1
  io.port.cmd.addr  := 0
  io.port.wr.valid  := False
  io.port.wr.data   := pattern(pushIdx)
  io.port.wr.mask   := B(0, c.maskWidth bits)   // 0 = zapisz wszystkie bajty
  io.port.rd.ready  := False

  // Sygnaly dla dziesietnego licznika indeksu odczytu. Zerowane na wejsciu
  // w faze odczytu, zamrazane przy pierwszej niezgodnosci - wtedy wartosc
  // licznika JEST indeksem bledu.
  val rdBeat    = Bool()
  val rdClear   = Bool()
  val passPulse = Bool()
  rdBeat    := False
  rdClear   := False
  passPulse := False

  val fsm = new StateMachine {
    val sCalib = new State with EntryPoint
    val sWrite = new State
    val sHold  = new State
    val sRead  = new State
    val sStop  = new State

    sCalib.whenIsActive {
      phase := B"00"
      when(io.calib) { goto(sWrite) }
    }

    // Dane ida do FIFO z wyprzedzeniem, komenda WRITE czeka, az caly jej burst
    // bedzie w kolejce. Inaczej MCB zglosilby wr_underrun.
    sWrite.whenIsActive {
      phase := B"01"

      val burstLimit = ((wrCmdIdx + 1) << logBL)
      io.port.wr.valid := (pushIdx < totalWords) &&
                          (if (strictWrite) pushIdx < burstLimit else True)
      when(io.port.wr.fire) { pushIdx := pushIdx + 1 }

      val burstBuffered = pushIdx >= burstLimit
      io.port.cmd.valid := (wrCmdIdx < burstCount) && burstBuffered
      io.port.cmd.instr := MigInstr.WRITE
      io.port.cmd.addr  := burstAddr(wrCmdIdx)
      when(io.port.cmd.fire) { wrCmdIdx := wrCmdIdx + 1 }

      when(wrCmdIdx === burstCount) {
        holdCnt := 0
        goto(sHold)
      }
    }

    sHold.whenIsActive {
      phase := B"10"
      holdCnt := holdCnt + 1
      when(holdCnt >= holdCycles) {
        rdCmdIdx := 0
        rdIdx    := 0
        rdClear  := True
        goto(sRead)
      }
    }

    // Odczyty zamawiane z wyprzedzeniem, ale nie wiecej, niz zmiesci sie
    // w 64-slowowym read FIFO - inaczej rd_overflow.
    sRead.whenIsActive {
      phase := B"11"

      val ordered  = (rdCmdIdx << logBL).resize(wordW)
      val inFlight = ordered - rdIdx
      io.port.cmd.valid := (rdCmdIdx < burstCount) &&
                           (inFlight <= outstandingCap)
      io.port.cmd.instr := MigInstr.READ
      io.port.cmd.addr  := burstAddr(rdCmdIdx)
      when(io.port.cmd.fire) { rdCmdIdx := rdCmdIdx + 1 }

      io.port.rd.ready := True
      when(io.port.rd.fire) {
        val expected = if (injectFault) pattern(rdIdx) ^ B(1, c.dataWidth bits)
                       else             pattern(rdIdx)
        when(io.port.rd.payload =/= expected) {
          when(!dataError) { errIdx := rdIdx.resize(errIdxWidth) }
          dataError := True
        } otherwise {
          rdBeat := !dataError
        }
        rdIdx := rdIdx + 1
      }

      when(rdIdx === totalWords) {
        pushIdx  := 0
        wrCmdIdx := 0
        done := True
        pass := !pass
        passPulse := True
        if (singlePass) goto(sStop) else goto(sWrite)
      }
    }

    // Tryb singlePass: zatrzymujemy sie tu na zawsze, zeby pozniejsze przebiegi
    // nie mogly zamaskowac ani dorzucic bledu.
    sStop.whenIsActive {
      phase := B"10"
    }
  }

  // Priorytet: pozniejsze przypisanie wygrywa, wiec wr_underrun jest najwyzej.
  when(!mcbFault && io.fault.orR) {
    mcbFault  := True
    faultCode := 4
    when(io.fault(1)) { faultCode := 3 }
    when(io.fault(2)) { faultCode := 2 }
    when(io.fault(3)) { faultCode := 1 }
  }

  val errBcd = Bcd.counter(3, inc = rdBeat, clear = rdClear)

  io.done      := done
  io.dataError := dataError
  io.mcbFault  := mcbFault
  io.errIdx    := errIdx
  io.error     := dataError || mcbFault
  io.faultCode := faultCode
  io.errBcd    := errBcd
  io.passPulse := passPulse
  io.phase     := phase
  io.pass      := pass
}
