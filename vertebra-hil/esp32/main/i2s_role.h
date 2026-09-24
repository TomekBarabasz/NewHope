/*
 * vertebra-hil: rola I2S partnera ESP32-S3 (contract/i2s/commands.md).
 */
#pragma once

#include "hil_cmd.h"

/* Ustawia piny magistrali w wysokiej impedancji i zwraca role dla hil_cmd. */
const hil_role_t *i2s_role_init(void);
