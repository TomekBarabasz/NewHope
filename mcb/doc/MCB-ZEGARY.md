# MCB — zegary i marginesy czasowe

Ściągawka do nowego projektu. Pełny opis w `MCB-README.md`.

Sprzęt: Mimas V2, XC6SLX9-3, MT46H32M16 (512 Mb LPDDR x16), oscylator 100 MHz.
Port 128-bitowy (Config-5). Wszystkie liczby **zmierzone**, nie szacowane.

---

## 1. Dwie sprawdzone konfiguracje

Pamięć 150 MHz w obu. Różnica to tylko `CLKOUT2_DIVIDE`.

```scala
val cfg = MigConfig.forClocks(memMHz = 150, uiMHz = 100)   // wariant A
val cfg = MigConfig.forClocks(memMHz = 150, uiMHz = 75)    // wariant B
```

| | A: UI 100 MHz | B: UI 75 MHz |
|---|---|---|
| `C3_CLKFBOUT_MULT` / `DIVCLK` | 6 / 1 | 6 / 1 |
| `C3_CLKOUT0_DIVIDE` | 2 | 2 |
| `C3_CLKOUT2_DIVIDE` | **6** | **8** |
| `C3_CLKOUT3_DIVIDE` | 10 | 10 |
| VCO | 600 MHz | 600 MHz |
| PFD | 100 MHz | 100 MHz |
| pamięć | 150 MHz | 150 MHz |
| `c3_clk0` (logika) | 100 MHz | 75 MHz |
| `mcb_drp_clk` | 60 MHz | 60 MHz |

Dzielniki idą ze Scali wprost do parametrów `s6_lpddr.v` — Verilogu nie dotykasz.
Wymaga zalatanego `s6_lpddr.v` (parametry PLL przeniesione z ciała modułu do
listy `#()`), bo `localparam` nadpisać się nie da.

---

## 2. Zmierzone marginesy

| Domena | Wymóg | A: osiągnięte | A: zapas | B: osiągnięte | B: zapas |
|---|---|---|---|---|---|
| `clk_2x` (DDR) | 3,333 ns | 1,499 | ogromny | 1,499 | ogromny |
| `clk0` (logika) | A 10,0 / B 13,333 ns | 9,040 | 0,960 ns | 12,200 | **1,133 ns** |
| `mcb_drp_clk` | 16,667 ns | 16,625 | 0,042 ns | 16,165 | 0,502 ns |

Oba warianty: `All constraints were met`, Timing errors 0, Score 0.

**Ścieżka MCB → twój przerzutnik** — ta, na którą wpływasz:

| | najgorsza ścieżka | zapas |
|---|---|---|
| A (100 MHz) | `samc_0` → `errIdx_4`, 8,844 ns, 3 poziomy logiki | **0,969 ns** |
| B (75 MHz) | `samc_0` → `errBcd_1_0` | **3,498 ns** |

To jest praktyczna różnica między wariantami: **3,6× więcej miejsca na logikę
na styku z pamięcią**. Przepustowość jest identyczna (patrz §3).

---

## 3. Przepustowość — zmierzona, oba warianty tak samo

| | A: UI 100 MHz | B: UI 75 MHz |
|---|---|---|
| szczyt pamięci | 600 MB/s | 600 MB/s |
| sufit portu | 1600 MB/s | 1200 MB/s |
| **ogranicza** | **pamięć** | **pamięć** |
| zapas portu ponad minimum UG388 | 2,67× | 2,00× |
| **zmierzony odczyt** | — | **552** (578,8 MB/s, 96,5%) |

Minimum z UG388 dla portu 128-bitowego to `memclk/4` = 37,5 MHz. Oba warianty
są znacznie powyżej, a ogranicza pamięć — więc **zwolnienie UI ze 100 na
75 MHz nie kosztuje przepustowości**.

To nie jest rachunek, tylko pomiar. Ten sam odczyt 552 wyszedł wcześniej przy
UI 37,5 MHz (zapas portu 1,00×). Podniesienie zapasu do 2,00× nie zmieniło
wyniku ani o jednostkę, co dowodzi, że port nie był ograniczeniem.

### Długość burstu

| `burstLen` | odczyt | % szczytu | burstów w locie |
|---|---|---|---|
| 16 | 552 | 96,5% | 4 |
| 32 | 552 | 96,5% | 2 |
| **64** | **516** | **90,2%** | **1** |

Trzymaj się 16–32. `BL = 64` wypełnia całą 64-słowową kolejkę jednym burstem,
więc dławik dopuszcza tylko jedną komendę naraz i latencja przestaje się chować
za poprzednim transferem. To ograniczenie strukturalne, nie do nastrojenia.

Brakujące 3,5% do szczytu: odświeżanie z precharge 1,44%, reszta (~2,1%) to
poziom DRAM/MCB. Szczegóły w `MCB-README.md` §7.

## 4. UCF — nic nie zmieniasz

```
NET "*/memc3_infrastructure_inst/sys_clk_ibufg" TNM_NET = "SYS_CLK3";
TIMESPEC "TS_SYS_CLK3" = PERIOD "SYS_CLK3"  10  ns HIGH 50 %;
```

Zostaje **10 ns przy każdej konfiguracji**, bo celuje w zegar wejściowy
(oscylator 100 MHz), który się nie zmienia. Wymagania dla `clk0`, `drp`
i `clk_2x` ISE wyprowadza sam — widać je w `.twr` w sekcji
*Derived Constraint Report*.

Plus `CONFIG MCB_PERFORMANCE = STANDARD;` i dwa `TIG` (patrz `MCB-README.md` §5.6).

---

## 5. Projektowanie logiki dotykającej MIG

**Zapas liczy się osobno dla każdej ścieżki, nie jest wspólną pulą.** Twoja
logika niezwiązana z pamięcią ma własne ścieżki i własne zapasy. Dzielisz
z MIG-iem tylko częstotliwość — i ścieżki, które fizycznie przez tę granicę
przechodzą.

**Styk jest dokładnie tam, gdzie czytasz sygnały kontrolera.** `cmd_full`,
`rd_empty`, `rd_data`, `wr_full`. Logika kombinacyjna między nimi a twoim
przerzutnikiem dokłada się do ścieżki startującej w twardym bloku MCB.

Budżet na tej ścieżce: **0,97 ns w wariancie A, 3,50 ns w wariancie B**.
W A to mniej więcej jeden poziom LUT-ów.

**Zasady:**

- Rejestruj sygnały statusowe MCB, zanim wejdą w głębsze drzewo logiki.
- Decyzja o wystawieniu komendy zależna kombinacyjnie od `cmd_full` wydłuża
  ścieżkę, która już zużywa 8,8 z 10 ns w wariancie A.
- Szerokie komparatory na danych odczytu (128 bitów) potokuj o takt — przy
  strumieniu nie kosztuje to przepustowości.
- Strona DRAM jest odizolowana: `clk_2x`, twardy MCB i piny nie stracą
  marginesu od tego, co dołożysz.

**Jeśli potrzebujesz innego zegara dla swojej logiki:** trzy wejścia zegarowe
portu (`cmd_clk`, `wr_clk`, `rd_clk`) są niezależne i kolejki robią CDC
sprzętowo. UG388 wprost mówi, że mogą pracować na dowolnej częstotliwości.
Ale logika użytkownika kolejek pójdzie wtedy na twój zegar — przesuwasz
granicę współdzielenia, nie usuwasz jej.

---

## 6. Jedna ścieżka do pilnowania

```
OD: DONE_SOFTANDHARD_CAL  (mcb_drp_clk 60 MHz)
DO: c3_calib_done_buffercc/buffers_0  (c3_clk0)
    wariant A: 0,320 ns      wariant B: 0,283 ns
```

To `calib_done` przechodzące przez `BufferCC`. **Jedyna ścieżka, która nie
zyskuje na zwolnieniu zegara** — jej wymaganie wynika z relacji zboczy dwóch
zegarów, nie z okresu jednego. Przy zmianie częstotliwości pęknie pierwsza.

`BufferCC` chroni przed metastabilnością, ale nie zwalnia z analizy czasowej,
bo oba zegary pochodzą z tego samego PLL. Rozwiązanie:

```
NET "*/c3_calib_done*" TIG;
```

Sprawdź dokładną nazwę sieci w raporcie mapowania — SpinalHDL mógł ją zmienić
przy optymalizacji.

---

## 7. Jak czytać raport czasowy

**Kolumna „osiągnięte" nie mierzy możliwości projektu**, tylko to, gdzie PAR
się zatrzymał. Zatrzymuje się po spełnieniu warunku, a punkt zatrzymania jest
nieprzewidywalny — w tej serii ta sama logika kalibracji dała 13,276 ns przy
budżecie 13,333, potem 16,625 przy 16,667, a potem 16,165 przy tym samym
16,667. Trzy przebiegi, trzy różne wyniki, zero zmian w kodzie.

Praktyczne wnioski:

- Mały zapas **nie** oznacza, że projekt jest na granicy.
- Duży budżet **nie** gwarantuje widocznego zapasu w raporcie.
- Prawdziwą rezerwę szacuj z przebiegu o **ciaśniejszym** ograniczeniu:
  projekt zamknął 9,040 ns przy wymogu 10 ns, więc przy budżecie 13,333 ns
  ma około 4,3 ns rzeczywistej rezerwy na dodatkową logikę.
- Zachowaj raport z najciaśniejszej działającej konfiguracji — to jedyna
  wiarygodna miara, jaką będziesz miał.
