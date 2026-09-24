/*
 * vertebra-hil: sprawdzenie hil_pattern na wektorach kontraktu.
 * Ten sam kod dziala w firmware (selftest) i na PC (esp32/test).
 */
#include "hil_vectors.h"
#include "hil_pattern.h"

#include <inttypes.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_COLS 16
#define MAX_LINE 256

/* ------------------------------------------------------------------ */
/*  Minimalny czytnik CSV: wiersz po wierszu, bez cudzyslowow.          */
/* ------------------------------------------------------------------ */

typedef struct {
    const char *p, *end;
    int line;                       /* numer wiersza w pliku, od 1 */
    char buf[MAX_LINE];
    char *col[MAX_COLS];
    int ncol;
} csv_reader_t;

static void csv_open(csv_reader_t *r, const hil_csv_t *f)
{
    r->p = f->data;
    r->end = f->data + f->len;
    /* EMBED_TXTFILES dopisuje '\0' na koncu, ktory nie jest czescia pliku */
    while (r->end > r->p && r->end[-1] == '\0') {
        r->end--;
    }
    r->line = 0;
}

/* Nastepny niepusty wiersz; false na koncu pliku albo przy za dlugim wierszu. */
static bool csv_next(csv_reader_t *r)
{
    while (r->p < r->end) {
        const char *s = r->p;
        const char *e = memchr(s, '\n', (size_t)(r->end - s));
        if (!e) {
            e = r->end;
        }
        r->p = e < r->end ? e + 1 : e;
        r->line++;
        size_t n = (size_t)(e - s);
        if (n > 0 && s[n - 1] == '\r') {
            n--;
        }
        if (n == 0) {
            continue;
        }
        if (n >= MAX_LINE) {
            return false;
        }
        memcpy(r->buf, s, n);
        r->buf[n] = '\0';
        r->ncol = 0;
        char *c = r->buf;
        for (;;) {
            if (r->ncol < MAX_COLS) {
                r->col[r->ncol++] = c;
            }
            char *comma = strchr(c, ',');
            if (!comma) {
                break;
            }
            *comma = '\0';
            c = comma + 1;
        }
        return true;
    }
    return false;
}

static uint32_t hex(const char *s) { return (uint32_t)strtoul(s, NULL, 16); }
static uint32_t dec(const char *s) { return (uint32_t)strtoul(s, NULL, 10); }
static bool none(const char *s) { return strcmp(s, "-") == 0; }

#define FAIL(...) do { snprintf(err, err_len, __VA_ARGS__); return -1; } while (0)

/* ------------------------------------------------------------------ */

static int run_pattern(const hil_csv_t *f, char *err, size_t err_len)
{
    csv_reader_t r;
    csv_open(&r, f);
    int n = 0;
    csv_next(&r);                                   /* naglowek */
    while (csv_next(&r)) {
        if (r.ncol != 5) {
            FAIL("pattern.csv:%d: %d kolumn", r.line, r.ncol);
        }
        uint32_t seed = hex(r.col[0]), fn = dec(r.col[1]);
        int c = (int)dec(r.col[2]), w = (int)dec(r.col[3]);
        uint32_t exp = hex(r.col[4]);
        if (hil_seq_bits(w) < 0 || (c != 0 && c != 1)) {
            FAIL("pattern.csv:%d: w=%d c=%d", r.line, w, c);
        }
        uint32_t got = hil_word(seed, fn, c, w);
        if (got != exp) {
            FAIL("pattern.csv:%d seed=%08" PRIx32 " n=%" PRIu32 " c=%d w=%d got=%08" PRIx32 " exp=%08" PRIx32,
                 r.line, seed, fn, c, w, got, exp);
        }
        n++;
    }
    return n;
}

static int run_transfer(const hil_csv_t *f, char *err, size_t err_len)
{
    csv_reader_t r;
    csv_open(&r, f);
    int n = 0;
    csv_next(&r);
    while (csv_next(&r)) {
        if (r.ncol != 5) {
            FAIL("transfer.csv:%d: %d kolumn", r.line, r.ncol);
        }
        uint32_t v = hex(r.col[0]), exp = hex(r.col[4]);
        int wtx = (int)dec(r.col[1]), slot = (int)dec(r.col[2]), wrx = (int)dec(r.col[3]);
        if (wtx < 1 || wtx > 32 || wrx < 1 || wrx > 32 || slot < 0) {
            FAIL("transfer.csv:%d: wtx=%d slot=%d wrx=%d", r.line, wtx, slot, wrx);
        }
        uint32_t got = hil_transfer(v, wtx, slot, wrx);
        if (got != exp) {
            FAIL("transfer.csv:%d word=%08" PRIx32 " %d/%d/%d got=%08" PRIx32 " exp=%08" PRIx32,
                 r.line, v, wtx, slot, wrx, got, exp);
        }
        n++;
    }
    return n;
}

/* Kolumny checker_cases.csv */
enum { C_CASE, C_SEED, C_WTX, C_SLOT, C_WRX, C_NFRAMES, C_FRAMES, C_BAD, C_GAPS, C_RELOCKS,
       C_LOCK_AT, C_ERR_N, C_ERR_GOT_L, C_ERR_GOT_R, C_ERR_EXP_L, C_ERR_EXP_R, C_COUNT };

static hil_link_t s_link;           /* ~2 KiB: nie na stosie taska komend */
static hil_checker_t s_chk;

static int run_checker(const hil_csv_t *cases, const hil_csv_t *frames, char *err, size_t err_len)
{
    csv_reader_t rc;
    csv_open(&rc, cases);
    int n = 0;
    csv_next(&rc);
    while (csv_next(&rc)) {
        if (rc.ncol != C_COUNT) {
            FAIL("checker_cases.csv:%d: %d kolumn", rc.line, rc.ncol);
        }
        const char *name = rc.col[C_CASE];
        hil_link_err_t le = hil_link_init(&s_link, hex(rc.col[C_SEED]), (int)dec(rc.col[C_WTX]),
                                          (int)dec(rc.col[C_SLOT]), (int)dec(rc.col[C_WRX]));
        if (le != HIL_LINK_OK) {
            FAIL("checker %s: link %d", name, (int)le);
        }
        hil_checker_init(&s_chk, &s_link);

        csv_reader_t rf;
        csv_open(&rf, frames);
        csv_next(&rf);
        uint32_t fed = 0;
        while (csv_next(&rf)) {
            if (rf.ncol != 4) {
                FAIL("checker_frames.csv:%d: %d kolumn", rf.line, rf.ncol);
            }
            if (strcmp(rf.col[0], name) != 0) {
                continue;
            }
            if (dec(rf.col[1]) != fed) {
                FAIL("checker_frames.csv:%d: %s idx=%s, oczekiwany %" PRIu32, rf.line, name, rf.col[1], fed);
            }
            hil_frame_t f = { hex(rf.col[2]), hex(rf.col[3]) };
            hil_checker_push(&s_chk, f);
            fed++;
        }

        const hil_stat_t *st = &s_chk.st;
        uint32_t lock_exp = strcmp(rc.col[C_LOCK_AT], "-1") == 0 ? HIL_LOCK_NONE : dec(rc.col[C_LOCK_AT]);
        if (fed != dec(rc.col[C_NFRAMES])) {
            FAIL("checker %s: ramek %" PRIu32 ", oczekiwane %s", name, fed, rc.col[C_NFRAMES]);
        }
        if (st->frames != dec(rc.col[C_FRAMES]) || st->bad != dec(rc.col[C_BAD]) ||
            st->gaps != dec(rc.col[C_GAPS]) || st->relocks != dec(rc.col[C_RELOCKS]) ||
            st->lock_at != lock_exp) {
            FAIL("checker %s: frames=%" PRIu32 " bad=%" PRIu32 " gaps=%" PRIu32 " relocks=%" PRIu32
                 " lock_at=%" PRId32 ", oczekiwane %s/%s/%s/%s/%s",
                 name, st->frames, st->bad, st->gaps, st->relocks, (int32_t)st->lock_at,
                 rc.col[C_FRAMES], rc.col[C_BAD], rc.col[C_GAPS], rc.col[C_RELOCKS], rc.col[C_LOCK_AT]);
        }
        bool err_exp = !none(rc.col[C_ERR_N]);
        if (st->has_err != err_exp ||
            (err_exp && (st->err_n != dec(rc.col[C_ERR_N]) ||
                         st->err_got.l != hex(rc.col[C_ERR_GOT_L]) || st->err_got.r != hex(rc.col[C_ERR_GOT_R]) ||
                         st->err_exp.l != hex(rc.col[C_ERR_EXP_L]) || st->err_exp.r != hex(rc.col[C_ERR_EXP_R])))) {
            FAIL("checker %s: first_err %s %" PRIu32 ":%08" PRIx32 ":%08" PRIx32 ":%08" PRIx32 ":%08" PRIx32
                 ", oczekiwany %s:%s:%s:%s:%s",
                 name, st->has_err ? "tak" : "nie", st->err_n, st->err_got.l, st->err_got.r,
                 st->err_exp.l, st->err_exp.r, rc.col[C_ERR_N], rc.col[C_ERR_GOT_L],
                 rc.col[C_ERR_GOT_R], rc.col[C_ERR_EXP_L], rc.col[C_ERR_EXP_R]);
        }
        n++;
    }
    return n;
}

int hil_vectors_run(const hil_vector_set_t *v, char *err, size_t err_len)
{
    int a = run_pattern(&v->pattern, err, err_len);
    if (a < 0) {
        return -1;
    }
    int b = run_transfer(&v->transfer, err, err_len);
    if (b < 0) {
        return -1;
    }
    int c = run_checker(&v->checker_cases, &v->checker_frames, err, err_len);
    if (c < 0) {
        return -1;
    }
    if (a == 0 || b == 0 || c == 0) {
        snprintf(err, err_len, "pusty plik wektorow (pattern %d, transfer %d, checker %d)", a, b, c);
        return -1;
    }
    return a + b + c;
}
