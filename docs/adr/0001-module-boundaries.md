# ADR 0001 — Séparation stricte des modules

## Statut

Accepté.

## Décision

Le projet garde quatre modules principaux :

- `core` : métier pur ;
- `ledger-protocol` : codec + APDU ;
- `cli` : outil PC ;
- `android-app` : UI et intégration Android.

## Conséquences

- Les tests de codec et métier tournent sans Android.
- Le transport Ledger peut être fake, PC, Speculos ou Android sans modifier le métier.
- L'app Android reste un frontend et ne devient pas la source de vérité cryptographique.
