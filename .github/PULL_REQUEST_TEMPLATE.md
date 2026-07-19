## Summary

Describe the change and why it is needed.

## Safety impact

Explain any effect on USB target selection, loader execution, partition writes, erase operations, repartitioning, cancellation, or file-descriptor ownership.

## Validation

- [ ] `python3 tools/verify_project.py`
- [ ] `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
- [ ] Hardware testing is described below, or this change does not require hardware testing.

## Hardware testing

List the Android host, target chipset family, storage type, VID/PID transitions, FDL source, and operations tested. Do not attach proprietary firmware or loader binaries.
