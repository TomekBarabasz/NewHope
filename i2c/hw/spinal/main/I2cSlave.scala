package newhope.i2c

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.io.InOutWrapper

// =====================================================================
//  WARSTWA 2 - SLAVE (sprzet, nie model symulacyjny)
//
//  DLACZEGO TU NIE MA PHY. Master ma PHY, bo master GENERUJE czas:
//  tabela cwiartek rozdziela okres SCL na Q0..Q3 i caly timing siedzi
//  tam. Slave czasu nie generuje, tylko go ODBIERA - jego jedyny zegar
//  to SCL mastera. Warstwa "cwiartkowa" nie ma tu czego opisywac,
//  zostaja dwa zbocza:
//
//    zbocze NARASTAJACE SCL - bit jest wazny, probkujemy SDA
//    zbocze OPADAJACE  SCL - wolno zmienic SDA, wystawiamy nasz bit
//
//  To dokladnie ta sama reguła, na ktorej stoi I2cSlaveModel (kolejka
//  slotow, slot wchodzi na opadajacym zboczu). Tamten model jest
//  referencja behawioralna dla tego komponentu.
//
//              bit N              |            bit N+1
//   SCL  __/~~~~~~~~~~~~\_________|__/~~~~~~~~~~~~\_________
//          ^ probkuj    ^ wystaw nastepny bit
//
//  CLOCK STRETCHING. Slave nie zawsze zdazy z decyzja: po odebranym
//  bajcie warstwa wyzej musi powiedziec ACK/NACK, przed wysylanym
//  bajtem musi ten bajt dostarczyc. W obu miejscach sciagamy SCL do
//  masy i magistrala czeka. Master to obsluguje (patrz `stretching`
//  w I2cPhyBase), wiec para master-slave z tego projektu dogaduje sie
//  bez zadnych zalozen o predkosci warstwy 3.
//
//  Punkty rozciagania zegara - zawsze na zboczu OPADAJACYM, czyli
//  w srodku niskiego stanu SCL:
//    * opadajace 8. bitu przy zapisie  -> czekamy na io.rx.ready
//    * opadajace 9. bitu przy odczycie -> czekamy na io.tx.valid
//
//  CZEGO TEN KOMPONENT NIE ROBI: nie zna zadnego protokolu powyzej
//  bajtu (rejestrow, komend, mapy pamieci). To warstwa 3, osobny
//  komponent - tak samo jak Aht10Ctrl jest warstwa 3 nad I2cMaster.
// =====================================================================

case class I2cSlaveIo() extends Bundle {
  val pins = master(I2cPins())

  /** Adres 7-bitowy. Wejscie, a nie generyk: da sie go zmienic
    * w runtime (piny adresowe, rejestr konfiguracyjny), a elaboracja
    * nic by na stalej nie zyskala - to i tak jeden komparator. */
  val address = in Bits (7 bits)

  /** Impulsy jednotaktowe. `start` obejmuje START i RESTART - dla
    * slave'a to to samo zdarzenie, roznica jest tylko w tym, co bylo
    * przed. */
  val start = out Bool ()
  val stop  = out Bool ()

  /** Adres trafil - impuls na opadajacym zboczu 8. bitu, czyli o caly
    * bit PRZED tym, zanim pierwszy bajt odczytu bedzie potrzebny.
    * Payload: True = master bedzie czytal. */
  val select = master Flow (Bool())

  /** Bajt odebrany od mastera. Dopoki nie ma handshake'u, SCL jest
    * przytrzymany - warstwa wyzej ma tyle czasu, ile potrzebuje.
    * io.rxAck jest probkowane W TAKCIE handshake'u: True = ACK
    * (sciagamy SDA), False = NACK (puszczamy linie i wychodzimy
    * z transakcji). */
  val rx    = master Stream (Bits(8 bits))
  val rxAck = in Bool ()

  /** Bajt do wyslania. Slave zglasza zapotrzebowanie przez podniesienie
    * tx.ready i rownoczesne rozciagniecie zegara. UWAGA: jesli warstwa
    * wyzej nigdy nie da valid, magistrala stoi w nieskonczonosc - to
    * jest swiadomy wybor (brak cichego wysylania smieci), a nie brak
    * timeoutu przez przeoczenie. */
  val tx = slave Stream (Bits(8 bits))

  /** Po kazdym WYSLANYM bajcie: True = master potwierdzil i chce
    * nastepny, False = NACK, czyli koniec odczytu. */
  val txAck = master Flow (Bool())
}

case class I2cSlave(g : I2cGenerics) extends Component {

  // -------------------------------------------------------------------
  //  BUDZET CZASOWY. Slave ma go OSTRZEJSZY NIZ MASTER i to jest jego
  //  glowne ograniczenie projektowe.
  //
  //  Master czas GENERUJE, wiec opoznienie wlasnego filtra tylko
  //  wydluza mu cwiartke - falszywy stretching kompensuje wszystko
  //  (patrz I2cGenerics.filterTracksScl). Slave czasu nie generuje:
  //  SDA wolno mu ruszyc WYLACZNIE przy niskim SCL, a niski SCL trwa
  //  2q i nikt go dla slave'a nie wydluzy. Jego reakcja to
  //  filterLatency (zanim zobaczy zbocze) + 1 (takt na sdaReg):
  //
  //      L + 1 < 2q
  //
  //  ZLAMANIE TEGO WARUNKU NIE PSUJE DANYCH i dlatego jest podstepne:
  //  master odczyta ACK poprawnie, bo jego wlasny filtr opoznia
  //  probkowanie o te same L cykli. Psuje PROTOKOL - SDA opada przy
  //  wysokim SCL, czyli w srodku bajtu powstaje warunek START. Zobaczy
  //  to dopiero trzecia strona z wlasnym filtrem: w I2cSlaveTestplan
  //  zrobil to I2cMonitor, dekodujac bit ACK jako Start.
  //
  //  Ten warunek jest silniejszy od budzetu na probkowanie (L <= 3q,
  //  z kompensacja przez falszywy stretching), wiec tamten jest w nim
  //  zawarty i nie ma go tu osobno.
  //
  //  PRAKTYCZNIE: okno filtra musi byc znaczaco krotsze od polowki
  //  SCL. Przy 1 MHz (Fm+, najszybszy tryb w standardzie) i zegarze
  //  100 MHz jest q = 25, wiec okno 4 ma zapas rzedu wielkosci.
  //  Konfiguracja qmin z testplanow mastera (8 MHz SCL) tego warunku
  //  NIE spelnia i nie ma po co - takiej magistrali nie ma, ona sluzy
  //  do meczenia licznikow cwiartek mastera.
  //
  //  Gdyby kiedys trzeba bylo zejsc nizej: osobny, krotszy filtr
  //  wylacznie do detekcji zbocza SCL. Kosztem odpornosci na glitche
  //  i za cene testu, ktory te glitche wstrzykuje.
  // -------------------------------------------------------------------
  require(g.filterLatency + 2 <= 2 * g.quarterCycles,
    s"Filtr za wolny wzgledem SCL dla slave'a: reakcja ${g.filterLatency + 1} cykli, " +
    s"niski stan SCL ${2 * g.quarterCycles} cykli. Zmniejsz filterWindow albo sclFrequency.")

  val io = I2cSlaveIo()

  // --- front: filtr, zbocza, warunki START/STOP -----------------------
  // Ten sam filtr co w I2cPhyBase i z tego samego powodu (metastabilnosc
  // + glitche). Przypisany do val-a, zeby nazwy w Verilogu byly ludzkie -
  // patrz komentarz przy I2cPhyBase.filter.
  val filter = new Area {
    val sclFilter = new I2cInputFilter(io.pins.scl.read, g.filterWindow)
    val sdaFilter = new I2cInputFilter(io.pins.sda.read, g.filterWindow)
    val scl = sclFilter.value
    val sda = sdaFilter.value
  }

  val edge = new Area {
    val sclPrev = RegNext(filter.scl) init (True)
    val sdaPrev = RegNext(filter.sda) init (True)

    val rise = filter.scl && !sclPrev
    val fall = !filter.scl && sclPrev

    // Zmiana SDA przy STABILNIE wysokim SCL - stad `scl && sclPrev`,
    // a nie samo `scl`: inaczej zbocze narastajace SCL zlozone z opoznieniem
    // filtra SDA udawaloby START.
    val start = filter.scl && sclPrev && sdaPrev && !filter.sda
    val stop  = filter.scl && sclPrev && !sdaPrev && filter.sda
  }

  // --- wyjscia open-drain ---------------------------------------------
  // SCL sterujemy WYLACZNIE na potrzeby stretchingu; poza tym linia jest
  // puszczona i nalezy do mastera.
  val sdaReg  = RegInit(True)
  val stretch = RegInit(False)
  io.pins.sda.write := sdaReg
  io.pins.scl.write := !stretch

  // --- stan bajtowy ----------------------------------------------------
  /** Rejestr przesuwny wspolny dla obu kierunkow: przy odbiorze bity
    * wchodza na LSB, przy nadawaniu wychodza z MSB. */
  val shifter = Reg(Bits(8 bits)) init (0)
  /** Liczba zboczy narastajacych w biezacym bajcie, 0..8. Cztery bity,
    * a nie trzy jak u mastera - potrzebna jest jawna wartosc 8, a nie
    * przekrecenie licznika. */
  val bitCounter = Reg(UInt(4 bits)) init (0)
  val isRead     = Reg(Bool()) init (False)
  val masterAck  = Reg(Bool()) init (False)
  val rxAcked    = Reg(Bool()) init (False)

  def shiftIn() : Unit = {
    shifter    := shifter(6 downto 0) ## filter.sda
    bitCounter := bitCounter + 1
  }

  io.start          := edge.start
  io.stop           := edge.stop
  io.select.valid   := False
  io.select.payload := isRead
  io.rx.valid       := False
  io.rx.payload     := shifter
  io.tx.ready       := False
  io.txAck.valid    := False
  io.txAck.payload  := masterAck

  val fsm = new StateMachine {

    val sIdle = new State with EntryPoint
    val sAddr, sAddrAck                = new State
    val sRx, sRxDecide, sRxAck         = new State
    val sTxLoad, sTx, sTxAck           = new State
    val sIgnore                        = new State

    // -----------------------------------------------------------------
    //  START / STOP moga przyjsc w dowolnym momencie i kasuja stan.
    //  Kolizji z przypisaniami stanow nie ma: warunek START/STOP wymaga
    //  WYSOKIEGO SCL, a stany ruszaja sie na zboczach i w trakcie
    //  stretchingu (SCL nisko) - te zbiory sa rozlaczne, wiec nie ma
    //  znaczenia, ktore przypisanie wygrywa.
    // -----------------------------------------------------------------
    always {
      when(edge.start) {
        shifter    := 0
        bitCounter := 0
        sdaReg     := True
        stretch    := False
        goto(sAddr)
      }
      when(edge.stop) {
        sdaReg  := True
        stretch := False
        goto(sIdle)
      }
    }

    // sIdle: magistrala jalowa albo cudza transakcja. Czekamy na START,
    // ktory obsluguje blok always - stad pusty stan.

    // --- bajt adresowy -----------------------------------------------
    sAddr.whenIsActive {
      when(edge.rise) { shiftIn() }

      // Opadajace 8. bitu: mamy komplet, decydujemy i OD RAZU wystawiamy
      // ACK - na to warstwa wyzej nie ma wplywu, wiec nie ma po co
      // rozciagac zegara.
      when(edge.fall && bitCounter === 8) {
        bitCounter := 0
        when(shifter(7 downto 1) === io.address) {
          isRead            := shifter(0)
          io.select.valid   := True
          io.select.payload := shifter(0)   // isRead jeszcze nie przepisany
          sdaReg            := False        // ACK == linia nisko
          goto(sAddrAck)
        } otherwise {
          goto(sIgnore)                     // nie do nas
        }
      }
    }

    sAddrAck.whenIsActive {
      when(edge.fall) {
        when(isRead) {
          // SDA zostaje nisko az do zaladowania bajtu - i tak nikt tego
          // nie zobaczy, bo sTxLoad natychmiast przytrzymuje SCL.
          goto(sTxLoad)
        } otherwise {
          sdaReg := True                    // teraz pisze master
          goto(sRx)
        }
      }
    }

    // --- master pisze -------------------------------------------------
    sRx.whenIsActive {
      when(edge.rise) { shiftIn() }
      when(edge.fall && bitCounter === 8) {
        bitCounter := 0
        goto(sRxDecide)
      }
    }

    // Bajt gotowy, SCL przytrzymany, pytamy warstwe wyzej o ACK.
    // valid nie zalezy od ready - kontrakt Stream'a zachowany.
    sRxDecide.whenIsActive {
      stretch     := True
      io.rx.valid := True
      when(io.rx.ready) {
        sdaReg  := !io.rxAck
        rxAcked := io.rxAck
        goto(sRxAck)
      }
    }

    // Stretch zdejmujemy dopiero TUTAJ, czyli takt po ustawieniu SDA.
    // Dwa powody, drugi wazniejszy:
    //   1. setup time wychodzi z rachunku, a nie z zalozenia, ze
    //      "przypisania w tym samym takcie jakos sie ulozą",
    //   2. gdyby SCL puscic w tym samym takcie, NASZ WLASNY filtr
    //      zobaczylby zmiane SDA i zmiane SCL w tym samym cyklu - a
    //      opadajace SDA przy wysokim SCL to warunek START. Z przesunieciem
    //      o takt kolejnosc jest gwarantowana: najpierw widzimy swoje SDA
    //      (przy SCL nadal niskim), dopiero potem SCL w gore.
    sRxAck.whenIsActive {
      stretch := False
      when(edge.fall) {
        sdaReg := True
        when(rxAcked) { goto(sRx) } otherwise { goto(sIgnore) }
      }
    }

    // --- master czyta -------------------------------------------------
    sTxLoad.whenIsActive {
      stretch     := True
      io.tx.ready := True                   // ready bez valid = brak transferu
      when(io.tx.valid) {
        shifter    := io.tx.payload
        sdaReg     := io.tx.payload.msb     // MSB-first
        bitCounter := 0
        goto(sTx)
      }
    }

    sTx.whenIsActive {
      stretch := False
      when(edge.rise) { bitCounter := bitCounter + 1 }
      when(edge.fall) {
        when(bitCounter === 8) {
          sdaReg := True                    // 9. bit nalezy do mastera
          goto(sTxAck)
        } otherwise {
          shifter := shifter(6 downto 0) ## False
          sdaReg  := shifter(6)             // kolejny bit, juz po przesunieciu
        }
      }
    }

    sTxAck.whenIsActive {
      when(edge.rise) {
        masterAck        := !filter.sda     // ACK == master sciagnal linie
        io.txAck.valid   := True
        io.txAck.payload := !filter.sda
        bitCounter       := 0
      }
      when(edge.fall) {
        // ACK -> master chce dalej, wiec od razu po nastepny bajt.
        // NACK -> koniec ramki, czekamy na STOP albo RESTART.
        when(masterAck) { goto(sTxLoad) } otherwise { goto(sIgnore) }
      }
    }

    // --- nie nasza transakcja / po NACK-u --------------------------------
    // Rece z magistrali. Wyjscie tylko przez START albo STOP.
    sIgnore.whenIsActive {
      sdaReg  := True
      stretch := False
    }
  }
}

// =====================================================================
//  Generacja RTL. InOutWrapper jak w PHY - bez niego ReadableOpenDrain
//  wychodzi jako osobne write/read i synteza nie widzi magistrali
//  dwukierunkowej.
// =====================================================================
object I2cSlaveVerilog extends App {
  SpinalConfig(
    targetDirectory             = "hw/gen/verilog",
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    anonymSignalUniqueness      = true
  ).generateVerilog(
    InOutWrapper(I2cSlave(I2cGenerics(clkFrequency = 100 MHz)))
  )
}

object I2cSlaveVhdl extends App {
  SpinalConfig(
    targetDirectory             = "hw/gen/vhdl",
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    anonymSignalUniqueness      = true
  ).generateVhdl(
    InOutWrapper(I2cSlave(I2cGenerics(clkFrequency = 100 MHz)))
  )
}
