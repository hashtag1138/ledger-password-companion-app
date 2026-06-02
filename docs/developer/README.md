# Documentation développeur

Cette section est destinée à l'étude du code, aux tests et à l'investigation.

## Lire dans cet ordre

1. [Architecture et étude du code source](./architecture.md)
2. [Builds et tests](./testing.md)
3. [Fuzzing et campagnes Speculos](./fuzzing.md)

## Références techniques existantes

- [TECHNICAL_CHOICES.md](../../TECHNICAL_CHOICES.md)
- [Protocole Ledger Passwords](../ledger-passwords-protocol.md)
- [Flows de synchronisation](../sync-flows.md)
- [Sécurité produit](../security.md)
- [ADR 0001](../adr/0001-module-boundaries.md)
- [ADR 0002](../adr/0002-no-password-generation-on-phone.md)

## Références d'investigation

- [Rapport de findings fuzzing](../fuzzing-findings-report.md)
- [Plan de mitigation côté companion](../companion-mitigation-plan.md)
- [Tracker détaillé de fuzzing](../fuzzing-tracker.md)
- [Mode d'emploi Speculos](../speculos-testing.md)

## Intentions de cette doc

Elle doit permettre à un nouveau contributeur de :

- comprendre comment le repo est structuré ;
- savoir où lire le code en premier ;
- reproduire la baseline de tests ;
- relancer les campagnes fuzz sans repartir de zéro.
