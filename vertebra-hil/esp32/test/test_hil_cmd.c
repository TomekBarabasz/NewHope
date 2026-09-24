/*
 * vertebra-hil, esp32/test: rdzen protokolu tekstowego (hil_cmd_core.c)
 * na PC, z rola-atrapa. Sprawdza kontrakt z contract/commands.md: jedna
 * linia odpowiedzi, kody bledow, stan, cfg atomowe i kompletne.
 */
#include "hil_cmd.h"

#include <stdio.h>
#include <string.h>

static int failures = 0;
static char out[8192];
static size_t out_len;
static int writes;

static void cap_write(const char *s, size_t n)
{
    memcpy(out + out_len, s, n);
    out_len += n;
    out[out_len] = '\0';
    writes++;
}

enum { K_ROLE, K_W, K_SEED, K_TX, K_COUNT };
static const char *const roles[] = { "master", "slave", NULL };
static const hil_key_t keys[K_COUNT] = {
    [K_ROLE] = { "role", HIL_KEY_ENUM, 0, 0, roles, true, 0 },
    [K_W]    = { "w",    HIL_KEY_INT,  8, 32, NULL, true, 0 },
    [K_SEED] = { "seed", HIL_KEY_U32,  0, 0, NULL, true, 0 },
    [K_TX]   = { "tx",   HIL_KEY_INT,  0, 1, NULL, false, 1 },
};
static uint32_t values[K_COUNT];
static int starts, stops;
static int fail_start;

static int v_validate(const uint32_t *v, char *msg, size_t len)
{
    if (v[K_W] % 8) {
        snprintf(msg, len, "w=%u nie jest wielokrotnoscia 8", (unsigned)v[K_W]);
        return HIL_ERR_RANGE;
    }
    return 0;
}
static int v_start(char *msg, size_t len)
{
    if (fail_start) {
        snprintf(msg, len, "i2s_new_channel: ESP_ERR_NOT_FOUND");
        return HIL_ERR_PERIPH;
    }
    starts++;
    return 0;
}
static void v_stop(void) { stops++; }
static void v_stat(char *o, size_t len) { snprintf(o, len, "sent=1 frames=2"); }
static int v_dump_count(void) { return 2; }
static void v_dump_line(int i, char *o, size_t len) { snprintf(o, len, "%d 00000001 00000002 00000003 00000004", i); }
static int v_selftest(int *n, char *msg, size_t len) { (void)msg; (void)len; *n = 702; return 0; }

static const hil_role_t role = {
    "i2s", keys, K_COUNT, values, v_validate, v_start, v_stop, v_stat, v_dump_count, v_dump_line, v_selftest,
};

/* Wysyla linie i porownuje cala odpowiedz (prefiks, gdy exp konczy sie na '*'). */
static void expect(const char *cmd, const char *exp)
{
    char line[300];
    snprintf(line, sizeof(line), "%s", cmd);
    out_len = 0;
    out[0] = '\0';
    writes = 0;
    hil_cmd_line(line);
    size_t n = strlen(exp);
    bool ok = n > 0 && exp[n - 1] == '*' ? strncmp(out, exp, n - 1) == 0 : strcmp(out, exp) == 0;
    if (!ok) {
        printf("FAIL '%s'\n  got: %s  exp: %s\n", cmd, out, exp);
        failures++;
    }
}

int main(void)
{
    hil_cmd_init(&role, "bef20891", cap_write);

    expect("ver", "ok proto=1 dev=esp32s3 ip=i2s build=bef20891\n");
    expect("ver\r", "ok proto=1 dev=esp32s3 ip=i2s build=bef20891\n");
    expect("", "");
    expect("   ", "");
    expect("foo", "err 1 nieznana komenda 'foo'\n");
    expect("start", "err 4 start bez cfg\n");

    /* pierwsze cfg musi byc kompletne; blad nic nie zmienia */
    expect("cfg role=master w=16", "err 3 pierwsze cfg po resecie musi podac 'seed'\n");
    expect("cfg role=master w=16 seed=42 wdth=3", "err 2 nieznany klucz 'wdth'\n");
    expect("cfg role=master w=16 seed=42 x", "err 2 'x': oczekiwane klucz=wartosc\n");
    expect("cfg role=mistrz w=16 seed=42", "err 3 role=mistrz: dozwolone master|slave\n");
    expect("cfg role=master w=40 seed=42", "err 3 w=40 poza [8, 32]\n");
    expect("cfg role=master w=12 seed=42", "err 3 w=12 nie jest wielokrotnoscia 8\n");
    expect("cfg role=master w=16 seed=0x1_", "err 3 seed=0x1_: to nie u32\n");
    expect("cfg role=master w=16 seed=4294967296", "err 3 seed=4294967296: to nie u32\n");
    expect("cfg role=master w=16 seed=42 w=24", "err 3 klucz 'w' podany dwa razy\n");
    if (values[K_W] != 0 || values[K_TX] != 1) {
        printf("FAIL cfg z bledem zmienilo wartosci\n");
        failures++;
    }
    expect("cfg role=slave w=16 seed=0xDEADBEEF", "ok\n");
    if (values[K_ROLE] != 1 || values[K_W] != 16 || values[K_SEED] != 0xDEADBEEFu || values[K_TX] != 1) {
        printf("FAIL wartosci po cfg\n");
        failures++;
    }
    expect("cfg w=24", "ok\n");                        /* kolejne cfg: czesciowe */
    expect("cfg seed=123", "ok\n");
    if (values[K_W] != 24 || values[K_SEED] != 123 || values[K_ROLE] != 1) {
        printf("FAIL czesciowe cfg\n");
        failures++;
    }

    fail_start = 1;
    expect("start", "err 5 i2s_new_channel: ESP_ERR_NOT_FOUND\n");
    if (hil_cmd_running()) {
        printf("FAIL bieg po nieudanym starcie\n");
        failures++;
    }
    fail_start = 0;
    expect("start", "ok\n");
    expect("start", "err 4 bieg juz trwa\n");
    expect("cfg w=16", "err 4 cfg w trakcie biegu, najpierw stop\n");
    expect("dump", "err 4 dump w trakcie biegu, najpierw stop\n");
    expect("selftest", "err 4 selftest w trakcie biegu, najpierw stop\n");
    expect("stat", "ok sent=1 frames=2\n");
    expect("stop", "ok\n");
    expect("stop", "ok\n");                            /* stop w kazdym stanie */
    expect("stat now", "err 3 stat nie ma argumentow\n");
    expect("dump", "ok n=2\n0 00000001 00000002 00000003 00000004\n1 00000001 00000002 00000003 00000004\nok end\n");
    expect("selftest", "ok vectors=702\n");
    if (starts != 1 || stops != 2) {
        printf("FAIL starts=%d stops=%d\n", starts, stops);
        failures++;
    }

    out_len = 0;
    hil_cmd_log("petla %d ok\nnowa linia", 16);
    if (strcmp(out, "# petla 16 ok nowa linia\n") != 0) {
        printf("FAIL log: %s", out);
        failures++;
    }
    out_len = 0;
    hil_cmd_line_too_long();
    if (strcmp(out, "err 1 linia dluzsza niz 255 znakow\n") != 0) {
        printf("FAIL za dluga linia: %s", out);
        failures++;
    }

    /* hil_cmd_feed: PuTTY (sam CR), LF, CRLF, linia w kawalkach, za dluga linia */
    static const struct { const char *in; const char *exp; } feeds[] = {
        { "ver\r", "ok proto=1 dev=esp32s3 ip=i2s build=bef20891\n" },
        { "ver\n", "ok proto=1 dev=esp32s3 ip=i2s build=bef20891\n" },
        { "ver\r\n", "ok proto=1 dev=esp32s3 ip=i2s build=bef20891\n" },
        { "st", "" },
        { "at\r", "ok sent=1 frames=2\n" },
        { "\r\n\n", "" },
        { "ver\r\nfoo\r\n", "ok proto=1 dev=esp32s3 ip=i2s build=bef20891\nerr 1 nieznana komenda 'foo'\n" },
    };
    for (size_t i = 0; i < sizeof(feeds) / sizeof(feeds[0]); i++) {
        out_len = 0;
        out[0] = '\0';
        hil_cmd_feed(feeds[i].in, strlen(feeds[i].in));
        if (strcmp(out, feeds[i].exp) != 0) {
            printf("FAIL feed %zu\n  got: %s  exp: %s\n", i, out, feeds[i].exp);
            failures++;
        }
    }
    char longl[400];
    memset(longl, 'x', sizeof(longl));
    out_len = 0;
    hil_cmd_feed(longl, sizeof(longl));
    hil_cmd_feed("\rver\r", 5);
    if (strcmp(out, "err 1 linia dluzsza niz 255 znakow\nok proto=1 dev=esp32s3 ip=i2s build=bef20891\n") != 0) {
        printf("FAIL feed za dluga linia: %s", out);
        failures++;
    }

    if (failures) {
        printf("%d bledow\n", failures);
        return 1;
    }
    printf("hil_cmd: wszystko zielone\n");
    return 0;
}
