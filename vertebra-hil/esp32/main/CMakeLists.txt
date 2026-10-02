idf_component_register(
    SRCS "main.c" "i2s_role.c" "fe_role.c"
    INCLUDE_DIRS "."
    REQUIRES hil_cmd hil_pattern hil_stream
    PRIV_REQUIRES driver esp_rom soc heap
)
