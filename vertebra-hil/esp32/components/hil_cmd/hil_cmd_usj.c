/*
 * vertebra-hil: transport protokolu tekstowego po USB-Serial-JTAG S3.
 *
 * Sprzetowy CDC-ACM na natywnym USB, bez TinyUSB; przez ten sam port
 * dziala `idf.py flash`. Konsola IDF musi byc na UART0
 * (sdkconfig.defaults), inaczej logi mieszaja sie z odpowiedziami.
 * Log firmware'u do hosta idzie jako linie "# ..." (hil_cmd_log).
 */
#include "hil_cmd.h"

#include <string.h>

#include "driver/usb_serial_jtag.h"
#include "esp_app_desc.h"
#include "esp_err.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

static const char *TAG = "hil_cmd";

#define USJ_BUF_SIZE    2048
#define WRITE_TIMEOUT   pdMS_TO_TICKS(500)   /* host nie czyta: linia przepada, task zyje */

static SemaphoreHandle_t s_wr_lock;

static void usj_write(const char *s, size_t n)
{
    xSemaphoreTake(s_wr_lock, portMAX_DELAY);
    size_t off = 0;
    while (off < n) {
        int w = usb_serial_jtag_write_bytes(s + off, n - off, WRITE_TIMEOUT);
        if (w <= 0) {
            break;
        }
        off += (size_t)w;
    }
    xSemaphoreGive(s_wr_lock);
}

static void cmd_task(void *arg)
{
    (void)arg;
    uint8_t buf[64];
    for (;;) {
        int n = usb_serial_jtag_read_bytes(buf, sizeof(buf), portMAX_DELAY);
        if (n > 0) {
            hil_cmd_feed((const char *)buf, (size_t)n);
        }
    }
}

void hil_cmd_start(const hil_role_t *role)
{
    usb_serial_jtag_driver_config_t cfg = USB_SERIAL_JTAG_DRIVER_CONFIG_DEFAULT();
    cfg.rx_buffer_size = USJ_BUF_SIZE;
    cfg.tx_buffer_size = USJ_BUF_SIZE;
    ESP_ERROR_CHECK(usb_serial_jtag_driver_install(&cfg));
    s_wr_lock = xSemaphoreCreateMutex();

    const esp_app_desc_t *app = esp_app_get_description();
    hil_cmd_init(role, app->version, usj_write);
    ESP_LOGI(TAG, "ip=%s build=%s, protokol na USB-Serial-JTAG", role->ip, app->version);

    /* Rdzen APP: rdzen PRO zostaje dla taskow I2S (i2s_role.c). */
    xTaskCreatePinnedToCore(cmd_task, "hil_cmd", 6144, NULL, 5, NULL, 1);
}
