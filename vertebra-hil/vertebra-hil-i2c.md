# vertebra-hil: rozszerzenie na I2C (plan)

Sep 24, 2026

Uzupełnienie `vertebra-hil.md`. Zasady z §1 obowiązują bez zmian. Ten dokument opisuje tylko to, co przy I2C jest inne, i plan etapów. Numery § bez nazwy pliku odnoszą się do `vertebra-hil.md`.

## 1. Miara sukcesu

Z §10 („Potem I2C”): I2C ma wejść **bez zmian w części wspólnej poza poprawkami błędów**. Każda zmiana, którą I2C wymusi we wspólnym kodzie albo kontrakcie, trafia na listę refaktorów (§8 tego dokumentu), a nie do kodu od ręki. Refaktor robimy na końcu (etap I8), kiedy są już dwa działające przypadki.

Co ma przejść bez zmian: `HilLink`, `HilDevice`, `EspDevice`, `FpgaDevice[R]`, `HilBench`, `HilSuite`, `hil_cmd`, `HilUartBridge`, `HilRegBus`, rejestry rdzenia, `HilCounters`, `HilCapture`, `HilResetInjector`, `programmer.py`.

Nowe, per IP: kontrakt `contract/i2c/`, wzorzec transakcji, `I2cHarness`, `main/i2c_role.c`, `hil_i2c_pattern`, `I2cHil`, `I2cHilTestplan`.

## 2. Czym I2C różni się od I2S (i co z tego wynika)

| I2S | I2C | Skutek dla harnessu |
| --- | --- | --- |
| ciągły strumień ramek, dwa kierunki na osobnych liniach | transakcje inicjowane przez mastera, jedna linia danych w obie strony | jednostką wzorca jest **transakcja**, a nie ramka; kierunek wynika z bitu R/W, nie z linii |
| odbiorca musi się zsynchronizować z treści (lock) | granice transakcji są jawne (START, RESTART, STOP) | lock jest prostszy: pierwsza transakcja z poprawnym `seq` |
| cisza = luka w nadawaniu | nie ma ciszy; „luką” jest rozciągnięcie zegara | rejestry `gap_*` sterują **wstrzykiwaniem clock stretchingu** przez FPGA slave |
| push-pull, kierunek linii zależy od roli | open-drain, pull-upy, czas narastania | nowa elektryka stanowiska; pull-upy jako parametr testu |
| DUT na zegarze audio z DCM | DUT na dowolnym zegarze, SCL z dzielnika | nie trzeba wariantów zegara; DCM z etapu 7 I2S służy tylko do sweepu |
| ACK nie istnieje | ACK/NACK po każdym bajcie, adres | NACK adresu to osobne zdarzenie w licznikach |
| ESP32 slave: ryzyko wyrównania kanałów (#9513) | ESP32 slave: FIFO 32 B, dane do odczytu muszą czekać w FIFO przed odczytem mastera | największe ryzyko wyroczni przenosi się na ESP32 jako **slave przy odczycie** (§9) |

## 3. Wzorzec transakcji (`contract/i2c/pattern.md`)

Te same wymagania co w I2S (§4): samosynchronizujący się, trywialny, zaimplementowany trzy razy (Spinal, C, Scala), sprawdzany wektorami, a nie porównaniem kodu. Arytmetyka jak w `contract/i2s/pattern.md` (u32, logiczne przesunięcia, ten sam `xorshift32`, który na FPGA już jest w `HilHw`).

Dwa niezależne strumienie: **zapisy** (master → slave, nadaje master) i **odczyty** (slave → master, nadaje slave). Każdy ma własny licznik transakcji `n` po stronie nadawcy i własną sól kierunku `d` (0 = zapis, 1 = odczyt), więc zamiana kierunku jest wykrywalna.

Szkic (do dopracowania w etapie I1):

```
seq      = n mod 2^8
byte(0)  = seq
byte(k)  = xorshift32(((seq << 9) | (d << 8) | k) ^ seed) mod 2^8      # k = 1 … L-1
L(seq)   = lmin + (xorshift32(seq ^ seed ^ 0x9E3779B9) mod (lmax - lmin + 1))
```

- `byte(0) = seq` daje lock i relock z jednego bajtu, jak pole `seq` w I2S.
- Długość zależy od `seq`, więc jeden bieg przechodzi przez transakcje jedno- i wielobajtowe (`*_multi_byte_*`). Obcięta transakcja (STOP za wcześnie) i nadmiarowy bajt są błędami, bo odbiorca zna `L(seq)` po pierwszym bajcie.
- **Odczyt:** długość wybiera master (NACK po ostatnim bajcie). Slave nadaje `byte(k)` aż do NACK, więc wzorzec musi być określony dla każdego `k`, a nie tylko `k < L`. Master czyta `L(seq)` bajtów, gdzie `seq` to jego oczekiwany numer; checker mastera sprawdza długość po stronie mastera, a nadawca-slave tylko liczy `sent`.
- Zgubienie dokładnie k·256 transakcji jest niewidoczne (odpowiednik k·2^S z I2S).

**Harmonogram mastera.** Typ kolejnej transakcji master wylicza z własnego licznika i seeda według `mix` z `cfg`: `W` (zapis), `R` (odczyt), `WR` (zapis, RESTART, odczyt: `*_write_then_read`, `*_repeated_start`) i `A` (adres `addr ^ 1`, oczekiwany NACK: `slv_addr_nack`). Slave harmonogramu nie zna: dowiaduje się o kierunku z bitu R/W. Dzięki temu scenariusz zmienia miks bez nowego bitstreamu ani firmware'u.

### Checker

Ta sama maszyna stanów co w I2S (Hunt / Confirm / Locked, `contract/i2s/pattern.md`), jednostką jest transakcja jednego kierunku. Transakcja jest *transakcją wzorca*, gdy jej bajty i długość zgadzają się z `seq` z bajtu 0.

Znaczenie wspólnych liczników dla I2C (układ rejestrów `0x010`–`0x01D` zostaje):

| Licznik | I2S | I2C |
| --- | --- | --- |
| `sent` | ramki oddane przez generator | transakcje nadane (zapisy przez mastera, odczyty przez slave'a) |
| `frames` | zgodne ramki | zgodne transakcje |
| `bad` | przekłamane ramki | transakcje z błędnym bajtem, złą długością albo nieoczekiwanym NACK |
| `gaps` | ramki ciszy | NACK adresu (oczekiwane przy `mix` z `A`; scenariusz porównuje z liczbą wysłanych `A`) |
| `relocks` | zgubione / zdublowane ramki | zgubione / zdublowane transakcje |
| `lock_at`, `first_err` | jak w I2S | jak w I2S, etykieta = numer transakcji |
| `overflow` | DMA RX / checker nie zdążył | FIFO ESP32 / checker nie zdążył |

`first_err` i wpisy capture mają pięć słów (`n, got_l, got_r, exp_l, exp_r`). Dla I2C: `got_l`/`exp_l` = `(k << 8) | bajt` pierwszego błędnego bajtu (`k = 0xFF` dla błędu długości), `got_r`/`exp_r` = długość i flagi transakcji. Nazwy L/R są wtedy mylące: lista refaktorów (§8).

### Wektory

`contract/i2c/vectors/`: `pattern.csv` (`seed, n, d, k, byte`), `length.csv` (`seed, seq, lmin, lmax, L`), `checker_cases.csv` i `checker_transactions.csv` (czysty bieg, zgubiona i zdublowana transakcja, przekłamany bajt, za krótka, za długa, NACK adresu w środku, zamiana kierunku, zawinięcie `n`, brak locka). Generuje `I2cVectors`, świeżość sprawdza `I2cContractTestplan` (`ctr_vectors_fresh`), jak w I2S.

## 4. Stanowisko i elektryka

Nowość w stosunku do I2S i główny powód, żeby I2C w ogóle testować na sprzęcie: w symulacji open-drain to idealne zero i idealna jedynka.

| Linia | ESP32-S3 | Mimas V2 | Uwagi |
| --- | --- | --- | --- |
| SCL | GPIO 8 (propozycja) | wolny pin P7, np. P7-6 (do potwierdzenia w tabeli Numato) | open-drain po obu stronach |
| SDA | GPIO 9 (propozycja) | wolny pin P7, np. P7-7 | open-drain po obu stronach |
| PU\_S, PU\_W | — | dwa piny FPGA | przełączane pull-upy (niżej) |
| TRIG | — | P7-5 (jak w I2S) | impuls przy pierwszym błędzie checkera |
| GND | GND | P7-9, P7-10 | |

- **Osobne piny niż I2S** (GPIO 4–7 / P7-1…4 zostają dla I2S). Oba zestawy przewodów mogą być podłączone jednocześnie, a przejście między IP to tylko bitstream i firmware, bez przepinania.
- **Pull-upy na płytce stanowiska**: stały słaby, np. 10 kΩ do 3,3 V na SCL i SDA, żeby magistrala miała spoczynek także przy nieskonfigurowanym FPGA. Do tego **przełączane mocne pull-upy**: rezystor (np. 2,2 kΩ) między linią a pinem FPGA; pin w stanie `1` włącza pull-up, w wysokiej impedancji go wyłącza. Rejestr `pullup` wybiera zestaw, co pozwala zrobić `hw_rise_time` bez przepinania (§6).
- Wewnętrzne pull-upy FPGA i ESP32 (dziesiątki kΩ) są wyłączone na magistrali: za słabe na 400 kHz i niekontrolowane. Wyjątek: pętla wewnętrzna ESP32 (§5).
- Oba układy na 3,3 V, więc bez translatora. VCCO banku 2 z otwartych pytań (§11) dotyczy I2C tak samo.
- Rezystory szeregowe (np. 100 Ω) przy obu płytkach ograniczają prąd przy pomyłce w konfiguracji (wyjście push-pull zamiast open-drain).
- **Logic analyzer:** I2C przy 1 MHz to łatwy przypadek dla tanich analizatorów 24 MS/s, więc `hw_la_crosscheck` (dekoder `i2c` sigroka, `SigrokI2cCheck`) jest tu realny wcześniej niż w I2S.

## 5. ESP32-S3

`main/i2c_role.c` rejestruje w `hil_cmd` swoje klucze `cfg` i funkcje `start`/`stop`/`stat`/`dump`, jak `i2s_role.c`. `hil_cmd` bez zmian.

| Klucz | Wartości | Znaczenie |
| --- | --- | --- |
| `role` | `master`, `slave` | |
| `scl` | Hz, 10 000…1 000 000 | częstotliwość SCL mastera; dla slave'a ignorowana |
| `addr` | 0x08…0x77 | adres slave'a (7 bitów) |
| `seed` | u32 | |
| `mix` | np. `w`, `r`, `wr`, `w,r,wr,a` | typy transakcji mastera (§3) |
| `lmin`, `lmax` | 1…64 | zakres długości transakcji |
| `tx`, `rx` | 0, 1 | nadawanie i sprawdzanie, jak w I2S |
| `loop` | 0, 1 | partner na drugim kontrolerze I2C |

- **Dwa kontrolery I2C w S3** dają odpowiednik `loop=1` i pętli w `selftest`: I2C0 w roli z `cfg`, I2C1 w roli przeciwnej, na osobnych pinach pętli (propozycja GPIO 10/11) z wewnętrznymi pull-upami, tylko do 100 kHz. Piny magistrali do FPGA zostają w wysokiej impedancji.
- **`hil_i2c_pattern`**: nowy komponent obok `hil_pattern`, bez ESP-IDF, testowany gccem na wektorach (`make -C vertebra-hil/esp32/test`).
- **Jedno IP na firmware.** `ver` zwraca jedno `ip=`, więc IP wybiera Kconfig (`HIL_IP_I2S` / `HIL_IP_I2C`) w czasie budowania. Zero zmian we wspólnym kontrakcie. Firmware z obiema rolami naraz to wpis na liście refaktorów (§8).
- **Wersja ESP-IDF.** Firmware jest na 5.2.x. Sterownik slave'a I2C w 5.2 (`i2c_slave.h`, pierwsza wersja nowego API) ma ograniczenia przy odczycie przez mastera; w nowszych wersjach IDF jest druga wersja sterownika slave'a. Do sprawdzenia w etapie I3, zanim zaufamy ESP32 jako slave'owi (§9). Ewentualne podbicie IDF dotyczy też I2S, więc po nim trzeba powtórzyć `I2sHilTestplan`.
- **Odczyt z ESP32-slave'a.** Slave musi mieć bajty w FIFO TX, zanim master zacznie czytać. Firmware dokłada kolejną transakcję odczytu do FIFO zaraz po NACK poprzedniej; czy S3 rozciąga SCL, gdy FIFO jest puste, sprawdza etap I3. Jeśli nie, `lmax` odczytu ograniczamy do rozmiaru FIFO, a zdarzenie „slave nie zdążył” liczy `overflow`, a nie `bad`.
- **Przepustowość.** Master ESP32 przez sterownik ma narzut rzędu dziesiątek µs na transakcję. Przy 400 kHz i średnio 8 bajtach to kilka tysięcy transakcji/s, więc kryteria liczymy w transakcjach (10^5 w V1, 10^6 w soak), a nie przenosimy 10^6 ramek z I2S.

## 6. FPGA

Jeden bitstream na wariant, jak w I2S, ale wariantów jest mniej: I2C nie potrzebuje zegara audio.

| Komponent | Opis |
| --- | --- |
| `I2cHilRegs` | mapa rejestrów: wspólna część + blok `0x100` |
| `I2cMasterSeq` | warstwa 3 nad `I2cMaster`: harmonogram transakcji z §3, generuje zapisy, sprawdza odczyty (generator i checker w jednym, bo oba kierunki idą przez ten sam port `cmd`/`rsp`) |
| `I2cSlaveAgent` | warstwa 3 nad `I2cSlave`: sprawdza zapisy (`rx`), nadaje odczyty (`tx`), `rxAck`; wstrzykuje stretching: przetrzymuje `rx.ready` / `tx.valid` według rejestrów `gap_*` |
| `I2cHarness` | oba DUT-y, multipleks na wspólne SCL/SDA (open-drain, `I2cPins`), przełączane pull-upy, TRIG |
| `I2cHarnessTop` | + `HilCore` i DCM, jak `I2sHarnessTop` |

Blok `0x100`–`0x1FF` (szkic):

| Adres | Nazwa | Opis |
| --- | --- | --- |
| `0x100` | `role` | 0 = FPGA slave, 1 = FPGA master; po resecie 0 |
| `0x101` | `addr` | adres slave'a DUT-a (`io.address`, zmiana tylko w stop, więc `slv_address_runtime` między biegami) |
| `0x102` | `mix` | maska typów transakcji mastera |
| `0x103` | `len` | `lmin` (7:0), `lmax` (15:8) |
| `0x104` | `phy` | 0 = `I2cPhyTable`, 1 = `I2cPhyFsm` (oba mastery w jednym bitstreamie; są małe) |
| `0x105` | `pullup` | bit 0 mocny pull-up SCL, bit 1 mocny pull-up SDA |
| `0x180`… | liczniki I2C (R) | np. `stretch_max` (najdłuższe rozciągnięcie SCL przez partnera, w cyklach), `bus_err` (START/STOP w środku bajtu widziane przez monitor w harnessie) |

Rejestry tylko do odczytu w bloku per IP: `contract/commands.md` opisuje ten blok jako RW. Wpis na listę refaktorów (§8), bez zmiany wersji protokołu, dopóki nie trzeba.

**Warianty.** `sclFrequency` mastera jest generykiem (`I2cGenerics`), więc wariant = SCL mastera FPGA: `s100`, `s400`, `s1000`. Zegar DUT-a: 100 MHz z oscylatora albo DCM (wtedy `hw_clock_ratio_sweep` bierze dynamiczne M/D z etapu 7 I2S). Ograniczenie slave'a `filterLatency + 2 <= 2 * quarterCycles` (`I2cSlave`) liczy `hw_param_bounds` dla SCL ESP32-mastera i zegara DUT-a.

**Harness w symulacji.** `I2cHarnessTestplan` jak `I2sHarnessTestplan`: rolę ESP32 grają modele z `i2c` (`I2cAgent`, `I2cSlaveModel`, driver mastera), a do tego potrzebna jest zależność `i2c % "compile->compile;test->test"` w `hilFpga` (dla `i2s` już jest). Pull-upy w symulacji to `I2cPins` z przewodowym AND.

**Zajętość.** Mastery I2C i slave są dużo mniejsze od DUT-ów I2S, więc XC6SLX9 nie jest ryzykiem. Timing domeny `sys` (most) jest ciasny już w I2S (§10, wyniki etapu 2): po dołożeniu `I2cHilRegs` trzeba go sprawdzić tak samo.

## 7. Plan testów

Mapowanie testpointów symulacji (`I2cMasterTestplan`, `I2cSlaveTestplan`) na sprzęt. Prefiks `hw_`, w `checking` odsyłacz do testpointu symulacyjnego.

| Testpoint | Etap | Role | Uzupełnia |
| --- | --- | --- | --- |
| `hw_param_bounds` | V1 | — | budżet filtra slave'a przy realnym SCL, `quarterCycles` mastera, wykrywalność wzorca, zakresy ESP32 |
| `hw_link` | V1 | obie | jak w I2S; `selftest` I2C na ESP32 |
| `hw_slv_write`, `hw_slv_read` | V1 | ESP master | `slv_addr_ack`, `slv_write_byte`, `slv_read_byte`, `slv_rx_nack`, `slv_tx_ack_report`, `slv_multi_byte_*` |
| `hw_mst_write`, `hw_mst_read` | V1 | FPGA master | `mst_write_byte`, `mst_read_byte`, `mst_ack_capture`, `mst_read_ack_nack` |
| `hw_write_read` | V2 | obie | `*_write_then_read`, `mst_repeated_start` |
| `hw_addr_nack` | V2 | obie | `slv_addr_nack`, `slv_address_runtime` |
| `hw_speeds` | V2 | obie | 100 kHz, 400 kHz, 1 MHz (Fm+, jeśli ESP32 da radę) |
| `hw_slv_stretch` | V2 | ESP master | `slv_stretch_rx`, `slv_stretch_tx`: stretching wstrzyknięty przez FPGA, ESP32 musi czekać |
| `hw_mst_stretch` | V2 | FPGA master | `mst_clock_stretching`: stretching ESP32-slave'a, niesterowany, ale z obcego krzemu; `stretch_max` > 0 dowodzi, że wystąpił |
| `hw_phy_equivalence` | V2 | FPGA master | `mst_phy_equivalence`: ten sam bieg na `I2cPhyTable` i `I2cPhyFsm` |
| `hw_la_crosscheck` | V2 | obie | sigrok zgadza się z obiema płytkami; `mst_sda_stable_during_bit`, `slv_start_stop_events` |
| `hw_rise_time` | V3 | obie | tylko sprzęt: słabe / mocne pull-upy przy 400 kHz i 1 MHz |
| `hw_random_reset` | V3 | obie | `mst_random_reset`, `slv_reset_releases_bus`; reset DUT-a w środku transakcji, partner odzyskuje magistralę (9 impulsów SCL) |
| `hw_clock_ratio_sweep` | V3 | ESP master | granica `filterLatency + 2 <= 2q` na krzemie |
| `hw_soak` | V3 | obie | 10^6 transakcji na rolę bez błędu |

Poza sprzętem zostaje to, czego ani ESP32, ani DUT nie umie: `mst_arbitration_lost` (DUT nie ma arbitrażu; dwa mastery ESP32 + FPGA dałyby się ustawić, ale to test na później), `slv_abort_mid_byte`, `slv_general_call`, `*_stretch_timeout`, `mst_bus_free_time_after_stop` (częściowo: pomiar tBUF z analizatora ≥ 100 MS/s).

**Trzeci świadek.** Projekt `aht10` ma czujnik AHT10, czyli trzecią, niezależną implementację slave'a I2C w krzemie. Opcjonalnie `hw_mst_aht10`: FPGA master czyta pomiar z AHT10 na tej samej magistrali (inny adres). To też kandydat na partnera zapasowego, gdyby ESP32 okazał się niewiarygodny jako slave (§9), choć tylko dla prostych transakcji.

## 8. Lista refaktorów (wypełniana w trakcie)

Rzeczy, które I2C wymusza lub sugeruje we wspólnej części. Nie ruszamy ich przed etapem I8, chyba że blokują.

| Miejsce | Problem | Propozycja |
| --- | --- | --- |
| `HilErr`, `HilDumpEntry`, rejestry `err_got_l/r`, `err_exp_l/r`, capture | nazwy L/R to I2S | `got0/got1`, `exp0/exp1`, znaczenie w kontrakcie IP |
| `HilStat.gaps` | „luka” to pojęcie I2S | zostaje nazwa, znaczenie w kontrakcie IP; albo `aux` |
| `contract/commands.md`, blok `0x100` | opisany jako RW | dopuścić R w bloku per IP |
| rejestry `gap_*` | semantyka „ramek ciszy” | ogólnie „zakłócenie z generatora”, znaczenie per IP |
| firmware ESP32 | jedno IP na build | opcjonalnie oba IP w jednym obrazie; wymaga zmiany `ver` |
| `I2sPatternGen/Check` vs `I2cMasterSeq/I2cSlaveAgent` | czy jest wspólna abstrakcja? | ocena po I7; prawdopodobnie wspólna jest tylko maszyna locka (Hunt/Confirm/Locked), sparametryzowana predykatem „jednostka wzorca” |

## 9. Ryzyka

| Ryzyko | Skutek | Co robimy |
| --- | --- | --- |
| ESP32-S3 slave: odczyt przez mastera (FIFO TX, stretching, sterownik IDF 5.2) | fałszywe błędy przy FPGA master czytającym | etap I3 w pętli I2C0 ↔ I2C1; `overflow` osobno od `bad`; w razie potrzeby nowszy IDF albo krótkie odczyty; zapasowo AHT10 |
| ESP32 master nie trzyma 1 MHz | brak Fm+ | `hw_speeds` robi 1 MHz jako *canceled* z powodem; granica z `hw_param_bounds` |
| Narzut sterownika ESP32 na transakcję | długie biegi | kryteria w transakcjach, nie ramkach; w `stat` postęp na bieżąco, jak w I2S |
| Pomyłka push-pull zamiast open-drain | zwarcie linii | rezystory szeregowe; `hw_link` sprawdza spoczynek linii (obie strony w stop: SCL = SDA = 1) przed pierwszym biegiem |
| Przejście I2S ↔ I2C wymaga przeflashowania obu płytek | wolniejszy pełny bieg | `HilBench` już grupuje testy po wariancie; osobne piny, bez przepinania |
| Podbicie ESP-IDF | regresja I2S | pełny `I2sHilTestplan` po podbiciu |

## 10. Plan wykonania

Te same zasady co w §10: w każdym etapie jeden nowy element, któremu nie ufamy; etap kończy się kryterium, nie napisanym kodem. Zaczynamy po zamknięciu etapu 7 I2S (z niego bierzemy dynamiczne M/D DCM). Etapy I1–I3 można zacząć wcześniej, bo nie dotykają stanowiska I2S.

| # | Etap | Zakres | Kryterium ukończenia |
| --- | --- | --- | --- |
| I0 | Stanowisko | pull-upy, przełączane pull-upy, rezystory szeregowe, przewody SCL/SDA na osobnych pinach, piny potwierdzone z tabeli Numato i Kconfig ESP32 | spoczynek SCL = SDA = 1 miernikiem przy obu płytkach w resecie; sigrok widzi linie |
| I1 | Kontrakt | `contract/i2c/pattern.md`, `commands.md`, `vectors/`; `I2cPattern`, `I2cCheckerModel`, `I2cVectors` | `I2cContractTestplan` zielony, w tym `ctr_vectors_fresh` |
| I2 | Harness w symulacji | `I2cHilRegs`, `I2cMasterSeq`, `I2cSlaveAgent`, `I2cHarness`, `I2cHarnessTop` | `I2cHarnessTestplan` zielony z wstrzykniętymi błędami (zgubiona, zdublowana, przekłamana, obcięta transakcja, NACK adresu); ISE: timing `sys` spełniony dla wszystkich wariantów |
| I3 | ESP32 samo | `hil_i2c_pattern`, `i2c_role.c`, `selftest` (wektory + pętla I2C0 ↔ I2C1), master w sigroku | wektory na PC i na płytce; pętla 10^5 transakcji w obu rolach bez błędu; wiadomo, jak S3 slave zachowuje się przy pustym FIFO TX |
| I4 | Host | `I2cHil` (`I2cFpgaMap`, `I2cBenchCfg`, `HilIp`), `I2cDiag`, `FakeI2cBus`, `SigrokI2cCheck` | `HilHostTestplan` z I2C na atrapach; `hw_link` i `hw_param_bounds` zielone na stanowisku; **zero zmian we wspólnym kodzie hosta** albo każda zmiana na liście z §8 |
| I5 | Pierwszy end-to-end | `hw_slv_write`, potem `hw_slv_read`, 100 kHz, `mix=w` / `mix=r` | 10^5 transakcji, zero błędów |
| I6 | V1 i V2 | obie role, wszystkie `mix`, prędkości, stretching w obie strony, oba PHY, `hw_la_crosscheck` | V1 kompletny w `testplan completeness`; V2 zrobiony albo `unimplemented` z powodem |
| I7 | V3 | `hw_random_reset`, `hw_rise_time`, `hw_clock_ratio_sweep`, `hw_soak` | soak 10^6 transakcji na rolę; granica zegara slave'a zgodna z `require` w `I2cSlave`; granica pull-upów zapisana |
| I8 | Refaktor | decyzje z §8 | wspólna część ma dwóch użytkowników; `I2sHilTestplan` i `I2cHilTestplan` zielone po refaktorze |

I2 i I3 są niezależne i mogą iść równolegle, jak w I2S.

## 11. Otwarte pytania

- [ ] Piny P7 dla SCL, SDA i pull-upów przełączanych (tabela Numato, bank 2).
- [ ] GPIO ESP32 dla magistrali (8/9?) i pętli (10/11?) na DevKitC-1 N16R8.
- [ ] Wersja ESP-IDF: czy sterownik slave'a I2C z 5.2 wystarcza do odczytów, czy podbijamy (i powtarzamy I2S).
- [ ] Czy ESP32-S3 master realnie robi 1 MHz.
- [ ] Wartości pull-upów (słaby stały, mocny przełączany) pod pojemność stanowiska.
- [ ] Czy AHT10 dołączamy do stanowiska jako trzeciego świadka.
