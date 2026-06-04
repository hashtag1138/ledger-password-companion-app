# Safety, Limits, and Guardrails

## What the App Protects

The companion helps prepare and transfer Ledger Passwords metadata, but it does not replace the Ledger as the root of trust.

Reminders:

- the recovery phrase must never be entered into this app;
- nicknames may be private, but they are not cryptographic secrets;
- the app does not derive final passwords.

## Important Functional Limits

- `19` UTF-8 bytes maximum per nickname;
- metadata block limited by the `storage_size` exposed by the Passwords app;
- the Ledger does not provide unit add/delete APDUs: every write replaces the whole metadata block.

## Safety Policy Before Writing to a Real Ledger

Before a real Ledger write, the companion applies a stricter policy than the raw protocol.

Blocking examples:

- control characters;
- `NUL`, newlines, tabs;
- dangerous Unicode formatting characters, such as some zero-width or bidi controls;
- logical duplicates after normalization;
- raw backup considered inconsistent or corrupted.

Warning examples:

- leading or trailing spaces;
- very similar identifiers;
- dense datasets;
- imported backups with weak risk signals.

## Dangerous Override

A bypass exists for testing, but it is isolated in `Debug`.

It should only be used to:

- reproduce a bug;
- test a behavior under supervision;
- work with Speculos or a lab device.

## Practical Precaution

Before a real synchronization write:

1. export a `backup.json` if the current local vault matters;
2. review local changes and resolve any reported conflicts deliberately;
3. approve the read, write, and verification prompts on the Ledger only when they match the action you started;
4. wait for the success dialog before assuming local and device state converged.

## Current Limits

The companion reduces risk, but it cannot fix internal `app-passwords` bugs.

Fuzzing campaigns have already shown that some valid or semi-valid states can crash the Ledger app itself. For that reason:

- Speculos should be used as a proving ground before any risky test;
- real writes should remain deliberate and explicit;
- debug flows remain separate from the normal flow.

The sync shadow is also local to the current phone install. It helps the app distinguish pending local changes from synchronized state, but it is not a replacement for a user-managed backup.
