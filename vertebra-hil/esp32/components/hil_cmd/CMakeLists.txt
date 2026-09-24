idf_component_register(
    SRCS "hil_cmd_core.c" "hil_cmd_usj.c"
    INCLUDE_DIRS "include"
    PRIV_REQUIRES driver esp_app_format
)
