package newhope.i2s

// =====================================================================
//  Zdarzenia na poziomie transakcji (§4.7). I2S nie ma Start/Stop ani
//  ACK - jedyna transakcja to ramka stereo. Cisza (underrun, brak danych
//  w kodeku) to zwykla ramka zer, bo na pinach jest nieodroznialna.
// =====================================================================
object I2sEvent {
  case class Frame(left : Long, right : Long) {
    override def toString = f"Frame(0x$left%X, 0x$right%X)"
  }
  val Silence = Frame(0, 0)
}
