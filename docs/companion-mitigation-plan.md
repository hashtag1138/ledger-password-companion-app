# Companion Side Mitigation Plan

This document describes what the companion can do to reduce the risk of writing to `app-passwords`, without claiming to fix internal bugs in the Ledger application.

References:

- [fuzzing-findings-report.md](/home/sofian/Sources/ledger-passwords-companion/docs/fuzzing-findings-report.md:1)
- [fuzzing-tracker.md](/home/sofian/Sources/ledger-passwords-companion/docs/fuzzing-tracker.md:1)

## Objectives

1. prevent the companion from pushing into manifestly dangerous states;
2. make the risks visible to the user before a real `push`;
3. keep a normal mode usable for simple and valid cases;
4. maintain a debug mode for Speculos and investigation;
5. transform the reproducers found into an automatic regression suite.

## Non-objectives

- correct the firmware or `app-passwords` itself;
- guarantee that a real `push` is “risk-free” as long as the Ledger app contains known bugs;
- arbitrarily block spaces, which are supported by `app-passwords`.

## Guiding principle

The companion must have two levels of severity:

1. basic validation, always active, to guarantee an encodable and consistent state;
2. policy `hardware-safe`, applied before real `push` on Ledger, stricter than the raw protocol.

## Phase 0: immediate safeguards

### P0.1 Stricter baseline validation

Purpose:

- never push an already doubtful state even if it is encodable.

To add in [VaultValidator.kt](/home/sofian/Sources/ledger-passwords-companion/core/src/main/kotlin/com/ledgerpasswords/companion/core/validation/VaultValidator.kt:1):

- rejection of Unicode control characters;
- rejection of `NUL`, `TAB`, `LF`, `CR`;
- rejection of dangerous invisible characters:
  - zero-width space/joiners;
  - bidi override/isolate controls;
- distinction between `blank`, `leading/trailing whitespace`, `dangerous unicode`, `duplicate`.

UX impact:

- internal spaces remain permitted;
- start/end spaces become at least a strong warning, ideally a rejection in hardware-safe mode.

### P0.2 Freeze the real push if the raw backup is suspicious

Purpose:

- do not retransmit a doubtful or inconsistent `raw_metadatas` to a real Ledger.

Rules:

- if a JSON import contains `corruptions_encountered`, block real `push`;
- if `raw_metadatas` exists but does not correspond to the expected decoded vault, block the real `push`;
- if a local round-trip `decode -> encode -> decode` diverges, block `push` real;
- if the backup comes from a known dangerous fuzz case, block `push` real.

Target modules:

- [BackupJsonCodec.kt](/home/sofian/Sources/ledger-passwords-companion/ledger-protocol/src/main/kotlin/com/ledgerpasswords/companion/ledger/backup/BackupJsonCodec.kt:16)
- [MainActivity.kt](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/kotlin/com/ledgerpasswords/companion/android/MainActivity.kt:742)
- [Main.kt](/home/sofian/Sources/ledger-passwords-companion/cli/src/main/kotlin/com/ledgerpasswords/companion/cli/Main.kt:1)### P0.3 Keep `push` and `verify` separate

Status:

- already in place on the Android side.

Rule:

- never reintroduce automatic post-write readback on real Ledger;
- keep `verify` as an explicit and separate action.

## Phase 1: policy `hardware-safe`

### P1.1 Add an explicit risk policy

Create a dedicated layer, for example:

- `core/.../risk/LedgerPushRiskPolicy.kt`

It will classify a vault into:

- `allow`
- `warn`
- `block`

Minimum heuristics to integrate:

- invisible or bidi characters: `block`
- leading/trailing spaces: `warn` or `block`
- identical standardized names under `NFC + trim + lowercase`: `block`
- visually confusing corpora: `warn`
- very similar corpora by long prefix: `warn`
- dense number of entries close to UI limits: `warn`
- imported raw with previous anomaly: `block`

### P1.2 Apply policy only to hardware push

Principle:

- do not unnecessarily break local or Speculos flows;
- be stricter only before `push` actual.

Rules:

- Android local mode: basic validation only;
- `push` to Speculos: authorize with warnings;
- `push` to true Ledger USB/HID: apply `hardware-safe`.

Target modules:

- [MainActivity.kt](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/kotlin/com/ledgerpasswords/companion/android/MainActivity.kt:742)
- [Main.kt](/home/sofian/Sources/ledger-passwords-companion/cli/src/main/kotlin/com/ledgerpasswords/companion/cli/Main.kt:1)

### P1.3 UX warning before real push

Replace the generic popup with a readable risk summary:

- `OK` if nothing notable;
- `Warning` if the corpus is close, dense, or ambiguous;
- `Blocked` if it contains invisible characters, severe confusables, or suspicious raw data.

The popup must mention the reason(s):

- `Leading/trailing spaces`
- `Invisible characters`
- `Nearly identical names`
- `Inconsistent raw backup`
- `High entry count`

## Phase 2: data hygiene

### P2.1 Normalization and detection of logical duplicates

The companion should not silently rewrite the nicknames, but it can also compare:

- gross value;
- form `NFC`;
- form `trim()`;
- form `collapse whitespace` if you choose to follow it.

Purpose:

- prevent pairs like `é` / `é`;
- prevent `foo bar` / `foo\u00a0bar`;
- prevent `zerowidth` / `zero\u200bwidth`.

### P2.2 More conservative capacity indicator

The capacity calculation already exists in [LedgerCapacity.kt](/home/sofian/Sources/ledger-passwords-companion/core/src/main/kotlin/com/ledgerpasswords/companion/core/LedgerCapacity.kt:1), but it is necessary to add:

- a warning threshold before the hard limit;
- a specific warning on very dense corpora;
- a clear message when the number of entries is technically encodable but potentially risky for the Ledger UI.

Important:

- this is not proof that a “high” size causes a crash;
- this is a cautious mitigation, not a root cause diagnosis.## Phase 3: normal / debug separation

### P3.1 Keep Speculos and lab tools out of normal user flow

Purpose:

- avoid mixing safe uses and the stress lab in the same flow.

Rules:

- `Speculos`, custom host/port, detailed diagnostics, security overrides remain in `Debug`;
- normal flow only keeps:
  - import;
  - compare ;
  - export to Ledger;
  - check.

### P3.2 Add a `dangerous override` explicitly debug mode

Need:

- certain tests must still be able to push a blocked corpus.

Rule:

- override possible only from `Debug`;
- never active by default;
- visually traceable in the UI and in the logs.

## Phase 4: testing and regression

### P4.1 Transform the best reproducers into a short suite

To keep in a rapid regression suite:

- `alpha_beta_push_show_second`
- `second_len_plus1_show_second`
- `sameprefix_19bytes_delete_third`
- `nfc_nfd_delete_second`
- `mixed_unicode_show_all`
- `dump_partial_then_info_then_pull`

### P4.2 Fix artifact `POPULATE=1`

Action:

- add a Speculos build variant without `POPULATE=1` for product campaigns;
- keep `POPULATE=1` only if you want a demonstration state.

Reference:

- [build-passwords-app.sh](/home/sofian/Sources/ledger-passwords-companion/scripts/build-passwords-app.sh:11)

### P4.3 Fail the CI on security regression

Purpose:

- prevent a relaxation of the safeguards from reintroducing a dangerous `push`.

To cover:

- unit tests `VaultValidator`;
- tests of the future `LedgerPushRiskPolicy`;
- Android/CLI tests on expected messages and blockages;
- E2E emulator + Speculos on safe case.

## Recommended implementation plan

1. Extend `VaultValidator` with prohibited characters and risk categories.
2. Add `LedgerPushRiskPolicy` in `core`.
3. Plug this policy into Android before `push` real.
4. Plug the same policy into the CLI before `device push --hid`.
5. Add rich warning popup and `hardware-safe` blocking.
6. Add validation and policy unit tests.
7. Add a Speculos variant without `POPULATE=1`.
8. Add a short regression sequence from confirmed reproducers.

## Acceptance criteria

The plan will be considered correctly implemented when:

1. a nickname with an internal space remains authorized;
2. a nickname with zero-width or bidi control is blocked before `push` real;
3. Unicode logical duplicates are blocked or require a debug override;
4. a suspicious raw backup cannot be pushed to real Ledger;
5. `push` real and `verify` remain separate;
6. existing safe cases remain green on Speculos and on the Android emulator;
7. the user doc clearly explains that a real `push` is filtered by a companion security policy.
