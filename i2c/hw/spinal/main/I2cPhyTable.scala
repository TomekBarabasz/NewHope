package org.newhope.i2c

import spinal.core._
import spinal.lib._
import spinal.lib.io.{ReadableOpenDrain, InOutWrapper}   // uwaga: NIE ma tego w spinal.lib._

// =====================================================================
//  WARSTWA 1 - PHY, wersja tablicowa.
//
//  Zamiast enumeracji stanow (sStartReleaseSda, sBitHighA, ...) stanem
//  jest para: (mode z payloadu Stream'a, numer cwiartki 0..3).
//  Kazda komenda trwa dokladnie 4 cwiartki, wiec licznik faz to 2 bity
//  i nie ma zadnego dekodera przejsc - jest tylko inkrementacja.
//
//  INWARIANT bez zmian: komenda zaczyna sie i konczy przy SCL NISKO
//  (wyjatek: STOP zostawia magistrale w stanie jalowym).
// =====================================================================

// ---------------------------------------------------------------------
//  Opis pojedynczej cwiartki. To jest czysta Scala - zaden sprzet stad
//  nie powstaje az do wywolania applyQuarter w petli elaboracyjnej.
// ---------------------------------------------------------------------
sealed trait Drive
object Drive {
  case object Keep    extends Drive   // brak przypisania -> rejestr trzyma wartosc
  case object Release extends Drive   // puszczam linie, podciagnie ja rezystor
  case object PullDown extends Drive  // sciagam do masy
  case object FromCmd extends Drive   // poziom z io.cmd.data
}

case class Quarter(scl : Drive, sda : Drive, sample : Boolean = false)

case class I2cPhyTable(g : I2cGenerics) extends I2cPhyBase(g) {

  import Drive._
  import I2cPhyCmdMode._

  // -------------------------------------------------------------------
  //  TABLICA CWIARTEK
  //
  //  START     SCL  ~~~~~~~~~~~~~\____      (Keep w Q0 -> ta sama sekwencja
  //            SDA  __/~~~~~~\________       obsluguje START i RESTART)
  //                 | Q0| Q1| Q2| Q3|
  //  BIT       SCL  ____/~~~~~~~\____
  //            SDA  =================        probkowanie w Q2, srodek wysokiego SCL
  //  STOP      SCL  ____/~~~~~~~~~~~~
  //            SDA  \____________/~~~
  // -------------------------------------------------------------------
  val table : Seq[(SpinalEnumElement[I2cPhyCmdMode.type], Seq[Quarter])] = Seq(

    START -> Seq(
      //      SCL,      SDA
      Quarter(Keep,     Release ),   // Q0 SDA w gore; SCL bez zmian (RESTART!)
      Quarter(Release,  Release ),   // Q1 SCL w gore, obie linie jalowe
      Quarter(Release,  PullDown),   // Q2 opadajace SDA przy wysokim SCL = START
      Quarter(PullDown, PullDown)),  // Q3 SCL w dol -> inwariant spelniony

    BIT -> Seq(
      Quarter(PullDown, FromCmd ),                 // Q0 setup danych przy niskim SCL
      Quarter(Release,  FromCmd ),                 // Q1 SCL w gore
      Quarter(Release,  FromCmd, sample = true),   // Q2 srodek wysokiego SCL -> rsp
      Quarter(PullDown, FromCmd )),                // Q3 SCL w dol

    STOP -> Seq(
      Quarter(PullDown, PullDown),   // Q0 SDA w dol przy niskim SCL
      Quarter(Release,  PullDown),   // Q1 SCL w gore
      Quarter(Release,  PullDown),   // Q2 hold, setup time
      Quarter(Release,  Release ))   // Q3 narastajace SDA przy wysokim SCL = STOP
  )

  val quarterCount = table.head._2.size
  require(table.forall(_._2.size == quarterCount),
          "Wszystkie komendy musza miec tyle samo cwiartek - inaczej licznik faz przestaje wystarczac")

  val seq = new Area {
    val active = RegInit(False)
    val phase  = Reg(UInt(log2Up(quarterCount) bits)) init (0)

    // Jedna cwiartka -> przypisania do rejestrow linii.
    def applyQuarter(q : Quarter) : Unit = {
      q.scl match {
        case Keep     => ()
        case Release  => sclReg := True
        case PullDown => sclReg := False
        case FromCmd  => SpinalError("SCL nie moze byc sterowane danymi")
      }
      q.sda match {
        case Keep     => ()
        case Release  => sdaReg := True
        case PullDown => sdaReg := False
        case FromCmd  => sdaReg := io.cmd.data
      }
      if (q.sample) io.rsp.valid := True
    }

    // Cwiartka nr i, wybrana trybem z payloadu. Payload jest stabilny
    // przez caly czas valid && !ready (kontrakt Stream'a), wiec nie ma
    // po co rejestrowac ani mode ani data.
    def applyQuarterOf(i : Int) : Unit = {
      switch(io.cmd.mode) {
        for ((mode, quarters) <- table) {
          is(mode) { applyQuarter(quarters(i)) }
        }
      }
    }

    when(!active) {
      when(io.cmd.valid) {
        active := True
        phase  := 0
        timer.restart()
        applyQuarterOf(0)          // wejscie w Q0 rownoczesne z ustawieniem phase
      }
    } otherwise {
      when(timer.done && !stretching) {
        timer.restart()
        phase := phase + 1
        switch(phase) {
          // konczy sie cwiartka i-1, wiec wjezdzaja poziomy cwiartki i
          for (i <- 1 until quarterCount) {
            is(i - 1) { applyQuarterOf(i) }
          }
          is(quarterCount - 1) {
            io.cmd.ready := True
            active       := False
          }
        }
      }
    }
  }

  // Dopoki slave przytrzymuje SCL, cwiartka nie zaczyna sie liczyc.
  // Ostatnie przypisanie wygrywa, wiec to musi byc PO bloku seq.
  when(stretching) { timer.restart() }
}

object I2cPhyTableVerilog extends App {
  SpinalConfig(
    targetDirectory             = "hw/gen/verilog",
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    anonymSignalUniqueness      = true
  ).generateVerilog(
    InOutWrapper(I2cPhyTable(I2cGenerics(clkFrequency = 100 MHz)))
  )
}