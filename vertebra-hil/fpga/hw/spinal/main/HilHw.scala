package newhope.vertebra.hil

import spinal.core._

/** Wspolne kawalki sprzetu harnessu, niezalezne od IP. */
object HilHw {
  /** xorshift32 (13, 17, 5), przesuniecia logiczne - jak HilRand / I2sPattern. */
  def xorshift32(x0 : Bits) : Bits = {
    val a = x0 ^ (x0 |<< 13)
    val b = a  ^ (a  |>> 17)
    b ^ (b |<< 5)
  }
}
