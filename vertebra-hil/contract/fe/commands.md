# Kontrakt FE: frontend N0 – N4 (I2S RX z INMP441, filtr DC, Framer, FFT, moc)

Uzupełnienie `../commands.md` o to, co zna frontend: DUT to `MicFrontEnd` z `front_end` (N0 `I2sMicRx` + N1 `DcFilter`). FPGA jest masterem I2S tak jak w urządzeniu. ESP32-S3 jest slave'em i udaje mikrofon INMP441: nadaje bodziec wgrany wcześniej przez PC, a w tym samym czasie nagrywa do PSRAM ramki wyniku, które FPGA odsyła na SD_F2E. Ocena jest na PC: bit w bit z `DcGolden` (`vertebra-hil.md` §12).

```
PC --load--> ESP32 PSRAM --SD_E2F--> I2sMicRx -> DcFilter -> (y, x) --SD_F2E--> ESP32 PSRAM --rec--> PC
                 ^ SCK/WS z I2sMicRx (FPGA master, 75 MHz / 73 / 64 = 16 053 Hz) ^
```

Kod: `FeFrame`, `FeHilRegs` i `FeHilVariant` w `fpga/hw/spinal/main/fe/FeHilRegs.scala`. Tę samą definicję czytają harness i host, a ocenę robi `FeCheck` (`fpga/hw/spinal/test/fe/`). Przy rozbieżności z tym plikiem wygrywa kod.

## Identyfikator

`ver` zwraca `ip=fe`, rejestr `ip_id` (`0x002`) ma wartość `0x46450000` („FE\0\0”).

## Ramki na magistrali

Obie strony mają slot 32 i słowo 32 bity, format Philips. ESP32 pracuje w full duplex na jednym kontrolerze (TX i RX na zegarze FPGA).

**ESP32 → FPGA (bodziec), jedna ramka na słowo z `load`:**

| Kanał | Treść |
| --- | --- |
| L | słowo bodźca. PC składa je jako `mic24 << 8 \| śmieci8`: 24-bitowe słowo mikrofonu i 8 bitów, które INMP441 trzyma w Hi-Z |
| R | `~L`. INMP441 z L/R = GND milczy w prawym slocie; tu są śmieci, które DUT ma zignorować |

N0 bierze górne `sampleWidth` bitów lewego słowa: `x = L[31:14]` (ze znakiem), czyli `mic24[23:6]`. Po wyczerpaniu bodźca ESP32 nadaje zera (`auto_clear`).

**FPGA → ESP32 (wynik, jedna ramka na próbkę N1), układ v2:**

| Kanał | Bity | Pole |
| --- | --- | --- |
| L | 31:14 | `y`: wyjście `DcFilter` (Q0.17) |
| L | 13:6 | `idx`: numer próbki od startu, mod 2^8 |
| L | 5 | `rst`: pierwsza próbka po resecie DUT-a, także po starcie |
| L | 4 | `bypass`: ta próbka ominęła filtr (`y == x`) |
| L | 3 | `overrun`: lepki `overrun` filtra albo Framera |
| L | 2 | `aux_valid`: ramka niesie bajt kanału pomocniczego |
| L | 1 | `aux_sof`: ten bajt otwiera rekord |
| L | 0 | 0 |
| R | 31:14 | `x`: próbka N0, z której policzono `y` (echo) |
| R | 13:6 | `aux`: bajt kanału pomocniczego (rekordy N2–N4, niżej) |
| R | 5:0 | znacznik `0x2D` |

Ramka bez znacznika to cisza: przed startem, w trakcie `tail` po stop, przy niedoborze nadajnika powrotnego albo ramka ucięta resetem DUT-a. Znacznik leży na samym końcu ramki, bo reset zatrzymuje SCK w połowie ramki, a ucięta reszta to zera. Host taką ramkę pomija. Echo `x` idzie obok potoku filtra, więc host liczy golden z tego, co filtr naprawdę dostał. Błąd transportu (I2S w którąkolwiek stronę) nigdy nie udaje błędu filtra.

Układ v1 (do `039cd8f`: `idx` 14 b, znacznik `0x5A5` w R, bez kanału pomocniczego) nie jest już używany; bitstream i host muszą być z tej samej wersji (`HilBench` sprawdza `build`).

## Ocena (host, `FeCheck`)

1. Dekodowanie ramek; początek biegu to pierwsza ramka z `idx = 0` i `rst`.
2. Ciągłość `idx`. Przerwa jest dozwolona tylko tuż przed ramką z `rst`, najwyżej 2 ramki: reset DUT-a zatrzymuje SCK w połowie ramki powrotnej.
3. **N1:** na każdym odcinku między flagami `rst` `y == DcGolden.run(x)` od stanu zerowego, a w ramkach z `bypass` `y == x`. Golden liczony jest po całym odcinku, bo filtr w bypassie liczy dalej.
4. **N0:** w pierwszym odcinku `x == L[31:14]` kolejnych słów bodźca, od przesunięcia k ≤ 16 ramek, a po końcu bodźca zera.
5. Flaga `bypass` dokładnie w oknie `[bypass_from, bypass_to)` `idx` (bieg bez resetów).
6. Liczniki FPGA: `frames` = ramki nagrania (+ zgubione przy resetach), a `x_sum`, `y_sum` = sumy z nagrania.

Do raportu idzie też `max |y − float|` (`DcGolden.runFloat`), czyli `dc_golden_vs_float` policzony na wyniku z krzemu.

## N2 – N4: rekordy w kanale pomocniczym (wariant `mimas_n4`)

DUT w wariancie z N4 to `MicFrontEnd` → `Rfft` (Framer 512/160, `FftCore`, `RealUnpack`) → `PowerSpectrum`, wszystko w domenie resetu DUT-a. Ramka widma (co `hop` = 160 próbek) ma ok. 9,2 kbit na każdym węźle, a cały powrót to 64 b na próbkę. Pełne ramki się nie mieszczą, ale ramka widma to deterministyczna funkcja strumienia `y`. Host liczy golden sam, a FPGA przesyła dowód zgodności: CRC32 każdej ramki na trzech węzłach i co `dump_every` ramek pełne widmo mocy. Kod: `FeN4` (`FeHilRegs.scala`), `FeN4Tap`, ocena `FeN4Check`.

**CRC32:** wielomian `0x04C11DB7`, start `0xFFFFFFFF`, bez odbicia i bez XOR na końcu (CRC-32/MPEG-2 po bitach). Każdy element to słowo 36 b podawane od MSB:

| Węzeł | Elementy na ramkę | Słowo |
| --- | --- | --- |
| N2 (wyjście Framera) | 256 par | `re[17:0] ## im[17:0]` |
| N3 (`Rfft`) | 257 prążków | `re[17:0] ## im[17:0]` |
| N4 (`PowerSpectrum`) | 257 prążków | `p[35:0]` |

Wykładnik bloku idzie osobno w rekordzie.

**Rekord CRC (typ `0x01`, 23 B)**, dla każdej ramki widma: typ, `trig` (3 B LE), flagi, potem dla N2, N3, N4 po kolei CRC32 (4 B LE) i wykładnik (2 B LE, ze znakiem).
- `trig` to numer próbki N1 od startu (licznik `frames`, mod 2^24), po której Framer wydał ramkę, czyli ostatnia próbka okna.
- Flagi: bit k oznacza, że węzeł k (N2, N3, N4) miał dokładnie oczekiwaną liczbę elementów z `last` na ostatnim.

**Rekord zrzutu (typ `0x02`, 6 + 5 · 257 = 1291 B):** typ, `trig` (3 B), wykładnik N4 (2 B), potem 257 × `p` (36 b w 5 B LE). Zrzucane są ramki nr `dump_every`, 2 · `dump_every`, … (licząc od 0 od startu), jeśli bufor jest wolny.

Rekordy wychodzą bajt na ramkę wyniku. Rekord CRC wchodzi do kolejki (256 B) w całości albo wcale (`n4_aux_drop`). Arbiter przełącza między CRC a zrzutem tylko na granicy rekordu.

**Ocena (host, `FeN4Check`):**

1. Sklejanie rekordów z bajtów `aux`. Przerwa w numeracji ramek (zgubione przy resecie) albo `sof` w środku rekordu oznacza rekord rozerwany.
2. Golden jak sprzęt. Framer po każdym resecie podaje zera zamiast pozycji pierścienia niezapisanych od resetu (licznik `fill`), więc każdy odcinek (od flagi `rst`) startuje z pierścieniem zer. Ramka f odcinka ma `trig` = numer próbki `(f + 1) · hop − 1`. Nieweryfikowalna jest tylko ramka z próbką zgubioną przy resecie w oknie (koniec odcinka przed flagą `rst`). Potem `FftGolden.rfftFrame` i `power`.
3. Rekord CRC weryfikowalnej ramki: flagi = 7, a CRC i wykładnik N2, N3, N4 równe goldenowi. Komunikat nazywa pierwszy niezgodny węzeł („N4 CRC …, golden … (N3 zgodny)”), co wskazuje blok: Framer, `FftCore`/`RealUnpack` albo `PowerSpectrum`.
4. Zrzut: `p` prążek po prążku i wykładnik równe goldenowi.
5. Każda weryfikowalna ramka ma rekord CRC. Braki są dozwolone tylko w trzech przypadkach:
   - tuż przed resetem: DUT nie zdążył policzyć ramki;
   - pod koniec nagrania: rekord jeszcze w kolejce, za zrzutem;
   - przy rekordach rozerwanych przez reset: braków najwyżej tyle, ile rozerwanych rekordów.

Uwaga do DUT: do commita 362b8db pierścień Framera przechodził przez reset i pierwsze 3 ramki po resecie niosły próbki sprzed niego. HIL to pokazał; od poprawki golden zakłada zera po każdym resecie. Bitstream sprzed poprawki nie przejdzie `hw_n4_reset` (pierwsze ramki odcinków niezgodne na N2).

## ESP32: klucze `cfg`

Rola jest stała: slave, `w = slot = 32`, TX i RX na pinach magistrali (`../i2s/commands.md`, „Piny”).

| Klucz | Wartości | Znaczenie |
| --- | --- | --- |
| `fs` | 8000…96000 Hz | nominał fs dla sterownika (DMA, zegar modułu slave'a); ESP32 idzie za SCK FPGA. Dla Mimas: 16053 |
| `rec` | 0, 1 | nagrywanie ramek wyniku do PSRAM, domyślnie 1 |

Wymagane w pierwszym `cfg` po resecie: `fs`.

## ESP32: komendy roli

| Komenda | Stan | Odpowiedź |
| --- | --- | --- |
| `load off=<n> data=<hex>` | stop | `ok n=<słów w buforze>`. `data` to 1…29 słów po 8 cyfr hex (małe albo duże litery), wpisanych od słowa `off`. `off=0` zaczyna nowy bodziec; inne `off` muszą być równe bieżącej długości (`err 3` przy przerwie albo nakładce, więc zgubiona linia nie przejdzie po cichu) |
| `rec off=<ramka> n=<ramek>` | stop | `ok n=<linii>`, linie po najwyżej 15 ramek (`LLLLLLLLRRRRRRRR…`, hex, małe litery), `ok end`. `n` ≤ 4096 na komendę; `off + n` ≤ liczby nagranych ramek (`err 3`) |

Pojemności (PSRAM 8 MB, N16R8): bodziec do 262 144 słów (ok. 16 s przy 16 kHz), nagranie do 655 360 ramek (ok. 40 s). Nagranie zaczyna się przy `start` od pierwszej ramki z DMA RX, łącznie z ciszą przed pierwszym wynikiem. Gdy nagranie jest pełne, kolejne ramki tylko się liczą (`frames`).

`stat`:

```
ok sent=<ramki bodźca oddane do DMA> frames=<ramki odebrane> bad=0 gaps=0 relocks=0 lock_at=-1 first_err=- overflow=<przepełnienia DMA RX> stim=<słowa bodźca> stim_sum=<suma u32 słów, hex> rec=<ramki nagrane> rec_max=<pojemność> preload=<ramki bodźca w DMA przed startem> preload_err=<- albo kod ESP-IDF>
```

Pola checkera wzorca (`bad`…`first_err`) mają stałe wartości, bo tu ocenia host. `stim_sum` pozwala hostowi sprawdzić, że bodziec doszedł cały. `preload` to ramki bodźca załadowane do DMA TX przed włączeniem kanału (normalnie 8 × 240 = 1920). Mniej albo `preload_err` różne od `-` oznacza, że przed bodźcem wyszły zera z pustego DMA, czyli bodziec zaczyna się później niż `MaxLead` ramek (`FeCheck`: „rozbieg nadawania ESP32”). `dump` zwraca `ok n=0`, `ok end`.

`selftest`: pętla wewnętrzna na pinach pętli (`../i2s/commands.md`). I2S0 jest masterem przy 16 kHz z DIN = DOUT, przez kontroler przechodzi 4096 słów xorshift32 i nagranie musi zawierać je w ciągu, z `R = ~L`. To sprawdza pakowanie ramek 32/32, DMA i nagrywanie do PSRAM bez FPGA. Odpowiedź to `ok vectors=0` (FE nie ma wektorów kontraktu).

Kolejność biegu (host):

1. `stop` po obu stronach, `cfg`, `load` bodźca.
2. Start: najpierw ESP32 (slave czeka na zegar), potem FPGA.
3. Czas bodźca plus zapas.
4. Stop FPGA (migawka liczników, potem `tail` ramek ciszy, które domykają bufory DMA ESP32). Host czeka, aż `status.locked` = 0, czyli SCK stanie.
5. Stop ESP32, `stat`, `rec`.

## FPGA: wariant

Rejestr `variant` (`0x006`) = `sckDiv | guardBits << 8 | sampleWidth << 16 | typ << 24`, gdzie typ to `0xFE` dla N0 + N1 i `0xF4` dla N0 – N4. Najstarszy bajt odróżnia bitstream FE od I2S (tam 0).

| Nazwa | Zegar `dut` | Dzielnik | fs | Plik |
| --- | --- | --- | --- | --- |
| `mimas` | 75 MHz (DCM 3/4, dokładnie) | 73 | 16 053,08 Hz | `FeHarnessTop_mimas`, `hw/fe_harness.ucf` |
| `mimas_n4` | jw. | 73 | 16 053,08 Hz | `FeHarnessTop_mimas_n4`, `hw/fe_harness.ucf`; DUT N0 – N4 |

Wariant `d8` (dzielnik 8) istnieje tylko w symulacji harnessu.

## FPGA: rejestry `0x100`–

| Adres | Nazwa | Dostęp | Opis |
| --- | --- | --- | --- |
| `0x100` | `bypass_from` | RW | bypass dla próbek N0 o numerze w `[bypass_from, bypass_to)` |
| `0x101` | `bypass_to` | RW | `0, 0` = bez bypassu (domyślnie), `0, 0xFFFFFFFF` = cały bieg |
| `0x102` | `tail` | RW | 16 bitów; próbki ciszy po `stop`, zanim SCK stanie (domyślnie 512, ok. 32 ms) |
| `0x103` | `dump_every` | RW | 16 bitów; N4: zrzut co tyle ramek widma, 0 = bez zrzutu (domyślnie 16). W wariancie bez N4 bez znaczenia |

Zapis tylko w stanie stop, zatrzaśnięcie przy starcie (jak I2S). Wspólne rejestry generatora (`0x020`–`0x023`) FE ignoruje: bodziec daje ESP32. Wstrzykiwanie resetu (`0x040`–`0x044`) działa jak w I2S i resetuje `MicFrontEnd`.

**Liczniki `0x010`–`0x01D`:** migawka przy `stop` w układzie FE, inna niż układ checkera I2S.

| Adres | Nazwa | Opis |
| --- | --- | --- |
| `0x010` | `sent` | ramki oddane nadajnikowi powrotnemu |
| `0x011` | `frames` | próbki N1 w biegu (ramki wyniku) |
| `0x012` | `underrun` | ramki ciszy nadajnika powrotnego w biegu (kilka na rozbiegu) |
| `0x013` | `overflow` | próbka N1 przy pełnej kolejce do nadajnika; ma być 0 |
| `0x014` | `dc_overrun` | lepki `overrun` filtra; ma być 0 |
| `0x015` | `x_sum` | suma u32 `x` (ze znakiem rozszerzonym do 32 b) wysłanych ramek |
| `0x016` | `y_sum` | to samo dla `y` |
| `0x017` | `rst_done` | resety DUT-a z `HilResetInjector` |
| `0x018` | `n4_frames` | ramki widma mocy zakończone (wariant N4; inaczej 0) |
| `0x019` | `n4_crc` | rekordy CRC wstawione do kanału pomocniczego |
| `0x01A` | `n4_dump` | rekordy zrzutu |
| `0x01B` | `n4_aux_drop` | rekordy CRC odrzucone przy pełnej kolejce; ma być 0 |
| `0x01C` | `n4_order` | koniec ramki N4 bez ramki N2/N3 w kolejce; ma być 0 |
| `0x01D` | `fr_overrun` | lepki `overrun` Framera; ma być 0 |

**`status` (`0x005`):** bit 1 (`locked`) oznacza, że DUT taktuje (SCK biegnie); po `stop` gaśnie na granicy ramki po `tail` próbkach. Bit 2 (`error`) oznacza `overflow`, `overrun` filtra lub Framera, `n4_aux_drop` albo `n4_order`.

**Piny:** jak I2S (`../i2s/commands.md`, P7). SCK i WS są wyjściami od pierwszego `start` do soft resetu; wcześniej są w wysokiej impedancji, więc FPGA nie walczy z ESP32 z firmware'em I2S w roli master. TRIG jest wysoko, gdy `HilResetInjector` trzyma DUT w resecie.
