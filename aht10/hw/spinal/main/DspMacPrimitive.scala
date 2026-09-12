package newhope.aht10

import spinal.core._

// =====================================================================
//  Prymityw DSP48A1 (Spartan-6).
//
//  BlackBox, czyli SpinalHDL emituje samo wywolanie modulu - cialo
//  dostarcza biblioteka unisim przy syntezie. To jest ta jawna
//  instancjacja, o ktora chodzilo: nie prosimy XST-a, zeby domyslil sie
//  z `a * b + c`, tylko mowimy wprost, ktory slice i w jakiej
//  konfiguracji.
//
//  Cena: tego modulu nie zasymulujesz bez modeli unisim. Stad podzial
//  na DspMacBehavioral i DspMacPrimitive.
//
//  ------------------------------------------------------------------
//  UWAGA: BCIN I B_INPUT TU NIE ISTNIEJA
//
//  Architektura slice'a ma dedykowane wejscie kaskadowe BCIN i atrybut
//  B_INPUT, ale KOMPONENT UNISIM ICH NIE WYSTAWIA (UG389, tabela
//  portow). Kaskade robi sie, podlaczajac BCOUT sasiedniego slice'a do
//  zwyklego portu B - mapper sam przeklada to na BCIN i ustawia
//  B_INPUT za nas.
//
//  Liste portow do instancjacji bierz z szablonu w UG389, nie z tabeli
//  sygnalow architektury. Te dwie listy sie roznia, a ISE zglasza to
//  dopiero przy syntezie:
//     Module <DSP48A1> does not have a port named <BCIN>.
//     Module DSP48A1 does not have a parameter named B_INPUT
//
//  PCIN zostaje - ten prymityw ma i UG389 kaze go zerowac, gdy
//  nieuzywany.
//  ------------------------------------------------------------------
// =====================================================================
class DSP48A1 extends BlackBox {

  // --- rejestry potoku -------------------------------------------------
  //  A i B maja po dwa opcjonalne stopnie (A0REG/A1REG). Bierzemy po
  //  jednym - drugi przydaje sie tylko przy kaskadowaniu.
  addGeneric("A0REG", 0)
  addGeneric("A1REG", 1)
  addGeneric("B0REG", 0)
  addGeneric("B1REG", 1)
  addGeneric("CREG",  1)
  addGeneric("DREG",  0)   // pre-sumator nieuzywany
  addGeneric("MREG",  1)
  addGeneric("PREG",  1)
  addGeneric("CARRYINREG",  0)
  addGeneric("CARRYOUTREG", 0)
  addGeneric("OPMODEREG",   1)

  // --- konfiguracja ----------------------------------------------------
  addGeneric("CARRYINSEL", "OPMODE5")
  addGeneric("RSTTYPE",    "SYNC")

  val io = new Bundle {
    val CLK = in Bool()

    val A       = in Bits (18 bits)
    val B       = in Bits (18 bits)
    val C       = in Bits (48 bits)
    val D       = in Bits (18 bits)
    val PCIN    = in Bits (48 bits)
    val CARRYIN = in Bool()
    val OPMODE  = in Bits (8 bits)

    val CEA, CEB, CEC, CED, CEM, CEP        = in Bool()
    val CECARRYIN, CEOPMODE                 = in Bool()
    val RSTA, RSTB, RSTC, RSTD, RSTM, RSTP  = in Bool()
    val RSTCARRYIN, RSTOPMODE               = in Bool()

    val P         = out Bits (48 bits)
    val M         = out Bits (36 bits)
    val PCOUT     = out Bits (48 bits)
    val BCOUT     = out Bits (18 bits)
    val CARRYOUT  = out Bool()
    val CARRYOUTF = out Bool()
  }

  noIoPrefix()
  mapCurrentClockDomain(clock = io.CLK)
}

// =====================================================================
//  Opakowanie prymitywu do umowy DspMacBase.
//
//  OPMODE = 0x0D:
//     bity [1:0] = 01  -> mux X bierze wynik mnozenia (M)
//     bity [3:2] = 11  -> mux Z bierze port C
//     bit  [4]   = 0   -> pre-sumator omijany, B idzie wprost
//     bit  [6]   = 0   -> post-sumator dodaje, nie odejmuje
//  czyli P = A*B + C. Jeden slice, zero LUT-ow na offset.
//
//  WYROWNANIE SCIEZKI C. Wynik mnozenia jest opozniony o dwa stopnie
//  (A1REG, potem MREG), a CREG daje tylko jeden. Bez dodatkowego
//  rejestru w fabric C wyprzedzalby M o takt. Przy zatrzasnietych
//  wejsciach nie mialoby to znaczenia, ale wtedy model behawioralny
//  i prymityw roznilyby sie zachowaniem przejsciowym - a caly sens
//  testu rownowaznosci polega na tym, ze sa nierozroznialne.
//
//  BCOUT, PCOUT, M, CARRYOUT i CARRYOUTF zostaja niepodlaczone. Sa
//  w prymitywie, nikt ich nie czyta, syntezator je usunie.
// =====================================================================
class DspMacPrimitive extends DspMacBase {
  def latency = 3

  val cAligned = RegNext(io.c) init (0)

  val dsp = new DSP48A1

  dsp.io.A       := io.a.asBits
  dsp.io.B       := io.b.asBits
  dsp.io.C       := cAligned.asBits
  dsp.io.D       := B(0, 18 bits)
  dsp.io.PCIN    := B(0, 48 bits)
  dsp.io.CARRYIN := False
  dsp.io.OPMODE  := B"8'x0D"

  dsp.io.CEA       := True
  dsp.io.CEB       := True
  dsp.io.CEC       := True
  dsp.io.CED       := True
  dsp.io.CEM       := True
  dsp.io.CEP       := True
  dsp.io.CECARRYIN := True
  dsp.io.CEOPMODE  := True

  dsp.io.RSTA       := False
  dsp.io.RSTB       := False
  dsp.io.RSTC       := False
  dsp.io.RSTD       := False
  dsp.io.RSTM       := False
  dsp.io.RSTP       := False
  dsp.io.RSTCARRYIN := False
  dsp.io.RSTOPMODE  := False

  io.p := dsp.io.P.asSInt
}
