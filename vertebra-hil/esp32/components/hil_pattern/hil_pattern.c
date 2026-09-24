/*
 * vertebra-hil: wzorzec, transfer() i checker (contract/i2s/pattern.md).
 * Maszyna stanow jest przepisana z I2sCheckerModel; komentarze w nawiasach
 * odsylaja do tabeli przejsc w pattern.md.
 */
#include "hil_pattern.h"

#include <string.h>

uint32_t hil_xorshift32(uint32_t x)
{
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    return x;
}

int hil_seq_bits(int w)
{
    if (w < HIL_PATTERN_MIN_W || w > HIL_PATTERN_MAX_W) {
        return -1;
    }
    int s = w / 2 - 1;
    return s < HIL_SEQ_MAX_BITS ? s : HIL_SEQ_MAX_BITS;
}

static inline uint32_t low_mask(int bits)
{
    return bits >= 32 ? 0xFFFFFFFFu : ((1u << bits) - 1u);
}

uint32_t hil_word(uint32_t seed, uint32_t n, int c, int w)
{
    int s = hil_seq_bits(w);
    int h = w - 1 - s;
    uint32_t seq  = n & low_mask(s);
    uint32_t hash = hil_xorshift32(((seq << 1) | (uint32_t)c) ^ seed);
    return ((uint32_t)c << (w - 1)) | (seq << h) | (hash & low_mask(h));
}

uint32_t hil_transfer(uint32_t v, int wtx, int slot, int wrx)
{
    uint32_t acc = 0;
    for (int p = 0; p < wrx; p++) {
        uint32_t b = (p < slot && p < wtx) ? (v >> (wtx - 1 - p)) & 1u : 0u;
        acc = (acc << 1) | b;
    }
    return acc;
}

static inline void put_le(uint8_t *d, uint32_t v, int bytes)
{
    for (int i = 0; i < bytes; i++) {
        d[i] = (uint8_t)(v >> (8 * i));
    }
}

static inline uint32_t get_le(const uint8_t *s, int bytes)
{
    uint32_t v = 0;
    for (int i = bytes - 1; i >= 0; i--) {
        v = (v << 8) | s[i];
    }
    return v;
}

void hil_pack_frame(uint8_t *dst, hil_frame_t f, int w)
{
    int b = w / 8;
    put_le(dst, f.l, b);
    put_le(dst + b, f.r, b);
}

hil_frame_t hil_unpack_frame(const uint8_t *src, int w)
{
    int b = w / 8;
    hil_frame_t f = { get_le(src, b), get_le(src + b, b) };
    return f;
}

hil_link_err_t hil_link_init(hil_link_t *k, uint32_t seed, int wtx, int slot, int wrx)
{
    int s = hil_seq_bits(wtx);
    if (s < 0 || slot < 1 || wrx < 1 || wrx > HIL_PATTERN_MAX_W) {
        return HIL_LINK_BAD_PARAM;
    }
    k->seed = seed;
    k->wtx = wtx;
    k->slot = slot;
    k->wrx = wrx;
    k->s = s;
    k->seq_mask = low_mask(s);
    if (hil_link_eff_bits(k) < 1 + s) {
        return HIL_LINK_UNCHECKABLE;
    }
    k->seq_shift = wrx - 1 - s;
    for (uint32_t n = 0; n <= k->seq_mask; n++) {
        hil_frame_t f = hil_pattern_frame(seed, n, wtx);
        k->exp[n].l = hil_transfer(f.l, wtx, slot, wrx);
        k->exp[n].r = hil_transfer(f.r, wtx, slot, wrx);
    }
    return HIL_LINK_OK;
}

void hil_checker_init(hil_checker_t *c, const hil_link_t *link)
{
    memset(c, 0, sizeof(*c));
    c->link = link;
    c->phase = HIL_HUNT;
    c->st.lock_at = HIL_LOCK_NONE;
    c->cap_left = -1;
}

static void cap_push(hil_checker_t *c, uint32_t idx, hil_frame_t got, hil_frame_t exp, bool trigger)
{
    if (c->cap_left == 0) {
        return;                                     /* zamrozony */
    }
    if (trigger && c->cap_left < 0) {
        c->cap_left = HIL_CAP_DEPTH / 2;            /* blad + 15 kolejnych */
    }
    hil_cap_entry_t *e = &c->cap[c->cap_wr % HIL_CAP_DEPTH];
    e->idx = idx;
    e->got = got;
    e->exp = exp;
    c->cap_wr++;
    if (c->cap_left > 0) {
        c->cap_left--;
    }
}

int hil_checker_capture(const hil_checker_t *c, hil_cap_entry_t *out)
{
    uint32_t n = c->cap_wr < HIL_CAP_DEPTH ? c->cap_wr : HIL_CAP_DEPTH;
    uint32_t first = c->cap_wr - n;
    for (uint32_t i = 0; i < n; i++) {
        out[i] = c->cap[(first + i) % HIL_CAP_DEPTH];
    }
    return (int)n;
}

/* Hunt dla ramki f: jesli jest ramka wzorca, zaczyna potwierdzanie. */
static void hunt(hil_checker_t *c, hil_frame_t f, uint32_t idx)
{
    const hil_link_t *k = c->link;
    if (hil_link_is_pattern(k, f)) {
        c->phase = HIL_CONFIRM;
        c->next = hil_link_seq_of(k, f.l) + 1;
        c->seen = 1;
        c->start = idx;
    } else {
        c->phase = HIL_HUNT;
    }
}

void hil_checker_push(hil_checker_t *c, hil_frame_t f)
{
    const hil_link_t *k = c->link;
    uint32_t idx = c->idx++;

    if (hil_frame_silent(f)) {
        if (c->phase == HIL_LOCKED) {
            c->st.gaps++;                           /* luka nie zuzywa numeru */
        }
        return;                                     /* przed lockiem: bez znaczenia */
    }

    switch (c->phase) {
    case HIL_HUNT:
        cap_push(c, idx, f, hil_link_expected(k, hil_link_seq_of(k, f.l)), false);
        hunt(c, f, idx);
        break;
    case HIL_CONFIRM: {
        hil_frame_t exp = hil_link_expected(k, c->next);
        cap_push(c, idx, f, exp, false);
        if (hil_frame_eq(f, exp)) {
            c->next++;
            if (++c->seen == HIL_LOCK_FRAMES) {
                c->st.frames += HIL_LOCK_FRAMES;
                c->st.lock_at = c->start;
                c->phase = HIL_LOCKED;
            }
        } else {
            hunt(c, f, idx);                        /* ciag przerwany: ta ramka moze zaczac nowy */
        }
        break;
    }
    case HIL_LOCKED: {
        hil_frame_t exp = hil_link_expected(k, c->next);
        bool ok = hil_frame_eq(f, exp);
        cap_push(c, idx, f, exp, !ok);
        if (ok) {
            c->st.frames++;
            c->next++;
            break;
        }
        c->st.bad++;
        if (!c->st.has_err) {
            c->st.has_err = true;
            c->st.err_n = c->next;
            c->st.err_got = f;
            c->st.err_exp = exp;
        }
        if (hil_link_is_pattern(k, f)) {
            /* Zgubiona albo zdublowana ramka: skok do przodu o d mod 2^S. */
            c->st.relocks++;
            uint32_t d = (hil_link_seq_of(k, f.l) - c->next) & k->seq_mask;
            c->next += d + 1;
        } else {
            c->next++;                              /* przeklamana ramka zuzywa numer */
        }
        break;
    }
    }
}
