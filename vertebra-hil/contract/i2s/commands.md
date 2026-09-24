# Kontrakt I2S: komendy

Uzupełnienie `../commands.md` o to, co zna I2S. Wzorzec danych, `transfer` i checker opisuje `pattern.md`, wektory leżą w `vectors/`.

## Identyfikator

`ver` zwraca `ip=i2s`, rejestr `ip_id` (`0x002`) ma wartość `0x49325300` („I2S\0”).

## ESP32: klucze `cfg`

| Klucz | Wartości | Znaczenie |
| --- | --- | --- |
| `role` | `master`, `slave` | kto daje SCK i WS |
| `fs` | Hz | częstotliwość ramki; dla `slave` tylko nominał do konfiguracji DMA |
| `w` | 8, 16, 24, 32 | `data_bit_width`, ta sama w TX i RX (jeden kontroler, `vertebra-hil.md` §7) |
| `slot` | 8, 16, 24, 32 | `slot_bit_width`, ≥ `w` |
| `seed` | u32 | seed wzorca, wspólny dla nadawania i sprawdzania |
| `peer_w` | 8…32, 0 | szerokość słowa nadawcy po drugiej stronie (`Wtx` checkera); domyślnie (i przy 0) `w` |
| `tx` | 0, 1 | nadawanie wzorca, domyślnie 1; 0 = linia DOUT w zerach |
| `rx` | 0, 1 | sprawdzanie, domyślnie 1; 0 = liczniki checkera zostają zerami |
| `loop` | 0, 1 | diagnostyka ESP32 bez FPGA, domyślnie 0; niżej |

Wymagane w pierwszym `cfg` po resecie: `role`, `fs`, `w`, `slot`, `seed`. `fs` w zakresie 8000…96000. `w` i `slot` to 8, 16, 24 albo 32 (tyle obsługuje `i2s_std` S3), `slot ≥ w`.

Checker ESP32 liczy `Link(seed, Wtx = peer_w, slot, Wrx = w)`; `cfg` odrzuca połączenie, które nie jest `checkable` (`err 3`).

Przy `tx=1, rx=1` kontroler pracuje w full duplex (TX i RX dzielą zegar), przy jednym z nich w simplex, bo S3 w trybie slave full duplex bywa niestabilny (`vertebra-hil.md` §7). Przy `tx=0` DOUT jest wyjściem w stanie niskim.

### `loop=1`: partner na drugim kontrolerze

Rola z `cfg` działa na I2S0, a I2S1 tego samego S3 gra partnera w roli przeciwnej, o szerokości słowa `peer_w`, z tym samym `fs`, `slot` i `seed`. Oba kontrolery używają pinów pętli wewnętrznej (niżej), połączonych w matrycy GPIO, więc piny magistrali do FPGA zostają w wysokiej impedancji, a stanowiska nie trzeba odłączać. Partner nadaje, gdy rola główna odbiera (`rx=1`), i sprawdza, gdy rola główna nadaje (`tx=1`); jego checker liczy `Link(seed, w, slot, peer_w)`, więc przy `loop=1` oba kierunki muszą być `checkable`, a `peer_w` jest jedną z szerokości 8, 16, 24, 32, nie większą niż `slot`.

Tak etap 3 sprawdza ESP32 jako slave'a (`cfg role=slave ... loop=1`): główny checker liczy ramki od mastera I2S1, a checker partnera ramki nadane przez slave'a.

`stat` przy `loop=1` dopisuje po polach roli głównej te same pola partnera z prefiksem `peer_`:

```
ok sent=… frames=… … overflow=… peer_sent=… peer_frames=… peer_bad=… peer_gaps=… peer_relocks=… peer_lock_at=… peer_first_err=… peer_overflow=…
```

`dump` zawsze dotyczy roli głównej.

## ESP32: `selftest`

Najpierw wektory z `vectors/` wbudowane w firmware (`vectors=` w odpowiedzi to ich liczba: wiersze `pattern.csv` i `transfer.csv` plus scenariusze checkera), potem pętla wewnętrzna: I2S0 jako master z DIN i DOUT na tym samym pinie pętli dla konfiguracji 48 kHz 16/32, 44,1 kHz 24/32, 48 kHz 16/16, 32/32 i 8/8, seed `0x5eed1234`. Każda musi dać lock i co najmniej 2000 ramek bez `bad`, `gaps`, `relocks` i `overflow`. Wyniki konfiguracji idą jako linie `# petla ...` przed odpowiedzią. Pętla wewnętrzna nie sprawdza pakowania próbek w buforze DMA (błąd pakowania jest symetryczny i znosi się w pętli); to rozstrzyga sigrok (`SigrokI2sCheck`) i FPGA.

## ESP32: piny

ESP32-S3-DevKitC-1 N16R8. Numery są w `esp32/main/Kconfig.projbuild` (`idf.py menuconfig`, menu `vertebra-hil`); wolne od pinów strapping (0, 3, 45, 46), USB (19, 20), UART0 (43, 44), flash i PSRAM oktalnego (26–37) i diody RGB (38 / 48).

| Linia | GPIO | Kierunek | Do FPGA |
| --- | --- | --- | --- |
| SCK | 4 | wyjście w roli master, inaczej wejście | P7-1 |
| WS | 5 | wyjście w roli master, inaczej wejście | P7-2 |
| SD\_E2F (DOUT) | 6 | wyjście | P7-3 |
| SD\_F2E (DIN) | 7 | wejście | P7-4 |
| GND | GND | — | P7-9, P7-10 |

Po resecie i po `stop` wszystkie cztery linie są wejściami bez podciągania (spoczynek ustalają rezystory po stronie FPGA).

Piny pętli wewnętrznej: 15 (SCK), 16 (WS), 17 (dane A: DOUT roli głównej), 18 (dane B: DIN roli głównej). Nic do nich nie podłączamy: w `selftest` i przy `loop=1` są jednocześnie wyjściem jednego kontrolera i wejściem drugiego.

Logic analyzer do sprawdzenia mastera ESP32 (etap 3): D0 = GPIO4 (SCK), D1 = GPIO5 (WS), D2 = GPIO6 (DOUT).

## FPGA: wariant

Jeden bitstream na wariant (`vertebra-hil.md` §6). Rejestr `variant` (`0x006`):

| Bity | Pole |
| --- | --- |
| 7:0 | `width` obu DUT-ów |
| 15:8 | `slotWidth` mastera |
| 23:16 | `halfDiv` mastera |
| 31:24 | 0 |

Warianty (`I2sHilVariant`; zegar `dut` stały w wariancie, `dut = fs · 2 · slotWidth · 2 · halfDiv`):

| Nazwa | fs | width | slotWidth | halfDiv | dut |
| --- | --- | --- | --- | --- | --- |
| `v16_32` | 48 kHz | 16 | 32 | 8 | 49,152 MHz |
| `v24_32` | 44,1 kHz | 24 | 32 | 8 | 45,1584 MHz |
| `v16_16` | 48 kHz | 16 | 16 | 16 | 49,152 MHz |
| `v32_32` | 48 kHz | 32 | 32 | 8 | 49,152 MHz |

`halfDiv` jest dobrany tak, żeby w roli slave polokres SCK ESP32 (slot 32) miał około 8 cykli `dut`, przy wymaganiu slave'a > 3. Dynamiczne M/D zegara `dut` dochodzi osobnym krokiem przed `hw_clock_ratio_sweep`.

## FPGA: blok `0x100`–`0x1FF`

| Adres | Nazwa | Dostęp | Opis |
| --- | --- | --- | --- |
| `0x100` | `role` | RW | 0 = FPGA slave (SCK/WS wejścia), 1 = FPGA master; po resecie 0, żeby FPGA nie walczył z ESP32-masterem |
| `0x101` | `peer_w` | RW | szerokość słowa ESP32 (`Wtx` checkera FPGA), po resecie `width` wariantu |
| `0x102` | `slot` | RW | slot na magistrali dla checkera: w roli master `slotWidth` wariantu, w roli slave slot ESP32; po resecie `slotWidth` |
| `0x103` | `dcm_md` | RW | M/D DCM\_CLKGEN zegara `dut`: M w bitach 8:0, D w 24:16; po resecie M/D wariantu. Zapis (tylko w stop) programuje DCM w biegu; M/D poza zakresem (M 2…256, D 1…256) albo szybsze niż wariant (`TS_dut` w `.ucf`) harness odrzuca bez programowania |
| `0x104` | `dcm_status` | R | bit 0 LOCKED, 1 programowanie trwa, 2 PROGDONE, 3 ostatni zapis `dcm_md` odrzucony, 4 PROGDONE nie wrócił (timeout 10 ms) |
| `0x105` | `dut_freq` | R | cykle `dut` w ostatnim oknie 1 000 000 cykli `sys` (10 ms): f\_dut = `dut_freq` · 100 Hz |

Zapis tylko w stanie stop, zatrzaśnięcie przy `start` (jak rejestry biegu). Programowanie DCM (`dcm_md`) trzyma domenę `dut` w resecie do LOCKED: konfiguracja domeny `dut` wraca do wartości po resecie i zatrzaskuje się od nowa przy następnym `start`.

Programowanie DCM\_CLKGEN (UG382, na PROGCLK = zegar `sys`): LoadD (PROGEN przez 10 cykli, PROGDATA 1, 0, D−1 od LSB), 2 cykle przerwy, LoadM (1, 1, M−1), 2 cykle przerwy, GO (PROGEN przez 1 cykl, PROGDATA 0), potem czekanie na PROGDONE. Zgodność z krzemem sprawdza `dut_freq` po każdym programowaniu (`hw_clock_ratio_sweep`). Host sprawdza przed startem `Link(seed, peer_w, slot, width).checkable`.

## FPGA: piny

Header P7 Mimas V2 (`fpga/hw/ise/i2s_harness.ucf`, `vertebra-hil.md` §9), LVCMOS33:

| Linia | Pin FPGA | Kierunek | Spoczynek |
| --- | --- | --- | --- |
| SCK | P7-1, U8 | wyjście w roli master, inaczej wejście | pull-down |
| WS | P7-2, V8 | wyjście w roli master, inaczej wejście | pull-up |
| SD\_E2F (ESP32 DOUT → FPGA) | P7-3, R8 | wejście | pull-down |
| SD\_F2E (FPGA → ESP32 DIN) | P7-4, T8 | wyjście | — |
| TRIG | P7-5, R5 | wyjście: impuls ~5 µs przy pierwszym błędzie checkera w biegu | — |

Do tego masa (P7-9, P7-10). Piny ESP32: wyżej, „ESP32: piny”.
