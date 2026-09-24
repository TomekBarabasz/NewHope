# Wektory kontraktu I2S wbudowane w firmware (selftest). Sciezka do
# contract/ jest wzgledna, wiec projekt ESP32 musi byc budowany z repo.
set(HIL_VECTORS ${COMPONENT_DIR}/../../../contract/i2s/vectors)

idf_component_register(
    SRCS "hil_pattern.c" "hil_vectors.c" "hil_vectors_embedded.c"
    INCLUDE_DIRS "include"
    EMBED_TXTFILES
        "${HIL_VECTORS}/pattern.csv"
        "${HIL_VECTORS}/transfer.csv"
        "${HIL_VECTORS}/checker_cases.csv"
        "${HIL_VECTORS}/checker_frames.csv"
)
