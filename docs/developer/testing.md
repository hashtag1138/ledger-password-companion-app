# Builds and Tests

## Prerequisites

- JDK 17
- Android SDK installed
- `adb`
- Docker or local `speculos` for emulation tests
- an Android AVD if you want instrumented emulator tests

The repository Gradle wrapper must be used:

```bash
./gradlew
```

## Recommended Baseline

The baseline to rerun after a significant change is:

```bash
./gradlew :core:test :ledger-protocol:test :cli:test :android-app:testDebugUnitTest :android-app:assembleDebug :android-app:assembleDebugAndroidTest :android-app:lintDebug
```

## Targeted Tests by Module

### `core`

```bash
./gradlew :core:test
```

### `ledger-protocol`

```bash
./gradlew :ledger-protocol:test
```

### `cli`

```bash
./gradlew :cli:test :cli:installDist
```

Local CLI smoke test:

```bash
./cli/build/install/ledger-pw/bin/ledger-pw help
./cli/build/install/ledger-pw/bin/ledger-pw file validate test-fixtures/backup-example.json
```

### `android-app`

Android unit tests:

```bash
./gradlew :android-app:testDebugUnitTest
```

Instrumentation build:

```bash
./gradlew :android-app:assembleDebug :android-app:assembleDebugAndroidTest
```

Lint:

```bash
./gradlew :android-app:lintDebug
```

## Android Testing on an Emulator with Speculos

### Build the Ledger Passwords App

Default test build:

```bash
scripts/build-passwords-app.sh
```

To avoid the demo state injected by `POPULATE=1`:

```bash
scripts/build-passwords-app.sh --no-populate
```

### Start Speculos

```bash
scripts/run-speculos-passwords.sh build/speculos/app-passwords/bin/app.elf
```

### Run the CLI Smoke Test

```bash
scripts/speculos-smoke.sh --auto-approve --with-push
```

### Run the Android E2E Test

```bash
scripts/android-emulator-speculos-test.sh
```

This script:

- detects a connected `emulator-*`;
- starts Speculos auto-approval;
- clears app data;
- runs `connectedDebugAndroidTest`.

## Manual Test on an Emulator

Install and launch the app:

```bash
adb -s emulator-5554 install -r android-app/build/outputs/apk/debug/android-app-debug.apk
adb -s emulator-5554 shell am start -n com.ledgerpasswords.companion/com.ledgerpasswords.companion.android.MainActivity
```

Then:

1. open `Sync`;
2. choose `Speculos`;
3. keep `10.0.2.2` and `10100` if you use the default configuration;
4. test `Import`, `Compare`, `Export`, `Verify`.

## Manual Test on a Real Ledger

Real hardware should be treated as a smoke test, not as a stress bench.

Recommended order:

1. `Compare`
2. `Import from Ledger`
3. `Verify`
4. only then `Export to Ledger` if the diff is understood

Reminders:

- the `Passwords` app must be open on the Ledger;
- real `push` requires confirmation;
- the companion does not perform automatic readback after writing;
- `Debug` contains the dangerous override and must not be confused with the normal flow.

## Where to Look When It Breaks

### Persistent Android Logs

```bash
adb shell run-as com.ledgerpasswords.companion cat files/ledger-debug.log
```

### Targeted Android Logcat

```bash
adb logcat -d LedgerPwUsb:V LedgerPwUi:V *:S
```

### JSON Output from Fuzzers

Most scripts write a usable `--json-out` file for reviewing cases and oracles.
