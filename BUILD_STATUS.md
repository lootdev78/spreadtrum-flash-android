# Build and validation status

Last updated: July 19, 2026

## Android build

The Android project has been compiled successfully in the provided environment.

- Gradle: 8.13
- Android Gradle Plugin: 8.12.2
- Kotlin: 2.1.21
- Compile/target SDK: 36
- Minimum SDK: 26
- Build Tools: 36.0.0
- Android NDK: 28.2.13676358 (r28c)
- CMake: 3.22.1
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`

Validation command:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Result: **BUILD SUCCESSFUL**

## Checks

- 15 unit tests passed; 0 failed; 0 skipped
- Android Lint completed without blocking errors
- `python3 tools/verify_project.py` passed
- APK ZIP alignment passed
- APK signature verification passed using Android APK Signature Scheme v2
- Native library packaged for every configured ABI

## Current application revision

- Application ID: `io.github.lootdev78.spdflash.debug`
- Version: `2.1.0-debug` (`versionCode 3`)
- Application name: `SPRD Flash Studio`
- UI and repository documentation: English

The debug APK is signed with the Android debug key. Public production distribution should use a securely stored release signing key and a documented release process.

## Upstream sources

- `spreadtrum_flash`: `d24c21a0c93a545c0130668caa1826d59ceebe48`
- libusb: `v1.0.29` / `15a7ebb4d426c5ce196684347d2b7cafad862626`
- exact source-tree hashes: `upstream.lock.json`
- update implementation: `gradle/upstream-update.gradle.kts`

Normal builds use only the locked local source trees. Explicit update tasks download complete GitHub source archives into a staging directory, verify required files and SHA-256 values, and replace the vendor directories only after all checks pass.

## Hardware validation still required

The software build is complete. Real flash, erase, repartition, and mode re-enumeration tests were not performed against physical Spreadtrum/Unisoc hardware in this environment. These tests require expendable hardware, correct device-specific FDL files, and verified images.
