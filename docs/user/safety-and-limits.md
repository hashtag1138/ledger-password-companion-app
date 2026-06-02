# Sécurité, limites et garde-fous

## Ce que l'app protège

Le companion aide à préparer et transférer les metadata de Ledger Passwords, mais il ne remplace pas le Ledger comme racine de confiance.

Rappels :

- la recovery phrase ne doit jamais être saisie dans cette app ;
- les nicknames peuvent être privés, mais ne sont pas des secrets cryptographiques ;
- l'app ne dérive pas les mots de passe finaux.

## Limites fonctionnelles importantes

- `19` octets UTF-8 maximum par nickname ;
- bloc metadata limité par le `storage_size` exposé par l'app Passwords ;
- le Ledger ne propose pas d'APDU d'ajout ou suppression unitaire : chaque écriture remplace tout le bloc metadata.

## Politique de sécurité avant push réel

Avant un `push` vers un vrai Ledger, le companion applique une policy plus stricte que le protocole brut.

Exemples de blocage :

- caractères de contrôle ;
- `NUL`, retours à la ligne, tabulations ;
- caractères de format Unicode dangereux, comme certains zero-width ou contrôles bidi ;
- doublons logiques après normalisation ;
- backup brut jugé incohérent ou corrompu.

Exemples d'avertissement :

- espaces en début ou fin ;
- identifiants très proches ;
- corpus dense ;
- backup importé avec signaux faibles de risque.

## Override dangereux

Un contournement existe pour les tests, mais il est séparé dans `Debug`.

Il ne doit être utilisé que pour :

- reproduire un bug ;
- tester un comportement sous supervision ;
- travailler avec Speculos ou un device de labo.

## Précaution pratique

Avant un `push` réel :

1. exporter un `backup.json` ;
2. relire le diff ;
3. confirmer le popup d'écriture ;
4. vérifier ensuite explicitement le résultat.

## Limites actuelles

Le companion réduit le risque, mais ne peut pas corriger les bugs internes de `app-passwords`.

Les campagnes de fuzzing ont déjà montré que certains états valides ou semi-valides peuvent faire crasher l'app Ledger elle-même. Pour cette raison :

- Speculos doit servir de banc d'essai avant tout test risqué ;
- le `push` réel doit rester réfléchi et explicite ;
- les flows debug restent séparés du flow normal.
