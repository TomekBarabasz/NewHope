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

## FPGA: blok `0x100`–`0x1FF`

Konfiguracja I2S harnessu (rola, `width`, dzielnik mastera, M/D DCM\_CLKGEN dla `dut`) powstaje w etapie 2 jako `object I2sHilRegs`. Tu trafi jej tabela.
