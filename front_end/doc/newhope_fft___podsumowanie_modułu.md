# newhope.fft — podsumowanie modułu

Sep 25, 2026 · @Tomek · aktualizacja 2026-10-01: zegar Mimasa 75 MHz (D-006)

## Przegląd

`newhope.fft` liczy widmo dla front-endu voice changera: od próbek z mikrofonu do zespolonego X\[k\] i mocy |X|². Każdy krok jest osobnym komponentem z własnym testplanem, więc da się go zrozumieć i sprawdzić w izolacji, a potem złożyć.

**Status:** testy przechodzą (SpinalHDL 1.12.3, GHDL i Verilator). Numeryka zgodna z kontraktem numerycznym v1 (D-005).

```
audio (Flow int18, 16 kHz)
  │
  ▼
Framer ──────── ring 512, co 160 próbek ramka, okno Hanna, pakowanie x[2m] + j x[2m+1]
  │  Stream 256 × BfpCplx, exp = 0
  ▼
FftCore ─────── zespolone radix-2 DIF, in-place, 1 motylek, II = 2, BFP, flaga inverse
  │  Stream 256 × BfpPair (Z[k], Z[M−k]) + nextShift
  ▼
RealUnpack ──── X[k] = (S + W^k T) / 2, k = 0..256
  │  Stream 257 × BfpCplx
  ├──▶ PowerSpectrum ── p = re² + im², exp · 2     → bank mel, F0, głośność
  └──▶ Magnitude ────── round(√p), 18 cykli/prążek → do eksperymentów
```

`Rfft` składa Framer, FftCore i RealUnpack w jeden komponent. Moc i moduł podpina się za nim.

### Konwencja liczb (BFP)

Każda wartość na porcie ma postać **mantysa · 2^exp**. Mantysa to liczba całkowita w jednostkach LSB próbki wejściowej (Q0.17 = int18). Wykładnik jest wspólny dla bloku (ramki) i jedzie z każdym elementem strumienia, więc odbiorca nie musi pamiętać nagłówka. `Fragment.last` zamyka blok.

Po log2 wykładnik staje się dodaną stałą: `log2(m · 2^e) = log2(m) + e`. BFP nic więc nie kosztuje dalej w torze cech.

## Numeryka

### Kryterium L1

Przed każdym stopniem motylka cały blok jest przesuwany o `s = shiftFor(L1max)`, gdzie `L1max = max(|re| + |im|)` po elementach bloku. Kryterium to L1, a nie max(|re|, |im|): warunek L1(a), L1(b) < 2^(W−2) gwarantuje, że każda składowa a + b i (a − b)·W mieści się w W bitach. Kryterium na maksimum składowej tego nie gwarantuje, bo (a − b)·W może urosnąć 2√2 raza w jednej składowej.

| L1max (W = 18) | s | Znaczenie |
| --- | --- | --- |
| 0 | 0 | blok zerowy |
| 1 … 2^(W−2) − 1 | msb(L1max) − (W − 3) | normalizacja w lewo, s od −15 do 0 |
| 2^(W−2) … 2^(W−1) − 3 | 1 |  |
| 2^(W−1) − 2 … 2^W − 5 | 2 |  |
| ≥ 2^W − 4 | 3 |  |

Progi dla s > 0 uwzględniają zaokrąglenie: po przesunięciu o s każda składowa rośnie najwyżej o ½, więc L1 najwyżej o 1. Stąd warunek L1/2^s + 1 < 2^(W−2).

Przesunięcie w prawo z zaokrągleniem połowy w górę, wykładnik bloku `+= s`. Iloczyn z twiddle zaokrąglany raz, po zsumowaniu iloczynów (`>> 17`), wynik nasycany do W bitów. Różnica a − b mieści się w W bitach z kryterium, więc mnożenie jest 18×18 i trafia w DSP48A1.

L1max jest zbierane w locie przy zapisach wyników stopnia, bez osobnego przebiegu skanującego. RealUnpack stosuje to samo kryterium raz, przed swoim motylkiem (`nextShift` z rdzenia).

Wiążąca definicja: `newhope.fft.Bfp` w `FftGenerics.scala`. Z niej generują się ROM-y w RTL i z niej korzysta golden model. Logika sprzętowa (`BfpHw`) implementuje te same funkcje osobno, a ich zgodność sprawdzają testy bit w bit.

### Dokładność

SNR widma rfft 512 wobec float, sygnał mowopodobny:

| Poziom | BFP + normalizacja | /2 w każdym stopniu (kontrakt v0) |
| --- | --- | --- |
| 0 dBFS | 74 dB | 64 dB |
| −20 dBFS | 72 dB | 41 dB |
| −40 dBFS | 72 dB | 24 dB |
| −60 dBFS | 75 dB | 3 dB |
| −80 dBFS | 72 dB | −16 dB |

Mowa z 30 cm na INMP441 to okolice −60 dBFS. Bez normalizacji w lewo samo BFP daje tam 45 dB.

Najgorszy SNR samego rdzenia (poziomy 0…−100 dBFS, szum, ton, równomierny, fwd i inv): 91,5 / 87,4 / 75,5 / 70,8 dB dla n = 4 / 8 / 256 / 512. Na rogach zakresu i na 400 losowych ramkach od 0 do −100 dBFS: zero nasyceń.

## Komponenty

| Komponent | Wejście | Wyjście | Przepustowość | Pamięć (Mimas, n = 256) | Mnożarki |
| --- | --- | --- | --- | --- | --- |
| `Framer` | `Flow[SInt(18)]` | 256 × `BfpCplx` | 1 próbka okna / cykl | ring 512 × 18, ROM Hanna 257 × 18 | 1 |
| `FftCore` | n × `BfpCplx` | n × `BfpCplx` albo `BfpPair` | II = 2 na motylek | RAM 256 × 36, ROM twiddle 128 × 36 | 4 |
| `RealUnpack` | n × `BfpPair` | n + 1 × `BfpCplx` | 1 prążek / cykl | ROM W\_N^k 257 × 36 | 4 |
| `PowerSpectrum` | `BfpCplx` | `BfpPower` (uint36, exp · 2) | 1 / cykl | — | 2 |
| `Magnitude` | `BfpPower` | `BfpMag` (uint18) | W cykli / prążek | — | 0 |

Wszystkie porty poza wejściem Framera to `Stream[Fragment[...]]` z pełnym backpressure. Wejście audio to `Flow`, bo próbek z mikrofonu nie da się wstrzymać.

### FftCore

Zespolone FFT radix-2 DIF, in-place, jeden motylek, `FftGenerics(logN, dataWidth = 18, twiddleWidth = 18, expWidth = 8, outOrder)`.

- **Wejście:** n próbek w porządku naturalnym. Wykładnik i `inverse` są brane z pierwszej próbki bloku, a `last` jest ignorowany, bo rdzeń liczy do n sam.
- **Kolejność wyjścia (`outOrder`):** `Natural`, `Bitrev` (surowa kolejność w pamięci) albo `RealPairs`, czyli (Z\[k\], Z\[(n−k) mod n\]) dla RealUnpack. `RealPairs` niesie też `nextShift`.
- **`inverse`:** sprzężony twiddle, bez skalowania 1/n. Skala siedzi w wykładniku jak każda inna.
- **Pamięć:** jeden BRAM w trybie SDP (jeden zapis i jeden odczyt na cykl), stąd II = 2.

Fazy: LOAD → (PREP → RUN → DRAIN) × logN → OUT. Potok motylka, gdzie t0 to wydanie odczytu A:

| Cykl | Co się dzieje |
| --- | --- |
| t0 | odczyt A, odczyt twiddle |
| t1 | odczyt B; A z pamięci do rejestru |
| t2 | B z pamięci; przesunięcie BFP, suma i różnica |
| t3 | cztery iloczyny (DSP48A1); suma nasycona |
| t4 | zapis A; złożenie iloczynów, zaokrąglenie, nasycenie |
| t5 | zapis B |

Zapisy A trafiają zawsze w cykle parzyste względem początku stopnia, a zapisy B w nieparzyste, więc port zapisu nie koliduje. Między stopniami potok jest opróżniany (DRAIN, 5 cykli), bo pierwszy motylek stopnia s + 1 czyta adresy, które ostatni motylek stopnia s jeszcze zapisuje. Adresy: motylek i w stopniu s ma zero wstawione na pozycji p = logN − 1 − s, a indeks twiddle to (i << s) mod 2^(logN−1).

Harmonogram (`FftGenerics`):

```
stageCycles    = n + 6                           PREP 1 + RUN n + DRAIN 5
computeLatency = 1 + logN · (n + 6) + (2 | 3)    ostatnia próbka wejścia → pierwszy valid
busyCycles     = n + computeLatency + (n | 2n)   LOAD + obliczenia + wyjście
```

### RealUnpack

Rozplata widmo sygnału rzeczywistego spakowanego jako z\[m\] = x\[2m\] + j·x\[2m+1\]:

```
Zmk  = conj(Z[(M−k) mod M])
S    = Z[k] + Zmk,   T = −j (Z[k] − Zmk)
X[k] = (S + W_N^k T) / 2,     k = 0..M   (dla k = M para k = 0 i W_N^M = −1)
```

To jeden motylek z przesunięciem `nextShift`. Po nim S i T mieszczą się w W bitach, więc mnożenie jest 18×18, a dzielenie przez 2 wchodzi w zaokrąglenie iloczynu (`>> 18`). Wykładnik wyjścia to exp + nextShift. Im X\[0\] i Im X\[M\] wychodzą dokładnie zero.

### Framer

`FramerGenerics(fftSize = 512, hop = 160, sampleWidth = 18, windowWidth = 18)`. Co `hop` próbek wystawia ostatnie `fftSize` próbek (najstarsza pierwsza), okienkowane periodycznym Hannem (skala 2^17 − 1) i spakowane parami. Przed pierwszymi 512 próbkami bufor zawiera zera.

`overrun` zapala się na stałe (do resetu), gdy nowa próbka nadpisuje jeszcze nieodczytaną albo nowa ramka przychodzi w trakcie czytania poprzedniej. Nowa ramka jest wtedy pomijana.

### PowerSpectrum i Magnitude

- **`PowerSpectrum(w, e)`:** p = re² + im² dokładnie, bez zaokrągleń (2W bitów bez znaku), exp\_p = 2 · exp. To wejście banku mel.
- **`Magnitude(w, e)`:** pierwiastek cyfra po cyfrze, W cykli na prążek, zaokrąglenie do najbliższego: r + (rem > r). Przy 257 prążkach to 4,6 k cykli na ramkę. Jest do eksperymentów, bo bank mel bierze moc.

### Rfft

`Rfft(RfftGenerics(framer, clockHz, sampleRate))` łączy Framer → FftCore(`RealPairs`) → RealUnpack. Na wyjściu jest 257 prążków ze wspólnym wykładnikiem ramki i `last` na prążku Nyquista. `overrun` pochodzi z Framera.

## Budżet czasu

Ramka to 160 próbek przy 16 kHz, czyli 10 ms. Liczby ze wzorów w `FftGenerics`/`RfftGenerics`; `rfft_param_bounds` drukuje je przy każdym uruchomieniu testów.

|  | Mimas V2 (75 MHz) | iCE40UP5K (48 MHz) |
| --- | --- | --- |
| Cykle na próbkę | 4 687 | 3 000 |
| Cykle na ramkę | 749 920 | 480 000 |
| FftCore n = 256, zajętość | 2 868 (0,4%) | 2 868 (0,6%) |
| Cały tor Rfft 512 | 3 132 | 3 132 |
| **Zapas** | **746 788 (99,6%)** | **476 868 (99,3%)** |

Zegar Mimasa to `c3_clk0` = 75 MHz przy LPDDR 150 MHz (D-006). Kolumna Mimasa zakłada `clockHz = 75 MHz` w `RfftGenerics.mimas512`; jeśli preset ma jeszcze 41,67 MHz, `rfft_param_bounds` drukuje stary budżet. Przy rzeczywistym fs ≈ 16 053 Hz (dzielnik I2S 73) na próbkę przypada 4 672 cykle, czyli o 0,3% mniej.

Do tego, jeśli podłączone:

- **Magnitude:** 257 × 18 = 4 626 cykli, 0,6% ramki na Mimasie.
- **FftCore n = 512** (rfft 1024 do autokorelacji F0 z dopełnieniem zerami): 6 202 cykle, 0,8%.

Czas nie jest ograniczeniem: nawet osobne rdzenie do widma (n = 256) i autokorelacji (n = 512) zajmują razem około 1,2% ramki. Rozmiar rdzenia jest ustalany w elaboracji, więc jedna instancja nie obsłuży obu.

## Weryfikacja

Według TESTING-STRATEGY (vertebra): plan w kodzie, etapy V1/V2/V3, konfiguracje z dolną granicą, testy parametrów bez symulacji, stałe ziarna, porównanie transakcji zamiast cykli.

| Testplan | Konfiguracje | Bez symulacji |
| --- | --- | --- |
| `FftCoreTestplan` | n256\_pairs, n256\_nat, n512\_pairs, n4\_bitrev, n4\_pairs | `fft_param_bounds`, `fft_golden_vs_float` |
| `RealUnpackTestplan` | m256, m4 | `unpack_param_bounds`, `unpack_vs_float` |
| `FramerTestplan` | l512\_h160, l16\_h6, l8\_h2 | `framer_param_bounds` |
| `PowerSpectrumTestplan`, `MagnitudeTestplan` | w18, w6 | `*_param_bounds`, `mag_rounding` |
| `RfftTestplan` | r512\_h160, r16\_h6 (tempo przy dolnej granicy) | `rfft_param_bounds`, `rfft_vs_float` |

**Co sprawdzają scenariusze:**

- **Bit w bit wobec golden modelu:** mantysy, wykładnik, `nextShift` i `last`, w kolejności `outOrder`.
- **Poziomy od 0 do −100 dBFS:** normalizacja w lewo, wykładnik.
- **Przypadki złośliwe dla kryterium L1:** rogi zakresu, DC, przemiennie, impuls −2^17, rotacja 45°. Golden nie może się na nich nasycić.
- **Latencja rdzenia:** zgodność ze wzorem `computeLatency` z tolerancją ±4 cykle na latencję `StreamFifo`.
- **Im X\[0\] = Im X\[M\] = 0** na wyjściu RTL.
- **`overrun` Framera:** zapala się przy zatkanym wyjściu i nie zapala przy tempie w granicach.
- **Tor end-to-end** bez `overrun` przy minimalnym tempie próbek.

**Progi SNR** w testach bez symulacji: SNR ≥ 6,02·(W − 4) − 3·logN dB dla rdzenia i o 3 dB mniej dla toru z rozplataniem. Zapas wobec zmierzonego minimum wynosi co najmniej 10 dB.

**Konformancja portów:** każdy port `Stream` ma testpointy `StreamConformance` (`payload_stable`, `reset_quiet`, `backpressure`). Checkery czytają payload wprost z `io` (`flatten`), więc komponenty nie mają żadnej instrumentacji. `*_stress_with_rand_reset` (V3) jest wszędzie `unimplemented` z podanym powodem.

**Zabezpieczenia:** każdy scenariusz ma `SimTimeout` (`guard()`, 2 mln cykli), więc zawieszenie jest błędem testu, a nie wiszącym sbt. Reset w teście czeka na zbocza przez `waitActiveEdge`, nie `waitSampling`.

### Golden model i lustro

- **`FftGolden.scala`** — model bit-exact w Scali. Każda operacja ma odpowiednik w RTL z tą samą kolejnością zaokrągleń i nasyceń. Asercje w środku łapią błędy kryterium BFP, nie RTL.
- **`jl/FftGolden.jl`** — lustro w Julii (biblioteka standardowa), do eksperymentów i porównań z modelem. `julia jl/FftGolden.jl` sprawdza je bit w bit wobec `jl/fft_golden_vectors.txt`: cztery ramki rfft 512 i jedno odwrotne FFT 256.

Wiążąca jest Scala. Wektory trzeba generować ponownie z `FftGolden.scala` przy każdej zmianie numeryki.

## Pliki i uruchamianie

```
lib/fft/
  hw/spinal/main/
    FftGenerics.scala    Bfp (numeryka, tablice), OutOrder, FftGenerics,
                         FramerGenerics, RfftGenerics (+ mimas512, ice40512)
    BfpHw.scala          typy Cplx, BfpCplx, BfpPair, BfpPower, BfpMag; BfpHw
    FftCore.scala
    RealUnpack.scala
    Framer.scala
    Spectrum.scala       PowerSpectrum, Magnitude
    Rfft.scala
  hw/spinal/test/
    FftGolden.scala      model bit-exact
    FftTestKit.scala     modele magistrali, monitor, bodźce, idleReset, guard
    *Testplan.scala      FftCore, RealUnpack, Framer, Spectrum, Rfft
  jl/
    FftGolden.jl, fft_golden_vectors.txt
  docs/
    D-005-zmiana-kontraktu.md
```

**Zależności:** kod syntezowalny nie zależy od niczego poza SpinalHDL. Testy korzystają z `vertebra` (`TestplanSuite`, `StreamConformance`, `SimEnv`) i `core` (`Conventions.spinal`: reset synchroniczny aktywny wysoko, `inlineRom = true`).

```
sbt fft/test                                         # wszystko
sbt "fft/testOnly newhope.fft.FftCoreTestplan"       # jeden komponent
sbt "fft/testOnly *Testplan -- -z n4"                # jedna konfiguracja
sbt "fft/testOnly *Testplan -- -z param"             # tylko testy parametrów
julia lib/fft/jl/FftGolden.jl                        # lustro vs wektory
```

W sesji sbt przełączniki działają bez przeładowania: `wavesOn`/`wavesOff`, `backendVrl`/`backendGhdl`, `simStatus`. `simClean` czyści workspace'y symulacji, a cache zostaje. Przebiegi trafiają do `lib/fft/simWorkspace/<komponent>_<konfiguracja>_<backend>/`.

## Lekcje z uruchamiania

Pułapki, które wyszły przy pierwszej kompilacji. Każda dotyczy całego workspace'u, nie tylko fft.

| Objaw | Przyczyna | Rozwiązanie |
| --- | --- | --- |
| `ArrayIndexOutOfBounds: Index -15` w elaboracji | `switch` po `SInt` z ujemnym literałem: Spinal 1.12.3 indeksuje tablicę pokrycia wartością literału | `switch(s.asBits)` z wzorcami U2 (`BfpHw.shiftRound`) |
| test wisi w `*_reset_quiet` | `waitSampling` pod aktywnym resetem asynchronicznym nie widzi zboczy | `waitActiveEdge` w resecie; `guard()` na każdy scenariusz |
| `found collection.Seq, required Seq` | Scala 2.13: `Seq` to `immutable.Seq`, a `flatten` i `ArrayBuffer` dają `collection.Seq` | `.toList` albo parametr typu `collection.Seq` |
| `math.sqrt` nie istnieje | `import spinal.lib._` zasłania `scala.math` pakietem `spinal.lib.math` | `import spinal.lib.{math => _, _}` |
| `not found: SimThread`, `Fragment` | `SimThread` jest w `spinal.sim`, `Fragment` w `spinal.lib` | jawne importy |

## Odłożone

- **`SinCosRom` ćwierćfalowy** wspólny z oscylatorami syntezy harmonicznej. Na razie FftCore i RealUnpack mają pełne tablice. Tablica RealUnpack jest nadzbiorem tablicy rdzenia (W\_M^k = W\_N^{2k}).
- **`RealPack`**, czyli odwrotność RealUnpack: IFFT sygnału rzeczywistego do autokorelacji z mocy i overlap-add szumu. Rdzeń ma już flagę `inverse`.
- **Mnożenie 3-mnożeniowe** dla iCE40, gdzie jest tylko 8 bloków DSP.
- **`FftGolden` do `main`** jako `newhope.fft.golden`, żeby testy `dsp` i repo `NewHope` mogły go używać.
- **`*_stress_with_rand_reset`** (V3): wymaga modelu nadajnika przerywalnego resetem.
- **Kalibracja:** tolerancja `fft_latency` (±4) i minimalne tempo w `RfftTestplan` (+3 próbki zapasu) są konserwatywne. Można je zawęzić przebiegiem.

## Otwarte pytania

| Pytanie | Zamyka | Uwagi |
| --- | --- | --- |
| F0: autokorelacja przez FFT jest kołowa | M1 | okno 512 i opóźnienia do 267 wymagają rfft 1024 z dopełnieniem zerami (`n512_pairs`) albo metody w dziedzinie czasu |
| Format energii pasma mel | moduł mel | akumulacja mantys mocy przy wspólnym wykładniku ramki, szerokość akumulatora |
| Wybór strumieni wewnętrznych do konformancji | gdy potrzebne | `simPublic` przy kompilacji (jak `phyCmd` w I2C); przy wielu strumieniach wraca pomysł `vertebra-instrument` |
