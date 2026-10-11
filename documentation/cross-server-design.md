# Shared exchange deployment

CapitalMC keeps its existing services and AdvMatch object API. LOCAL owns one native engine; REMOTE is a transport client of that engine. A separate CapitalMC exchange process hosts the same engine and account directory without Minecraft. A LOCAL Minecraft instance can expose the same endpoint for smaller installations.

The account directory and credentials have one authoritative ServerUserManager. Remote managers retain immutable account views and local player sessions, and delegate registration/authentication/credential changes to the directory. Financial state always belongs to AdvMatch. No remote Minecraft instance creates a second native engine.

Assets, item binding descriptions, display units, symbol icons/lots, issuer identity, peg configuration and access restrictions are shared application metadata. ItemStack decoding and rendering remain on each Minecraft server thread. Inventory selections, custody phases, world-save evidence, delivery, application operation journals and sessions remain instance-owned. An instance ID must be stable and unique.

Requests use a versioned, bounded binary transport with authenticated instances, optional TLS, bounded outstanding requests and slow-reader disconnects. Native commands and response DTOs retain their existing codecs. A response loss is not a rejection: possibly submitted mutations fail explicitly as uncertain and are never automatically replayed. Durable request ID ranges are allocated centrally so servers cannot collide. Existing reconciliation paths use the retained IDs.

Subscriptions multiplex identical immutable queries, deliver an initial snapshot, coalesce later updates and carry failure/staleness explicitly. Socket readers and native completions never run Minecraft work. GUI callbacks marshal through GameServices.onServer. Static screens refresh less often; clicks remain immediate.

Native WAL, account metadata, shared application metadata and Minecraft world saves are separate durability boundaries. No network layer makes them atomic. Namespace leases serialize global registration/configuration workflows; lost connections release leases but do not make uncertain commands safe to retry. Unfinished custody stays quarantined on its originating instance.

Custody journals include the exact JSON binding and unit evidence loaded by the gateway. Before financial admission the owner compares every asset against current shared metadata and verifies complete mutation coverage. This closes the stale-gateway binding window without moving ItemStack decoding to the owner. Rejection after local physical custody preserves evidence for reconciliation; it does not claim atomicity across world saves, application journals and native WAL.

Implementation order: SDK subscriptions and transport seam; authenticated endpoint/client and independent server; central account/shared metadata adapters; LOCAL/REMOTE configuration and GUI integration; multi-client matching, persistence, disconnect, subscription and Minecraft regression tests; bilingual deployment instructions and built artifacts. Existing run demo data is preserved and is not used as a network migration test.
