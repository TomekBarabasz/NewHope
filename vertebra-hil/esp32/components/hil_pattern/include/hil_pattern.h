/*
 * vertebra-hil: wzorzec danych I2S, transfer() i checker w C.
 *
 * Specyfikacja: contract/i2s/pattern.md. Referencja: I2sPattern i
 * I2sCheckerModel (Scala, fpga/hw/spinal/main/i2s/). Zgodnosc sprawdzaja
 * wektory z contract/i2s/vectors/ (hil_vectors.h), a nie porownanie kodu.
 *
 * Ten modul nie zna ESP-IDF: kompiluje sie tez gccem na PC
 * (esp32/test/Makefile), wiec wektory mozna sprawdzic bez plytki.
 *
 * Arytmetyka: uint32_t, przesuniecia w prawo tylko na typach bez znaku.
 * Liczniki checkera sa 32-bitowe, jak rejestry FPGA (contract/commands.md).
 */
#pragma once

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define HIL_PATTERN_MIN_W   8
#define HIL_PATTERN_MAX_W   32
#define HIL_SEQ_MAX_BITS    8
#define HIL_LOCK_FRAMES     3       /* pierwsza ramka + 2 potwierdzenia */
#define HIL_CAP_DEPTH       32      /* okno dump: 16 przed pierwszym bledem i 16 od niego */
#define HIL_LOCK_NONE       0xFFFFFFFFu

typedef struct {
    uint32_t l;
    uint32_t r;
} hil_frame_t;

static inline bool hil_frame_silent(hil_frame_t f) { return f.l == 0 && f.r == 0; }
static inline bool hil_frame_eq(hil_frame_t a, hil_frame_t b) { return a.l == b.l && a.r == b.r; }

/* Jeden krok xorshift32 (13, 17, 5). */
uint32_t hil_xorshift32(uint32_t x);

/* S = min(8, W/2 - 1); -1, gdy W poza [8, 32]. */
int hil_seq_bits(int w);

/* Slowo W-bitowe ramki n, kanalu c (0 = L, 1 = R). W musi byc poprawne. */
uint32_t hil_word(uint32_t seed, uint32_t n, int c, int w);

/* transfer(v, Wtx, slot, Wrx): slowo nadawcy przez slot do odbiornika. */
uint32_t hil_transfer(uint32_t v, int wtx, int slot, int wrx);

/* ------------------------------------------------------------------ */
/*  Polaczenie nadawca -> odbiorca                                      */
/* ------------------------------------------------------------------ */

typedef enum {
    HIL_LINK_OK = 0,
    HIL_LINK_BAD_PARAM = -1,        /* wtx poza [8, 32], wrx poza [1, 32], slot < 1 */
    HIL_LINK_UNCHECKABLE = -2,      /* min(wtx, slot, wrx) < 1 + S(wtx) */
} hil_link_err_t;

typedef struct {
    uint32_t seed;
    int wtx, slot, wrx;
    int s;                          /* S(wtx) */
    uint32_t seq_mask;              /* 2^S - 1 */
    int seq_shift;                  /* wrx - 1 - S: pozycja pola seq w slowie odebranym */
    /* Slowo zalezy tylko od n mod 2^S, wiec cala tabela oczekiwanych ramek
     * ma najwyzej 256 wpisow i checker nie liczy hasha w petli RX. */
    hil_frame_t exp[1 << HIL_SEQ_MAX_BITS];
} hil_link_t;

hil_link_err_t hil_link_init(hil_link_t *link, uint32_t seed, int wtx, int slot, int wrx);

static inline int hil_link_eff_bits(const hil_link_t *k)
{
    int e = k->wtx < k->slot ? k->wtx : k->slot;
    return e < k->wrx ? e : k->wrx;
}
static inline int hil_link_visible_hash_bits(const hil_link_t *k)
{
    int v = hil_link_eff_bits(k) - 1 - k->s;
    return v > 0 ? v : 0;
}
static inline hil_frame_t hil_link_expected(const hil_link_t *k, uint32_t n)
{
    return k->exp[n & k->seq_mask];
}
static inline uint32_t hil_link_seq_of(const hil_link_t *k, uint32_t v)
{
    return (v >> k->seq_shift) & k->seq_mask;
}
static inline bool hil_link_is_pattern(const hil_link_t *k, hil_frame_t f)
{
    return !hil_frame_silent(f) && hil_frame_eq(f, hil_link_expected(k, hil_link_seq_of(k, f.l)));
}

/* Ramka wzorca n po stronie nadawcy (surowe slowa W-bitowe, bez transfer). */
static inline hil_frame_t hil_pattern_frame(uint32_t seed, uint32_t n, int w)
{
    hil_frame_t f = { hil_word(seed, n, 0, w), hil_word(seed, n, 1, w) };
    return f;
}

/* ------------------------------------------------------------------ */
/*  Uklad ramki w buforze DMA I2S ESP32-S3 (tryb std, stereo)           */
/* ------------------------------------------------------------------ */
/*
 * Probka zajmuje w/8 bajtow (8 -> 1, 16 -> 2, 24 -> 3, 32 -> 4), little
 * endian, najpierw L, potem R; wartosc probki to slowo nadawane MSB-first
 * (dokumentacja ESP-IDF 5.2, I2S std, "not (esp32 or esp32s2)"). Pakowanie
 * jest w tym module, zeby test na PC sprawdzil je razem ze wzorcem; czy
 * zgadza sie z krzemem, rozstrzyga sigrok (vertebra-hil.md §7).
 */
static inline int hil_frame_bytes(int w) { return 2 * (w / 8); }
void hil_pack_frame(uint8_t *dst, hil_frame_t f, int w);
hil_frame_t hil_unpack_frame(const uint8_t *src, int w);

/* ------------------------------------------------------------------ */
/*  Checker (pattern.md, "Checker")                                     */
/* ------------------------------------------------------------------ */

typedef struct {
    uint32_t idx;                   /* indeks ramki checkera */
    hil_frame_t got;
    hil_frame_t exp;
} hil_cap_entry_t;

typedef struct {
    uint32_t frames, bad, gaps, relocks;
    uint32_t lock_at;               /* HIL_LOCK_NONE = brak locka */
    bool has_err;
    uint32_t err_n;
    hil_frame_t err_got, err_exp;
} hil_stat_t;

typedef enum { HIL_HUNT, HIL_CONFIRM, HIL_LOCKED } hil_phase_t;

typedef struct {
    const hil_link_t *link;
    hil_phase_t phase;
    uint32_t next;
    int seen;
    uint32_t start;
    uint32_t idx;                   /* wszystkie ramki, lacznie z cisza */
    hil_stat_t st;
    /* Capture jak HilCapture na FPGA: kazda ramka, ktora nie jest cisza,
     * z oczekiwana dla numeru, z ktorym ja porownano; po pierwszym bledzie
     * jeszcze HIL_CAP_DEPTH/2 wpisow (lacznie z bledem) i zamarza. */
    hil_cap_entry_t cap[HIL_CAP_DEPTH];
    uint32_t cap_wr;                /* wpisy zapisane od startu */
    int cap_left;                   /* -1 = nie wyzwolony */
} hil_checker_t;

void hil_checker_init(hil_checker_t *c, const hil_link_t *link);
void hil_checker_push(hil_checker_t *c, hil_frame_t f);

/* Okno capture w kolejnosci idx. Zwraca liczbe wpisow (<= HIL_CAP_DEPTH). */
int hil_checker_capture(const hil_checker_t *c, hil_cap_entry_t *out);

#ifdef __cplusplus
}
#endif
