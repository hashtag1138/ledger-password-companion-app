# Flows de synchronisation

## Sources

```text
LocalVault     état édité dans l'app
DeviceVault    état lu depuis Ledger
ImportedVault  état lu depuis backup.json
```

## Pull depuis Ledger

```text
1. Brancher Ledger
2. Ouvrir app Passwords sur Ledger
3. Android demande permission USB
4. getAppInfo
5. getAppConfig
6. dumpMetadatas
7. Validation physique sur Ledger
8. Decode raw metadata
9. Afficher liste + diff avec local
10. Choisir remplacer local ou fusionner
```

## Push vers Ledger

```text
1. Valider LocalVault
2. Brancher Ledger
3. getAppInfo = Passwords
4. getAppConfig
5. dump DeviceVault pour diff
6. Afficher diff
7. Backup automatique local
8. Confirmation utilisateur dans Android/CLI
9. loadMetadatas par chunks
10. Validation physique sur Ledger
11. dump après écriture
12. Comparaison raw attendu vs raw relu
```

## Merge

Stratégie MVP :

- union par nickname ;
- conflit si même nickname mais charsets différents ;
- l'utilisateur choisit local ou device ;
- pas de détection automatique de rename.

Pourquoi pas de rename automatique : deux entrées avec charsets identiques et noms différents peuvent être deux vrais comptes différents. Le rename est fonctionnellement un delete + add du point de vue mot de passe généré.

## États UI sync

```text
Idle
UsbPermissionRequired
DeviceConnected
WrongAppOpened
WaitingForLedgerApproval
Dumping
Loading
Verifying
Success
CancelledByUser
TransportError
ValidationError
```
