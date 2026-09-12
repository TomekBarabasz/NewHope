package newhope.i2c

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.io.InOutWrapper

// =====================================================================
//  WARSTWA 2 - MASTER
//
//  PHY mowi bitami, master bajtami: 8 bitow MSB-first plus dziewiaty
//  bit ACK. Jedna komenda io.cmd == jeden bajt na magistrali (albo
//  jeden warunek START/STOP).
//
//  KONTRAKT WZGLEDEM PHY (patrz komentarz przy sBits) - payload
//  phy.io.cmd MUSI byc staly przez cala komende. I2cPhyTable czyta
//  io.cmd.data KOMBINACYJNIE w kazdej cwiartce (Drive.FromCmd), wiec
//  zmiana danych w trakcie bitu przenosi sie natychmiast na SDA.
//  I2cPhyFsm probkuje dane tylko raz, w sBitSetup, i taki blad
//  toleruje - dlatego master napisany "pod FSM" przechodzi testy,
//  a po podmianie PHY na tablicowe generuje falszywe START/STOP.
// =====================================================================

object I2cCmdMode extends SpinalEnum {
  val START, STOP, WRITE, READ = newElement()
}

case class I2cCmd() extends Bundle {
  val mode = I2cCmdMode()
  val data = Bits(8 bits)   // WRITE: bajt do wyslania (dla READ nieistotny)
  val ack  = Bool()         // READ: True = odeslij ACK po bajcie, False = NACK
}

case class I2cRsp() extends Bundle {
  /** READ: odebrany bajt.
    * WRITE: to, co bylo FAKTYCZNIE widziane na SDA w trakcie wysylania
    *        (readback) - rowne wyslanemu bajtowi, dopoki nikt nie
    *        przeszkadza. Roznica == kolizja / przegrany arbitraz. */
  val data = Bits(8 bits)
  /** True = ACK. Na magistrali ACK to NISKI poziom SDA w 9. bicie,
    * inwersja jest tutaj, zeby wyzej nie trzeba bylo o tym pamietac. */
  val ack  = Bool()
}

case class I2cMasterIo() extends Bundle {
  val pins = master(I2cPins())
  val cmd  = slave  Stream (I2cCmd())
  val rsp  = master Flow   (I2cRsp())
}

// ---------------------------------------------------------------------
//  buildPhy zamiast I2cPhy(g) na sztywno - ten sam mechanizm, ktory
//  I2cPhyTestplan uzywa do przepuszczenia jednego planu przez dwie
//  implementacje. Dzieki temu I2cMasterTestplan tez jest jeden.
//
//  Przy generacji obu wariantow w JEDNYM przebiegu trzeba rozroznic
//  nazwy modulow (setDefinitionName) - patrz App-y na dole.
// ---------------------------------------------------------------------
case class I2cMaster(g        : I2cGenerics,
                     buildPhy : I2cGenerics => I2cPhyBase =
                       (gg : I2cGenerics) => I2cPhyTable(gg)) extends Component {

  val io = I2cMasterIo()

  val phy = buildPhy(g)
  io.pins <> phy.io.pins

  // -------------------------------------------------------------------
  //  Sterowanie PHY idzie przez WLASNY strumien mastera, a nie wprost
  //  do phy.io.cmd. Same przewody, zero logiki, dwa powody:
  //
  //   1. te sygnaly sa w zasiegu I2cMaster, wiec Instrument.stream /
  //      simPublic dzialaja na nich bez czytania portow WEJSCIOWYCH
  //      komponentu-dziecka,
  //   2. to wlasnie na tym porcie obowiazuje kontrakt stabilnosci
  //      payloadu, ktory lamala pierwsza wersja sBits - teraz da sie
  //      go sprawdzic assercja (phy_cmd_payload_stable), a nie tylko
  //      przez objaw na pinach.
  // -------------------------------------------------------------------
  val phyCmd = Stream(I2cPhyCmd())
  phy.io.cmd << phyCmd

  phyCmd.valid := False
  phyCmd.mode  := I2cPhyCmdMode.BIT
  phyCmd.data  := True              // domyslnie linia puszczona

  val shifter    = Reg(Bits(8 bits)) init(0)
  val bitCounter = Reg(UInt(3 bits)) init(0)
  val isRead     = Reg(Bool())       init(False)

  /** Bit odczytany w Q2 biezacej komendy. Przesuwamy go do `shifter`
    * dopiero na cmd.ready, dlatego trzeba go przechowac osobno - patrz
    * sBits. */
  val rxBit   = Reg(Bool()) init(True)
  /** Poziom SDA zmierzony w 9. bicie, nieodwrocony (nisko == ACK). */
  val ackLvl  = Reg(Bool()) init(True)

  io.cmd.ready := False
  io.rsp.valid := False
  io.rsp.data  := shifter
  io.rsp.ack   := !ackLvl

  val fsm = new StateMachine {

    val sIdle = new State with EntryPoint
    val sStart, sStop, sBits, sAck = new State

    sIdle.whenIsActive {
      when(io.cmd.valid) {
        bitCounter := 0
        shifter    := io.cmd.data
        isRead     := io.cmd.mode === I2cCmdMode.READ
        switch(io.cmd.mode) {
          is(I2cCmdMode.START) { goto(sStart) }
          is(I2cCmdMode.STOP)  { goto(sStop)  }
          default              { goto(sBits)  }   // WRITE i READ
        }
      }
    }

    // Ta sama komenda PHY obsluguje START i RESTART - warunek "z zimna"
    // i powtorzony start rozni sie tylko stanem wejsciowym magistrali,
    // a tym zajmuje sie tablica cwiartek / sStartReleaseSda.
    sStart.whenIsActive {
      phyCmd.valid := True
      phyCmd.mode  := I2cPhyCmdMode.START
      when(phyCmd.ready) { io.cmd.ready := True; goto(sIdle) }
    }

    sStop.whenIsActive {
      phyCmd.valid := True
      phyCmd.mode  := I2cPhyCmdMode.STOP
      when(phyCmd.ready) { io.cmd.ready := True; goto(sIdle) }
    }

    // -----------------------------------------------------------------
    //  8 bitow MSB-first. Przy odczycie puszczamy SDA (True) i tylko
    //  probkujemy; ten sam rejestr przesuwny obsluguje oba kierunki -
    //  MSB wychodzi, odczytany bit wchodzi na LSB.
    //
    //  Przesuniecie NA cmd.ready, nie na rsp.valid: rsp.valid wypada
    //  w Q2, czyli w SRODKU WYSOKIEGO SCL. Przesuniecie tam zmienia
    //  shifter.msb, a wiec i phyCmd.data, a wiec (w wersji
    //  tablicowej) SDA przy wysokim SCL - czyli generuje warunek
    //  START/STOP w srodku bajtu. Rejestr `rxBit` istnieje wylacznie
    //  po to, zeby przesuniecie moglo poczekac do konca komendy.
    // -----------------------------------------------------------------
    sBits.whenIsActive {
      phyCmd.valid := True
      phyCmd.mode  := I2cPhyCmdMode.BIT
      phyCmd.data  := isRead ? True | shifter.msb

      when(phy.io.rsp.valid) { rxBit := phy.io.rsp.data }
      when(phyCmd.ready) {
        shifter    := shifter(6 downto 0) ## rxBit
        bitCounter := bitCounter + 1
        when(bitCounter === 7) { goto(sAck) }
      }
    }

    // -----------------------------------------------------------------
    //  Dziewiaty bit. Przy zapisie puszczamy SDA i czytamy ACK slave'a,
    //  przy odczycie sami wystawiamy ACK (SDA nisko) albo NACK.
    //
    //  io.cmd.ack jest tu nadal wazne, bo io.cmd.ready podnosi sie
    //  dopiero na koncu tego stanu - kontrakt Stream'a gwarantuje
    //  stabilnosc payloadu do handshake'u.
    //
    //  W sAck NIE przesuwamy shiftera: io.rsp.data ma pokazac osiem
    //  bitow bajtu, nie bajt przesuniety o bit ACK.
    // -----------------------------------------------------------------
    sAck.whenIsActive {
      phyCmd.valid := True
      phyCmd.mode  := I2cPhyCmdMode.BIT
      phyCmd.data  := isRead ? !io.cmd.ack | True

      when(phy.io.rsp.valid) { ackLvl := phy.io.rsp.data }
      when(phyCmd.ready) {
        io.rsp.valid := True
        io.cmd.ready := True
        goto(sIdle)
      }
    }
  }
}

// ---------------------------------------------------------------------
//  Skrot do testow i do instancjonowania - zeby nie powtarzac lambdy.
// ---------------------------------------------------------------------
object I2cMasterBuild {
  val fsm   : I2cGenerics => I2cPhyBase = (g : I2cGenerics) => I2cPhyFsm(g)
  val table : I2cGenerics => I2cPhyBase = (g : I2cGenerics) => I2cPhyTable(g)
}

// =====================================================================
//  Generacja RTL. definitionName ustawiany recznie, bo oba warianty to
//  ta sama klasa Scali - bez tego dwa rozne moduly nazywaja sie
//  I2cMaster i drugi nadpisuje pierwszy.
// =====================================================================
object I2cMasterVerilog extends App {
  val cfg = SpinalConfig(
    targetDirectory             = "hw/gen/verilog",
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    anonymSignalUniqueness      = true
  )
  val g = I2cGenerics(clkFrequency = 100 MHz)

  cfg.generateVerilog(InOutWrapper(
    I2cMaster(g, I2cMasterBuild.table).setDefinitionName("I2cMasterTable")))
  cfg.generateVerilog(InOutWrapper(
    I2cMaster(g, I2cMasterBuild.fsm).setDefinitionName("I2cMasterFsm")))
}
