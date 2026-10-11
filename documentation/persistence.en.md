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

## Administrator commands

Use `/cmcadmin help persistence [page]` for syntax and tab completion. Commands require owner permission.

| Command | Function |
| --- | --- |
| `persistence status` | Persistence health and current/durable sequences |
| `persistence flush` | Force the exchange WAL to disk |
| `persistence snapshot save <reason>` | Checkpoint the owner ledger and pinned application stores; audit the request |
| `persistence snapshot list` / `persistence wal list` | List owner files and sizes in bytes |
| `persistence verify` | Load metadata snapshots/WAL and audit the current ledger |
| `persistence config show` | Show persistence configuration |
| `persistence config set <field> <value> <reason>` | Validate and save configuration; restart the owner to apply |
| `persistence backup list` | List complete offline backups |
| `persistence backup save <name>` / `persistence wal save <name>` | Show the corresponding offline save command |
| `persistence snapshot load <name>` / `persistence wal load <name>` | Show the offline restore command |

Prefix each command with `/cmcadmin`. REMOTE gateways execute online operations on their exchange owner. These checkpoints cover owner stores, not other gateways' local custody stores or Minecraft worlds. A checkpoint is not a transaction across separate stores. Verification may recover a physically incomplete metadata WAL tail according to normal startup rules.

## Offline backup and restore

Stop the owner **and every gateway**. Use the standalone exchange-server JAR with JDK 25:

```sh
java -jar CapitalMC-0.5.1-SNAPSHOT-exchange-server.jar --storage backup-save /path/to/capitalmc before-maintenance
java -jar CapitalMC-0.5.1-SNAPSHOT-exchange-server.jar --storage backup-list /path/to/capitalmc
java -jar CapitalMC-0.5.1-SNAPSHOT-exchange-server.jar --storage load /path/to/capitalmc before-maintenance /path/to/restored-capitalmc
java -jar CapitalMC-0.5.1-SNAPSHOT-exchange-server.jar --storage verify /path/to/restored-capitalmc
```

`backup-save` checkpoints before copying. `wal-save` flushes and copies the existing snapshot plus its WAL tail. Both save complete data directories under the sibling `capitalmc-backups/<name>/`, with a SHA-256 manifest. Loading checks the complete file set and checksums, then validates metadata and native replay in isolation. It publishes only to a previously absent directory; failure does not replace the original data. Names allow letters, digits, `_` and `-` (1–64 characters). Unpublished `.pending-*` / `.restore-pending-*` directories can remain after failure; inspect them while stopped.

The offline tool also supports `status`, `flush`, `snapshot-save`, `snapshot-list`, `wal-list`, `verify`, `config-show` and `config-set <field> <value>`. An active native writer prevents opening the tool. An individual WAL is insufficient for recovery; always preserve its matching snapshots, manifests, pins and archives. There is no command to delete active WAL segments.

Before activating restored data, restore matching gateway metadata and Minecraft world backups separately. Never combine an old ledger with newer physical custody/world state. Complete data backups include credentials and connection configuration; keep them private.
