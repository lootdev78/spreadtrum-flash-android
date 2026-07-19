cmake_minimum_required(VERSION 3.22.1)
project(spdflash_android LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(VENDOR_DIR "${CMAKE_CURRENT_SOURCE_DIR}/vendor")
set(UPSTREAM_DIR "${VENDOR_DIR}/spreadtrum_flash")
set(LIBUSB_DIR "${VENDOR_DIR}/libusb")
set(GENERATED_DIR "${CMAKE_CURRENT_SOURCE_DIR}/generated")

foreach(required_file
    "${UPSTREAM_DIR}/common.c"
    "${UPSTREAM_DIR}/common.h"
    "${UPSTREAM_DIR}/spd_cmd.h"
    "${GENERATED_DIR}/spd_dump_android.c"
    "${GENERATED_DIR}/GITVER.h"
    "${LIBUSB_DIR}/libusb/core.c"
    "${LIBUSB_DIR}/android/config.h")
    if(NOT EXISTS "${required_file}")
        message(FATAL_ERROR
            "Native vendor file is missing: ${required_file}. "
            "Run './gradlew :app:vendorNativeSources' first or sync the project again.")
    endif()
endforeach()

add_library(libusb_android STATIC
    "${LIBUSB_DIR}/libusb/core.c"
    "${LIBUSB_DIR}/libusb/descriptor.c"
    "${LIBUSB_DIR}/libusb/hotplug.c"
    "${LIBUSB_DIR}/libusb/io.c"
    "${LIBUSB_DIR}/libusb/sync.c"
    "${LIBUSB_DIR}/libusb/strerror.c"
    "${LIBUSB_DIR}/libusb/os/linux_usbfs.c"
    "${LIBUSB_DIR}/libusb/os/events_posix.c"
    "${LIBUSB_DIR}/libusb/os/threads_posix.c"
    "${LIBUSB_DIR}/libusb/os/linux_netlink.c"
)
target_include_directories(libusb_android PUBLIC
    "${LIBUSB_DIR}/android"
    "${LIBUSB_DIR}/libusb"
    "${LIBUSB_DIR}/libusb/os"
)
target_compile_definitions(libusb_android PRIVATE HAVE_CONFIG_H=1)
target_compile_options(libusb_android PRIVATE -fvisibility=hidden -pthread -Wno-unused-parameter)
target_link_libraries(libusb_android PUBLIC log)

add_library(spdflash_android SHARED
    native_bridge.c
    "${UPSTREAM_DIR}/common.c"
    "${GENERATED_DIR}/spd_dump_android.c"
)

# common.c defines the original helpers under private names. spd_dump_android.c
# calls Android wrappers so abnormal exits can still release libusb and memory.
set_source_files_properties("${UPSTREAM_DIR}/common.c" PROPERTIES
    COMPILE_DEFINITIONS
        "exit=spd_android_exit;scanf=spd_android_scanf;getchar=spd_android_getchar;fopen=spd_android_fopen;libusb_bulk_transfer=spd_android_bulk_transfer;spdio_init=spd_upstream_spdio_init;spdio_free=spd_upstream_spdio_free")
set_source_files_properties("${GENERATED_DIR}/spd_dump_android.c" PROPERTIES
    COMPILE_DEFINITIONS
        "main=spd_dump_main;exit=spd_android_exit;scanf=spd_android_scanf;getchar=spd_android_getchar;fopen=spd_android_fopen;libusb_bulk_transfer=spd_android_bulk_transfer;spdio_init=spd_android_spdio_init;spdio_free=spd_android_spdio_free")

target_include_directories(spdflash_android PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
    "${UPSTREAM_DIR}"
    "${GENERATED_DIR}"
    "${LIBUSB_DIR}/libusb"
    "${LIBUSB_DIR}/libusb/os"
)
target_compile_definitions(spdflash_android PRIVATE
    USE_LIBUSB=1
    LIBUSB_DETACH=0
)
target_compile_options(spdflash_android PRIVATE
    -include "${CMAKE_CURRENT_SOURCE_DIR}/android_hooks.h"
    -Wall
    -Wextra
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-sign-compare
    -Wno-format
    -Uunix
    -pthread
)
target_link_libraries(spdflash_android PRIVATE
    libusb_android
    android
    log
    m
)
