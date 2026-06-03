# Findings Fuzzing Report `app-passwords`

This document summarizes the findings from `16` fuzzing campaigns carried out against the Ledger Passwords app via the companion CLI and Speculos.

Detailed reference:

- [fuzzing-tracker.md](/home/sofian/Sources/ledger-passwords-companion/docs/fuzzing-tracker.md:1)

## Perimeter

Covered by this campaign:

- APDU paths `GET_APP_INFO`, `GET_APP_CONFIG`, `DUMP_METADATAS`, `LOAD_METADATAS`;
- UI Speculos navigation;
- scenarios `push`, `pull`, `verify`, `diff`, `show`, `type`, `delete`;
- builds `app-passwords` `1.3.0`, `1.3.1` and `master` on certain campaigns.

Not directly covered:

- actual firmware/hardware behavior beyond what Speculos emulates;
- real Android USB stack;
- proof of a hardware factory reset identical to the real device.

## Confidence level

- `High`: `signal 11` crashes, timeouts, reread corruptions, wrong item selected under Speculos, reproducible across several runs or fuzzers.
- `Medium`: behaviors seen in a single harness but consistent with other findings.
- `Low`: behaviors potentially polluted by the Speculos test build.

## Executive summary

1. `app-passwords` can crash on valid metadata, without prior corruption injected by the companion.
2. The most fragile areas are list management, item selection, and certain APDU/UI sequences.
3. Malformed but accepted metadata can then crash the `show` and `delete` flows.
4. The APDU layer alone can produce silent corruptions and hangs.
5. The companion cannot "fix" these bugs on the Ledger app side, but it can greatly reduce the risk surface before `push` real.

## Cases that cause the app to crash

### 1. Minimum valid reproducer: `["alpha", "beta"] -> push -> show second`

- `severity`: critical
- `confidence`: high
- `fuzzers`: `FZ-04`, `FZ-06`, `FZ-07`, `FZ-15`
- `signature`:
  - the CLI sees `Remote end closed connection without response`
  - Speculos reports `The app crashed with signal 11`

Conclusion:

- this case is the best current minimal reproducer;
- it proves that a semantically valid state can be enough to break the app.

### 2. Dense lists / mixed widths

- `severity`: critical
- `confidence`: high
- `fuzzers`: `FZ-09`, `FZ-13`

Confirmed cases:

- `dense_twelve_show_delete_last`
- `reindex_delete_first_then_last`
- `mixed_widths_show_first_middle_last`
- `dense_twelve_push_prompt_spam_show_last`

Conclusion:

- the list/rendering/indexing logic is fragile;
- the crash doesn't just depend on exotic Unicode or corrupted metadata.

### 3. Mixed Unicode

- `severity`: critical
- `confidence`: high
- `fuzzers`: `FZ-11`

Confirmed case:

- `mixed_unicode_show_all` crashes the app with `signal 11`.

Conclusion:

- Unicode confusions not only cause bad item choices;
- some mixed Unicode corpora also crash the process.### 4. Metadata malformed but accepted: `second_len_plus1`

- `severity`: critical
- `confidence`: high
- `fuzzers`: `FZ-02`, `FZ-07`, `FZ-15`

Behavior:

- `LOAD_METADATAS` is accepted;
- `pull` silently replays a corrupted nickname `gmail\0`;
- `show` and `delete` of the second item then cause the app to crash.

Conclusion:

- the app does not validate this raw input strictly enough;
- the following traversal or selection can no longer manage the state obtained.

### 5. Chaotic navigation on valid state

- `severity`: high
- `confidence`: medium
- `fuzzers`: `FZ-05`

Confirmed case:

- `single_entry_walk_seed21` crashes the app after pseudo-random navigation on a valid state containing `["sofian terki"]`.

Conclusion:

- there is at least one UI state machine fragility independent of the corpora `alpha/beta` alone.

## Dangerous case without immediate crash

### 1. Wrong item deleted or displayed

- `severity`: high
- `confidence`: high
- `fuzzers`: `FZ-09`, `FZ-11`

Confirmed cases:

- `sameprefix_19bytes_delete_third` deletes the wrong item;
- `sameprefix_19bytes_type_last_delete_second` deletes the bad item after `type`;
- `nfc_nfd_delete_second`, `nbsp_delete_second`, `zero_width_delete_second`, `bidi_delete_second` remove `plain` instead of the second requested item.

Conclusion:

- selection by position is not reliable on certain visually or textually close corpora;
- this point is dangerous even without a crash, because it can cause an unexpected deletion on the device side.

### 2. Silent corruptions via APDU

- `severity`: high
- `confidence`: high
- `fuzzers`: `FZ-03`

Confirmed cases:

- `load_partial_prefix_abandon` persists `load-alph` ;
- `load_zero_length_nonfinal_then_valid_final` also persists `load-alph` ;
- `load_valid_final_then_extra_nonfinal` empties the vault;
- `load_out_of_order_two_chunk` and `load_duplicate_first_chunk_then_final_remainder` reread `bulk-0`, `bulk-0`.

Conclusion:

- the `LOAD_METADATAS` protocol is not robustly defensive;
- an interrupted or inconsistent sequence can leave an invalid persistent state.

### 3. APDU Hangs and Stuck Screens

- `severity`: high
- `confidence`: high
- `fuzzers`: `FZ-03`, `FZ-06`, `FZ-13`

Confirmed cases:

- `dump_bad_p1_payload_then_pull`
- `dump_partial_then_info_then_pull`
- `sofian_verify_prompt_doubletap`
- `multi_concurrent_reads_seed65`

Conclusion:

- some flows leave the app stuck on `Transfer metadatas ?`;
- the APDU/transport state then becomes unreliable until the timeout.

## Cases that do not reproduce the problem

### 1. `sofian terki` alone

- `severity`: control
- `confidence`: high
- `fuzzers`: `FZ-08`, `FZ-14`, `FZ-15`

Observation:

- `sofian terki` is stable in round-trip;
- `sofian_push_show_control` and `sofian_push_verify_show_control` pass;
- the simple fact of having a space in the nickname does not, in itself, explain the reset seen on a real device.### 2. Charset engine

- `severity`: control
- `confidence`: high
- `fuzzers`: `FZ-10`

Observation:

- no crashes on the tank-oriented campaign;
- the known vectors remain coherent.

Conclusion:

- confirmed bugs are not concentrated in the password generation engine.

## Specific verification: are spaces allowed?

Yes, the source of `app-passwords` indicates that spaces are supported.

Concrete elements:

- creation rejects an empty or duplicate nickname, not spaces, in [ui_passwords.c](/home/sofian/Sources/ledger-passwords-companion/build/speculos/app-passwords/src/ui/ui_passwords.c:274);
- the keyboard is not limited to single letters in [ui_passwords.c](/home/sofian/Sources/ledger-passwords-companion/build/speculos/app-passwords/src/ui/ui_passwords.c:302);
- the nickname is copied as is in [password.c](/home/sofian/Sources/ledger-passwords-companion/build/speculos/app-passwords/src/password.c:7);
- the real visible constraint is the size, with `MAX_METANAME = 20` in [types.h](/home/sofian/Sources/ledger-passwords-companion/build/speculos/app-passwords/src/types.h:8), i.e. `19` UTF-8 bytes useful for the nickname.

Conclusion:

- space is not a prohibited character;
- the companion must not treat it as an intrinsically invalid case.

## What appears to be the cause

At this point, the best diagnosis is a cluster of bugs on the Ledger app side:

1. bug in managing lists and item selection;
2. insufficient validation of certain input metadata;
3. insufficient robustness of the `LOAD/DUMP` protocol to abnormal sequences;
4. State fragility when transport APDU and UI interact at the same time.

The source contains several suspect points, without any single causal evidence on its own:

- metadata traversal based on lengths stored in [metadata.c](/home/sofian/Sources/ledger-passwords-companion/build/speculos/app-passwords/src/metadata.c:56);
- length calculations and display limits in [metadata.h](/home/sofian/Sources/ledger-passwords-companion/build/speculos/app-passwords/src/metadata.h:9);
- construction of the UI list in [ui_passwords.c](/home/sofian/Sources/ledger-passwords-companion/build/speculos/app-passwords/src/ui/ui_passwords.c:202);
- accumulation in the list buffer in [password_list.c](/home/sofian/Sources/ledger-passwords-companion/build/speculos/app-passwords/src/ui/password_list.c:61).

## Limitations and test artifacts

The cluster `restart -> password1/password2/password3` of `FZ-12` should not be interpreted raw as a product bug.

Our active Speculos build:

- `TESTING=1`
- `POPULATE=1`

in [build-passwords-app.sh](/home/sofian/Sources/ledger-passwords-companion/scripts/build-passwords-app.sh:11).

And the test source injects these entries into [app_main.c](/home/sofian/Sources/ledger-passwords-companion/build/speculos/app-passwords/src/app_main.c:44).

Conclusion:

- pollution findings after restart are useful for the harness;
- but they must be replayed without `POPULATE=1` before being treated as a product bug.## Consequence for the companion

The companion cannot repair `app-passwords`, but it can:

- reduce the risk surface before `push` real;
- refuse suspicious raw states;
- warn about high-risk corpora;
- keep `push` and `verify` separate;
- concentrate stress campaigns on Speculos before any hardware writing.

The concrete plan is documented in:

- [companion-mitigation-plan.md](/home/sofian/Sources/ledger-passwords-companion/docs/companion-mitigation-plan.md:1)
