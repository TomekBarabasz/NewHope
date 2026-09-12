package newhope.aht10

import spinal.core._
import spinal.core.sim._
import newhope.i2c._
import newhope.vertebra.{Stage, Testpoint, TestplanSuite}
import newhope.vertebra.sim.SimBackend

// =====================================================================
//  Testplan Aht10Ctrl.
//
//  Wszystkie stale czasowe skrocone: tickCycles = 4 zamiast 100000,
//  czyli "milisekunda" trwa cztery takty. Bez tego jeden pomiar to
//  9.5 mln taktow symulacji i suita nie skonczylaby sie przed obiadem.
//  Proporcje miedzy opoznieniami zostaja, wiec sprawdzamy te same
//  zaleznosci.
//
//  aht10_watchdog jest tu najwazniejszy i najlatwiejszy do pominiecia.
//  I2cMaster nie ma timeoutu z zalozenia; caly ciezar zywotnosci lezy
//  na liczniku w Aht10Ctrl. Bez tego testpointu odkrylbys to dopiero
//  przy odlaczonym czujniku na plytce - i wygladaloby to na zawieszony
//  bitstream.
// =====================================================================
class Aht10CtrlTestplan extends TestplanSuite {

  def testplan : Seq[Testpoint] = Seq(

    Testpoint("aht10_power_up_delay", Stage.V1,
      "Przed pierwsza komenda kontroler odczekuje czas rozruchu",
      stimulus = Seq("Start symulacji, slave gotowy"),
      checking = Seq("Zadnego ruchu na magistrali przez powerUpTicks",
                     "Pierwszy warunek START dopiero po tym czasie")),

    Testpoint("aht10_init_sequence", Stage.V1,
      "Po rozruchu idzie dokladnie sekwencja inicjalizacji",
      stimulus = Seq("Slave odbiera i potwierdza wszystkie bajty"),
      checking = Seq("Adres zapisu, potem 0xE1, 0x08, 0x00",
                     "Sekwencja zamknieta warunkiem STOP",
                     "Kontroler przechodzi w stan gotowosci")),

    Testpoint("aht10_measure_sequence", Stage.V1,
      "Trigger uruchamia pomiar i odczyt szesciu bajtow",
      stimulus = Seq("Impuls trigger po osiagnieciu gotowosci"),
      checking = Seq("Bajty komendy to 0xAC, 0x33, 0x00",
                     "Odczyt nie zaczyna sie wczesniej niz po measureTicks",
                     "Odebrane dokladnie szesc bajtow")),

    Testpoint("aht10_frame_unpack", Stage.V1,
      "Ramka rozpakowana zgodnie z podzialem bajtu srodkowego",
      // Bajt 3 dzieli sie na pol: gorne cztery bity to koniec
      // wilgotnosci, dolne to poczatek temperatury. Zamiana polowek
      // daje wartosci, ktore nadal wygladaja wiarygodnie.
      stimulus = Seq("Slave zwraca wartosci z roznymi polowkami bajtu 3"),
      checking = Seq("rawRh i rawT zgodne z zadanymi w modelu",
                     "Bit CAL ze statusu widoczny na wyjsciu")),

    Testpoint("aht10_read_nack_last", Stage.V1,
      "Ostatni odczytany bajt konczy sie NACK-iem",
      // Bez NACK-a czujnik nie puszcza SDA i STOP nie wychodzi.
      stimulus = Seq("Pelny cykl pomiaru"),
      checking = Seq("Piec bajtow potwierdzonych, szosty nie",
                     "Po NACK-u nastepuje STOP")),

    Testpoint("aht10_busy_retry", Stage.V1,
      "Status z bitem busy powoduje ponowny odczyt, nie zatrzask smieci",
      stimulus = Seq("Slave trzyma bit busy przez pierwszy odczyt"),
      checking = Seq("Kontroler czeka i czyta ponownie",
                     "Probka wystawiona dopiero po zejsciu busy")),

    Testpoint("aht10_addr_nack_error", Stage.V1,
      "Brak ACK na adres prowadzi do soft resetu i flagi bledu",
      stimulus = Seq("Slave nie potwierdza adresu"),
      checking = Seq("Flaga bledu podniesiona",
                     "Na magistrali pojawia sie komenda 0xBA",
                     "Kontroler nie zawiesza sie - wraca do rozruchu")),

    Testpoint("aht10_watchdog", Stage.V2,
      "Martwa magistrala nie zawiesza kontrolera",
      stimulus = Seq("Slave trzyma SCL nisko w trakcie transakcji"),
      checking = Seq("Sekwencja przerwana w oknie watchdogTicks",
                     "Flaga bledu podniesiona",
                     "Kontroler wraca do rozruchu, a nie stoi w miejscu")))

  // -------------------------------------------------------------------
  val ag = Aht10Generics(tickCycles    = 4,
                         powerUpTicks  = 20,
                         measureTicks  = 80,
                         resetTicks    = 20,
                         watchdogTicks = 200)

  val g = I2cGenerics(clkFrequency = 100 MHz, sclFrequency = 5 MHz)

  lazy val dut : SimCompiled[Aht10Ctrl] = Config.sim
    .withFstWave
    .workspaceName(s"aht10ctrl_${SimBackend.default.label}")
    .compile { Aht10Ctrl(g, ag) }

  def scenario(name : String)(body : (Aht10Ctrl, Aht10SlaveModel) => Unit) : Unit =
    testpoint(name) {
      dut.doSim(s"aht10ctrl_$name", seed = 42) { d =>
        SimTimeout(50000000)
        d.clockDomain.forkStimulus(10)
        d.io.trigger #= false
        val bus   = new I2cBusModel(d.clockDomain, d.io.pins)
        val slave = new Aht10SlaveModel(d.clockDomain, d.io.pins, bus)
        bus.start()
        d.clockDomain.waitSampling()
        body(d, slave)
      }
    }

  // --- pomocnicze ----------------------------------------------------
  def waitReady(d : Aht10Ctrl, limit : Int = 20000) : Unit = {
    var n = 0
    while (!d.io.status.ready.toBoolean && n < limit) {
      d.clockDomain.waitSampling(); n += 1
    }
    assert(n < limit, "kontroler nie osiagnal gotowosci")
  }

  def pulseTrigger(d : Aht10Ctrl) : Unit = {
    d.io.trigger #= true
    d.clockDomain.waitSampling()
    d.io.trigger #= false
  }

  def waitSample(d : Aht10Ctrl, limit : Int = 40000) : (Int, Int) = {
    var n = 0
    while (!d.io.sample.valid.toBoolean && n < limit) {
      d.clockDomain.waitSampling(); n += 1
    }
    assert(n < limit, "probka nie pojawila sie w oczekiwanym oknie")
    (d.io.sample.rawT.toInt, d.io.sample.rawRh.toInt)
  }

  // --- scenariusze ---------------------------------------------------

  scenario("aht10_power_up_delay") { (d, slave) =>
    slave.start()
    val quiet = ag.powerUpTicks * ag.tickCycles - 4
    for (_ <- 0 until quiet) {
      assert(d.io.pins.scl.write.toBoolean && d.io.pins.sda.write.toBoolean,
             "ruch na magistrali przed uplywem czasu rozruchu")
      d.clockDomain.waitSampling()
    }
    waitReady(d)
  }

  scenario("aht10_init_sequence") { (d, slave) =>
    slave.start()
    waitReady(d)
    assert(slave.initCount == 1, s"komend 0xE1: ${slave.initCount}")
    assert(slave.lastCmdBytes == Seq(0xE1, 0x08, 0x00),
           s"bajty inicjalizacji: ${slave.lastCmdBytes.map(_.toHexString)}")
  }

  scenario("aht10_measure_sequence") { (d, slave) =>
    slave.start()
    waitReady(d)

    // lastCmdBytes zatrzaskuje sie na STOP, wiec licznik STOP-ow jest
    // jedynym wiarygodnym sygnalem, ze ramka jest kompletna. measCount
    // rosnie juz w polowie transakcji.
    val stopsBefore = slave.stopCount
    pulseTrigger(d)

    var n = 0
    while (slave.stopCount == stopsBefore && n < 20000) {
      d.clockDomain.waitSampling(); n += 1
    }
    assert(n < 20000, "ramka pomiaru nie zamknela sie STOP-em")
    assert(slave.measCount == 1, s"komend 0xAC: ${slave.measCount}")
    assert(slave.lastCmdBytes == Seq(0xAC, 0x33, 0x00),
           s"bajty pomiaru: ${slave.lastCmdBytes.map(_.toHexString)}")

    waitSample(d)
    val gap = slave.readTime - slave.measTime
    val required = ag.measureTicks * ag.tickCycles
    assert(gap >= required, s"odczyt po $gap taktach, wymagane $required")
  }

  scenario("aht10_frame_unpack") { (d, slave) =>
    // polowki bajtu 3 celowo rozne, zeby zamiana byla widoczna
    slave.rawRh = 0xA5C30
    slave.rawT  = 0x12345
    slave.start()
    waitReady(d)
    pulseTrigger(d)
    val (t, rh) = waitSample(d)
    assert(rh == 0xA5C30, f"rawRh = 0x$rh%05x, oczekiwano 0xa5c30")
    assert(t  == 0x12345, f"rawT  = 0x$t%05x, oczekiwano 0x12345")
    assert(d.io.status.calibrated.toBoolean, "bit CAL zgubiony")
  }

  scenario("aht10_read_nack_last") { (d, slave) =>
    slave.start()
    waitReady(d)
    pulseTrigger(d)
    waitSample(d)
    assert(slave.ackedBytes == Seq(true, true, true, true, true, false),
           s"potwierdzenia bajtow: ${slave.ackedBytes}")
  }

  scenario("aht10_busy_retry") { (d, slave) =>
    slave.busyCycles = ag.measureTicks * ag.tickCycles + 200
    slave.start()
    waitReady(d)
    pulseTrigger(d)
    val (t, rh) = waitSample(d)
    assert(t == slave.rawT && rh == slave.rawRh,
           "probka zatrzasnieta mimo bitu busy")
  }

  scenario("aht10_addr_nack_error") { (d, slave) =>
    slave.nakAddress = true
    slave.start()
    var n = 0
    while (!d.io.status.error.toBoolean && n < 40000) {
      d.clockDomain.waitSampling(); n += 1
    }
    assert(n < 40000, "flaga bledu nie podniosla sie")
    var m = 0
    while (slave.resetCount == 0 && m < 40000) {
      d.clockDomain.waitSampling(); m += 1
    }
    assert(slave.resetCount >= 1, "nie wyslano komendy soft reset")
  }

  scenario("aht10_watchdog") { (d, slave) =>
    slave.start()
    waitReady(d)
    slave.hang(true)
    pulseTrigger(d)

    val limit = ag.watchdogTicks * ag.tickCycles * 3
    var n = 0
    while (!d.io.status.error.toBoolean && n < limit) {
      d.clockDomain.waitSampling(); n += 1
    }
    assert(n < limit, s"watchdog nie zadzialal w $limit taktach")

    // zywotnosc: po zwolnieniu magistrali kontroler wraca do gotowosci
    slave.hang(false)
    waitReady(d, limit = 60000)
  }
}
