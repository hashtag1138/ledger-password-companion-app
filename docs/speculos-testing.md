# Tests via Speculos

Speculos est le bon environnement pour rejouer le chemin APDU de l'app Passwords sans toucher un vrai Ledger.

Ce repo a déjà un transport TCP dédié côté protocole et la CLI parle déjà Speculos par défaut. Ce document ajoute le mode d'emploi manquant autour de l'émulateur.

## Ce que Speculos couvre ici

- le `getAppInfo` et `getAppConfig` ;
- la lecture `dumpMetadatas` ;
- l'écriture `loadMetadatas` ;
- les prompts de confirmation de l'app Passwords ;
- les vérifications CLI `pull`, `push`, `verify`, `diff`.

## Ce que Speculos ne remplace pas

- le stack USB Android ;
- la sélection d'interface HID ou bulk sur device réel ;
- les timings hardware réels ;
- certains comportements firmware comme les watchdogs.

Après l'incident observé pendant `push` Android sur vrai device, le chemin d'écriture doit d'abord être rejoué sous Speculos. Le vrai Ledger doit rester réservé au smoke test matériel en lecture seule tant que l'incident n'est pas compris.

## Pré-requis

Il faut disposer du binaire `app.elf` de l'app Ledger Passwords. Ce repo ne l'embarque pas.

Tu peux ensuite :

- soit installer Speculos localement ;
- soit utiliser l'image Docker officielle `ghcr.io/ledgerhq/speculos`.

## Builder l'`app.elf`

Le repo inclut maintenant un helper qui clone `LedgerHQ/app-passwords` et le build dans l'image Docker officielle Ledger :

```bash
scripts/build-passwords-app.sh
```

Le binaire généré est un build de test avec `TESTING=1 POPULATE=1`. Par défaut il sort en :

```text
build/speculos/app-passwords/bin/app.elf
```

Tu peux récupérer juste le chemin :

```bash
scripts/build-passwords-app.sh --print-path
```

## Lancer l'émulateur

Le wrapper du repo choisit automatiquement le binaire local `speculos` si présent, sinon Docker.

```bash
scripts/run-speculos-passwords.sh /chemin/vers/app.elf
```

Par défaut :

- modèle : `nanosp`
- affichage : `headless`
- API/Web UI : `http://127.0.0.1:5000`
- APDU TCP : `127.0.0.1:9999`
- nom/version exposés : `Passwords:0.0.0`

Exemples :

```bash
scripts/run-speculos-passwords.sh /chemin/vers/app.elf --display text
scripts/run-speculos-passwords.sh /chemin/vers/app.elf --docker --sdk 1.0.3
scripts/run-speculos-passwords.sh /chemin/vers/app.elf --display headless --vnc-port 41000
```

Si l'app demande une confirmation, utilise :

- la Web UI sur `http://127.0.0.1:5000` ;
- ou le mode `--display text` ;
- ou des règles d'automation Speculos passées après `--`.

Exemple de passthrough :

```bash
scripts/run-speculos-passwords.sh /chemin/vers/app.elf -- --automation file:rules.json
```

## Smoke test CLI

Avec Speculos déjà lancé :

```bash
scripts/speculos-smoke.sh
```

Ce script :

- build la CLI si nécessaire ;
- attend que le port TCP de Speculos soit ouvert ;
- lance `device info` ;
- fait un `device pull` dans un dossier temporaire ;
- vérifie immédiatement le contenu relu avec `device verify`.

Si tu viens juste de lancer un build de test `app-passwords` dans Speculos, tu peux aussi :

```bash
scripts/speculos-smoke.sh --api-port 5000 --first-run --auto-approve
```

Ça fait deux choses utiles pour `app-passwords` :

- valide le disclaimer de premier lancement ;
- choisit `QWERTY` ;
- lit l'écran Speculos et appuie sur `both` quand `Transfer metadatas ?` ou `Overwrite metadatas ?` apparaît.

Pour rejouer aussi le chemin d'écriture sur l'émulateur :

```bash
scripts/speculos-smoke.sh --api-port 5000 --auto-approve --with-push
```

Le `--with-push` reste confiné à Speculos. Il n'utilise jamais `--hid`.

## Test Android sur émulateur

L'app Android sait aussi parler à Speculos en TCP. Sur un AVD Android standard, le host local du PC est exposé en `10.0.2.2`.

Le chemin automatisé recommandé est :

```bash
scripts/android-emulator-speculos-test.sh --first-run
```

Ce script :

- détecte le premier `emulator-*` connecté ;
- démarre un auto-approver Speculos côté host ;
- vide les données de l'app sur l'émulateur ;
- lance `:android-app:connectedDebugAndroidTest` contre `10.0.2.2:${SPECULOS_APDU_PORT:-10100}`.

Par défaut, l'auto-approver ne regarde que l'écran courant Speculos et gère la séquence :

- `Transfer metadatas ?` ou `Overwrite metadatas ?` : appui sur `right` ;
- `Approve` : appui sur `both`.

Si tu veux garder les données locales de l'app entre deux runs :

```bash
scripts/android-emulator-speculos-test.sh --keep-app-data
```

Pour un test manuel sur l'émulateur :

1. installe l'APK ;
2. ouvre l'écran de sync ;
3. choisis `Speculos` ;
4. laisse `10.0.2.2` et `10100` ;
5. utilise `Rafraîchir`, puis `Importer`, `Comparer`, `Exporter`, `Vérifier`.

## Commandes CLI directes

La CLI utilise Speculos TCP par défaut, donc ces commandes ciblent déjà l'émulateur :

```bash
./cli/build/install/ledger-pw/bin/ledger-pw device info
./cli/build/install/ledger-pw/bin/ledger-pw device pull --out /tmp/speculos-backup.json
./cli/build/install/ledger-pw/bin/ledger-pw device verify /tmp/speculos-backup.json
./cli/build/install/ledger-pw/bin/ledger-pw device push test-fixtures/backup-example.json
./cli/build/install/ledger-pw/bin/ledger-pw device diff test-fixtures/backup-example.json
```

Tu peux aussi changer l'hôte ou le port :

```bash
./cli/build/install/ledger-pw/bin/ledger-pw device info --server 127.0.0.1 --port 9999
```

## Diagnostic recommandé après l'incident Android

Le bon ordre de reprise est :

1. rejouer `pull` puis `verify` sous Speculos ;
2. rejouer `push` puis `verify` sous Speculos ;
3. comparer les prompts vus dans Speculos avec ceux du vrai device ;
4. seulement ensuite reprendre les tests hardware, en lecture seule d'abord.
