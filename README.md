# Ledger Passwords Companion

![Ledger Passwords Companion home screen](docs/assets/home-screen-v0.2.0.jpg)

## Disclaimer

This companion targets the original Ledger Passwords app from [`LedgerHQ/app-passwords`](https://github.com/LedgerHQ/app-passwords), but real-device testing uncovered known app-side bugs in upstream `1.3.1`, including:

- wrong index handling in `Passwords list` on some second-entry flows;
- broken `AZERTY` typing for characters that require `AltGr`;
- misleading glyph rendering in `Show password` for some generated characters.

The upstream app repository is:

- https://github.com/LedgerHQ/app-passwords

The patched builds, bug analysis, and patch files maintained for those issues are here:

- https://github.com/hashtag1138/ledger-passwords-show-second-repro

Repository scaffold for an Android companion app for the Ledger password manager [`LedgerHQ/app-passwords`](https://github.com/LedgerHQ/app-passwords).

The goal of the project is to provide a simple UI to manage the **identifiers/nicknames** used by the Ledger Passwords app, without turning the phone into a password manager. The companion app must not know the recovery phrase and must not generate or display final passwords. It edits the metadata list, supports `backup.json` import/export, and provides a guided synchronization flow with the Ledger.

## Functional Goal

- List the identifiers used by Ledger Passwords.
- Add, delete, rename, or change the character policy of an identifier.
- Import from a JSON backup compatible with the Ledger web tool.
- Export to a JSON backup.
- Synchronize local state with a connected Ledger through read, merge, write, and verify steps.
- Keep the Ledger fully usable on its own, as intended by the official app.

## Repository Architecture

```text
ledger-passwords-companion/
  core/                    # pure business logic, no Android and no USB
  ledger-protocol/         # Ledger Passwords metadata codec + APDU client
  cli/                     # PC CLI for offline/online tests
  android-app/             # Jetpack Compose Android app
  docs/                    # plan, protocol, security, sync, UI
  test-fixtures/           # reference backups and dumps
```

## Current State

The repository is no longer just a scaffold. It now contains:

- Gradle modules with the wrapper included;
- domain models, validation, and diff logic;
- the Ledger metadata codec and the `backup.json` codec;
- the APDU client with a fake transport;
- shared Ledger HID framing;
- a Speculos TCP transport;
- a PC USB HID transport;
- an offline and device CLI (`info`, `pull`, `diff`, `push`, `verify`);
- Speculos launch and smoke-test scripts;
- a Compose Android app with persistent local storage, local editing, `backup.json` import/export, a sync shadow, a guided `Synchronize` flow, real USB support, Speculos support, and a custom launcher icon;
- implementation-plan and technical-choice documentation.

Android note:

- the default launcher icon was replaced with a custom asset derived from [android-app/src/main/assets/app_icon_source.jpg](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/assets/app_icon_source.jpg:1);
- the main files added or updated are `AndroidManifest.xml`, `mipmap-*/ic_launcher*.png`, `mipmap-anydpi-v26/ic_launcher*.xml`, and `drawable/ic_launcher_background.xml`.

What is still mainly missing on the product side:

- the full analysis of the reset incident observed on a real Ledger after some `push` operations followed by device-side usage;
- broader real-hardware soak coverage after repeated write and device-side use cycles;
- more end-to-end validation around delete and update convergence across sync retries;
- additional polish around conflict-heavy scenarios and recovery after a stale sync shadow.

## Getting Started

Recommended prerequisites:

- JDK 17;
- the included Gradle wrapper;
- a recent Android Studio;
- the Android SDK matching the `compileSdk` declared in `gradle/libs.versions.toml`;
- a Ledger with the **Passwords** app open for device tests.

Build the JVM modules:

```bash
./gradlew :core:test :ledger-protocol:test :cli:installDist
```

Run the CLI offline after `installDist`:

```bash
./cli/build/install/ledger-pw/bin/ledger-pw help
./cli/build/install/ledger-pw/bin/ledger-pw file list test-fixtures/backup-example.json
./cli/build/install/ledger-pw/bin/ledger-pw file validate test-fixtures/backup-example.json
```

Test the Passwords app without a real Ledger via Speculos:

```bash
scripts/build-passwords-app.sh
scripts/run-speculos-passwords.sh build/speculos/app-passwords/bin/app.elf
scripts/speculos-smoke.sh --api-port 5000 --first-run --auto-approve
```

The write path must go through Speculos before any new write attempt on a real device. See [`docs/speculos-testing.md`](docs/speculos-testing.md).

At the moment, the companion blocks real writes if the detected Passwords app is older than `1.3.2`. Read-only target access remains allowed, including `pull`/`dump` against `1.3.1`.

Open the Android app:

```bash
./gradlew :android-app:assembleDebug
```

Test the Android app on an emulator with Speculos:

```bash
scripts/android-emulator-speculos-test.sh --first-run
```

This script:

- starts Speculos auto-approval on the host side;
- clears app data on the emulator;
- runs Android `connectedDebugAndroidTest` against `10.0.2.2:10100`.

For a manual test, install and launch the app:

```bash
adb -s emulator-5554 install -r android-app/build/outputs/apk/debug/android-app-debug.apk
adb -s emulator-5554 shell am start -n com.ledgerpasswords.companion/com.ledgerpasswords.companion.android.MainActivity
```

Then:

- open `Debug`;
- choose `Speculos`;
- keep `10.0.2.2` as the host inside the Android emulator;
- set the Speculos APDU port, `10100` by default in this repo;
- use `Refresh target` if needed, then return to `Sync`;
- use `Synchronize local and target` and follow the guided read/write/verify dialogs.

The Android emulator does not validate real USB OTG. It is used to test the full Android flow against Speculos without touching a real Ledger.

## Ledger Passwords Invariants

The project encodes these constraints from the start:

- default metadata storage: `4096` bytes;
- usable nickname length: `19` UTF-8 bytes max;
- entry format: `[length][kind][charsets][nickname bytes...]`;
- `kind = 0x00` active, `kind = 0xFF` erased;
- charset `0x00` or `0xFF` = all sets;
- there is no add/delete/update APDU on the Ledger side: data is changed locally and then the full metadata block is rewritten.

## Documentation

### User

- [`docs/user/README.md`](docs/user/README.md): user entry point.
- [`docs/user/android-app.md`](docs/user/android-app.md): install and use the Android app.
- [`docs/user/safety-and-limits.md`](docs/user/safety-and-limits.md): safety, limits, and guardrails.

### Developer

- [`docs/developer/README.md`](docs/developer/README.md): developer entry point.
- [`docs/developer/architecture.md`](docs/developer/architecture.md): repo design and suggested code reading order.
- [`docs/developer/testing.md`](docs/developer/testing.md): reproduce builds, tests, and smoke tests.
- [`docs/developer/fuzzing.md`](docs/developer/fuzzing.md): fuzzing harness and Speculos campaigns.

### Technical References

- [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md): detailed phase-by-phase plan.
- [`TECHNICAL_CHOICES.md`](TECHNICAL_CHOICES.md): technical choices, dependencies, and architecture.
- [`docs/speculos-testing.md`](docs/speculos-testing.md): detailed Speculos usage guide.
- [`docs/ledger-passwords-protocol.md`](docs/ledger-passwords-protocol.md): metadata format and APDU protocol.
- [`docs/security.md`](docs/security.md): security, guardrails, and threats.
- [`docs/sync-flows.md`](docs/sync-flows.md): pull/push/merge flows.
- [`docs/sync-merge-roadmap.md`](docs/sync-merge-roadmap.md): current merge model and remaining sync work.
- [`docs/ui-wireframes.md`](docs/ui-wireframes.md): wireframes and UI structure.
- [`docs/fuzzing-findings-report.md`](docs/fuzzing-findings-report.md): fuzzing findings summary.
- [`docs/companion-mitigation-plan.md`](docs/companion-mitigation-plan.md): companion-side mitigation plan.
- [`CODEX_PROMPT.md`](CODEX_PROMPT.md): prompt ready to give to Codex to continue implementation.

## License

Apache-2.0, aligned with the original Ledger Passwords repository.
