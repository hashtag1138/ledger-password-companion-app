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

Le repository n'est plus seulement un squelette. Il contient maintenant :

- les modules Gradle avec wrapper inclus ;
- les modèles métier, la validation et le diff ;
- le codec metadata Ledger et le codec `backup.json` ;
- le client APDU avec fake transport ;
- le framing HID Ledger partagé ;
- un transport Speculos TCP ;
- un transport PC USB HID ;
- une CLI offline et device (`info`, `pull`, `diff`, `push`, `verify`) ;
- des scripts de lancement et de smoke test Speculos ;
- une app Android Compose avec stockage local persistant, édition locale, import/export `backup.json`, flow USB réel de synchronisation et icône launcher personnalisée ;
- les docs de plan d'implémentation et choix techniques.

Note Android :

- l'icône launcher par défaut a été remplacée par une ressource personnalisée dérivée de [android-app/src/main/assets/app_icon_source.jpg](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/assets/app_icon_source.jpg:1) ;
- les fichiers principaux ajoutés ou mis à jour sont `AndroidManifest.xml`, `mipmap-*/ic_launcher*.png`, `mipmap-anydpi-v26/ic_launcher*.xml` et `drawable/ic_launcher_background.xml`.

Ce qui manque encore surtout côté produit :

- l'analyse complète de l'incident de reset observé sur vrai Ledger après certains `push` puis usages device-side ;
- la validation sur vrai device Ledger côté Android ;
- une stratégie de merge plus fine après lecture du device ;
- un écran de confirmation/diff avant écriture vers le Ledger.

## Démarrage

Pré-requis recommandés :

- JDK 17 ;
- wrapper Gradle inclus ;
- Android Studio récent ;
- Android SDK correspondant au `compileSdk` déclaré dans `gradle/libs.versions.toml` ;
- un Ledger avec l'app **Passwords** ouverte pour les tests device.

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

Tester l'app Passwords sans vrai Ledger via Speculos :

```bash
scripts/build-passwords-app.sh
scripts/run-speculos-passwords.sh build/speculos/app-passwords/bin/app.elf
scripts/speculos-smoke.sh --api-port 5000 --first-run --auto-approve
```

Le write path doit passer par Speculos avant toute nouvelle tentative d'écriture sur vrai device. Voir [`docs/speculos-testing.md`](docs/speculos-testing.md).

À date, le companion bloque le `push` vers un vrai Ledger si l'app Passwords détectée est antérieure à `1.3.1`. La lecture seule (`refresh`, `pull`, `compare`, `verify`) reste autorisée.

Ouvrir l'app Android :

```bash
./gradlew :android-app:assembleDebug
```

Tester l'app Android sur émulateur avec Speculos :

```bash
scripts/android-emulator-speculos-test.sh --first-run
```

Ce script :

- démarre l'auto-approbation Speculos côté host ;
- nettoie les données de l'app sur l'émulateur ;
- exécute le `connectedDebugAndroidTest` Android contre `10.0.2.2:10100`.

Pour un test manuel, l'app expose aussi ce mode dans l'écran de sync :

```bash
adb -s emulator-5554 install -r android-app/build/outputs/apk/debug/android-app-debug.apk
adb -s emulator-5554 shell am start -n com.ledgerpasswords.companion/com.ledgerpasswords.companion.android.MainActivity
```

Puis :

- choisir `Speculos` ;
- laisser `10.0.2.2` comme host dans l'émulateur Android ;
- mettre le port APDU de Speculos, par défaut `10100` dans ce repo ;
- utiliser `Rafraîchir`, puis `Importer`, `Comparer`, `Exporter`, `Vérifier`.

L'émulateur Android ne valide pas l'USB OTG réel. Il sert à tester le flow Android complet contre Speculos sans toucher un vrai Ledger.

## Invariants Ledger Passwords

Le projet encode ces contraintes dès le départ :

- stockage metadata par défaut : `4096` octets ;
- nickname utile : `19` octets UTF-8 max ;
- format d'entrée : `[length][kind][charsets][nickname bytes...]` ;
- `kind = 0x00` actif, `kind = 0xFF` effacé ;
- charset `0x00` ou `0xFF` = tous les sets ;
- pas d'APDU add/delete/update unitaire côté Ledger : on modifie localement puis on réécrit tout le bloc metadata.

## Documentation

### Utilisateur

- [`docs/user/README.md`](docs/user/README.md) : point d'entrée utilisateur.
- [`docs/user/android-app.md`](docs/user/android-app.md) : installer et utiliser l'app Android.
- [`docs/user/safety-and-limits.md`](docs/user/safety-and-limits.md) : sécurité, limites et garde-fous.

### Développeur

- [`docs/developer/README.md`](docs/developer/README.md) : point d'entrée développeur.
- [`docs/developer/architecture.md`](docs/developer/architecture.md) : design du repo et ordre de lecture du code.
- [`docs/developer/testing.md`](docs/developer/testing.md) : reproduire builds, tests et smoke tests.
- [`docs/developer/fuzzing.md`](docs/developer/fuzzing.md) : harness de fuzzing et campagnes Speculos.

### Références techniques

- [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) : plan détaillé phase par phase.
- [`TECHNICAL_CHOICES.md`](TECHNICAL_CHOICES.md) : choix techniques, dépendances, architecture.
- [`docs/speculos-testing.md`](docs/speculos-testing.md) : mode d'emploi Speculos détaillé.
- [`docs/ledger-passwords-protocol.md`](docs/ledger-passwords-protocol.md) : format metadata et APDU.
- [`docs/security.md`](docs/security.md) : sécurité, garde-fous et menaces.
- [`docs/sync-flows.md`](docs/sync-flows.md) : flows pull/push/merge.
- [`docs/ui-wireframes.md`](docs/ui-wireframes.md) : wireframes et structure UI.
- [`docs/fuzzing-findings-report.md`](docs/fuzzing-findings-report.md) : synthèse des findings fuzzing.
- [`docs/companion-mitigation-plan.md`](docs/companion-mitigation-plan.md) : plan de mitigation côté companion.
- [`CODEX_PROMPT.md`](CODEX_PROMPT.md) : prompt prêt à donner à Codex pour continuer l'implémentation.

## Licence

Apache-2.0, aligné avec le dépôt Ledger Passwords original.
