# Etap 5 — integracja

## Pliki

| Plik | Miejsce w repo |
|---|---|
| `DemoSupport.scala` (`MeasTimer`, `SwitchInput`) | `hw/spinal/main/` |
| `DisplayFormat.scala` | `hw/spinal/main/` |
| `Aht10Demo.scala` | `hw/spinal/main/` |
| `DisplayFormatTestplan.scala` | `hw/spinal/test/` |

Zmiany: `Makefile` → `TOP := Aht10Demo`. UCF — odkomentuj sekcje DIP
oraz I2C (jeśli jeszcze nie).

## Dwie rzeczy, które wyszły dopiero przy składaniu

**`BinToBcd` musiał wejść do środka `DisplayFormat`.** Zaokrąglenie przy
odrzucaniu części ułamkowej to dodanie 5 **przed** podziałem przez 10,
czyli przed konwersją na BCD. A czy w ogóle odrzucamy ułamek, wynika
z formatu. Decyzja o formacie musi więc zapaść wcześniej niż konwersja.
Gdyby konwerter stał obok, ktoś musiałby mu przekazać „zaokrąglaj albo
nie” — czyli i tak podjąć tę decyzję, tylko w miejscu, które o formacie
nic nie wie.

**Szerokość konwertera to 13 bitów, nie 12.** Wartość bezwzględna
z `SInt(13 bits)` sięga 4096, a po dodaniu zaokrąglenia 4101. To już
druga korekta tej liczby w projekcie — pierwsza była z 11 na 12 przy
`ScalerDsp`. Za każdym razem powód ten sam: liczyłem zakres czujnika
zamiast zakresu typu.

## Ścieżka przeliczenia

Przełączniki nie dotykają `Aht10Ctrl`. Jedna transakcja zwraca oba
pomiary, więc przełączenie zmienia tylko stałe w `ScalerDsp` i wynik
pojawia się po czterech taktach, bez ruchu na magistrali. To wycofany
punkt [3] wymagań, teraz widoczny w kodzie: `recompute` podnosi się od
nowej próbki **albo** od zmiany przełącznika, a rejestr surowych
wartości leży pomiędzy, więc obie drogi wyglądają tak samo.

## Uruchomienie

```
sbt "testOnly *DisplayFormatTestplan"
sbt "testOnly *Testplan"          # cala suita, piec planow
make
```

## Lista kontrolna na płytce

Najpierw to, o czym uprzedzałem od etapu 1:

- [ ] **Sprawdź, który przełącznik działa.** Numato numeruje magistrale
      od prawej, co potwierdziły enable'y wyświetlacza. Jeśli `dp[0]`
      na F17 okaże się fizycznie DP8, zamień LOC-e w UCF — nie w RTL,
      tak samo jak przy enable'ach.

Potem właściwe demo:

- [ ] Po włączeniu przez chwilę `---`, potem temperatura pokoju
- [ ] D2 (`ready`) zapalona, D1 (`error`) zgaszona, D4 (`calibrated`)
      zapalona
- [ ] D3 (`busy`) mruga co dwie sekundy
- [ ] DP2 przełącza °C / °F **natychmiast**, bez czekania na pomiar —
      jeśli czeka dwie sekundy, `recompute` nie łapie zmiany
      przełącznika
- [ ] `25.0` przełączone na Fahrenheita daje `77.0`
- [ ] DP1 przełącza na wilgotność, też natychmiast
- [ ] Chuchnięcie na czujnik: wilgotność rośnie w ciągu paru sekund,
      temperatura wolniej
- [ ] Wilgotność powyżej 99.9 % pokazuje `100` bez kropki
- [ ] Wyciągnięcie czujnika: `---` i D1, **układ nie zamiera**
- [ ] Wpięcie z powrotem: wraca do pomiarów bez resetu płytki

## Stan wymagań

| Nr | Wymaganie | Gdzie |
|----|-----------|-------|
| 1 | DP1 temperatura / wilgotność | `Aht10Demo`, wybór `raw` i trybu |
| 2 | DP2 °C / °F | `ScalerDsp`, stałe B i C |
| 3 | ~~restart licznika po DP1~~ | wycofane — jedna ramka niesie oba pomiary |
| 4 | wynik przeliczony na wyświetlaczu | `DisplayFormat` + `SevenSegMux` |
| 5 | skalowanie w slice DSP | `DspMacPrimitive`, `DSP48A1s: 1 out of 16` |
| 6 | odpytywanie co `nn` sekund | `MeasTimer`, `periodMs = 2000` |

## Co zostało otwarte

Trzy rzeczy, świadomie niezamknięte:

**`dsp_primitive_equivalence`** — nadal `unimplemented`. Prymityw
zweryfikowany strukturalnie (raport map) i funkcjonalnie (tabela
odczytów), ale nie bit w bit wobec modelu. Przepis z `addRtl`
i bibliotekami unisim jest w `etap3-README`.

**Organizacja `build.sbt`** — `i2c % "compile->compile;test->test"`
działa, ale mówi, że `I2cAgent` siedzi w złej konfiguracji. Wydzielenie
`i2cSim` warto zrobić, gdy pojawi się trzeci konsument agenta.

**Punkt 3 z pytań otwartych dokumentu** — czy `ScalerDsp` ma być jednym
slice z multipleksowanymi stałymi, czy trzema równoległymi.
Odpowiedź z etapu 3: jednym, i to nie z powodu zasobów, tylko dlatego
że trzy równoległe liczyłyby dwie wartości, których nikt nie ogląda.

Pytanie 4 (mruganie przy ujemnej temperaturze) odpadło — minus mieści
się na wyświetlaczu, a przy jednocyfrowej wartości przesuwa się w prawo
(` -5` zamiast `-05`).
