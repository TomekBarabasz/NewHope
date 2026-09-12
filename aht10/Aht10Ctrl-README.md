# Etap 4 — `Aht10Ctrl`

Pierwszy etap z prawdziwym sprzętem po drugiej stronie i pierwszy,
w którym watchdog przestaje być teorią.

## Pliki

| Plik | Miejsce w repo |
|---|---|
| `Aht10Ctrl.scala` | `hw/spinal/main/` |
| `Aht10SlaveModel.scala` | katalog symulacji |
| `Aht10CtrlTestplan.scala` | katalog testów |

Zmiana w UCF: odkomentuj sekcję I2C (`scl` → U7, `sda` → V7 na złączu
P6). **Nie P9** — bank 1.8 V od LPDDR.

## Trzy decyzje projektowe

**ROM komend zamiast stanu na bajt.** Sekwencja odczytu to dziewięć
komend, inicjalizacja sześć — stan FSM na każdą dałby ponad dwadzieścia
stanów robiących dokładnie to samo. Zamiast tego jest ROM słów
11-bitowych (`[10:9]` tryb, `[8]` ack, `[7:0]` dane) i licznik. Faza to
ciągły przedział w ROM-ie, a cała logika sprowadza się do „wystaw
komendę, poczekaj na `fire`, przesuń się”.

**Nie ma stanu `sCheckCal`.** W dokumencie projektowym był jako
opcjonalny — okazał się zbędny. Bajt statusu przychodzi z **każdym**
pomiarem, więc bit CAL czytamy za darmo w `sParse`. To odpowiedź na
pytanie otwarte nr 1.

**`rsp.valid` tylko po WRITE i READ.** `I2cMaster` przy START i STOP
podnosi sam `cmd.ready`, bez odpowiedzi. Dlatego brak ACK sprawdzany
jest warunkowo (`when(isWrite && !rsp.ack)`), a nie na każdym `fire`.
Kod, który sprawdza ACK bezwarunkowo, wywali się na pierwszym STOP-ie.

## Watchdog

`I2cMaster` nie ma timeoutu — masz to udokumentowane jako
`unimplemented("host_stretch_timeout")`. Cały ciężar żywotności leży
więc na liczniku w `Aht10Ctrl`: brak `cmd.fire` przez `watchdogTicks`
przerywa sekwencję i wraca do rozruchu.

Licznik chodzi **tylko w trakcie sekwencji**. Odliczanie 75 ms w `sWait`
jest legalnie długie i nie może wywoływać alarmu.

Przerwanie sekwencji zostawia magistralę bez STOP-a. Następny START
zwykle ją odzyskuje, ale to „zwykle”, nie „zawsze” — świadomy
kompromis wobec pętli resetu, która sama by się wieszała.

## Ryzykowne miejsca w tym etapie

Trzy rzeczy, które mogą nie zadziałać za pierwszym razem, wraz z tym,
gdzie szukać:

**Model slave'a.** To najbardziej niepewny element całego projektu.
Napisałem go na surowych pinach, bo nie znam API `I2cBusModel`
z `I2cAgent.scala`. Jeśli tamten udostępnia szkielet slave'a albo gotowe
podłączenie wired-AND, warto ten model na nim oprzeć — połowa
`Aht10SlaveModel` wtedy znika. Objawy błędu w modelu wyglądają jak błąd
w RTL, więc przy pierwszym failu sprawdź falę: czy slave w ogóle
odsyła ACK na adres.

**Częstotliwość SCL w testach.** Ustawiłem 5 MHz przy zegarze 100 MHz,
żeby `quarterCycles` wyszło 5 i przebiegi były krótkie. To daleko od
docelowych 100 kHz — jeśli filtr wejściowy zacznie gubić zbocza
(`I2cGenerics` ma na to asercję i komentarz o progu), podnieś
`quarterCycles`, zwiększając dzielnik.

**`aht10_read_nack_last`.** Ten scenariusz jest najsłabszy z całej
suity: sprawdza NACK pośrednio, przez to, że rozpakowanie ramki w innym
teście dało poprawne wartości. Porządna wersja wymaga licznika bitów ACK
w modelu slave'a. Jeśli chcesz go domknąć, dodaj do modelu pole
`ackedBytes : Seq[Boolean]` i sprawdź, że wynosi pięć razy `true` i raz
`false`. Zostawiłem w kodzie komentarz w tym miejscu.

## Uruchomienie

```
sbt "testOnly *Aht10CtrlTestplan"
```

Wszystkie stałe czasowe skrócone: `tickCycles = 4`, czyli
„milisekunda” trwa cztery takty. Bez tego jeden pomiar to 9,5 mln
taktów symulacji. Proporcje między opóźnieniami zostają, więc
sprawdzane zależności są te same.

## Lista kontrolna na płytce

Etap 4 nie ma jeszcze własnego topu — wyświetlacz dostanie dane dopiero
w etapie 5. Sensowny podgląd to LED-y podpięte do `io.status`:

- [ ] D2 (`ready`) zapala się po chwili od włączenia — czujnik odpowiada
- [ ] D1 (`error`) zgaszona
- [ ] D4 (`calibrated`) zapalona po pierwszym pomiarze
- [ ] D3 (`busy`) mruga co dwie sekundy — widać cykliczne transakcje
- [ ] Wyciągnięcie czujnika: D1 zapala się, D2 gaśnie, **układ nie
      zamiera** — to jest test watchdoga na żywo i najważniejszy punkt
      tej listy
- [ ] Wpięcie z powrotem: wraca do gotowości bez resetu płytki

Jeśli nie masz jeszcze rezystorów podciągających 4,7 kΩ, ostatnie dwa
punkty i tak zadziałają — brak podciągnięć wygląda dla kontrolera jak
martwa magistrala, czyli dokładnie przypadek watchdoga.

## Następny krok

Etap 5: integracja. `MeasTimer`, `SwitchInput`, `DisplayFormat` i
spięcie wszystkiego w jeden top. Wtedy też sprawdzenie, czy `dp[0]` na
F17 to faktycznie DP1, czy — jak enable'y wyświetlacza — DP8.
