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
| agent z własnym zegarem (async)         | model w czasie symulacji (`sleep`), nie na zegarze DUT-a (§4.11)          |
| `uvm_monitor`                           | monitor dekodujący piny do zdarzeń (`*Monitor`)                           |
| scoreboard                              | lista oczekiwanych zdarzeń + `mon.expect(...)`                            |
| czyszczenie scoreboardu w trakcie testu | bariera `resync()` z dala od granicy transakcji (§4.12)                   |
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
    sim/SimEnv.scala        (newhope.vertebra.sim) SimBackend: wybór symulatora
                            (verilator/ghdl), .label; SimEnv: SimConfig z falami
```

W `build.sbt` projektu:

```scala
lazy val vertebra = ProjectRef(file("../vertebra"), "vertebra")  // albo libraryDependencies
lazy val i2s = (project in file("i2s")).dependsOn(vertebra).settings(hwSettings)
```

Samo `dependsOn(vertebra)` wystarcza: konfiguracja `test` dziedziczy
classpath z `compile`, więc testy widzą `TestplanSuite` bez
`"test->compile"`.

`Config` nie należy do vertebry: każdy moduł sprzętowy ma własny (§2.3).
Vertebra daje tylko to, co wspólne dla symulacji: `SimEnv` i `SimBackend`.

### 2.2. Pliki na każde IP

Dla IP `foo` (przykład: `i2c`):

```
src/main/scala/newhope/foo/
  Config.scala            SpinalConfig modułu (Config.spinal) + Config.sim
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
| `Config`          | tak         | jak moduł generuje Verilog i jak jest symulowany (§2.3)      |
| `FooGenerics`     | tak         | jedyne źródło prawdy o parametrach i ich ograniczeniach      |
| `FooEvent`        | tak         | porównujemy transakcje, nie przebiegi cykl po cyklu          |
| `FooBusModel`     | tak         | stymulacja na pinach, wstrzykiwanie zakłóceń (glitch, stretch, jitter) |
| `FooMonitor`      | tak*        | niezależne od DUT-a odczytanie tego, co poszło na magistralę |
| `FooTestplan`     | tak         | plan i testy w jednym miejscu, kompletność liczona automatycznie |
| `FooSweep`        | nie         | gdy oczekiwane wartości trzeba zmierzyć, zanim da się je zapisać jako asercję |

\* Gdy model sam generuje timing magistrali (np. `I2sBusMaster` dla slave'a
I2S), może też dekodować odpowiedź DUT-a – wie, do której pozycji należy
każda próbka, więc nie musi niczego odgadywać z pinów. Osobny monitor jest
wtedy zbędny.

### 2.3. `Config` w każdym module

`Config.spinal` to nie tylko testy: decyduje o generacji Veriloga
(`targetDirectory`, polaryzacja resetu, typy portów na topie). To decyzja
modułu, nie biblioteki weryfikacji, dlatego `Config` żyje w module, w jego
własnym pakiecie (`newhope.foo.Config`). Testplan w tym samym pakiecie widzi
go bez importu, a moduł zależny od kilku IP nie ma kolizji nazw.

Część wspólna jest w vertebrze i nie jest kopiowana: `Config.sim` to tylko
`SimEnv(spinal).withBackend(SimBackend.default)`. W module zostaje więc sam
`SpinalConfig` i dwie jednolinijkowe metody:

```scala
package newhope.foo

import spinal.core._
import newhope.vertebra.sim.{SimEnv, SimBackend}

object Config {
  import newhope.vertebra.sim.SimBackendOps._

  // Reset aktywny wysoko: wymagają tego testpointy z resetem w trakcie
  // pracy (*_reset_quiet, *_random_reset). Zmiana tu zmienia ich sens.
  def spinal = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(resetActiveLevel = HIGH),
    onlyStdLogicVectorAtTopLevelIo = false
  )

  // MUSI być def: withBackend mutuje SpinalSimConfig w miejscu.
  def simFor(b : SimBackend) = SimEnv(spinal).withBackend(b)
  def sim = simFor(SimBackend.default)
}
```

Ryzyko tego układu to ciche rozjechanie się modułów, np. testy na domyślnym
`SpinalConfig()` z inną polaryzacją resetu niż w syntezie. Dlatego każdy
`Config` ma komentarz, dlaczego jego ustawienia są takie, a nie inne.

W katalogu głównym repozytorium: ten plik (`TESTING-STRATEGY.md`). Osobnego
dokumentu z testplanem nie ma: testplanem jest kod.

## 3. Kolejność pracy

1. **Znajdź istniejący plan.** Jeśli dla tego typu IP istnieje testplan
   OpenTitana (albo inny publiczny), zacznij od niego. Nazwy testpointów
   bierz **bez zmian**, żeby dało się wrócić do źródła po szczegóły. Podaj
   źródło i licencję w nagłówku pliku.

   uvmdvgen nie jest źródłem testpointów: generuje szkielet z `smoke` i
   importem wspólnych planów (csr/mem/intr/tl). Testpointy I2C pochodzą
   z `hw/ip/i2c/data/i2c_testplan.hjson`, nie z generatora.

   **Gdy planu nie ma** (np. I2S – nie ma go w OpenTitanie, a plany
   komercyjnych VIP-ów są zamknięte): nazwy są nasze, z prefiksem IP
   (`i2s_`, `slv_`), a wymagania bierzemy ze specyfikacji protokołu. Każdy
   testpoint mówi w `checking`, z której własności specyfikacji wynika.
   Potem **drugi obieg**: publiczne testbenche i przykłady producentów.
   Zwykle nie mają asercji ani planu, więc bierzemy z nich **pomysły na
   scenariusze**, a nie nazwy ani kod. W nagłówku planu wypisz każde
   źródło z tym, co z niego wzięto i co odrzucono.
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

Wyjątek: **interfejs asynchroniczny** (np. slave I2S, któremu zegar daje
magistrala). Tam celem testu jest właśnie niecałkowity stosunek zegarów.
Całkowite mają być *jednostki czasu symulacji* (półokres SCK 37 przy okresie
zegara 10), a ułamkowy stosunek jest zamierzony, żeby faza dryfowała.
Konfiguracja z fazą stałą (półokres = wielokrotność okresu) też musi być
na liście.

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

Granice testbenchu **wyprowadza się, a nie zgaduje**. Przy modelu kodeka
I2S próg `minHalfDiv` był dwa razy źle oszacowany na wyczucie; poprawna
wartość wyszła dopiero z rozpisania okna ważności bitu (od zbocza, na
którym model wystawia bit, do zbocza, na którym go podmienia) względem
zbocza próbkowania DUT-a. Wyprowadzenie ląduje w komentarzu stałej.

Dla wejść asynchronicznych granice liczy się dla **najgorszego przypadku**
(zbocze tuż po próbkowaniu, każdy stopień synchronizatora +1 cykl) i
zapisuje jako predykat w `FooGenerics` (`I2sSlaveGenerics.supportsSckHalf`).

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

Stretching, glitche, kolizje, jitter, zatrzymany zegar magistrali: to
robota `FooBusModel`, uruchamiana w `fork` w scenariuszu. Zakłócenia mają być
asynchroniczne względem zegara DUT-a tam, gdzie specyfikacja tego wymaga
(np. losowa długość stretchingu).

Model konfiguruje się funkcjami, nie stałymi: `lowLen : () => Int`,
`slotLen : () => Int`. Scenariusz podstawia losowanie albo licznik, który
raz zwraca długą pauzę – bez nowych metod w modelu na każdy przypadek.

Warianty dopuszczone przez specyfikację, ale inne niż domyślny (np. nadajnik
I2S taktowany narastającym zboczem, słowo innej długości niż odbiornik),
to flagi modelu. Takie warianty zawężają okno, które domyślny model zostawia
DUT-owi, i dlatego zasługują na własny testpoint.

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

Dla portu na **najwyższym poziomie** wystarczy `Instrument` w `rework`
w teście – nie trzeba go wołać w ciele komponentu.

Dla **portu wejściowego** (`valid` i payload steruje testbench)
`payload_stable` i `reset_quiet` sprawdzają głównie drivera. Część
skierowana w DUT-a to `noStall` z limitem wyprowadzonym z bufora DUT-a.
`backpressure` jest wtedy nieaplikowalne (ready steruje DUT) – oznacz je
`unimplemented` z odesłaniem do testpointów, które pokrywają wzorce po
stronie `valid`. `StreamPortHandle.isInput` na razie niczego nie zmienia (§8).

### 4.10. Semantyka czasu w symulacji

Wątek wznowiony przez `waitActiveEdge()` / `waitSampling()` czyta wartości
**sprzed** zbocza. To ta sama własność, dzięki której `StreamMonitor` widzi
`valid && ready` w chwili handshake'u – ale model, który na tej podstawie
„od razu” reaguje, reaguje w praktyce z opóźnieniem 2 cykli: zobaczy zmianę
dopiero na następnym zboczu, a DUT zobaczy jego odpowiedź na kolejnym.

Model, który ma odpowiadać szybciej, robi `sleep(outDelay)` po zboczu
(`outDelay` < okres zegara) i dopiero wtedy czyta piny. To modeluje jawne
opóźnienie wyjścia (t_d) zamiast artefaktu symulatora. Objaw pomyłki jest
mylący: przesunięcie o jeden bit tylko w jednym kierunku i tylko na
najszybszej konfiguracji, co wygląda jak błąd próbkowania w DUT-cie.

Zbocze sygnału z modelu w tej samej chwili co zbocze zegara DUT-a jest
dla wejść asynchronicznych dozwolone – każdy wynik próbkowania jest wtedy
fizycznie możliwy, a granice z §4.3 liczą najgorszy przypadek.

### 4.11. Modele interfejsów asynchronicznych

Gdy magistrala ma własny zegar (DUT jest slave'em), model działa w czasie
symulacji (`sleep`), a nie na zegarze DUT-a. Próbkuje odpowiedź DUT-a
**w chwili zbocza z protokołu** (np. narastający SCK), zanim to zbocze
wystawi. To jest prawdziwy warunek setupu; monitor próbkujący zegarem DUT-a
zobaczyłby poprawny bit później w fazie i przepuścił spóźniony DUT.

Oczekiwania, które zależą od parametrów magistrali zmiennych w trakcie
testu (długość slotu, szerokość słowa po każdej stronie), model zapisuje
**per transakcja** (`BusFrame(sent, wordWidth, lenL, lenR, received)`), a
scoreboard przelicza je jedną funkcją (`transfer`) – nie osobnymi
wyjątkami w każdym scenariuszu.

### 4.12. Scoreboard z kolejnością z DUT-a, bariera i `settle`

**Kolejność.** Jeśli DUT sam decyduje, kiedy przyjąć dane i kiedy wstawić
lukę (underrun), jeden wątek zapisuje w kolejności zdarzenia handshake i
luki (`txOrder : Seq[Option[Frame]]`), a monitor ma pokazać na pinach
dokładnie ten ciąg. Kolejność pochodzi z DUT-a, treść jest porównywana
niezależnie – jedno porównanie łapie zgubienie, dubel, mieszanie kanałów
i to, że luka jest ciszą, a nie powtórką. Oba źródła w **jednym** wątku,
żeby kolejność w obrębie cyklu była deterministyczna.

**Bariera `resync()`.** Czyszczenie scoreboardów w trakcie testu (po resecie,
między fazami) robi się z dala od granicy transakcji – dla I2S na początku
prawego slotu. Czyszczenie w cyklu granicy robi z kolejności wątków część
wyniku. Jeśli odpowiedź DUT-a jest opóźniona o transakcję (io.rx ramki k
strzela w ramce k+1), bariera zostawia w logu modelu transakcję w toku, a
monitor i `txOrder` zaczynają od następnej.

**`settle()`.** Jeśli model ma nieblokujące `send()`, `settle()` najpierw
czeka, aż kolejka modelu się opróżni, a dopiero potem liczy transakcje.
Liczenie od chwili wywołania kończy test przed ostatnimi danymi. Jeśli
`waitFrames` liczy starty transakcji w modelu, a granica widziana przez DUT
jest później (synchronizator, opóźnienie o bit), przerwy liczone w ten
sposób są o jedną transakcję krótsze – zapisz to w komentarzu przy liczbie.

### 4.13. Integracja dwóch prawdziwych IP

Gdy istnieją obie strony protokołu (master i slave I2S), osobna suita łączy
je na wspólnych liniach (`I2sPairTestplan`) z własnym, małym planem.
Nie zastępuje suit pojedynczych IP – tam model może być dowolnie złośliwy –
ale nie ma w niej modelu, który mógłby mieć ten sam błąd co DUT.
Konfiguracja pary ma zawierać dokładnie granicę wydajności słabszej strony.

## 5. Szablon

```scala
package newhope.foo

import spinal.core._
import spinal.core.sim._
import scala.util.Random
import newhope.vertebra._
import newhope.vertebra.sim.SimBackend
// UWAGA: `import spinal.lib._` wciąga pakiet spinal.lib.math i przesłania
// scala.math - w pliku z tym importem pisz scala.math.min / scala.math.max.

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
- [ ] `Config.scala` w module, z komentarzem o polaryzacji resetu (§2.3)
- [ ] `FooGenerics` z predykatami legalności i wartościami pochodnymi
- [ ] Plan w kodzie, zanim powstanie pierwszy test
- [ ] Wspólne testpointy `StreamConformance` dla każdego portu `Stream`
      (albo notka, dlaczego jeszcze nie)
- [ ] `configs` z dolną granicą i wartościami całkowitymi
- [ ] Test parametrów bez symulacji, obejmujący też ograniczenia testbenchu
- [ ] Każda asercja timingu ma wzór i tolerancję w komentarzu
- [ ] Stałe seedy, unikalne nazwy workspace i przebiegów
- [ ] Modele reagujące w tym samym cyklu robią `sleep(outDelay)` po zboczu (§4.10)
- [ ] Granice testbenchu wyprowadzone w komentarzu, nie oszacowane (§4.3)
- [ ] `settle()` czeka na kolejki modeli; bariera `resync()` z dala od granicy (§4.12)
- [ ] Dla interfejsu async: konfiguracja z fazą stałą i z fazą dryfującą (§4.2)
- [ ] V1 kompletny, znane luki jako `unimplemented`

## 8. Stan obecny i długi do spłacenia

### Użytkownicy `TestplanSuite`

| Suita               | IP                 | Źródło planu                              | Uwagi |
|---------------------|--------------------|-------------------------------------------|-------|
| `I2cPhyTestplan`    | I2C PHY            | OpenTitan `i2c_testplan.hjson`            | pierwszy użytkownik, wzorzec |
| `I2sMasterTestplan` | I2S master         | brak publicznego; spec + drugi obieg      | 6 konfiguracji, dolna granica `halfDiv = 1` |
| `I2sSlaveTestplan`  | I2S slave (async)  | jw.                                       | model w czasie symulacji, faza dryfująca |
| `I2sPairTestplan`   | master + slave     | PG308, Microchip UG, retroSoC loop mode   | `halfDiv = 4` = granica slave'a |

Pakiety są ujednolicone: `newhope.vertebra` i `newhope.vertebra.sim`.

### Długi

- **`StreamConformance` dla portów wejściowych.** `isInput` nie jest
  używane, więc dla `io.tx` dwa z czterech testpointów sprawdzają drivera.
  Obie suity I2S mają przez to odłożony `tx_stress_with_rand_reset`: driver
  trzyma `valid` przez reset wstrzykiwany z forka. Potrzebny jest driver
  świadomy resetu albo osobny zestaw testpointów dla wejść.
- **Wspólny dekoder I2S.** `I2sMonitor` i `I2sBusMaster` mają osobne
  implementacje reguły Philipsa (dekodowanie z WS vs. pozycje znane
  z generacji). To celowe dla niezależności od DUT-a, ale między sobą mogą
  się rozjechać – rozważyć wspólne testy jednostkowe na surowych bitach.
- **Odłożone testpointy I2S:** left-/right-justified, MCLK, `io.enable`
  (master). Mają nazwy w planach i czekają na funkcję w DUT-cie.
- **Notka o `Instrument` w I2C.** Komentarz przy `Instrument` w
  `Testplan.scala` odsyła do notki w `I2cPhyTestplan`, której tam nie ma.
  `I2cPhy` nie woła `Instrument`, więc testpointy `StreamConformance` dla
  portu `cmd` nie są dołączone do planu. Albo instrumentacja w `rework` (jak
  w I2S, §4.9), albo notka w nagłówku `I2cPhyTestplan`.
