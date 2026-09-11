package newhope.asyncfifo

import spinal.core._
import spinal.lib._

class AsyncFifo[T <:Data]( dataType: HardType[T],
                            depth: Int, 
                            pushClock: ClockDomain, 
                            popClock: ClockDomain) extends Component {

    assert(isPow2(depth) && depth >= 4, "depth musi być potęgą 2, >= 4")

    val io = new Bundle {
        val push = slave  Stream(dataType)
        val pop  = master Stream(dataType)
        val full  = out Bool
        val empty = out Bool
    }

    val ptrWidth = log2Up(depth) + 1
    
    /* przy kodzie Graya trzeba zanegować dwa najstarsze bity
     *  aby sprawdzić czy FIFO jest pełne
     *  bo w kodzie Graya przy przepełnieniu zmieniają się dwa najstarsze bity
     *  a reszta pozostaje taka sama
     *  więc porównujemy z negacją dwóch najstarszych bitów
     *  i resztę porównujemy normalnie
     */
    def isFull(a: Bits, b: Bits) =
        a(ptrWidth - 1 downto ptrWidth - 2) === ~b(ptrWidth - 1 downto ptrWidth - 2) &&
        a(ptrWidth - 3 downto 0) === b(ptrWidth - 3 downto 0)

    def isEmpty(a: Bits, b: Bits): Bool = a === b
    
    val ram = Mem(dataType, depth)

    val popToPushGray = Bits(ptrWidth bits)
    val pushToPopGray = Bits(ptrWidth bits)

    val pushCC = new ClockingArea(pushClock) {
        val pushPtr     = Counter(depth << 1)
        val pushPtrGray = RegNext(toGray(pushPtr.valueNext)) init(0)
        val popPtrGray  = BufferCC(popToPushGray, B(0, ptrWidth bits))
        val full        = isFull(pushPtrGray, popPtrGray)
        /* wywołanie metody isFull generuje hardware i przypisuje referencję do niego
        ** do zmiennej full. Każde użycie full (jak niżej: full i !full)
        ** to tylko podpięcie do tego samego sygnały.
        ** Tylko wywołanie samej funkcji isFull(..) faktycznie generuje hardware 2x
        ** który prawdopodobnie dopiero syntezator ISE/Vivado zoptymalizuje */
        io.full := full
        io.push.ready := !full
        when(io.push.fire) {
            ram.write(pushPtr.resized, io.push.payload)
            pushPtr.increment()
        }
    }

    val popCC = new ClockingArea(popClock) {
        val popPtr      = Counter(depth << 1)
        val popPtrGray  = RegNext(toGray(popPtr.valueNext)) init(0)
        val pushPtrGray = BufferCC(pushToPopGray, B(0, ptrWidth bits))
        val empty       = isEmpty(popPtrGray, pushPtrGray)

        io.empty := empty
        io.pop.valid := !empty
        io.pop.payload := ram.readSync(
            address = popPtr.valueNext.resized,
            clockCrossing = true
        )
        when(io.pop.fire) {popPtr.increment()}
    }
    pushToPopGray := pushCC.pushPtrGray
    popToPushGray := popCC.popPtrGray
}

case class AsyncFifoDemo(dataWidth: Int = 8, depth: Int =  16) extends Component {
    val clkA = ClockDomain.external("clkA", frequency = FixedFrequency(100 MHz))
    val clkB = ClockDomain.external("clkB", frequency = FixedFrequency(37 MHz))

    val io = new Bundle {
        val push = slave Stream(UInt(dataWidth bits))
        val pop  = master Stream(UInt(dataWidth bits))
        val full = out Bool
        val empty = out Bool
        //val occupancyWidth = log2Up(depth + 1)
        //val pushOccupancy = out UInt(occupancyWidth bits)
        //val popOccupancy = out UInt(occupancyWidth bits)
        /* if using val fifo = StreamFifoCC
        val pushOccupancy = out(cloneOf(fifo.io.pushOccupancy))
        val popOccupancy  = out(cloneOf(fifo.io.popOccupancy))*/
    }

    val fifo = new AsyncFifo(   //można użyć StreamFifoCC
        dataType = UInt(dataWidth bits), 
        depth = depth, 
        pushClock = clkA, 
        popClock = clkB
    )

    fifo.io.push << io.push
    io.pop << fifo.io.pop

    // Uwaga: to sa oceny LOKALNE dla kazdej domeny i celowo pesymistyczne.
    // "full" widziane w clkA opiera sie na zsynchronizowanym (starszym o 2-3 cykle)
    // wskazniku odczytu, wiec czasem klamie "na plus". Nigdy odwrotnie.
    //io.full  := fifo.io.pushOccupancy === depth
    //io.empty := fifo.io.popOccupancy === 0
    io.full := fifo.io.full
    io.empty := fifo.io.empty
}

object AsyncFifoDemoVerilog extends App {
  SpinalConfig(
    targetDirectory = "hw/gen/verilog",
  ).generateVerilog(AsyncFifoDemo())
}
