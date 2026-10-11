# Shared exchange

[简体中文](cross-server.zh-CN.md) · [README](../README.md)

Several Minecraft servers can share **one** AdvMatch ledger. Each server keeps its own worlds, inventories, login sessions and custody journal. Trading accounts, balances, orders, positions, assets, item bindings and trading settings are shared.

## Standalone owner

Use Java 25 on Windows amd64 or Linux amd64 with glibc 2.38+. Minecraft and Fabric are not required on the owner.

```text
java --enable-native-access=ALL-UNNAMED -jar CapitalMC-0.5.1-SNAPSHOT-exchange-server.jar ./capitalmc
```

The first start creates `capitalmc/connection.json` with a random secret token. The standalone process always listens in LOCAL mode, initially at `127.0.0.1:25570`. Stop it with Ctrl+C before editing configuration. Keep the token private; connected Minecraft instances are trusted gateways with access to the exchange administration API.

In each Minecraft server's `config/capitalmc.json`, set:

```json
{
  "mode": "REMOTE",
  "host": "127.0.0.1",
  "port": 25570,
  "token": "COPY_THE_OWNER_TOKEN_FROM_CONNECTION_JSON"
}
```

Missing fields receive defaults, including a new `instanceId`. Keep that ID stable and different on every server. After the first successful connection, `expectedExchangeId` is saved automatically; a different exchange cannot silently take its place. Never clone a server's complete configuration or custody directory into another instance.

Start the owner first, then the Minecraft servers. Use the existing administrator GUI/commands on a Minecraft server to register assets and markets. Players use the usual `/trade` commands and GUI; account credentials and financial state work across connected servers. Minecraft servers must use compatible versions and item/component registries.

## A Minecraft server as owner

The default `mode` is `LOCAL`; it preserves the existing embedded exchange. Set `listen` to `true` in its `config/capitalmc.json` to accept REMOTE gateways. Other gateways use its `host`, `port` and `token`. The owner must stay running for everyone to trade. Use the standalone process when the exchange should run independently of Minecraft.

## Connections and limits

| Setting | Default | Purpose |
| --- | --- | --- |
| `mode` | `LOCAL` | Own an engine, or connect using `REMOTE` |
| `listen` | `false` | Expose an embedded LOCAL engine |
| `bind` / `host` | `127.0.0.1` | Listener address / remote destination |
| `port` | `25570` | Exchange TCP port |
| `instanceId` | Generated UUID | Stable gateway identity |
| `token` | Random 64-character secret | Authentication |
| `expectedExchangeId` | Saved on first connection | Prevent accidental connection to another ledger |
| `tls` | `false` | Enable encrypted connections |
| `keyStore` / `keyStorePassword` | Empty | Owner's PKCS12 certificate and password |
| `allowPlaintextNetwork` | `false` | Explicitly permit plaintext on a trusted private network |
| `maxConnections` | `64` | Maximum connected gateways, up to 256 |
| `maxOutstanding` | `256` | Requests awaiting responses per gateway, up to 4096 |
| `requestTimeoutMillis` | `30000` | Response deadline; timeout makes sent mutations uncertain |
| `maxFrameBytes` | `4194304` | Maximum packet size, up to 16 MiB |

Loopback connections work with the default settings. Other addresses require TLS unless both sides explicitly allow trusted-network plaintext. For TLS, set `tls: true` on both sides and configure the owner's PKCS12 `keyStore`; relative paths resolve beside its configuration file. The certificate must cover the gateway's configured hostname. Gateways use Java's default trust store; a private certificate authority can be configured with `-Djavax.net.ssl.trustStore=/absolute/path/trust.p12` and its Java trust-store password setting. Bind/firewall the endpoint for your Minecraft servers, never for player clients.

## Subscriptions and interruption

Balances, market lists, depth, live orders, positions and active conditional orders use shared subscriptions. Identical queries share an owner-side source; batched revision checks avoid rebuilding unchanged snapshots. Visible data is polled every 250 ms and GUI rendering follows changes; history refreshes every five seconds. Explicit balance checks before custody operations still read current financial state. Full lists retain cursor pagination when they exceed a subscription's bounded snapshot. See [capacity and operation](performance.en.md) for limits and measured workloads.

Disconnects make live views stale. Subscriptions reconnect automatically to the same exchange. **Orders, transfers and other mutations are never replayed automatically.** A lost response may mean the exchange already executed the request; review the originating server's operation journal and use its existing reconciliation tools. A physical withdrawal is delivered only on the server where it was requested. Chest/shulker eligibility and unfinished custody remain local to that server.

Physical custody retains the item binding and unit configuration used at confirmation. The owner checks that evidence before admitting a balance mutation. Changed bindings, disabled assets or incomplete evidence reject admission; any items already held remain on the originating gateway for reconciliation. Legacy unfinished operations without binding evidence require review rather than automatic re-crediting.

The owner is a single point of availability; this is not a replication or failover system. Subscription data is coalesced state, not a durable event stream for accounting. Throughput depends on market activity, subscriptions, persistence and the Minecraft servers; no thousand-player capacity is promised without a representative load test.

## Existing data and backups

To move an existing LOCAL exchange, stop all servers and back up the entire `capitalmc` directory and worlds. Copy that entire directory to the owner, including store pins, manifests, snapshots, WAL, archives and `exchange.id`. The owner retains native/account IDs and imports shared application settings through the existing services.

Before switching the original gateway to REMOTE, archive its account registry and native exchange store outside the active directory, including their `users.store-path` / `exchange.store-path` pins and legacy `users.dat`, `users.json`, `exchange/` if present. **Keep the gateway's services store and its pin**, including all snapshots and WAL: they contain local custody and recovery evidence. Preserve old `services.json` with `services.json.wal` until migration has been verified. Reconcile unresolved physical operations before reopening deposits or withdrawals. REMOTE refuses an active LOCAL account registry or ledger.

Back up the stopped owner and each gateway in full. World saves and exchange WAL are separate durability boundaries. Interrupted custody or configuration operations may require administrator reconciliation.

[Persistence, migration and backups](persistence.en.md)

Build outputs are `build/libs/CapitalMC-0.5.1-SNAPSHOT.jar` for Minecraft and `build/libs/CapitalMC-0.5.1-SNAPSHOT-exchange-server.jar` for the owner. `./gradlew.bat build` builds both.
