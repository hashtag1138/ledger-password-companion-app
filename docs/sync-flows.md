# Synchronization Flows

## Sources

```text
LocalVault     state edited in the app
DeviceVault    state read from the Ledger
ImportedVault  state read from backup.json
```

## Pull from Ledger

```text
1. Connect Ledger
2. Open Passwords app on Ledger
3. Android requests USB permission
4. getAppInfo
5. getAppConfig
6. dumpMetadatas
7. Physical approval on Ledger
8. Decode raw metadata
9. Show list + diff against local
10. Choose replace local or merge
```

## Push to Ledger

```text
1. Validate LocalVault
2. Connect Ledger
3. getAppInfo = Passwords
4. getAppConfig
5. dump DeviceVault for diff
6. Show diff
7. Automatic local backup
8. User confirmation in Android/CLI
9. loadMetadatas in chunks
10. Physical approval on Ledger
11. dump after writing
12. Compare expected raw vs reread raw
```

## Merge

MVP strategy:

- union by nickname;
- conflict if the same nickname has different charsets;
- user chooses local or device;
- no automatic rename detection.

Why no automatic rename: two entries with identical charsets and different names may be two genuinely different accounts. From the generated-password point of view, rename is functionally a delete + add.

## Sync UI States

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
