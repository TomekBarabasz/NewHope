package newhope.vgademo

import spinal.core._
import spinal.lib.graphic.RgbConfig
import spinal.lib.graphic.vga.{VgaTimings, VgaTimingsHV}
import newhope.mcb.MigConfig

/**
 * Jedna os czasowania VGA.
 *
 * Konwencja licznika w spinal.lib.graphic.vga jest taka, ze zero licznika
 * wypada NA POCZATKU impulsu synchronizacji, a rejestr `sync` wewnatrz VgaCtrl
 * jest NISKI dokladnie przez czas trwania impulsu. Dlatego przeliczenie
 * z (visible, front, sync, back) na cztery progi VgaTimingsHV siedzi tutaj,
 * w jednym miejscu - patrz applyTimings().
 *
 * POLARYZACJA. VgaTimingsHV ma piate pole, `polarity`, i VgaCtrl robi z niego
 * `io.vga.hSync := h.sync ^ h.polarity`. Czyli polarity = false daje impuls
 * aktywny NISKIM, a to jest wlasnie to, czego chce 640x480@60 (oba impulsy
 * ujemne). To pole jest wejsciem komponentu jak kazde inne i musi byc
 * sterowane, inaczej elaboracja konczy sie "NO DRIVER ON io_timings_h_polarity".
 *
 * @param activeHigh true = impuls synchronizacji aktywny wysokim. Dla 640x480
 *                   zostaw false; przydaje sie przy trybach w rodzaju 800x600,
 *                   gdzie VESA chce obu impulsow dodatnich.
 */
case class VgaAxisTiming(visible: Int, front: Int, sync: Int, back: Int,
                         activeHigh: Boolean = false) {
  val total = visible + front + sync + back
}

object VgaAxisTiming {
  /** 640x480@60 wg VESA. Nominalny zegar piksela to 25,175 MHz. */
  def h640 = VgaAxisTiming(visible = 640, front = 16, sync = 96, back = 48)
  def v480 = VgaAxisTiming(visible = 480, front = 10, sync = 2, back = 33)
}

/**
 * Konfiguracja demo: VGA + framebuffer w LPDDR + wgrywanie przez UART.
 *
 * NAJWAZNIEJSZA LICZBA W CALYM PROJEKCIE
 * --------------------------------------
 * Przy memclk 100 MHz zegar interfejsu uzytkownika MCB to memclk/4 = 25,000 MHz,
 * a zegar piksela 640x480@60 to 25,175 MHz. Roznica 0,7% oznacza odswiezanie
 * 59,52 Hz zamiast 60,00 Hz - kazdy monitor to lyknie. Dzieki temu CALY projekt
 * siedzi w jednej domenie zegarowej oddanej przez MCB: zero CDC na sciezce
 * pikseli, zero PLL-a obok MCB, zero ograniczen czasowych do dopisania.
 *
 * To nie jest przypadek do wykorzystania raz - to jest powod, dla ktorego demo
 * chodzi na memclk 100 MHz, mimo ze kontroler zmierzyliscmy do 166 MHz.
 * Podniesienie memclk zepsuje zegar piksela.
 *
 * @param scale  1 = framebuffer 640x480 (300 KiB), 2 = 320x240 powielane 2x2
 *               (75 KiB). Przy 115200 bd to roznica 27 s vs 7 s na obrazek.
 */
case class VgaFbConfig(
    mig        : MigConfig     = MigConfig(dataWidth = 128, memClkPeriod = 10000),
    h          : VgaAxisTiming = VgaAxisTiming.h640,
    v          : VgaAxisTiming = VgaAxisTiming.v480,
    scale      : Int           = 2,
    bufferA    : BigInt        = 0x00000000,
    bufferB    : BigInt        = 0x00100000,
    burstWords : Int           = 20,
    uartBaud   : Int           = 19200
) {

  // ---- kolor ---------------------------------------------------------
  /** Mimas V2 ma drabinki 3-3-2, czyli dokladnie jeden bajt na piksel. */
  val rgb = RgbConfig(3, 3, 2)
  require(rgb.getWidth == 8, "ten framebuffer zaklada 1 bajt na piksel")

  // ---- geometria framebuffera ----------------------------------------
  val scaleLog = log2Up(scale)
  require((1 << scaleLog) == scale, s"scale = $scale nie jest potega dwojki")

  val fbWidth  = h.visible / scale
  val fbHeight = v.visible / scale
  require(fbWidth * scale == h.visible, "scale nie dzieli szerokosci obrazu")
  require(fbHeight * scale == v.visible, "scale nie dzieli wysokosci obrazu")

  val wordBytes = mig.dataWidth / 8
  val wordBits  = log2Up(wordBytes)
  require((1 << wordBits) == wordBytes, "szerokosc portu musi byc potega dwojki")
  require(fbWidth % wordBytes == 0,
    s"linia $fbWidth B nie dzieli sie na slowa po $wordBytes B")

  val wordsPerLine = fbWidth / wordBytes
  require(burstWords >= 1 && burstWords <= mig.maxBurst,
    s"burstWords = $burstWords poza zakresem 1..${mig.maxBurst}")
  require(burstWords <= mig.fifoDepth,
    s"burst $burstWords slow nie zmiesci sie w FIFO portu (${mig.fifoDepth})")
  require(wordsPerLine % burstWords == 0,
    s"$wordsPerLine slow w linii nie dzieli sie na bursty po $burstWords")
  val burstsPerLine = wordsPerLine / burstWords

  /**
   * Odstep miedzy liniami zaokraglony w gore do potegi dwojki. Kosztuje pamiec
   * (przy 320 B linii marnuje sie 192 B), ale zamienia mnozenie adresu na
   * przesuniecie. Przy 64 MB pamieci i 75 KiB obrazu to nie jest handel,
   * z ktorego trzeba sie tlumaczyc.
   */
  val strideLog  = log2Up(fbWidth)
  val lineStride = 1 << strideLog
  val frameBytes = lineStride.toLong * fbHeight

  require(bufferA % wordBytes == 0 && bufferB % wordBytes == 0,
    s"bazy buforow musza byc wyrownane do $wordBytes B")
  require((bufferA - bufferB).abs >= frameBytes, "bufory nachodza na siebie")
  require(bufferA + frameBytes <= mig.memBytes, "bufor A wychodzi poza pamiec")
  require(bufferB + frameBytes <= mig.memBytes, "bufor B wychodzi poza pamiec")

  // ---- zegar i przepustowosc ------------------------------------------
  val uiClkHz    = mig.uiClkHz
  val frameHz    = uiClkHz.toDouble / (h.total.toLong * v.total)
  /** Czytamy tylko piksele, nie wypelnienie stride. */
  val fetchBytesPerSec = fbWidth.toDouble * fbHeight * frameHz
  val portBytesPerSec  = mig.portPeakBytesPerSec.toDouble
  require(fetchBytesPerSec < portBytesPerSec / 2,
    "odswiezanie obrazu zjada ponad polowe portu - zostaw zapas na zapisy")

  // ---- UART ------------------------------------------------------------
  /** 1 + 5 + 2, czyli domyslne UartCtrlGenerics. */
  val uartSamplesPerBit = 8
  val uartDivider = (uiClkHz / (uartBaud.toLong * uartSamplesPerBit)).toInt
  require(uartDivider >= 1, s"$uartBaud bd jest za szybkie dla $uiClkHz Hz")
  val uartActualBaud = uiClkHz.toDouble / (uartDivider.toDouble * uartSamplesPerBit)
  val uartBaudError  = (uartActualBaud - uartBaud) / uartBaud
  require(scala.math.abs(uartBaudError) < 0.015,
    f"blad predkosci UART ${uartBaudError * 100}%.2f%% - dobierz inna wartosc uartBaud")

  // ---- protokol --------------------------------------------------------
  object Proto {
    val sync0 = 0xA5
    val sync1 = 0x5A
    val cmdWrite = 0x01 // addr[32] len[32] payload[len] crc[8]
    val cmdShow  = 0x02 // bufor[8]
    val cmdPing  = 0x03 //
    val ackOk    = 0x4B // 'K'
    val ackErr   = 0x45 // 'E'
  }

  // ---- czasowanie VGA --------------------------------------------------
  def applyTimings(t: VgaTimings): Unit = {
    def axis(dst: VgaTimingsHV, a: VgaAxisTiming): Unit = {
      dst.syncStart  := a.sync - 1             // koniec impulsu sync
      dst.syncEnd    := a.total - 1            // poczatek nastepnego impulsu
      dst.colorStart := a.sync + a.back - 1
      dst.colorEnd   := a.total - a.front - 1
      dst.polarity   := Bool(a.activeHigh)     // false = sync aktywny niskim
    }
    axis(t.h, h)
    axis(t.v, v)
  }

  def report(): Unit = {
    println(f"[vgademo] port MCB      : ${mig.dataWidth} b, memclk ${mig.memClkHz / 1000000} MHz")
    println(f"[vgademo] zegar UI/px   : ${uiClkHz / 1000000.0}%.3f MHz -> ${frameHz}%.2f Hz odswiezania")
    println(f"[vgademo] ekran         : ${h.visible} x ${v.visible}, total ${h.total} x ${v.total}")
    println(f"[vgademo] framebuffer   : $fbWidth x $fbHeight x 8 b, stride $lineStride B, $frameBytes B na klatke")
    println(f"[vgademo] pobieranie    : ${fetchBytesPerSec / 1e6}%.1f MB/s z ${portBytesPerSec / 1e6}%.1f MB/s portu (${100 * fetchBytesPerSec / portBytesPerSec}%.1f%%)")
    println(f"[vgademo] linia         : $wordsPerLine slow = $burstsPerLine x $burstWords")
    println(f"[vgademo] UART          : $uartBaud bd zadane, ${uartActualBaud}%.0f bd faktyczne (${uartBaudError * 100}%+.2f%%)")
    println(f"[vgademo] wgranie klatki: ${frameBytes * 10.0 / uartActualBaud}%.1f s przy 10 bitach na bajt")
  }
}
