# Porting notes

## USB data path

```text
Android UsbManager
  -> UsbDeviceConnection.getFileDescriptor()
  -> dup(fd) in JNI
  -> libusb_wrap_sys_device()
  -> original spreadtrum_flash protocol implementation
```

Android remains responsible for USB permission and device ownership. JNI duplicates the Android file descriptor so the Java/Kotlin connection and the native libusb handle have explicit lifetimes.

## Native embedding

The upstream program is command-line oriented and reports fatal errors through `exit()`. The Android embedding layer therefore:

- renames upstream `main` to `spd_dump_main`
- redirects `exit` to a thread-local `setjmp`/`longjmp` boundary
- redirects stdout/stderr into a pipe streamed to Kotlin
- supplies CLI confirmation input through the Android safety gate
- generates a `quit`/`exit` command for clean queue completion
- slices libusb bulk transfers to provide controlled cancellation and progress callbacks
- guarantees JNI-side cleanup after normal completion, cancellation, and upstream fatal errors

Only one native operation may execute at a time because the upstream implementation uses process-global state.

## Android document access

Input files selected through the Storage Access Framework are represented by persisted `content://` references. Seekable providers are opened as file descriptors and exposed to the native C core through the JNI file bridge. Non-seekable providers are copied into private app storage as a compatibility fallback.

Read commands can write directly into a selected SAF tree. The native `fopen` bridge creates or opens the requested document and returns a POSIX stream backed by the Android provider file descriptor. This avoids a second full-size workspace copy for large dumps.

## Re-enumeration

Desktop libusb can discover hotplugged devices directly. Android applications must obtain permission and open each device instance through `UsbManager`. Kick and loader transitions therefore use an Android-side state machine:

1. send the requested mode transition
2. close the old descriptor cleanly
3. observe detach/attach broadcasts
4. identify the expected replacement device
5. request permission when required
6. open a new descriptor and continue the pipeline

Automatic reconnect is stopped when multiple plausible Spreadtrum/Unisoc devices are attached, preventing the pipeline from continuing on the wrong target.

## Cancellation model

Cancellation is cooperative. Kotlin requests cancellation, the foreground service updates its state, JNI marks the native session cancelled, and libusb transfers return at bounded slices. The system avoids killing the Android process or blindly closing descriptors from unrelated threads. A partially completed write is still possible when cancellation occurs during a destructive operation.
