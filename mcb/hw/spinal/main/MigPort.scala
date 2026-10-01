package newhope.mcb

import spinal.core._
import spinal.lib._

/**
 * Konfiguracja MCB. JEDYNE zrodlo prawdy o drzewie zegarowym.
 *
 * Szesc parametrow PLL to dokladnie te same liczby, ktore s6_lpddr.v przyjmuje
 * jako parametry - BlackBox przekazuje je do instancji, wiec Verilogu nie trzeba
 * juz dotykac. Wymagalo to jednorazowego przeniesienia ich z ciała modulu do
 * listy #(), bo w formie ANSI parametry z ciala modulu sa lokalne i nadpisac
 * sie ich nie da.
 *
 * Drzewo zegarowe:
 *
 *   vco        = inClk * clkFbOutMult / divClkDivide
 *   sysclk_2x  = vco / clkOut0Divide        -> BUFPLL_MCB -> twardy blok MCB
 *   memClk     = sysclk_2x / 2              -> zegar pamieci (DDR)
 *   uiClk      = vco / clkOut2Divide        -> c3_clk0, domena logiki
 *   drpClk     = vco / clkOut3Divide        -> soft-kalibracja
 *
 * CLKOUT1 zawsze dzieli tak samo jak CLKOUT0 (to para sysclk_2x / _180),
 * wiec nie jest osobnym parametrem.
 *
 * Parametry czasowe pamieci w s6_lpddr.v sa w PIKOSEKUNDACH (tRAS 40000,
 * tREFI 7800000), czyli niezalezne od zegara - memc_wrapper przelicza je na
 * takty przez C_MEMCLK_PERIOD. Dlatego zmiana zegara stad przestraja je
 * poprawnie.
 */
case class MigConfig(
    dataWidth     : Int  = 128,

    // --- PLL: 1:1 z parametrami s6_lpddr.v ---
    inClkHz       : Long = 100000000L,   // oscylator na plytce
    clkFbOutMult  : Int  = 6,            // C3_CLKFBOUT_MULT
    divClkDivide  : Int  = 1,            // C3_DIVCLK_DIVIDE
    clkOut0Divide : Int  = 2,            // C3_CLKOUT0_DIVIDE -> sysclk_2x
    clkOut2Divide : Int  = 6,            // C3_CLKOUT2_DIVIDE -> c3_clk0
    clkOut3Divide : Int  = 10,           // C3_CLKOUT3_DIVIDE -> mcb_drp_clk

    // --- kosc ---
    dqPins        : Int  = 16,
    colBits       : Int  = 10,
    memAddrWidth  : Int  = 13,
    bankAddrWidth : Int  = 2,
    memClkMaxHz   : Long = 166670000L,   // MT46H32M16-5

    // --- port ---
    countWidth    : Int  = 7,
    addrWidth     : Int  = 30
) {
  require(Set(32, 64, 128) contains dataWidth,
    "MCB obsluguje port uzytkownika 32, 64 albo 128 bitow")

  def clkOut1Divide = clkOut0Divide

  def vcoHz       : Long = inClkHz * clkFbOutMult / divClkDivide
  def pfdHz       : Long = inClkHz / divClkDivide
  def sysClk2xHz  : Long = vcoHz / clkOut0Divide
  def memClkHz    : Long = sysClk2xHz / 2
  def uiClkHz     : Long = vcoHz / clkOut2Divide
  def drpClkHz    : Long = vcoHz / clkOut3Divide

  /** Okres zegara pamieci w ps, zaokraglony - trafia do C3_MEMCLK_PERIOD. */
  def memClkPeriod: Int = ((1000000000000L + memClkHz / 2) / memClkHz).toInt

  // --- limity PLL Spartan-6, UG382 ---
  require(vcoHz >= 400000000L && vcoHz <= 1080000000L,
    s"VCO ${vcoHz / 1000000} MHz poza zakresem 400-1080 MHz")
  require(pfdHz >= 19000000L && pfdHz <= 400000000L,
    s"PFD ${pfdHz / 1000000} MHz poza zakresem 19-400 MHz")
  require(clkFbOutMult >= 1 && clkFbOutMult <= 64, "clkFbOutMult poza 1..64")
  require(divClkDivide >= 1 && divClkDivide <= 52, "divClkDivide poza 1..52")
  require(Seq(clkOut0Divide, clkOut2Divide, clkOut3Divide).forall(d => d >= 1 && d <= 128),
    "dzielnik CLKOUT poza 1..128")

  // --- limity MCB, UG388 ---
  require(memClkHz <= memClkMaxHz,
    s"zegar pamieci ${memClkHz / 1000000} MHz przekracza ${memClkMaxHz / 1000000} MHz")
  require(drpClkHz >= 50000000L && drpClkHz <= 100000000L,
    s"zegar kalibracji ${drpClkHz / 1000000} MHz poza 50-100 MHz (UG388)")

  /** Czysta czestotliwosc - dla modulow, ktore chca HertzNumber (np. SevenSegMux). */
  def uiHertz: HertzNumber = HertzNumber(BigDecimal(uiClkHz))
  /** Opakowana - dla ClockDomain(frequency = ...). NIE opakowuj drugi raz. */
  def uiFrequency = FixedFrequency(uiHertz)

  /**
   * Minimalny zegar UI dla pelnego wykorzystania pasma, wzor z UG388:
   * (bity na takt pamieci / szerokosc portu) * memClk.
   * Kosc x16 przy DDR daje 32 bity na takt.
   */
  def uiClkMinHz: Long = memClkHz * (dqPins * 2) / dataWidth
  def uiClkMeetsBandwidth: Boolean = uiClkHz >= uiClkMinHz

  def maskWidth    = dataWidth / 8
  def bytesPerWord = dataWidth / 8
  def maxBurst     = 64
  def fifoDepth    = 64
  def rowBytes     = (1 << colBits) * (dqPins / 8)

  /** Pojemnosc z geometrii - niezgodnosc z kostka wyjdzie przy elaboracji. */
  def memBytes: Long =
    (1L << memAddrWidth) * (1L << bankAddrWidth) * (1L << colBits) * (dqPins / 8)

  def dramPeakBytesPerSec: Long = memClkHz * 2 * (dqPins / 8)
  def portPeakBytesPerSec: Long = uiClkHz * bytesPerWord

  def report: String =
    f"""|  VCO         : ${vcoHz / 1000000}%4d MHz   (PFD ${pfdHz / 1000000} MHz)
        |  pamiec      : ${memClkHz / 1000000}%4d MHz   (okres $memClkPeriod ps)
        |  zegar UI    : ${uiClkHz / 1000000}%4d MHz   (minimum dla pasma: ${uiClkMinHz / 1000000} MHz${if (uiClkMeetsBandwidth) "" else "  <-- ZA WOLNO"})
        |  kalibracja  : ${drpClkHz / 1000000}%4d MHz
        |  port ${dataWidth}%3d b  : ${portPeakBytesPerSec / 1000000}%4d MB/s
        |  pamiec szczyt: ${dramPeakBytesPerSec / 1000000}%3d MB/s""".stripMargin
}

object MigConfig {

  // Limity krzemu: PLL Spartan-6 (UG382) i MCB (UG388).
  private val VcoMin = 400e6;  private val VcoMax = 1080e6
  private val PfdMin = 19e6;   private val PfdMax = 400e6
  private val DrpMin = 50e6;   private val DrpMax = 100e6
  /**
   * Cel dla zegara kalibracji. NIE srodek zakresu 50-100 MHz, tylko dolna
   * czesc - przy tym zegarze nic sie nie zyskuje na szybkosci, a kazdy
   * megaherc w gore zabiera margines czasowy. Przy 75 MHz raport pokazal
   * 57 ps zapasu; przy 60 MHz jest go ponad 3 ns.
   */
  private val DrpTarget = 60e6

  case class PllSolution(mult: Int, divclk: Int, out0: Int, out2: Int, out3: Int,
                         vcoHz: Double, memHz: Double, uiHz: Double, pfdHz: Double) {
    /** CLKOUT0 inne niz 2 to teren niesprawdzony - patrz komentarz przy forClocks. */
    def usesUnusualClkOut0 = out0 != 2
  }

  private def search(memHz: Double, uiHz: Double, inClkHz: Long,
                     allowClkOut0: Seq[Int], tol: Double): Seq[PllSolution] = {
    val found = scala.collection.mutable.ArrayBuffer[PllSolution]()
    for (divclk <- 1 to 52) {
      val pfd = inClkHz.toDouble / divclk
      if (pfd >= PfdMin && pfd <= PfdMax) for (mult <- 1 to 64) {
        val vco = inClkHz.toDouble * mult / divclk
        if (vco >= VcoMin && vco <= VcoMax) {
          for (out0 <- allowClkOut0 if out0 >= 1 && out0 <= 128) {
            val m = vco / (2.0 * out0)
            if (scala.math.abs(m - memHz) / memHz <= tol) for (out2 <- 1 to 128) {
              val u = vco / out2
              if (scala.math.abs(u - uiHz) / uiHz <= tol) {
                val o3 = (1 to 128).filter(o => vco / o >= DrpMin && vco / o <= DrpMax)
                if (o3.nonEmpty) {
                  val out3 = o3.minBy(o => scala.math.abs(vco / o - DrpTarget))
                  found += PllSolution(mult, divclk, out0, out2, out3, vco, m, u, pfd)
                }
              }
            }
          }
        }
      }
    }
    // Najpierw wysokie PFD (nizszy jitter), potem sprawdzone CLKOUT0 = 2,
    // na koncu mniejszy mnoznik.
    found.toSeq.sortBy(s => (-s.pfdHz, if (s.out0 == 2) 0 else 1, s.mult))
  }

  /** Jakie zegary UI da sie uzyskac przy zadanym zegarze pamieci. W MHz. */
  def achievableUi(memMHz: Double, base: MigConfig = MigConfig(),
                   allowClkOut0: Seq[Int] = Seq(1, 2),
                   tolerance: Double = 0.005): Seq[Double] = {
    val memHz = memMHz * 1e6
    val out = scala.collection.mutable.SortedSet[Double]()
    for (divclk <- 1 to 52; mult <- 1 to 64) {
      val pfd = base.inClkHz.toDouble / divclk
      val vco = base.inClkHz.toDouble * mult / divclk
      if (pfd >= PfdMin && pfd <= PfdMax && vco >= VcoMin && vco <= VcoMax) {
        for (out0 <- allowClkOut0) {
          val m = vco / (2.0 * out0)
          if (scala.math.abs(m - memHz) / memHz <= tolerance) {
            for (out2 <- 1 to 128) {
              val u = vco / out2
              if (u >= 20e6 && u <= 160e6) out += scala.math.round(u / 1e4) / 100.0
            }
          }
        }
      }
    }
    out.toSeq
  }

  def findClocks(memMHz: Double, uiMHz: Double, base: MigConfig = MigConfig(),
                 allowClkOut0: Seq[Int] = Seq(1, 2),
                 tolerance: Double = 0.005): Option[MigConfig] =
    search(memMHz * 1e6, uiMHz * 1e6, base.inClkHz, allowClkOut0, tolerance)
      .headOption.map { s =>
        base.copy(clkFbOutMult  = s.mult,  divClkDivide  = s.divclk,
                  clkOut0Divide = s.out0,  clkOut2Divide = s.out2,
                  clkOut3Divide = s.out3)
      }

  /**
   * Dobiera dzielniki PLL dla zadanej pary czestotliwosci (w MHz).
   *
   *   val cfg = MigConfig.forClocks(memMHz = 150, uiMHz = 100)
   *
   * Tolerancja 0,5%, bo PLL daje dokladne STOSUNKI, nie dokladne herce -
   * 166,67 MHz nie jest liczba calkowita i przy wymaganiu dokladnosci
   * nie znalazloby sie nic.
   *
   * allowClkOut0 domyslnie Seq(1, 2), czyli tylko to, czego uzywa sam MIG.
   * Rozszerzenie na Seq(1,2,3,4) otwiera wiecej kombinacji - w tym
   * mem 166,67 + ui 100 MHz przy VCO 1000 MHz i PFD 100 MHz zamiast 33 MHz,
   * co dalo by WYRAZNIE nizszy jitter niz obecna konfiguracja.
   *
   * ALE: CLKOUT0 karmi zegar DDR przez BUFPLL_MCB i ma pare fazowa 180 stopni
   * (CLKOUT1_PHASE). Przy nieparzystym dzielniku wypelnienie i faza nie sa
   * przeze mnie zweryfikowane. Zanim tego uzyjesz, przelicz to w Clocking
   * Wizard (tryb Manual Selection, prymityw PLL_BASE) i sprawdz wypelnienie
   * oraz jitter - UG388 zreszta sam do tego narzedzia odsyla.
   */
  def forClocks(memMHz: Double, uiMHz: Double, base: MigConfig = MigConfig(),
                allowClkOut0: Seq[Int] = Seq(1, 2),
                tolerance: Double = 0.005): MigConfig =
    findClocks(memMHz, uiMHz, base, allowClkOut0, tolerance).getOrElse {
      val alt = achievableUi(memMHz, base, allowClkOut0, tolerance)
      val hint =
        if (alt.isEmpty) s"zegar pamieci $memMHz MHz jest nieosiagalny z wejscia ${base.inClkHz / 1000000} MHz"
        else s"przy pamieci $memMHz MHz osiagalne zegary UI to: ${alt.map(v => f"$v%.2f").mkString(", ")} MHz"
      sys.error(s"brak dzielnikow PLL dla pamieci $memMHz MHz + UI $uiMHz MHz; $hint" +
                (if (allowClkOut0 == Seq(1, 2)) " (sprobuj allowClkOut0 = Seq(1,2,3,4))" else ""))
    }

  // Gotowe kombinacje z oscylatora 100 MHz. Przechodza przez solver, zeby
  // CLKOUT3_DIVIDE dobral sie wedlug tego samego kryterium co wszedzie.
  def mem100ui100 = forClocks(100, 100)
  def mem125ui100 = forClocks(125, 100)
  def mem150ui100 = forClocks(150, 100)
  def mem166ui111 = forClocks(166.67, 111.11)
}

object MigInstr {
  def WRITE    = B"3'b000"
  def READ     = B"3'b001"
  def WRITE_AP = B"3'b010"   // z auto-precharge
  def READ_AP  = B"3'b011"
  def REFRESH  = B"3'b100"
}

/** Komenda do kolejki komend portu. */
case class MigCmd(c: MigConfig) extends Bundle {
  val instr = Bits(3 bits)
  /** Dlugosc burstu MINUS JEDEN. 0 = jedno slowo, 63 = 64 slowa. */
  val bl    = UInt(6 bits)
  /** Adres bajtowy, wyrownany do bytesPerWord. */
  val addr  = UInt(c.addrWidth bits)
}

/** Slowo do kolejki zapisu. */
case class MigWrData(c: MigConfig) extends Bundle {
  val data = Bits(c.dataWidth bits)
  /** Maska aktywna wysokim: bit = 1 oznacza bajt POMINIETY. */
  val mask = Bits(c.maskWidth bits)
}

/**
 * Stream-owy widok jednego portu MCB.
 *
 * Kolejki MCB maja dokladnie semantyke Stream, wiec warstwa jest cienka:
 *   cmd.ready = !cmd_full,  cmd_en = cmd.fire
 *   wr.ready  = !wr_full,   wr_en  = wr.fire
 *   rd.valid  = !rd_empty,  rd_en  = rd.fire   (FIFO jest first-word-fall-through)
 *
 * Czego ta warstwa NIE robi, a o czym musi pamietac kazdy master:
 *  - dane zapisu musza znalezc sie w kolejce PRZED komenda WRITE (albo nadazac
 *    za nia), inaczej MCB zglosi wr_underrun i zapisze smieci,
 *  - nie zamawiaj wiecej odczytow, niz zmiesci sie w 64-slowowym FIFO, bo
 *    dostaniesz rd_overflow,
 *  - dane odczytu wracaja w kolejnosci komend i BEZ tagu,
 *  - zapis nie ma potwierdzenia - o uporzadkowaniu decyduje tylko kolejnosc
 *    komend na tym samym porcie.
 */
case class MigPort(c: MigConfig) extends Bundle with IMasterSlave {
  val cmd = Stream(MigCmd(c))
  val wr  = Stream(MigWrData(c))
  val rd  = Stream(Bits(c.dataWidth bits))

  override def asMaster(): Unit = {
    master(cmd, wr)
    slave(rd)
  }

  /**
   * Podlacza mastera do tej strony portu. Uzywane od strony MCB:
   *   mcb.port.driveFrom(engine.io.port)
   *
   * Nie uzywamy tu `<>`, bo strona MCB to zwykly bundle bez kierunkow
   * (siedzi wewnatrz modulu), wiec SpinalHDL nie mialby jak ich wywnioskowac.
   */
  def driveFrom(m: MigPort): Unit = {
    this.cmd << m.cmd
    this.wr  << m.wr
    m.rd     << this.rd
  }
}
