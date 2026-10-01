package newhope.frontend

import spinal.core._
import spinal.lib._

// =====================================================================
//  Widmo sygnalu rzeczywistego: Framer -> FftCore(RealPairs) -> RealUnpack.
//  Wejscie: probki audio (Flow), wyjscie: X[k], k = 0..fftSize/2,
//  mantysy BFP ze wspolnym wykladnikiem, last na prazku Nyquista.
//
//  Moc i modul to osobne komponenty (PowerSpectrum, Magnitude),
//  podpinane za tym blokiem - zespolone X przyda sie tez do
//  autokorelacji (F0) i overlap-add szumu.
// =====================================================================
class Rfft(val g: RfftGenerics) extends Component {
  require(g.isLegal, g.problems.mkString("; "))
  val W = g.core.dataWidth
  val E = g.core.expWidth

  val io = new Bundle {
    val input   = slave(Flow(SInt(g.framer.sampleWidth bits)))
    val output  = master(Stream(Fragment(BfpCplx(W, E))))
    val overrun = out Bool()
  }

  val framer = new Framer(g.framer)
  val core   = new FftCore(g.core)
  val unpack = new RealUnpack(g.core)

  framer.io.input << io.input
  core.io.input   << framer.io.output
  core.io.inverse := False
  unpack.io.input << core.io.pairs
  io.output       << unpack.io.output
  io.overrun      := framer.io.overrun

}
