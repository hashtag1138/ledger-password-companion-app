# Sécurité

## Ce que l'app fait

- Elle stocke et édite des nicknames/identifiants.
- Elle encode/décode le bloc metadata Ledger Passwords.
- Elle transfère ce bloc vers ou depuis le Ledger après approbation physique.

## Ce que l'app ne fait pas

- Elle ne demande jamais la recovery phrase.
- Elle ne dérive jamais les mots de passe finaux.
- Elle n'affiche jamais les mots de passe finaux.
- Elle ne remplace pas le Ledger comme racine de confiance.
- Elle ne synchronise pas automatiquement vers le cloud.

## Données sensibles

Les nicknames ne sont pas des secrets cryptographiques, mais ils peuvent révéler des services utilisés : `bank`, `gmail`, `github`, etc.

Traitement recommandé :

- stockage local privé ;
- export uniquement par action explicite ;
- pas de logs contenant les nicknames en production ;
- pas de telemetry ;
- pas de réseau ;
- backup automatique local avant écriture sur Ledger.

## Risques principaux

### Mauvais renommage

Renommer un nickname change le mot de passe généré par le Ledger.

Mitigation :

- warning visible dans l'UI ;
- diff avant push ;
- confirmation avant écriture.

### Écrasement accidentel du Ledger

Mitigation :

- backup local automatique avant push ;
- diff device vs local ;
- confirmation Android/CLI ;
- approbation physique Ledger ;
- vérification par dump après écriture.

### Backup corrompu

Mitigation :

- validation stricte ;
- parse tolerant mais export strict ;
- affichage des corruptions ;
- refus de push si corruption bloquante.

### Téléphone compromis

Le téléphone pourrait modifier les nicknames et pousser une mauvaise liste si l'utilisateur valide sur le Ledger.

Mitigation raisonnable :

- diff lisible ;
- aucun mode sync automatique ;
- affichage du nombre d'entrées modifiées ;
- backup récupérable.

## Permissions Android

MVP :

- USB host ;
- accès fichiers via Storage Access Framework uniquement sur action utilisateur.

Pas de permission réseau en V1.
