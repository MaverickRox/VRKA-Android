# Build & Test Guide

## Toolchain Requirements

- **JDK:** JDK 17+ (Android Studio JBR recommended)
- **Compile SDK:** Android API 37, minor level 2 (`compileSdk = 37`, `minorApiLevel = 2` required by GeckoView 159 metadata)
- **Target / Min SDK:** `targetSdk = 36` / `minSdk = 26`
- **Target ABI:** `arm64-v8a` only
- **Android Build-Tools:** 36.0.0+ (`apksigner`, `zipalign`, `aapt2`)
- **Gradle Verification:** Committed `app/gradle.lockfile` + `gradle/verification-metadata.xml` (`--dependency-verification=strict`)

## Environment Setup

### Windows (PowerShell)
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle"
```

### Linux / macOS (Bash)
```bash
export JAVA_HOME=/path/to/jbr
export ANDROID_HOME=~/Android/Sdk
```

## Full Local Verification (Matches CI Validation Gate)

Always keep `--dependency-verification=strict` enabled when running verification builds:

### Windows (PowerShell)
```powershell
.\gradlew.bat --no-daemon --no-configuration-cache --dependency-verification=strict `
  testDebugUnitTest lintDebug lintRelease compileDebugAndroidTestKotlin compileReleaseKotlin `
  assembleDebug assembleDebugAndroidTest assembleDevRelease
```

### Linux / macOS (Bash)
```bash
./gradlew --no-daemon --no-configuration-cache --dependency-verification=strict \
  testDebugUnitTest lintDebug lintRelease compileDebugAndroidTestKotlin compileReleaseKotlin \
  assembleDebug assembleDebugAndroidTest assembleDevRelease
```

Confirm lockfile hygiene afterward:
```bash
git diff --exit-code -- app/gradle.lockfile
git diff --check
```

## Build Types & Signing Isolation

| Build Type | Minified (R8) | Signing Config | Credentials Needed | Output Path |
|---|---|---|---|---|
| `debug` | No | Android debug key | No | `app/build/outputs/apk/debug/app-debug.apk` |
| `devRelease` | Yes (`minify + shrinkResources`) | Debug keystore | No | `app/build/outputs/apk/devRelease/` + `mapping.txt` |
| `release` | Yes (`minify + shrinkResources`) | Production `.p12` keystore | Explicit credentials required | `app/build/outputs/apk/release/app-release.apk` |

Production signing properties (`signing.properties`) are **never** committed to Git and **never** loaded by `debug`, `devRelease`, unit tests, or CI jobs. They are read only when an explicit production signing task (`assembleRelease`, `packageRelease`, `validateSigningRelease`, etc.) is invoked via `VRKA_SIGNING_PROPERTIES`, root `signing.properties`, or `~/.vrka-android-signing/signing.properties`.

## API 36 Emulator Provider Instrumentation Tests

With an API 36 Android emulator running (supporting `arm64-v8a` translation or native ABI):

### Windows (PowerShell)
```powershell
.\gradlew.bat --no-daemon connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.mvrk.vrka.SafPublicationIntegrationTest,com.mvrk.vrka.PublicationStorageIntegrationTest'
```

### Linux / macOS (Bash)
```bash
./gradlew --no-daemon connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mvrk.vrka.SafPublicationIntegrationTest,com.mvrk.vrka.PublicationStorageIntegrationTest
```

## Updating Locked Dependencies

See [Dependency Management](../DEPENDENCY_MANAGEMENT.md) before modifying dependency coordinates or versions in `app/build.gradle.kts` or `.github/workflows/ci.yml`.
