# Architecture et étude du code source

## Vue d'ensemble

Le repo est organisé pour séparer :

- la logique métier pure ;
- le protocole Ledger Passwords ;
- les outils de test et de ligne de commande ;
- l'intégration Android.

```text
core/             logique métier pure
ledger-protocol/  codec metadata, backups, APDU, transports
cli/              banc de test PC et automation simple
android-app/      UI Compose, stockage local, sync Android
scripts/          helpers Speculos, smoke tests, fuzzers
docs/             design, sécurité, findings, plans
```

## Responsabilités par module

### `core`

Code à lire en premier si tu veux comprendre les règles produit.

Contient notamment :

- [Vault.kt](/home/sofian/Sources/ledger-passwords-companion/core/src/main/kotlin/com/ledgerpasswords/companion/core/model/Vault.kt:1)
- [PasswordIdentifier.kt](/home/sofian/Sources/ledger-passwords-companion/core/src/main/kotlin/com/ledgerpasswords/companion/core/model/PasswordIdentifier.kt:1)
- [VaultEditor.kt](/home/sofian/Sources/ledger-passwords-companion/core/src/main/kotlin/com/ledgerpasswords/companion/core/edit/VaultEditor.kt:1)
- [VaultValidator.kt](/home/sofian/Sources/ledger-passwords-companion/core/src/main/kotlin/com/ledgerpasswords/companion/core/validation/VaultValidator.kt:1)
- [LedgerPushRiskPolicy.kt](/home/sofian/Sources/ledger-passwords-companion/core/src/main/kotlin/com/ledgerpasswords/companion/core/risk/LedgerPushRiskPolicy.kt:1)
- [VaultDiff.kt](/home/sofian/Sources/ledger-passwords-companion/core/src/main/kotlin/com/ledgerpasswords/companion/core/diff/VaultDiff.kt:1)

### `ledger-protocol`

Code à lire ensuite pour comprendre le format et le transport.

Fichiers clés :

- [MetadataCodec.kt](/home/sofian/Sources/ledger-passwords-companion/ledger-protocol/src/main/kotlin/com/ledgerpasswords/companion/ledger/metadata/MetadataCodec.kt:1)
- [BackupJsonCodec.kt](/home/sofian/Sources/ledger-passwords-companion/ledger-protocol/src/main/kotlin/com/ledgerpasswords/companion/ledger/backup/BackupJsonCodec.kt:1)
- [LedgerPasswordsClient.kt](/home/sofian/Sources/ledger-passwords-companion/ledger-protocol/src/main/kotlin/com/ledgerpasswords/companion/ledger/client/LedgerPasswordsClient.kt:1)
- [LedgerHidFraming.kt](/home/sofian/Sources/ledger-passwords-companion/ledger-protocol/src/main/kotlin/com/ledgerpasswords/companion/ledger/transport/LedgerHidFraming.kt:1)
- [SpeculosTransport.kt](/home/sofian/Sources/ledger-passwords-companion/ledger-protocol/src/main/kotlin/com/ledgerpasswords/companion/ledger/transport/SpeculosTransport.kt:1)
- [PcHidLedgerTransport.kt](/home/sofian/Sources/ledger-passwords-companion/ledger-protocol/src/main/kotlin/com/ledgerpasswords/companion/ledger/transport/PcHidLedgerTransport.kt:1)

### `cli`

Le point d'entrée est [Main.kt](/home/sofian/Sources/ledger-passwords-companion/cli/src/main/kotlin/com/ledgerpasswords/companion/cli/Main.kt:1).

Le module sert à :

- valider les codecs hors Android ;
- piloter Speculos ;
- piloter un vrai Ledger sur PC ;
- servir de base aux campagnes de fuzz.

### `android-app`

Le point d'entrée runtime est [MainActivity.kt](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/kotlin/com/ledgerpasswords/companion/android/MainActivity.kt:1).

Répartition actuelle :

- [AppShell.kt](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/kotlin/com/ledgerpasswords/companion/android/AppShell.kt:1) : UI Compose
- [SyncUiLogic.kt](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/kotlin/com/ledgerpasswords/companion/android/SyncUiLogic.kt:1) : réduction d'état et rendu du diff
- [LocalVaultStore.kt](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/kotlin/com/ledgerpasswords/companion/android/storage/LocalVaultStore.kt:1) : persistance locale
- [DiagnosticLogStore.kt](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/kotlin/com/ledgerpasswords/companion/android/storage/DiagnosticLogStore.kt:1) : logs persistants
- [AndroidUsbLedgerTransport.kt](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/kotlin/com/ledgerpasswords/companion/android/usb/AndroidUsbLedgerTransport.kt:1) : intégration USB Android

## Flows runtime principaux

### Édition locale

`AppShell` collecte l'intention utilisateur, `MainActivity` construit un nouveau `Vault`, puis le sauvegarde via `LocalVaultStore`.

### Import/export JSON

Le fichier passe par `BackupJsonCodec`, puis :

- validation stricte dans `core` ;
- conservation du raw si possible ;
- blocage ultérieur éventuel par la policy hardware-safe si le raw est suspect.

### Sync réelle Ledger

Chaîne de lecture :

`MainActivity` -> `AndroidUsbLedgerTransport` -> `LedgerPasswordsClient` -> `MetadataCodec`

Chaîne d'écriture :

`MainActivity` -> `LedgerPushRiskPolicy` -> `LedgerPasswordsClient.loadMetadatas()`

### Sync Speculos

Le flow est le même que pour un vrai Ledger, sauf que le transport est `SpeculosTransport` et que la policy est moins stricte.

## Ordre recommandé pour étudier le code

1. Lire [README.md](../../README.md) et [TECHNICAL_CHOICES.md](../../TECHNICAL_CHOICES.md).
2. Lire les modèles et validateurs dans `core`.
3. Lire `MetadataCodec` et `BackupJsonCodec`.
4. Lire `LedgerPasswordsClient` et les transports.
5. Lire `Main.kt` côté CLI pour les scénarios minimaux.
6. Lire `MainActivity` puis `AppShell` côté Android.
7. Lire les tests correspondants avant de modifier une zone.

## Ordre recommandé pour déboguer

### Bug métier

Commencer par `core`, puis `ledger-protocol`.

### Bug de sync

Commencer par :

- `LedgerPasswordsClient`
- le transport concerné
- `MainActivity`

### Bug UI Android

Commencer par :

- `AppShell`
- `SyncUiLogic`
- `MainActivity`

### Bug de risque / blocage avant push

Commencer par :

- `VaultValidator`
- `NicknameSafety`
- `LedgerPushRiskPolicy`

## Références liées

- [Protocole Ledger Passwords](../ledger-passwords-protocol.md)
- [Flows de synchronisation](../sync-flows.md)
- [Plan de mitigation côté companion](../companion-mitigation-plan.md)
