# Etap 3 — `ScalerDsp`

Etap, dla którego robisz cały ten projekt: jawna instancjacja slice'a
DSP48A1 zamiast liczenia na to, że XST się domyśli.

## Pliki

| Plik | Miejsce w repo |
|---|---|
| `ScalerDsp.scala` | `hw/spinal/main/` |
| `DspMacPrimitive.scala` | `hw/spinal/main/` |
| `ScalerDspDemoTop.scala` | `hw/spinal/main/` |
| `ScalerDspTestplan.scala` | katalog testów |

Zmiana: `Makefile` → `TOP := ScalerDspDemoTop`.

## Korekta do dokumentu projektowego

W dokumencie napisałem `BinToBcd` z `binWidth = 11`, bo zakres czujnika
(−40…85 °C) daje maksimum 1850 w dziesiątych stopnia Fahrenheita.
Licząc zakresy przy `ScalerDsp` wyszło, że to za mało: pełna skala
surowej wartości daje **3020**, a 11 bitów kończy się na 2047.

Czujnik nigdy nie zwróci pełnej skali — ale przekręcony bit na I2C już
tak, a wtedy `BinToBcd` zawinie się po cichu i pokaże wiarygodnie
wyglądającą bzdurę zamiast oczywistego śmiecia. **Używaj 12 bitów.**
Kosztuje jeden takt konwersji.

## Matematyka

Trzy tryby, ta sama postać `P = A·B + C`, wynik `P >>> 17`:

| Tryb | A | B | offset w C |
|---|---|---|---|
| °C ×10 | `raw >> 3` | 2000 | −500 |
| °F ×10 | `raw >> 3` | 3600 | −580 |
| %RH ×10 | `raw >> 3` | 1000 | 0 |

`C = (offset << 17) + (1 << 16)`. Drugi człon to zaokrąglenie do
najbliższej — bez niego przesunięcie arytmetyczne daje podłogę i błąd
idzie zawsze w dół. Przy temperaturach ujemnych to systematyczne
zaniżanie, nie symetryczny szum. Osobna asercja w
`dsp_physical_accuracy` pilnuje, żeby błąd nie leżał cały po jednej
stronie.

Czujnik daje 20bitów bez znaku na każdy pomiar - stąd : 
**`raw >> 3`, nie `>> 2`.** Port A to 18 bitów **ze znakiem**, czyli
maksimum 131071. `raw >> 2` sięga 262143 i po cichu przekręca się na
ujemne dla połowy zakresu. Objaw: powyżej 50 °C czujnik pokazuje mróz.
Testpoint `dsp_low_bits_ignored` istnieje po to, żeby czyjaś „poprawka
precyzji” wywaliła się w symulacji, a nie na płytce.

Druga wersja tej samej pułapki jest w `ScalerDsp`:

```scala
mac.io.a := (rawReg >> 3).resize(18).asSInt   // dobrze
mac.io.a := (rawReg >> 3).asSInt.resize(18)   // ZLE
```

Druga linia reinterpretuje 17-bitową wartość bez znaku jako ze znakiem,
więc wszystko powyżej 65535 staje się ujemne. Bez ostrzeżenia.

## Jak wymuszony jest DSP

`DSP48A1` jako `BlackBox` — SpinalHDL emituje samo wywołanie modułu,
ciało dostarcza biblioteka unisim przy syntezie. `OPMODE = 0x0D` daje
`P = M + C`: mux X bierze wynik mnożenia, mux Z bierze port C. Offset
−50 °C liczy post-sumator w tym samym slice, zero LUT-ów.

Wyrównanie ścieżki C wymaga jednego dodatkowego rejestru w fabric:
wynik mnożenia jest opóźniony o dwa stopnie (`A1REG`, potem `MREG`),
a `CREG` daje tylko jeden. `DspMacBehavioral` odwzorowuje to stopień po
stopniu, żeby oba warianty miały identyczną latencję 3.

## Uruchomienie

```
sbt "testOnly *ScalerDspTestplan"
make
```

Siedem testpointów zielonych, jeden `unimplemented`. Przebieg
wyczerpujący (393 216 konwersji) idzie na osobno skompilowanym DUT bez
zapisu fali — z FST plik miałby gigabajty. Licz kilkanaście do
kilkudziesięciu sekund.

## Weryfikacja, że slice faktycznie się użył

To jest właściwa treść tego etapu i nie zobaczysz jej w symulacji.

- [ ] `build/ScalerDspDemoTop_map.mrp`, sekcja utilization:
      **`DSP48A1s: 1 out of 16`**. Zero oznacza, że BlackBox nie trafił
      do biblioteki albo XST go wyrzucił.
- [ ] `build/ScalerDspDemoTop.syr` — brak ostrzeżenia o nierozwiązanej
      instancji `DSP48A1`. Jeśli jest, synteza zostawiła czarną skrzynkę
      i `ngdbuild` dopiero teraz się wyłoży.
- [ ] Dla porównania zbuduj raz z `usePrimitive = false`. Zobaczysz albo
      `DSP48A1s: 1` (XST domyślił się sam), albo kilkadziesiąt LUT-ów.
      To jest odpowiedź na pytanie „po co jawnie” — i zależy od wersji
      ISE, więc warto sprawdzić na swojej.
- [ ] Raport czasowy: ścieżka przez DSP nie powinna być krytyczna przy
      100 MHz z trzema stopniami rejestrów.

## Tabela odczytów na płytce

Pętla 24 s: cztery wartości surowe, w każdej trzy tryby po 2 s. Tryb
pokazują D4/D5/D6, D7 zapala się przy wartości ujemnej, D8 przy
wartości z cyfrą tysięcy (wyświetlacz pokazuje wtedy trzy najmłodsze).

| Wartość surowa | °C | °F | %RH |
|---|---|---|---|
| 0 | `50.0` + D7 | `58.0` + D7 | `00.0` |
| 262144 | `00.0` | `32.0` | `25.0` |
| 393216 | `25.0` | `77.0` | `37.5` |
| 1048575 | `50.0` + D8 | `2.0` + D8 | `00.0` + D8 |

Najłatwiejsza do zapamiętania para: **25.0 °C i 77.0 °F**. Jeśli druga
pozycja pokazuje cokolwiek innego, stała 3600 albo offset −580 są złe.
Ostatni wiersz wygląda dziwnie, bo 150.0 / 302.0 / 100.0 nie mieszczą
się w trzech cyfrach — dlatego jest tam D8.

## Test równoważności model ↔ prymityw

Ten jeden `unimplemented` da się zamknąć, jeśli chcesz. Przepis:

```scala
lazy val dutPrim = Config.sim
  .addRtl(s"${sys.env("XILINX")}/verilog/src/unisims/DSP48A1.v")
  .addRtl(s"${sys.env("XILINX")}/verilog/src/glbl.v")
  .compile { ScalerDsp(() => new DspMacPrimitive) }
```

Ostrzeżenia, na które trafisz: modele unisim mają bloki `specify`
(Verilator je pomija, ale hałasuje) i sięgają po `glbl.GSR`, co wymaga
dociągnięcia `glbl.v` i czasem ręcznego podpięcia. Nie zakładam, że
pójdzie gładko — dlatego jest to `unimplemented`, a nie obietnica
w planie.

Bez tego prymityw weryfikują raport map (czy slice się użył) i tabela
odczytów na płytce (czy liczy to samo, co model). To łapie „nie użył
się” i „liczy zupełnie inaczej”, ale nie złapie różnicy o jeden bit na
skrajnej wartości.

## Następny krok

Etap 4: `Aht10Ctrl` z modelem slave'a AHT10 w symulacji. Pierwszy etap,
w którym wraca I2C i w którym watchdog przestaje być teorią.
