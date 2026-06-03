# Detailed implementation plan

## Vision

Create an Android companion app for Ledger Passwords that only manages metadata/nicknames. The Ledger remains the cryptographic authority and the only component capable of generating/typing final passwords.

The project is divided into three parts:

1. pure business logic;
2. PC CLI for testing offline and with Ledger;
3. Simple Android UI for daily use.

## Fundamental principle

The Ledger Passwords app does not provide APDUs to add, delete, or edit an entry individually. It exposes a complete dump and a complete load of metadata. CRUD operations must therefore be done locally on a business model, then exported by rewriting the entire metadata block of the Ledger.

Important UX consequence: renaming a nickname is not a simple change of wording. The nickname participates in the deterministic derivation of the password on the Ledger side. Renaming `gmail` to `google` will produce another password.

## Phase 0 — framing and repo base

Objectives:

- create the multi-module repository;
- isolate the business logic of the Ledger protocol;
- document the metadata format and flows;
- prepare the project for Codex.

Deliverables:

- `core/` ;
- `ledger-protocol/` ;
- `cli/` ;
- `android-app/` ;
- docs;
- fixtures.

Acceptance criteria:

- the repository opens in IntelliJ/Android Studio;
- JVM modules are independent of Android;
- TODOs are explicit and localized;
- Ledger invariants are written in the docs and in the code.

## Phase 1 — pure business logic

Module concerned: `core`.

To implement/finalize:

- `PasswordIdentifier` ;
- `CharsetFlag` ;
- `CharsetPolicy` ;
- `Vault` ;
- `VaultEditor` ;
- `VaultValidator` ;
- `VaultDiff`.

Rules:

- non-empty nickname;
- nickname max `19` UTF-8 bytes;
- duplicates prohibited;
- charsets between `0x00` and `0xFF` ;
- total capacity <= `storageSize`, default `4096`;
- conservative limit of `178` entries;
- local notes are never exported to Ledger.

Required unit tests:

- valid addition;
- duplicate rejected;
- empty nickname rejected;
- nickname of 19 bytes accepted;
- nickname of 20 bytes rejected;
- Unicode nickname validated in bytes, not in characters;
- rename warned/documented;
- diff added/removed/changed.

## Phase 2 — Ledger metadata codec

Module concerned: `ledger-protocol`.

To implement/finalize:

- `MetadataCodec.decode(raw)` ;
- `MetadataCodec.encode(vault)` ;
- management of active entries;
- management of deleted entries in reading mode;
- compaction during normal export;
- conversion charsets bitmask <-> names;
- hex utils;
- corruption reporting.

Raw format:

```text
[length: 1 byte] [kind: 1 byte] [charsets: 1 byte] [nickname bytes...]
```

`length = 1 + nicknameByteLength`, because the charset byte is part of the data.

Required tests:

- decode existing fixture;
- encode -> decode roundtrip;
- raw 4096 bytes with zero padding;
- deleted entry `kind=0xFF` visible in `erasedEntries`;
- corruption if `length > 20`;
- corruption if offset exceeds the size of the buffer;
- compact export without deleted entries.

## Phase 3 — Ledger Web UI compatible JSON backup

Module concerned: `ledger-protocol`.

To be finalized:

- `BackupJsonCodec.fromJson` ;
- `BackupJsonCodec.toJson` ;
- `parsed` compatibility;
- `nicknames_erased_but_still_stored` compatibility;
- `corruptions_encountered` compatibility;
- `raw_metadatas` compatibility;
- additional ignorable companion fields.

Recommended format:

```json
{
  "format": "ledger-passwords-companion.v1",
  "storage_size": 4096,
  "app": { "name": "Passwords", "version": "unknown" },
  "parsed": [
    { "nickname": "github", "charsets": ["UPPERCASE", "LOWERCASE", "NUMBERS"] }
  ],
  "nicknames_erased_but_still_stored": [],
  "corruptions_encountered": [],
  "raw_metadatas": "..."
}
```

Required tests:

- import of an example backup;
- readable export;
- unknown keys ignored;
- `ALL_SETS` recognized;
- JSON -> Vault -> raw -> functionally stable JSON.## Phase 4 — APDU client and fake transport

Module concerned: `ledger-protocol`.

To implement/finalize:

- `LedgerTransport` ;
- `ApduResponse` ;
- `LedgerPasswordsClient.getAppInfo()` ;
- `LedgerPasswordsClient.getAppConfig()` ;
- `LedgerPasswordsClient.dumpMetadatas()` ;
- `LedgerPasswordsClient.loadMetadatas(raw)` ;
- `FakeLedgerTransport`.

APDU to support:

| Action | CLA | INS | P1 | P2 | Data |
|---|---:|---:|---:|---:|---|
| App info | `0xB0` | `0x01` | `0x00` | `0x00` | empty |
| Config | `0xE0` | `0x03` | `0x00` | `0x00` | empty |
| Dump | `0xE0` | `0x04` | `0x00` | `0x00` | empty |
| Load chunk | `0xE0` | `0x05` | `0x00` or `0xFF` | `0x00` | chunk <= 255 |

Status words:

- `0x9000`: success;
- `0x6985`: action canceled;
- `0x6A86`: bad P1/P2;
- `0x6A87`: wrong length;
- `0x6D00`: INS not supported;
- `0x6E00`: CLA not supported;
- `0x6F10`: metadata parsing error.

Required tests:

- fake `getAppInfo` ;
- fake `getAppConfig` ;
- fake dump 4096 bytes per chunk;
- fake load 4096 bytes in chunks of 255;
- load then dump identical.

## Phase 5 — offline CLI

Module concerned: `cli`.

MVP commands:

```bash
ledger-pw help
ledger-pw file list backup.json
ledger-pw file validate backup.json
ledger-pw file add backup.json github --charset upper,lower,numbers --out backup2.json
ledger-pw file delete backup.json github --out backup2.json
ledger-pw file rename backup.json old new --out backup2.json
ledger-pw file edit backup.json github --charset all --out backup2.json
ledger-pw file export-raw backup.json --out metadata.bin
```

Acceptance criteria:

- no Ledger connection required;
- readable errors;
- `--out` mandatory to avoid overwriting by accident;
- simple table output;
- JSON machine-readable option to add later.

## Phase 6 — CLI with real device or emulator

Transportation to add:

- `SpeculosTransport` for functional tests;
- `PcHidLedgerTransport` for real Ledger USB/HID;
- possibly transport via an existing Ledger JS/Java lib if the choice is validated.

Commands:

```bash
ledger-pw device info
ledger-pw device pull --out backup.json
ledger-pw device push backup.json
ledger-pw device diff backup.json
ledger-pw device verify backup.json
```

Flow `push`:

1. read backup JSON;
2. validate;
3. connect Ledger;
4. check app name = `Passwords`;
5. read config;
6. encode raw;
7. dumper device for diff;
8. request CLI confirmation;
9. send `LOAD_METADATAS` in chunks;
10. reread the device;
11. compare raw expected vs raw reread.

## Phase 7 — Android MVP offline

Module concerned: `android-app`.

Screens:

- `HomeScreen`;
- `IdentifierListScreen`;
- `EditIdentifierScreen` ;
- `ImportExportScreen` ;
- `SettingsScreen` minimum.

Functions:

- internal local storage;
- import/export JSON via Storage Access Framework;
- list + search;
- add/delete/edit/rename;
- inline validation;
- rename warning.

Acceptance criteria:

- usable without Ledger;
- no network permissions;
- no telemetry;
- backup exported explicitly by the user.

## Phase 8 — Android USB Ledger

To implement:

- `AndroidUsbLedgerTransport` ;
- USB host detection;
- Android permission;
- claim interface;
- IN/OUT endpoints;
- framing APDU Ledger HID;
- sync screen;
- sweater;
- push;
- verify post-push;
- disconnection management.

Flow pull:

1. Ledger plugged in;
2. open Passwords app;
3. Android asks USB permission;
4. `getAppInfo`;
5. `getAppConfig`;
6. `dumpMetadatas`;
7. Ledger requests approval;
8. parse + print diff/replace.

Flow push:

1. local automatic backup;
2. diff local vs device;
3. Android confirmation;
4. Ledger requests approval;
5. upload chunks;
6. verify by dump;
7. success or manual rollback via backup.## Phase 9 — hardening

To test:

-Nano S;
- Nano S Plus;
- Nano X in USB;
- OTG cable;
- refusal of USB permission;
- Ledger app not open;
- action canceled on Ledger;
- disconnection during dump;
- disconnection during load;
- corrupted backup;
- name too long;
- full storage;
- Unicode;
- custom chariots.

To add:

- crash-safe local backups;
- redacted logs;
- test instrumentation;
- validation screenshots;
- CI GitHub Actions;
- locally signed debug release.

## Excluding MVP

What not to do at the start:

- generation/display of passwords on Android;
- storage of secrets;
- cloud sync;
- Android autofill;
- WHEAT ;
- complex multi-vault;
- automatic sync without diff;
- telemetry containing nicknames.
