package newhope.asyncfifo

import spinal.core._

/**
 * Model synchronizatora z wstrzykiwana niepewnoscia rozstrzygania.
 *
 * NIESYNTEZOWALNY - sluzy wylacznie do symulacji. W syntezie uzywaj BufferCC.
 *
 * Idea: na poziomie RTL metastabilnosc nie objawia sie jako "napiecie w polowie",
 * tylko jako niepewnosc o jeden cykl zegara docelowego. Pierwszy stopien albo
 * zdazyl sie rozstrzygnac przed zboczem drugiego stopnia, albo nie - i wtedy
 * drugi stopien zatrzaskuje jeszcze stara wartosc.
 *
 * Model jest MONOTONICZNY: wartosc na wyjsciu moze sie spoznic, ale nigdy nie
 * cofnie sie ani nie wyprzedzi zrodla. Realna metastabilnosc tez tego nie robi.
 *
 * Zwloka jest ograniczona do jednego dodatkowego cyklu na kazda zmiane
 * (flaga `stalled`) - inaczej losowy `chaos` moglby zatrzymac sygnal na zawsze,
 * co nie ma nic wspolnego z fizyka.
 *
 * Przy `chaos = 0` zachowuje sie identycznie jak zwykly BufferCC o glebokosci 2.
 *
 * Struktura celowo odwzorowuje spinal.lib BufferCC: Component z io + object apply,
 * ktory chowa instalacje. Dzieki granicy modulu sygnaly wewnetrzne maja w VCD
 * wlasna hierarchie zamiast wpadac do przestrzeni nazw wolajacego.
 */
class MetaBufferCC(width: Int) extends Component {

  val io = new Bundle {
    val dataIn  = in Bits (width bits) // sygnal z innej domeny zegarowej
    val chaos   = in Bits (width bits) // 1 = "ten przerzutnik nie zdazyl sie rozstrzygnac"
    val dataOut = out Bits (width bits)
  }

  // Kazdy bit dostaje wlasna Area -> w VCD wyladuje jako bits_0_stage0,
  // bits_1_stage0 itd., zamiast anonimowego stage0_1, stage0_2, ...
  val bits = for (i <- 0 until width) yield new Area {

    // pierwszy stopien: zwykle probkowanie sygnalu z obcej domeny
    val stage0 = RegNext(io.dataIn(i)) init (False)
    stage0.addTag(crossClockDomain)

    // drugi stopien z modelowana zwloka
    val stage1  = Reg(Bool()) init (False)
    val stalled = Reg(Bool()) init (False)

    when(stage1 =/= stage0) {
      when(io.chaos(i) && !stalled) {
        stalled := True // pierwszy stopien jeszcze sie klaruje
      } otherwise {
        stage1  := stage0
        stalled := False
      }
    } otherwise {
      stalled := False
    }

    io.dataOut(i) := stage1
  }
}

object MetaBufferCC {

  /**
   * @param input sygnal z innej domeny zegarowej
   * @param chaos jeden bit na kazdy bit `input`, sterowany z testbencha
   * @param name  opcjonalna nazwa instancji - warto podac, bo inaczej
   *              w hierarchii zobaczysz metaBufferCC_1, metaBufferCC_2
   */
  def apply(input: Bits, chaos: Bits, name: String = null): Bits = {
    require(
      chaos.getWidth == input.getWidth,
      s"chaos ma ${chaos.getWidth} bitow, input ${input.getWidth} - kazdy przerzutnik rozstrzyga sie niezaleznie"
    )

    val c = new MetaBufferCC(input.getWidth)
    if (name != null) c.setName(name)
    c.io.dataIn := input
    c.io.chaos  := chaos
    c.io.dataOut
  }
}