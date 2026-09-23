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
| `peer_w` | 8…32 | szerokość słowa nadawcy po drugiej stronie (`Wtx` checkera); domyślnie `w` |
| `tx` | 0, 1 | nadawanie wzorca; 0 = linia DOUT w zerach |
| `rx` | 0, 1 | sprawdzanie; 0 = liczniki checkera zostają zerami |

Checker ESP32 liczy `Link(seed, Wtx = peer_w, slot, Wrx = w)`; `cfg` odrzuca połączenie, które nie jest `checkable` (`err 3`).

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

Zapis tylko w stanie stop, zatrzaśnięcie przy `start` (jak rejestry biegu). Host sprawdza przed startem `Link(seed, peer_w, slot, width).checkable`.
