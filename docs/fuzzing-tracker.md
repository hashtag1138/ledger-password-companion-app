# Fuzzing Tracker

This document follows the families of fuzzers to be implemented against the Ledger Passwords app via the CLI and Speculos.

Objective:

- find application crashes;
- find hangs, timeouts and APDU errors;
- detect metadata corruption;
- identify scenarios that could correspond to a reset or dangerous behavior on a real device.

Work rule:

1. implement a fuzzer;
2. launch your tests;
3. update this file;
4. move on to the next one.

## Legend

- `pending`: not started
- `in_progress`: currently being implemented or investigated
- `done`: implemented and validated
- `blocked`: dependence or external limit

## Common oracles

Fuzzers can reuse one or more common oracles:

- crash of the Speculos process;
- APDU output not `0x9000` where the scenario expects a success;
- read/write timeout;
- unexpected screen;
- frozen screen;
- divergence `push -> dump -> decode`;
- corruption of reread metadata;
- exception thrown by the CLI or the harness;
- apparent restart of the app in Speculos;
- output outside the expected UI flow.

## Recommended implementation order

1. `FZ-14` corpus targeted dangerous nicknames
2. `FZ-04` stateful business scenarios
3. `FZ-02` mutation of valid metadata
4. `FZ-05` UI by button navigation
5. `FZ-10` charset-oriented
6. `FZ-08` round-trip
7. `FZ-12` multi-session persistence
8. `FZ-03` Low level APDU
9. `FZ-06` chaos timing
10. `FZ-11` Unicode/normalization
11. `FZ-09` lists/menus
12. `FZ-13` mixed APDU + UI prompts
13. `FZ-01` generative metadata
14. `FZ-07` differential
15. `FZ-15` incident-oriented regression
16. `FZ-16` consolidated multiple oracles

## Detailed backlog

### [x] FZ-01 Generative Metadata

- `status`: `done`
- `scope`: generation of `backup.json` and synthetic raw metadata
- `focus`:
  - empty nicknames, short, long, 19 exact bytes, 20+ bytes
  - Multi-byte UTF-8
  - spaces, tabs, newlines, invisible characters
  - volumes close to `storage_size`
- `entrypoint`: CLI `device push/pull/verify` via Speculos
- `oracles`:
  - APDU error
  - timeout
  - crashes
  - corrupt dump after push
- `notes`:
  - implemented in `scripts/fuzz-generated-metadata.py`
  - synthesis of valid `backup.json` corpora and synthetic raw metadata without going through the companion guardrails
  - validated campaigns:
    - `empty_control_pull`
    - `json_maxcount_177_pull`
    - `raw_storage_edge_186_show_last`
    - `raw_overlong_20byte_show`
    - `raw_blank_plain_space_show_second`
  - confirmed findings:
    - `empty_control_pull` passes without drift
    - `json_maxcount_177_pull` accepts a dense JSON corpus of `177` entries, but the final `pull` only rereads `11` entries, with the last one already corrupted to `vm010-xxxxxxxxxxx\\0\\0`
    - `raw_storage_edge_186_show_last` and `raw_overlong_20byte_show` are rejected cleanly during `LOAD_METADATAS` at the offset `4080` with `sw=0x6f10`
    - `raw_blank_plain_space_show_second` does not crash, but `show` on position `2` selects empty entry instead of `plain-target`
  - conclusion:
    - the app correctly rejects certain raw excesses close to the storage limit
    - on the other hand, large JSON corpora and lists containing empty + space still reveal corruption/truncation and poor item selection### [x] FZ-02 Mutation of valid metadata

- `status`: `done`
- `scope`: start from a valid raw then mutate the bytes
- `focus`:
  - `length` false
  - inconsistent type/kind
  - strange charset mask
  - truncated entry
  - end of buffer broken
  - non-zero padding
- `entrypoint`: APDU `LOAD_METADATAS` then UI/CLI reading scenarios
- `oracles`:
  - crashes
  -hang
  - corruption reread
  - abnormal behavior on `show/type/delete`
- `notes`:
  - implemented in `scripts/fuzz-metadata-mutations.py`
  - injection of the mutated raw via APDU `LOAD_METADATAS`, then CLI/UI verifications via Speculos
  - explicitly tested cases:
    - `first_len_plus4`
    - `first_charset_ff`
    - `second_kind_unknown`
    - `second_len_plus1`
    - `second_len_overflow`
    - `terminator_removed_tail_ff`
    - `padding_non_zero_after_terminator`
  - confirmed findings:
    - `first_len_plus4`, `second_kind_unknown`, `second_len_overflow` and `terminator_removed_tail_ff` are rejected during `LOAD_METADATAS` with `sw=0x6f10`
    - `first_charset_ff` and `padding_non_zero_after_terminator` are accepted without crash or corruption reread
    - `second_len_plus1` is accepted, then `device pull` silently rereads a corrupt nickname `gmail\\0`
    - on this same seed `second_len_plus1`, `type password` of the second item succeeds, but `show password` and `delete password` of the second item crash `app-passwords` under Speculos with `signal 11`
  - this seed `second_len_plus1` must be kept as a priority regression case

### [x] FZ-03 Low Level APDU

- `status`: `done`
- `scope`: direct fuzz of the protocol `GET_APP_CONFIG`, `DUMP_METADATAS`, `LOAD_METADATAS`
- `focus`:
  - invalid chunks
  - sizes outside of contract
  - inconsistent `p1` sequences
  - payloads too short / too long
- `entrypoint`: harness low level APDU above `SpeculosTransport`
- `oracles`:
  - unexpected status words
  - deadlocks
  - crashes
- `notes`:
  - implemented in `scripts/fuzz-low-level-apdu.py`
  - relies on the direct socket/APDU exchange added to the shared harness `scripts/speculos_fuzz_lib.py`
  - validated campaigns:
    - `load_zero_length_nonfinal_then_valid_final`
    - `load_partial_prefix_abandon`
    - `load_valid_final_then_extra_nonfinal`
    - `load_out_of_order_two_chunk`
    - `load_duplicate_first_chunk_then_final_remainder`
    - `dump_bad_p1_payload_then_pull`
    - `dump_partial_then_info_then_pull`
  - confirmed findings:
    - `load_partial_prefix_abandon` silently persists a partial state read back as `load-alph`
    - `load_zero_length_nonfinal_then_valid_final` also persists `load-alph` instead of `load-alpha` / `load-beta`
    - `load_valid_final_then_extra_nonfinal` completely empties the relu vault
    - `load_out_of_order_two_chunk` and `load_duplicate_first_chunk_then_final_remainder` do not fail cleanly and reread a corrupt state `bulk-0`, `bulk-0`
    - `dump_bad_p1_payload_then_pull` and `dump_partial_then_info_then_pull` both end up in hang/timeout with the screen stuck on `Transfer metadatas ?`
  - conclusion:
    - the APDU layer alone is enough to break the state of the app without going through the UI
    - the problems here are silent corruptions and dump deadlocks, not just screen or list selection crashes### [x] FZ-04 Stateful of business scenarios

- `status`: `done`
- `scope`: high-level realistic or semi-realistic sequences
- `focus`:
  - `push -> show password`
  - `push -> type password`
  - `push -> delete`
  - `push -> pull -> verify`
  - `load invalid -> list -> show`
- `entrypoint`: CLI + automation buttons Speculos
- `oracles`:
  - crashes
  - timeout
  - divergence between pushed state and read state
- `notes`:
  - implemented in `scripts/fuzz-stateful-scenarios.py`
  - hardened harness to use `speculos-auto-approve.sh`, dynamic ports per scenario, and `home_to_menu()` navigation tolerant of intermediate UI feedback
  - validated scenarios:
    - `sofian_push_show_pull`
    - `sofian_push_type_pull`
    - `sofian_push_delete_pull`
    - `leading_space_push_show_pull`
    - `leading_space_push_type_pull`
    - `multi_type_second_delete_first_pull`
    - `multi_push_verify_pull`
  - confirmed finding:
    - the seed `["alpha", "beta"]` in `multi_show_second_pull` causes a reproducible crash of `app-passwords` under Speculos during `device push`
    - signature observed: `Remote end closed connection without response` on the CLI side, then `The app crashed with signal 11` in the Speculos log
  - this seed must be kept as a regression case for the next fuzzers

### [x] FZ-05 UI by button navigation

- `status`: `done`
- `scope`: pseudo-random but structured supports `left/right/both`
- `focus`:
  - chaotic navigation
  - rapid validation/rejection
  - unexpected screen change
- `entrypoint`: Speculos buttons API
- `oracles`:
  - frozen screen
  - crashes
  - abnormal return home
- `notes`:
  - implemented in `scripts/fuzz-ui-navigation.py`
  - the runner prepares the state via CLI/Speculos or raw injection, executes a pseudo-random walk on the buttons, then ends with `device info` and `device pull`
  - explicitly tested cases:
    - `empty_home_walk_seed11`
    - `single_entry_walk_seed21`
    - `leading_space_walk_seed22`
    - `multi_entry_walk_seed31`
    - `mutated_second_len_plus1_walk_seed41`
  - confirmed findings:
    - `single_entry_walk_seed21` crashes `app-passwords` under Speculos after chaotic navigation on a valid state containing `["sofian terki"]`
    - signature observed: `Remote end closed connection without response` on the CLI side, then `The app crashed with signal 11` in the Speculos log
    - chaotic walks on empty state and on multi-entry vault can create new identifiers via the UI alone, for example `["A", "password1", "password2", "password3"]` and `["A", "github", "gmail", "proton"]`
    - the corrupted seed `second_len_plus1` also survives chaotic navigation and leaves an abnormal reread state `["00", "github", "gmail\\0"]`
  - the seed `single_entry_walk_seed21` must be kept as a regression case for the following fuzzers

### [x] FZ-06 Chaos timing

- `status`: `done`
- `scope`: variation of delays between APDU and buttons
- `focus`:
  - random micro-delays
  - bursts of requests
  - write sequence then read immediately
- `entrypoint`: CLI + automation Speculos
- `oracles`:
  - timeout
  - incomplete answer
  - UI/APDU status blocked
- `notes`:
  - implemented in `scripts/fuzz-chaos-timing.py`
  - this runner mixes two families of stress:
    - jittered manual approval of `Overwrite metadatas` and `Approve` prompts
    - CLI command bursts with micro-delays and read concurrency
  - explicitly tested cases:
    - `sofian_manual_push_show_seed61`
    - `sofian_push_verify_pull_burst_seed62`
    - `alpha_beta_manual_push_show_second_seed63`
    - `leading_space_manual_push_delete_seed64`
    - `multi_concurrent_reads_seed65`
  - confirmed findings:
    - `alpha_beta_manual_push_show_second_seed63` still crashes `app-passwords` under Speculos with `Remote end closed connection without response`, then `The app crashed with signal 11`
    - this crash remains reproducible even with jittery approval of the prompts, so it is not linked to too "clean" harness timing
    - `multi_concurrent_reads_seed65` reproducibly causes a timeout on the CLI side on `device info` when several reads (`info`, `pull`, `verify`, `pull`) are launched with small offsets
    - in this concurrent case, the app does not crash under Speculos, but the APDU/transport state becomes unreliable and a command remains blocked until the timeout
  - the cases `sofian_manual_push_show_seed61`, `sofian_push_verify_pull_burst_seed62` and `leading_space_manual_push_delete_seed64` pass with preservation of the expected state### [x] FZ-07 Differential

- `status`: `done`
- `scope`: replay the same corpus on several versions of `app-passwords`
- `focus`:
  - 1.3.0 vs 1.3.1 vs HEAD
  - crash divergence, output, prompts
- `entrypoint`: multi-version build + Speculos
- `oracles`:
  - behavioral mismatch
  - regression between versions
- `notes`:
  - implemented in `scripts/fuzz-differential.py`
  - compared versions:
    - `nanos_plus_1_3_0` via `nanos+_1.6.0_1.3.0_sdk_v26.0.2`
    - `nanos_plus_1_3_1` via `nanos+_1.6.1_1.3.1_sdk_v26.1.7`
    - `master`
  - replayed corpus:
    - `sofian_show_first`
    - `leading_space_delete_first`
    - `alpha_beta_show_second`
    - `second_len_plus1_show_second`
  - confirmed findings:
    - `leading_space_delete_first` changes to `1.3.0`, `1.3.1` and `master`
    - `sofian_show_first` changes to `1.3.1` and `master`
    - `alpha_beta_show_second` and `second_len_plus1_show_second` cause `app-passwords` to crash on `1.3.1` and `master` with `Remote end closed connection without response`, then `The app crashed with signal 11`
    - on `1.3.0`, the cases which suppose direct access to `Passwords list` are not comparable with the same harness: the automation falls on `Create password` then timeout
  - interpretation:
    - the campaign confirms a real divergence of UI/menu flow between `1.3.0` and `1.3.1+`
    - the crashes `alpha/beta` and `second_len_plus1` remain present at least on `1.3.1` and `master`
    - a first transient failure `master/sofian_show_first` was overturned by a green targeted repro; it should not be counted as finding

### [x] FZ-08 Round trip

- `status`: `done`
- `scope`: check invariants `encode -> load -> dump -> decode`
- `focus`:
  - conservation of nicknames
  - conservation of carts
  - stability over repeated cycles
- `entrypoint`: CLI and local codec
- `oracles`:
  - byte-level divergence
  - semantic divergence
- `implementation`:
  - shared harness: `scripts/speculos_fuzz_lib.py`
  - fuzzer: `scripts/fuzz-roundtrip.py`
- `test status`:
  - smoke validated on `sofian_space_three_cycles` and `embedded_raw_preferred_three_cycles`
  - complete run validated on `5` corpus for `3` cycles each
- `findings`:
  - no crashes or drifts observed during this campaign
  - `sofian terki` remains stable on `3` cycles `push -> verify -> pull`
  - the multi-input corpus `github/gmail/proton` exactly preserves nicknames, charsets and raw bytes
  - the UTF-8 nickname `éééééééééa` at exactly `19` bytes remains stable over `3` cycles
  - a `backup.json` with embedded `raw_metadatas` remains controlled by the raw: `parsed-loses` is ignored in favor of `raw-wins`, without drift after `3` cycles
- `notes`: good safety net for regressions; unstable seeds are to be found in the UI/stateful fuzzers, not in the pure round-trip### [x] FZ-09 Lists and menus

- `status`: `done`
- `scope`: stress of password lists and menu navigation
- `focus`:
  - lots of entries
  - very similar names
  - extreme UI widths
  - first/last indexes
- `entrypoint`: push corpus then UI navigation
- `oracles`:
  - crash when rendering
  - wrong item selected
  - inconsistent screen
- `implementation`:
  - shared harness: `scripts/speculos_fuzz_lib.py`
  - fuzzer: `scripts/fuzz-list-menus.py`
- `test status`:
  - targeted smoke validated on the harness, then complete campaign executed on `5` case
  - improvement of the harness `home_to_menu()` to return properly to the menu from the screens `show password` and `PASSWORD HAS BEEN WRITTEN`
- `findings`:
  - `dense_twelve_show_delete_last`: a dense corpus of `12` ASCII entries (`slot-01` to `slot-12`) crashes `app-passwords` with `signal 11` just after `LOAD_METADATAS`
  - `sameprefix_19bytes_delete_third`: on `sameprefixvalue-001..005`, the deletion requested in position `3` actually removes the last item `sameprefixvalue-005`
  - `sameprefix_19bytes_type_last_delete_second`: after a `type` of the last item, the deletion requested in position `2` actually removes `sameprefixvalue-103` instead of `sameprefixvalue-102`
  - `reindex_delete_first_then_last`: a corpus `reindex-01..06` crashes `app-passwords` with `signal 11`
  - `mixed_widths_show_first_middle_last`: a corpus mixing `a`, `medium-name`, a UTF-8 nickname with `19` bytes and two long ASCII entries also crashes `app-passwords` with `signal 11`
- `notes`: extremely profitable campaign; it reveals both rendering/list crashes and inconsistent item selections in flows `Delete`

### [x] FZ-10 Charset-oriented

- `status`: `done`
- `scope`: targeted exploration of charset bitmasks
- `focus`:
  - `0x00`, `0xFF`
  - isolated bits
  - rare combinations
  - invalid masks injected via mutation
- `entrypoint`: corpus generator + `show/type password`
- `oracles`:
  - crash generation
  - invalid output
  - inconsistent behavior
- `implementation`:
  - shared harness: `scripts/speculos_fuzz_lib.py`
  - fuzzer: `scripts/fuzz-charset-oriented.py`
- `test status`:
  - smoke validated on `official_vectors_gmail_and_alias` and `raw_mask00_alias_allsets`
  - complete campaign validated on `8` case without failure
- `findings`:
  - the official vectors `app-passwords` are respected exactly for `gmail` on `0x01`, `0x03`, `0x07`, `0x0F`, `0x1F`, `0x3F`, `0x7F`, `0xFF`
  - the bitmask `0x00` is indeed treated as an alias of `ALL_SETS` in the generation path: it produces exactly the same password as `0xFF`
  - on `raw_mask00_alias_allsets`, `pull` re-canonizes the charset to `ALL_SETS` on the JSON side while retaining the raw byte `0x00`
  - singleton masks behave as expected: `0x08` generates `--------------------`, `0x10` generates `____________________`, `0x20` generates `20` spaces
  - the masks `0x40`, `0x80` and the rare combo `0x81` do not crack and remain consistent in `show/type/pull`
- `notes`: no regression found on this campaign; confirmed issues remain focused on list/selection logic rather than the charset generation engine### [x] FZ-11 Unicode and standardization

- `status`: `done`
- `scope`: tricky Unicode nicknames
- `focus`:
  - NFC/NFD
  - combining marks
  - bidi
  - zero-width
  - Unicode separators
- `entrypoint`: push via CLI, then read UI
- `oracles`:
  - crashes
  - display/storage mismatch
  - broken comparison
- `implementation`:
  - shared harness: `scripts/speculos_fuzz_lib.py`
  - fuzzer: `scripts/fuzz-unicode-normalization.py`
- `test status`:
  - smoke validated on `nfc_nfd_delete_second` and `zero_width_delete_second`
  - complete campaign validated on `5` cases, with `5` reproducible failures
- `findings`:
  - `nfc_nfd_delete_second`: when requesting deletion of the second item on `["é", "é", "plain"]`, the app actually removes `plain` and leaves the NFD entry `é`
  - `nbsp_delete_second`: when requesting deletion of the second item on `["foo bar", "foo\\u00a0bar", "plain"]`, the app actually removes `plain` and leaves the `NBSP` entry
  - `zero_width_delete_second`: when requesting deletion of the second item on `["zerowidth", "zero\\u200bwidth", "plain"]`, the app actually removes `plain` and leaves the zero-width entry
  - `bidi_delete_second`: when requesting deletion of the second item on `["abc123", "abc\\u202e123", "plain"]`, the app actually removes `plain` and leaves the bidi entry
  - `mixed_unicode_show_all`: a mixed Unicode corpus crashes `app-passwords` under Speculos with `Remote end closed connection without response`, then `The app crashed with signal 11`
- `notes`: Unicode confusionables aggravate the same selection drift already seen in `FZ-09`, with the addition of a crash confirmed on mixed Unicode corpus

### [x] FZ-12 Multi-session persistence

- `status`: `done`
- `scope`: write a report, restart the app, reread
- `focus`:
  - `push -> restart -> show`
  - `push -> restart -> type`
  - `push -> restart -> list`
- `entrypoint`: Speculos revived between stages
- `oracles`:
  - crash after reboot
  - loss/corruption of metadata
- `implementation`:
  - persisted Speculos wrapper: `scripts/run-speculos-passwords.sh`
  - shared harness: `scripts/speculos_fuzz_lib.py`
  - fuzzer: `scripts/fuzz-multi-session-persistence.py`
- `test status`:
  - smoke validated on `sofian_restart_list` and `alpha_beta_restart_show_second`
  - complete campaign validated on `6` cases, with `6` reproducible failures
- `findings`:
  - `sofian_restart_list`, `sofian_restart_show_first`, `sofian_restart_type_first`: after restart, the dump reads `["sofian terki", "password1", "password2", "password3"]` instead of `["sofian terki"]`
  - `alpha_beta_restart_show_second`: after restart, the dump rereads `["alpha", "beta", "password1", "password2", "password3"]` and a `show` in position `2` displays `password1` instead of `beta`
  - `dense_twelve_restart_show_last`: a dense list of `12` ASCII entries crashes `app-passwords` after restart with `Remote end closed connection without response`, then `The app crashed with signal 11`
  - `second_len_plus1_restart_show_second`: the accepted corrupted raw survives the restart, but the dump rereads `["github", "gmail\\0", "password1", "password2", "password3"]` and a `show` in position `2` displays `password1`
- `notes`: Speculos restart with persistent NVRAM reveals systematic pollution by `password1/password2/password3`, which then combines with the selection bugs already seen on the lists### [x] FZ-13 Prompts APDU + UI mixed

- `status`: `done`
- `scope`: inject supports during APDU flows
- `focus`:
  - quick validation/rejection
  - unexpected navigation during prompt
  - exit/reentry during transfer
- `entrypoint`: async CLI + Speculos buttons API
- `oracles`:
  - blocking
  - inconsistent state
  - crashes
- `implementation`:
  - shared harness: `scripts/speculos_fuzz_lib.py`
  - fuzzer: `scripts/fuzz-apdu-ui-interleaved.py`
- `test status`:
  - smoke validated on `sofian_push_prompt_bounce_show` and `alpha_beta_push_prompt_spam_show_second`
  - complete campaign validated on `5` cases, with `3` reproducible failures
- `findings`:
  - `sofian_verify_prompt_doubletap`: a `verify` after normal push timeout in reading if we inject parasitic supports around `Approve`; the CLI fails with `error: Read timed out` and the screen gets stuck on `Transfer metadatas ?`
  - `alpha_beta_push_prompt_spam_show_second`: a valid `push` of `["alpha", "beta"]` crashes `app-passwords` during transfer when mixing APDU and buttons on prompts, with `Remote end closed connection without response`, then `The app crashed with signal 11`
  - `dense_twelve_push_prompt_spam_show_last`: same crash `signal 11` on a dense list of `12` entries if you spam the prompts during `push`
  - `sofian_push_prompt_bounce_show` remains stable despite parasitic supports
  - `leading_space_push_prompt_spam_type` also remains stable in this campaign
- `notes`: the APDU/UI mix does not break all simple cases, but it is enough to transform already fragile seeds into crashes during `push` and to block a `verify` that is nevertheless valid

### [x] FZ-14 Corpus targeted dangerous nicknames

- `status`: `done`
- `scope`: small manual corpus with high value
- `focus`:
  - `sofian terki`
  - start/end spaces
  - apostrophe, backtick, slash, backslash
  - accents
  - 19 exact bytes
  - visual collisions
- `entrypoint`: minimal push then `show/type/delete`
- `oracles`:
  - crashes
  - corruption
  - rendering divergence
- `implementation`:
  - shared harness: `scripts/speculos_fuzz_lib.py`
  - fuzzer: `scripts/fuzz-dangerous-nicknames.py`
- `test status`:
  - smoke validated on `sofian_space` for `show`, `type`, `delete`
  - smoke validated on `leading_space` for `show` and `delete`
  - unstable case observed on `leading_space` + scenario `type`
- `findings`:
  - `sofian terki` does not reproduce the reset under Speculos 1.3.1
  - `leading_space` (`" leading"`) triggered an intermittent timeout during `device push` before scenario `type`
  - repro targeted on `leading_space/type`: `2` success, `1` timeout `LOAD_METADATAS`
- `notes`: best entry point for the current incident; the case `leading_space` must be promoted to `FZ-15`

### [x] FZ-15 Incident Oriented Regression

- `status`: `done`
- `scope`: scenarios very close to the incidents observed
- `focus`:
  - `push nickname -> show password`
  - `push -> separate verify`
  - `push -> restart -> show`
  - variations around the same nickname
- `entrypoint`: dedicated incident harness
- `oracles`:
  - crashes
  - unexpected screen
  - inconsistent reread state
- `implementation`:
  - shared harness: `scripts/speculos_fuzz_lib.py`
  - fuzzer: `scripts/fuzz-incident-regression.py`
- `test status`:
  - smoke validated on `sofian_push_verify_show_control` and `alpha_beta_push_show_second`
  - complete campaign validated on `8` cases, with `5` reproducible failures
- `findings`:
  - `sofian_push_show_control` and `sofian_push_verify_show_control` pass; the minimal incident flow on a single identifier remains stable under Speculos
  - `sofian_push_restart_show_first` fails after restart: the reread state becomes `["password1", "password2", "password3", "sofian terki"]`
  - `leading_space_push_type_repeat3` passes `3/3` in this harness; the flakiness observed earlier was not reproduced on this campaign
  - `alpha_beta_push_show_second` always crashes `app-passwords` with `Remote end closed connection without response`, then `signal 11`
  - `alpha_beta_push_verify_show_second` also crashes; a separate `verify` therefore does not neutralize this minimal seed
  - `second_len_plus1_show_second` also crashes `app-passwords` with `signal 11`
  - `second_len_plus1_restart_show_second` does not crash on restart, but selects `password1` instead of `gmail\\0`
- `notes`:
  - `FZ-15` confirms that the current best minimal reproducer is `["alpha", "beta"] -> push -> show second`
  - the separation `push` / `verify` is healthy for `sofian terki`, but is not enough to protect the already fragile seeds### [x] FZ-16 Consolidated Multiple Oracles

- `status`: `done`
- `scope`: detection layer common to all fuzzers
- `focus`:
  - crash process
  - timeout
  - APDU mismatch
  - app restart
  - corruption reread
  - unexpected screen
- `entrypoint`: shared harness library
- `oracles`: n/a, this fuzzer is the oracle
- `implementation`:
  - shared library: `scripts/fuzz_oracles.py`
  - dedicated validations: `scripts/fuzz-oracle-consolidation.py`
  - plugged in integration at least in `scripts/fuzz-incident-regression.py` and `scripts/fuzz-low-level-apdu.py`
- `test status`:
  - full validation executed via `./scripts/fuzz-oracle-consolidation.py --json-out /tmp/fz16-full.json`
  - `4/4` green checks
- `findings`:
  - the healthy control `sofian_push_verify_show_control` does not trigger any parasitic oracle
  - the minimal reproducer `alpha_beta_push_show_second` triggers `speculos_crash`, `transport_closed` and `empty_screen`
  - the case of pollution after restart `sofian_push_restart_show_first` triggers `pulled_state_mismatch` and `password_pollution`
  - the hang APDU `dump_partial_then_info_then_pull` triggers `timeout` and `stuck_transfer_prompt`; it also returns `unexpected_screen`, which is consistent with a screen stuck on `Transfer metadatas ?`
- `notes`:
  - the common layer now knows how to classify the main fault families already observed: Speculos crash, transport closure, timeout, blocked screen, pollution `password1/2/3`, dump mismatch, unexpected selection
  - the implementation phase of the `16` fuzzers of the tracker is completed
