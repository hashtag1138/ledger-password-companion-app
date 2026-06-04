# Synchronization Flows

## Sources

```text
LocalVault     state edited in the app
TargetVault    state read from the Ledger or Speculos
SyncShadow     last successful synchronized state stored locally
ImportedVault  state read from backup.json
```

## Daily Guided Synchronize Flow

```text
1. User taps Synchronize
2. Connect target and open Passwords
3. If needed, Android requests USB permission
4. getAppInfo
5. getAppConfig
6. dumpMetadatas
7. Physical approval on Ledger for the initial read
8. Decode raw metadata into TargetVault
9. If SyncShadow matches the current target kind, run a 3-way merge:
     base   = SyncShadow.lastSyncedVault
     local  = LocalVault
     target = TargetVault
10. Otherwise, fall back to the safe 2-way bootstrap merge:
     local  = LocalVault
     target = TargetVault
11. If there are no conflicts, continue automatically
12. If conflicts exist, ask the user to keep the local or target version per identifier
13. Validate the merged vault with the push safety policy
14. loadMetadatas in chunks
15. Physical approval on Ledger for the write
16. dumpMetadatas again
17. Physical approval on Ledger for the verification read
18. Compare reread target state with the expected merged vault
19. Persist the merged local vault only on success
20. Advance SyncShadow only on success
21. Show a final completion dialog
```

## Backup Import / Export

```text
Import backup.json
1. Read file through Android picker
2. Decode through BackupJsonCodec
3. Validate in core
4. Replace local vault after confirmation if needed

Export backup.json
1. Suggest a timestamped file name in the Android picker
2. Encode LocalVault through BackupJsonCodec
3. Write file through Android picker
```

## Merge Rules

Bootstrap 2-way merge:

- union by nickname;
- conflict if the same nickname has different charsets;
- no delete propagation;
- used when there is no trustworthy sync shadow for the current target.

Shadow-based 3-way merge:

- one-sided additions are kept automatically;
- one-sided updates are kept automatically;
- one-sided removals are propagated automatically;
- identical concurrent changes converge automatically;
- modify-vs-modify and modify-vs-delete differences become explicit conflicts.

Why no automatic rename: two entries with identical charsets and different names may be two genuinely different accounts. From the generated-password point of view, rename is functionally a delete + add.

## Sync UI Surface

```text
Home
  - Last sync
  - Pending local changes
  - New locally markers

Sync
  - Synchronize
  - Current state

Debug & Lab
  - transport selection
  - Speculos host/port
  - dangerous override
  - target refresh
```

## Sync Status States

```text
Idle
UsbPermissionRequired
DeviceConnected
WrongAppOpened
WaitingForLedgerApproval
Dumping
Loading
Verifying
WriteDisabled
Success
CancelledByUser
TransportError
ValidationError
```
