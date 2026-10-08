package newhope.sandbox

import scala.collection.mutable.ArrayBuffer
object ScalaTest {
  def spt(k: Double, nTerms: Int, maxShift: Int): Seq[(Int, Int)] = {
    val terms = ArrayBuffer[(Int, Int)]()
    var r     = k
    var done  = false
    while (!done && r != 0.0 && terms.size < nTerms) {
      val sign = if (r > 0) 1 else -1
      val e    = -math.log(math.abs(r)) / math.log(2.0)
      val s = Seq(math.floor(e), math.ceil(e))
        .map(_.toInt.max(1).min(maxShift))
        .minBy(s => math.abs(math.abs(r) - math.pow(2.0, -s)))
      val next = r - sign * math.pow(2.0, -s)
      if (math.abs(next) < math.abs(r)) { terms += ((sign, s)); r = next }
      else done = true
    }
    terms.toSeq
  }

  def sptBest(k: Double, nTerms: Int, maxShift: Int): Seq[(Int, Int)] = {
    val atoms = for (s <- 1 to maxShift; sg <- Seq(1, -1)) yield (sg, s)
    (1 to nTerms).iterator
        .flatMap(n => atoms.combinations(n))
        .filter(t => t.map(_._2).distinct.size == t.size)          // różne przesunięcia
        .minBy(t => math.abs(k - t.map { case (sg, s) => sg * math.pow(2.0, -s) }.sum))
  }

  def test_spt() {
    val fs = 75e6 / 4672
    val fc = 30.0
    val G = 8
    val nTerms = 3
    val dataWidth = 18
    val accWidth  = dataWidth + 3 + G
 
    val k     = 2 * math.Pi * fc / fs
    val maxShift = accWidth - 1
    val terms = spt(k, nTerms, maxShift)
    terms.foreach { case (sign,shift) =>
      println(s"sign = $sign, shift = $shift")
    }
    val terms2 = sptBest(k, nTerms, maxShift)
    terms2.foreach { case (sign,shift) =>
      println(s"sign = $sign, shift = $shift")
    }
  }

  def test_foreach() {
    val S = Seq( 1,2,3 )
    S.foreach {case (s) => println(s) }
    val maxShift = 10
    val atoms = for (s <- 1 to maxShift; sg <- Seq(1, -1)) yield (sg, s)
    atoms.foreach { case (sign,shift) =>
      println(s"sign = $sign, shift = $shift")
    }
  }

  def test_roundShift() : Unit = {
    val s1 = (1,1) +: Seq( (2,2), (3,3) )  // dodaj (1,1) na początku
    val s2 =  Seq( (1,1), (2,2) ) :+ (3,3) // dodaj (3,3) na końcu
    s1.foreach { case (a,b) => println(s"a = $a, b = $b") }
    s2.foreach { case (a,b) => println(s"a = $a, b = $b") }
  }

  def main(args: Array[String]): Unit = {
    //test_spt()
    //test_foreach()
    test_roundShift()
  }
}
