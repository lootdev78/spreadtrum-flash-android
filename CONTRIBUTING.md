# Contributing

Thank you for helping improve SPRD Flash Studio for Android.

## Development setup

Use JDK 17, Android SDK 36, Build Tools 36.0.0, NDK 28.2.13676358, and CMake 3.22.1.

Before opening a pull request, run:

```bash
python3 tools/verify_project.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## Safety requirements

Changes that affect USB selection, loader execution, partition writes, erase operations, repartitioning, or cancellation must preserve the existing safety gates. Never weaken target-device checks or destructive-operation confirmation merely to simplify a workflow.

Hardware test reports should identify the chipset family, USB VID/PID transitions, storage type, Android host device, FDL source, and whether the test used expendable hardware. Do not upload proprietary firmware or FDL binaries unless redistribution is clearly permitted.

## Upstream changes

Do not replace vendored sources manually. Use:

```bash
./gradlew updateUpstream
```

Review the lockfile diff and the generated Android patch before committing. A successful compile is not enough to prove protocol compatibility.

## Pull requests

Keep changes focused, explain user-visible and safety impact, and include the validation commands that were run. New UI text and repository documentation must be written in English.
