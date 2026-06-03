# Continuation prompt for Codex

You are working in the `ledger-passwords-companion` repository.

Overall objective: implement an Android companion app for Ledger Passwords. The app only manages metadata/nicknames, never the seed, never the final passwords.

Unavoidable constraints:

- preserve the separation `core` / `ledger-protocol` / `cli` / `android-app`;
- do not introduce Android in `core` nor `ledger-protocol`;
- run the tests before adding features;
- respect the Ledger metadata format: `[length][kind][charsets][nickname]`;
- nickname max 19 UTF-8 bytes;
- storage metadata by default 4096 bytes;
- Ledger Web UI compatible bitmask charsets;
- `0x00` or `0xFF` for all charsets;
- no password generation or display in Android or CLI;
- do not add network/telemetry.

First recommended mission:

1. launch `./gradlew :core:test :ledger-protocol:test` ;
2. correct compilation errors;
3. complete the `MetadataCodecTest` tests;
4. complete `BackupJsonCodecTest`;
5. finalize the offline CLI for `list`, `validate`, `add`, `delete`, `rename`, `edit`, `export-raw`.

Then:

1. implement `LedgerHidFraming` ;
2. implement `PcHidLedgerTransport` or `SpeculosTransport` ;
3. implement `AndroidUsbLedgerTransport` ;
4. add Android pull/push sync screen with diff.

Before each push to Ledger, the app must:

- validate the vault;
- dump the device;
- display a diff;
- create a local backup;
- request user confirmation;
- request physical validation on the Ledger;
- reread the device after writing and compare.
