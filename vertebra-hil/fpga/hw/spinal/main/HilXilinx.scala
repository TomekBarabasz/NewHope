package newhope.vertebra.hil

import spinal.core._

// =====================================================================
//  Prymitywy Spartan-6 uzywane przez top-levele na plytke. Tylko do
//  generowania Verilogu dla ISE - symulacja testuje harness bez nich.
// =====================================================================

/** DCM_CLKGEN (UG382). M/D z generykow to wartosc po konfiguracji;
  * PROGCLK/PROGDATA/PROGEN zmieniaja je w biegu (DcmProgrammer). */
case class DcmClkGen(m : Int, d : Int, clkinPeriodNs : Double) extends BlackBox {
  require(m >= 2 && m <= 256 && d >= 1 && d <= 256, s"DCM_CLKGEN M/D $m/$d poza zakresem")
  setDefinitionName("DCM_CLKGEN")
  addGeneric("CLKFX_MULTIPLY", m)
  addGeneric("CLKFX_DIVIDE", d)
  addGeneric("CLKIN_PERIOD", clkinPeriodNs)
  addGeneric("SPREAD_SPECTRUM", "NONE")
  addGeneric("STARTUP_WAIT", "FALSE")

  val io = new Bundle {
    val CLKIN     = in  Bool()
    val RST       = in  Bool()
    val FREEZEDCM = in  Bool()
    val PROGCLK   = in  Bool()
    val PROGDATA  = in  Bool()
    val PROGEN    = in  Bool()
    val CLKFX     = out Bool()
    val CLKFX180  = out Bool()
    val CLKFXDV   = out Bool()
    val LOCKED    = out Bool()
    val PROGDONE  = out Bool()
    val STATUS    = out Bits(2 bits)
  }
  noIoPrefix()
}

object DcmClkGen {
  /** Najblizsze M/D dla fout z fin (oba w Hz); zwraca (M, D, fout). */
  def best(finHz : Long, foutHz : Long) : (Int, Int, Double) = {
    val c = for (m <- 2 to 256; d <- 1 to 256) yield (m, d, finHz.toDouble * m / d)
    c.minBy { case (_, _, f) => scala.math.abs(f - foutHz) }
  }
}

/** Programowanie M/D DCM_CLKGEN w biegu (UG382, "Dynamic Frequency
  * Synthesis"). Wszystko synchronicznie z PROGCLK (u nas zegar sys):
  *   LoadD: PROGEN = 1 przez 10 cykli, PROGDATA = 1, 0, potem D-1 (8 bitow, LSB pierwszy)
  *   Gap cykli PROGEN = 0
  *   LoadM: PROGEN = 1 przez 10 cykli, PROGDATA = 1, 1, potem M-1
  *   Gap cykli PROGEN = 0
  *   GO:    PROGEN = 1 przez 1 cykl, PROGDATA = 0
  * PROGDONE wraca do 1, gdy nowe M/D dziala; LOCKED opada i wraca.
  * Ta sama tabela jest wzorcem dla RTL (DcmProgrammer) i dla modelu DCM
  * w symulacji harnessu, ktory ja dekoduje niezaleznie. Zgodnosc z krzemem
  * sprawdza pomiar zegara dut (HilFreqMeter) po kazdym programowaniu. */
object DcmProg {
  val Gap = 2
  /** (PROGEN, PROGDATA) cykl po cyklu. */
  def sequence(m : Int, d : Int) : Seq[(Boolean, Boolean)] = {
    require(valid(m, d), s"M/D $m/$d")
    def load(isM : Boolean, v : Int) =
      Seq((true, true), (true, isM)) ++ (0 until 8).map(i => (true, (((v - 1) >> i) & 1) == 1))
    val gap = Seq.fill(Gap)((false, false))
    load(isM = false, d) ++ gap ++ load(isM = true, m) ++ gap :+ ((true, false))
  }
  val Steps : Int = sequence(2, 1).size
  def valid(m : Int, d : Int) : Boolean = m >= 2 && m <= 256 && d >= 1 && d <= 256

  /** M w bitach 8:0, D w bitach 24:16 (rejestr dcm_md). */
  def encode(m : Int, d : Int) : Long = m.toLong | (d.toLong << 16)
  def decode(v : Long) : (Int, Int) = ((v & 0x1FF).toInt, ((v >> 16) & 0x1FF).toInt)
}

/** Automat DcmProg.sequence: `go` z M/D zatrzasnietymi w tym cyklu, potem
  * czeka na PROGDONE (najwyzej `timeoutCycles`). Wyjscia rejestrowane. */
case class DcmProgrammer(timeoutCycles : Int = 1 << 20) extends Component {
  val io = new Bundle {
    val go       = in  Bool()
    val m, d     = in  UInt(9 bits)
    val progEn   = out Bool()
    val progData = out Bool()
    val progDone = in  Bool()
    val busy     = out Bool()
    val timeout  = out Bool()          // sticky do nastepnego go
  }
  val n = DcmProg.Steps
  val ref = DcmProg.sequence(2, 1)     // PROGEN nie zalezy od M/D

  val mL = Reg(UInt(9 bits)) init 2
  val dL = Reg(UInt(9 bits)) init 1
  val dBits = (dL - 1).resize(8).asBits
  val mBits = (mL - 1).resize(8).asBits
  // PROGDATA krok po kroku: 1,0,D-1 | gap | 1,1,M-1 | gap | GO(0)
  val mStart = 10 + DcmProg.Gap
  val data = Vec((0 until n).map {
    case 0                                 => True
    case i if i >= 2 && i < 10             => dBits(i - 2)
    case i if i == mStart || i == mStart + 1 => True
    case i if i >= mStart + 2 && i < mStart + 10 => mBits(i - mStart - 2)
    case _                                 => False
  })
  // Stale kroki (bez bitow M/D) musza zgadzac sie z tabela DcmProg.sequence.
  private val fixed = Set(0, 1, mStart, mStart + 1) ++ (10 until mStart) ++ (mStart + 10 until n)
  for (i <- fixed) require(ref(i)._2 == (i == 0 || i == mStart || i == mStart + 1), s"DcmProgrammer: krok $i")
  val en = Vec(ref.map(x => Bool(x._1)))

  val idx      = Reg(UInt(log2Up(n) bits)) init 0
  val sending  = RegInit(False)
  val waiting  = RegInit(False)
  val wcnt     = Reg(UInt(log2Up(timeoutCycles + 1) bits)) init 0
  val timedOut = RegInit(False)
  val done     = RegNext(io.progDone) init False

  when(io.go && !sending && !waiting) {
    sending := True; idx := 0; timedOut := False
    mL := io.m; dL := io.d
  }
  when(sending) {
    idx := idx + 1
    when(idx === n - 1) { sending := False; waiting := True; wcnt := 0 }
  }
  when(waiting) {
    wcnt := wcnt + 1
    when(wcnt >= 4 && done) { waiting := False }
    when(wcnt === timeoutCycles) { waiting := False; timedOut := True }
  }
  io.progEn   := RegNext(sending && en(idx)) init False
  io.progData := RegNext(sending && data(idx)) init False
  io.busy     := sending || waiting
  io.timeout  := timedOut
}

case class Bufg() extends BlackBox {
  setDefinitionName("BUFG")
  val io = new Bundle { val I = in Bool(); val O = out Bool() }
  noIoPrefix()
}
