# User Documentation

This section is intended for normal companion usage, especially the Android app.

## Start Here

- [Use the Android app](./android-app.md)
- [Safety, limits, and guardrails](./safety-and-limits.md)

## What the Companion Does

- manage the list of Ledger Passwords identifiers/nicknames locally;
- import and export a compatible `backup.json`;
- synchronize local state and Ledger state through a guided merge flow;
- remember the last successful sync to surface pending local changes.

## What the Companion Does Not Do

- it never asks for the recovery phrase;
- it does not generate final passwords;
- it does not replace the Passwords app on the Ledger;
- it does not run automatic sync to a network service.

## Recommended Path

1. Prepare or edit identifiers locally in the app.
2. Export a `backup.json` if you want a file copy.
3. Connect the Ledger, open the `Passwords` app, then open `Sync`.
4. Tap `Synchronize` and approve the read, write, and verification steps on the device.
5. If a conflict is reported, choose whether to keep the local or Ledger version for that identifier.
6. Wait for the success dialog, then return home and confirm that pending local changes disappeared.

## About Debug

The `Debug` menu exists for testing and technical investigation. A normal user does not need it day to day.

It contains:

- transport selection for real USB or Speculos;
- target diagnostics such as `Refresh target`;
- the dangerous hardware override used only for supervised testing.

If you want to understand the architecture, reproduce tests, or use Speculos, go to the [developer documentation](../developer/README.md).
