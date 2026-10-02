# Strumien probek (contract/fe/commands.md): bodziec, nagranie, hex.
# Bez ESP-IDF; testy na PC w esp32/test (test_hil_stream.c).
idf_component_register(
    SRCS "hil_stream.c"
    INCLUDE_DIRS "include"
    REQUIRES hil_cmd
)
