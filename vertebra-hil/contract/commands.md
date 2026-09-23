# Kontrakt: komendy (część wspólna)

Wersja protokołu: **1**, wspólna dla wszystkich IP. Ten plik opisuje to, co nie zna protokołu testowanej magistrali: transport, ramki, kody błędów, cykl `cfg`/`start`/`stop`/`stat` i wspólną mapę rejestrów. Klucze `cfg`, identyfikator IP, blok rejestrów `0x100`–`0x1FF`, wzorzec danych i wektory są per IP, w podkatalogu IP (`i2s/`, później `i2c/`).

Zmiana tego pliku podnosi wersję protokołu dla wszystkich IP. PC rozmawia z ESP32 tekstem, a z FPGA binarnym dostępem do rejestrów (`vertebra-hil.md` §5). Na PC oba protokoły chowa `HilDevice`. Obie płytki znają tylko komendy z tego pliku i nic nie wiedzą o scenariuszach.

## ESP32: protokół tekstowy

Transport: USB-CDC. Komenda to jedna linia ASCII zakończona `\n` (`\r` przed nim jest ignorowane), najwyżej 255 znaków. Słowa oddziela spacja, argumenty mają postać `klucz=wartość`. Każda komenda dostaje dokładnie jedną linię odpowiedzi, poza `dump`:

```
ok[ klucz=wartość ...]
err <kod> <opis do końca linii>
```

Liczby są dziesiętne, a słowa danych i seed szesnastkowe, 8 cyfr, małe litery, bez `0x`. W `cfg` seed można podać jako dziesiętny albo z prefiksem `0x`. Linia, która nie jest odpowiedzią (log firmware), zaczyna się od `#` i host ją pomija.

| Kod | Znaczenie |
| --- | --- |
| `1` | nieznana komenda |
| `2` | nieznany klucz w `cfg` (literówka przerywa test, a nie jest ignorowana) |
| `3` | wartość spoza zakresu albo połączenie nie jest `checkable` |
| `4` | zły stan (np. `cfg` w trakcie biegu, `start` bez `cfg`) |
| `5` | błąd peryferium (kod ESP-IDF w opisie) |
| `6` | `selftest` nie przeszedł (pierwszy niezgodny wektor w opisie) |

### Komendy

| Komenda | Stan | Odpowiedź |
| --- | --- | --- |
| `ver` | dowolny | `ok proto=1 dev=esp32s3 ip=<id IP> build=<hash gita, 7+ znaków>` |
| `cfg k=v ...` | stop | `ok`; klucze niepodane zachowują poprzednią wartość, pierwsze `cfg` po resecie musi podać wszystkie wymagane |
| `start` | stop, po `cfg` | `ok`; zeruje liczniki, uruchamia RX, potem TX |
| `stop` | dowolny | `ok`; zatrzymuje TX i RX, SCK/WS w wysokiej impedancji; liczniki zostają |
| `stat` | dowolny | `ok sent=… frames=… bad=… gaps=… relocks=… lock_at=… first_err=… overflow=…` |
| `dump` | stop | `ok n=<liczba linii>`, potem n linii `idx got_l got_r exp_l exp_r`, na końcu `ok end` |
| `selftest` | stop | `ok vectors=<liczba>` albo `err 6 …`: wektory IP (`<ip>/vectors/`) wbudowane w firmware, potem test własny roli (dla I2S pętla wewnętrzna) |

`first_err` to `-` albo `n:got_l:got_r:exp_l:exp_r` (n dziesiętnie, słowa hex). `lock_at = -1` oznacza brak locka. `sent` to liczba ramek wzorca oddanych do DMA TX, `overflow` to liczba przepełnień DMA RX (osobno od `bad`, żeby nie udawać błędu DUT-a). `dump` zwraca okno ramek wokół pierwszego błędu; `idx` to indeks ramki w sensie checkera (jak `lock_at`).

## FPGA: binarny most do rejestrów

Transport: UART FPGA, 115200 8N1 (baud jest generykiem harnessu). Adres to **indeks słowa 32-bitowego** (u16), nie adres bajtowy. W harnessie most wystawia go bez zmian na wewnętrzną magistralę `HilRegBus` (nie Apb3: potrzebny jest kod błędu z rejestru, a Apb3 ma tylko `PSLVERROR`).

| Ramka | Bajty |
| --- | --- |
| zapis | `A5 02 addr_hi addr_lo d3 d2 d1 d0 sum` |
| odczyt | `A5 01 addr_hi addr_lo sum` |
| odpowiedź | `5A status d3 d2 d1 d0 sum` |

`sum` to XOR wszystkich wcześniejszych bajtów ramki, łącznie z `A5` / `5A`. Dane big-endian. Odpowiedź na odczyt niesie wartość rejestru, odpowiedź na udany zapis powtarza zapisane dane (bez odczytu z powrotem: rejestry impulsowe czytają się jako 0), a przy błędzie dane to `0`. Każda kompletna ramka dostaje dokładnie jedną odpowiedź. Nieznana operacja ma długość odczytu (5 bajtów). Bajty inne niż `A5` poza ramką są ignorowane. Most porzuca niedokończoną ramkę po 10 ms ciszy między bajtami i czeka na następne `A5`; bajty przychodzące w trakcie odpowiedzi przepadają. Host po timeoutcie odpowiedzi wysyła ramkę jeszcze raz (odczyty i zapisy są idempotentne, `ctrl` jest wyjątkiem: host czyta `status`, zanim ponowi).

| Status | Znaczenie |
| --- | --- |
| `00` | ok |
| `01` | zła suma |
| `02` | adres poza mapą |
| `03` | nieznana operacja albo zapis rejestru tylko do odczytu |
| `04` | zapis konfiguracji w trakcie biegu |

### Mapa rejestrów (część wspólna)

| Adres | Nazwa | Dostęp | Opis |
| --- | --- | --- | --- |
| `0x000` | `magic` | R | `0x48494C31` („HIL1”) |
| `0x001` | `proto` | R | wersja protokołu, `1` |
| `0x002` | `ip_id` | R | 4 znaki ASCII, wartość per IP (`<ip>/commands.md`) |
| `0x003` | `build` | R | 32 najstarsze bity hasha gita z elaboracji |
| `0x004` | `ctrl` | W | bit 0 start, bit 1 stop, bit 2 soft reset harnessu; impulsy, odczyt daje 0 |
| `0x005` | `status` | R | bit 0 bieg, bit 1 lock, bit 2 był błąd |
| `0x006` | `variant` | R | wariant bitstreamu, kodowanie per IP (`<ip>/commands.md`); host sprawdza go razem z `build` |
| `0x007` | `scratch` | RW | dowolna wartość, bez wpływu na harness; test łącza (`hw_link`) |
| `0x010` | `sent` | R | ramki oddane przez generator (handshake z DUT-em); ten i następne: migawka przy `stop`, czytać po `stop` |
| `0x011` | `frames` | R | |
| `0x012` | `bad` | R | |
| `0x013` | `gaps` | R | |
| `0x014` | `relocks` | R | |
| `0x015` | `lock_at` | R | `0xFFFFFFFF` = brak locka |
| `0x016` | `err_n` | R | pola `first_err`; ważne, gdy `bad > 0` |
| `0x017`–`0x01A` | `err_got_l`, `err_got_r`, `err_exp_l`, `err_exp_r` | R | |
| `0x020` | `seed` | RW | seed wzorca |
| `0x021` | `gap_mode` | RW | 0 bez luk; 1 luka po każdych `gap_every` ramkach; 2 luka po ramce n, gdy `(xorshift32(n ^ seed ^ 0x9E3779B9) mod 2^16) & (gap_every - 1) == 0` (`gap_every` potęgą dwójki) |
| `0x022` | `gap_every` | RW | 16 bitów; 0 = bez luk |
| `0x023` | `gap_len` | RW | 16 bitów, liczba ramek ciszy w luce (liczona impulsami underrun DUT-a); 0 = bez luk |
| `0x040` | `rst_count` | RW | liczba resetów DUT-a w biegu |
| `0x041` | `rst_seed` | RW | seed LFSR opóźnień |
| `0x042` | `rst_min` | RW | minimalne opóźnienie w cyklach `dut` |
| `0x043` | `rst_max` | RW | maksymalne opóźnienie |
| `0x044` | `rst_len` | RW | długość resetu w cyklach `dut` |
| `0x100`–`0x1FF` | per IP | RW | konfiguracja IP (`<ip>/commands.md`) |
| `0x1000`– | `capture` | R | okno ramek wokół pierwszego błędu, 5 słów na ramkę: idx, got_l, got_r, exp_l, exp_r |

Rejestry RW można zapisywać tylko w stanie stop (inaczej status `04`). Adresy są stałymi w obiekcie mapy rejestrów IP (dla I2S `I2sHilRegs`, etap 2), z którego korzystają i harness, i host, więc ta tabela to dokumentacja kodu, a nie jego źródło. Przy rozbieżności wygrywa kod, a tabelę się poprawia.
