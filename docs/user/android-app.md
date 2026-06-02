# Utiliser l'app Android

Ce guide couvre l'usage normal de l'application Android.

## Installation

Compiler l'APK :

```bash
./gradlew :android-app:assembleDebug
```

Installer sur un téléphone ou un émulateur :

```bash
adb install -r android-app/build/outputs/apk/debug/android-app-debug.apk
```

Lancer l'app :

```bash
adb shell am start -n com.ledgerpasswords.companion/com.ledgerpasswords.companion.android.MainActivity
```

## Structure de l'app

Depuis l'écran principal :

- la liste locale affiche les identifiants déjà stockés sur le téléphone ;
- toucher une ligne ouvre l'édition ;
- l'icône de copie copie rapidement le nickname ;
- le menu `…` donne accès à `Réglages`, `À propos` et `Debug`.

## Gérer les identifiants

Tu peux :

- ajouter un identifiant ;
- renommer un identifiant ;
- modifier son jeu de caractères ;
- supprimer un identifiant.

Important :

- renommer un identifiant change le mot de passe généré par le Ledger ;
- la limite utile est de `19` octets UTF-8 par nickname ;
- les espaces internes sont autorisés.

## Importer ou exporter un `backup.json`

Depuis l'écran principal :

- `Importer backup.json` remplace l'état local par le contenu du fichier ;
- `Exporter backup.json` écrit l'état local dans un fichier choisi via le sélecteur Android.

Le companion conserve si possible les `raw_metadatas` exactes du backup, mais il peut bloquer plus tard un `push` réel si le fichier contient des anomalies jugées dangereuses.

## Synchroniser avec un vrai Ledger

Pré-requis :

- Ledger branché au téléphone en USB OTG ;
- app `Passwords` ouverte sur le Ledger ;
- permission USB accordée à l'app Android ;
- version `Passwords >= 1.3.1` pour autoriser le `push` réel.

Workflow recommandé :

1. Ouvrir `Synchroniser`.
2. Utiliser `Rafraîchir` si besoin.
3. Utiliser `Importer depuis Ledger` pour relire l'état du device.
4. Utiliser `Comparer le local avec la cible`.
5. Utiliser `Exporter le local vers Ledger` seulement si le diff est bien compris.
6. Utiliser `Vérifier la cohérence finale` après l'écriture.

Le `push` réel et le `verify` restent volontairement séparés. L'app ne relance pas automatiquement une lecture après écriture.

## Messages de blocage avant push

Le companion peut refuser un `push` réel si l'état local présente un risque trop élevé, par exemple :

- caractères invisibles dangereux ;
- caractères de contrôle ;
- doublons logiques après normalisation ;
- `backup.json` brut incohérent ;
- override de sécurité requis.

Dans ce cas, lis le résumé affiché dans le popup de confirmation ou dans le message de sync.

## Utiliser Speculos sur émulateur

Le mode `Speculos` existe surtout pour les tests. Sur émulateur Android, le host local du PC est exposé en `10.0.2.2`.

En usage manuel :

1. Lancer Speculos sur le PC.
2. Ouvrir `Synchroniser`.
3. Choisir `Speculos`.
4. Laisser `10.0.2.2` comme host et `10100` comme port si tu utilises la config par défaut du repo.

Pour plus de détails, voir la [documentation développeur sur les tests](../developer/testing.md).
