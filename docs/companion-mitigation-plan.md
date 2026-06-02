# Plan de Mitigation Côté Companion

Ce document décrit ce que le companion peut faire pour réduire le risque d'écriture vers `app-passwords`, sans prétendre corriger les bugs internes de l'application Ledger.

Références :

- [fuzzing-findings-report.md](/home/sofian/Sources/ledger-passwords-companion/docs/fuzzing-findings-report.md:1)
- [fuzzing-tracker.md](/home/sofian/Sources/ledger-passwords-companion/docs/fuzzing-tracker.md:1)

## Objectifs

1. empêcher le companion de pousser des états manifestement dangereux ;
2. rendre les risques visibles à l'utilisateur avant un `push` réel ;
3. garder un mode normal utilisable pour les cas simples et valides ;
4. conserver un mode debug pour Speculos et l'investigation ;
5. transformer les reproducers trouvés en suite de régression automatique.

## Non-objectifs

- corriger le firmware ou `app-passwords` lui-même ;
- garantir qu'un `push` réel est "sans risque" tant que l'app Ledger contient des bugs connus ;
- bloquer arbitrairement les espaces, qui sont supportés par `app-passwords`.

## Principe directeur

Le companion doit avoir deux niveaux de sévérité :

1. validation de base, toujours active, pour garantir un état encodable et cohérent ;
2. politique `hardware-safe`, appliquée avant `push` réel sur Ledger, plus stricte que le protocole brut.

## Phase 0 : garde-fous immédiats

### P0.1 Validation de base plus stricte

But :

- ne jamais pousser un état déjà douteux même s'il est encodable.

À ajouter dans [VaultValidator.kt](/home/sofian/Sources/ledger-passwords-companion/core/src/main/kotlin/com/ledgerpasswords/companion/core/validation/VaultValidator.kt:1) :

- rejet des caractères de contrôle Unicode ;
- rejet des `NUL`, `TAB`, `LF`, `CR` ;
- rejet des caractères invisibles dangereux :
  - zero-width space/joiners ;
  - bidi override/isolate controls ;
- distinction entre `blank`, `leading/trailing whitespace`, `dangerous unicode`, `duplicate`.

Impact UX :

- les espaces internes restent permis ;
- les espaces de début/fin deviennent au minimum un warning fort, idéalement un rejet en mode hardware-safe.

### P0.2 Geler le push réel si le backup brut est suspect

But :

- ne pas réémettre vers un vrai Ledger un `raw_metadatas` douteux ou incohérent.

Règles :

- si un import JSON contient des `corruptions_encountered`, bloquer `push` réel ;
- si `raw_metadatas` existe mais ne correspond pas au vault décodé attendu, bloquer `push` réel ;
- si un round-trip local `decode -> encode -> decode` diverge, bloquer `push` réel ;
- si le backup vient d'un cas fuzz connu dangereux, bloquer `push` réel.

Modules cibles :

- [BackupJsonCodec.kt](/home/sofian/Sources/ledger-passwords-companion/ledger-protocol/src/main/kotlin/com/ledgerpasswords/companion/ledger/backup/BackupJsonCodec.kt:16)
- [MainActivity.kt](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/kotlin/com/ledgerpasswords/companion/android/MainActivity.kt:742)
- [Main.kt](/home/sofian/Sources/ledger-passwords-companion/cli/src/main/kotlin/com/ledgerpasswords/companion/cli/Main.kt:1)

### P0.3 Garder `push` et `verify` séparés

Statut :

- déjà en place côté Android.

Règle :

- ne jamais réintroduire de readback automatique post-write sur vrai Ledger ;
- garder `verify` comme action explicite et séparée.

## Phase 1 : politique `hardware-safe`

### P1.1 Ajouter une politique de risque explicite

Créer une couche dédiée, par exemple :

- `core/.../risk/LedgerPushRiskPolicy.kt`

Elle classera un vault en :

- `allow`
- `warn`
- `block`

Heuristiques minimales à intégrer :

- caractères invisibles ou bidi : `block`
- leading/trailing spaces : `warn` ou `block`
- noms normalisés identiques sous `NFC + trim + lowercase` : `block`
- corpus confusables visuellement : `warn`
- corpus très proches par préfixe long : `warn`
- nombre d'entrées dense proche des limites UI : `warn`
- raw importé avec anomalie précédente : `block`

### P1.2 Appliquer la politique seulement au push hardware

Principe :

- ne pas casser inutilement les flux locaux ou Speculos ;
- être plus strict uniquement avant `push` réel.

Règles :

- mode local Android : validation de base seulement ;
- `push` vers Speculos : autoriser avec warnings ;
- `push` vers vrai Ledger USB/HID : appliquer `hardware-safe`.

Modules cibles :

- [MainActivity.kt](/home/sofian/Sources/ledger-passwords-companion/android-app/src/main/kotlin/com/ledgerpasswords/companion/android/MainActivity.kt:742)
- [Main.kt](/home/sofian/Sources/ledger-passwords-companion/cli/src/main/kotlin/com/ledgerpasswords/companion/cli/Main.kt:1)

### P1.3 UX d'avertissement avant push réel

Remplacer le popup générique par un résumé de risque lisible :

- `OK` si rien de notable ;
- `Avertissement` si corpus proche, dense, ou ambigu ;
- `Bloqué` si caractères invisibles, confusables sévères, ou raw suspect.

Le popup doit mentionner le ou les motifs :

- `Espaces en début/fin`
- `Caractères invisibles`
- `Noms presque identiques`
- `Backup brut incohérent`
- `Nombre d'entrées élevé`

## Phase 2 : hygiène de données

### P2.1 Normalisation et détection de doublons logiques

Le companion ne doit pas réécrire silencieusement les nicknames, mais il peut comparer aussi :

- valeur brute ;
- forme `NFC` ;
- forme `trim()` ;
- forme `collapse whitespace` si on choisit de la suivre.

But :

- empêcher des couples comme `é` / `é` ;
- empêcher `foo bar` / `foo\u00a0bar` ;
- empêcher `zerowidth` / `zero\u200bwidth`.

### P2.2 Indicateur de capacité plus conservateur

Le calcul de capacité existe déjà dans [LedgerCapacity.kt](/home/sofian/Sources/ledger-passwords-companion/core/src/main/kotlin/com/ledgerpasswords/companion/core/LedgerCapacity.kt:1), mais il faut ajouter :

- un seuil de warning avant la limite dure ;
- un warning spécifique sur les corpus très denses ;
- un message clair quand le nombre d'entrées est techniquement encodable mais potentiellement risqué pour l'UI Ledger.

Important :

- ce n'est pas une preuve qu'une taille "élevée" fait planter ;
- c'est une mitigation prudente, pas un diagnostic de cause racine.

## Phase 3 : séparation normal / debug

### P3.1 Garder Speculos et les outils de labo hors du flux utilisateur normal

But :

- éviter de mélanger dans le même flow les usages sûrs et le labo de stress.

Règles :

- `Speculos`, host/port custom, diagnostics détaillés, overrides de sécurité restent dans `Debug` ;
- le flow normal garde seulement :
  - importer ;
  - comparer ;
  - exporter vers Ledger ;
  - vérifier.

### P3.2 Ajouter un mode `dangerous override` explicitement debug

Besoin :

- certains tests doivent pouvoir pousser quand même un corpus bloqué.

Règle :

- override possible uniquement depuis `Debug` ;
- jamais actif par défaut ;
- traçable visuellement dans l'UI et dans les logs.

## Phase 4 : test et régression

### P4.1 Transformer les meilleurs reproducers en suite courte

À garder dans une suite de régression rapide :

- `alpha_beta_push_show_second`
- `second_len_plus1_show_second`
- `sameprefix_19bytes_delete_third`
- `nfc_nfd_delete_second`
- `mixed_unicode_show_all`
- `dump_partial_then_info_then_pull`

### P4.2 Corriger l'artefact `POPULATE=1`

Action :

- ajouter une variante de build Speculos sans `POPULATE=1` pour les campagnes produit ;
- garder `POPULATE=1` seulement si on veut un état de démonstration.

Référence :

- [build-passwords-app.sh](/home/sofian/Sources/ledger-passwords-companion/scripts/build-passwords-app.sh:11)

### P4.3 Faire échouer le CI sur régression de sécurité

But :

- empêcher qu'un relâchement des garde-fous réintroduise un `push` dangereux.

À couvrir :

- tests unitaires `VaultValidator` ;
- tests de la future `LedgerPushRiskPolicy` ;
- tests Android/CLI sur les messages et blocages attendus ;
- E2E émulateur + Speculos sur cas safe.

## Plan d'implémentation recommandé

1. Étendre `VaultValidator` avec les caractères interdits et les catégories de risque.
2. Ajouter `LedgerPushRiskPolicy` dans `core`.
3. Brancher cette politique dans Android avant `push` réel.
4. Brancher la même politique dans la CLI avant `device push --hid`.
5. Ajouter le popup d'avertissement enrichi et le blocage `hardware-safe`.
6. Ajouter les tests unitaires de validation et de policy.
7. Ajouter une variante Speculos sans `POPULATE=1`.
8. Ajouter une suite courte de régression à partir des reproducers confirmés.

## Critères d'acceptation

Le plan sera considéré correctement implémenté quand :

1. un nickname avec espace interne reste autorisé ;
2. un nickname avec zero-width ou bidi control est bloqué avant `push` réel ;
3. des doublons logiques Unicode sont bloqués ou demandent un override debug ;
4. un backup brut suspect ne peut pas être poussé sur vrai Ledger ;
5. `push` réel et `verify` restent séparés ;
6. les cas safe existants restent verts sur Speculos et sur l'émulateur Android ;
7. la doc utilisateur explique clairement qu'un `push` réel est filtré par une politique de sécurité du companion.
