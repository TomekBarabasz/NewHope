# Handoff: Vertebra — metodologia weryfikacji dla SpinalHDL

> Dokument przekazania. Powstał z serii czatów o przepisywaniu I²C z VHDL-a
> na SpinalHDL, która skręciła w stronę weryfikacji. Zawiera decyzje już
> podjęte, pytania otwarte i kierunek. **Żaden kod nie został skompilowany** —
> brak dostępu do Maven Central po stronie asystenta. Traktować jako materiał
> do przeczytania i uruchomienia, nie jako gotowiec.

---

## 1. Nazwa
`vertebra`

---

## 2. Teza projektu

Weryfikacja sprzętu ma dojrzałe, dobre praktyki, ale są one zamknięte w
SystemVerilogu i UVM-ie, czyli w bibliotece obchodzącej braki języka
gospodarza. Scala tych braków nie ma. **Da się przenieść praktyki, odrzucając
maszynerię.**

Trzy obserwacje, na których to stoi:

1. **Architektura UVM przenosi się trywialnie.** Driver, monitor, sequencer,
   scoreboard to zwykły podział odpowiedzialności. `SimThread` w SpinalSim
   wystarcza. Precedens: pyuvm (UVM 1.800.2 w Pythonie na cocotb, ~5k linii
   zamiast ~30k SystemVeriloga — ta różnica to miara tego, ile UVM-a jest
   obejściem ograniczeń SV).

2. **Testplan jest wartościowszy niż testbench.** OpenTitan trzyma testplany
   w maszynowo czytelnym hjson. Z 40 testpointów I²C osiem dotyczy protokołu,
   reszta przyjeżdża z importowanych planów wspólnych (`csr_testplan.hjson`,
   `intr_test_testplan.hjson`, `tl_device_access_types_testplan.hjson`).
   Ta faktoryzacja jest głównym wnioskiem całej analizy.

3. **Pokrycie funkcjonalne jako dane pierwszej klasy zmienia grę.** W SV
   `covergroup` to konstrukt języka — możesz zapytać o procent, nie możesz
   dostać listy pustych binów jako obiektów i oddać jej generatorowi. W Scali
   punkt pokrycia to `case class`, brakujące to różnica zbiorów, a pętla
   sprzężenia zwrotnego (coverage-guided fuzzing) to trzy linijki. To jest
   jedyna rzecz, w której możemy realnie przebić narzędzia komercyjne w
   ergonomii, a nie tylko w cenie.

---

## 3. Co jest już ustalone

### 3.1 Faktoryzacja po bundle'ach, nie po rejestrach

OpenTitan faktoryzuje wspólne testpointy po CSR / TL-UL / przerwaniach, bo to
jego wspólny mianownik. **Naszym wspólnym mianownikiem są bundle z
`spinal.lib`**: `Stream`, `Flow`, `BusSlaveFactory`, `ClockDomain`. Każdy port
`Stream` w każdym IP ma identyczny kontrakt do sprawdzenia niezależnie od
przenoszonego typu.

Zestaw startowy dla `Stream` (zaimplementowany w `CommonTestplan.scala`):
- `payload_stable` — payload nie zmienia się przy `valid && !ready` (V1)
- `reset_quiet` — `valid` nisko przez cały reset (V1)
- `backpressure` — poprawność przy dowolnym wzorcu `ready` (V2)
- `stress_with_rand_reset` — reset w trakcie ruchu (V3)

### 3.2 Testplan jako osobny artefakt

Testplan powstaje **przed** testami i jest listą, do której testy się
odwołują. `testpoint(name)` wymaga, żeby nazwa istniała w planie —
nie da się napisać testu spoza planu. `unimplemented(name, reason)` daje
żółty wpis w raporcie zamiast cichego braku.

Test `testplan completeness` wywala się, gdy stage V1 nie jest kompletny.

### 3.3 Stages jako definicja ukończenia

- **V1** — smoke i sanity, podstawowa funkcjonalność end-to-end
- **V2** — pełna funkcjonalność, wszystkie feature'y, pokrycie
- **V3** — stres, losowe resety, przypadki brzegowe

(OpenTitan ma jeszcze V2S dla countermeasures bezpieczeństwa — pomijamy.)

### 3.4 Rozdzielenie Stimulus / Checking

Każdy testpoint ma obie sekcje osobno. To wykrywacz testów, które coś
odpalają i nie sprawdzają niczego poza tym, że symulacja nie padła.

### 3.5 Instrumentacja podczas elaboracji

Żeby sprawdzić kontrakt strumienia z symulacji, trzeba widzieć payload jako
jedną wartość. Robimy to podczas elaboracji (`payload.asBits.simPublic()`),
co jest tą rzeczą, której SystemVerilog nie umie i dlatego ma `bind`.

**Ograniczenie:** musi być wywołane wewnątrz komponentu albo w bloku `rework`.

### 3.6 Pokrycie i fuzzing

Model z `I2cPhyFuzz.scala`:
- punkt pokrycia to `sealed trait` + `case class`
- zbiór docelowy liczony z konstrukcji DUT-a, nie z sufitu
- pętla AFL-owa: korpus → mutacja → symulacja → różnica pokrycia → do korpusu
  trafiają tylko mutanty, które coś odkryły
- na końcu wypis `NIEPOKRYTE` z konkretnymi punktami

### 3.7 Constrained random przez ScalaCheck, nie przez solver

Generatory kompozycyjne pokrywają ~80% zastosowań i dają shrinking, którego
UVM nie ma w ogóle. Solver (Z3) tylko jeśli kiedyś pojawią się splątane
ograniczenia arytmetyczne. Dla weryfikacji peryferiów nie pojawią się.

### 3.8 Czego świadomie nie robimy

- Nie odtwarzamy hierarchii klas UVM (`uvm_component`, fazy, fabryka,
  `uvm_config_db`). To jest infrastruktura obchodząca braki SV.
- Nie zastępujemy SpinalSim — budujemy warstwę nad nim.
- Nie celujemy w signoff do tapeoutu. Progi pokrycia, checklisty i V2S
  są kalibrowane pod zespół weryfikacyjny z budżetem.

---

## 4. Pliki do przeniesienia

| plik | rola w nowym projekcie | stan |
|---|---|---|
| `CommonTestplan.scala` | **rdzeń biblioteki** — `Testpoint`, `TestplanSuite`, `StreamConformance`, `Instrument` | szkielet, do rozbudowy |
| `I2cPhyFuzz.scala` | **rdzeń biblioteki** — `CoverageDb`, `TestCase`, mutacje, pętla; do uogólnienia (dziś zaszyty I²C) | szkielet |
| `I2cPhyTest.scala` | **przykład agenta** — `I2cBusModel` (driver), `I2cMonitor` (monitor + checker protokołu) | działa jako wzorzec |
| `I2cPhyTestplan.scala` | **przykład** suity wyprowadzonej z testplanu OpenTitana, z `pending` na luki | wzorzec |
| `I2cPhy.scala` | **DUT przykładowy** — PHY z tablicą ćwiartek | potrzebny do przykładów |
| `I2cPhyBase` + `I2cPhyFsm` | drugi DUT do porównania — pokazuje, że suita jest niezależna od implementacji | z poprzedniego czatu |
| `I2cMasterBitCtrl.scala` | tłumaczenie OpenCores z VHDL-a; **nie przenosić**, chyba że jako trzeci DUT do regresji | opcjonalne |

Sugerowany układ repozytorium:

```
vertebra/
  core/src/main/scala/vertebra/
      Testplan.scala        <- Testpoint, Stage, TestplanSuite
      Coverage.scala        <- CovPoint, CoverageDb, goal/missing
      Fuzz.scala            <- TestCase, mutacje, pętla
      Instrument.scala      <- simPublic helpers
      conformance/
          StreamTests.scala
          FlowTests.scala
          ResetTests.scala
  examples/src/test/scala/vertebra/examples/i2c/
      I2cPhy.scala          <- DUT
      I2cAgent.scala        <- bus model + monitor
      I2cSuite.scala        <- testplan + testy
  docs/
      methodology.md
```

---

## 5. Do doprecyzowania

Kolejność mniej więcej według tego, jak bardzo blokują resztę.

### 5.1 Jak deklarować model pokrycia (blokuje 5.2 i 5.3)

Dziś: `sealed trait CovPoint` z ręcznie pisanymi `case class`ami i ręcznie
liczonym iloczynem kartezjańskim. Pytania:

- Czy potrzebny DSL na biny i krzyże (`cover(cmd) cross cover(phase)`),
  czy wystarczą krotki i `for`-comprehension? Skłaniam się ku drugiemu —
  w Scali iloczyn kartezjański jest jednolinijkowcem, a DSL doda ceremonii.
- Jak zapisywać biny nierównomierne (odpowiednik `bins small = {[1:4]}`)?
  Prawdopodobnie funkcja `Int => Bucket` przy próbkowaniu.
- Czy pokrycie ma być zbierane per-test i mergowane, czy globalnie w JVM?
  Merge jest potrzebny do CI, ale wymaga serializacji.

### 5.2 Nieosiągalność (to jest właściwy problem badawczy)

Zbiór docelowy liczony jako iloczyn kartezjański zawiera komórki
nieosiągalne z konstrukcji. Fuzzer będzie się o nie tłukł w nieskończoność,
a próg „70% wystarczy" jest kapitulacją.

Kierunek: **spiąć to z formalną.** SpinalHDL ma `FormalConfig` z BMC.
Dla punktu pokrycia da się wygenerować `cover()` i sprawdzić osiągalność
w ograniczonej głębokości. Punkt nieosiągalny do głębokości N przy N
znacznie większym niż długość komendy → wykreślamy z celu z adnotacją.

To jest dokładnie to, co komercyjne narzędzia sprzedają jako
*unreachability analysis*, i jest to jedyna część projektu, która ma szansę
być publikowalna.

### 5.3 Mutacja celowana

Dziś mutacja jest ślepa. Krok dalej: wziąć `missing` i pytać „jaka sekwencja
mnie tam zaprowadzi". Przy 64 punktach ślepa wystarczy; pytanie, czy warto
budować cokolwiek więcej, zanim pojawi się drugi, większy przykład.

### 5.4 Format testplanu i interop

- DSL w Scali (dziś), import z hjson, czy oba?
- Antmicro wydzieliło `testplanner` — samodzielne narzędzie do formatu hjson
  OpenTitana, adoptowane w rdzeniu I3C w CHIPS Alliance. Jeśli format się
  ustandaryzuje, warto umieć go czytać i pisać. **Do zbadania: aktualny stan
  i stabilność formatu.**
- Eksport do JSON/HTML dla CI.

### 5.5 Testowanie resetu

Testpointy V3 wymagają wstrzykiwania resetu w losowych momentach. Do
sprawdzenia, jak to najczyściej zrobić w SpinalSim (`assertReset` /
`deassertReset` w trakcie `doSim`, i czy modele agentów to przeżyją).

### 5.6 Koszt kompilacji

Parametryzacja suity po implementacjach i konfiguracjach mnoży kompilacje
Verilatora (4 configi × 2 implementacje = 8). Potrzebny mechanizm wyboru
podzbioru z linii poleceń i cache'owania `SimCompiled`.

### 5.7 Granica abstrakcji agenta

Czy `Driver` / `Monitor` / `Scoreboard` mają być traitami z kontraktem, czy
zostają luźnymi klasami jak w przykładzie I²C? Ryzyko: zbyt wcześnie
narzucona abstrakcja da drugie UVM. Propozycja: **zostawić luźne aż do
drugiego protokołu**, wtedy wyfaktoryzować to, co się faktycznie powtórzyło.

---

## 6. Roadmapa

- **0.1** — `Testplan.scala` + raportowanie + przykład I²C przechodzący.
  Cel: udowodnić, że dyscyplina testplanu działa w ScalaTest.
- **0.2** — conformance dla `Stream`, `Flow`, reset. Cel: nowe IP dostaje
  kilkanaście testów za darmo.
- **0.3** — `Coverage.scala` + `Fuzz.scala` uogólnione, przykład domyka
  pokrycie I²C.
- **0.4** — integracja z formalną: przycinanie celu o punkty nieosiągalne.
- **0.5** — drugi protokół (SPI albo UART) jako dowód, że abstrakcje nie są
  przypadkowo dopasowane do I²C. **To jest najważniejszy kamień milowy** —
  wszystko przed nim to hipoteza.
- **1.0** — dokumentacja metodologii, interop testplanów, CI.

---

## 7. Materiały źródłowe

- `hw/ip/*/data/*_testplan.hjson` w OpenTitanie — lista testpointów per IP.
  Zacząć od `uart` (najprostszy), potem `spi_host`, `pwm`, `pattgen`.
  `usbdev` na koniec.
- `hw/dv/tools/dvsim/testplans/*.hjson` — **plany wspólne, główne źródło
  inspiracji dla faktoryzacji.**
- `hw/dv/doc` — opis metodologii i stages. Jedyna część OpenTitana bez
  związku z SystemVerilogiem, przenosi się w całości.
- `hw/dv/sv/i2c_agent` — agent UVM; czytać dla struktury, nie dla kodu.
- pyuvm — precedens przepisania UVM na nowoczesny język.
- `spinal.lib.sim` — `StreamDriver`, `StreamMonitor`, `StreamReadyRandomizer`,
  `FlowMonitor`, `ScoreboardInOrder`. **Sprawdzić dokładnie, co już jest,
  zanim się cokolwiek napisze — część roboty może być zrobiona.**
- `spinal.lib.com.i2c.sim` — `OpenDrainInterconnect`, `I2cSoftMaster`.
- RFUZZ (2018) i DifuzzRTL (2021) — prace o coverage-guided fuzzingu RTL-u.
- cocotbext-i2c (MIT) — niezależny model I²C, przydatny jako drugi sędzia.

---

## 8. Ryzyka

**Przedwczesna abstrakcja.** Największe. Wszystko powyżej wyprowadzono z
jednego protokołu. Do 0.5 traktować każdą abstrakcję jako tymczasową.

**Rozrost w drugie UVM.** Jeśli po roku `vertebra` ma fazy, fabrykę i
rejestr konfiguracji po stringach — projekt się nie udał.

**Verilator dogania.** Wsparcie UVM w Verilatorze robi realne postępy
(constrained randomization od 2024, regresja UVM w changelogu 5.050).
Jeśli za dwa lata UVM będzie chodzić na Verilatorze bez licencji, część
uzasadnienia znika. Kontrargument: ergonomia Scali zostaje, a pokrycie jako
dane pierwszej klasy jest przewagą niezależną od kosztu narzędzi. Ten
argument warto trzymać w README, bo będzie pierwszym pytaniem każdego
recenzenta.

**Rozjazd API SpinalHDL.** Kod używa `simPublic`, `BufferCC`, `FormalConfig`,
`addPrePopTask` — sygnatury zmieniały się między wersjami. Przypiąć wersję
w `build.sbt` i podnosić świadomie.

---

## 9. Styl pracy w tym wątku

Wyjaśnienia „dlaczego tak, a nie inaczej", jedno zagadnienie naraz, krótkie
przykłady kodu. Kod jest czytany linia po linii, więc odwołania do konkretnych
fragmentów są mile widziane. Sprostowania własnych wcześniejszych stwierdzeń
są oczekiwane, gdy fakty się nie zgadzają — zdarzyło się to dwa razy
(wsparcie UVM w Verilatorze, „nikt nie zrobił coverage-guided fuzzingu").
