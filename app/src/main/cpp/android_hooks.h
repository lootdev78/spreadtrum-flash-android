#pragma once
#include <stdint.h>
#include <stdio.h>
#include <libusb.h>

typedef struct spdio_forward spdio_forward_t;

FILE *spd_android_fopen(const char *path, const char *mode);
int spd_android_bulk_transfer(
    libusb_device_handle *dev_handle,
    unsigned char endpoint,
    unsigned char *data,
    int length,
    int *transferred,
    unsigned int timeout);
void spd_android_exit(int code) __attribute__((noreturn));
int spd_android_scanf(const char *format, ...);
int spd_android_getchar(void);
void spd_android_set_command(const char *command);
