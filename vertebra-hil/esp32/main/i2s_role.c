/*
 * vertebra-hil: rola I2S partnera ESP32-S3 (vertebra-hil.md §7,
 * contract/i2s/commands.md).
 *
 * Firmware nie zna scenariuszy: konfiguruje kanal z `cfg`, nadaje wzorzec,
 * sprawdza wzorzec i raportuje liczniki. Jeden "koniec" magistrali to
 * ep_t: kontroler I2S w trybie std (Philips), generator w tasku TX i
 * checker w tasku RX.
 *
 *   ep_main   rola z `cfg` na pinach magistrali (do FPGA), I2S0
 *   ep_peer   tylko przy `cfg loop=1`: drugi kontroler (I2S1) w roli
 *             przeciwnej, oba na pinach petli wewnetrznej; tak sprawdzamy
 *             ESP32 jako slave'a bez FPGA (etap 3, #9513)
 *
 * `selftest` uzywa ep_main na pinach petli z DIN == DOUT (petla
 * wewnetrzna ESP-IDF) dla kazdej konfiguracji stanowiska.
 */
#include "i2s_role.h"

#include <inttypes.h>
#include <stdio.h>
#include <string.h>

#include "driver/gpio.h"
#include "driver/i2s_std.h"
#include "esp_attr.h"
#include "esp_log.h"
#include "esp_rom_gpio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "hil_cmd.h"
#include "hil_pattern.h"
#include "hil_vectors.h"
#include "soc/i2s_periph.h"
#include "sdkconfig.h"

static const char *TAG = "i2s_role";

/* ------------------------------------------------------------------ */
/*  Klucze cfg (contract/i2s/commands.md)                               */
/* ------------------------------------------------------------------ */

enum { K_ROLE, K_FS, K_W, K_SLOT, K_SEED, K_PEER_W, K_TX, K_RX, K_LOOP, K_COUNT };
enum { ROLE_MASTER, ROLE_SLAVE };

static const char *const s_role_names[] = { "master", "slave", NULL };

static const hil_key_t s_keys[K_COUNT] = {
    [K_ROLE]   = { "role",   HIL_KEY_ENUM, 0, 0,          s_role_names, true,  0 },
    [K_FS]     = { "fs",     HIL_KEY_INT,  8000, 96000,   NULL, true,  0 },
    [K_W]      = { "w",      HIL_KEY_INT,  8, 32,         NULL, true,  0 },
    [K_SLOT]   = { "slot",   HIL_KEY_INT,  8, 32,         NULL, true,  0 },
    [K_SEED]   = { "seed",   HIL_KEY_U32,  0, 0,          NULL, true,  0 },
    /* 0 = rowne w (domyslne w kontrakcie) */
    [K_PEER_W] = { "peer_w", HIL_KEY_INT,  0, 32,         NULL, false, 0 },
    [K_TX]     = { "tx",     HIL_KEY_INT,  0, 1,          NULL, false, 1 },
    [K_RX]     = { "rx",     HIL_KEY_INT,  0, 1,          NULL, false, 1 },
    /* diagnostyka ESP32: partner na drugim kontrolerze, piny petli */
    [K_LOOP]   = { "loop",   HIL_KEY_INT,  0, 1,          NULL, false, 0 },
};

static uint32_t s_values[K_COUNT];

static int peer_w_of(const uint32_t *v)
{
    return v[K_PEER_W] ? (int)v[K_PEER_W] : (int)v[K_W];
}

/* ------------------------------------------------------------------ */
/*  Koniec magistrali                                                   */
/* ------------------------------------------------------------------ */

#define DMA_FRAMES      240     /* wielokrotnosc 3 (24 bity) i 8 */
#define DMA_DESC        8
#define IO_TIMEOUT_MS   100     /* taski sprawdzaja flage stopu co tyle */
#define CHUNK_BYTES     (DMA_FRAMES * 8)

typedef struct {
    int sck, ws, dout, din;
} pins_t;

typedef struct {
    const char *name;
    i2s_port_t port;
    int core;

    /* konfiguracja */
    bool master;
    int fs, w, slot;
    uint32_t seed;
    bool gen, chk;                  /* nadaje wzorzec / sprawdza wzorzec */
    int peer_w;                     /* Wtx checkera */
    pins_t pins;

    /* bieg */
    i2s_chan_handle_t txh, rxh;
    volatile bool run;
    int ntasks;
    SemaphoreHandle_t done;
    hil_frame_t tx_tab[1 << HIL_SEQ_MAX_BITS];     /* surowe slowa W-bitowe dla seq */
    uint32_t tx_n;
    uint32_t tx_seq_mask;
    hil_link_t link;
    hil_checker_t checker;
    volatile uint64_t sent_bytes;   /* oddane do DMA; ramki = / hil_frame_bytes */
    volatile uint32_t overflow;
    uint8_t tx_buf[CHUNK_BYTES];
    uint8_t rx_buf[CHUNK_BYTES + 8];
} ep_t;

static ep_t s_main = { .name = "main", .port = I2S_NUM_0, .core = 0 };
static ep_t s_peer = { .name = "peer", .port = I2S_NUM_1, .core = 1 };
static bool s_loop;                 /* ep_peer w uzyciu */
static bool s_have_run;             /* byl start od resetu: dump/stat maja sens */

static const pins_t s_bus_pins = {
    CONFIG_HIL_PIN_SCK, CONFIG_HIL_PIN_WS, CONFIG_HIL_PIN_DOUT, CONFIG_HIL_PIN_DIN,
};

/* Kanaly: TX istnieje, gdy nadajemy albo gdy nie ma RX (sam zegar). */
static bool ep_has_tx(const ep_t *e) { return e->gen || !e->chk; }
static bool ep_has_rx(const ep_t *e) { return e->chk; }

static int chunk_bytes(const ep_t *e) { return DMA_FRAMES * hil_frame_bytes(e->w); }

/* Linie w wysokiej impedancji, bez podciagania (FPGA ma wlasne, §9). */
static void pins_hiz(const pins_t *p)
{
    gpio_config_t c = {
        .pin_bit_mask = (1ULL << p->sck) | (1ULL << p->ws) | (1ULL << p->dout) | (1ULL << p->din),
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    gpio_config(&c);
}

static void pin_low(int pin)
{
    gpio_config_t c = {
        .pin_bit_mask = 1ULL << pin,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    gpio_config(&c);
    gpio_set_level(pin, 0);
}

static bool IRAM_ATTR on_rx_overflow(i2s_chan_handle_t h, i2s_event_data_t *ev, void *ctx)
{
    (void)h; (void)ev;
    ((ep_t *)ctx)->overflow++;
    return false;
}

#define TRY(call) do {                                                        \
        esp_err_t e_ = (call);                                                \
        if (e_ != ESP_OK) {                                                   \
            snprintf(msg, len, "%s: %s: %s", e->name, #call, esp_err_to_name(e_)); \
            return HIL_ERR_PERIPH;                                            \
        }                                                                     \
    } while (0)

/* Kanaly I2S wedlug konfiguracji ep; nic nie jest jeszcze wlaczone. */
static int ep_open(ep_t *e, bool internal_loopback, char *msg, size_t len)
{
    e->txh = e->rxh = NULL;
    i2s_chan_config_t cc = {
        .id = e->port,
        .role = e->master ? I2S_ROLE_MASTER : I2S_ROLE_SLAVE,
        .dma_desc_num = DMA_DESC,
        .dma_frame_num = DMA_FRAMES,
        .auto_clear = true,         /* brak danych = zera na linii, nie powtorka bufora */
        .intr_priority = 0,
    };
    TRY(i2s_new_channel(&cc, ep_has_tx(e) ? &e->txh : NULL, ep_has_rx(e) ? &e->rxh : NULL));

    bool has24 = e->w == 24 || e->slot == 24;
    /* Slave: fs wyznacza tylko zegar modulu, mclk = 8 x BCLK nominalnego
     * (i2s_std.c). S3 potrzebuje >= 8 x BCLK (#9513), a BCLK mastera FPGA
     * bywa o 0,1% szybszy od nominalu; podwajamy zegar modulu, jesli
     * dzielnik z PLL 160 MHz zostaje >= 2. */
    uint32_t rate = (uint32_t)e->fs;
    if (!e->master && (uint64_t)rate * 2 * (uint32_t)e->slot * 16 <= 80000000ULL) {
        rate *= 2;
    }
    i2s_std_config_t sc = {
        .clk_cfg = {
            .sample_rate_hz = rate,
            .clk_src = I2S_CLK_SRC_DEFAULT,
            .mclk_multiple = has24 ? I2S_MCLK_MULTIPLE_384 : I2S_MCLK_MULTIPLE_256,
        },
        .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG((i2s_data_bit_width_t)e->w, I2S_SLOT_MODE_STEREO),
        .gpio_cfg = {
            .mclk = I2S_GPIO_UNUSED,
            .bclk = e->pins.sck,
            .ws = e->pins.ws,
            .dout = ep_has_tx(e) ? e->pins.dout : I2S_GPIO_UNUSED,
            .din = ep_has_rx(e) ? e->pins.din : I2S_GPIO_UNUSED,
            .invert_flags = { 0 },
        },
    };
    sc.slot_cfg.slot_bit_width = (i2s_slot_bit_width_t)e->slot;
    sc.slot_cfg.ws_width = (uint32_t)e->slot;    /* makro daje ws_width = w: przy paddingu WS bylby za krotki */
    if (internal_loopback) {
        sc.gpio_cfg.din = sc.gpio_cfg.dout;         /* ESP-IDF laczy DOUT z DIN w matrycy GPIO */
    }
    if (e->txh) {
        TRY(i2s_channel_init_std_mode(e->txh, &sc));
    }
    if (e->rxh) {
        TRY(i2s_channel_init_std_mode(e->rxh, &sc));
        i2s_event_callbacks_t cb = { .on_recv_q_ovf = on_rx_overflow };
        TRY(i2s_channel_register_event_callback(e->rxh, &cb, e));
    }
    return 0;
}

static void ep_close(ep_t *e)
{
    if (e->txh) {
        i2s_del_channel(e->txh);
        e->txh = NULL;
    }
    if (e->rxh) {
        i2s_del_channel(e->rxh);
        e->rxh = NULL;
    }
}

static void fill_tx(ep_t *e, uint8_t *buf, int frames)
{
    int fb = hil_frame_bytes(e->w);
    for (int i = 0; i < frames; i++) {
        hil_pack_frame(buf + i * fb, e->tx_tab[e->tx_n & e->tx_seq_mask], e->w);
        e->tx_n++;
    }
}

static void tx_task(void *arg)
{
    ep_t *e = arg;
    int cb = chunk_bytes(e);
    size_t pending = 0, off = 0;
    while (e->run) {
        if (pending == 0) {
            fill_tx(e, e->tx_buf, DMA_FRAMES);
            pending = (size_t)cb;
            off = 0;
        }
        size_t wr = 0;
        i2s_channel_write(e->txh, e->tx_buf + off, pending, &wr, IO_TIMEOUT_MS);
        off += wr;
        pending -= wr;
        e->sent_bytes += wr;
    }
    xSemaphoreGive(e->done);
    vTaskDelete(NULL);
}

static void rx_task(void *arg)
{
    ep_t *e = arg;
    int cb = chunk_bytes(e);
    int fb = hil_frame_bytes(e->w);
    size_t carry = 0;               /* niepelna ramka z poprzedniego odczytu */
    while (e->run) {
        size_t rd = 0;
        i2s_channel_read(e->rxh, e->rx_buf + carry, (size_t)cb, &rd, IO_TIMEOUT_MS);
        size_t have = carry + rd;
        size_t i = 0;
        for (; i + (size_t)fb <= have; i += (size_t)fb) {
            hil_checker_push(&e->checker, hil_unpack_frame(e->rx_buf + i, e->w));
        }
        carry = have - i;
        if (carry) {
            memmove(e->rx_buf, e->rx_buf + i, carry);
        }
    }
    xSemaphoreGive(e->done);
    vTaskDelete(NULL);
}

/* Liczniki, generator i checker od zera; wymaga poprawnego linku. */
static void ep_reset_run_state(ep_t *e)
{
    e->sent_bytes = 0;
    e->overflow = 0;
    e->tx_n = 0;
    e->tx_seq_mask = (1u << hil_seq_bits(e->w)) - 1u;
    for (uint32_t n = 0; n <= e->tx_seq_mask; n++) {
        e->tx_tab[n] = hil_pattern_frame(e->seed, n, e->w);
    }
    if (e->chk) {
        hil_link_init(&e->link, e->seed, e->peer_w, e->slot, e->w);   /* sprawdzone w validate */
    } else {
        /* checker bez ramek: liczniki zostaja zerami (kontrakt: rx=0) */
        hil_link_init(&e->link, e->seed, 32, 32, 32);
    }
    hil_checker_init(&e->checker, &e->link);
}

/* Najpierw RX, potem TX (contract/commands.md, start). */
static int ep_enable(ep_t *e, char *msg, size_t len)
{
    if (!e->done) {
        e->done = xSemaphoreCreateCounting(2, 0);
    }
    e->run = true;
    e->ntasks = 0;
    if (e->rxh) {
        TRY(i2s_channel_enable(e->rxh));
        xTaskCreatePinnedToCore(rx_task, "hil_rx", 4096, e, 10, NULL, e->core);
        e->ntasks++;
    }
    if (e->txh) {
        if (e->gen) {
            /* Wzorzec od pierwszej ramki, zamiast zer z pustego DMA. */
            for (;;) {
                size_t loaded = 0;
                fill_tx(e, e->tx_buf, DMA_FRAMES);
                i2s_channel_preload_data(e->txh, e->tx_buf, (size_t)chunk_bytes(e), &loaded);
                e->sent_bytes += loaded;
                if (loaded < (size_t)chunk_bytes(e)) {
                    /* bufor DMA pelny: niezaladowana reszta idzie jeszcze raz */
                    e->tx_n -= (uint32_t)(DMA_FRAMES - loaded / (size_t)hil_frame_bytes(e->w));
                    break;
                }
            }
        }
        TRY(i2s_channel_enable(e->txh));
        if (e->gen) {
            xTaskCreatePinnedToCore(tx_task, "hil_tx", 4096, e, 10, NULL, e->core);
            e->ntasks++;
        }
    }
    return 0;
}

static void ep_disable(ep_t *e)
{
    e->run = false;
    for (int i = 0; i < e->ntasks; i++) {
        xSemaphoreTake(e->done, portMAX_DELAY);     /* taski koncza sie po <= IO_TIMEOUT_MS */
    }
    e->ntasks = 0;
    if (e->txh) {
        i2s_channel_disable(e->txh);
    }
    if (e->rxh) {
        i2s_channel_disable(e->rxh);
    }
    ep_close(e);
}

/* ------------------------------------------------------------------ */
/*  Petla na dwoch kontrolerach: piny wspolne w matrycy GPIO            */
/* ------------------------------------------------------------------ */

/*
 * Kazdy z dwoch kanalow ustawia swoje piny przez gpio_set_direction, a
 * to przy wejsciu odlacza wyjscie matrycy (gpio_ll_output_disable), wiec
 * po inicjalizacji obu kontrolerow wspolne piny laczymy od nowa, tak jak
 * i2s_gpio_loopback_set w ESP-IDF: wejscie i wyjscie na tym samym padzie.
 * Wybor sygnalow powtarza i2s_std_set_gpio (ESP-IDF 5.2).
 */
static void route(int pin, int out_sig, int in_sig)
{
    gpio_set_direction(pin, GPIO_MODE_INPUT_OUTPUT);
    esp_rom_gpio_connect_out_signal(pin, (uint32_t)out_sig, false, false);
    esp_rom_gpio_connect_in_signal(pin, (uint32_t)in_sig, false);
}

static void route_loop(const ep_t *m, const ep_t *s)
{
    const i2s_signal_conn_t *ms = &i2s_periph_signal[m->port];
    const i2s_signal_conn_t *ss = &i2s_periph_signal[s->port];
    bool m_tx = ep_has_tx(m), s_tx_only = ep_has_tx(s) && !ep_has_rx(s);
    route(m->pins.sck, m_tx ? ms->m_tx_bck_sig : ms->m_rx_bck_sig, s_tx_only ? ss->s_tx_bck_sig : ss->s_rx_bck_sig);
    route(m->pins.ws,  m_tx ? ms->m_tx_ws_sig  : ms->m_rx_ws_sig,  s_tx_only ? ss->s_tx_ws_sig  : ss->s_rx_ws_sig);
    /* dane: m.dout -> s.din i s.dout -> m.din (piny sa skrzyzowane w ep_t) */
    if (m->gen && s->chk) {
        route(m->pins.dout, ms->data_out_sig, ss->data_in_sig);
    }
    if (s->gen && m->chk) {
        route(s->pins.dout, ss->data_out_sig, ms->data_in_sig);
    }
}

/* ------------------------------------------------------------------ */
/*  hil_role_t                                                          */
/* ------------------------------------------------------------------ */

static bool is_byte_width(uint32_t w) { return w == 8 || w == 16 || w == 24 || w == 32; }

static int role_validate(const uint32_t *v, char *msg, size_t len)
{
    if (!is_byte_width(v[K_W])) {
        snprintf(msg, len, "w=%" PRIu32 ": dozwolone 8, 16, 24, 32", v[K_W]);
        return HIL_ERR_RANGE;
    }
    if (!is_byte_width(v[K_SLOT]) || v[K_SLOT] < v[K_W]) {
        snprintf(msg, len, "slot=%" PRIu32 ": dozwolone 8, 16, 24, 32 i slot >= w (w=%" PRIu32 ")", v[K_SLOT], v[K_W]);
        return HIL_ERR_RANGE;
    }
    int pw = peer_w_of(v);
    if (pw < HIL_PATTERN_MIN_W) {
        snprintf(msg, len, "peer_w=%d: dozwolone 8..32", pw);
        return HIL_ERR_RANGE;
    }
    static hil_link_t probe;        /* ~2 KiB, nie na stosie taska komend */
    if (v[K_RX]) {
        hil_link_err_t le = hil_link_init(&probe, v[K_SEED], pw, (int)v[K_SLOT], (int)v[K_W]);
        if (le != HIL_LINK_OK) {
            snprintf(msg, len, "polaczenie peer_w=%d slot=%" PRIu32 " w=%" PRIu32 " nie jest checkable: "
                     "odbiorca widzi %d bitow, potrzebuje %d (kanal + seq)",
                     pw, v[K_SLOT], v[K_W], hil_link_eff_bits(&probe), 1 + hil_seq_bits(pw));
            return HIL_ERR_RANGE;
        }
    }
    if (v[K_LOOP] && v[K_TX]) {
        /* partner odbiera slowa w, nadane przez nas; jego checker: Link(seed, w, slot, peer_w) */
        hil_link_err_t le = hil_link_init(&probe, v[K_SEED], (int)v[K_W], (int)v[K_SLOT], pw);
        if (le != HIL_LINK_OK) {
            snprintf(msg, len, "loop=1: polaczenie w=%" PRIu32 " -> peer_w=%d nie jest checkable", v[K_W], pw);
            return HIL_ERR_RANGE;
        }
    }
    if (v[K_LOOP] && pw != 8 && pw != 16 && pw != 24 && pw != 32) {
        snprintf(msg, len, "loop=1: peer_w=%d to szerokosc drugiego kontrolera, dozwolone 8, 16, 24, 32", pw);
        return HIL_ERR_RANGE;
    }
    if (v[K_LOOP] && v[K_SLOT] < (uint32_t)pw) {
        snprintf(msg, len, "loop=1: slot=%" PRIu32 " < peer_w=%d", v[K_SLOT], pw);
        return HIL_ERR_RANGE;
    }
    return 0;
}

static void ep_configure_main(ep_t *e, const uint32_t *v, const pins_t *pins)
{
    e->master = v[K_ROLE] == ROLE_MASTER;
    e->fs = (int)v[K_FS];
    e->w = (int)v[K_W];
    e->slot = (int)v[K_SLOT];
    e->seed = v[K_SEED];
    e->gen = v[K_TX] != 0;
    e->chk = v[K_RX] != 0;
    e->peer_w = peer_w_of(v);
    e->pins = *pins;
}

static int start_loop(char *msg, size_t len);

static int role_start(char *msg, size_t len)
{
    s_have_run = true;
    if (s_values[K_LOOP]) {
        return start_loop(msg, len);
    }
    s_loop = false;
    ep_t *e = &s_main;
    ep_configure_main(e, s_values, &s_bus_pins);
    ep_reset_run_state(e);
    int r = ep_open(e, false, msg, len);
    if (r) {
        ep_close(e);
        pins_hiz(&e->pins);
        return r;
    }
    if (!ep_has_tx(e)) {
        pin_low(e->pins.dout);                          /* tx=0: DOUT w zerach */
    }
    r = ep_enable(e, msg, len);
    if (r) {
        ep_disable(e);
        pins_hiz(&e->pins);
        return r;
    }
    ESP_LOGI(TAG, "start %s fs=%d w=%d slot=%d peer_w=%d tx=%d rx=%d",
             e->master ? "master" : "slave", e->fs, e->w, e->slot, e->peer_w, e->gen, e->chk);
    return 0;
}

static int start_loop(char *msg, size_t len)
{
    const pins_t lp = { CONFIG_HIL_PIN_LOOP_SCK, CONFIG_HIL_PIN_LOOP_WS, CONFIG_HIL_PIN_LOOP_A, CONFIG_HIL_PIN_LOOP_B };
    const pins_t pp = { lp.sck, lp.ws, lp.din, lp.dout };  /* dane skrzyzowane */
    ep_t *m = &s_main, *p = &s_peer;
    ep_configure_main(m, s_values, &lp);
    p->master = !m->master;
    p->fs = m->fs;
    p->w = m->peer_w;
    p->slot = m->slot;
    p->seed = m->seed;
    p->gen = m->chk;
    p->chk = m->gen;
    p->peer_w = m->w;
    p->pins = pp;
    ep_reset_run_state(m);
    ep_reset_run_state(p);
    s_loop = true;

    int r = ep_open(m, false, msg, len);
    if (!r) {
        r = ep_open(p, false, msg, len);
    }
    if (r) {
        ep_close(m);
        ep_close(p);
        pins_hiz(&lp);
        return r;
    }
    ep_t *mst = m->master ? m : p, *slv = m->master ? p : m;
    route_loop(mst, slv);
    /* Najpierw strona bez zegara, potem master (vertebra-hil.md §3). */
    r = ep_enable(slv, msg, len);
    if (!r) {
        r = ep_enable(mst, msg, len);
    }
    if (r) {
        ep_disable(mst);
        ep_disable(slv);
        pins_hiz(&lp);
        return r;
    }
    ESP_LOGI(TAG, "start loop: main %s w=%d, peer w=%d, slot=%d fs=%d",
             m->master ? "master" : "slave", m->w, p->w, m->slot, m->fs);
    return 0;
}

static void role_stop(void)
{
    if (!hil_cmd_running()) {
        pins_hiz(&s_bus_pins);      /* stop w stanie stop: tylko upewnienie sie, ze linie sa wolne */
        return;
    }
    if (s_loop) {
        ep_t *mst = s_main.master ? &s_main : &s_peer, *slv = s_main.master ? &s_peer : &s_main;
        ep_disable(mst);            /* najpierw zegar, potem odbiorca */
        ep_disable(slv);
        pins_hiz(&s_main.pins);
    } else {
        ep_disable(&s_main);
        pins_hiz(&s_bus_pins);
    }
}

static int fmt_counters(char *out, size_t len, const char *pfx, const ep_t *e)
{
    const hil_stat_t *st = &e->checker.st;
    char lock[16], err[64];
    if (st->lock_at == HIL_LOCK_NONE) {
        snprintf(lock, sizeof(lock), "-1");
    } else {
        snprintf(lock, sizeof(lock), "%" PRIu32, st->lock_at);
    }
    if (st->has_err) {
        snprintf(err, sizeof(err), "%" PRIu32 ":%08" PRIx32 ":%08" PRIx32 ":%08" PRIx32 ":%08" PRIx32,
                 st->err_n, st->err_got.l, st->err_got.r, st->err_exp.l, st->err_exp.r);
    } else {
        snprintf(err, sizeof(err), "-");
    }
    return snprintf(out, len,
                    "%ssent=%" PRIu32 " %sframes=%" PRIu32 " %sbad=%" PRIu32 " %sgaps=%" PRIu32
                    " %srelocks=%" PRIu32 " %slock_at=%s %sfirst_err=%s %soverflow=%" PRIu32,
                    pfx, (uint32_t)(e->sent_bytes / (uint64_t)hil_frame_bytes(e->w)), pfx, st->frames, pfx, st->bad, pfx, st->gaps,
                    pfx, st->relocks, pfx, lock, pfx, err, pfx, e->overflow);
}

static void role_stat(char *out, size_t len)
{
    if (!s_have_run) {
        snprintf(out, len, "sent=0 frames=0 bad=0 gaps=0 relocks=0 lock_at=-1 first_err=- overflow=0");
        return;
    }
    int n = fmt_counters(out, len, "", &s_main);
    if (s_loop && n > 0 && (size_t)n + 1 < len) {
        out[n] = ' ';
        fmt_counters(out + n + 1, len - (size_t)n - 1, "peer_", &s_peer);
    }
}

static hil_cap_entry_t s_cap[HIL_CAP_DEPTH];
static int s_cap_n;

static int role_dump_count(void)
{
    s_cap_n = s_have_run ? hil_checker_capture(&s_main.checker, s_cap) : 0;
    return s_cap_n;
}

static void role_dump_line(int i, char *out, size_t len)
{
    const hil_cap_entry_t *c = &s_cap[i];
    snprintf(out, len, "%" PRIu32 " %08" PRIx32 " %08" PRIx32 " %08" PRIx32 " %08" PRIx32,
             c->idx, c->got.l, c->got.r, c->exp.l, c->exp.r);
}

/* ------------------------------------------------------------------ */
/*  selftest                                                            */
/* ------------------------------------------------------------------ */

typedef struct {
    int fs, w, slot;
} loop_cfg_t;

/* Konfiguracje stanowiska (wariantow FPGA) i najkrotsze slowo. */
static const loop_cfg_t s_loop_cfgs[] = {
    { 48000, 16, 32 }, { 44100, 24, 32 }, { 48000, 16, 16 }, { 48000, 32, 32 }, { 48000, 8, 8 },
};

#define SELFTEST_FRAMES     2000
#define SELFTEST_SEED       0x5EED1234u     /* >= 2^9, pattern.md "Seed" */

static int loopback_one(const loop_cfg_t *lc, char *msg, size_t len)
{
    ep_t *e = &s_main;
    const pins_t lp = { CONFIG_HIL_PIN_LOOP_SCK, CONFIG_HIL_PIN_LOOP_WS, CONFIG_HIL_PIN_LOOP_A, CONFIG_HIL_PIN_LOOP_A };
    e->master = true;
    e->fs = lc->fs;
    e->w = lc->w;
    e->slot = lc->slot;
    e->seed = SELFTEST_SEED;
    e->gen = e->chk = true;
    e->peer_w = lc->w;
    e->pins = lp;
    ep_reset_run_state(e);
    int r = ep_open(e, true, msg, len);
    if (!r) {
        r = ep_enable(e, msg, len);
    }
    if (!r) {
        TickType_t t0 = xTaskGetTickCount();
        while (e->checker.st.frames < SELFTEST_FRAMES && xTaskGetTickCount() - t0 < pdMS_TO_TICKS(2000)) {
            vTaskDelay(pdMS_TO_TICKS(10));
        }
    }
    ep_disable(e);
    pins_hiz(&lp);
    if (r) {
        return r;
    }
    const hil_stat_t *st = &e->checker.st;
    if (st->frames < SELFTEST_FRAMES || st->bad || st->gaps || st->relocks || e->overflow) {
        char c[200];
        fmt_counters(c, sizeof(c), "", e);
        snprintf(msg, len, "petla w=%d slot=%d fs=%d: %s", lc->w, lc->slot, lc->fs, c);
        return HIL_ERR_SELFTEST;
    }
    hil_cmd_log("petla w=%d slot=%d fs=%d: frames=%" PRIu32 " lock_at=%" PRIu32 " ok",
                lc->w, lc->slot, lc->fs, st->frames, st->lock_at);
    return 0;
}

static int role_selftest(int *vectors, char *msg, size_t len)
{
    s_loop = false;
    hil_vector_set_t v = hil_vectors_embedded();
    char err[160];
    int n = hil_vectors_run(&v, err, sizeof(err));
    if (n < 0) {
        snprintf(msg, len, "wektory: %s", err);
        return HIL_ERR_SELFTEST;
    }
    for (size_t i = 0; i < sizeof(s_loop_cfgs) / sizeof(s_loop_cfgs[0]); i++) {
        int r = loopback_one(&s_loop_cfgs[i], msg, len);
        if (r) {
            return r;
        }
    }
    s_have_run = false;             /* liczniki selftestu to nie wynik biegu */
    *vectors = n;
    return 0;
}

/* ------------------------------------------------------------------ */

static const hil_role_t s_role = {
    .ip = "i2s",
    .keys = s_keys,
    .nkeys = K_COUNT,
    .values = s_values,
    .validate = role_validate,
    .start = role_start,
    .stop = role_stop,
    .stat = role_stat,
    .dump_count = role_dump_count,
    .dump_line = role_dump_line,
    .selftest = role_selftest,
};

const hil_role_t *i2s_role_init(void)
{
    pins_hiz(&s_bus_pins);
    ESP_LOGI(TAG, "piny: SCK=%d WS=%d DOUT=%d DIN=%d, petla %d/%d/%d/%d",
             CONFIG_HIL_PIN_SCK, CONFIG_HIL_PIN_WS, CONFIG_HIL_PIN_DOUT, CONFIG_HIL_PIN_DIN,
             CONFIG_HIL_PIN_LOOP_SCK, CONFIG_HIL_PIN_LOOP_WS, CONFIG_HIL_PIN_LOOP_A, CONFIG_HIL_PIN_LOOP_B);
    return &s_role;
}
