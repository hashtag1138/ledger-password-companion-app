# Ledger Passwords Protocol

## Source of Truth

This document describes the format currently used by the Ledger Passwords app `LedgerHQ/app-passwords` to manage metadata/nicknames.

The companion does not manipulate generated passwords. It only manipulates metadata.

## Storage

Default size: `4096` bytes.

Each metadata entry is stored as follows:

```text
offset + 0: length
offset + 1: kind
offset + 2: charsets
offset + 3: nickname bytes
```

`length` includes the `charsets` byte and the nickname:

```text
length = 1 + nicknameByteLength
```

The total size occupied by one entry is:

```text
entryTotalLength = length + 2
```

## Kind

| Value | Meaning |
|---:|---|
| `0x00` | active entry |
| `0xFF` | erased entry |

A normal companion export must compact metadata and must not re-export erased entries.

## Charsets

| JSON Name | Bit |
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

`0x00` must be read as `ALL_SETS`.

## End of List

The first `length = 0x00` byte marks the end of entries.

The rest of the buffer must be filled with zeros.

## Example

Entry `password1` with charsets `0x07`:

```text
0A 00 07 70 61 73 73 77 6F 72 64 31
```

Interpretation:

```text
0A          length = 10 = 1 charset + 9 nickname bytes
00          active kind
07          UPPERCASE + LOWERCASE + NUMBERS
70...31     "password1"
```

## APDU

| Action | CLA | INS | P1 | P2 | Data |
|---|---:|---:|---:|---:|---|
| App info | `0xB0` | `0x01` | `0x00` | `0x00` | empty |
| Config | `0xE0` | `0x03` | `0x00` | `0x00` | empty |
| Dump | `0xE0` | `0x04` | `0x00` | `0x00` | empty |
| Load chunk | `0xE0` | `0x05` | `0x00` or `0xFF` | `0x00` | chunk <= 255 |

## Dump Metadata

The device returns a series of responses:

```text
[flag][payload]
```

- `flag = 0x00`: more chunks remain;
- `flag = 0xFF`: last chunk.

The first request triggers user approval on the Ledger.

## Load Metadata

The client sends encoded metadata in chunks of 255 bytes max.

- `P1 = 0x00` for intermediate chunks;
- `P1 = 0xFF` for the last chunk.

The first request triggers user approval on the Ledger.

## Status Words

| SW | Meaning |
|---:|---|
| `0x9000` | success |
| `0x6985` | action cancelled |
| `0x6A86` | incorrect P1/P2 |
| `0x6A87` | incorrect length |
| `0x6D00` | unsupported INS |
| `0x6E00` | unsupported CLA |
| `0x6F10` | metadata parsing error |
