package newhope.i2c

import spinal.core._
import spinal.lib._
import spinal.lib.io.{ReadableOpenDrain, InOutWrapper}

// ---------------------------------------------------------------------
//  Parametry elaboracji. Osobno od konfiguracji runtime - tu wszystko
//  jest policzone przez Scale zanim powstanie jakikolwiek sprzet.
// ---------------------------------------------------------------------
case class I2cGenerics(clkFrequency  : HertzNumber,
                       sclFrequency  : HertzNumber = 100 kHz,
                       filterWindow  : Int         = 4) {

  // Okres SCL dzielimy na CWIARTKI, nie polowki: SDA zmienia sie
  // w srodku niskiego stanu SCL, a probkujemy w srodku wysokiego.
  def quarterCycles : Int      = (clkFrequency / sclFrequency / 4).toInt
  def quarterWidth  : BitCount = log2Up(quarterCycles + 1) bits

  assert(quarterCycles >= 2, "Zegar systemowy za wolny wzgledem SCL")
}

// ---------------------------------------------------------------------
//  Piny. Open-drain: nigdy nie wystawiamy aktywnej jedynki, tylko
//  puszczamy linie i podciaga ja rezystor. write=True znaczy "puszczam".
// ---------------------------------------------------------------------
case class I2cPins() extends Bundle with IMasterSlave {
  val scl = ReadableOpenDrain(Bool())
  val sda = ReadableOpenDrain(Bool())

  override def asMaster(): Unit = master(scl, sda)
}

// ---------------------------------------------------------------------
//  Filtr wejsciowy: synchronizacja (metastabilnosc!) + odrzucanie
//  glitchy. Zmieniamy stan dopiero gdy cale okno jest zgodne.
// ---------------------------------------------------------------------
class I2cInputFilter(input : Bool, windowSize : Int) extends Area {
  val synced = BufferCC(input, init = True)

  val window = Reg(Bits(windowSize bits)) init((BigInt(1) << windowSize) - 1)
  window := window(windowSize - 2 downto 0) ## synced

  val value = RegInit(True)
  when(window.andR)  { value := True  }
  when(!window.orR)  { value := False }
}

// =====================================================================
//  WARSTWA 1 - PHY
// =====================================================================
object I2cPhyCmdMode extends SpinalEnum {
  val START, BIT, STOP = newElement()
}

case class I2cPhyCmd() extends Bundle {
  val mode = I2cPhyCmdMode()
  // dla BIT: poziom do wystawienia na SDA.
  // Odczyt = wystawienie True (puszczenie linii) i zobaczenie co tam jest.
  val data = Bool()
}

case class I2cPhyRsp() extends Bundle {
  val data = Bool() // poziom faktycznie odczytany z SDA
}

case class I2cPhyIo() extends Bundle {
  val pins = master(I2cPins())
  val cmd  = slave  Stream (I2cPhyCmd())
  val rsp  = master Flow   (I2cPhyRsp())
}

// taki trick żeby obie wersje I2cPhy miały wspólny typ bazowy
// to można klasę z testami tym sparametryzować
abstract class I2cPhyBase(g : I2cGenerics) extends Component {
  val io = I2cPhyIo()
  /* INFO: 
  ** bez przypisania I2cInputFilter do val - jest problem z nazewnictwem sygnałów w wygenerowanym verilog'u
  ** nazwy są losowe zz czy jakoś tak
  ** do tego jak są nieużwane to zostaną sprunowane (a jak mają sensowne nazwy to pozostają nawet nieużywane)
  val filter = new Area {
    val scl = new I2cInputFilter(io.pins.scl.read, g.filterWindow).value
    val sda = new I2cInputFilter(io.pins.sda.read, g.filterWindow).value
  }*/
  val filter = new Area {
    val sclFilter = new I2cInputFilter(io.pins.scl.read, g.filterWindow)
    val sdaFilter = new I2cInputFilter(io.pins.sda.read, g.filterWindow)
    val scl = sclFilter.value
    val sda = sdaFilter.value
  }
  // Odliczanie jednej cwiartki okresu SCL.
  val timer = new Area {
    val counter = Reg(UInt(g.quarterWidth)) init(0)
    val done    = counter === 0
    when(!done) { counter := counter - 1 }
    def restart() : Unit = counter := U(g.quarterCycles - 1)
  }
  val sclReg = RegInit(True)
  val sdaReg = RegInit(True)
  io.pins.scl.write := sclReg
  io.pins.sda.write := sdaReg

  io.cmd.ready := False
  io.rsp.valid := False
  io.rsp.data  := filter.sda

  // Clock stretching, teraz jedna linijka zamiast waitSclHigh() w kazdym
  // stanie: puscilismy SCL, a magistrala nadal niska -> slave trzyma.
  val stretching = sclReg && !filter.scl
}
