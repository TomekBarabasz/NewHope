/*
 * vertebra-hil: strumien probek (hil_stream.h, contract/fe/commands.md).
 */
#include "hil_stream.h"

#include <inttypes.h>
#include <stdio.h>
#include <string.h>

#include "hil_cmd.h"

static int hexval(char c)
{
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
        return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
        return c - 'A' + 10;
    }
    return -1;
}

bool hil_hex_word(const char *s, uint32_t *out)
{
    uint32_t v = 0;
    for (int i = 0; i < 8; i++) {
        int h = hexval(s[i]);
        if (h < 0) {
            return false;
        }
        v = (v << 4) | (uint32_t)h;
    }
    *out = v;
    return true;
}

int hil_stim_load(hil_stim_t *st, uint32_t off, const char *hex, char *msg, size_t len)
{
    size_t chars = strlen(hex);
    if (chars == 0 || chars % 8 != 0 || chars / 8 > HIL_LOAD_MAX_WORDS) {
        snprintf(msg, len, "data: %zu znakow, oczekiwane 8..%d i wielokrotnosc 8", chars, 8 * HIL_LOAD_MAX_WORDS);
        return HIL_ERR_RANGE;
    }
    uint32_t k = (uint32_t)(chars / 8);
    if (off != 0 && off != st->n) {
        snprintf(msg, len, "off=%" PRIu32 ": bufor ma %" PRIu32 " slow (0 = nowy bodziec, inaczej dopisanie na koniec)",
                 off, st->n);
        return HIL_ERR_RANGE;
    }
    if (off + k > st->cap) {
        snprintf(msg, len, "bodziec %" PRIu32 " slow > pojemnosc %" PRIu32, off + k, st->cap);
        return HIL_ERR_RANGE;
    }
    uint32_t w[HIL_LOAD_MAX_WORDS];
    for (uint32_t i = 0; i < k; i++) {
        if (!hil_hex_word(hex + 8 * i, &w[i])) {
            snprintf(msg, len, "data: slowo %" PRIu32 " '%.8s' to nie hex", i, hex + 8 * i);
            return HIL_ERR_RANGE;
        }
    }
    /* Calosc sprawdzona: dopiero teraz zmiana bufora. */
    if (off == 0) {
        st->n = 0;
        st->sum = 0;
    }
    for (uint32_t i = 0; i < k; i++) {
        st->words[off + i] = w[i];
        st->sum += w[i];
    }
    st->n = off + k;
    return 0;
}

uint32_t hil_stim_fill(const hil_stim_t *st, uint32_t *pos, uint32_t *buf, uint32_t frames)
{
    uint32_t from = 0;
    for (uint32_t i = 0; i < frames; i++) {
        uint32_t l = 0;
        if (*pos < st->n) {
            l = st->words[*pos];
            (*pos)++;
            from++;
            buf[2 * i] = l;
            buf[2 * i + 1] = ~l;
        } else {
            buf[2 * i] = 0;             /* koniec bodzca: cisza, jak auto_clear */
            buf[2 * i + 1] = 0;
        }
    }
    return from;
}

void hil_rec_reset(hil_rec_t *r)
{
    r->n = 0;
    r->seen = 0;
}

void hil_rec_push(hil_rec_t *r, const uint32_t *lr, uint32_t frames)
{
    uint32_t room = r->cap - r->n;
    uint32_t k = frames < room ? frames : room;
    if (k) {
        memcpy(r->lr + 2 * (size_t)r->n, lr, sizeof(uint32_t) * 2 * (size_t)k);
        r->n += k;
    }
    r->seen += frames;
}

void hil_rec_line(const hil_rec_t *r, uint32_t off, uint32_t n, char *out, size_t len)
{
    size_t p = 0;
    out[0] = '\0';
    for (uint32_t i = 0; i < n && p + 17 <= len; i++) {
        const uint32_t *f = r->lr + 2 * (size_t)(off + i);
        p += (size_t)snprintf(out + p, len - p, "%08" PRIx32 "%08" PRIx32, f[0], f[1]);
    }
}

int hil_rec_check_range(const hil_rec_t *r, uint32_t off, uint32_t n, char *msg, size_t len)
{
    if (n == 0 || n > HIL_REC_MAX_PER_CMD) {
        snprintf(msg, len, "n=%" PRIu32 ": dozwolone 1..%d", n, HIL_REC_MAX_PER_CMD);
        return HIL_ERR_RANGE;
    }
    if (off > r->n || n > r->n - off) {
        snprintf(msg, len, "off=%" PRIu32 " n=%" PRIu32 ": nagranie ma %" PRIu32 " ramek", off, n, r->n);
        return HIL_ERR_RANGE;
    }
    return 0;
}

int hil_rec_find_stim(const hil_rec_t *r, const hil_stim_t *st, uint32_t *at, char *msg, size_t len)
{
    if (st->n == 0) {
        snprintf(msg, len, "pusty bodziec");
        return HIL_ERR_SELFTEST;
    }
    for (uint32_t s = 0; s + st->n <= r->n; s++) {
        if (r->lr[2 * (size_t)s] != st->words[0]) {
            continue;
        }
        uint32_t i = 0;
        while (i < st->n && r->lr[2 * (size_t)(s + i)] == st->words[i] &&
               r->lr[2 * (size_t)(s + i) + 1] == ~st->words[i]) {
            i++;
        }
        if (i == st->n) {
            *at = s;
            return 0;
        }
        snprintf(msg, len, "nagranie od ramki %" PRIu32 ": zgodne %" PRIu32 " z %" PRIu32 " slow, ramka %" PRIu32
                 " = %08" PRIx32 " %08" PRIx32 ", oczekiwane %08" PRIx32 " %08" PRIx32,
                 s, i, st->n, s + i, s + i < r->n ? r->lr[2 * (size_t)(s + i)] : 0,
                 s + i < r->n ? r->lr[2 * (size_t)(s + i) + 1] : 0, st->words[i], ~st->words[i]);
        return HIL_ERR_SELFTEST;
    }
    snprintf(msg, len, "brak pierwszego slowa bodzca %08" PRIx32 " w %" PRIu32 " nagranych ramkach",
             st->words[0], r->n);
    return HIL_ERR_SELFTEST;
}

uint32_t hil_xorshift32(uint32_t x)
{
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    return x;
}
