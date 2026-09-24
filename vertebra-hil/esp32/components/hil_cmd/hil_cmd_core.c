/*
 * vertebra-hil: rdzen protokolu tekstowego (contract/commands.md).
 * Bez ESP-IDF: transport podaje linie i dostaje odpowiedzi przez write.
 */
#include "hil_cmd.h"

#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define HIL_MAX_KEYS    16
#define OUT_MAX         (HIL_LINE_MAX + 1)

static const hil_role_t *s_role;
static const char *s_build;
static hil_write_fn s_write;
static bool s_configured;           /* bylo udane cfg od resetu */
static volatile bool s_running;

/* ------------------------------------------------------------------ */
/*  Wyjscie                                                             */
/* ------------------------------------------------------------------ */

static void vemit(const char *prefix, const char *fmt, va_list ap)
{
    char buf[OUT_MAX + 64];
    int n = snprintf(buf, sizeof(buf), "%s", prefix);
    int m = vsnprintf(buf + n, sizeof(buf) - (size_t)n - 1, fmt, ap);
    if (m < 0) {
        m = 0;
    }
    n += m;
    if (n > (int)sizeof(buf) - 2) {
        n = (int)sizeof(buf) - 2;           /* obciete, ale nadal jedna linia */
    }
    /* Opis bledu nie moze rozbic linii odpowiedzi. */
    for (int i = 0; i < n; i++) {
        if (buf[i] == '\n' || buf[i] == '\r') {
            buf[i] = ' ';
        }
    }
    buf[n++] = '\n';
    s_write(buf, (size_t)n);
}

static void emit(const char *prefix, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
static void emit(const char *prefix, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vemit(prefix, fmt, ap);
    va_end(ap);
}

#define OK(...)          emit("ok", __VA_ARGS__)
#define ERR(code, ...)   do { char p_[8]; snprintf(p_, sizeof(p_), "err %d ", (code)); emit(p_, __VA_ARGS__); } while (0)

void hil_cmd_log(const char *fmt, ...)
{
    if (!s_write) {
        return;
    }
    va_list ap;
    va_start(ap, fmt);
    vemit("# ", fmt, ap);
    va_end(ap);
}

bool hil_cmd_running(void) { return s_running; }

void hil_cmd_init(const hil_role_t *role, const char *build, hil_write_fn write)
{
    s_role = role;
    s_build = build;
    s_write = write;
    s_configured = false;
    s_running = false;
    for (int i = 0; i < role->nkeys; i++) {
        role->values[i] = role->keys[i].dflt;
    }
}

/* ------------------------------------------------------------------ */
/*  cfg                                                                 */
/* ------------------------------------------------------------------ */

static bool parse_u32(const char *s, uint32_t *out, bool allow_hex)
{
    if (*s == '\0' || *s == '-' || *s == '+' || *s == ' ') {
        return false;
    }
    int base = 10;
    if (s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) {
        if (!allow_hex) {
            return false;
        }
        base = 16;
        s += 2;
        if (*s == '\0') {
            return false;
        }
    }
    char *end;
    errno = 0;
    unsigned long long v = strtoull(s, &end, base);
    if (*end != '\0' || errno != 0 || v > 0xFFFFFFFFull) {
        return false;
    }
    *out = (uint32_t)v;
    return true;
}

/* 0 albo kod bledu; opis w msg. */
static int parse_value(const hil_key_t *k, const char *v, uint32_t *out, char *msg, size_t len)
{
    switch (k->type) {
    case HIL_KEY_INT: {
        uint32_t u;
        bool neg = v[0] == '-';
        if (!parse_u32(neg ? v + 1 : v, &u, false) || u > 0x7FFFFFFFu) {
            snprintf(msg, len, "%s=%s: to nie liczba", k->name, v);
            return HIL_ERR_RANGE;
        }
        int32_t x = neg ? -(int32_t)u : (int32_t)u;
        if (x < k->min || x > k->max) {
            snprintf(msg, len, "%s=%s poza [%ld, %ld]", k->name, v, (long)k->min, (long)k->max);
            return HIL_ERR_RANGE;
        }
        *out = (uint32_t)x;
        return 0;
    }
    case HIL_KEY_U32:
        if (!parse_u32(v, out, true)) {
            snprintf(msg, len, "%s=%s: to nie u32", k->name, v);
            return HIL_ERR_RANGE;
        }
        return 0;
    case HIL_KEY_ENUM:
        for (uint32_t i = 0; k->names[i]; i++) {
            if (strcmp(k->names[i], v) == 0) {
                *out = i;
                return 0;
            }
        }
        snprintf(msg, len, "%s=%s: dozwolone", k->name, v);
        for (int i = 0; k->names[i]; i++) {
            size_t n = strlen(msg);
            snprintf(msg + n, len - n, "%s%s", i ? "|" : " ", k->names[i]);
        }
        return HIL_ERR_RANGE;
    }
    return HIL_ERR_RANGE;
}

static void cmd_cfg(char **argv, int argc)
{
    const hil_role_t *r = s_role;
    char msg[160] = "";
    if (s_running) {
        ERR(HIL_ERR_STATE, "cfg w trakcie biegu, najpierw stop");
        return;
    }
    uint32_t staged[HIL_MAX_KEYS];
    bool given[HIL_MAX_KEYS] = { false };
    memcpy(staged, r->values, sizeof(uint32_t) * (size_t)r->nkeys);

    /* Najpierw cala linia, dopiero potem zmiana: blad nie zostawia polowy cfg. */
    for (int a = 1; a < argc; a++) {
        char *eq = strchr(argv[a], '=');
        if (!eq || eq == argv[a]) {
            ERR(HIL_ERR_UNKNOWN_KEY, "'%s': oczekiwane klucz=wartosc", argv[a]);
            return;
        }
        *eq = '\0';
        const char *key = argv[a], *val = eq + 1;
        int ki = -1;
        for (int i = 0; i < r->nkeys; i++) {
            if (strcmp(r->keys[i].name, key) == 0) {
                ki = i;
                break;
            }
        }
        if (ki < 0) {
            ERR(HIL_ERR_UNKNOWN_KEY, "nieznany klucz '%s'", key);
            return;
        }
        if (given[ki]) {
            ERR(HIL_ERR_RANGE, "klucz '%s' podany dwa razy", key);
            return;
        }
        int e = parse_value(&r->keys[ki], val, &staged[ki], msg, sizeof(msg));
        if (e) {
            ERR(e, "%s", msg);
            return;
        }
        given[ki] = true;
    }
    if (!s_configured) {
        for (int i = 0; i < r->nkeys; i++) {
            if (r->keys[i].required && !given[i]) {
                ERR(HIL_ERR_RANGE, "pierwsze cfg po resecie musi podac '%s'", r->keys[i].name);
                return;
            }
        }
    }
    if (r->validate) {
        int e = r->validate(staged, msg, sizeof(msg));
        if (e) {
            ERR(e, "%s", msg);
            return;
        }
    }
    memcpy(r->values, staged, sizeof(uint32_t) * (size_t)r->nkeys);
    s_configured = true;
    OK("%s", "");
}

/* ------------------------------------------------------------------ */
/*  Pozostale komendy                                                   */
/* ------------------------------------------------------------------ */

static void cmd_ver(char **argv, int argc)
{
    (void)argv; (void)argc;
    OK(" proto=%d dev=esp32s3 ip=%s build=%s", HIL_PROTO_VERSION, s_role->ip, s_build);
}

static void cmd_start(char **argv, int argc)
{
    (void)argv; (void)argc;
    char msg[160] = "";
    if (s_running) {
        ERR(HIL_ERR_STATE, "bieg juz trwa");
        return;
    }
    if (!s_configured) {
        ERR(HIL_ERR_STATE, "start bez cfg");
        return;
    }
    int e = s_role->start(msg, sizeof(msg));
    if (e) {
        ERR(e, "%s", msg);
        return;
    }
    s_running = true;
    OK("%s", "");
}

static void cmd_stop(char **argv, int argc)
{
    (void)argv; (void)argc;
    s_role->stop();                 /* w kazdym stanie: wymusza SCK/WS w wysokiej impedancji */
    s_running = false;
    OK("%s", "");
}

static void cmd_stat(char **argv, int argc)
{
    (void)argv; (void)argc;
    char out[OUT_MAX];
    s_role->stat(out, sizeof(out));
    OK(" %s", out);
}

static void cmd_dump(char **argv, int argc)
{
    (void)argv; (void)argc;
    if (s_running) {
        ERR(HIL_ERR_STATE, "dump w trakcie biegu, najpierw stop");
        return;
    }
    int n = s_role->dump_count();
    OK(" n=%d", n);
    for (int i = 0; i < n; i++) {
        char out[OUT_MAX];
        s_role->dump_line(i, out, sizeof(out));
        emit("", "%s", out);
    }
    OK(" end");
}

static void cmd_selftest(char **argv, int argc)
{
    (void)argv; (void)argc;
    char msg[200] = "";
    if (s_running) {
        ERR(HIL_ERR_STATE, "selftest w trakcie biegu, najpierw stop");
        return;
    }
    int vectors = 0;
    int e = s_role->selftest(&vectors, msg, sizeof(msg));
    if (e) {
        ERR(e, "%s", msg);
        return;
    }
    OK(" vectors=%d", vectors);
}

typedef struct {
    const char *name;
    void (*fn)(char **argv, int argc);
} cmd_t;

static const cmd_t s_cmds[] = {
    { "ver", cmd_ver },
    { "cfg", cmd_cfg },
    { "start", cmd_start },
    { "stop", cmd_stop },
    { "stat", cmd_stat },
    { "dump", cmd_dump },
    { "selftest", cmd_selftest },
};

void hil_cmd_line(char *line)
{
    size_t n = strlen(line);
    while (n > 0 && line[n - 1] == '\r') {
        line[--n] = '\0';
    }
    char *argv[HIL_MAX_KEYS + 2];
    int argc = 0;
    for (char *t = strtok(line, " "); t; t = strtok(NULL, " ")) {
        if (argc == (int)(sizeof(argv) / sizeof(argv[0]))) {
            ERR(HIL_ERR_RANGE, "za duzo argumentow");
            return;
        }
        argv[argc++] = t;
    }
    if (argc == 0) {
        return;
    }
    for (size_t i = 0; i < sizeof(s_cmds) / sizeof(s_cmds[0]); i++) {
        if (strcmp(s_cmds[i].name, argv[0]) == 0) {
            if (argc > 1 && s_cmds[i].fn != cmd_cfg) {
                ERR(HIL_ERR_RANGE, "%s nie ma argumentow", argv[0]);
                return;
            }
            s_cmds[i].fn(argv, argc);
            return;
        }
    }
    ERR(HIL_ERR_UNKNOWN_CMD, "nieznana komenda '%s'", argv[0]);
}

void hil_cmd_line_too_long(void)
{
    ERR(HIL_ERR_UNKNOWN_CMD, "linia dluzsza niz %d znakow", HIL_LINE_MAX);
}
