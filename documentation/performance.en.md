# Capacity and operation

[简体中文](performance.zh-CN.md) · [Shared exchange setup](cross-server.en.md)

Use a standalone exchange owner for several Minecraft servers. Player count alone does not determine capacity: orders per second, active markets, subscriptions, disk latency and retained history matter.

## Measured workload

The local TCP harness uses real persistent AdvMatch and CapitalMC order journals. Each account has three private subscriptions refreshed every 250 ms. Actions mix spot and perpetual orders, cancellation, and take-profit/stop-loss creation, modification and cancellation. One action may involve several native commands and durability barriers.

| Accounts | Gateways | Subscriptions | Actions | Completed actions/s | P99 latency |
| --- | --- | --- | --- | --- | --- |
| 1,000 | 10 | 3,000 | 2,000 | 99.82 | 95 ms |
| 3,000 | 30 | 9,000 | 7,500 | 297.15 | 650 ms |
| 5,000 | 50 | 15,000 | 15,000 | 480.82 | 1,345 ms |
| 5,000 (repeat + recovery) | 50 | 15,000 | 15,000 | 296.71 | 2,346 ms |
| 5,000 (120 s, final build) | 50 | 15,000 | 30,000 | 247.51 | 2,422 ms |

All runs completed without failed actions and passed conservation and consistency audits. The final build's 120-second run targeted 250 actions/s, restored all 34,500 command receipts after closing/reopening, and preserved the financial state hash. Revision checks skipped about 72% of snapshot rebuilds. Earlier runs lasted 20–30 seconds and targeted 100, 300 and 500 actions/s respectively. These are short tests on a Ryzen 7 7435HS, 16 GB RAM, Windows and JDK 25, with a 2 GB load JVM. Gateways share one JVM and metadata executor; traffic stays on loopback. The faster 5,000-account run overlapped a native build. Repeats show substantial variability and do not establish 500 actions/s as sustainable capacity. They do not measure thousands of Minecraft bots, WAN latency, TLS overhead, physical custody, login hashing, or long-running retention.

Run a fresh test without touching server data:

```powershell
.\gradlew.bat multiGatewayLoad '-PloadArgs=1000 30 100 D:/load-tests/fresh-run'
```

The directory must not contain an existing exchange. The harness writes `report.json`, reopens the ledger, compares its financial state hash and verifies durable application receipts after recovery.

## Work scheduling

- Identical subscriptions share a source. Batched revision checks rebuild snapshots only when relevant state may have changed; broad configuration and risk operations invalidate conservatively.
- Live first pages use subscriptions. History and cursor pages use bounded reads. Visible GUI data is polled every 250 ms; rendering follows changes, with periodic state refresh.
- TCP admission rotates between gateways. It allows 128 running requests globally, eight per gateway and at most 32 explicit reads. Waiting queues hold at most 4,096 globally and 256 per gateway. Trading commands keep their order within a gateway; reads may be reordered.
- Subscription registration has 32 in-flight control messages per channel. Replies have a separate bounded queue; obsolete pending snapshots coalesce. A persistently slow connection disconnects rather than accumulating unlimited memory.
- Metadata uses bounded executors and groups up to 64 edits into one forced WAL write. Native flush requests may share a short durability barrier. Success is returned only after the required native and application journal boundaries complete.

The owner subscription hub accepts at most 16,384 distinct queries and 1,048,576 reserved rows; each connection accepts 1,024 subscriptions. Capacity applies to unique queries, not just players. For example, 5,000 accounts with three unique queries consume 15,000 sources, close to the source limit. Do not raise limits without checking memory, latency and recovery time.

## Monitor and recover

The administration status page shows subscription failures/deferred checks, connected gateways, queued/running requests, rejected admission and coalesced updates. Compare these with operation latency, disk latency, JVM memory and Minecraft tick time. Reduce unnecessary subscription frequency and page size before adding load. Overload or a lost reply does not justify blindly submitting the same financial operation again; inspect its journal and reconciliation status.

Native request IDs are opaque signed values. Each stable gateway receives a durable namespace and counter range from the owner. Keep the owner metadata and each gateway's recovery journal together with their adjacent `.wal` files. A gateway with uncertain physical delivery must preserve its items and evidence for review.

The exchange still has one financial owner. Matching, risk and observation share that owner; state snapshot restore and full checkpoint writes remain work proportional to retained state; only the newer WAL tail is replayed. There is no replication, automatic failover or matching sharding. Long soak tests, realistic market concentration and crash/recovery tests on deployment storage are necessary before increasing production traffic. A standby must never write the same ledger concurrently; stop the previous owner and restore the complete compatible backup before manual recovery.

[Persistence thresholds and storage boundaries](persistence.en.md)
