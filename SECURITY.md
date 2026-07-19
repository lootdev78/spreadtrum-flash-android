# Security policy

## Reporting a vulnerability

Do not publish exploit details, private device data, proprietary loader files, or credentials in a public issue. Report vulnerabilities privately to the repository owner through GitHub's private vulnerability reporting feature when enabled.

A useful report includes the affected app version, Android version, device chipset family, operation sequence, expected behavior, observed behavior, and a minimal reproduction that does not contain copyrighted firmware.

## Scope

Security-sensitive areas include:

- selecting and reconnecting the correct USB target
- permission and file-descriptor ownership
- SAF path and filename handling
- native cancellation and cleanup
- command parsing and destructive-operation confirmation
- loader and image integrity metadata
- CI, signing, and release artifact provenance

## Hardware warning

This project interacts with boot ROM and flash programming protocols. A software defect or an incorrect loader can destroy data or leave hardware unbootable. Security testing must use hardware you are authorized to modify and should begin on expendable devices.
