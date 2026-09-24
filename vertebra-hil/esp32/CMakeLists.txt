# vertebra-hil: firmware partnera ESP32-S3 (vertebra-hil.md §7).
#   cd vertebra-hil/esp32 && idf.py build flash
# ESP-IDF 5.2.x; cel esp32s3 z sdkconfig.defaults (tu, obok projektu).
cmake_minimum_required(VERSION 3.16)

# `ver` zwraca build=<hash>: 8 znakow hasha gita, "-dirty" przy zmianach
# w drzewie (jak rejestr build FPGA, HilBuildInfo). Liczone przy
# konfiguracji CMake; po commicie `idf.py reconfigure` albo fullclean.
execute_process(COMMAND git rev-parse --short=8 HEAD
                WORKING_DIRECTORY ${CMAKE_CURRENT_LIST_DIR}
                OUTPUT_VARIABLE HIL_GIT_HASH OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
execute_process(COMMAND git status --porcelain
                WORKING_DIRECTORY ${CMAKE_CURRENT_LIST_DIR}
                OUTPUT_VARIABLE HIL_GIT_DIRTY OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
if(NOT HIL_GIT_HASH)
    set(HIL_GIT_HASH "00000000")
endif()
if(HIL_GIT_DIRTY)
    set(PROJECT_VER "${HIL_GIT_HASH}-dirty")
else()
    set(PROJECT_VER "${HIL_GIT_HASH}")
endif()

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(vertebra_hil)
