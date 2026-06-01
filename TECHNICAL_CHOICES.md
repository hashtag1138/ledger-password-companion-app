# Choix techniques

## Langage

Kotlin est utilisé partout pour partager le maximum de logique entre la CLI et Android.

Décision V1 : modules Kotlin/JVM simples pour `core`, `ledger-protocol` et `cli`, plus module Android classique pour `android-app`.

Alternative future : migrer `core` et `ledger-protocol` en Kotlin Multiplatform si une cible iOS, Desktop native ou shared Android/JVM plus stricte devient nécessaire.

## Build

- Gradle Kotlin DSL.
- Version catalog `gradle/libs.versions.toml`.
- JDK 17.
- Versions de Kotlin, Android Gradle Plugin, Compose et coroutines centralisées dans `gradle/libs.versions.toml`.

Le wrapper Gradle n'est pas inclus pour éviter d'ajouter un JAR généré dans le squelette. Après extraction, générer le wrapper avec une version compatible avec l'Android Gradle Plugin déclaré :

```bash
gradle wrapper
```

## Modules

### `core`

Responsabilité : logique métier pure.

Ne doit dépendre de rien d'autre que Kotlin stdlib et test libs.

Contient :

- modèles ;
- validation ;
- édition ;
- diff ;
- constantes fonctionnelles.

Interdit dans ce module :

- Android ;
- USB ;
- APDU ;
- fichiers ;
- JSON ;
- coroutines si non nécessaire.

### `ledger-protocol`

Responsabilité : parler le format Ledger Passwords.

Contient :

- codec metadata brut ;
- backup JSON ;
- constantes APDU ;
- client Ledger Passwords ;
- transport abstrait ;
- fake transport ;
- futur framing USB/HID.

Ne contient pas :

- UI ;
- permissions Android ;
- logique CLI ;
- stockage local Android.

### `cli`

Responsabilité : fournir un banc de test utilisable sur PC.

Contient :

- parsing d'arguments minimal ;
- commandes offline ;
- plus tard, commandes device ;
- affichage humain.

Choix V1 : pas de dépendance externe de CLI parser pour limiter le squelette. On pourra remplacer par Clikt si le CLI devient complexe.

### `android-app`

Responsabilité : UI et intégration Android.

Contient :

- Jetpack Compose ;
- USB permission/host ;
- Storage Access Framework ;
- état UI ;
- ViewModels à ajouter ;
- intégration du client Ledger via `AndroidUsbLedgerTransport`.

## Format metadata

Le format brut respecte le comportement Ledger Passwords :

```text
[length][kind][charsets][nickname bytes]
```

- `length = 1 + nicknameByteLength` ;
- `kind = 0x00` actif ;
- `kind = 0xFF` effacé ;
- `charsets = bitmask 8 bits` ;
- padding zéro jusqu'à `storageSize` ;
- premier `length = 0x00` marque la fin.

## Limites

- `storageSize = 4096` par défaut ;
- `MAX_METANAME = 20` côté Ledger ;
- nickname utile = `19` octets UTF-8 ;
- limite prudente = `178` entrées.

Le code valide en **octets UTF-8**, pas en nombre de caractères. Par défaut, l'UI devrait recommander ASCII pour éviter les surprises de saisie sur Ledger.

## Charsets

Mapping :

| Nom | Bit |
|---|---:|
| `UPPERCASE` | `0x01` |
| `LOWERCASE` | `0x02` |
| `NUMBERS` | `0x04` |
| `MINUS` | `0x08` |
| `UNDERLINE` | `0x10` |
| `SPACE` | `0x20` |
| `SPECIAL` | `0x40` |
| `BRACKETS` | `0x80` |
| `ALL_SETS` | `0xFF` |

`0x00` est lu comme `ALL_SETS` pour compatibilité.

## Protocole Ledger

APDU supportées :

| Action | CLA | INS |
|---|---:|---:|
| app info | `0xB0` | `0x01` |
| config | `0xE0` | `0x03` |
| dump metadata | `0xE0` | `0x04` |
| load metadata | `0xE0` | `0x05` |

Le device demande une approbation physique pour dump et load. L'app Android/CLI doit donc afficher un état explicite : “valide l'action sur le Ledger”.

## Transport USB

Le transport reste derrière l'interface :

```kotlin
interface LedgerTransport {
    suspend fun exchange(
        cla: Int,
        ins: Int,
        p1: Int = 0x00,
        p2: Int = 0x00,
        data: ByteArray = byteArrayOf()
    ): ApduResponse
}
```

Avantage :

- tests avec fake ;
- CLI PC ;
- Speculos ;
- Android USB ;
- futures variantes sans changer le protocole métier.

## Android

Choix V1 : USB uniquement.

Raisons :

- plus simple que BLE ;
- plus robuste pour commencer ;
- validation physique Ledger déjà obligatoire ;
- permet tests rapides en OTG.

Le manifest déclare `android.hardware.usb.host` et un filtre de vendor-id Ledger `0x2C97`.

## Sécurité

Posture :

- ne jamais demander la recovery phrase ;
- ne jamais stocker de mots de passe finaux ;
- ne jamais afficher de mots de passe finaux ;
- les nicknames sont considérés comme privés mais non secrets au niveau recovery phrase ;
- pas de réseau par défaut ;
- pas de telemetry ;
- backup local automatique avant push.

## Stratégie d'implémentation Codex

Ordre conseillé :

1. faire compiler `core` ;
2. compléter tests `core` ;
3. faire compiler `ledger-protocol` ;
4. compléter tests codec ;
5. stabiliser `BackupJsonCodec` ;
6. finaliser CLI offline ;
7. implémenter fake transport complet ;
8. ajouter transport Speculos ou PC HID ;
9. finaliser Android offline ;
10. implémenter Android USB.
