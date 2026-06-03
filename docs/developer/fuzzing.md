# Fuzzing and Speculos Campaigns

Fuzzing in this repository goes through the CLI and Speculos. The goal is to break `app-passwords` without touching a real Ledger.

## Harness Components

Main scripts:

- [speculos_fuzz_lib.py](../../scripts/speculos_fuzz_lib.py)
- [fuzz_oracles.py](../../scripts/fuzz_oracles.py)
- [fuzz-oracle-consolidation.py](../../scripts/fuzz-oracle-consolidation.py)

Campaign fuzzers:

- `fuzz-dangerous-nicknames.py`
- `fuzz-stateful-scenarios.py`
- `fuzz-metadata-mutations.py`
- `fuzz-ui-navigation.py`
- `fuzz-chaos-timing.py`
- `fuzz-differential.py`
- `fuzz-roundtrip.py`
- `fuzz-list-menus.py`
- `fuzz-charset-oriented.py`
- `fuzz-unicode-normalization.py`
- `fuzz-multi-session-persistence.py`
- `fuzz-apdu-ui-interleaved.py`
- `fuzz-generated-metadata.py`
- `fuzz-low-level-apdu.py`
- `fuzz-incident-regression.py`

## Prerequisites

1. Build the Passwords app for Speculos.
2. Start Speculos.
3. Make sure the CLI is installed.

Minimal example:

```bash
scripts/build-passwords-app.sh --no-populate
scripts/run-speculos-passwords.sh build/speculos/app-passwords/bin/app.elf
./gradlew :cli:installDist
```

## Quick Harness Validation

```bash
python3 -m py_compile scripts/speculos_fuzz_lib.py scripts/fuzz_oracles.py
bash -n scripts/run-speculos-passwords.sh scripts/speculos-smoke.sh scripts/speculos-auto-approve.sh
scripts/speculos-smoke.sh --auto-approve
```

## Run a Targeted Campaign

Examples:

```bash
./scripts/fuzz-dangerous-nicknames.py --cases sofian_space,leading_space
./scripts/fuzz-stateful-scenarios.py --scenarios sofian_push_show_pull,multi_push_verify_pull
./scripts/fuzz-metadata-mutations.py --cases second_len_plus1 --json-out /tmp/fz02-second-len-plus1.json
./scripts/fuzz-incident-regression.py --cases alpha_beta_push_show_second --json-out /tmp/fz15-smoke.json
```

## Run a Full Campaign

Examples:

```bash
./scripts/fuzz-list-menus.py --json-out /tmp/fz09-full.json
./scripts/fuzz-unicode-normalization.py --json-out /tmp/fz11-full.json
./scripts/fuzz-low-level-apdu.py --json-out /tmp/fz03-full.json
./scripts/fuzz-oracle-consolidation.py --json-out /tmp/fz16-full.json
```

## Available Oracles

The harness notably consolidates:

- `speculos_crash`
- `transport_closed`
- `timeout`
- `stuck_transfer_prompt`
- `pulled_state_mismatch`
- `password_pollution`
- `embedded_nul_nickname`
- `unexpected_selected_nickname`
- `unexpected_screen`
- `empty_screen`

## Campaign References

Overview:

- [Detailed tracker](../fuzzing-tracker.md)
- [Findings report](../fuzzing-findings-report.md)
- [Mitigation plan](../companion-mitigation-plan.md)

## Priority Reproducers

Keep these cases close at hand:

- `alpha_beta_push_show_second`
- `second_len_plus1_show_second`
- `sameprefix_19bytes_delete_third`
- `nfc_nfd_delete_second`
- `mixed_unicode_show_all`
- `dump_partial_then_info_then_pull`

## Good Practices

- use `--no-populate` for product campaigns;
- keep `POPULATE=1` only for demos or some historical harnesses;
- write every campaign with `--json-out`;
- do not conclude too quickly about real hardware from Speculos alone;
- revalidate key reproducers after any change to the companion or the harness.
