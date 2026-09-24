/*
 * vertebra-hil: wektory kontraktu I2S (contract/i2s/vectors/, pliki CSV).
 *
 * Pliki sa przekazywane jako bufory: w firmware to EMBED_TXTFILES
 * (hil_vectors_embedded), w tescie na PC pliki czytane z dysku. Kolumny
 * i format opisuje contract/i2s/pattern.md, "Wektory".
 */
#pragma once

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    const char *data;
    size_t len;
} hil_csv_t;

typedef struct {
    hil_csv_t pattern;          /* seed,n,c,w,word */
    hil_csv_t transfer;         /* word,wtx,slot,wrx,result */
    hil_csv_t checker_cases;    /* case,seed,wtx,slot,wrx,nframes,frames,... */
    hil_csv_t checker_frames;   /* case,idx,l,r */
} hil_vector_set_t;

/*
 * Sprawdza wszystkie wektory. Zwraca liczbe sprawdzonych wektorow (wiersze
 * pattern i transfer plus scenariusze checkera) albo -1; wtedy `err`
 * opisuje pierwszy niezgodny wektor (plik, wiersz, got, exp).
 */
int hil_vectors_run(const hil_vector_set_t *v, char *err, size_t err_len);

/* Wektory wbudowane w firmware (tylko ESP-IDF, hil_vectors_embedded.c). */
hil_vector_set_t hil_vectors_embedded(void);

#ifdef __cplusplus
}
#endif
