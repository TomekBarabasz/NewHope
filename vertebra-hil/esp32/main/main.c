/*
 * vertebra-hil, etap 3 (vertebra-hil.md §7, §10): firmware partnera I2S.
 *
 * Protokol tekstowy z contract/commands.md na natywnym USB S3
 * (USB-Serial-JTAG, hil_cmd); rola I2S w i2s_role.c. Logi ESP-IDF ida na
 * UART0 (mostek CH343), zeby nie mieszaly sie z odpowiedziami.
 */
#include "hil_cmd.h"
#include "i2s_role.h"

void app_main(void)
{
    hil_cmd_start(i2s_role_init());
}
