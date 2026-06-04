# Architecture and Source Code Study

## Overview

The repository is organized to separate:

- pure business logic;
- the Ledger Passwords protocol;
- testing and command-line tools;
- Android integration.

```text
core/             pure business logic
ledger-protocol/  metadata codec, backups, APDU, transports
cli/              PC test bench and simple automation
android-app/      Compose UI, local storage, Android sync
scripts/          Speculos helpers, smoke tests, fuzzers
docs/             design, security, findings, plans
```

## Responsibilities by Module

### `core`

Read this first if you want to understand product rules.

It notably contains:

- [Vault.kt](/home/sofian/Sources/ledger-passwords-companion/core/src/main/kotlin/com/ledgerpasswords/companion/core/model/Vault.kt:1)
- [PasswordIdentifier.kt](/home/sofian/Sources/ledger-passwords-companion/core/src/main/kotlin/com/ledgerpasswords/companion/core/model/PasswordIdentifier.kt:1)
- [VaultEditor.kt](/home/sofian/Sources/ledger-passwords-companion/core/src/main/kotlin/com/ledgerpasswords/companion/core/edit/VaultEditor.kt:1)
- [VaultValidator.kt](/home/sofian/Sources/ledger-passwords-companion/core/src/main/kotlin/com/ledgerpasswords/companion/core/validation/VaultValidator.kt:1)
- [LedgerPushRiskPolicy.kt](/home/sofian/Sources/ledger-passwords-companion/core/src/main/kotlin/com/ledgerpasswords/companion/core/risk/LedgerPushRiskPolicy.kt:1)
- [VaultDiff.kt](/home/sofian/Sources/ledger-passwords-companion/core/src/main/kotlin/com/ledgerpasswords/companion/core/diff/VaultDiff.kt:1)

### `ledger-protocol`

Read this next to understand the format and transport.

Key files:

- [MetadataCodec.kt](/home/sofian/Sources/ledger-passwords-companion/ledger-protocol/src/main/kotlin/com/ledgerpasswords/companion/ledger/metadata/MetadataCodec.kt:1)
- [BackupJsonCodec.kt](/home/sofian/Sources/ledger-passwords-companion/ledger-protocol/src/main/kotlin/com/ledgerpasswords/companion/ledger/backup/BackupJsonCodec.kt:1)
- [LedgerPasswordsClient.kt](/home/sofian/Sources/ledger-passwords-companion/ledger-protocol/src/main/kotlin/com/ledgerpasswords/companion/ledger/client/LedgerPasswordsClient.kt:1)
- [LedgerHidFraming.kt](/home/sofian/Sources/ledger-passwords-companion/ledger-protocol/src/main/kotlin/com/ledgerpasswords/companion/ledger/transport/LedgerHidFraming.kt:1)
- [SpeculosTransport.kt](/home/sofian/Sources/ledger-passwords-companion/ledger-protocol/src/main/kotlin/com/ledgerpasswords/companion/ledger/transport/SpeculosTransport.kt:1)
- [PcHidLedgerTransport.kt](/home/sofian/Sources/ledger-passwords-companion/ledger-protocol/src/main/kotlin/com/ledgerpasswords/companion/ledger/transport/PcHidLedgerTransport.kt:1)

### `cli`

The entry point is [Main.kt](/home/sofian/Sources/ledger-passwords-companion/cli/src/main/kotlin/com/ledgerpasswords/companion/cli/Main.kt:1).

This module is used to:

- validate codecs outside Android;
- drive Speculos;
- drive a real Ledger from a PC;
- serve as a base for fuzzing campaigns.

### `android-app`

The runtime entry point is [MainActivity.kt](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/kotlin/com/ledgerpasswords/companion/android/MainActivity.kt:1).

Current split:

- [AppShell.kt](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/kotlin/com/ledgerpasswords/companion/android/AppShell.kt:1): Compose UI
- [SyncUiLogic.kt](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/kotlin/com/ledgerpasswords/companion/android/SyncUiLogic.kt:1): state reduction and diff rendering
- [LocalVaultStore.kt](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/kotlin/com/ledgerpasswords/companion/android/storage/LocalVaultStore.kt:1): local persistence
- [SyncShadowStore.kt](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/kotlin/com/ledgerpasswords/companion/android/storage/SyncShadowStore.kt:1): last successful sync shadow
- [DiagnosticLogStore.kt](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/kotlin/com/ledgerpasswords/companion/android/storage/DiagnosticLogStore.kt:1): persistent logs
- [AndroidUsbLedgerTransport.kt](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/kotlin/com/ledgerpasswords/companion/android/usb/AndroidUsbLedgerTransport.kt:1): Android USB integration

## Main Runtime Flows

### Local Editing

`AppShell` collects user intent, `MainActivity` builds a new `Vault`, then saves it through `LocalVaultStore`. The home screen derives pending local changes and `New locally` markers by comparing `LocalVaultStore` with `SyncShadowStore`.

### JSON Import/Export

The file goes through `BackupJsonCodec`, then:

- strict validation in `core`;
- raw preservation when possible;
- possible later blocking by the hardware-safe policy if the raw backup is suspicious.

### Real Ledger Sync

Read chain:

`MainActivity` -> `AndroidUsbLedgerTransport` -> `LedgerPasswordsClient` -> `MetadataCodec`

Write chain:

`MainActivity` -> `LedgerPushRiskPolicy` -> `LedgerPasswordsClient.loadMetadatas()`

The day-to-day Android flow is a guided pipeline:

`read -> merge -> optional conflict resolution -> write -> verify -> persist local vault -> persist sync shadow`

### Speculos Sync

The flow is the same as for a real Ledger, except the transport is `SpeculosTransport`, the policy is less strict, and transport configuration is exposed through `Debug & Lab`.

## Recommended Order for Studying the Code

1. Read [README.md](../../README.md) and [TECHNICAL_CHOICES.md](../../TECHNICAL_CHOICES.md).
2. Read the models and validators in `core`.
3. Read `MetadataCodec` and `BackupJsonCodec`.
4. Read `LedgerPasswordsClient` and the transports.
5. Read `Main.kt` on the CLI side for minimal scenarios.
6. Read `MainActivity` and then `AppShell` on the Android side.
7. Read the corresponding tests before modifying an area.

## Recommended Order for Debugging

### Business Logic Bug

Start with `core`, then `ledger-protocol`.

### Sync Bug

Start with:

- `LedgerPasswordsClient`
- the relevant transport
- `MainActivity`

### Android UI Bug

Start with:

- `AppShell`
- `SyncUiLogic`
- `MainActivity`

### Risk / Pre-Push Blocking Bug

Start with:

- `VaultValidator`
- `NicknameSafety`
- `LedgerPushRiskPolicy`

## Related References

- [Ledger Passwords protocol](../ledger-passwords-protocol.md)
- [Synchronization flows](../sync-flows.md)
- [Companion-side mitigation plan](../companion-mitigation-plan.md)
