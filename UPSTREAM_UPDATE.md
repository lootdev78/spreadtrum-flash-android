# Upstream update workflow

The complete updater is implemented in one Gradle script:

```text
gradle/upstream-update.gradle.kts
```

## Update and build

```bash
./gradlew updateUpstreamAndBuild
```

This task:

1. resolves the newest commit of `TomKing062/spreadtrum_flash`
2. resolves the latest stable release of `libusb/libusb`
3. downloads both complete GitHub source archives
4. verifies the expected Android and C source files
5. calculates archive and source-tree SHA-256 values
6. replaces vendor directories only after every validation succeeds
7. writes the resolved revisions and hashes to `upstream.lock.json`
8. verifies the Android patch points and builds the debug APK

## Other tasks

```bash
./gradlew checkUpstreamUpdates
./gradlew updateUpstream
./gradlew assembleDebug
```

`assembleDebug` uses only the locked local sources and does not contact GitHub.

## Select explicit revisions

```bash
./gradlew updateUpstreamAndBuild \
  -PspreadtrumRef=<branch-tag-or-commit> \
  -PlibusbRef=<branch-tag-or-commit>
```

Example:

```bash
./gradlew updateUpstreamAndBuild \
  -PspreadtrumRef=d24c21a0c93a545c0130668caa1826d59ceebe48 \
  -PlibusbRef=v1.0.29
```

## GitHub token

Anonymous GitHub API requests are rate-limited. Optionally set a token:

```bash
export GITHUB_TOKEN=github_pat_...
./gradlew updateUpstream
```

PowerShell:

```powershell
$env:GITHUB_TOKEN = "github_pat_..."
.\gradlew.bat updateUpstream
```

## Safety behavior

Updates are deliberately not run during every build. A new upstream revision can rename functions, change global state, or invalidate patch locations. If a required file, expected source hash, or Android patch point cannot be verified, the updater stops instead of producing an unreviewed binary.
