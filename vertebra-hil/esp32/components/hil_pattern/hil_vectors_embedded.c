/*
 * Wektory z contract/i2s/vectors/ wbudowane przez EMBED_TXTFILES
 * (CMakeLists.txt). Symbole _binary_<plik>_start/_end tworzy ESP-IDF;
 * EMBED_TXTFILES dopisuje '\0', ktory czytnik CSV pomija.
 */
#include "hil_vectors.h"

extern const char pattern_start[]        asm("_binary_pattern_csv_start");
extern const char pattern_end[]          asm("_binary_pattern_csv_end");
extern const char transfer_start[]       asm("_binary_transfer_csv_start");
extern const char transfer_end[]         asm("_binary_transfer_csv_end");
extern const char checker_cases_start[]  asm("_binary_checker_cases_csv_start");
extern const char checker_cases_end[]    asm("_binary_checker_cases_csv_end");
extern const char checker_frames_start[] asm("_binary_checker_frames_csv_start");
extern const char checker_frames_end[]   asm("_binary_checker_frames_csv_end");

#define CSV(name) { name##_start, (size_t)(name##_end - name##_start) }

hil_vector_set_t hil_vectors_embedded(void)
{
    hil_vector_set_t v = {
        CSV(pattern), CSV(transfer), CSV(checker_cases), CSV(checker_frames),
    };
    return v;
}
