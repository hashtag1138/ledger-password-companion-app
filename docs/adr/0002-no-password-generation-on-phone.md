# ADR 0002 - No Password Generation on the Phone

## Status

Accepted.

## Decision

The Android companion does not generate or display final passwords.

## Reasons

- The Ledger must remain the cryptographic authority.
- The goal is to manage identifiers, not replace the Passwords app.
- Reduce attack surface and UX risk.

## Consequences

- No "show password" function.
- No Android autofill in V1.
- No seed or secret request.
