# Strategia weryfikacji (vertebra)

Ten dokument opisuje, jak weryfikujemy IP pisane w SpinalHDL i jak zacząć
weryfikację w nowym projekcie. Dotyczy każdego IP; szczegóły konkretnego bloku
żyją w jego testplanie, nie tutaj.

## 1. Założenie

Weryfikacja sprzętu ma dojrzałe praktyki: testplan pisany przed testami,
podział na etapy, modele magistrali, monitory, scoreboardy, pokrycie
parametrów, raport z tym, czego jeszcze nie przetestowano. Te praktyki są
jednak zamknięte w SystemVerilogu i UVM-ie, a duża część UVM-a to maszyneria
obchodząca braki języka: fabryka zamiast parametrów konstruktora,
`config_db` zamiast zwykłego przekazywania wartości, `bind` zamiast dostępu do
hierarchii w czasie elaboracji, makra zamiast funkcji wyższego rzędu.

Scala tych braków nie ma. Vertebra przenosi **praktyki**, a **maszynerię
odrzuca**. Jeśli coś w UVM-ie istnieje tylko po to, żeby obejść SystemVerilog,
u nas tego nie ma i nie należy tego odtwarzać.

### Mapowanie pojęć

| UVM / OpenTitan                         | vertebra                                                                  |
|-----------------------------------------|---------------------------------------------------------------------------|
| `*_testplan.hjson`                      | `Seq[Testpoint]` w kodzie, sprawdzany przez kompilator                    |
| `hw/dv/tools/dvsim/testplans/*.hjson`   | wspólne testpointy, np. `StreamConformance.testpoints(port)`              |
| etapy V1/V2/V3                          | `Stage.V1/V2/V3`, V1 jest bramką w teście `testplan completeness`         |
| „No Tests Implemented”                  | `unimplemented(name, reason)` → test `pending`, nie liczy się do kompletności |
| `uvm_sequence`, `uvm_sequence_item`     | zwykłe funkcje Scali (`cmd`, `byte`) i zwykłe `Seq`                       |
| driver / responder agenta               | model magistrali na pinach (`*BusModel`) z `fork`                         |
| `uvm_monitor`                           | monitor dekodujący piny do zdarzeń (`*Monitor`)                           |
| scoreboard                              | lista oczekiwanych zdarzeń + `mon.expect(...)`                            |
| SVA / checkery                          | checkery w `fork` (`StreamConformance.payloadStable` itd.)                |
| `bind`                                  | `Instrument.stream(...)` w czasie elaboracji                              |
| fabryka, `config_db`                    | parametr konstruktora suity: `build : G => Dut`                           |
| covergroup na parametrach               | jawna lista `configs`, łącznie z przypadkami brzegowymi                   |
| `uvm_info`                              | `info(...)` ScalaTesta                                                    |

## 2. Pliki w nowym projekcie

### 2.1. Biblioteka (współdzielona, nie kopiowana)

Vertebra powinna być osobnym podprojektem sbt (albo zależnością), a nie
plikami kopiowanymi między repozytoriami. Kopiowanie oznacza rozjeżdżające się
wersje `TestplanSuite` i zgubione poprawki.

```
vertebra/
  src/main/scala/newhope/vertebra/
    Testplan.scala          (newhope.vertebra) Stage, Testpoint, TestplanSuite,
                            Instrument, StreamPortHandle, StreamConformance
    sim/SimBackend.scala    (newhope.vertebra.sim) wybór symulatora (verilator/ghdl/...), .label
    Config.scala            wspólny SpinalConfig/SimConfig (Config.sim)
```

W `build.sbt` projektu:

```scala
lazy val vertebra = ProjectRef(file("../vertebra"), "vertebra")  // albo libraryDependencies
lazy val root = (project in file(".")).dependsOn(vertebra % "compile->compile;test->compile")
```

### 2.2. Pliki na każde IP

Dla IP `foo` (przykład: `i2c`):

```
src/main/scala/newhope/foo/
  FooGenerics.scala       case class z parametrami, wartościami pochodnymi
                          i predykatami legalności (patrz §4.3)
  Foo*.scala              implementacje DUT-a (może być kilka, patrz §4.5)

src/test/scala/newhope/foo/
  FooEvent.scala          ADT zdarzeń na poziomie transakcji (Start, Bit, Stop...)
  FooBusModel.scala       model drugiej strony magistrali na pinach
  FooMonitor.scala        dekoder pinów → zdarzenia, expect(...), pomiary timingu
  FooTestplan.scala       plan (Seq[Testpoint]) + suita testów
  FooSweep.scala          (opcjonalnie) charakteryzacja, wyjście do CSV
```

| Plik              | Obowiązkowy | Po co                                                        |
|-------------------|-------------|--------------------------------------------------------------|
| `FooGenerics`     | tak         | jedyne źródło prawdy o parametrach i ich ograniczeniach      |
| `FooEvent`        | tak         | porównujemy transakcje, nie przebiegi cykl po cyklu          |
| `FooBusModel`     | tak         | stymulacja na pinach, wstrzykiwanie zakłóceń (glitch, stretch) |
| `FooMonitor`      | tak         | niezależne od DUT-a odczytanie tego, co poszło na magistralę |
| `FooTestplan`     | tak         | plan i testy w jednym miejscu, kompletność liczona automatycznie |
| `FooSweep`        | nie         | gdy oczekiwane wartości trzeba zmierzyć, zanim da się je zapisać jako asercję |

W katalogu głównym repozytorium: ten plik (`TESTING-STRATEGY.md`). Osobnego
dokumentu z testplanem nie ma: testplanem jest kod.

## 3. Kolejność pracy

1. **Znajdź istniejący plan.** Jeśli dla tego typu IP istnieje testplan
   OpenTitana (albo inny publiczny), zacznij od niego. Nazwy testpointów
   bierz **bez zmian**, żeby dało się wrócić do źródła po szczegóły. Podaj
   źródło i licencję w nagłówku pliku.
2. **Odrzuć jawnie.** W nagłówku wypisz testpointy, które nie mają
   zastosowania, z powodem (np. `csr_*`, `tl_*` – nie mamy rejestrów;
   `target_*` – jesteśmy tylko masterem).
3. **Zapisz plan w kodzie**, zanim napiszesz pierwszy test. Każdy testpoint
   dostaje etap, opis, bodźce i sposób sprawdzania.
4. **Dołącz wspólne testpointy** dla każdego portu `Stream`:
   `StreamConformance.testpoints("cmd")`.
5. **Zrealizuj V1.** Dopóki V1 nie jest kompletny, test `testplan
   completeness` jest czerwony. To celowe.
6. **Znane luki oznacz `unimplemented`**, nie usuwaj ich z planu. Żółty wpis
   w raporcie ma być widoczny.
7. **V2, V3.**

## 4. Zasady

### 4.1. Testpoint to nazwa z planu, nie dowolny string

`testpoint("host_smoke")` rzuca wyjątkiem, jeśli takiej nazwy nie ma w planie.
Literówka nie tworzy „nowego testu”, który po cichu nic nie pokrywa.

### 4.2. Parametryzacja po konfiguracjach

Suita iteruje po jawnej liście konfiguracji. Lista **musi** zawierać:

- typowe punkty pracy (dla I2C: 100 kHz, 400 kHz, 1 MHz),
- **dolną granicę** parametru krytycznego dla timingu (dla I2C: najmniejsze
  `quarterCycles`, przy którym konstrukcja jeszcze działa),
- wartości dające się policzyć **dokładnie w liczbach całkowitych**
  (np. 96 MHz / 8 MHz / 4 = 3). Konfiguracja, która wychodzi z zaokrąglenia
  zmiennoprzecinkowego, testuje zaokrąglenie, a nie DUT-a.

Jeżeli konfiguracja brzegowa wymaga wyjątków w poszczególnych testach, to
zazwyczaj jest zła konfiguracja, a nie złe testy. Przesuń ją o krok do punktu,
w którym żaden wyjątek nie jest potrzebny, i zapisz w komentarzu dlaczego.

### 4.3. Ograniczenia parametrów sprawdzaj bez symulacji

Część własności konstrukcji wynika z samych parametrów, np. „opóźnienie
filtra (`filterWindow + 3`) nie może przekroczyć półokresu SCL”. Taka
własność:

- jest predykatem w `FooGenerics` (`filterTracksScl`, `filterLatency`),
- ma własny test, który **niczego nie kompiluje** i przechodzi przez
  `configs`, wypisując zapasy przez `info(...)`,
- obejmuje też **testbench**: monitor ma własne okno filtra i własną granicę,
  poniżej której przestaje widzieć zbocza. Objawy ślepego monitora są mylące
  (np. `Start, Stop, Start, Stop` bez żadnego `Bit`), więc tę granicę też
  sprawdzamy asercją na parametrach.

Wartości progowe kalibruje się narzędziem charakteryzacji (`FooSweep`),
a w komentarzu testu zapisuje się, jak je odtworzyć (polecenie, siatka,
backend).

### 4.4. Oczekiwania liczbowe z uzasadnieniem

Każda asercja na timingu ma w komentarzu wzór, z którego wynika oczekiwana
wartość, oraz jawną tolerancję. Przykład z I2C: `tHIGH` to dwie ćwiartki plus
fałszywy stretching w Q1 (opóźnienie filtra plus cykl rejestru). Liczba bez
wzoru to liczba skopiowana z przebiegu, która przestanie się zgadzać przy
pierwszej zmianie parametrów.

### 4.5. Wiele implementacji tej samej specyfikacji

Jeśli IP ma kilka implementacji (np. `I2cPhyFsm` i `I2cPhyTable`), suita jest
**abstrakcyjna** i bierze funkcję budującą DUT-a:

```scala
abstract class FooTestplan(label : String, build : FooGenerics => FooBase) extends TestplanSuite
class FooFsmTestplan   extends FooTestplan("fsm",   g => FooFsm(g))
class FooTableTestplan extends FooTestplan("table", g => FooTable(g))
```

Wszystkie implementacje przechodzą dokładnie ten sam plan. Do tego służy
wspólna klasa bazowa (`FooBase`) z identycznym `io`.

### 4.6. Determinizm

- Każdy `doSim` ma stały `seed`.
- Każde losowanie w teście idzie przez `new Random(n)` ze stałym `n`,
  różnym dla różnych scenariuszy.
- Nazwa workspace zawiera etykietę implementacji, konfigurację i backend
  (`s"${label}_${cfg}_${SimBackend.default.label}"`), a nazwa symulacji
  dodatkowo scenariusz. Dzięki temu przebiegi (`withFstWave`) się nie
  nadpisują i łatwo trafić do właściwego pliku.
- DUT kompilujemy raz na konfigurację (`lazy val dut`), nie raz na test.

### 4.7. Porównujemy transakcje, nie cykle

Scoreboard to lista oczekiwanych zdarzeń budowana równolegle ze stymulacją
(`sent += Start`, `sent ++= bity`, `sent += Stop`) i porównanie
`mon.expect(sent : _*)` na końcu. Timing sprawdzają osobne, nazwane asercje
(§4.4). Dzięki temu test protokołu nie pęka przy zmianie timingu i odwrotnie.

### 4.8. Zakłócenia wstrzykuje model magistrali

Stretching, glitche, kolizje: to robota `FooBusModel`, uruchamiana w `fork`
w scenariuszu. Zakłócenia mają być asynchroniczne względem zegara DUT-a tam,
gdzie specyfikacja tego wymaga (np. losowa długość stretchingu).

### 4.9. Kontrakt portów `Stream`

Żeby checkery `StreamConformance` widziały payload jako jedną wartość, DUT
musi go wystawić **w czasie elaboracji**, wewnątrz komponentu:

```scala
// w ciele komponentu (albo w rework)
Instrument.stream(io.cmd, "cmd")
```

W teście:

```scala
val cmdPort = StreamPortHandle(
  valid   = d.io.cmd.valid,
  ready   = d.io.cmd.ready,
  payload = d.cmd_payload_bits,    // nazwa nadana przez Instrument
  isInput = true,
  name    = "cmd")
StreamConformance.all(d.clockDomain, cmdPort)
```

Dopóki IP nie woła `Instrument`, testpointów `StreamConformance` nie dołączaj
do planu, bo nie da się ich zrealizować. Odnotuj to w nagłówku testplanu.

## 5. Szablon

```scala
package newhope.foo

import spinal.core._
import spinal.core.sim._
import scala.util.Random
import newhope.vertebra._
import newhope.vertebra.sim.SimBackend

// Źródło: <link do testplanu>, <licencja>. Nazwy testpointów bez zmian.
// Odrzucone jako nieaplikowalne: csr_*, tl_*, ... (powód)
object FooPlan {
  val plan : Seq[Testpoint] = Seq(
    Testpoint("foo_smoke", Stage.V1,
      "Losowe transakcje, scoreboard na monitorze",
      stimulus = Seq("8 losowych ramek, 1-3 bajty"),
      checking = Seq("Sekwencja zdarzeń z monitora == wysłana")),
    Testpoint("foo_timing", Stage.V2,
      "Timing magistrali zgodny z konfiguracją",
      checking = Seq("tHIGH, tLOW w tolerancji")),
    Testpoint("foo_param_bounds", Stage.V1,
      "Wszystkie konfiguracje spełniają ograniczenia DUT-a i testbenchu"),
    Testpoint("foo_timeout", Stage.V2,
      "Timeout na zablokowanej magistrali")
  ) ++ StreamConformance.testpoints("cmd")

  case class Cfg(name : String, g : FooGenerics)
  val configs = Seq(
    Cfg("typ", FooGenerics(100 MHz, 1 MHz)),
    Cfg("min", FooGenerics( 96 MHz, 8 MHz))   // dolna granica, liczona dokładnie
  )
}

abstract class FooTestplan(label : String, build : FooGenerics => FooBase)
    extends TestplanSuite {
  import FooPlan._
  override def testplan = plan

  for (Cfg(cfgName, g) <- configs) {
    lazy val dut = Config.sim.withFstWave
      .workspaceName(s"${label}_${cfgName}_${SimBackend.default.label}")
      .compile { build(g) }

    def scenario(tp : String)(body : (FooBase, FooBusModel, FooMonitor) => Unit) =
      testpoint(tp, variant = s"$label/$cfgName") {
        dut.doSim(s"${label}_${cfgName}_$tp", seed = 42) { d =>
          val bus = new FooBusModel(d)
          val mon = new FooMonitor(d, bus)
          // wartości początkowe wejść, zegar, start modeli
          d.clockDomain.forkStimulus(period = 10)
          bus.start(); mon.start()
          d.clockDomain.waitSampling(5)
          body(d, bus, mon)
        }
      }

    scenario("foo_smoke") { (d, bus, mon) =>
      val rng = new Random(1)
      // stymulacja + budowa listy oczekiwanych zdarzeń
      // mon.expect(...)
    }
  }

  // Bez symulacji: tylko parametry.
  testpoint("foo_param_bounds") {
    configs.foreach(c => info(s"${c.name}: zapas = ${c.g.margin}"))
    val bad = configs.filterNot(_.g.isLegal)
    assert(bad.isEmpty, s"nielegalne: ${bad.map(_.name).mkString(", ")}")
  }

  unimplemented("foo_timeout", "DUT nie ma jeszcze timeoutu")
}

class FooFsmTestplan extends FooTestplan("fsm", g => FooFsm(g))
```

Uwaga: `testpoint` wywołany w pętli po konfiguracjach i implementacjach
rejestruje jeden testpoint wielokrotnie; `variant` zapewnia unikalne nazwy
w ScalaTeście, a kompletność liczy się po samej nazwie testpointu.

## 6. Uruchamianie

```
sbt "testOnly *FooFsmTestplan"               # jedna implementacja
sbt "testOnly *FooTestplan* -- -z min"       # jedna konfiguracja
sbt "testOnly *FooTestplan* -- -z param"     # tylko testy parametrów (bez kompilacji)
```

W raporcie ScalaTesta testy mają prefiks etapu (`[V1] foo_smoke (fsm/typ)`),
testy `pending` to znane luki, a `testplan completeness` wypisuje
`zaimplementowane: n/m` i listę braków.

## 7. Checklista dla nowego IP

- [ ] Źródło planu i licencja w nagłówku, lista odrzuconych z powodami
- [ ] `FooGenerics` z predykatami legalności i wartościami pochodnymi
- [ ] Plan w kodzie, zanim powstanie pierwszy test
- [ ] Wspólne testpointy `StreamConformance` dla każdego portu `Stream`
      (albo notka, dlaczego jeszcze nie)
- [ ] `configs` z dolną granicą i wartościami całkowitymi
- [ ] Test parametrów bez symulacji, obejmujący też ograniczenia testbenchu
- [ ] Każda asercja timingu ma wzór i tolerancję w komentarzu
- [ ] Stałe seedy, unikalne nazwy workspace i przebiegów
- [ ] V1 kompletny, znane luki jako `unimplemented`

## 8. Stan obecny i długi do spłacenia

Suita I2C (`I2cPhyTestplan`) jest pierwszym użytkownikiem `TestplanSuite`
i wzorcem dla kolejnych IP. Pakiety są ujednolicone: `newhope.vertebra`
i `newhope.vertebra.sim`.

Otwarty punkt: komentarz przy `Instrument` w `Testplan.scala` odsyła do
notki w `I2cPhyTestplan`, której tam nie ma. `I2cPhy` nie woła `Instrument`,
więc testpointy `StreamConformance` dla portu `cmd` nie są dołączone do planu.
Trzeba albo dodać instrumentację i testpointy, albo dopisać notkę
w nagłówku `I2cPhyTestplan`.
