# Kontrakt I2S: wzorzec danych, `transfer`, checker

Wersja protokołu: **1**. Obowiązuje trzy implementacje: Spinal (`I2sPatternGen`, `I2sPatternCheck`), C (`components/hil_pattern`) i Scalę (`I2sPattern`, `I2sCheckerModel`, referencja). Zgodność każdej z nich sprawdzają wektory z `vectors/`, nie porównanie kodu.

Zmiana czegokolwiek w tym pliku to zmiana wersji protokołu (`ver` / rejestr `0x001`) i przegenerowanie wektorów.

## Arytmetyka

Wszystkie wartości pośrednie to liczby 32-bitowe **bez znaku**. Przesunięcie w prawo jest logiczne (`>>>` w Scali na `Long` albo `Int`, `>>` na `uint32_t` w C, nigdy na `int32_t`). Przesunięcie w lewo obcina wynik do 32 bitów.

## Słowo wzorca

Parametry: `seed` (u32), numer ramki `n` (u32, zawija się przy 2^32), kanał `c` (0 = L przy WS = 0, 1 = R), szerokość słowa nadawcy `W`, 8 ≤ W ≤ 32.

```
S    = min(8, W/2 - 1)          # dzielenie całkowite: 3 dla W=8, 7 dla W=16, 8 dla W>=18
H    = W - 1 - S                # bity hasha
seq  = n mod 2^S
key  = ((seq << 1) | c) ^ seed
hash = xorshift32(key)          # x ^= x << 13; x ^= x >> 17; x ^= x << 5
word = (c << (W-1)) | (seq << H) | (hash mod 2^H)
```

| Bity | Pole | Po co |
| --- | --- | --- |
| W-1 | `c` | zamiana kanałów widoczna po każdym obcięciu słowa; R ≠ 0, więc ramka nigdy nie jest ciszą |
| W-2 … H | `seq` | kolejność |
| H-1 … 0 | `hash mod 2^H` | przesunięcie o bit i przekłamania w młodszych bitach |

### Dlaczego hash z `seq`, a nie z `n`

Checker po locku zna tylko `n mod 2^S`: tyle niesie słowo. Gdyby hash zależał od pełnego `n`, odbiorca, który zaczyna słuchać w trakcie biegu nadawcy (każdy przypadek poza startem zsynchronizowanym co do ramki), nie mógłby policzyć oczekiwanego słowa. Dlatego słowo jest funkcją `(seq, c, seed, W)` i powtarza się z okresem 2^S ramek.

Skutek: zgubienie dokładnie k·2^S ramek z rzędu jest niewidoczne (dla W ≥ 18 to 256 ramek, dla W = 8 tylko 8). Pojedyncze zgubienia, duble i przekłamania są wykrywane.

### Seed

`xorshift32(0) = 0`, więc gdy `key == 0`, hash jest zerem. Zdarza się to tylko dla `seed < 2^(S+1)` i jednej pary `(seq, c)`; przy `seed = 0` słowo L ramki `seq = 0` to same zera. Ramka nadal nie jest ciszą (R ≠ 0), ale linia L zwarta do masy przejdzie w tej jednej ramce na okres. Scenariusze sprzętowe (`hw_*`) używają więc seedów ≥ 2^9, dla których ten przypadek nie występuje. Wektory celowo zawierają też małe seedy (0, 42), żeby implementacje przeszły przez `key == 0`.

## `transfer(v, Wtx, slot, Wrx)`

Słowo nadawcy `v` o szerokości `Wtx`, nadane MSB-first w slocie o długości `slot` i odebrane przez odbiornik o szerokości `Wrx`:

```
pozycja p slotu (0 = pierwszy bit po opóźnieniu o bit od zbocza WS):
  bit(p) = (p < slot && p < Wtx) ? bit Wtx-1-p słowa v : 0
wynik = bity pozycji 0 … Wrx-1, pozycja 0 jako MSB
```

Czyli: padding nadajnika to zera, słowo dłuższe niż slot traci LSB-y już na magistrali, odbiornik krótszy obcina LSB-y, dłuższy dostaje zera. To ta sama funkcja, której używają scoreboardy symulacji (`newhope.i2s.I2sFormat.transfer`).

## Połączenie i warunek sprawdzalności

Odbiorca porównuje zawsze `transfer(word(seed, n, c, Wtx), Wtx, slot, Wrx)`, nigdy surowe słowo. Parametry nadawcy dostaje w `cfg`.

```
E        = min(Wtx, slot, Wrx)     # bity słowa nadawcy, które dochodzą do odbiorcy
checkable: E >= 1 + S(Wtx)         # kanał + całe pole seq
widoczny hash: E - 1 - S(Wtx) bitów
seqOf(v) = (v >> (Wrx - 1 - S)) mod 2^S      # pole seq ze słowa odebranego
```

Połączenie, które nie jest `checkable`, jest błędem konfiguracji: checker go nie przyjmuje (Scala: wyjątek, ESP32: `err`, host: test przerwany przed startem). Przy widocznym hashu równym 0 przesunięcie o bit wykrywa już tylko pole seq (`hw_param_bounds` wypisuje tę wartość per konfiguracja).

## Checker

Wejście: kolejne ramki `(L, R)` o szerokości `Wrx`. Cisza: `L == 0 && R == 0`. `exp(k)` to para oczekiwanych słów dla numeru `k` (liczy się tylko `k mod 2^S`). Ramka jest *ramką wzorca*, gdy nie jest ciszą i równa się `exp(seqOf(L))`.

Stany i przejścia, dla ramki o indeksie `i` (liczonym od startu, łącznie z ciszą):

| Stan | Ramka | Działanie |
| --- | --- | --- |
| **Hunt** | cisza albo nie-wzorzec | nic |
| | ramka wzorca | → Confirm(next = seqOf(L)+1, seen = 1, start = i) |
| **Confirm** | cisza | nic (nie przerywa ciągu, nie jest luką) |
| | `== exp(next)` | seen += 1, next += 1; przy seen == 3: `frames += 3`, `lock_at = start`, → Locked(next) |
| | inna | jak Hunt dla tej ramki |
| **Locked(next)** | cisza | `gaps += 1`, numer nie jest zużyty |
| | `== exp(next)` | `frames += 1`, next += 1 |
| | inna | `bad += 1`; pierwszy raz: `first_err = (next, got, exp)`; jeśli to ramka wzorca: `relocks += 1`, `next += d + 1`, gdzie `d = (seqOf(L) - next) mod 2^S`; w przeciwnym razie `next += 1` |

Wszystkie `next` modulo 2^32. `next` to etykieta checkera: startuje od `seq` pierwszej ramki locka, więc różni się od `n` nadawcy o wielokrotność 2^S. W `first_err` raportujemy tę etykietę.

Interpretacja: relock to zgubiona albo zdublowana ramka; `bad` bez relocka to przekłamana ramka (zamiana kanałów, przekłamany bit), która zużywa numer; trwałe przesunięcie o bit daje `bad` przy każdej ramce. Ciszy przed lockiem się nie liczy, bo początek biegu to zera z pustego DMA.

Liczniki: `frames`, `bad`, `gaps`, `relocks`, `lock_at` (−1 = brak locka), `first_err`. Które wartości są dopuszczalne, decyduje scenariusz na PC.

## Wektory

Pliki CSV w `vectors/`, pierwszy wiersz to nagłówek, separator `,`, koniec linii `\n` (czytelnik ma tolerować `\r\n`). Liczby dziesiętnie, z wyjątkiem kolumn ze słowami i seedem: 8 cyfr hex, małe litery, bez `0x`. Brak wartości to `-`.

| Plik | Kolumny | Zawartość |
| --- | --- | --- |
| `pattern.csv` | `seed,n,c,w,word` | 4 seedy × W ∈ {8,16,24,32} × n przez zawinięcie seq, 256 i granice u32 × oba kanały |
| `transfer.csv` | `word,wtx,slot,wrx,result` | słowa wzorca, same jedynki, 0x55…; slot 8…40 (obcięcie przez slot i padding) |
| `checker_cases.csv` | `case,seed,wtx,slot,wrx,nframes,frames,bad,gaps,relocks,lock_at,err_n,err_got_l,err_got_r,err_exp_l,err_exp_r` | 14 scenariuszy i oczekiwane liczniki po ostatniej ramce |
| `checker_frames.csv` | `case,idx,l,r` | ramki wejściowe scenariuszy, w kolejności |

Scenariusze checkera: `clean`, `prelock` (cisza, śmieci, pół ramki, cisza w trakcie potwierdzania), `gaps`, `drop`, `dup`, `swap`, `bitflip`, `bitshift` (od ramki 20 odbiorca czyta o bit za późno), `wrap` (start przy n = 2^32 − 16), `trunc` (24 → 16), `ext` (16 → 24), `slot_trunc` (32 w slocie 16), `w8_drop` (S = 3), `nolock`.

Generowanie (katalog roboczy `vertebra-hil/fpga`):

```
sbt "hilFpga/runMain newhope.vertebra.hil.i2s.I2sVectors"
```

`I2sContractTestplan` (`ctr_vectors_fresh`) sprawdza, że pliki w repo są identyczne z generowanymi. Zmiana wzorca bez przegenerowania wektorów jest więc czerwona w `sbt test`.
