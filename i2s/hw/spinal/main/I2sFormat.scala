package newhope.i2s

// =====================================================================
//  Format slowa na magistrali I2S, niezalezny od implementacji DUT-a.
//
//  Przeniesione z I2sBusMaster (testy), bo ta sama funkcja jest czescia
//  kontraktu vertebra-hil (contract/pattern.md): scoreboardy symulacji
//  i checkery na FPGA / ESP32 / hoscie maja liczyc dokladnie to samo.
//  Czysta Scala, bez Spinala - uzywa jej tez host, ktory nie symuluje.
// =====================================================================
object I2sFormat {

  /** Slowo nadajnika (fromW bitow) przeslane w slocie `len` bitow i
    * odebrane przez odbiornik o szerokosci toW, MSB-first: nadmiar LSB
    * odciety, brak uzupelniony zerami.
    *
    * Pozycja p slotu (0 = pierwszy bit po zboczu WS + 1 bit) niesie bit
    * fromW-1-p slowa, jesli p < fromW i p < len; inaczej 0 (padding
    * nadajnika albo koniec slotu). Odbiornik bierze pozycje 0 until toW.
    * Stad: padding po stronie odbiornika to zera, a slowo dluzsze niz
    * slot traci LSB-y juz na magistrali. */
  def transfer(v : Long, fromW : Int, len : Int, toW : Int) : Long = {
    require(fromW >= 1 && fromW <= 63 && toW >= 1 && toW <= 63 && len >= 0,
            s"transfer: fromW=$fromW len=$len toW=$toW poza zakresem")
    (0 until toW).foldLeft(0L) { (acc, p) =>
      val b = p < len && p < fromW && ((v >> (fromW - 1 - p)) & 1L) == 1L
      (acc << 1) | (if (b) 1L else 0L)
    }
  }
}
