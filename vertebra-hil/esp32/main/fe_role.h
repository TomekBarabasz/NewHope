/*
 * vertebra-hil: rola frontendu N0 + N1 partnera ESP32-S3 (contract/fe/commands.md).
 */
#pragma once

#include "hil_cmd.h"

/* Piny magistrali w wysokiej impedancji, bufory bodzca i nagrania
 * w PSRAM; zwraca role dla hil_cmd. */
const hil_role_t *fe_role_init(void);
