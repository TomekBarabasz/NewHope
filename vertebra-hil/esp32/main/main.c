/*
 * vertebra-hil (vertebra-hil.md §7, §10, §12): firmware partnera ESP32-S3.
 *
 * Protokol tekstowy z contract/commands.md na natywnym USB S3
 * (USB-Serial-JTAG, hil_cmd). Rola IP wybrana przy budowaniu
 * (menuconfig "vertebra-hil" -> "IP"): I2S w i2s_role.c, frontend N0 + N1
 * w fe_role.c (sdkconfig.fe). Logi ESP-IDF ida na UART0 (mostek CH343),
 * zeby nie mieszaly sie z odpowiedziami.
 */
#include "hil_cmd.h"
#include "sdkconfig.h"

#if CONFIG_HIL_IP_FE
#include "fe_role.h"
#define ROLE_INIT fe_role_init
#else
#include "i2s_role.h"
#define ROLE_INIT i2s_role_init
#endif

void app_main(void)
{
    hil_cmd_start(ROLE_INIT());
}
