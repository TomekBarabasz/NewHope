/*
 * vertebra-hil, esp32/test: hil_stream (contract/fe/commands.md) na PC:
 * load (hex, dopisanie, bledy bez zmiany bufora), nadawanie bodzca,
 * nagranie z pojemnoscia, linie rec, wyszukanie bodzca w nagraniu.
 */
#include "hil_stream.h"
#include "hil_cmd.h"

#include <stdio.h>
#include <string.h>

static int failures = 0;

#define CHECK(cond, ...) do {                       \
        if (!(cond)) {                              \
            printf("FAIL %s:%d: ", __FILE__, __LINE__); \
            printf(__VA_ARGS__);                    \
            printf("\n");                           \
            failures++;                             \
        }                                           \
    } while (0)

int main(void)
{
    char msg[200];
    uint32_t w;

    CHECK(hil_hex_word("deadBEEF", &w) && w == 0xDEADBEEFu, "hex %08x", (unsigned)w);
    CHECK(!hil_hex_word("deadbeeg", &w), "zly znak hex");
    CHECK(!hil_hex_word("dead", &w), "za krotkie");

    static uint32_t words[64];
    hil_stim_t st = { words, 64, 0, 0 };
    CHECK(hil_stim_load(&st, 0, "0000000100000002", msg, sizeof(msg)) == 0 && st.n == 2, "load 0: %s", msg);
    CHECK(hil_stim_load(&st, 2, "fffffffe", msg, sizeof(msg)) == 0 && st.n == 3, "dopisanie: %s", msg);
    CHECK(st.sum == 1u, "suma u32 %u", (unsigned)st.sum);            /* 1 + 2 + 0xfffffffe */
    CHECK(hil_stim_load(&st, 5, "00000003", msg, sizeof(msg)) == HIL_ERR_RANGE && st.n == 3, "przerwa: %s", msg);
    CHECK(hil_stim_load(&st, 1, "00000003", msg, sizeof(msg)) == HIL_ERR_RANGE && st.n == 3, "nakladka");
    CHECK(hil_stim_load(&st, 3, "0000000", msg, sizeof(msg)) == HIL_ERR_RANGE, "7 znakow");
    CHECK(hil_stim_load(&st, 3, "00000003000000zz", msg, sizeof(msg)) == HIL_ERR_RANGE && st.n == 3 && words[3] == 0,
          "zly hex nie zmienia bufora: %s", msg);
    char big[8 * 30 + 1];
    memset(big, '0', sizeof(big) - 1);
    big[sizeof(big) - 1] = '\0';
    CHECK(hil_stim_load(&st, 3, big, msg, sizeof(msg)) == HIL_ERR_RANGE, "30 slow w linii");
    big[8 * 29] = '\0';
    CHECK(hil_stim_load(&st, 3, big, msg, sizeof(msg)) == 0 && st.n == 32, "29 slow: %s", msg);
    for (int i = 0; i < 2; i++) {
        hil_stim_load(&st, st.n, big, msg, sizeof(msg));
    }
    CHECK(st.n == 61, "n %u", (unsigned)st.n);
    CHECK(hil_stim_load(&st, 61, "0000000000000000", msg, sizeof(msg)) == 0 && st.n == 63, "do 63");
    CHECK(hil_stim_load(&st, 63, "0000000000000000", msg, sizeof(msg)) == HIL_ERR_RANGE && st.n == 63, "ponad pojemnosc");
    CHECK(hil_stim_load(&st, 0, "12345678", msg, sizeof(msg)) == 0 && st.n == 1 && st.sum == 0x12345678u, "nowy bodziec");

    /* nadawanie: R = ~L, potem zera */
    hil_stim_load(&st, 1, "00000002", msg, sizeof(msg));
    uint32_t buf[2 * 4], pos = 0;
    uint32_t got = hil_stim_fill(&st, &pos, buf, 4);
    CHECK(got == 2 && pos == 2, "fill %u", (unsigned)got);
    CHECK(buf[0] == 0x12345678u && buf[1] == ~0x12345678u && buf[2] == 2 && buf[3] == ~2u, "L, ~L");
    CHECK(buf[4] == 0 && buf[5] == 0 && buf[6] == 0 && buf[7] == 0, "zera po bodzcu");
    CHECK(hil_stim_fill(&st, &pos, buf, 4) == 0 && pos == 2, "dalej zera");

    /* nagranie z pojemnoscia */
    static uint32_t lr[2 * 20];
    hil_rec_t r = { lr, 20, 0, 0 };
    uint32_t frames[2 * 16];
    for (uint32_t i = 0; i < 16; i++) {
        frames[2 * i] = 0xA0000000u + i;
        frames[2 * i + 1] = ~(0xA0000000u + i);
    }
    hil_rec_push(&r, frames, 16);
    hil_rec_push(&r, frames, 16);
    CHECK(r.n == 20 && r.seen == 32, "rec n %u seen %u", (unsigned)r.n, (unsigned)r.seen);
    CHECK(lr[2 * 16] == 0xA0000000u && lr[2 * 19] == 0xA0000003u, "drugi kawalek do pojemnosci");

    char line[300];
    hil_rec_line(&r, 0, 2, line, sizeof(line));
    CHECK(strcmp(line, "a00000005fffffffa00000015ffffffe") == 0, "linia rec: %s", line);
    hil_rec_line(&r, 5, HIL_REC_LINE_FRAMES, line, sizeof(line));
    CHECK(strlen(line) == 16 * HIL_REC_LINE_FRAMES && strlen(line) <= HIL_LINE_MAX, "15 ramek: %zu znakow", strlen(line));
    CHECK(hil_rec_check_range(&r, 0, 20, msg, sizeof(msg)) == 0, "zakres pelny");
    CHECK(hil_rec_check_range(&r, 19, 2, msg, sizeof(msg)) == HIL_ERR_RANGE, "poza nagraniem");
    CHECK(hil_rec_check_range(&r, 0, 0, msg, sizeof(msg)) == HIL_ERR_RANGE, "n=0");
    CHECK(hil_rec_check_range(&r, 21, 1, msg, sizeof(msg)) == HIL_ERR_RANGE, "off za koncem");

    /* selftest: bodziec w nagraniu po kilku ramkach smieci */
    static uint32_t sw[32];
    hil_stim_t s2 = { sw, 32, 0, 0 };
    uint32_t x = 0x5EED1234u;
    char hex[8 * 8 + 1];
    for (int k = 0; k < 4; k++) {
        for (int i = 0; i < 8; i++) {
            x = hil_xorshift32(x);
            snprintf(hex + 8 * i, 9, "%08x", (unsigned)x);
        }
        CHECK(hil_stim_load(&s2, s2.n, hex, msg, sizeof(msg)) == 0, "load selftest: %s", msg);
    }
    static uint32_t lr2[2 * 64];
    hil_rec_t r2 = { lr2, 64, 0, 0 };
    uint32_t junk[2 * 3] = { 0, 0, 0x11, 0x22, 0, 0 };
    hil_rec_push(&r2, junk, 3);
    uint32_t p2 = 0, tx[2 * 40];
    hil_stim_fill(&s2, &p2, tx, 40);
    hil_rec_push(&r2, tx, 40);
    uint32_t at = 99;
    CHECK(hil_rec_find_stim(&r2, &s2, &at, msg, sizeof(msg)) == 0 && at == 3, "find: %s at %u", msg, (unsigned)at);
    lr2[2 * 20 + 1] ^= 1;                                  /* przeklamany R */
    CHECK(hil_rec_find_stim(&r2, &s2, &at, msg, sizeof(msg)) == HIL_ERR_SELFTEST, "przeklamanie niewykryte");
    printf("  (komunikat: %s)\n", msg);
    CHECK(hil_xorshift32(1) == 270369u, "xorshift32(1) = %u", (unsigned)hil_xorshift32(1));

    if (failures) {
        printf("%d bledow\n", failures);
        return 1;
    }
    printf("hil_stream: wszystko zielone\n");
    return 0;
}
