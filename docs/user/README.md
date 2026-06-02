# Documentation utilisateur

Cette section est destinée à l'usage normal du companion, surtout l'app Android.

## Commencer ici

- [Utiliser l'app Android](./android-app.md)
- [Sécurité, limites et garde-fous](./safety-and-limits.md)

## Ce que fait le companion

- gérer localement la liste des identifiants/nicknames Ledger Passwords ;
- importer et exporter un `backup.json` compatible ;
- comparer l'état local et l'état du Ledger ;
- pousser un bloc metadata vers le Ledger après confirmation explicite.

## Ce que le companion ne fait pas

- il ne demande jamais la recovery phrase ;
- il ne génère pas les mots de passe finaux ;
- il ne remplace pas l'app Passwords sur le Ledger ;
- il n'exécute pas de synchronisation automatique vers un service réseau.

## Parcours recommandé

1. Préparer ou éditer les identifiants localement dans l'app.
2. Exporter un `backup.json` si tu veux une copie fichier.
3. Brancher le Ledger, ouvrir l'app `Passwords`, puis utiliser `Comparer`.
4. Si le diff est correct, utiliser `Exporter vers Ledger`.
5. Lancer `Vérifier` séparément après l'écriture.

## À propos de Debug

Le menu `Debug` existe pour les tests et l'investigation technique. Un utilisateur normal n'en a pas besoin au quotidien.

Si tu veux comprendre l'architecture, reproduire les tests ou utiliser Speculos, passe à la [documentation développeur](../developer/README.md).
