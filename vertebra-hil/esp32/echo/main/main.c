/*
 * vertebra-hil, etap 0 (vertebra-hil.md §10): echo po natywnym USB S3.
 *
 * Kazdy bajt odebrany z USB-Serial-JTAG wraca bez zmian. EchoProbe na PC
 * wysyla porcje po 64 B i porownuje odpowiedz. W etapie 3 ten plik
 * zastapi hil_cmd (parser linii) i main/i2s_role.c; transport zostaje.
 *
 * Dlaczego USB-Serial-JTAG, a nie TinyUSB CDC: to sprzetowy CDC-ACM S3,
 * bez dodatkowego komponentu, i przez ten sam port dziala `idf.py flash`
 * z automatycznym resetem. Konsola jest przeniesiona na UART0
 * (sdkconfig.defaults), zeby logi nie trafialy do strumienia.
 */
#include <stdint.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/usb_serial_jtag.h"
#include "esp_err.h"
#include "esp_log.h"

static const char *TAG = "hil_echo";

#define USJ_BUF_SIZE 1024   /* >= 64 B porcji EchoProbe z zapasem */

void app_main(void)
{
    usb_serial_jtag_driver_config_t cfg = USB_SERIAL_JTAG_DRIVER_CONFIG_DEFAULT();
    cfg.rx_buffer_size = USJ_BUF_SIZE;
    cfg.tx_buffer_size = USJ_BUF_SIZE;
    ESP_ERROR_CHECK(usb_serial_jtag_driver_install(&cfg));

    ESP_LOGI(TAG, "echo USB-Serial-JTAG gotowe (log na UART0)");

    static uint8_t buf[256];
    for (;;) {
        int n = usb_serial_jtag_read_bytes(buf, sizeof(buf), portMAX_DELAY);
        int off = 0;
        while (off < n) {
            int w = usb_serial_jtag_write_bytes(buf + off, (size_t)(n - off), portMAX_DELAY);
            if (w > 0) {
                off += w;
            }
        }
    }
}
