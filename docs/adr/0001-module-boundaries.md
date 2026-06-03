# ADR 0001 - Strict Module Separation

## Status

Accepted.

## Decision

The project keeps four main modules:

- `core`: pure business logic;
- `ledger-protocol`: codec + APDU;
- `cli`: PC tool;
- `android-app`: UI and Android integration.

## Consequences

- Codec and business-logic tests run without Android.
- Ledger transport can be fake, PC, Speculos, or Android without changing business logic.
- The Android app remains a frontend and does not become the source of cryptographic truth.
