package newhope.vertebra.hil.i2s

import newhope.vertebra.hil._

// =====================================================================
//  Zegar dut w biegu (hw_clock_ratio_sweep): M/D DCM_CLKGEN z rejestru
//  dcm_md i pomiar z dut_freq (contract/i2s/commands.md, blok 0x100).
//
//  Programowanie tylko w stanie stop i nie szybciej niz M/D wariantu
//  (harness odrzuca, bo TS_dut jest liczony dla zegaru wariantu). Po
//  kazdej zmianie domena dut jest w resecie do LOCKED; konfiguracja
//  zatrzaskuje sie od nowa przy starcie, wiec wystarczy zwykly setup.
// =====================================================================
object I2sDcm {
  import I2sHilRegs.{DcmMd, DcmStatus, DutFreq, DcmBit, FreqWindowCycles}

  val ClkHz : Long = HilBridgeGenerics().clkHz

  /** M/D po konfiguracji (ten sam wybor co I2sHarness.dcm0). */
  def initial(v : I2sHilVariant) : (Int, Int) = initialCache.getOrElseUpdate(v, {
    val (m, d, _) = DcmClkGen.best(ClkHz, v.dutHz); (m, d)
  })
  private val initialCache = scala.collection.concurrent.TrieMap[I2sHilVariant, (Int, Int)]()

  def hz(m : Int, d : Int) : Double = ClkHz.toDouble * m / d

  /** Dozwolone w harnessie: zakres DCM i nie szybciej niz wariant. */
  def allowed(v : I2sHilVariant, m : Int, d : Int) : Boolean = {
    val (m0, d0) = initial(v)
    DcmProg.valid(m, d) && m.toLong * d0 <= m0.toLong * d
  }

  /** Najblizsze dozwolone M/D dla fHz. */
  def best(v : I2sHilVariant, fHz : Double) : (Int, Int) = {
    val (m0, d0) = initial(v)
    val c = for (m <- 2 to 256; d <- 1 to 256 if m.toLong * d0 <= m0.toLong * d) yield (m, d)
    c.minBy { case (m, d) => scala.math.abs(hz(m, d) - fHz) }
  }

  /** Punkty sweepu: geometrycznie od zegara wariantu w dol do `minHz`. */
  def sweep(v : I2sHilVariant, minHz : Double, points : Int) : Seq[(Int, Int)] = {
    val (m0, d0) = initial(v)
    val f0 = hz(m0, d0)
    val q  = scala.math.pow(minHz / f0, 1.0 / (points - 1))
    (0 until points).map(i => best(v, f0 * scala.math.pow(q, i))).distinct
  }

  /** Takt PLL ESP32-S3 (bez APLL): najgorszy polokres SCK jest o jeden
    * jego okres krotszy (jitter dzielnika ulamkowego, vertebra-hil.md §7). */
  val EspPllHz = 160e6

  /** Czy DUT slave nadazy za SCK ESP32 mastera przy zegarze dut `dutHz`
    * (I2sSlaveGenerics.supportsSckHalf dla najgorszego polokresu). */
  def slaveOk(c : I2sBenchCfg, dutHz : Double) : Boolean = {
    val half = dutHz / (2.0 * c.espFs * 2 * c.espSlot)
    c.v.slaveG.supportsSckHalf(BigDecimal(half - dutHz / EspPllHz))
  }

  /** Granica f*: polokres = txLatencyCycles. */
  def boundaryHz(c : I2sBenchCfg) : Double = {
    val bclk = c.espFs.toDouble * 2 * c.espSlot
    c.v.slaveG.txLatencyCycles / (1 / (2 * bclk) - 1 / EspPllHz)
  }

  /** Stan DCM z dcm_status. */
  case class Status(locked : Boolean, busy : Boolean, progDone : Boolean, rejected : Boolean, timeout : Boolean)

  def status(f : FpgaDevice[_]) : Status = {
    val s = f.read(DcmStatus)
    def b(i : Int) = ((s >> i) & 1) == 1
    Status(b(DcmBit.Locked), b(DcmBit.Busy), b(DcmBit.ProgDone), b(DcmBit.Rejected), b(DcmBit.Timeout))
  }

  /** Zegar dut zmierzony zegarem sys: czeka na dwa pelne okna pomiaru. */
  def measure(f : FpgaDevice[_]) : Double = {
    Thread.sleep(2L * FreqWindowCycles * 1000 / ClkHz + 5)
    f.read(DutFreq).toDouble * ClkHz / FreqWindowCycles
  }

  /** Programuje M/D (FPGA w stop) i zwraca zmierzony zegar dut. */
  def program(f : FpgaDevice[_], m : Int, d : Int, timeoutMs : Long = 500) : Double = {
    f.write(DcmMd, DcmProg.encode(m, d))
    val end = System.nanoTime + timeoutMs * 1000000L
    var s = status(f)
    while (s.busy || !s.locked) {
      if (System.nanoTime - end > 0) throw new HilDeviceError(f.label, None, s"DCM $m/$d: $s po $timeoutMs ms")
      Thread.sleep(2)
      s = status(f)
    }
    if (s.rejected) throw new HilDeviceError(f.label, None, s"DCM $m/$d odrzucone przez harness (zakres albo szybciej niz wariant)")
    if (s.timeout)  throw new HilDeviceError(f.label, None, s"DCM $m/$d: PROGDONE nie wrocil (timeout programatora)")
    measure(f)
  }
}
