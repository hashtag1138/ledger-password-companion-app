# Testing with Speculos

Speculos is the right environment to replay the APDU path of the Passwords app without touching a real Ledger.

This repository already has a dedicated TCP transport on the protocol side, and the CLI already speaks to Speculos by default. This document fills the missing operational guide around the emulator.

## What Speculos Covers Here

- `getAppInfo` and `getAppConfig`;
- `dumpMetadatas` reads;
- `loadMetadatas` writes;
- confirmation prompts from the Passwords app;
- CLI `pull`, `push`, `verify`, and `diff` checks.
- the Android guided `Synchronize` flow over TCP.

## What Speculos Does Not Replace

- the Android USB stack;
- HID or bulk interface selection on a real device;
- real hardware timing;
- some firmware behaviors such as watchdogs.

After the incident observed during Android `push` on a real device, the write path must first be replayed under Speculos. The real Ledger should remain reserved for read-only hardware smoke tests until the incident is understood.

## Prerequisites

You need the `app.elf` binary of the Ledger Passwords app. This repository does not ship it.

You can then:

- either install Speculos locally;
- or use the official Docker image `ghcr.io/ledgerhq/speculos`.

## Build `app.elf`

The repository now includes a helper that clones `LedgerHQ/app-passwords` and builds it inside the official Ledger Docker image:

```bash
scripts/build-passwords-app.sh
```

The generated binary is a test build with `TESTING=1 POPULATE=1`. By default it is written to:

```text
build/speculos/app-passwords/bin/app.elf
```

You can retrieve only the path:

```bash
scripts/build-passwords-app.sh --print-path
```

## Start the Emulator

The repository wrapper automatically chooses the local `speculos` binary if present, otherwise Docker.

```bash
scripts/run-speculos-passwords.sh /path/to/app.elf
```

By default:

- model: `nanosp`
- display: `headless`
- API/Web UI: `http://127.0.0.1:5000`
- APDU TCP: `127.0.0.1:9999`
- exposed name/version: `Passwords:0.0.0`

Examples:

```bash
scripts/run-speculos-passwords.sh /path/to/app.elf --display text
scripts/run-speculos-passwords.sh /path/to/app.elf --docker --sdk 1.0.3
scripts/run-speculos-passwords.sh /path/to/app.elf --display headless --vnc-port 41000
```

If the app asks for confirmation, use:

- the Web UI at `http://127.0.0.1:5000`;
- or `--display text`;
- or Speculos automation rules passed after `--`.

Passthrough example:

```bash
scripts/run-speculos-passwords.sh /path/to/app.elf -- --automation file:rules.json
```

## CLI Smoke Test

With Speculos already running:

```bash
scripts/speculos-smoke.sh
```

This script:

- builds the CLI if needed;
- waits for the Speculos TCP port to open;
- runs `device info`;
- performs a `device pull` into a temporary directory;
- immediately verifies the reread content with `device verify`.

If you just launched a test build of `app-passwords` in Speculos, you can also run:

```bash
scripts/speculos-smoke.sh --api-port 5000 --first-run --auto-approve
```

This does two useful things for `app-passwords`:

- approves the first-run disclaimer;
- chooses `QWERTY`;
- reads the Speculos screen and auto-answers common prompts used during read/write tests.

To also replay the write path on the emulator:

```bash
scripts/speculos-smoke.sh --api-port 5000 --auto-approve --with-push
```

`--with-push` stays confined to Speculos. It never uses `--hid`.

## Android Test on an Emulator

The Android app can also speak to Speculos over TCP. On a standard Android AVD, the host PC loopback is exposed as `10.0.2.2`.

The recommended automated path is:

```bash
scripts/android-emulator-speculos-test.sh --first-run
```

This script:

- detects the first connected `emulator-*`;
- starts a Speculos auto-approver on the host side;
- clears app data on the emulator;
- runs `:android-app:connectedDebugAndroidTest` against `10.0.2.2:${SPECULOS_APDU_PORT:-10100}`.

By default, the auto-approver only watches the current Speculos screen and handles the sequence:

- `Refuse`, `Transfer metadatas ?`, or `Overwrite metadatas ?`: press `right`;
- `Approve`: press `both`.

If you want to keep local app data between two runs:

```bash
scripts/android-emulator-speculos-test.sh --keep-app-data
```

For a manual test on the emulator:

1. install the APK;
2. open `Debug`;
3. choose `Speculos`;
4. keep `10.0.2.2` and `10100`;
5. use `Refresh target` if you want to confirm connectivity first;
6. open `Sync`;
7. use `Synchronize local and target` and follow the guided read/write/verify dialogs.

## Direct CLI Commands

The CLI uses Speculos TCP by default, so these commands already target the emulator:

```bash
./cli/build/install/ledger-pw/bin/ledger-pw device info
./cli/build/install/ledger-pw/bin/ledger-pw device pull --out /tmp/speculos-backup.json
./cli/build/install/ledger-pw/bin/ledger-pw device verify /tmp/speculos-backup.json
./cli/build/install/ledger-pw/bin/ledger-pw device push test-fixtures/backup-example.json
./cli/build/install/ledger-pw/bin/ledger-pw device diff test-fixtures/backup-example.json
```

You can also change host or port:

```bash
./cli/build/install/ledger-pw/bin/ledger-pw device info --server 127.0.0.1 --port 9999
```

## Recommended Diagnostic Order After the Android Incident

The right recovery order is:

1. replay `pull` then `verify` under Speculos;
2. replay `push` then `verify` under Speculos;
3. compare the prompts seen in Speculos with those seen on the real device;
4. only then resume hardware tests, read-only first.
