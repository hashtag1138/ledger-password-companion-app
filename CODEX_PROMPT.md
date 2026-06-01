# Prompt de continuation pour Codex

Tu travailles dans le repository `ledger-passwords-companion`.

Objectif global : implémenter une app companion Android pour Ledger Passwords. L'app gère uniquement les metadata/nicknames, jamais la seed, jamais les mots de passe finaux.

Contraintes incontournables :

- préserver la séparation `core` / `ledger-protocol` / `cli` / `android-app` ;
- ne pas introduire Android dans `core` ni `ledger-protocol` ;
- faire passer les tests avant d'ajouter des features ;
- respecter le format metadata Ledger : `[length][kind][charsets][nickname]` ;
- nickname max 19 octets UTF-8 ;
- storage metadata par défaut 4096 bytes ;
- charsets bitmask compatible Ledger Web UI ;
- `0x00` ou `0xFF` pour tous les charsets ;
- pas de génération ni d'affichage de mot de passe dans Android ou CLI ;
- ne pas ajouter de réseau/telemetry.

Première mission recommandée :

1. lancer `./gradlew :core:test :ledger-protocol:test` ;
2. corriger les erreurs de compilation ;
3. compléter les tests de `MetadataCodecTest` ;
4. compléter `BackupJsonCodecTest` ;
5. finaliser la CLI offline pour `list`, `validate`, `add`, `delete`, `rename`, `edit`, `export-raw`.

Ensuite :

1. implémenter `LedgerHidFraming` ;
2. implémenter `PcHidLedgerTransport` ou `SpeculosTransport` ;
3. implémenter `AndroidUsbLedgerTransport` ;
4. ajouter l'écran Android de sync pull/push avec diff.

Avant chaque push vers Ledger, l'app doit :

- valider le vault ;
- dumper le device ;
- afficher un diff ;
- créer un backup local ;
- demander confirmation utilisateur ;
- demander validation physique sur le Ledger ;
- relire le device après écriture et comparer.
