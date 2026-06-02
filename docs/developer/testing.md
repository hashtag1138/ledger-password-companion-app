# Builds et tests

## Pré-requis

- JDK 17
- Android SDK installé
- `adb`
- Docker ou `speculos` local pour les tests d'émulation
- un AVD Android si tu veux les tests instrumentés sur émulateur

Le wrapper Gradle du repo doit être utilisé :

```bash
./gradlew
```

## Baseline recommandée

La baseline à relancer après un changement significatif est :

```bash
./gradlew :core:test :ledger-protocol:test :cli:test :android-app:testDebugUnitTest :android-app:assembleDebug :android-app:assembleDebugAndroidTest :android-app:lintDebug
```

## Tests ciblés par module

### `core`

```bash
./gradlew :core:test
```

### `ledger-protocol`

```bash
./gradlew :ledger-protocol:test
```

### `cli`

```bash
./gradlew :cli:test :cli:installDist
```

Smoke CLI local :

```bash
./cli/build/install/ledger-pw/bin/ledger-pw help
./cli/build/install/ledger-pw/bin/ledger-pw file validate test-fixtures/backup-example.json
```

### `android-app`

Unit tests Android :

```bash
./gradlew :android-app:testDebugUnitTest
```

Instrumentation build :

```bash
./gradlew :android-app:assembleDebug :android-app:assembleDebugAndroidTest
```

Lint :

```bash
./gradlew :android-app:lintDebug
```

## Test Android sur émulateur avec Speculos

### Build de l'app Ledger Passwords

Build de test par défaut :

```bash
scripts/build-passwords-app.sh
```

Pour éviter l'état de démonstration injecté par `POPULATE=1` :

```bash
scripts/build-passwords-app.sh --no-populate
```

### Lancer Speculos

```bash
scripts/run-speculos-passwords.sh build/speculos/app-passwords/bin/app.elf
```

### Lancer le smoke CLI

```bash
scripts/speculos-smoke.sh --auto-approve --with-push
```

### Lancer le test E2E Android

```bash
scripts/android-emulator-speculos-test.sh
```

Ce script :

- détecte un `emulator-*` connecté ;
- démarre l'auto-approbation Speculos ;
- nettoie les données de l'app ;
- lance `connectedDebugAndroidTest`.

## Test manuel sur émulateur

Installer et lancer l'app :

```bash
adb -s emulator-5554 install -r android-app/build/outputs/apk/debug/android-app-debug.apk
adb -s emulator-5554 shell am start -n com.ledgerpasswords.companion/com.ledgerpasswords.companion.android.MainActivity
```

Puis :

1. ouvrir `Synchroniser` ;
2. choisir `Speculos` ;
3. laisser `10.0.2.2` et `10100` si tu utilises la config par défaut ;
4. tester `Importer`, `Comparer`, `Exporter`, `Vérifier`.

## Test manuel sur vrai Ledger

Le vrai hardware doit être traité comme un smoke test, pas comme un banc de stress.

Ordre recommandé :

1. `Comparer`
2. `Importer depuis Ledger`
3. `Vérifier`
4. puis seulement `Exporter vers Ledger` si le diff est compris

Rappels :

- l'app `Passwords` doit être ouverte sur le Ledger ;
- le `push` réel demande confirmation ;
- le companion ne fait pas de readback automatique après écriture ;
- le mode `Debug` contient l'override dangereux et ne doit pas être confondu avec le flow normal.

## Où regarder quand ça casse

### Logs Android persistants

```bash
adb shell run-as com.ledgerpasswords.companion cat files/ledger-debug.log
```

### Logcat Android ciblé

```bash
adb logcat -d LedgerPwUsb:V LedgerPwUi:V *:S
```

### JSON de sortie des fuzzers

La plupart des scripts écrivent un fichier `--json-out` exploitable pour relire les cas et les oracles.
