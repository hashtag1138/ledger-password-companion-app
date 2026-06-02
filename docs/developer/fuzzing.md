# Fuzzing et campagnes Speculos

Le fuzzing de ce repo passe par la CLI et Speculos. Le but est de casser `app-passwords` sans toucher un vrai Ledger.

## Composants du harness

Scripts principaux :

- [speculos_fuzz_lib.py](../../scripts/speculos_fuzz_lib.py)
- [fuzz_oracles.py](../../scripts/fuzz_oracles.py)
- [fuzz-oracle-consolidation.py](../../scripts/fuzz-oracle-consolidation.py)

Fuzzers par campagne :

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

## Prérequis

1. Build l'app Passwords pour Speculos.
2. Lancer Speculos.
3. S'assurer que la CLI est installée.

Exemple minimal :

```bash
scripts/build-passwords-app.sh --no-populate
scripts/run-speculos-passwords.sh build/speculos/app-passwords/bin/app.elf
./gradlew :cli:installDist
```

## Validation rapide du harness

```bash
python3 -m py_compile scripts/speculos_fuzz_lib.py scripts/fuzz_oracles.py
bash -n scripts/run-speculos-passwords.sh scripts/speculos-smoke.sh scripts/speculos-auto-approve.sh
scripts/speculos-smoke.sh --auto-approve
```

## Lancer une campagne ciblée

Exemples :

```bash
./scripts/fuzz-dangerous-nicknames.py --cases sofian_space,leading_space
./scripts/fuzz-stateful-scenarios.py --scenarios sofian_push_show_pull,multi_push_verify_pull
./scripts/fuzz-metadata-mutations.py --cases second_len_plus1 --json-out /tmp/fz02-second-len-plus1.json
./scripts/fuzz-incident-regression.py --cases alpha_beta_push_show_second --json-out /tmp/fz15-smoke.json
```

## Lancer une campagne complète

Exemples :

```bash
./scripts/fuzz-list-menus.py --json-out /tmp/fz09-full.json
./scripts/fuzz-unicode-normalization.py --json-out /tmp/fz11-full.json
./scripts/fuzz-low-level-apdu.py --json-out /tmp/fz03-full.json
./scripts/fuzz-oracle-consolidation.py --json-out /tmp/fz16-full.json
```

## Oracles disponibles

Le harness consolide notamment :

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

## Références de campagne

Vue d'ensemble :

- [Tracker détaillé](../fuzzing-tracker.md)
- [Rapport de findings](../fuzzing-findings-report.md)
- [Plan de mitigation](../companion-mitigation-plan.md)

## Reproducers prioritaires

Cas à garder sous la main :

- `alpha_beta_push_show_second`
- `second_len_plus1_show_second`
- `sameprefix_19bytes_delete_third`
- `nfc_nfd_delete_second`
- `mixed_unicode_show_all`
- `dump_partial_then_info_then_pull`

## Bonnes pratiques

- utiliser `--no-populate` pour les campagnes produit ;
- garder `POPULATE=1` seulement pour des démos ou certains harness historiques ;
- écrire chaque campagne avec `--json-out` ;
- ne pas conclure trop vite sur le vrai hardware à partir de Speculos seul ;
- revalider les reproducers clés après toute modification du companion ou du harness.
