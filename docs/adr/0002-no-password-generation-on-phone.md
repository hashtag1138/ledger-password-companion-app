# ADR 0002 — Pas de génération de mot de passe sur téléphone

## Statut

Accepté.

## Décision

Le companion Android ne génère pas et n'affiche pas les mots de passe finaux.

## Raisons

- Le Ledger doit rester l'autorité cryptographique.
- L'objectif est de gérer les identifiants, pas de remplacer l'app Passwords.
- Réduire la surface d'attaque et le risque UX.

## Conséquences

- Pas de fonction “show password”.
- Pas d'autofill Android en V1.
- Pas de demande de seed ou secret.
