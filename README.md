# SPRD Flash Studio for Android

[![Android CI](https://github.com/lootdev78/spreadtrum-flash-android/actions/workflows/android.yml/badge.svg)](https://github.com/lootdev78/spreadtrum-flash-android/actions/workflows/android.yml)

A native Android user interface for [`TomKing062/spreadtrum_flash`](https://github.com/TomKing062/spreadtrum_flash), built with the Android USB Host API, libusb, JNI, Kotlin, and Material Components.

> **Hardware warning**
>
> Writing, erasing, or repartitioning flash storage can permanently destroy data or make a device unbootable. Use verified FDL files and images, keep a complete backup, and test destructive operations on expendable hardware first.

## What is included

- Android USB permission, attach/detach handling, and controlled USB re-enumeration
- libusb access through the file descriptor opened by `UsbDeviceConnection`
- FDL1 and FDL2 selection through the Android Storage Access Framework
- SHA-256 metadata for selected loader and image files
- typed forms for 53 upstream commands, plus advanced raw-command input
- stage-aware validation for BROM, FDL1, FDL2, download, and diagnostic modes
- persistent, reorderable operation queue with interrupted-session recovery
- safety confirmation for destructive and unknown operations
- direct SAF output for large dumps without creating a second workspace copy
- controlled native cancellation, cleanup, live logs, progress, speed, and ETA
- foreground service and wake lock for long transfers
- phone bottom navigation and tablet navigation rail layouts
- arm64-v8a, armeabi-v7a, and x86_64 native libraries
- a single Gradle updater for the latest `spreadtrum_flash` revision and stable libusb release

The app does **not** include device-specific FDL files or firmware images.

## Architecture

```text
Android UsbManager
  -> UsbDeviceConnection.getFileDescriptor()
  -> dup(fd) in JNI
  -> libusb_wrap_sys_device()
  -> original spreadtrum_flash protocol implementation
```

The Android layer owns permission, device selection, re-enumeration, SAF documents, queue state, safety checks, and the foreground service. The original C command and protocol logic remains in the native upstream core.

## Build requirements

- Android Studio or JDK 17
- Android SDK 36
- Android Build Tools 36.0.0
- Android NDK `28.2.13676358` (r28c)
- CMake 3.22.1

Build the debug APK:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

A normal build does not download native source code. `:app:vendorNativeSources` verifies the vendored source trees against `upstream.lock.json`, applies the Android embedding patch, and generates the native entry point. This keeps regular builds reproducible and offline-capable after Gradle dependencies are cached.

## Update upstream and build

Resolve the latest `spreadtrum_flash` commit and latest stable libusb release, update the lockfile, and build:

```bash
./gradlew updateUpstreamAndBuild
```

Check for updates without replacing the local source trees:

```bash
./gradlew checkUpstreamUpdates
```

Update and lock sources without building:

```bash
./gradlew updateUpstream
```

Pin explicit revisions:

```bash
./gradlew updateUpstreamAndBuild \
  -PspreadtrumRef=d24c21a0c93a545c0130668caa1826d59ceebe48 \
  -PlibusbRef=v1.0.29
```

Set `GITHUB_TOKEN` to avoid the anonymous GitHub API rate limit when checking frequently. See [UPSTREAM_UPDATE.md](UPSTREAM_UPDATE.md) for the complete workflow.

## Usage

1. Use an Android phone or tablet with USB OTG/host support.
2. Put the target Spreadtrum/Unisoc device into its download or diagnostic mode and connect it by OTG.
3. Open **Overview**, select the USB device, and grant Android USB permission.
4. Open **Loaders**, choose compatible FDL1 and FDL2 files, and enter verified load addresses.
5. Open **Operations**, configure commands, and add them to the queue.
6. Review **Queue**, confirm destructive operations when required, and start the pipeline.
7. Keep the USB cable connected until the operation finishes.

Large read operations can write directly into a user-selected SAF directory. Input documents are opened directly through persisted `content://` file descriptors when possible; non-seekable providers are copied into private app storage as a fallback.

## Important limitations

- The APK and native libraries compile successfully, but destructive flash, erase, repartition, and USB re-enumeration paths still require validation on physical devices with correct device-specific FDL files.
- Android grants USB access per device instance. A mode change can therefore require a new permission/open cycle after re-enumeration.
- The upstream `baudrate` command is specific to the Windows SPRD driver and does not provide the same behavior through Android/libusb.
- Cancellation is coordinated between Kotlin, JNI, and sliced libusb transfers, but interrupting a write operation can still leave a partition incomplete.
- `spreadtrum_flash` is archived. Normal builds use the exact revisions in `upstream.lock.json`; updates are explicit and may require adapting the Android embedding patch.

## Project layout

```text
app/src/main/java/                 Android UI, USB coordination, queue, SAF, service
app/src/main/cpp/                  JNI bridge and CMake build
app/src/main/cpp/vendor/           vendored spreadtrum_flash and libusb sources
app/src/main/cpp/generated/        generated Android-patched upstream entry point
gradle/upstream-update.gradle.kts  one-file upstream update tool
upstream.lock.json                 resolved commits and source-tree SHA-256 values
tools/                             project verification scripts
```

## Validation

The included source revision was validated with:

```bash
python3 tools/verify_project.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

See [BUILD_STATUS.md](BUILD_STATUS.md) and [APK_BUILD_REPORT.md](APK_BUILD_REPORT.md) for the exact toolchain and results.

## Licensing

This repository contains components with different licensing and notice requirements. No single license statement overrides third-party source notices. Review [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) before redistributing source or binaries.
