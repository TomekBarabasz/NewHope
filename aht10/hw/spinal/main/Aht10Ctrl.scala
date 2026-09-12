package newhope.aht10

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import newhope.i2c._

// =====================================================================
//  Parametry czasowe.
//
//  tickCycles jako liczba cykli, nie TimeNumber - symulacja ustawia 4
//  i caly testplan idzie w sekundy zamiast w minutach. Gdyby to bylo
//  wyliczane z czestotliwosci zegara, kazdy test czekalby 75 ms
//  wirtualnego czasu na jeden pomiar.
// =====================================================================
case class Aht10Generics(tickCycles    : Int = 100000,  // 1 ms @ 100 MHz
                         powerUpTicks  : Int = 20,      // datasheet: do 20 ms
                         measureTicks  : Int = 80,      // datasheet: >= 75 ms
                         resetTicks    : Int = 20,      // po 0xBA
                         watchdogTicks : Int = 200) {
  require(tickCycles >= 2, "tickCycles musi byc co najmniej 2")
  def maxTicks : Int = Seq(powerUpTicks, measureTicks, resetTicks).max
}

case class Aht10Sample() extends Bundle {
  val rawT  = UInt(20 bits)
  val rawRh = UInt(20 bits)
}

case class Aht10Status() extends Bundle {
  val ready      = Bool()      // po inicjalizacji, czeka na trigger
  val busy       = Bool()      // transakcja albo odliczanie w toku
  val error      = Bool()      // brak ACK, watchdog albo uporczywe busy
  val calibrated = Bool()      // bit CAL z ostatniej ramki statusu
  val state      = Bits(4 bits)
}

// =====================================================================
//  Program sekwencera.
//
//  Zamiast stanu FSM na kazdy bajt (bylo by ich ponad dwadziescia) -
//  ROM komend i licznik. Kazda faza to ciagly przedzial w ROM-ie,
//  a jedyna logika to "wystaw komende, poczekaj na fire, przesun sie".
//
//  Slowo: [10:9] tryb, [8] ack, [7:0] dane.
// =====================================================================
object Aht10Program {

  val START = 0
  val STOP  = 1
  val WRITE = 2
  val READ  = 3

  private def w(data : Int)     = (WRITE << 9) | data
  private def r(ack : Boolean)  = (READ  << 9) | (if (ack) 1 << 8 else 0)
  private val start             = START << 9
  private val stop              = STOP  << 9

  val addrWrite = 0x70   // 0x38 << 1
  val addrRead  = 0x71

  val words : Seq[Int] = Seq(
    // --- INIT: 0..5 ---------------------------------------------------
    start, w(addrWrite), w(0xE1), w(0x08), w(0x00), stop,
    // --- TRIGGER: 6..11 -----------------------------------------------
    start, w(addrWrite), w(0xAC), w(0x33), w(0x00), stop,
    // --- READ: 12..20 -------------------------------------------------
    //  Piec bajtow z ACK, szosty z NACK. Bez NACK-a na ostatnim czujnik
    //  nie puszcza SDA i STOP nie wychodzi poprawnie.
    start, w(addrRead),
    r(true), r(true), r(true), r(true), r(true), r(false),
    stop,
    // --- SOFT RESET: 21..24 -------------------------------------------
    start, w(addrWrite), w(0xBA), stop)

  val initFirst  = 0;  val initLast  = 5
  val trigFirst  = 6;  val trigLast  = 11
  val readFirst  = 12; val readLast  = 20
  val resetFirst = 21; val resetLast = 24
}

// =====================================================================
//  Sekwencer.
//
//  Punkt [3] wymagan zostal wycofany, wiec trigger jest zwyklym
//  impulsem z zewnatrz - kontroler nie wie nic o przelacznikach ani
//  o tym, ktora wielkosc bedzie pokazana. Jedna transakcja zwraca oba
//  pomiary i oba wychodza w Aht10Sample.
//
//  Bit CAL czytamy z bajtu statusu przy KAZDYM pomiarze, wiec osobny
//  stan sCheckCal po inicjalizacji jest niepotrzebny - to odpowiedz na
//  pytanie otwarte nr 1 z dokumentu projektowego.
// =====================================================================
case class Aht10Ctrl(g        : I2cGenerics,
                     ag       : Aht10Generics = Aht10Generics(),
                     buildPhy : I2cGenerics => I2cPhyBase = I2cMasterBuild.table)
    extends Component {

  val io = new Bundle {
    val pins    = master(I2cPins())
    val trigger = in Bool()
    val sample  = master Flow (Aht10Sample())
    val status  = out(Aht10Status())
  }

  val bus = I2cMaster(g, buildPhy)
  io.pins <> bus.io.pins

  // -------------------------------------------------------------------
  //  Podstawa czasu
  // -------------------------------------------------------------------
  val tickCounter = Reg(UInt(log2Up(ag.tickCycles) bits)) init (0)
  val tick        = tickCounter === ag.tickCycles - 1
  tickCounter := tick ? U(0) | (tickCounter + 1)

  val delay = Reg(UInt(log2Up(ag.maxTicks + 1) bits)) init (0)
  when(tick && delay =/= 0) { delay := delay - 1 }
  val delayDone = delay === 0

  // -------------------------------------------------------------------
  //  Wykonawca sekwencji
  // -------------------------------------------------------------------
  val rom     = Vec(Aht10Program.words.map(v => B(v, 11 bits)))
  val pc      = Reg(UInt(log2Up(Aht10Program.words.length) bits)) init (0)
  val pcLast  = Reg(UInt(log2Up(Aht10Program.words.length) bits)) init (0)
  val running = RegInit(False)

  val word     = rom(pc)
  val modeCode = word(10 downto 9).asUInt
  val isWrite  = modeCode === Aht10Program.WRITE
  val isRead   = modeCode === Aht10Program.READ

  bus.io.cmd.valid := running
  bus.io.cmd.data  := word(7 downto 0)
  bus.io.cmd.ack   := word(8)
  switch(modeCode) {
    is(Aht10Program.START) { bus.io.cmd.mode := I2cCmdMode.START }
    is(Aht10Program.STOP)  { bus.io.cmd.mode := I2cCmdMode.STOP  }
    is(Aht10Program.WRITE) { bus.io.cmd.mode := I2cCmdMode.WRITE }
    default                { bus.io.cmd.mode := I2cCmdMode.READ  }
  }

  val seqDone = False
  when(bus.io.cmd.fire) {
    when(pc === pcLast) {
      running := False
      seqDone := True
    } otherwise {
      pc := pc + 1
    }
  }

  def startSeq(first : Int, last : Int) : Unit = {
    pc      := first
    pcLast  := last
    running := True
  }

  // -------------------------------------------------------------------
  //  Odpowiedzi z magistrali.
  //
  //  I2cMaster podnosi rsp.valid TYLKO po WRITE i READ - przy START
  //  i STOP jest sam cmd.ready. Dlatego brak ACK sprawdzamy warunkowo,
  //  a nie na kazdym fire.
  // -------------------------------------------------------------------
  val rxBytes = Vec.fill(6)(Reg(Bits(8 bits)) init (0))
  val rxIdx   = Reg(UInt(3 bits)) init (0)
  val nakSeen = RegInit(False)

  when(bus.io.rsp.valid) {
    when(isWrite && !bus.io.rsp.ack) { nakSeen := True }
    when(isRead) {
      rxBytes(rxIdx) := bus.io.rsp.data
      rxIdx          := rxIdx + 1
    }
  }

  // -------------------------------------------------------------------
  //  Watchdog.
  //
  //  I2cMaster nie ma timeoutu (unimplemented("host_stretch_timeout")).
  //  Bez tego licznika zwarte SDA albo brak podciagniecia zawieszaja
  //  plytke bez zadnego objawu - wyswietlacz pokazuje ostatnia wartosc
  //  i wyglada na dzialajacy.
  //
  //  Liczymy tylko w trakcie sekwencji. Odliczanie w sWait jest
  //  legalnie dlugie i nie moze wywolywac alarmu.
  // -------------------------------------------------------------------
  val wdog = Reg(UInt(log2Up(ag.watchdogTicks + 1) bits)) init (0)
  when(!running || bus.io.cmd.fire) {
    wdog := 0
  } elsewhen (tick) {
    wdog := wdog + 1
  }
  val wdogFired = running && wdog === ag.watchdogTicks

  // -------------------------------------------------------------------
  val errorFlag = RegInit(False)
  val retried   = RegInit(False)

  io.sample.valid   := False
  io.sample.rawRh   := (rxBytes(1) ## rxBytes(2) ## rxBytes(3)(7 downto 4)).asUInt
  io.sample.rawT    := (rxBytes(3)(3 downto 0) ## rxBytes(4) ## rxBytes(5)).asUInt

  val fsm = new StateMachine {

    val sPowerUp = new State with EntryPoint
    val sInit, sIdle, sTrigger, sWait, sRead, sParse, sReset = new State

    /** Wspolna obsluga konca sekwencji: blad -> reset, sukces -> dalej. */
    def afterSeq(next : State) : Unit = {
      when(wdogFired) {
        running := False
        goto(sReset)
      }
      when(seqDone) {
        when(nakSeen) { goto(sReset) } otherwise { goto(next) }
      }
    }

    sPowerUp.onEntry { delay := ag.powerUpTicks }
    sPowerUp.whenIsActive {
      when(delayDone) {
        nakSeen := False
        startSeq(Aht10Program.initFirst, Aht10Program.initLast)
        goto(sInit)
      }
    }

    sInit.whenIsActive { afterSeq(sIdle) }

    sIdle.whenIsActive {
      when(io.trigger) {
        nakSeen := False
        retried := False
        startSeq(Aht10Program.trigFirst, Aht10Program.trigLast)
        goto(sTrigger)
      }
    }

    sTrigger.whenIsActive { afterSeq(sWait) }

    sWait.onEntry { delay := ag.measureTicks }
    sWait.whenIsActive {
      when(delayDone) {
        nakSeen := False
        rxIdx   := 0
        startSeq(Aht10Program.readFirst, Aht10Program.readLast)
        goto(sRead)
      }
    }

    sRead.whenIsActive { afterSeq(sParse) }

    // Bit 7 statusu to busy. Jedna ponowna proba, potem soft reset -
    // uporczywe busy oznacza, ze czujnik nie skonczy, a nie ze trzeba
    // czekac dluzej.
    sParse.whenIsActive {
      when(rxBytes(0)(7)) {
        when(retried) {
          goto(sReset)
        } otherwise {
          retried := True
          goto(sWait)
        }
      } otherwise {
        io.sample.valid := True
        errorFlag       := False
        goto(sIdle)
      }
    }

    // Watchdog dziala takze tutaj. Przerwanie sekwencji zostawia
    // magistrale bez STOP-a, ale nastepny START zwykle ja odzyskuje -
    // lepsze to niz petla resetu, ktora sama sie wiesza.
    sReset.onEntry {
      errorFlag := True
      nakSeen   := False
      startSeq(Aht10Program.resetFirst, Aht10Program.resetLast)
    }
    sReset.whenIsActive {
      when(wdogFired) {
        running := False
        goto(sPowerUp)
      }
      when(seqDone) { goto(sPowerUp) }
    }
  }

  io.status.ready      := fsm.isActive(fsm.sIdle)
  io.status.busy       := !fsm.isActive(fsm.sIdle)
  io.status.error      := errorFlag
  io.status.calibrated := rxBytes(0)(3)

  /* ciekawostka: to generuje elaboration errror
  ** A null pointer access has been detected in the JVM.
  ** This could happen when in your SpinalHDL description, you access an signal which is only defined further.
  ** io.status.state      := fsm.stateReg.asBits.resize(4)
  ** fsm.stateReg jeszcze tu nie istnieje
  ** StateMachine tworzy go dopiero podczas budowania maszyny — a to dzieje się na końcu elaboracji komponentu, 
  ** nie w chwili new StateMachine { ... }. Blok konstruktora tylko rejestruje stany i ich ciała.
  ** przypisania statusu stoją bezpośrednio po bloku FSM, czyli za wcześnie. isActive przechodzi, 
  ** bo zwraca sygnał tworzony leniwie, ale stateReg to zwykłe pole i w tym momencie jest jeszcze null.
  */
  
  // wersja poprawna
  // stateReg powstaje dopiero przy budowaniu maszyny, czyli po tym
  // bloku - stad odroczenie. isActive dziala od razu, bo zwraca sygnal
  // tworzony leniwie; stateReg to zwykle pole i tu jest jeszcze null.
  /*val stateBits = Bits(4 bits)
  io.status.state := stateBits
  Component.current.afterElaboration {
    stateBits := fsm.stateReg.asBits.resized
  }*/
  io.status.state := B(0, 4 bits)
  when(fsm.isActive(fsm.sPowerUp)) { io.status.state := 1 }
  when(fsm.isActive(fsm.sInit))    { io.status.state := 2 }
  when(fsm.isActive(fsm.sIdle))    { io.status.state := 3 }
  when(fsm.isActive(fsm.sTrigger)) { io.status.state := 4 }
  when(fsm.isActive(fsm.sWait))    { io.status.state := 5 }
  when(fsm.isActive(fsm.sRead))    { io.status.state := 6 }
  when(fsm.isActive(fsm.sParse))   { io.status.state := 7 }
  when(fsm.isActive(fsm.sReset))   { io.status.state := 8 }
}
