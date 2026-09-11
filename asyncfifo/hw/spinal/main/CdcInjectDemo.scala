package newhope.asyncfifo

import spinal.core._
import spinal.lib._

/**
 * Eksperyment kontrolny: ten sam licznik przechodzi przez granice domen
 * dwiema sciezkami jednoczesnie - w kodzie Graya i binarnie - przez identyczne
 * synchronizatory z wstrzykiwana niepewnoscia.
 *
 * NIESYNTEZOWALNY (uzywa MetaBufferCC).
 *
 * Licznik zrodlowy jest celowo spowolniony (`srcDivider`), tak zeby domena
 * docelowa probkowala go kilka razy na kazda inkrementacje. Dzieki temu miedzy
 * dwoma probkami zmienia sie co najwyzej JEDNA inkrementacja - czyli w sciezce
 * Graya co najwyzej jeden bit, a w sciezce binarnej tyle bitow, ile wyniesie
 * przeniesienie (np. 011111 -> 100000 to piec bitow naraz).
 *
 * Obie sciezki dostaja dokladnie te same zaklocenia. Jedyna roznica to kodowanie.
 *
 * Kryterium bledu: licznik rosnie o 1, wiec kazda kolejna probka w domenie
 * docelowej musi sie roznic od poprzedniej o 0 albo o 1. Cokolwiek innego
 * to wartosc, ktora nigdy nie istniala w domenie zrodlowej.
 */
case class CdcInjectDemo(width: Int = 6, srcDivider: Int = 16) extends Component {

  val clkA = ClockDomain.external("clkA", frequency = FixedFrequency(100 MHz))
  val clkB = ClockDomain.external("clkB", frequency = FixedFrequency(37 MHz))

  val io = new Bundle {
    // sterowanie zaklocaniem - jeden bit na kazdy przerzutnik synchronizatora
    val chaosGray = in Bits (width bits)
    val chaosBin  = in Bits (width bits)

    val srcValue   = out UInt (width bits) // prawda w domenie A
    val graySynced = out UInt (width bits) // co widzi domena B sciezka Graya
    val binSynced  = out UInt (width bits) // co widzi domena B sciezka binarna
    val grayErrors = out UInt (16 bits)
    val binErrors  = out UInt (16 bits)
  }

  // sygnaly miedzydomenowe - poza obiema ClockingArea, dokladnie jak w StreamFifoCC
  val grayLine = Bits(width bits)
  val binLine  = Bits(width bits)

  val src = new ClockingArea(clkA) {
    val tick    = CounterFreeRun(srcDivider).willOverflow
    val counter = Reg(UInt(width bits)) init (0)
    val nextVal = counter + 1

    // Oba rejestry licza sie z *nastepnej* wartosci licznika, rownolegle do niego.
    // Dzieki temu na granice domen wychodzi czyste wyjscie przerzutnika,
    // bez logiki kombinacyjnej, ktora mogla by generowac hazardy.
    val grayReg = Reg(Bits(width bits)) init (0)
    val binReg  = Reg(Bits(width bits)) init (0)

    when(tick) {
      counter := nextVal
      grayReg := toGray(nextVal)
      binReg  := nextVal.asBits
    }
  }

  grayLine := src.grayReg
  binLine  := src.binReg
  io.srcValue := src.counter

  val dst = new ClockingArea(clkB) {
    val graySync = MetaBufferCC(grayLine, io.chaosGray)
    val binSync  = MetaBufferCC(binLine, io.chaosBin)

    val grayVal = fromGray(graySync)
    val binVal  = binSync.asUInt

    val grayPrev = RegNext(grayVal) init (0)
    val binPrev  = RegNext(binVal) init (0)

    // roznica modulo 2^width
    val grayStep = grayVal - grayPrev
    val binStep  = binVal - binPrev

    val grayErr = Reg(UInt(16 bits)) init (0)
    val binErr  = Reg(UInt(16 bits)) init (0)

    when(grayStep =/= 0 && grayStep =/= 1) { grayErr := grayErr + 1 }
    when(binStep =/= 0 && binStep =/= 1) { binErr := binErr + 1 }
  }

  io.graySynced := dst.grayVal
  io.binSynced  := dst.binVal
  io.grayErrors := dst.grayErr
  io.binErrors  := dst.binErr
}
