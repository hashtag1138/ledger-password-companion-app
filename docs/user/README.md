# User Documentation

This section is intended for normal companion usage, especially the Android app.

## Start Here

- [Use the Android app](./android-app.md)
- [Safety, limits, and guardrails](./safety-and-limits.md)

## What the Companion Does

- manage the list of Ledger Passwords identifiers/nicknames locally;
- import and export a compatible `backup.json`;
- compare local state and Ledger state;
- push a metadata block to the Ledger after explicit confirmation.

## What the Companion Does Not Do

- it never asks for the recovery phrase;
- it does not generate final passwords;
- it does not replace the Passwords app on the Ledger;
- it does not run automatic sync to a network service.

## Recommended Path

1. Prepare or edit identifiers locally in the app.
2. Export a `backup.json` if you want a file copy.
3. Connect the Ledger, open the `Passwords` app, then use `Compare`.
4. If the diff is correct, use `Export to Ledger`.
5. Run `Verify` separately after writing.

## About Debug

The `Debug` menu exists for testing and technical investigation. A normal user does not need it day to day.

If you want to understand the architecture, reproduce tests, or use Speculos, go to the [developer documentation](../developer/README.md).
