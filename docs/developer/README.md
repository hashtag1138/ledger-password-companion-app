# Developer Documentation

This section is intended for code study, testing, and investigation.

## Read in This Order

1. [Architecture and source code study](./architecture.md)
2. [Builds and tests](./testing.md)
3. [Fuzzing and Speculos campaigns](./fuzzing.md)

## Existing Technical References

- [TECHNICAL_CHOICES.md](../../TECHNICAL_CHOICES.md)
- [Ledger Passwords protocol](../ledger-passwords-protocol.md)
- [Synchronization flows](../sync-flows.md)
- [Product security](../security.md)
- [ADR 0001](../adr/0001-module-boundaries.md)
- [ADR 0002](../adr/0002-no-password-generation-on-phone.md)

## Investigation References

- [Fuzzing findings report](../fuzzing-findings-report.md)
- [Companion-side mitigation plan](../companion-mitigation-plan.md)
- [Detailed fuzzing tracker](../fuzzing-tracker.md)
- [Speculos guide](../speculos-testing.md)

## Goals of This Documentation

It should allow a new contributor to:

- understand how the repository is structured;
- know where to read the code first;
- reproduce the test baseline;
- rerun fuzz campaigns without starting from scratch.
