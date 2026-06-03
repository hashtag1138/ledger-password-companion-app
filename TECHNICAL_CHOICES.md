# Technical choices

## Language

Kotlin is used everywhere to share as much logic as possible between the CLI and Android.

Decision V1: simple Kotlin/JVM modules for `core`, `ledger-protocol` and `cli`, plus classic Android module for `android-app`.

Future alternative: migrate `core` and `ledger-protocol` to Kotlin Multiplatform if a stricter iOS, Desktop native or shared Android/JVM target becomes necessary.

## Build

- Gradle Kotlin DSL.
- Catalog version `gradle/libs.versions.toml`.
- JDK 17.
- Versions of Kotlin, Android Gradle Plugin, Compose and coroutines centralized in `gradle/libs.versions.toml`.

The Gradle wrapper is included and should be used for all builds in the repo:

```bash
./gradlew
```

## Modules

### `core`

Responsibility: pure business logic.

Should not depend on anything other than Kotlin stdlib and test libs.

Contains:

- models;
- validation;
- editing;
- diff;
- functional constants.

Prohibited in this module:

- Android;
- USB;
- APDU;
- files;
- JSON;
- coroutines if not necessary.

### `ledger-protocol`

Responsibility: Speak Ledger Passwords format.

Contains:

- raw metadata codec;
- JSON backup;
- APDU constants;
- Ledger Passwords client;
- abstract transport;
- fake transport;
- shared USB/HID framing.

Does not contain:

- UI;
- Android permissions;
- CLI logic;
- Android local storage.

### `cli`

Responsibility: provide a test bench usable on PC.

Contains:

- minimal argument parsing;
- offline commands;
- later, device commands;
- human display.

Choice V1: no external CLI parser dependency to limit the skeleton. We can replace it with Clikt if the CLI becomes complex.

### `android-app`

Responsibility: UI and Android integration.

Contains:

- Jetpack Compose;
- USB permission/host;
- Storage Access Framework;
- UI state;
- ViewModels to add;
- Ledger client integration via `AndroidUsbLedgerTransport`.

## Metadata format

The raw format respects the Ledger Passwords behavior:

```text
[length][kind][charsets][nickname bytes]
```

- `length = 1 + nicknameByteLength` ;
- `kind = 0x00` active;
- `kind = 0xFF` deleted;
- `charsets = bitmask 8 bits` ;
- zero padding up to `storageSize`;
- first `length = 0x00` marks the end.

## Limits

- `storageSize = 4096` by default;
- `MAX_METANAME = 20` Ledger side;
- useful nickname = `19` UTF-8 bytes;
- conservative limit = `178` entries.

The code validates in **UTF-8 bytes**, not in number of characters. By default, the UI should recommend ASCII to avoid input surprises on Ledger.

## Charsets

Mapping:

| Name | Bit |
|---|---:|
| `UPPERCASE` | `0x01` |
| `LOWERCASE` | `0x02` |
| `NUMBERS` | `0x04` |
| `MINUS` | `0x08` |
| `UNDERLINE` | `0x10` |
| `SPACE` | `0x20` |
| `SPECIAL` | `0x40` |
| `BRACKETS` | `0x80` |
| `ALL_SETS` | `0xFF` |

`0x00` is read as `ALL_SETS` for compatibility.

## Ledger Protocol

Supported APDUs:

| Action | CLA | INS |
|---|---:|---:|
| app info | `0xB0` | `0x01` |
| config | `0xE0` | `0x03` |
| dump metadata | `0xE0` | `0x04` |
| load metadata | `0xE0` | `0x05` |

The device requests physical approval for dump and load. The Android/CLI app must therefore display an explicit status such as "approve the action on the Ledger".

## USB transport

The transport remains behind the interface:

```kotlin
interface LedgerTransport {
    suspend fun exchange(
        cla: Int,
        ins: Int,
        p1: Int = 0x00,
        p2: Int = 0x00,
        data: ByteArray = byteArrayOf()
    ): ApduResponse
}
```

Advantage:

- tests with fake;
- PC CLI;
- Speculos;
- Android USB;
- future variants without changing the business protocol.

## Android

Choice V1: USB only.

Reasons:

- simpler than BLE;
- more robust to begin with;
- Ledger physical validation already mandatory;
- allows rapid OTG testing.

The manifest declares `android.hardware.usb.host` and a Ledger vendor-id filter `0x2C97`.

## Security

Posture:

- never ask for the recovery phrase;
- never store final passwords;
- never display final passwords;
- nicknames are considered private but not secret at recovery phrase level;
- no default network;
- no telemetry;
- automatic local backup before push.

## Codex implementation strategy

Base already made:

1. compilation `core` / `ledger-protocol` / `cli` / `android-app` ;
2. offline codec, client, fake transport and CLI tests;
3. stabilization `BackupJsonCodec`;
4. offline and device CLI commands;
5. Speculos, PC HID and Android USB transports;
6. Android's first USB sync flow;
7. Persistent Android local storage and local editing;
8. import/export `backup.json` Android via Storage Access Framework.

Next recommended order:

1. replay the writing path under Speculos before any new attempt at `push` Android on a real device;
2. validate on real Ledger Android read-only and adjust the USB field robustness;
3. add a real merge/conflict before local replacement after `pull`;
4. add confirmation/diff screen before `push`.
