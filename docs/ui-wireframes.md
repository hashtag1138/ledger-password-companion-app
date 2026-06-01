# UI wireframes MVP

## Home

```text
Ledger Passwords Companion

[ Liste locale ]
  12 identifiants
  Dernière modification : aujourd'hui

Actions rapides
[ Importer depuis Ledger ]
[ Exporter vers Ledger ]
[ Importer backup.json ]
[ Exporter backup.json ]
```

## Liste

```text
Identifiants                                    [+]
[ Rechercher... ]

github             UPPER LOWER NUMBERS        [>]
gmail              ALL_SETS                   [>]
bank-main          UPPER LOWER NUMBERS SPECIAL[>]
```

## Édition

```text
Modifier identifiant

Nickname
[ github________________ ] 6 / 19 bytes

Charsets
[x] Majuscules
[x] Minuscules
[x] Chiffres
[ ] Tiret
[ ] Underscore
[ ] Espace
[ ] Spéciaux
[ ] Brackets

[ Enregistrer ]
```

Si rename :

```text
Attention : renommer cet identifiant changera le mot de passe généré par le Ledger.
```

## Sync Ledger

```text
Synchronisation Ledger

État : Ledger connecté, app Passwords ouverte
Storage : 4096 bytes
Entrées device : 12
Entrées locales : 13

[ Importer depuis Ledger ]
[ Comparer ]
[ Exporter vers Ledger ]
```

## Diff avant push

```text
Diff local -> Ledger

Ajoutés
+ github-work

Supprimés
- old-forum

Modifiés
~ gmail : ALL_SETS -> UPPER LOWER NUMBERS SPECIAL

[ Créer backup et écrire sur Ledger ]
```
