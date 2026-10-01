# newhope.frontend — N0 (I2S RX) i N1 (filtr DC)

Węzły N0 i N1 z grafu architektury: od pinów INMP441 do `Flow[SInt(18)]`
w Q0.17, czyli dokładnie typu wejścia `Framer` / `Rfft` z `newhope.fft`.

```
SCK, WS ◀─┐
SD ──────▶ I2sMicRx (N0) ── Flow[SInt(18)] ──▶ DcFilter (N1) ── Flow[SInt(18)] ──▶ Framer (N2)
           słowo24[23:6]                        y = x − x1 + a·y1
```

```scala
val fe   = new MicFrontEnd(FrontEndGenerics.mimas)
val rfft = new Rfft(FftGenerics.mimas512)
rfft.io.input << fe.io.output
```

## N0 · I2sMicRx

Master I2S: sam generuje SCK = 75 MHz / 73 i WS = SCK / 64, więc
fs = 16 053,08 Hz (+5,7 centa, kontrakt D-006). Lewy kanał (L/R = GND),
górne 18 bitów 24-bitowego słowa (obcięcie). SD przez synchronizator
2 FF, próbkowane na narastającym SCK. Po resecie WS = 1, więc pierwsze
opadające zbocze otwiera lewy slot prawdziwą zmianą WS i pierwsza próbka
jest pełna. Wszystkie piny prosto z rejestrów. Bez mnożników i BRAM.

## N1 · DcFilter

`y[n] = x[n] − x[n−1] + a·y[n−1]`, `a = 1 − 2π·30/fs`. Zamiast mnożenia
1 − a jest rozkładane na potęgi dwójki: przy fs = 16 053 Hz
`1 − a = 2⁻⁶ − 2⁻⁸`, fc = 29,94 Hz, **zero DSP48A1**.

| Pozycja | Wartość (Mimas) |
| --- | --- |
| Stan | 29 b = 18 + 3 + G, G = 8 bitów ochronnych |
| Błąd wobec float | ≤ 0,58 LSB (G = 6: 0,65; G = 0: 31 LSB cyklu granicznego) |
| \|H\| przy 30 / 60 / 80 Hz / 1 kHz | −2,98 / −0,92 / −0,52 / +0,05 dB |
| Stała czasowa | 85 próbek (5,3 ms); pełnoskalowy offset znika po ~1 070 próbkach |
| Latencja | 4 cykle; próbki co ≥ 2 cykle (`overrun` przy szybszych) |
| `bypass` | wariant „dc_filter = false”: ta sama latencja, filtr liczy dalej |

Zaokrąglenia: przesunięcie z zaokrągleniem połowy w górę, nasycenie
na wyjściu, jak w `newhope.fft`. Uwaga: przy 60 Hz (dolna granica F0)
filtr tłumi 0,9 dB, a przy 80 Hz (dolna granica mel) 0,5 dB. Model w
PyTorch musi mieć ten sam filtr przed STFT, inaczej cechy się rozjadą.

## Weryfikacja

| Testplan | Konfiguracje | Bez symulacji |
| --- | --- | --- |
| `DcFilterTestplan` | mimas, g6_w10 (dolna granica G), t3_16k | `dc_param_bounds`, `dc_golden_vs_float` |
| `I2sMicRxTestplan` | mimas (dzielnik 73), d8 (dolna granica), d9_right, d8_w24 | `i2s_param_bounds` |
| `MicFrontEndTestplan` | d8 + Framer 16/6 i 512/160 | `fe_param_bounds` |

`MicFrontEndTestplan` składa N0 → N1 → `Framer` w jednej symulacji i
porównuje ramki z `FftGolden.framer(DcGolden(słowo >> 6))`. Model
mikrofonu (`MicModel`) wystawia bity na pinach jak INMP441, a bit
opóźnienia, bity 25..32 i drugi kanał wypełnia losowymi śmieciami.

Dolna granica dzielnika (8) wynika z `sckLow ≥ syncStages + 2` i jest
ciasna: przy 7 model cyklowy gubi bity.

Porty są `Flow`, więc `StreamConformance` (payload_stable, backpressure)
nie ma zastosowania; ciszę w resecie sprawdzają `dc_reset` i `i2s_reset`.

## Pliki

```
lib/frontend/
  hw/spinal/main/
    FrontEndGenerics.scala   I2sMicGenerics, DcGenerics, DcTerm, Dc.csd, FrontEndGenerics (+ mimas)
    I2sMicRx.scala           N0
    DcFilter.scala           N1, DcHw
    MicFrontEnd.scala        N0 + N1
  hw/spinal/test/
    FrontEndGolden.scala     I2sGolden, DcGolden (bit-exact, float, |H|)
    FrontEndTestKit.scala    MicModel, FlowMonitor, feed, idleReset, guard
    DcFilterTestplan.scala
    I2sMicRxTestplan.scala
    MicFrontEndTestplan.scala  (MicFramerTop: N0 → N1 → Framer)
```

Synteza zależy tylko od SpinalHDL. Testy: `vertebra` oraz `fft`
(`Framer` z main, `FftGolden` i `Cx` z test), więc w `build.sbt`
potrzebna jest zależność test→test, np.:

```scala
lazy val frontend = (project in file("lib/frontend"))
  .settings(/* te same ustawienia co fft: scalaSource = hw/spinal/main, hw/spinal/test */)
  .dependsOn(core, vertebra % "test->compile", fft % "compile->compile;test->test")
```

```
sbt frontend/test
sbt "frontend/testOnly *Testplan -- -z param"     # bez symulacji
sbt "frontend/testOnly newhope.frontend.MicFrontEndTestplan"
```
