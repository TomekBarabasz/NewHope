/*
 * vertebra-hil, esp32/test: hil_pattern na PC.
 *
 * 1. Wektory kontraktu (to samo, co `selftest` na plytce).
 * 2. Wlasnosci, ktorych wektory nie pokrywaja: pakowanie ramki w bufor DMA,
 *    capture wokol pierwszego bledu, strumien z nadawcy przez transfer().
 *
 * Uzycie: test_hil_pattern <katalog contract/i2s/vectors>
 */
#include "hil_pattern.h"
#include "hil_vectors.h"

#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int failures = 0;

#define CHECK(cond, ...) do {                                   \
        if (!(cond)) {                                          \
            printf("FAIL %s:%d: ", __FILE__, __LINE__);         \
            printf(__VA_ARGS__);                                \
            printf("\n");                                       \
            failures++;                                         \
        }                                                       \
    } while (0)

static hil_csv_t load(const char *dir, const char *name)
{
    char path[512];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    FILE *f = fopen(path, "rb");
    if (!f) {
        perror(path);
        exit(2);
    }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *buf = malloc((size_t)n + 1);
    if (fread(buf, 1, (size_t)n, f) != (size_t)n) {
        perror(path);
        exit(2);
    }
    buf[n] = '\0';                  /* jak EMBED_TXTFILES */
    fclose(f);
    hil_csv_t c = { buf, (size_t)n + 1 };
    return c;
}

static void test_vectors(const char *dir)
{
    hil_vector_set_t v = {
        load(dir, "pattern.csv"), load(dir, "transfer.csv"),
        load(dir, "checker_cases.csv"), load(dir, "checker_frames.csv"),
    };
    char err[256] = "";
    int n = hil_vectors_run(&v, err, sizeof(err));
    CHECK(n > 0, "wektory: %s", err);
    if (n > 0) {
        printf("ok vectors=%d\n", n);
    }

    /* Przeklamany wektor musi byc wykryty (test testu). */
    char *bad = malloc(v.pattern.len);
    memcpy(bad, v.pattern.data, v.pattern.len);
    char *last = strrchr(bad, ',');         /* ostatnia kolumna ostatniego wiersza */
    last[1] = last[1] == '0' ? '1' : '0';
    hil_vector_set_t vb = v;
    vb.pattern.data = bad;
    CHECK(hil_vectors_run(&vb, err, sizeof(err)) < 0, "przeklamany pattern.csv przeszedl");
    free(bad);
    free((void *)v.pattern.data);
    free((void *)v.transfer.data);
    free((void *)v.checker_cases.data);
    free((void *)v.checker_frames.data);
}

static void test_pack(void)
{
    static const int ws[] = { 8, 16, 24, 32 };
    for (int i = 0; i < 4; i++) {
        int w = ws[i];
        hil_frame_t f = hil_pattern_frame(0x12345678u, 77, w);
        uint8_t buf[8] = { 0 };
        hil_pack_frame(buf, f, w);
        hil_frame_t g = hil_unpack_frame(buf, w);
        CHECK(hil_frame_eq(f, g), "pack/unpack w=%d", w);
        CHECK(hil_frame_bytes(w) == w / 4, "bajty ramki w=%d", w);
        /* little endian, L przed R */
        CHECK(buf[0] == (uint8_t)f.l && buf[w / 8] == (uint8_t)f.r, "uklad w=%d", w);
    }
    hil_frame_t f = { 0x00abcdefu, 0x00123456u };
    uint8_t b[6];
    hil_pack_frame(b, f, 24);
    static const uint8_t exp[6] = { 0xef, 0xcd, 0xab, 0x56, 0x34, 0x12 };
    CHECK(memcmp(b, exp, 6) == 0, "24 bity: 3 bajty na probke");
}

static hil_link_t link_;
static hil_checker_t chk;

/* Nadawca -> transfer -> checker, bez bledow, dla wszystkich konfiguracji stanowiska. */
static void test_stream_clean(void)
{
    static const int cfg[][3] = {   /* wtx, slot, wrx */
        { 16, 32, 16 }, { 24, 32, 24 }, { 16, 16, 16 }, { 32, 32, 32 },
        { 8, 8, 8 }, { 24, 32, 16 }, { 16, 32, 24 }, { 32, 16, 16 },
    };
    for (size_t i = 0; i < sizeof(cfg) / sizeof(cfg[0]); i++) {
        int wtx = cfg[i][0], slot = cfg[i][1], wrx = cfg[i][2];
        CHECK(hil_link_init(&link_, 0xC0FFEE00u, wtx, slot, wrx) == HIL_LINK_OK, "link %d/%d/%d", wtx, slot, wrx);
        hil_checker_init(&chk, &link_);
        uint32_t n0 = 0xFFFFFF00u;          /* przez zawiniecie u32 */
        for (int z = 0; z < 5; z++) {
            hil_frame_t zero = { 0, 0 };
            hil_checker_push(&chk, zero);   /* zera z pustego DMA */
        }
        for (uint32_t k = 0; k < 2000; k++) {
            hil_frame_t f = hil_pattern_frame(0xC0FFEE00u, n0 + k, wtx);
            hil_frame_t r = { hil_transfer(f.l, wtx, slot, wrx), hil_transfer(f.r, wtx, slot, wrx) };
            hil_checker_push(&chk, r);
        }
        CHECK(chk.st.frames == 2000 && chk.st.bad == 0 && chk.st.gaps == 0 && chk.st.lock_at == 5,
              "czysty strumien %d/%d/%d: frames=%" PRIu32 " bad=%" PRIu32 " lock_at=%" PRIu32,
              wtx, slot, wrx, chk.st.frames, chk.st.bad, chk.st.lock_at);
    }
    CHECK(hil_link_init(&link_, 1, 32, 8, 8) == HIL_LINK_UNCHECKABLE, "32 -> 8 jest checkable");
    CHECK(hil_link_init(&link_, 1, 7, 8, 8) == HIL_LINK_BAD_PARAM, "wtx=7 przyjete");
}

/* Capture: 16 wpisow przed pierwszym bledem, blad i 15 po nim, potem zamarza. */
static void test_capture(void)
{
    hil_link_init(&link_, 0x1000u, 16, 32, 16);
    hil_checker_init(&chk, &link_);
    const uint32_t bad_at = 100;
    for (uint32_t n = 0; n < 300; n++) {
        hil_frame_t f = hil_pattern_frame(0x1000u, n, 16);
        if (n == bad_at || n == bad_at + 40) {
            f.l ^= 1;                        /* przeklamany bit */
        }
        hil_checker_push(&chk, f);
    }
    CHECK(chk.st.bad == 2 && chk.st.err_n == bad_at, "bad=%" PRIu32 " err_n=%" PRIu32, chk.st.bad, chk.st.err_n);
    hil_cap_entry_t cap[HIL_CAP_DEPTH];
    int n = hil_checker_capture(&chk, cap);
    CHECK(n == HIL_CAP_DEPTH, "cap_count=%d", n);
    CHECK(cap[0].idx == bad_at - 16 && cap[16].idx == bad_at && cap[31].idx == bad_at + 15,
          "okno %" PRIu32 "..%" PRIu32 ", blad na %" PRIu32, cap[0].idx, cap[31].idx, cap[16].idx);
    CHECK(!hil_frame_eq(cap[16].got, cap[16].exp) && hil_frame_eq(cap[15].got, cap[15].exp), "wpis bledu");

    /* Blad na samym poczatku: okno krotsze niz 32. */
    hil_checker_init(&chk, &link_);
    for (uint32_t n2 = 0; n2 < 10; n2++) {
        hil_frame_t f = hil_pattern_frame(0x1000u, n2, 16);
        if (n2 == 5) {
            f.r ^= 0x100;
        }
        hil_checker_push(&chk, f);
    }
    n = hil_checker_capture(&chk, cap);
    CHECK(n == 10 && cap[0].idx == 0 && cap[5].idx == 5, "krotkie okno n=%d", n);
}

int main(int argc, char **argv)
{
    if (argc != 2) {
        fprintf(stderr, "uzycie: %s <contract/i2s/vectors>\n", argv[0]);
        return 2;
    }
    test_vectors(argv[1]);
    test_pack();
    test_stream_clean();
    test_capture();
    if (failures) {
        printf("%d bledow\n", failures);
        return 1;
    }
    printf("wszystko zielone\n");
    return 0;
}
