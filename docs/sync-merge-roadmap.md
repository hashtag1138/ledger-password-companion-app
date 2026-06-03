# Sync Merge Roadmap

Date: `2026-06-03`

## Problem

The current sync flow is destructive in both directions:

- importing from the Ledger replaces the local vault;
- exporting to the Ledger replaces the full metadata block on the device.

That is acceptable for explicit restore flows, but it is not acceptable as the main day-to-day synchronization model.

We need a user-facing `Synchronize` flow that:

1. reads the current device state;
2. computes a merge proposal with the local vault;
3. writes the merged vault back to the device;
4. verifies that the device now matches the merged result;
5. persists the merged result locally only after verification succeeds.

## Goals

- avoid silent data loss on either side;
- make `Synchronize` the primary sync action;
- keep `Import`, `Compare`, `Export`, and `Verify` available as advanced/manual tools;
- introduce a safe merge model in Phase 1, then a true bidirectional sync model in Phase 2.

## Non-Goals

- storing passwords on the phone;
- automatic conflict resolution when both sides changed the same identifier differently;
- multi-device/cloud synchronization in this milestone.

## Phase 1: Safe 2-Way Merge

### Product Goal

Introduce a non-destructive `Synchronize` button that merges obvious additions from both sides and blocks on real conflicts.

### Merge Rules

- `local only` identifier: keep it;
- `device only` identifier: keep it;
- `same nickname + same charsets`: keep it once;
- `same nickname + different charsets`: mark as conflict and stop before writing.

### Expected User Flow

1. user taps `Synchronize`;
2. companion reads the current device vault;
3. companion computes a merge plan;
4. if the plan is conflict-free, companion shows a merge summary and asks for confirmation;
5. on confirmation, companion pushes the merged vault, verifies it, then persists the merged local vault;
6. if verification fails, the local vault is not replaced.

### Implementation Checklist

- [x] add a core `VaultMergePlanner` for safe 2-way merge
- [x] model merge conflicts explicitly
- [x] render a merge summary in the sync UI
- [x] add a primary `Synchronize` action in the sync screen
- [x] keep manual `Import`, `Compare`, `Export`, `Verify` as advanced actions
- [x] execute `read -> merge -> push -> verify -> persist local`
- [x] block on conflicts before any write
- [x] reuse existing push safety policy on the merged vault
- [x] add unit tests for merge planning
- [x] add Android/UI coverage for the new sync entry point where useful

### Acceptance Criteria

- no local-only identifier is lost during sync;
- no device-only identifier is lost during sync;
- same-name, different-charset conflicts block the write path;
- local persistence happens only after successful device verification;
- manual flows still work.

## Phase 2: True Bidirectional Sync

### Product Goal

Handle adds, edits, and deletions correctly by introducing a remembered sync base.

### Design Direction

Store a local sync shadow:

- `lastSyncedVault`
- sync target metadata
- last successful sync timestamp

Then compute a 3-way merge:

- `base = lastSyncedVault`
- `local = current local vault`
- `remote = current Ledger vault`

### Expected User Flow

1. user taps `Synchronize`;
2. companion computes a 3-way merge;
3. non-conflicting changes are merged automatically;
4. true conflicts open a resolution step;
5. once resolved, companion pushes, verifies, persists local, then updates the sync shadow.

### Implementation Checklist

- [ ] add a dedicated local sync state store
- [ ] define a 3-way merge planner
- [ ] propagate deletions correctly
- [ ] detect modify-vs-delete conflicts
- [ ] detect modify-vs-modify conflicts
- [ ] add a conflict resolution UI
- [ ] update the sync shadow only after a successful end-to-end sync
- [ ] add tests for add/update/delete convergence

### Acceptance Criteria

- deletions do not get silently resurrected;
- two-sided edits on the same identifier are surfaced as conflicts;
- repeated syncs converge to the same result on both sides;
- the sync shadow is only advanced on success.

## Testing Matrix

### Phase 1

- local-only + device-only identifiers
- identical vaults
- same nickname with different charsets
- merged vault rejected by safety policy
- push succeeds but verify fails

### Phase 2

- local deletion only
- device deletion only
- local edit vs device edit
- local delete vs device edit
- stale sync shadow recovery
