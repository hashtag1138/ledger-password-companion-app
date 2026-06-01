# Ledger Passwords Companion

Squelette de repository pour une app companion Android du gestionnaire de mots de passe Ledger [`LedgerHQ/app-passwords`](https://github.com/LedgerHQ/app-passwords).

Le but du projet est de fournir une UI simple pour gérer les **identifiants/nicknames** utilisés par l'app Ledger Passwords, sans transformer le téléphone en gestionnaire de mots de passe. L'app companion ne doit pas connaître la recovery phrase et ne doit pas générer ni afficher les mots de passe finaux. Elle édite uniquement la liste des metadata, permet import/export, puis pousse le bloc metadata vers le Ledger.

## Objectif fonctionnel

- Lister les identifiants utilisés par Ledger Passwords.
- Ajouter, supprimer, renommer ou modifier la politique de caractères d'un identifiant.
- Importer depuis un backup JSON compatible avec l'outil web Ledger.
- Exporter vers un backup JSON.
- Importer depuis le Ledger connecté.
- Exporter vers le Ledger connecté.
- Garder le Ledger utilisable seul, comme prévu par l'app officielle.

## Architecture du repo

```text
ledger-passwords-companion/
  core/                    # logique métier pure, sans Android et sans USB
  ledger-protocol/          # codec metadata + client APDU Ledger Passwords
  cli/                     # CLI PC pour tests offline/online
  android-app/             # app Android Jetpack Compose
  docs/                    # plan, protocole, sécurité, sync, UI
  test-fixtures/            # backups et dumps de référence
```

## État actuel

Ce repository est un **squelette orienté implémentation Codex**. Il contient :

- les modules Gradle ;
- les modèles métier ;
- un codec metadata Ledger déjà structuré ;
- un client APDU avec transport abstrait et fake transport ;
- une CLI offline minimale ;
- une app Android Compose minimale ;
- les docs de plan d'implémentation et choix techniques.

Les transports réels USB/HID et Android USB sont volontairement laissés sous forme de TODO structurés. Ils doivent être implémentés après validation du codec et des tests offline.

## Démarrage

Pré-requis recommandés :

- JDK 17 ;
- Gradle installé localement ou wrapper généré ;
- Android Studio récent ;
- Android SDK correspondant au `compileSdk` déclaré dans `gradle/libs.versions.toml` ;
- un Ledger avec l'app **Passwords** ouverte pour les tests device.

Créer le wrapper Gradle si besoin :

```bash
gradle wrapper
```

Compiler les modules JVM :

```bash
./gradlew :core:test :ledger-protocol:test :cli:installDist
```

Lancer la CLI offline après `installDist` :

```bash
./cli/build/install/ledger-pw/bin/ledger-pw help
./cli/build/install/ledger-pw/bin/ledger-pw file list test-fixtures/backup-example.json
./cli/build/install/ledger-pw/bin/ledger-pw file validate test-fixtures/backup-example.json
```

Ouvrir l'app Android :

```bash
./gradlew :android-app:assembleDebug
```

## Invariants Ledger Passwords

Le projet encode ces contraintes dès le départ :

- stockage metadata par défaut : `4096` octets ;
- nickname utile : `19` octets UTF-8 max ;
- format d'entrée : `[length][kind][charsets][nickname bytes...]` ;
- `kind = 0x00` actif, `kind = 0xFF` effacé ;
- charset `0x00` ou `0xFF` = tous les sets ;
- pas d'APDU add/delete/update unitaire côté Ledger : on modifie localement puis on réécrit tout le bloc metadata.

## Documentation principale

- [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) : plan détaillé phase par phase.
- [`TECHNICAL_CHOICES.md`](TECHNICAL_CHOICES.md) : choix techniques, dépendances, architecture.
- [`docs/ledger-passwords-protocol.md`](docs/ledger-passwords-protocol.md) : format metadata et APDU.
- [`docs/security.md`](docs/security.md) : sécurité, garde-fous et menaces.
- [`docs/sync-flows.md`](docs/sync-flows.md) : flows pull/push/merge.
- [`docs/ui-wireframes.md`](docs/ui-wireframes.md) : écrans Android MVP.
- [`CODEX_PROMPT.md`](CODEX_PROMPT.md) : prompt prêt à donner à Codex pour continuer l'implémentation.

## Licence

Apache-2.0, aligné avec le dépôt Ledger Passwords original.
