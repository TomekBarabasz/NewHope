/*
 * vertebra-hil: rola frontendu N0 + N1 (contract/fe/commands.md,
 * vertebra-hil.md §12).
 *
 * ESP32 udaje mikrofon INMP441: jest slave'em I2S (SCK/WS daje I2sMicRx
 * na FPGA), nadaje bodziec wgrany przez `load` (L = slowo, R = ~L) i na
 * tym samym kontrolerze nagrywa do PSRAM ramki wyniku z FPGA. Firmware nic
 * nie ocenia - nagranie czyta host przez `rec` i porownuje z DcGolden.
 *
 *   start: kanal I2S0 slave, slot 32 / slowo 32, TX + RX (full duplex);
 *          TX wstepnie zaladowany bodzcem, RX nagrywa od pierwszej ramki
 *   stop:  kanal wylaczony, piny w wysokiej impedancji, nagranie zostaje
 *
 * Bufory (hil_stream) leza w PSRAM: bodziec do 1 MiB, nagranie do 5 MiB.
 * Logika bez ESP-IDF (load, nagranie, linie rec) jest w hil_stream
 * i ma testy na PC (esp32/test/test_hil_stream.c).
 */
#include "fe_role.h"

#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "driver/gpio.h"
#include "driver/i2s_std.h"
#include "esp_attr.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "hil_cmd.h"
#include "hil_stream.h"
#include "sdkconfig.h"

static const char *TAG = "fe_role";

/* ------------------------------------------------------------------ */
/*  Klucze cfg (contract/fe/commands.md)                                */
/* ------------------------------------------------------------------ */

enum { K_FS, K_REC, K_COUNT };

static const hil_key_t s_keys[K_COUNT] = {
    [K_FS]  = { "fs",  HIL_KEY_INT, 8000, 96000, NULL, true,  0 },
    [K_REC] = { "rec", HIL_KEY_INT, 0, 1,        NULL, false, 1 },
};

static uint32_t s_values[K_COUNT];

/* ------------------------------------------------------------------ */
/*  Stan                                                                */
/* ------------------------------------------------------------------ */

#define STIM_CAP        (256u * 1024u)      /* slowa: 1 MiB */
#define REC_CAP         (640u * 1024u)      /* ramki: 5 MiB */
#define DMA_FRAMES      240
#define DMA_DESC        8
#define IO_TIMEOUT_MS   100
#define FRAME_BYTES     8                   /* L, R po 32 bity */
#define CHUNK_BYTES     (DMA_FRAMES * FRAME_BYTES)

typedef struct {
    int sck, ws, dout, din;
} pins_t;

static const pins_t s_bus_pins = {
    CONFIG_HIL_PIN_SCK, CONFIG_HIL_PIN_WS, CONFIG_HIL_PIN_DOUT, CONFIG_HIL_PIN_DIN,
};

static hil_stim_t s_stim;
static hil_rec_t s_rec;
static bool s_psram;                        /* bufory przydzielone */

static i2s_chan_handle_t s_txh, s_rxh;
static volatile bool s_run;
static int s_ntasks;
static SemaphoreHandle_t s_done;
static uint32_t s_pos;                      /* nastepne slowo bodzca */
static volatile uint64_t s_sent;            /* ramki bodzca oddane do DMA */
static volatile uint32_t s_overflow;
static bool s_record;
static uint32_t s_tx_buf[2 * DMA_FRAMES];
static uint32_t s_rx_buf[2 * DMA_FRAMES + 2];

/* Linie w wysokiej impedancji, bez podciagania (spoczynek ustala FPGA). */
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

static bool IRAM_ATTR on_rx_overflow(i2s_chan_handle_t h, i2s_event_data_t *ev, void *ctx)
{
    (void)h; (void)ev; (void)ctx;
    s_overflow++;
    return false;
}

#define TRY(call) do {                                                        \
        esp_err_t e_ = (call);                                                \
        if (e_ != ESP_OK) {                                                   \
            snprintf(msg, len, "%s: %s", #call, esp_err_to_name(e_));         \
            return HIL_ERR_PERIPH;                                            \
        }                                                                     \
    } while (0)

/*
 * Kanal 32/32 Philips, TX + RX. `master` tylko w selftescie (petla
 * wewnetrzna: DIN == DOUT). Slave: fs to nominal dla zegara modulu; jak
 * w i2s_role.c podwajamy go, zeby zegar modulu mial zapas nad 8 x BCLK
 * (#9513), gdy dzielnik z PLL 160 MHz na to pozwala.
 */
static int chan_open(bool master, int fs, const pins_t *p, bool internal_loopback, char *msg, size_t len)
{
    s_txh = s_rxh = NULL;
    i2s_chan_config_t cc = {
        .id = I2S_NUM_0,
        .role = master ? I2S_ROLE_MASTER : I2S_ROLE_SLAVE,
        .dma_desc_num = DMA_DESC,
        .dma_frame_num = DMA_FRAMES,
        .auto_clear = true,                 /* koniec bodzca = zera na linii */
        .intr_priority = 0,
    };
    TRY(i2s_new_channel(&cc, &s_txh, &s_rxh));

    uint32_t rate = (uint32_t)fs;
    if (!master && (uint64_t)rate * 2 * 32 * 16 <= 80000000ULL) {
        rate *= 2;
    }
    i2s_std_config_t sc = {
        .clk_cfg = {
            .sample_rate_hz = rate,
            .clk_src = I2S_CLK_SRC_DEFAULT,
            .mclk_multiple = I2S_MCLK_MULTIPLE_256,
        },
        .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_32BIT, I2S_SLOT_MODE_STEREO),
        .gpio_cfg = {
            .mclk = I2S_GPIO_UNUSED,
            .bclk = p->sck,
            .ws = p->ws,
            .dout = p->dout,
            .din = internal_loopback ? p->dout : p->din,
            .invert_flags = { 0 },
        },
    };
    sc.slot_cfg.slot_bit_width = I2S_SLOT_BIT_WIDTH_32BIT;
    sc.slot_cfg.ws_width = 32;
    TRY(i2s_channel_init_std_mode(s_txh, &sc));
    TRY(i2s_channel_init_std_mode(s_rxh, &sc));
    i2s_event_callbacks_t cb = { .on_recv_q_ovf = on_rx_overflow };
    TRY(i2s_channel_register_event_callback(s_rxh, &cb, NULL));
    return 0;
}

static void chan_close(void)
{
    if (s_txh) {
        i2s_del_channel(s_txh);
        s_txh = NULL;
    }
    if (s_rxh) {
        i2s_del_channel(s_rxh);
        s_rxh = NULL;
    }
}

static void tx_task(void *arg)
{
    const hil_stim_t *st = arg;
    size_t pending = 0, off = 0;
    while (s_run) {
        if (pending == 0) {
            s_sent += hil_stim_fill(st, &s_pos, s_tx_buf, DMA_FRAMES);
            pending = CHUNK_BYTES;
            off = 0;
        }
        size_t wr = 0;
        i2s_channel_write(s_txh, (uint8_t *)s_tx_buf + off, pending, &wr, IO_TIMEOUT_MS);
        off += wr;
        pending -= wr;
    }
    xSemaphoreGive(s_done);
    vTaskDelete(NULL);
}

static void rx_task(void *arg)
{
    hil_rec_t *r = arg;
    size_t carry = 0;                       /* niepelna ramka z poprzedniego odczytu */
    while (s_run) {
        size_t rd = 0;
        i2s_channel_read(s_rxh, (uint8_t *)s_rx_buf + carry, CHUNK_BYTES, &rd, IO_TIMEOUT_MS);
        size_t have = carry + rd;
        uint32_t frames = (uint32_t)(have / FRAME_BYTES);
        if (frames) {
            if (s_record) {
                hil_rec_push(r, s_rx_buf, frames);
            } else {
                r->seen += frames;
            }
        }
        carry = have - (size_t)frames * FRAME_BYTES;
        if (carry) {
            memmove(s_rx_buf, (uint8_t *)s_rx_buf + (size_t)frames * FRAME_BYTES, carry);
        }
    }
    xSemaphoreGive(s_done);
    vTaskDelete(NULL);
}

/* RX najpierw, potem TX wstepnie zaladowany bodzcem (bodziec od pierwszej
 * ramki, a nie od zer z pustego DMA). Bez zegara (slave) nic jeszcze nie
 * plynie; ramki zaczna sie, gdy FPGA ruszy SCK. */
static int run_begin(const hil_stim_t *st, hil_rec_t *r, char *msg, size_t len)
{
    if (!s_done) {
        s_done = xSemaphoreCreateCounting(2, 0);
    }
    s_pos = 0;
    s_sent = 0;
    s_overflow = 0;
    hil_rec_reset(r);
    s_run = true;
    s_ntasks = 0;
    TRY(i2s_channel_enable(s_rxh));
    xTaskCreatePinnedToCore(rx_task, "fe_rx", 4096, r, 10, NULL, 0);
    s_ntasks++;
    for (;;) {
        size_t loaded = 0;
        uint32_t before = s_pos;
        uint32_t from = hil_stim_fill(st, &s_pos, s_tx_buf, DMA_FRAMES);
        i2s_channel_preload_data(s_txh, s_tx_buf, CHUNK_BYTES, &loaded);
        uint32_t took = (uint32_t)(loaded / FRAME_BYTES);
        /* niezaladowana reszta pojdzie jeszcze raz z tx_task */
        if (took < DMA_FRAMES) {
            s_pos = before + (took < from ? took : from);
            s_sent += took < from ? took : from;
            break;
        }
        s_sent += from;
    }
    TRY(i2s_channel_enable(s_txh));
    xTaskCreatePinnedToCore(tx_task, "fe_tx", 4096, (void *)st, 10, NULL, 0);
    s_ntasks++;
    return 0;
}

static void run_end(void)
{
    s_run = false;
    for (int i = 0; i < s_ntasks; i++) {
        xSemaphoreTake(s_done, portMAX_DELAY);     /* taski koncza sie po <= IO_TIMEOUT_MS */
    }
    s_ntasks = 0;
    if (s_txh) {
        i2s_channel_disable(s_txh);
    }
    if (s_rxh) {
        i2s_channel_disable(s_rxh);
    }
    chan_close();
}

/* ------------------------------------------------------------------ */
/*  hil_role_t                                                          */
/* ------------------------------------------------------------------ */

static bool s_have_run;

static int need_psram(char *msg, size_t len)
{
    if (!s_psram) {
        snprintf(msg, len, "brak PSRAM na bufory (CONFIG_SPIRAM, sdkconfig.fe)");
        return HIL_ERR_PERIPH;
    }
    return 0;
}

static int role_start(char *msg, size_t len)
{
    int r = need_psram(msg, len);
    if (r) {
        return r;
    }
    s_have_run = true;
    s_record = s_values[K_REC] != 0;
    r = chan_open(false, (int)s_values[K_FS], &s_bus_pins, false, msg, len);
    if (!r) {
        r = run_begin(&s_stim, &s_rec, msg, len);
    }
    if (r) {
        run_end();
        pins_hiz(&s_bus_pins);
        return r;
    }
    ESP_LOGI(TAG, "start slave fs=%" PRIu32 " bodziec %" PRIu32 " slow, nagranie %s",
             s_values[K_FS], s_stim.n, s_record ? "tak" : "nie");
    return 0;
}

static void role_stop(void)
{
    if (hil_cmd_running()) {
        run_end();
    }
    pins_hiz(&s_bus_pins);
}

static void role_stat(char *out, size_t len)
{
    snprintf(out, len,
             "sent=%" PRIu32 " frames=%" PRIu32 " bad=0 gaps=0 relocks=0 lock_at=-1 first_err=- overflow=%" PRIu32
             " stim=%" PRIu32 " stim_sum=%08" PRIx32 " rec=%" PRIu32 " rec_max=%" PRIu32,
             s_have_run ? (uint32_t)s_sent : 0u, s_have_run ? (uint32_t)s_rec.seen : 0u,
             s_have_run ? s_overflow : 0u, s_stim.n, s_stim.sum, s_have_run ? s_rec.n : 0u, s_rec.cap);
}

static int role_dump_count(void) { return 0; }
static void role_dump_line(int i, char *out, size_t len) { (void)i; snprintf(out, len, "-"); }

static int validate(const uint32_t *v, char *msg, size_t len)
{
    (void)v; (void)msg; (void)len;
    return 0;
}

static bool parse_u32_dec(const char *s, uint32_t *out)
{
    if (!s || !*s) {
        return false;
    }
    char *end;
    unsigned long long x = strtoull(s, &end, 10);
    if (*end || x > 0xFFFFFFFFull || *s == '-' || *s == '+') {
        return false;
    }
    *out = (uint32_t)x;
    return true;
}

/* load off=<n> data=<hex> */
static int cmd_load(int argc, char **argv, char *msg, size_t len)
{
    static const char *const ks[] = { "off", "data", NULL };
    const char *v[2];
    int e = hil_cmd_args(argc, argv, ks, v, msg, len);
    if (e) {
        return e;
    }
    if ((e = need_psram(msg, len)) != 0) {
        return e;
    }
    uint32_t off;
    if (!parse_u32_dec(v[0], &off) || !v[1]) {
        snprintf(msg, len, "load: wymagane off=<liczba> data=<hex>");
        return HIL_ERR_RANGE;
    }
    e = hil_stim_load(&s_stim, off, v[1], msg, len);
    if (e) {
        return e;
    }
    hil_cmd_ok(" n=%" PRIu32, s_stim.n);
    return 0;
}

/* rec off=<ramka> n=<ramek> */
static int cmd_rec(int argc, char **argv, char *msg, size_t len)
{
    static const char *const ks[] = { "off", "n", NULL };
    const char *v[2];
    int e = hil_cmd_args(argc, argv, ks, v, msg, len);
    if (e) {
        return e;
    }
    uint32_t off, n;
    if (!parse_u32_dec(v[0], &off) || !parse_u32_dec(v[1], &n)) {
        snprintf(msg, len, "rec: wymagane off=<liczba> n=<liczba>");
        return HIL_ERR_RANGE;
    }
    if ((e = hil_rec_check_range(&s_rec, off, n, msg, len)) != 0) {
        return e;
    }
    uint32_t lines = (n + HIL_REC_LINE_FRAMES - 1) / HIL_REC_LINE_FRAMES;
    hil_cmd_ok(" n=%" PRIu32, lines);
    char line[HIL_REC_LINE_FRAMES * 16 + 1];
    for (uint32_t i = 0; i < n; i += HIL_REC_LINE_FRAMES) {
        uint32_t k = n - i < HIL_REC_LINE_FRAMES ? n - i : HIL_REC_LINE_FRAMES;
        hil_rec_line(&s_rec, off + i, k, line, sizeof(line));
        hil_cmd_data(line);
    }
    hil_cmd_ok(" end");
    return 0;
}

static const hil_role_cmd_t s_cmds[] = {
    { "load", true, cmd_load },
    { "rec", true, cmd_rec },
};

/* ------------------------------------------------------------------ */
/*  selftest: petla wewnetrzna, master 16 kHz, 4096 slow xorshift32     */
/* ------------------------------------------------------------------ */

#define SELFTEST_WORDS  4096
#define SELFTEST_SEED   0x5EED1234u

static int role_selftest(int *vectors, char *msg, size_t len)
{
    int r = need_psram(msg, len);
    if (r) {
        return r;
    }
    /* Bodziec selftestu w osobnym buforze: wgrany bodziec zostaje.
     * Nagranie biegu jest kasowane (kontrakt). */
    uint32_t *w = heap_caps_malloc(SELFTEST_WORDS * sizeof(uint32_t), MALLOC_CAP_SPIRAM);
    if (!w) {
        snprintf(msg, len, "selftest: brak pamieci");
        return HIL_ERR_PERIPH;
    }
    hil_stim_t st = { w, SELFTEST_WORDS, SELFTEST_WORDS, 0 };
    uint32_t x = SELFTEST_SEED;
    for (uint32_t i = 0; i < SELFTEST_WORDS; i++) {
        x = hil_xorshift32(x);
        w[i] = x;
        st.sum += x;
    }
    const pins_t lp = { CONFIG_HIL_PIN_LOOP_SCK, CONFIG_HIL_PIN_LOOP_WS, CONFIG_HIL_PIN_LOOP_A, CONFIG_HIL_PIN_LOOP_A };
    s_record = true;
    r = chan_open(true, 16000, &lp, true, msg, len);
    if (!r) {
        r = run_begin(&st, &s_rec, msg, len);
    }
    if (!r) {
        /* 4096 ramek przy 16 kHz to 256 ms; z zapasem na rozbieg DMA */
        TickType_t t0 = xTaskGetTickCount();
        while (s_rec.seen < SELFTEST_WORDS + 2 * DMA_FRAMES && xTaskGetTickCount() - t0 < pdMS_TO_TICKS(2000)) {
            vTaskDelay(pdMS_TO_TICKS(10));
        }
    }
    run_end();
    pins_hiz(&lp);
    if (!r) {
        uint32_t at = 0;
        r = hil_rec_find_stim(&s_rec, &st, &at, msg, len);
        if (!r) {
            hil_cmd_log("petla 32/32 fs=16000: %d slow od ramki %" PRIu32 ", nagranych %" PRIu32 ", overflow %" PRIu32 " ok",
                        SELFTEST_WORDS, at, s_rec.n, s_overflow);
        }
        if (!r && s_overflow) {
            snprintf(msg, len, "petla: overflow DMA RX %" PRIu32, s_overflow);
            r = HIL_ERR_SELFTEST;
        }
    }
    heap_caps_free(w);
    hil_rec_reset(&s_rec);
    s_have_run = false;
    *vectors = 0;
    return r;
}

/* ------------------------------------------------------------------ */

static const hil_role_t s_role = {
    .ip = "fe",
    .keys = s_keys,
    .nkeys = K_COUNT,
    .values = s_values,
    .validate = validate,
    .start = role_start,
    .stop = role_stop,
    .stat = role_stat,
    .dump_count = role_dump_count,
    .dump_line = role_dump_line,
    .selftest = role_selftest,
    .cmds = s_cmds,
    .ncmds = sizeof(s_cmds) / sizeof(s_cmds[0]),
};

const hil_role_t *fe_role_init(void)
{
    pins_hiz(&s_bus_pins);
    s_stim.words = heap_caps_malloc(STIM_CAP * sizeof(uint32_t), MALLOC_CAP_SPIRAM);
    s_rec.lr = heap_caps_malloc(REC_CAP * 2 * sizeof(uint32_t), MALLOC_CAP_SPIRAM);
    s_psram = s_stim.words && s_rec.lr;
    s_stim.cap = s_psram ? STIM_CAP : 0;
    s_rec.cap = s_psram ? REC_CAP : 0;
    ESP_LOGI(TAG, "piny: SCK=%d WS=%d DOUT=%d DIN=%d; PSRAM: %s (bodziec %" PRIu32 " slow, nagranie %" PRIu32 " ramek)",
             CONFIG_HIL_PIN_SCK, CONFIG_HIL_PIN_WS, CONFIG_HIL_PIN_DOUT, CONFIG_HIL_PIN_DIN,
             s_psram ? "tak" : "NIE", s_stim.cap, s_rec.cap);
    return &s_role;
}
