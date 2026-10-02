/*
 * vertebra-hil: strumien probek dla rol "nadaj bodziec, nagraj wynik"
 * (contract/fe/commands.md): bufor bodzca z `load`, nagranie ramek do
 * `rec`, kodowanie hex. Bez ESP-IDF: pamiec podaje rola (PSRAM na
 * plytce, malloc w testach na PC, esp32/test/test_hil_stream.c).
 *
 * Ramka na magistrali to dwa slowa 32-bitowe (L, R), slot 32, slowo 32;
 * w buforze DMA I2S std (stereo, 32 bity) leza wlasnie tak, jako uint32_t
 * w kolejnosci L, R.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define HIL_LOAD_MAX_WORDS  29      /* "load off=262143 data=" + 29 x 8 hex <= 255 znakow */
#define HIL_REC_LINE_FRAMES 15      /* 15 x 16 hex = 240 znakow */
#define HIL_REC_MAX_PER_CMD 4096

/* Bodziec: slowa L; R = ~L powstaje przy nadawaniu. */
typedef struct {
    uint32_t *words;
    uint32_t cap;
    uint32_t n;
    uint32_t sum;                   /* suma u32 slow (stat stim_sum) */
} hil_stim_t;

/* Nagranie ramek (L, R) od startu. Po zapelnieniu ramki tylko sie licza. */
typedef struct {
    uint32_t *lr;                   /* 2 * cap slow */
    uint32_t cap;
    uint32_t n;                     /* nagrane */
    uint64_t seen;                  /* wszystkie odebrane */
} hil_rec_t;

/* 8 cyfr hex (male albo duze litery) -> slowo. false przy zlym znaku. */
bool hil_hex_word(const char *s, uint32_t *out);

/* `load off=<off> data=<hex>`: 0 albo HIL_ERR_RANGE z opisem.
 * off == 0 zaczyna nowy bodziec, inne off == stim->n (dopisanie). */
int hil_stim_load(hil_stim_t *st, uint32_t off, const char *hex, char *msg, size_t len);

/* Nadawanie: `frames` ramek od pozycji *pos do buf (L, R = ~L); po
 * koncu bodzca zera. Zwraca liczbe ramek z bodzca (reszta to zera). */
uint32_t hil_stim_fill(const hil_stim_t *st, uint32_t *pos, uint32_t *buf, uint32_t frames);

void hil_rec_reset(hil_rec_t *r);
/* `frames` ramek (L, R) z bufora DMA; bez kopiowania ponad pojemnosc. */
void hil_rec_push(hil_rec_t *r, const uint32_t *lr, uint32_t frames);

/* Linia `rec`: ramki [off, off + n), n <= HIL_REC_LINE_FRAMES, hex male. */
void hil_rec_line(const hil_rec_t *r, uint32_t off, uint32_t n, char *out, size_t len);

/* Sprawdzenie zakresu `rec off= n=`: 0 albo HIL_ERR_RANGE z opisem. */
int hil_rec_check_range(const hil_rec_t *r, uint32_t off, uint32_t n, char *msg, size_t len);

/* selftest: czy nagranie zawiera caly bodziec w ciagu, z R = ~L.
 * 0 albo HIL_ERR_SELFTEST z opisem; *at = indeks pierwszej ramki bodzca. */
int hil_rec_find_stim(const hil_rec_t *r, const hil_stim_t *st, uint32_t *at, char *msg, size_t len);

/* xorshift32 (13, 17, 5) - wzorzec selftestu, jak HilRand na hoscie. */
uint32_t hil_xorshift32(uint32_t x);

#ifdef __cplusplus
}
#endif
