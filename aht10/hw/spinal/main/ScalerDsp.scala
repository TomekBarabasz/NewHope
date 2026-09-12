package newhope.aht10

import spinal.core._
import spinal.lib._

// =====================================================================
//  Tryb skalowania. Wybiera pare stalych, nic wiecej - to jest cala
//  pointa tego etapu: trzy zupelnie rozne wielkosci fizyczne maja te
//  sama postac afiniczna, wiec mieszcza sie w jednym slice.
// =====================================================================
object ScalerMode extends SpinalEnum {
  val celsius, fahrenheit, humidity = newElement()
}

case class ScalerCmd() extends Bundle {
  val raw  = UInt(20 bits)
  val mode = ScalerMode()
}

object ScalerDsp {

  /** Ile bitow obciac z surowej wartosci przed mnozeniem.
    *
    * TO JEST NAJWAZNIEJSZA STALA W CALYM PROJEKCIE. Mnozarka DSP48A1
    * jest 18x18 ZE ZNAKIEM, czyli port A miesci najwyzej 131071.
    * Surowa wartosc ma 20 bitow, wiec `raw >> 2` (do 262143) po cichu
    * przekreci sie na wartosc ujemna dla polowy zakresu - a objawi sie
    * to jako "powyzej 50 stopni czujnik pokazuje mroz".
    *
    * `raw >> 3` daje najwyzej 131071, czyli dokladnie granice. Kosztuje
    * 3 najmlodsze bity: 0.0019 dziesiatych stopnia na bit, razem ponizej
    * 0.02 dziesiatej stopnia. Dokladnosc czujnika to +-0.3 stopnia.
    */
  val preShift = 3
  val shift    = 20 - preShift   // 17: tyle odejmujemy po mnozeniu

  case class Coef(b : Int, offset : Int, label : String)

  //  Wzory z datasheetu AHT10:
  //     T[C]  = S/2^20 * 200 - 50
  //     T[F]  = T[C] * 9/5 + 32  =  S/2^20 * 360 - 58
  //     RH[%] = S/2^20 * 100
  //  Wszystkie razy 10, zeby miec jedna cyfre po przecinku bez
  //  arytmetyki ulamkowej.
  val celsius    = Coef(2000, -500, "C")
  val fahrenheit = Coef(3600, -580, "F")
  val humidity   = Coef(1000,    0, "RH")

  /** Stala na port C: przesuniety offset plus zaokraglenie.
    *
    * Czlon (1 << (shift-1)) to zaokraglenie do najblizszej. Bez niego
    * przesuniecie arytmetyczne w prawo daje podloge, wiec blad idzie
    * ZAWSZE w dol - przy ujemnych temperaturach oznacza to systematyczne
    * zanizanie, a nie symetryczny szum.
    *
    * Post-sumator DSP48A1 dodaje C do wyniku mnozenia w tym samym
    * slice. Offset -50 stopni nie kosztuje ani jednego LUT-a - to jest
    * powod, dla ktorego robi sie to jawnie zamiast zostawiac XST-owi.
    */
  def cValue(coef : Coef) : BigInt =
    (BigInt(coef.offset) << shift) + (BigInt(1) << (shift - 1))

  /** Model bit-dokladny. Referencja dla testow i dla czytelnika. */
  def reference(raw : Int, coef : Coef) : Int = {
    val a = raw >> preShift
    val p = BigInt(a) * coef.b + cValue(coef)
    (p >> shift).toInt          // BigInt >> jest arytmetyczne (podloga)
  }

  /** Zakres wyjscia: od -580 (0 stopni surowego w Fahrenheitach) do
    * 3020 (pelna skala). Miesci sie w 13 bitach ze znakiem. */
  val resultWidth = 13
}

// =====================================================================
//  Wspolna baza dla obu implementacji mnozenia z akumulacja.
//
//  Ten sam zabieg co przy I2cPhyBase: jedna umowa, dwie realizacje.
//  Model behawioralny idzie do symulacji, prymityw do syntezy. Bez
//  tego rozdzialu albo nie zasymulujesz nic (BlackBox), albo nie masz
//  pewnosci, co XST zrobil z twoim mnozeniem.
// =====================================================================
abstract class DspMacBase extends Component {
  val io = new Bundle {
    val a = in  SInt (18 bits)
    val b = in  SInt (18 bits)
    val c = in  SInt (48 bits)
    val p = out SInt (48 bits)
  }
  def latency : Int
}

// ---------------------------------------------------------------------
//  Model behawioralny.
//
//  Potokowanie celowo odwzorowuje prymityw stopien po stopniu, zeby
//  latencja i wyrownanie sciezki C zgadzaly sie bez zastanawiania.
//  Gdyby to bylo jedno kombinacyjne wyrazenie, test rownowaznosci nie
//  mialby sensu, a integracja rozjechalaby sie o dwa takty.
// ---------------------------------------------------------------------
class DspMacBehavioral extends DspMacBase {
  def latency = 3

  val aReg = RegNext(io.a) init (0)          // odpowiednik A1REG
  val bReg = RegNext(io.b) init (0)          // odpowiednik B1REG
  val cReg = RegNext(io.c) init (0)          // odpowiednik CREG

  val mReg  = RegNext(aReg * bReg) init (0)  // odpowiednik MREG
  val cReg2 = RegNext(cReg) init (0)         // wyrownanie C do M

  val pReg = RegNext(mReg.resize(48) + cReg2) init (0)   // PREG
  io.p := pReg
}

// =====================================================================
//  Skaler.
//
//  Wejscia MAC-a sa zatrzasniete na czas calej konwersji, wiec nawet
//  gdyby wyrownanie potoku w prymitywie rozjechalo sie o takt, wynik
//  bylby ten sam. To nie jest zaslanianie problemu - przy jednym
//  pomiarze na dwie sekundy przepustowosc nie ma znaczenia, a
//  odpornosc na szczegoly konfiguracji rejestrow DSP-a ma.
// =====================================================================
case class ScalerDsp(buildMac : () => DspMacBase = () => new DspMacBehavioral)
    extends Component {

  val io = new Bundle {
    val cmd = slave  Stream (ScalerCmd())
    val rsp = master Flow (SInt(ScalerDsp.resultWidth bits))
  }

  val mac = buildMac()

  val rawReg  = Reg(UInt(20 bits)) init (0)
  val modeReg = Reg(ScalerMode()) init (ScalerMode.celsius)
  val busy    = RegInit(False)
  val counter = Reg(UInt(log2Up(mac.latency + 1) bits)) init (0)

  io.cmd.ready := !busy

  when(io.cmd.fire) {
    rawReg  := io.cmd.raw
    modeReg := io.cmd.mode
    counter := 0
    busy    := True
  }
  when(busy) {
    counter := counter + 1
    when(counter === mac.latency - 1) { busy := False }
  }

  // --- stale zalezne od trybu -----------------------------------------
  val bSel = SInt(18 bits)
  val cSel = SInt(48 bits)
  switch(modeReg) {
    is(ScalerMode.celsius) {
      bSel := S(ScalerDsp.celsius.b, 18 bits)
      cSel := S(ScalerDsp.cValue(ScalerDsp.celsius), 48 bits)
    }
    is(ScalerMode.fahrenheit) {
      bSel := S(ScalerDsp.fahrenheit.b, 18 bits)
      cSel := S(ScalerDsp.cValue(ScalerDsp.fahrenheit), 48 bits)
    }
    default {
      bSel := S(ScalerDsp.humidity.b, 18 bits)
      cSel := S(ScalerDsp.cValue(ScalerDsp.humidity), 48 bits)
    }
  }

  // resize PRZED asSInt, nie po. `(raw >> 3).asSInt` reinterpretuje
  // 17-bitowa wartosc bez znaku jako ze znakiem - wszystko powyzej
  // 65535 staje sie ujemne. Zero ostrzezen, polowa zakresu do smieci.
  mac.io.a := (rawReg >> ScalerDsp.preShift).resize(18).asSInt
  mac.io.b := bSel
  mac.io.c := cSel

  io.rsp.valid   := RegNext(busy && counter === mac.latency - 1) init (False)
  io.rsp.payload := (mac.io.p >> ScalerDsp.shift).resize(ScalerDsp.resultWidth)
}
