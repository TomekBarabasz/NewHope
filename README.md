# how to a start container
from learning-hdl/SpinalHDL directory
```bash
docker run --rm -ti -v .:/workspace -w /workspace -v .\.spinal-sbt:/sbt ghcr.io/spinalhdl/docker:master
```

```sh
// To generate the Verilog
sbt  runMain I2CExample.I2cMasterVerilog
sbt  runMain I2CExample.AsyncFifoDemoVerilog
```

```sh
// To run tests
sbt  test
sbt  testOnly *I2cPhyTest
sbt  testOnly *AsyncFifoDemoTest
sbt  runMain I2CExample.CdcInjectDemoSim //to jest App a nie AnyFunSuite
```

# Komendy sbt
projects
projects i2c - przejście do modułu
sbt:i2c> 
  test
  compile
  Test/compile - tylo compilacja testó
  clen
project root - powrót
reload - po każdej edycji build.sbt

wavesOn [default] - włączanie  generacji przebiegów
wavesOff          - wyłączanie generacji przebiegów

i2c/test - uruchomienie testów dla modułu i2c

sbt test w korzeniu odpala wszystko przez aggregate. Przy I2cPhyTestplan to 4 configi × 2 implementacje = 8 kompilacji Verilatora, plus fuzzer z 400 przebiegami. Rzadko tego chcesz.

```
i2c/test                                          <- jeden modul
i2c/testOnly org.newhope.i2c.I2cPhyFsmTest        <- jedna klasa
i2c/testOnly *TableTestplan                       <- glob
i2c/testOnly *Testplan -- -z host_smoke           <- jeden test po nazwie
i2c/testOnly *Testplan -- -z "fast400k"           <- jeden config
```

-z to filtr ScalaTest po fragmencie nazwy testu. Skoro twoje testy nazywają się "$cfgName / $name", -z fast400k odpala jeden config zamiast czterech. To jest doraźna odpowiedź na §5.6 handoffu — na tyle dobra, że mechanizm wyboru z linii poleceń może w ogóle nie być potrzebny.

## Ciągłe przebudowywanie przy edycji:
~i2c/testOnly *I2cPhyFsmTest
Tylda obserwuje źródła wszystkich modułów, od których zależy i2c — czyli zmiana w vertebrze też przeładuje test I²C. To jest ta rzecz, której nie dałoby osobne repo.

## Generowanie Veriloga per moduł
Tak, każdy moduł osobno. Twój object I2cPhyVerilog extends App uruchamiasz przez:
i2c/runMain org.newhope.i2c.I2cPhyVerilog
runMain z pełną nazwą, bo run przy kilku obiektach z main zapyta interaktywnie albo padnie. Dzięki Compile / run / baseDirectory z hwSettings cwd to katalog modułu, więc targetDirectory = "hw/gen" daje i2c/hw/gen/.

# I2C
## I2C Phy
## I2C Master

# AsyncFifio
## Działający most CDC
`StreamFifoCC` przenoszący strumień z domeny `clkA` (100 MHz) do `clkB` (~37 MHz), plus testbench generujący przebiegi.

- `AsyncFifoDemo.scala` — DUT. Dwie domeny tworzone przez `ClockDomain.external`,
między nimi `StreamFifoCC`. Na zewnątrz wyprowadzone `pushOccupancy`,
`popOccupancy`, `full`, `empty`.
- `AsyncFifoDemoSim.scala` — dwa scenariusze:
    - **burst** — producent wysyła 40 słów tak szybko, jak się da; konsument początkowo
  jest wolny, potem przyspiesza. Ręczne sterowanie handshake'em przez
  `waitSamplingWhere`, na końcu sprawdzenie, że odebrana sekwencja to dokładnie
  `0..39` w tej samej kolejności.
    - **jitter** — gotowe agenty z `spinal.lib.sim`: `StreamDriver`,
  `StreamReadyRandomizer`, `ScoreboardInOrder`.
  puszcza FIFO 20 razy z losową fazą startową zegarów i pięcioprocentowym jitterem okresu.

   `forkStimulus` daje idealny, stały okres — a wtedy dwa zegary potrafią utknąć
   w jednej relacji fazowej na całą symulację i nigdy nie odwiedzić tej niewygodnej.
   Losowa faza plus jitter sprawiają, że zbocza dryfują przez wszystkie możliwe
   wzajemne położenia. To nie symuluje metastabilności, ale wymiata protokoły, które
   działały przypadkiem.

### Na co patrzeć w przebiegach

Dodaj: `clkA_clk`, `clkB_clk`, `io_push_valid`, `io_push_ready`, `io_push_payload`,
`io_pop_valid`, `io_pop_ready`, `io_pop_payload`, `io_pushOccupancy`,
`io_popOccupancy`, `io_full`, `io_empty`.

1. **Backpressure** — `pushOccupancy` rośnie do 16, `io_push_ready` opada.
2. **Rozjazd liczników zajętości** — `pushOccupancy` i `popOccupancy` nie są równe
   w tej samej chwili. Każda domena widzi wskaźnik tej drugiej opóźniony o `BufferCC`.
3. **Opóźnienie startu** — po pierwszym zapisie `io_pop_valid` podnosi się dopiero
   po 2–3 cyklach `clkB`. To koszt synchronizatora.
4. **Wskaźniki Graya** — rozwiń hierarchię `fifo`. Między kolejnymi wartościami
   zmienia się dokładnie jeden bit.

Verilator w SpinalSim trasuje całą hierarchię, więc sygnały wewnętrzne FIFO są w VCD
bez dodatkowych zabiegów. `.simPublic()` potrzebne jest tylko do czytania ich
z poziomu Scali.

Przebiegi w : simWorkspace/AsyncFifoDemo

## Stanowisko do badania metastabilności
model synchronizatora z wstrzykiwaną niepewnością i eksperyment kontrolny pokazujący, dlaczego kod Graya jest konieczny.

### `MetaBufferCC.scala`
Model synchronizatora, **niesyntezowalny**, przeznaczony wyłącznie do symulacji.

Na poziomie RTL metastabilność nie objawia się jako napięcie w połowie skali —
objawia się jako **niepewność o jeden cykl zegara docelowego**. Pierwszy stopień albo
zdążył się rozstrzygnąć przed zboczem drugiego, albo nie, i wtedy drugi stopień
zatrzaskuje jeszcze starą wartość. `MetaBufferCC` modeluje dokładnie to, per bit,
sterowane wejściem `chaos` z testbencha.

Dwie własności modelu są istotne:

- **Monotoniczność.** Wyjście może się spóźnić, ale nigdy nie cofa się ani nie
  wyprzedza źródła. Realna metastabilność też tego nie robi.
- **Ograniczona zwłoka.** Zaburzenie może opóźnić zmianę o co najwyżej jeden
  dodatkowy cykl. Bez tego losowy `chaos` zatrzymałby sygnał na zawsze, co nie ma
  nic wspólnego z fizyką.

Przy `chaos = 0` komponent zachowuje się identycznie jak zwykły `BufferCC`.

### `CdcInjectDemo.scala` — eksperyment kontrolny
Ten sam licznik przechodzi przez granicę dwiema ścieżkami równolegle: w kodzie Graya
i binarnie, przez identyczne synchronizatory, przy identycznym rozkładzie zaburzeń.
Jedyna różnica to kodowanie.

Kryterium błędu jest bardzo ostre: licznik rośnie o 1, więc każda kolejna próbka
w domenie docelowej musi się różnić od poprzedniej o 0 albo o 1. Cokolwiek innego to
wartość, która nigdy nie istniała w domenie źródłowej.

Spodziewany wynik: ścieżka Graya ma **zero** błędów, ścieżka binarna ich zbiera
mnóstwo — najwięcej przy przeniesieniach, gdzie jedna inkrementacja zmienia kilka
bitów naraz (`011111 → 100000`).

W GTKWave zestaw obok siebie `io_srcValue`, `io_graySynced` i `io_binSynced`.
Ścieżka Graya podąża za źródłem z opóźnieniem. Ścieżka binarna co jakiś czas wypluwa
wartość kompletnie z kosmosu i wraca.

### Dlaczego licznik źródłowy jest spowolniony

`srcDivider = 16` sprawia, że domena docelowa próbkuje źródło kilka razy na każdą
inkrementację. To **ograniczenie modelu, nie rzeczywistości** — i warto rozumieć różnicę.

W prawdziwym układzie wskaźnik zapisu może zmieniać się szybciej niż zegar odczytu
i Gray dalej jest bezpieczny. Gwarancja opiera się na argumencie czasowym: kolejne
bity Graya zmieniają się na kolejnych *zboczach źródła*, więc w dowolnej chwili
co najwyżej jeden bit jest w trakcie przejścia, a reszta jest ustalona.

Model RTL nie ma pojęcia „w trakcie przejścia" — losuje niezależnie każdy bit, który
się różni. Gdyby źródło było szybsze od celu, model zaburzałby bity, które w realnym
układzie były już dawno stabilne, i niesłusznie zepsułby ścieżkę Graya. Spowolnienie
źródła utrzymuje model w zakresie, w którym jest wierny.

To dobra ilustracja ogólnej zasady: wstrzykiwanie metastabilności do RTL jest
użyteczne, ale zawsze jest przybliżeniem, a jego założenia trzeba znać. Komercyjne
narzędzia (Questa CDC-FX, SpyGlass) rozwiązują to inaczej — instrumentują projekt
z wiedzą o strukturze synchronizatorów i o tym, które sygnały faktycznie mogą być
w oknie niepewności.

przebiegi w simWorkspace/CdcInjectDemo
