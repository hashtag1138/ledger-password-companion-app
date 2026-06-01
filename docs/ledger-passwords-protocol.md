# Protocole Ledger Passwords

## Source de vérité

Ce document décrit le format actuellement utilisé par l'app Ledger Passwords `LedgerHQ/app-passwords` pour gérer les metadata/nicknames.

Le companion ne manipule pas les mots de passe générés. Il manipule uniquement les metadata.

## Storage

Taille par défaut : `4096` octets.

Chaque entrée metadata est stockée ainsi :

```text
offset + 0: length
offset + 1: kind
offset + 2: charsets
offset + 3: nickname bytes
```

`length` inclut l'octet `charsets` et le nickname :

```text
length = 1 + nicknameByteLength
```

La taille totale occupée par une entrée est :

```text
entryTotalLength = length + 2
```

## Kind

| Valeur | Sens |
|---:|---|
| `0x00` | entrée active |
| `0xFF` | entrée effacée |

Un export normal du companion doit compacter les metadata et ne pas réexporter les entrées effacées.

## Charsets

| Nom JSON | Bit |
|---|---:|
| `UPPERCASE` | `0x01` |
| `LOWERCASE` | `0x02` |
| `NUMBERS` | `0x04` |
| `MINUS` | `0x08` |
| `UNDERLINE` | `0x10` |
| `SPACE` | `0x20` |
| `SPECIAL` | `0x40` |
| `BRACKETS` | `0x80` |
| `ALL_SETS` | `0xFF` |

`0x00` doit être lu comme `ALL_SETS`.

## Fin de liste

Le premier octet `length = 0x00` marque la fin des entrées.

Le reste du buffer doit être rempli avec des zéros.

## Exemple

Entrée `password1` avec charsets `0x07` :

```text
0A 00 07 70 61 73 73 77 6F 72 64 31
```

Interprétation :

```text
0A          length = 10 = 1 charset + 9 bytes nickname
00          kind active
07          UPPERCASE + LOWERCASE + NUMBERS
70...31     "password1"
```

## APDU

| Action | CLA | INS | P1 | P2 | Data |
|---|---:|---:|---:|---:|---|
| App info | `0xB0` | `0x01` | `0x00` | `0x00` | vide |
| Config | `0xE0` | `0x03` | `0x00` | `0x00` | vide |
| Dump | `0xE0` | `0x04` | `0x00` | `0x00` | vide |
| Load chunk | `0xE0` | `0x05` | `0x00` ou `0xFF` | `0x00` | chunk <= 255 |

## Dump metadata

Le device renvoie une série de réponses :

```text
[flag][payload]
```

- `flag = 0x00` : il reste des chunks ;
- `flag = 0xFF` : dernier chunk.

La première requête déclenche une approbation utilisateur sur le Ledger.

## Load metadata

Le client envoie les metadata encodées en chunks de 255 octets max.

- `P1 = 0x00` pour les chunks intermédiaires ;
- `P1 = 0xFF` pour le dernier chunk.

La première requête déclenche une approbation utilisateur sur le Ledger.

## Status words

| SW | Sens |
|---:|---|
| `0x9000` | succès |
| `0x6985` | action annulée |
| `0x6A86` | P1/P2 incorrect |
| `0x6A87` | longueur incorrecte |
| `0x6D00` | INS non supportée |
| `0x6E00` | CLA non supportée |
| `0x6F10` | erreur parsing metadata |
