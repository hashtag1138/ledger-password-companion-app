# Plan d'implémentation détaillé

## Vision

Créer une app companion Android pour Ledger Passwords qui gère uniquement les metadata/nicknames. Le Ledger reste l'autorité cryptographique et le seul composant capable de générer/taper les mots de passe finaux.

Le projet est découpé en trois parties :

1. logique métier pure ;
2. CLI PC pour tester offline et avec Ledger ;
3. UI Android simple pour usage quotidien.

## Principe fondamental

L'app Ledger Passwords ne fournit pas d'APDU pour ajouter, supprimer ou modifier une entrée individuellement. Elle expose un dump complet et un load complet des metadata. Les opérations CRUD doivent donc être faites localement sur un modèle métier, puis exportées en réécrivant tout le bloc metadata du Ledger.

Conséquence UX importante : renommer un nickname n'est pas un simple changement de libellé. Le nickname participe à la dérivation déterministe du mot de passe côté Ledger. Renommer `gmail` en `google` produira un autre mot de passe.

## Phase 0 — cadrage et socle repo

Objectifs :

- créer le repository multi-module ;
- isoler la logique métier du protocole Ledger ;
- documenter le format metadata et les flux ;
- préparer le projet pour Codex.

Livrables :

- `core/` ;
- `ledger-protocol/` ;
- `cli/` ;
- `android-app/` ;
- docs ;
- fixtures.

Critères d'acceptation :

- le repository s'ouvre dans IntelliJ/Android Studio ;
- les modules JVM sont indépendants d'Android ;
- les TODO sont explicites et localisés ;
- les invariants Ledger sont écrits dans les docs et dans le code.

## Phase 1 — logique métier pure

Module concerné : `core`.

À implémenter/finaliser :

- `PasswordIdentifier` ;
- `CharsetFlag` ;
- `CharsetPolicy` ;
- `Vault` ;
- `VaultEditor` ;
- `VaultValidator` ;
- `VaultDiff`.

Règles :

- nickname non vide ;
- nickname max `19` octets UTF-8 ;
- doublons interdits ;
- charsets entre `0x00` et `0xFF` ;
- capacité totale <= `storageSize`, par défaut `4096` ;
- limite prudente de `178` entrées ;
- les notes locales ne sont jamais exportées vers Ledger.

Tests unitaires requis :

- ajout valide ;
- doublon rejeté ;
- nickname vide rejeté ;
- nickname de 19 octets accepté ;
- nickname de 20 octets rejeté ;
- nickname Unicode validé en octets, pas en caractères ;
- rename averti/documenté ;
- diff added/removed/changed.

## Phase 2 — codec metadata Ledger

Module concerné : `ledger-protocol`.

À implémenter/finaliser :

- `MetadataCodec.decode(raw)` ;
- `MetadataCodec.encode(vault)` ;
- gestion des entrées actives ;
- gestion des entrées effacées en mode lecture ;
- compactage à l'export normal ;
- conversion charsets bitmask <-> noms ;
- hex utils ;
- corruption reporting.

Format brut :

```text
[length: 1 byte] [kind: 1 byte] [charsets: 1 byte] [nickname bytes...]
```

`length = 1 + nicknameByteLength`, car l'octet charset fait partie de la donnée.

Tests requis :

- decode fixture existante ;
- encode -> decode roundtrip ;
- raw 4096 bytes avec padding zéro ;
- entrée effacée `kind=0xFF` visible dans `erasedEntries` ;
- corruption si `length > 20` ;
- corruption si offset dépasse la taille du buffer ;
- export compact sans entries effacées.

## Phase 3 — backup JSON compatible Ledger Web UI

Module concerné : `ledger-protocol`.

À finaliser :

- `BackupJsonCodec.fromJson` ;
- `BackupJsonCodec.toJson` ;
- compatibilité `parsed` ;
- compatibilité `nicknames_erased_but_still_stored` ;
- compatibilité `corruptions_encountered` ;
- compatibilité `raw_metadatas` ;
- champs companion additionnels ignorables.

Format recommandé :

```json
{
  "format": "ledger-passwords-companion.v1",
  "storage_size": 4096,
  "app": { "name": "Passwords", "version": "unknown" },
  "parsed": [
    { "nickname": "github", "charsets": ["UPPERCASE", "LOWERCASE", "NUMBERS"] }
  ],
  "nicknames_erased_but_still_stored": [],
  "corruptions_encountered": [],
  "raw_metadatas": "..."
}
```

Tests requis :

- import d'un backup d'exemple ;
- export lisible ;
- unknown keys ignorées ;
- `ALL_SETS` reconnu ;
- JSON -> Vault -> raw -> JSON stable au niveau fonctionnel.

## Phase 4 — client APDU et fake transport

Module concerné : `ledger-protocol`.

À implémenter/finaliser :

- `LedgerTransport` ;
- `ApduResponse` ;
- `LedgerPasswordsClient.getAppInfo()` ;
- `LedgerPasswordsClient.getAppConfig()` ;
- `LedgerPasswordsClient.dumpMetadatas()` ;
- `LedgerPasswordsClient.loadMetadatas(raw)` ;
- `FakeLedgerTransport`.

APDU à supporter :

| Action | CLA | INS | P1 | P2 | Data |
|---|---:|---:|---:|---:|---|
| App info | `0xB0` | `0x01` | `0x00` | `0x00` | vide |
| Config | `0xE0` | `0x03` | `0x00` | `0x00` | vide |
| Dump | `0xE0` | `0x04` | `0x00` | `0x00` | vide |
| Load chunk | `0xE0` | `0x05` | `0x00` ou `0xFF` | `0x00` | chunk <= 255 |

Status words :

- `0x9000` : succès ;
- `0x6985` : action annulée ;
- `0x6A86` : mauvais P1/P2 ;
- `0x6A87` : mauvaise longueur ;
- `0x6D00` : INS non supportée ;
- `0x6E00` : CLA non supportée ;
- `0x6F10` : erreur de parsing metadata.

Tests requis :

- fake `getAppInfo` ;
- fake `getAppConfig` ;
- fake dump 4096 bytes par chunks ;
- fake load 4096 bytes par chunks de 255 ;
- load puis dump identique.

## Phase 5 — CLI offline

Module concerné : `cli`.

Commandes MVP :

```bash
ledger-pw help
ledger-pw file list backup.json
ledger-pw file validate backup.json
ledger-pw file add backup.json github --charset upper,lower,numbers --out backup2.json
ledger-pw file delete backup.json github --out backup2.json
ledger-pw file rename backup.json old new --out backup2.json
ledger-pw file edit backup.json github --charset all --out backup2.json
ledger-pw file export-raw backup.json --out metadata.bin
```

Critères d'acceptation :

- aucune connexion Ledger nécessaire ;
- erreurs lisibles ;
- `--out` obligatoire pour éviter d'écraser par accident ;
- sortie table simple ;
- option JSON machine-readable à ajouter plus tard.

## Phase 6 — CLI avec device réel ou émulateur

Transport à ajouter :

- `SpeculosTransport` pour tests fonctionnels ;
- `PcHidLedgerTransport` pour vrai Ledger USB/HID ;
- éventuellement transport via une lib Ledger JS/Java existante si le choix est validé.

Commandes :

```bash
ledger-pw device info
ledger-pw device pull --out backup.json
ledger-pw device push backup.json
ledger-pw device diff backup.json
ledger-pw device verify backup.json
```

Flow `push` :

1. lire backup JSON ;
2. valider ;
3. connecter Ledger ;
4. vérifier app name = `Passwords` ;
5. lire config ;
6. encoder raw ;
7. dumper device pour diff ;
8. demander confirmation CLI ;
9. envoyer `LOAD_METADATAS` par chunks ;
10. relire le device ;
11. comparer raw attendu vs raw relu.

## Phase 7 — Android MVP offline

Module concerné : `android-app`.

Écrans :

- `HomeScreen` ;
- `IdentifierListScreen` ;
- `EditIdentifierScreen` ;
- `ImportExportScreen` ;
- `SettingsScreen` minimal.

Fonctions :

- stockage local interne ;
- import/export JSON via Storage Access Framework ;
- liste + recherche ;
- add/delete/edit/rename ;
- validation inline ;
- avertissement rename.

Critères d'acceptation :

- utilisable sans Ledger ;
- aucune permission réseau ;
- pas de telemetry ;
- backup exporté explicitement par l'utilisateur.

## Phase 8 — Android USB Ledger

À implémenter :

- `AndroidUsbLedgerTransport` ;
- détection USB host ;
- permission Android ;
- claim interface ;
- endpoints IN/OUT ;
- framing APDU Ledger HID ;
- écran sync ;
- pull ;
- push ;
- verify post-push ;
- gestion déconnexion.

Flow pull :

1. Ledger branché ;
2. app Passwords ouverte ;
3. Android demande permission USB ;
4. `getAppInfo` ;
5. `getAppConfig` ;
6. `dumpMetadatas` ;
7. Ledger demande approbation ;
8. parse + affiche diff/remplacement.

Flow push :

1. backup automatique local ;
2. diff local vs device ;
3. confirmation Android ;
4. Ledger demande approbation ;
5. upload chunks ;
6. verify par dump ;
7. succès ou rollback manuel via backup.

## Phase 9 — durcissement

À tester :

- Nano S ;
- Nano S Plus ;
- Nano X en USB ;
- câble OTG ;
- refus de permission USB ;
- app Ledger non ouverte ;
- action annulée sur Ledger ;
- déconnexion pendant dump ;
- déconnexion pendant load ;
- backup corrompu ;
- nom trop long ;
- stockage plein ;
- Unicode ;
- charsets custom.

À ajouter :

- crash-safe local backups ;
- logs expurgés ;
- instrumentation tests ;
- screenshots de validation ;
- CI GitHub Actions ;
- release debug signée localement.

## Hors MVP

À ne pas faire au départ :

- génération/affichage de mots de passe sur Android ;
- stockage de secrets ;
- cloud sync ;
- autofill Android ;
- BLE ;
- multi-vault complexe ;
- sync automatique sans diff ;
- telemetry contenant les nicknames.
