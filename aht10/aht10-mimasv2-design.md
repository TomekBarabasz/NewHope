# AHT10 na Mimas V2 — dokument projektowy

Demo: czujnik temperatury i wilgotności AHT10 na magistrali I2C, sterowany
z FPGA Spartan-6 (Mimas V2), z prezentacją wyniku na trzycyfrowym
wyświetlaczu 7-segmentowym. Warstwa I2C już istnieje (`I2cMaster`,
`I2cPhyTable` / `I2cPhyFsm`) — ten dokument opisuje to, co trzeba dopisać.

---

## 1. Wymagania

| Nr | Wymaganie | Status |
|----|-----------|--------|
| 1 | DP1 wybiera: temperatura / wilgotność | |
| 2 | DP2 wybiera: °C / °F | |
| 3 | ~~Restart licznika po przełączeniu DP1~~ | wycofane — patrz niżej |
| 4 | Wynik przeliczony na wyświetlaczu | |
| 5 | Skalowanie przez jawnie wymuszony slice DSP | |
| 6 | Odpytywanie czujnika co `nn` sekund (stała projektowa) | |

**Dlaczego [3] wypadło.** Jedna transakcja `0xAC` zwraca *oba* pomiary
w tej samej sześciobajtowej ramce. Oba są zatrzaskiwane w rejestrach, więc
przełączenie DP1 zmienia wyłącznie stałe wejściowe skalera — wynik pojawia
się w następnym takcie, bez ruchu na magistrali. Ponowne odpytanie byłoby
czekaniem 75 ms na dane, które już leżą w rejestrze.

Konsekwencja dla [2]: DP2 działa identycznie. Żaden z przełączników nie
dotyka `Aht10Ctrl` — obydwa wchodzą tylko do `ScalerDsp` i `DisplayFormat`.
`MeasTimer` staje się zwykłym licznikiem swobodnym.

---

## 2. Środowisko sprzętowe

**Płytka:** Numato Mimas V2, Spartan-6 `XC6SLX9-CSG324`, oscylator 100 MHz,
8 LED, 6 przycisków, 8-pozycyjny DIP switch, **trzycyfrowy** wyświetlacz
7-segmentowy, 32 IO na czterech złączach 6×2 (P6…P9).

**Toolchain:** SpinalHDL → Verilog → Xilinx ISE 14.7 (Spartan-6 nie jest
wspierany przez Vivado) → `.bin` → narzędzie konfiguracyjne Numato przez USB.

### 2.1 Przydział pinów

| Sygnał | Kierunek | Lokalizacja | Pin FPGA |
|---|---|---|---|
| `clk` 100 MHz | in | oscylator pokładowy | z UCF Numato |
| `scl` | inout | P6 pin 1 | `U7` |
| `sda` | inout | P6 pin 2 | `V7` |
| `dp[1:2]` | in | DIP switch | z UCF Numato |
| `seg[7:0]` (a…g + dot) | out | 7-seg | z UCF Numato |
| `en[2:0]` | out | 7-seg, anody | z UCF Numato |
| `led[7:0]` | out | LED | z UCF Numato |

Piny `U7`/`V7` pochodzą z tabeli złącz w dokumentacji Numato. Reszty nie
zgaduj — weź z UCF-a z przykładowego projektu Numato albo ze schematu
(`MimasV2Sch.pdf`).

### 2.2 Elektryka — trzy rzeczy do sprawdzenia przed lutowaniem

1. **Nie używaj złącza P9.** Wszystkie jego piny to `M1DQ*`, `M1RASN`,
   `M1CASN` — to bank 1, obsługujący LPDDR, czyli VCCO = 1.8 V. AHT10
   zasilany 3.3 V wystawi 3.3 V na wejście banku 1.8 V. P6/P7/P8 są
   w banku 2 (3.3 V) i te są właściwe.
2. **Rezystory podciągające 4.7 kΩ do 3.3 V na SDA i SCL — zewnętrzne.**
   Wewnętrzne `PULLUP` Spartana mają rząd 10–50 kΩ i nie wyrobią zboczy
   narastających I2C przy jakiejkolwiek pojemności przewodów.
3. **Kondensator 100 nF przy VDD czujnika**, jak najbliżej obudowy —
   wymaganie datasheetu, nie sugestia.

Dodatkowo: pin ADR czujnika zostawiamy tak, jak jest na module — adres
i tak jest jeden (0x38), a datasheet mówi wprost, że na magistrali nie
powinno być innych urządzeń I2C.

---

## 3. Architektura

```
DP1/DP2 ──┬─────────────────────────────────────┐
          │                                     │
          │   MeasTimer ──> Aht10Ctrl <──> I2cMaster <──> AHT10
          │   (co nn s)         │                 (istniejący)
          │                     │ rawT, rawRh (20 b)
          │                     v
          └──────────────> ScalerDsp ──> BinToBcd ──> DisplayFormat
                           (DSP48A1)                       │
                                                           v
                                                     SevenSegMux ──> 7-seg
```

Jedna domena zegarowa 100 MHz, wszystko synchroniczne. Wspólny `msTick`
(dzielnik przez 100 000) zasila wszystkie odliczania czasu — dzięki temu
w projekcie nie ma licznika 28-bitowego obok 12-bitowego bez powodu,
a stałe czasowe są czytelne (20, 75, 2000 ms).

---

## 4. Komponenty

### 4.1 `SwitchInput`

Synchronizacja i odszumienie wejść DIP.

```
BufferCC(2) -> licznik stabilności (~10 ms) -> rejestr wyjściowy
```

Bez `BufferCC` masz metastabilność na wejściu asynchronicznym. Debounce po
wycofaniu [3] jest już mniej krytyczny (nic się nie wyzwala na zboczu),
ale drgający DP1 daje migotanie wyświetlacza między temperaturą
a wilgotnością — więc zostaje.

### 4.2 `MeasTimer`

```scala
case class MeasTimer(periodMs : Int) extends Component {
  val io = new Bundle { val trigger = out Bool() }
}
```

Impuls jednotaktowy co `periodMs`. Pierwszy impuls dopiero po zakończeniu
inicjalizacji czujnika (`Aht10Ctrl` sam bramkuje `trigger`, gdy nie jest
w stanie `sIdle`).

`periodMs` musi być **parametrem elaboracyjnym, nie stałą w kodzie** —
inaczej testbench każdego pomiaru czeka 2 sekundy symulacji.

Wartość produkcyjna: **2000 ms**. Datasheet zaleca dokładnie co 2 sekundy
i wprost ostrzega, że przy zbyt częstym pomiarze samonagrzewanie przekracza
0.1 °C (czas aktywności ma być < 10 % okresu; 100 ms / 2000 ms = 5 %,
mieścimy się).

### 4.3 `Aht10Ctrl`

Sekwencer bajtowy nad `I2cMaster`. Cała wiedza o protokole czujnika
siedzi tutaj i nigdzie indziej.

**Adres:** 7-bitowy 0x38 → bajt zapisu `0x70`, bajt odczytu `0x71`.

**Sekwencja stanów:**

| Stan | Akcja | Czas |
|---|---|---|
| `sPowerUp` | odczekaj | 20 ms (datasheet: do 20 ms do stanu jałowego) |
| `sInit` | `START, W 0x70, W 0xE1, W 0x08, W 0x00, STOP` | |
| `sCheckCal` | `START, W 0x71, R×1 (NACK), STOP`, sprawdź bit 3 | opcjonalne |
| `sIdle` | czeka na `trigger` | |
| `sTrigger` | `START, W 0x70, W 0xAC, W 0x33, W 0x00, STOP` | |
| `sWait` | odczekaj | ≥ 75 ms (datasheet: 75 ms) |
| `sRead` | `START, W 0x71, R×5 (ACK), R×1 (NACK), STOP` | |
| `sParse` | zatrzask + walidacja | 1 takt |
| `sReset` | `START, W 0x70, W 0xBA, STOP`, odczekaj 20 ms | ścieżka błędu |

**Rozpakowanie ramki (6 bajtów):**

```
b0 = status
b1 = RH[19:12]
b2 = RH[11:4]
b3 = RH[3:0] : T[19:16]
b4 = T[15:8]
b5 = T[7:0]
```

**Status:** bit 7 = busy (1 = jeszcze liczy), bit 3 = CAL enable
(1 = skalibrowany). Jeśli po 75 ms busy dalej stoi — jeszcze jedna próba
odczytu, potem `sReset`.

**Wyjście:**

```scala
case class Aht10Sample() extends Bundle {
  val rawT  = UInt(20 bits)
  val rawRh = UInt(20 bits)
}
// io.sample : Flow(Aht10Sample())
// io.error  : Bool()   // do LED
```

**Watchdog.** `I2cMaster` nie ma timeoutu (masz to udokumentowane jako
`unimplemented("host_stretch_timeout")`). Przy niepodłączonym czujniku,
zwartym SDA albo braku podciągnięcia FSM zawiśnie na `phyCmd.ready`
i płytka zamarznie bez żadnego objawu. Licznik w `Aht10Ctrl`: brak
`io.cmd.ready` przez np. 200 ms → przerwij transakcję, podnieś `error`,
idź do `sPowerUp`. To jest jedna z ważniejszych rzeczy w tym projekcie —
bez tego debugowanie na płytce polega na zgadywaniu.

**NACK na ostatnim bajcie.** Interfejs `I2cCmd` ma pole `ack`: `True`
= odeślij ACK po odebranym bajcie. Ostatni odczyt musi mieć `ack = false`,
inaczej czujnik nie puści SDA i STOP nie wyjdzie poprawnie.

**Sprawdzanie ACK adresu.** `I2cRsp.ack` jest już odwrócone (`True` = ACK
na magistrali, czyli niski poziom w 9. bicie). Po każdym zapisie adresu
warto to sprawdzić — brak ACK oznacza, że czujnika nie ma, i lepiej
zapalić `error` niż odczytać 0xFF i pokazać bezsens.

### 4.4 `ScalerDsp`

Przeliczenie surowej wartości na dziesiąte części jednostki. Trzy tryby
mają **tę samą postać afiniczną**, więc mieszczą się w jednym slice z
multipleksowanymi stałymi:

| Tryb | Wzór z datasheetu | A | B | offset |
|---|---|---|---|---|
| °C ×10 | `S/2²⁰·200 − 50` | `rawT >> 3` | 2000 | −500 |
| °F ×10 | `S/2²⁰·360 − 58` | `rawT >> 3` | 3600 | −580 |
| %RH ×10 | `S/2²⁰·100` | `rawRh >> 3` | 1000 | 0 |

```
P = A * B + C          gdzie C = (offset << 17) + (1 << 16)
wynik = P >>> 17       (przesunięcie arytmetyczne)
```

Człon `(1 << 16)` to zaokrąglenie do najbliższej — bez niego przesunięcie
arytmetyczne w prawo daje podłogę, i przy ujemnych temperaturach błąd idzie
zawsze w tę samą stronę.

**Uwaga na szerokość — to jest pułapka.** Mnożarka DSP48A1 jest **18×18 ze
znakiem**, więc na port A wchodzi maksymalnie 131071. `raw >> 2` daje do
262143 i cicho przekręci się na wartość ujemną. Stąd `>> 3` i przesunięcie
wyniku o 17, nie 18. Kosztuje 3 najmłodsze bity, czyli rozdzielczość
0.08 °C — przy dokładności czujnika ±0.3 °C bez znaczenia.

**Weryfikacja skrajnych punktów (do testu):**

| `rawT` | A | P >>> 17 | znaczenie |
|---|---|---|---|
| 0 | 0 | −500 | −50.0 °C ✔ |
| 2¹⁹ | 65536 | 500 | +50.0 °C ✔ |
| 2²⁰−1 | 131071 | 1499 | +149.9 °C ✔ |

Zakres wyjściowy: °C ×10 ∈ [−400, 850], °F ×10 ∈ [−400, 1850],
%RH ×10 ∈ [0, 1000]. Czyli 12 bitów ze znakiem wystarcza na wszystko.

#### Jak wymusić DSP — trzy poziomy

1. Zwykły `*` w SpinalHDL + atrybut syntezy (`use_dsp48 = "yes"`, opcja
   XST). Działa, ale nie masz kontroli nad tym, *co* wyląduje w slice —
   XST może wciągnąć mnożarkę, a sumator zostawić w LUT-ach.
2. **Instancjacja prymitywu** `DSP48A1` jako `BlackBox`. To jest wersja,
   o którą chodzi w wymaganiu [5].
3. Weryfikacja w raporcie map: ile DSP48A1 użyto (LX9 ma ich 16).

Konfiguracja prymitywu: `OPMODE = 0x0D` daje `P = M + C` (X-mux = wynik
mnożenia, Z-mux = port C). Offset −50 °C dostajesz **w tym samym slice**,
bez osobnego sumatora — to jest cała pointa ćwiczenia. Generyki do
ustawienia: `A0REG`, `A1REG`, `B0REG`, `B1REG`, `CREG`, `MREG`, `PREG`,
`OPMODEREG`, `CARRYINSEL`, `B_INPUT = "DIRECT"`, `RSTTYPE = "SYNC"`.
Zegar przez `mapClockDomain(clock = CLK)`.

**Problem z symulacją.** `BlackBox` nie zasymuluje się bez bibliotek
unisim, a testplan jest w GHDL/Verilatorze. Rozwiązanie: dokładnie ten sam
mechanizm, którego używasz przy `buildPhy` — parametr wybierający
implementację:

```scala
sealed trait DspImpl
object DspImpl { case object Behavioral extends DspImpl
                 case object Primitive  extends DspImpl }
```

Model behawioralny do symulacji i testplanu, prymityw do syntezy. Wtedy
jeden zestaw testów pokrywa obie ścieżki i masz dowód, że model i prymityw
są bit-identyczne (test równoważności można puścić w ISE ISim albo przez
porównanie z modelem Scali na zbiorze wyczerpującym).

**Latencja.** Z zarejestrowanymi A/B/M/P prymityw ma 3–4 takty. Interfejs
musi to odzwierciedlać (`Flow` z `valid` przesuniętym o latencję albo
`Stream` z prostym licznikiem). Nie rób tego kombinacyjnie „bo szybciej” —
jeśli wyłączysz `MREG`/`PREG`, DSP przestaje być DSP i XST może go
rozłożyć na logikę.

### 4.5 `BinToBcd`

Double dabble, wejście 11 bitów bez znaku (0…2047), wyjście 4 cyfry BCD.
11 taktów sekwencyjnie. Kombinacyjnie nie ma sensu — masz 2 sekundy.

Wejściem jest **wartość bezwzględna** wyniku ze skalera; znak idzie obok,
osobnym bitem.

### 4.6 `DisplayFormat`

Trzy cyfry to realne ograniczenie i regułę trzeba ustalić teraz, a nie
odkryć przy `100.0 %RH`:

| Warunek | Format | Przykład |
|---|---|---|
| wartość < 0 | `-` + 2 cyfry całkowite, bez kropki | `-12` |
| wartość ≥ 100.0 | 3 cyfry całkowite, bez kropki | `100`, `185` |
| pozostałe | 2 cyfry + kropka + 1 po przecinku | `25.4` |
| `error` z `Aht10Ctrl` | `---` | |

Przy odrzucaniu części ułamkowej zaokrąglaj (dodaj 5 przed dzieleniem
przez 10), inaczej `99.7 °C` pokaże się jako `99` zamiast `100`.

Wartość ujemna poniżej −99 nie wystąpi (dolny zakres czujnika to −40 °C
/ −40 °F), ale warto to zaasertować w elaboracji zamiast cicho ucinać.

### 4.7 `SevenSegMux`

Multiplekser 3 cyfr, dekoder BCD → segmenty, kropka dziesiętna, znak minus
(sam segment `g`).

- Odświeżanie **1 kHz na cyfrę** → przełączanie co ~333 µs
  (dzielnik 100 MHz / 3000 ≈ 33 333).
- **Wszystko aktywne w stanie niskim** — segmenty *i* enable'y. Na wyjściu
  `io.seg := ~segments`, `io.en := ~oneHot`. To jest najczęstsza pomyłka
  na tej płytce; udokumentowane w sekcji 2.12 dokumentacji Numato.
- Opcjonalnie: krótkie wygaszenie w momencie przełączania cyfry
  (blanking), jeśli pojawi się duchowanie.

### 4.8 Top `Aht10Demo`

Spina całość, mapuje piny, wystawia LED-y jako kanał debugowy:

| LED | Znaczenie |
|---|---|
| 0 | `error` |
| 1 | czujnik zainicjalizowany |
| 2 | transakcja w toku |
| 3 | bit CAL ze statusu |
| 4–7 | numer stanu `Aht10Ctrl` |

To jedyny wgląd w układ, jaki będziesz miał na płytce — nie oszczędzaj.

**Reset.** Mimas V2 nie ma dedykowanego przycisku resetu. Dwie opcje:
przypisać jeden z sześciu przycisków, albo użyć
`ClockDomainConfig(resetKind = BOOT)` — Spartan-6 wspiera GSR, więc
wszystkie `Reg init()` stają się atrybutami INIT i po konfiguracji układ
startuje w znanym stanie. Dla dema `BOOT` wystarczy i oszczędza pin.

**UCF.** Poza przydziałem pinów potrzebne jest ograniczenie okresu:
`NET "clk" TNM_NET = clk; TIMESPEC TS_clk = PERIOD "clk" 10 ns HIGH 50%;`
oraz `IOSTANDARD = LVCMOS33` i `PULLUP` na SDA/SCL (jako uzupełnienie
rezystorów zewnętrznych, nie zamiennik).

---

## 5. Na co uważać — lista kontrolna

**Sprzęt**
- [ ] AHT10 na złączu P6/P7/P8, **nie P9** (bank 1.8 V od LPDDR)
- [ ] Zewnętrzne podciągnięcia 4.7 kΩ na SDA i SCL
- [ ] 100 nF przy VDD czujnika
- [ ] Napięcie zasilania czujnika i banku FPGA zgodne (3.3 V)

**RTL**
- [ ] Wyświetlacz: segmenty **i** enable'y aktywne w stanie niskim
- [ ] Port A DSP48A1 to 18 bitów **ze znakiem** — max 131071, stąd `>> 3`
- [ ] Zaokrąglenie w `C`, nie ucinanie
- [ ] Ostatni odczyt I2C z `ack = false`
- [ ] `BufferCC` na wszystkich wejściach asynchronicznych (DP)
- [ ] Watchdog w `Aht10Ctrl` — `I2cMaster` nie ma timeoutu
- [ ] Sprawdzanie ACK adresu → `error`, nie ciche 0xFF
- [ ] Stałe czasowe jako parametry elaboracyjne (symulacja!)

**Datasheet — łatwe do przeoczenia**
- [ ] 20 ms po włączeniu zasilania przed pierwszą komendą
- [ ] ≥ 75 ms po `0xAC` przed odczytem
- [ ] Bit busy w statusie sprawdzany, nie zakładany
- [ ] Pomiar nie częściej niż co 2 s (samonagrzewanie)
- [ ] Świeżo lutowany czujnik może pokazywać zawyżoną wilgotność, dopóki
      polimer się nie nawodni (>75 % RH przez 12 h albo >40 % RH przez
      5 dni) — jeśli pierwsze odczyty wilgotności są dziwne, to *może* być
      to, a nie błąd w RTL

**Toolchain**
- [ ] ISE 14.7, nie Vivado
- [ ] Bitstream jako `.bin` (opcja „Create Binary Configuration File")
- [ ] SW7 w pozycji 1 przy programowaniu przez USB

---

## 6. Plan realizacji

Kolejność jest podyktowana jedną zasadą: **najpierw kanał obserwacji,
potem to, co się obserwuje.** Wyświetlacz i LED-y to jedyny debug na
płytce, więc powstają pierwsze — inaczej przy pierwszym problemie z I2C
nie masz jak odróżnić „czujnik nie odpowiada" od „wyświetlacz źle
podłączony".

### Etap 0 — szkielet i toolchain
Pusty top, migający LED z licznika, pełna ścieżka SpinalHDL → Verilog →
ISE → `.bin` → płytka.

*Gotowe, gdy:* LED miga z widoczną częstotliwością, co potwierdza zegar
100 MHz, UCF i przepływ narzędzi.

### Etap 1 — `SevenSegMux`
Statyczna wartość wpisana na sztywno, np. `1.23`.

*Gotowe, gdy:* na płytce widać `1.23`, wszystkie trzy cyfry jednakowo
jasne, bez duchowania. To weryfikuje polaryzację aktywnie-niską i
częstotliwość multipleksowania — dwie rzeczy, których symulacja ci nie
powie.

### Etap 2 — `BinToBcd`
Wyczerpujący test w symulacji, potem na płytce: licznik sekund na
wyświetlaczu.

*Gotowe, gdy:* licznik liczy 000→999 i zawija; test wyczerpujący
przechodzi.

### Etap 3 — `ScalerDsp`
Najpierw model behawioralny + testy wyczerpujące. Potem prymityw
`DSP48A1`. Potem synteza i raport.

*Gotowe, gdy:* raport map pokazuje `DSP48A1: 1`, a test równoważności
model vs prymityw przechodzi. Na płytce: DP2 przełącza wyświetlaną
wartość ze stałej wpisanej jako `rawT` między `°C` a `°F`.

### Etap 4 — `Aht10Ctrl`
Model slave'a AHT10 w symulacji, pełna sekwencja init → trigger → read.

*Gotowe, gdy:* testplan przechodzi w symulacji; na płytce LED-y pokazują
zainicjalizowany czujnik i cykliczne transakcje.

### Etap 5 — integracja
`MeasTimer`, `SwitchInput`, `DisplayFormat`, spięcie w top.

*Gotowe, gdy:* wyświetlacz pokazuje sensowną temperaturę, DP1 przełącza
na wilgotność, DP2 na °F, dmuchnięcie na czujnik zmienia odczyt.

### Etap 6 — wykończenie
Watchdog, obsługa błędów, `---` przy braku czujnika, LED-y diagnostyczne.

*Gotowe, gdy:* wyciągnięcie czujnika z płytki daje `---` i zapalony LED
błędu zamiast zawieszenia.

---

## 7. Plan testów

Zasada: testujemy tam, gdzie test jest **tańszy od debugowania na
płytce**, i pomijamy tam, gdzie oko na wyświetlaczu wystarczy.

### `BinToBcd` — test wyczerpujący, wysoki priorytet

Wejście ma 11 bitów, czyli 2048 przypadków. Referencja w Scali to jedna
linijka. Nie ma powodu testować tego inaczej niż w pełni.

```
for (v <- 0 until 2048) assert(dut(v) == bcdOf(v))
```

Sprawdź osobno: 0, 9, 10, 99, 100, 999, 1000, 1850, 2047.

### `ScalerDsp` — test wyczerpujący, wysoki priorytet

Po `>> 3` przestrzeń wejściowa ma 131 072 punktów × 3 tryby ≈ 400 tys.
przypadków. To nadal jest w zasięgu symulacji i daje pełną pewność, że
przesunięcia i zaokrąglenia się zgadzają. Referencja:

```scala
def refC10(raw : Int)  = math.round(raw * 200.0 / (1 << 20) * 10 - 500).toInt
def refF10(raw : Int)  = math.round(raw * 360.0 / (1 << 20) * 10 - 580).toInt
def refRh10(raw : Int) = math.round(raw * 100.0 / (1 << 20) * 10).toInt
```

Tolerancja ±1 na najmłodszej cyfrze (skutek `>> 3`). Punkty obowiązkowe:
`raw = 0`, `2¹⁹`, `2²⁰−1`, oraz okolice zera °C (`raw ≈ 262144`), gdzie
znak wyniku się zmienia i widać, czy zaokrąglenie jest symetryczne.

Drugi test: **równoważność model ↔ prymityw**. Ten sam zbiór wejść, dwa
DUT-y, porównanie bit w bit.

### `Aht10Ctrl` — testplan z modelem slave'a, wysoki priorytet

Potrzebny `Aht10SlaveModel` (rozszerzenie `I2cBusModel`): odpowiada na
adres 0x38, rozpoznaje `0xE1` / `0xAC` / `0xBA`, oddaje sześć bajtów
o zadanej zawartości. Testpointy w konwencji `I2cPhyTestplan`:

| Testpoint | Sprawdza |
|---|---|
| `aht10_power_up_delay` | brak ruchu na magistrali przez pierwsze 20 ms |
| `aht10_init_sequence` | dokładnie `0xE1, 0x08, 0x00` po adresie zapisu |
| `aht10_measure_opcodes` | dokładnie `0xAC, 0x33, 0x00` |
| `aht10_measure_delay` | odczyt nie wcześniej niż 75 ms po `0xAC` |
| `aht10_read_nack_last` | pięć ACK, ostatni bajt NACK, potem STOP |
| `aht10_frame_unpack` | znana ramka → oczekiwane `rawT` i `rawRh` (w tym poprawny podział bajtu b3) |
| `aht10_busy_retry` | status z bitem 7 → ponowny odczyt, nie zatrzask śmieci |
| `aht10_addr_nack_error` | slave nie ACK-uje adresu → `error`, powrót do `sPowerUp` |
| `aht10_watchdog` | slave trzyma SCL w nieskończoność → abort w oczekiwanym oknie |

Ostatni jest najważniejszy i najłatwiejszy do pominięcia. **Każdy test
musi mieć `SimTimeout`** — bez watchdoga w RTL i limitu w symulacji
źle sparametryzowana konfiguracja zawiesza się zamiast failować (masz to
ostrzeżenie już w nagłówku `I2cPhyTestplan`).

Wszystkie stałe czasowe skrócone przez parametry (20 ms → 20 µs itd.),
inaczej jeden test to sekundy symulacji.

### `DisplayFormat` — test wyczerpujący, tani

Cały zakres wejściowy × 2 tryby, porównanie z modelem Scali. Granice do
wymuszenia jawnie: `-1`, `0`, `99`, `100` (czyli 9.9 → 10.0), `999`,
`1000` (99.9 → 100), `1850`, oraz `error`.

### `MeasTimer` — jeden test

Odstęp między impulsami zgadza się z parametrem. Nic więcej tam nie ma.

### `SevenSegMux` — test w symulacji ograniczony

Warto zasymulować: każda cyfra aktywna dokładnie 1/3 czasu, tylko jeden
enable aktywny naraz, poprawna polaryzacja. Reszty (jasność, duchowanie)
symulacja nie powie — to etap 1 na płytce.

### `SwitchInput` — opcjonalnie

Jeden test odbić styku (impulsy krótsze niż okno debounce nie przechodzą).
Niski priorytet — po wycofaniu [3] błąd tu daje migotanie, nie awarię.

### Test integracyjny

Smoke w symulacji: model slave'a oddaje znaną ramkę, sprawdzamy, że po
przewidywalnym czasie na wyjściach segmentów pojawiają się oczekiwane
wzorce dla `25.4` w obu trybach. Wszystkie stałe czasowe skrócone.

---

## 8. Otwarte pytania

1. Czy chcesz sprawdzać bit CAL po inicjalizacji (dodatkowy stan
   `sCheckCal`), czy zakładać kalibrację fabryczną?
2. `nn` = 2 s — czy zostajemy przy zaleceniu datasheetu, czy chcesz
   szybciej i przyjmujesz samonagrzewanie?
3. Czy `ScalerDsp` ma być jednym slice z multipleksowanymi stałymi
   (mniej zasobów, jeden wynik naraz), czy trzema równoległymi (widać
   trzy DSP w raporcie, ale sensu funkcjonalnego brak)?
4. Wyświetlanie znaku: czy `-12` wystarcza, czy chcesz np. mrugać
   wartością przy ujemnej temperaturze?
