# Persistence

CapitalMC stores business data as checksummed binary WAL records and state snapshots. JSON is used only for editable connection/persistence configuration and language resources. Minecraft identity, custody, bindings and audit stay in the existing application services; AdvMatch owns financial state.

## Configuration

Edit `capitalmc/persistence.json` while stopped, then restart. Defaults:

```json
{
  "directory": "persistence",
  "exchangeSnapshotEverySequences": 10000,
  "exchangeRetainedSnapshots": 2,
  "flushEveryCommands": 256,
  "flushIntervalMilliseconds": 10,
  "metadataSnapshotEverySequences": 128,
  "metadataSnapshotWalBytes": 4194304,
  "metadataRetainedSnapshots": 2
}
```

Exchange snapshots run after the configured number of commands. Application stores checkpoint after the sequence or WAL byte threshold, whichever comes first. A metadata sequence is a committed group of edits, not an individual trade. Snapshots contain current retained state; restart restores that state and replays only the newer WAL tail. Larger thresholds reduce checkpoint frequency but increase recovery work. Full snapshots still serialize and force storage synchronously on the exchange owner or metadata IO worker; measure their latency on deployment storage.

## Files and migration

Fresh installations use `capitalmc/persistence/` with separate `exchange`, `users`, `services`, `shared-services` and `data-version` stores. Each has a WAL and a `snapshots/` folder. Binary manifests identify the required snapshot. Native `archive/` segments preserve complete committed trading history and are not read at startup; recent outbox reads skip older segments. Archive disk usage grows with history.

`*.store-path` files pin each store's relative location. Changing `directory` does not redirect an existing store to an empty ledger. To relocate, stop all owners/gateways, copy the complete store, update its pin and validate recovery before reopening. Existing native `exchange/` storage stays in place during migration.

Legacy `users.json` v1, `users.dat`, application JSON files **and their adjacent `.wal`** are imported once. Original application files remain untouched. Native replay checkpoints are replayed once and converted to state snapshots with durable historical archives. Do not downgrade using migrated data; restore a complete compatible pre-upgrade backup.

## Durability and recovery

Application writes are acknowledged after their WAL is forced. Grouped edits share a force. Native batch receipt sequence and durable sequence can differ; custody workflows wait for the explicit durable boundary. A checkpoint forces WAL, publishes history (native), publishes the full snapshot and manifest, then removes covered active WAL. Snapshot retention never removes trading archive segments.

Checksums, sequence validation and required manifests stop startup on complete corruption or missing required state. Only a physically incomplete WAL tail may be discarded. Writes with an uncertain result are quarantined; restarting is not permission to deliver items again. Minecraft world saves and the exchange WAL are separate durability boundaries, so physical custody cannot be described as atomic with the financial ledger.

Back up the entire `capitalmc` directory and relevant Minecraft worlds together while stopped, including pins, manifests, WAL, snapshots and archives. Never run two writers against one native ledger. Configuration is read at startup; there is no replication or automatic failover.
