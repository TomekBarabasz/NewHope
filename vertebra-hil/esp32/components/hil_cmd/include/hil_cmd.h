/*
 * vertebra-hil: protokol tekstowy ESP32 (contract/commands.md, "ESP32").
 *
 * Wspolny dla wszystkich IP: transport (USB-Serial-JTAG), parser linii,
 * komendy ver/cfg/start/stop/stat/dump/selftest, rejestr kluczy `cfg`
 * z typami i zakresami, stan stop/bieg. Nic tu nie zna I2S: rola IP
 * rejestruje klucze i funkcje (hil_role_t), a hil_cmd pilnuje kontraktu
 * (kody bledow, stan, kompletnosc pierwszego cfg).
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define HIL_PROTO_VERSION   1
#define HIL_LINE_MAX        255

/* Kody `err` (contract/commands.md) */
enum {
    HIL_ERR_UNKNOWN_CMD = 1,
    HIL_ERR_UNKNOWN_KEY = 2,
    HIL_ERR_RANGE       = 3,
    HIL_ERR_STATE       = 4,
    HIL_ERR_PERIPH      = 5,
    HIL_ERR_SELFTEST    = 6,
};

typedef enum {
    HIL_KEY_INT,        /* dziesietnie, [min, max] */
    HIL_KEY_U32,        /* dziesietnie albo 0x..., caly zakres u32 */
    HIL_KEY_ENUM,       /* jedna z nazw `names`; wartosc to indeks */
} hil_key_type_t;

typedef struct {
    const char *name;
    hil_key_type_t type;
    int32_t min, max;               /* HIL_KEY_INT */
    const char *const *names;       /* HIL_KEY_ENUM, zakonczone NULL */
    bool required;                  /* musi byc w pierwszym cfg po resecie */
    uint32_t dflt;                  /* dla niewymaganych, przed pierwszym cfg */
} hil_key_t;

/*
 * Rola IP. Funkcje zwracaja 0 albo kod `err`; opis bledu wpisuja do `msg`.
 * `values` to tablica wartosci kluczy (indeks jak w `keys`), wlasnosc roli;
 * hil_cmd zapisuje ja tylko w stanie stop i tylko po udanym `validate`.
 */
typedef struct {
    const char *ip;                 /* "i2s" */
    const hil_key_t *keys;
    int nkeys;
    uint32_t *values;

    /* Sprawdzenie calej konfiguracji przed jej przyjeciem (np. checkable). */
    int (*validate)(const uint32_t *values, char *msg, size_t len);
    int (*start)(char *msg, size_t len);
    void (*stop)(void);
    /* Liczniki: "sent=... frames=..." bez "ok ". */
    void (*stat)(char *out, size_t len);
    /* Okno wokol pierwszego bledu: liczba wpisow i wpis i jako tekst linii. */
    int (*dump_count)(void);
    void (*dump_line)(int i, char *out, size_t len);
    /* Test wlasny roli (wektory + petla wewnetrzna); `vectors` = liczba. */
    int (*selftest)(int *vectors, char *msg, size_t len);
} hil_role_t;

/* ---- rdzen protokolu (hil_cmd_core.c, bez ESP-IDF, testowany na PC) ---- */

/* Zapis calej linii (z '\n') do transportu; musi byc bezpieczny watkowo. */
typedef void (*hil_write_fn)(const char *s, size_t n);

void hil_cmd_init(const hil_role_t *role, const char *build, hil_write_fn write);

/* Jedna linia komendy bez '\n' ('\r' na koncu jest pomijane). Zapisuje
 * dokladnie jedna linie odpowiedzi (dump: n + 2). Pusta linia: nic. */
void hil_cmd_line(char *line);

/* Linia dluzsza niz HIL_LINE_MAX: odpowiedz bledem zamiast wykonania. */
void hil_cmd_line_too_long(void);

/* Bajty z transportu: sklada linie i wykonuje je przez hil_cmd_line.
 * Linie konczy '\n' albo '\r' (terminal typu PuTTY wysyla na Enter sam
 * '\r'); "\r\n" daje linie i pusta linie, ktora nic nie robi. */
void hil_cmd_feed(const char *data, size_t n);

/* ---- transport (hil_cmd_usj.c, ESP-IDF) ---- */

/* Instaluje USB-Serial-JTAG i uruchamia task komend. Wywolac raz. */
void hil_cmd_start(const hil_role_t *role);

/* Czy trwa bieg (miedzy udanym `start` a `stop`). */
bool hil_cmd_running(void);

/* Linia logu w strumieniu protokolu: "# ...", host ja pomija. */
void hil_cmd_log(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

#ifdef __cplusplus
}
#endif
