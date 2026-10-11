package nhcm.capitalmc.backend;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import nhcm.advmatch.Exchange;
import nhcm.advmatch.command.account.UpdateBalance;
import nhcm.advmatch.command.account.BalanceTransaction;
import nhcm.advmatch.command.read.ReadBalance;
import nhcm.advmatch.command.order.PlaceOrder;
import nhcm.advmatch.command.persistence.Flush;
import nhcm.advmatch.model.*;
import nhcm.advmatch.remote.*;
import nhcm.capitalmc.management.ServerUserManager;
import nhcm.capitalmc.service.ServiceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real sockets and persistent JNI engine, with no Minecraft server/world. */
class BackendIntegrationTest
{
    @TempDir Path directory;
    private static final String TOKEN = "backend-test-012345678901234567890123456789";

    @Test
    void sharedAccountsMetadataAndRequestIdsSurviveOwnerRestart() throws Exception
    {
        long reservedThrough;
        UUID account;
        String identity;
        try (var owner = new Exchange(ExchangeRuntime.nativeConfig(directory.resolve("exchange"))))
        {
            var users = new ServerUserManager(directory.resolve("users.dat"));
            users.connectExchange(owner).get(5, TimeUnit.SECONDS);
            try (var coordinator = new BackendCoordinator(owner, users, directory);
                 var endpoint = new ExchangeServer(owner, new ExchangeServer.Options("127.0.0.1", 0, TOKEN, coordinator.exchangeId(), false, 8, 64, 4194304));
                 var channelA = new RemoteChannel(new RemoteOptions("127.0.0.1", endpoint.port(), "alpha", TOKEN));
                 var channelB = new RemoteChannel(new RemoteOptions("127.0.0.1", endpoint.port(), "beta", TOKEN)))
            {
                coordinator.expose(endpoint);
                identity = coordinator.exchangeId();
                var a = new BackendClient(channelA);
                var b = new BackendClient(channelB);
                var managerA = new ServerUserManager();
                var managerB = new ServerUserManager();
                managerA.useDirectory(new RemoteAccountDirectory(a));
                managerB.useDirectory(new RemoteAccountDirectory(b));
                UUID player = UUID.randomUUID();
                var registered = managerA.registerAsync("Trader", "trader", "secret", player).get(10, TimeUnit.SECONDS);
                account = registered.getUuid();
                assertTrue(registered.getExchangeUserId() > 0);
                assertTrue(managerB.authenticateAsync("trader", "wrong").join().isEmpty());
                var verified = managerB.authenticateAsync("trader", "secret").join().orElseThrow();
                assertEquals(account, verified.getUuid());
                managerB.activateVerified(player, verified);
                managerB.refreshDirectory().join();
                assertSame(verified, managerB.getLoggedInUser(player).orElseThrow(), "Unchanged refresh must retain session identity");
                assertTrue(managerA.changePasswordAsync("trader", "secret", "new-password").join());
                managerB.refreshDirectory().join();
                assertTrue(managerB.getLoggedInUser(player).isEmpty(), "Password changes revoke sessions on other gateways");
                assertTrue(managerB.authenticateAsync("trader", "secret").join().isEmpty());
                assertTrue(managerB.authenticateAsync("trader", "new-password").join().isPresent());

                var initial = a.read().join();
                var first = initial.deepCopy();
                first.addProperty("issuerUser", registered.getExchangeUserId());
                a.commit(initial, first).join();
                var conflicting = initial.deepCopy();
                conflicting.addProperty("issuerUser", 999);
                assertThrows(CompletionException.class, () -> b.commit(initial, conflicting).join());
                var second = initial.deepCopy();
                second.add("symbolLots", new JsonObject());
                b.commit(initial, second).join();
                assertEquals(registered.getExchangeUserId(), b.read().join().get("issuerUser").getAsLong());

                var stateA = new ServiceState(directory.resolve("node-a/services.json"));
                var stateB = new ServiceState(directory.resolve("node-b/services.json"));
                stateA.useShared(a).join();
                stateB.useShared(b).join();
                stateA.update(root -> { root.add("pegs", new JsonObject()); root.addProperty("localDelivery", "alpha"); });
                stateB.refreshShared().join();
                assertTrue(stateB.snapshot().has("pegs"));
                assertFalse(stateB.snapshot().has("localDelivery"), "Custody evidence is instance-local");
                stateB.update(root -> root.addProperty("localDelivery", "beta"));
                assertEquals("beta", new ServiceState(directory.resolve("node-b/services.json")).snapshot().get("localDelivery").getAsString());

                long idA = a.nextRequestId();
                long idB = b.nextRequestId();
                assertNotEquals(idA, idB);
                assertEquals(idA + 4096, idB);
                reservedThrough = idB + 4096;

                try (var remote = new Exchange(channelA, ForkJoinPool.commonPool()))
                {
                    var base = remote.assets().create("test-base", "Base", 0).join();
                    var quote = remote.assets().create("test-quote", "Quote", 0).join();
                    var symbol = remote.symbols().create("B/Q", SymbolType.Spot, base, quote).join();
                    remote.books().addSpot(symbol, OrderBookImplementation.Radix).join();
                    long spot = remote.users().get(registered.getExchangeUserId()).spotAccount().join().id();
                    remote.submit(new UpdateBalance(spot, quote.id(), 100, a.nextRequestId())).join();
                    stateB.update(root -> {
                        var restrictions = new JsonObject();
                        var ban = new JsonObject();
                        ban.addProperty("banned", true);
                        restrictions.add(account.toString(), ban);
                        root.add("userRestrictions", restrictions);
                    });
                    assertThrows(CompletionException.class, () -> managerA.ensureExchangeUser(account).join());
                    assertThrows(CompletionException.class, () -> remote.submit(new PlaceOrder(registered.getExchangeUserId(), symbol.id(),
                            OrderSide.Bid, OrderType.Limit, TimeInForce.GTC, 10, 1, 1, new ContractOrderOptions(), a.nextRequestId())).join());
                }
                owner.submit(new Flush()).join();
            }
        }
        try (var owner = new Exchange(ExchangeRuntime.nativeConfig(directory.resolve("exchange"))))
        {
            var users = new ServerUserManager(directory.resolve("users.dat"));
            users.connectExchange(owner).join();
            try (var coordinator = new BackendCoordinator(owner, users, directory))
            {
                assertEquals(identity, coordinator.exchangeId());
                assertEquals(account, users.findByUsername("trader").orElseThrow().getUuid());
                assertTrue(coordinator.localClient("gamma").nextRequestId() >= reservedThrough);
                assertTrue(coordinator.localClient("gamma").read().join().has("userRestrictions"));
            }
        }
    }

    @Test
    void globalConfigurationLeaseSerializesNodesAndReleasesOnDisconnect() throws Exception
    {
        try (var owner = new Exchange(ExchangeRuntime.nativeConfig(directory.resolve("exchange"))))
        {
            var users = new ServerUserManager();
            users.connectExchange(owner).join();
            try (var coordinator = new BackendCoordinator(owner, users, directory);
                 var endpoint = new ExchangeServer(owner, new ExchangeServer.Options("127.0.0.1", 0, TOKEN, coordinator.exchangeId(), false, 8, 64, 4194304));
                 var channel = new RemoteChannel(new RemoteOptions("127.0.0.1", endpoint.port(), "holder", TOKEN)))
            {
                coordinator.expose(endpoint);
                var held = new CompletableFuture<Integer>();
                var entered = new CountDownLatch(1);
                var operation = new BackendClient(channel).exclusive("configuration", () -> { entered.countDown(); return held; });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var other = coordinator.localClient("other").exclusive("configuration", () -> CompletableFuture.completedFuture(42));
                assertFalse(other.isDone());
                channel.close();
                assertEquals(42, other.get(5, TimeUnit.SECONDS));
                held.complete(7);
                assertThrows(CompletionException.class, operation::join, "Disconnected lease cannot be claimed as committed");
            }
        }
    }

    @Test
    void custodyRejectsStaleDisabledAndIncompleteBindingsBeforeChangingBalances() throws Exception
    {
        try (var owner = new Exchange(ExchangeRuntime.nativeConfig(directory.resolve("exchange"))))
        {
            var users = new ServerUserManager();
            users.connectExchange(owner).join();
            try (var coordinator = new BackendCoordinator(owner, users, directory);
                 var endpoint = new ExchangeServer(owner, new ExchangeServer.Options("127.0.0.1", 0, TOKEN, coordinator.exchangeId(), false, 8, 64, 4194304));
                 var channel = new RemoteChannel(new RemoteOptions("127.0.0.1", endpoint.port(), "custody", TOKEN)))
            {
                coordinator.expose(endpoint);
                var client = new BackendClient(channel);
                var user = owner.users().create().join();
                long account = user.spotAccount().join().id();
                long asset = owner.assets().create("Diamond", "diamond", 0).join().id();
                long other = owner.assets().create("Gold", "gold", 0).join().id();
                var initial = client.read().join();
                var next = initial.deepCopy();
                var entry = new JsonObject();
                entry.addProperty("id", asset);
                entry.addProperty("item", "minecraft:diamond");
                entry.addProperty("electronic", false);
                var entries = new JsonArray();
                entries.add(entry);
                next.add("assets", entries);
                client.commit(initial, next).join();
                var proofs = new JsonObject();
                proofs.add(Long.toString(asset), BindingProof.asset(next, asset));
                assertEquals(BalanceOpResult.Success.code(), client.custody(new UpdateBalance(account, asset, 10, client.nextRequestId()), proofs).join().result().state());

                var missing = new BalanceTransaction(List.of(new BalanceDelta(account, asset, 1), new BalanceDelta(account, other, 1)), false, client.nextRequestId());
                assertThrows(CompletionException.class, () -> client.custody(missing, proofs).join());
                var changed = next.deepCopy();
                changed.getAsJsonArray("assets").get(0).getAsJsonObject().addProperty("item", "minecraft:emerald");
                client.commit(next, changed).join();
                assertThrows(CompletionException.class, () -> client.custody(new UpdateBalance(account, asset, -5, client.nextRequestId()), proofs).join());
                assertEquals(10, owner.submit(new ReadBalance(new ObservationQuery().withAccountId(account).withAssetId(asset))).join().values().getFirst().active());

                proofs.add(Long.toString(asset), BindingProof.asset(changed, asset));
                assertEquals(BalanceOpResult.Success.code(), client.custody(new UpdateBalance(account, asset, -2, client.nextRequestId()), proofs).join().result().state());
                var disabled = changed.deepCopy();
                disabled.getAsJsonArray("assets").get(0).getAsJsonObject().addProperty("enabled", false);
                client.commit(changed, disabled).join();
                proofs.add(Long.toString(asset), BindingProof.asset(disabled, asset));
                assertThrows(CompletionException.class, () -> client.custody(new UpdateBalance(account, asset, 1, client.nextRequestId()), proofs).join());
                assertEquals(8, owner.submit(new ReadBalance(new ObservationQuery().withAccountId(account).withAssetId(asset))).join().values().getFirst().active());
            }
        }
    }

    @Test
    void delayedMetadataReadCannotOverwriteACompletedLocalCommit()
    {
        var stale = new CompletableFuture<JsonObject>();
        var current = new JsonObject();
        current.addProperty("issuerUser", 1);
        var source = new SharedMetadata()
        {
            int reads;

            public CompletableFuture<JsonObject> read()
            {
                return reads++ == 0 ? CompletableFuture.completedFuture(current.deepCopy()) : stale;
            }

            public CompletableFuture<JsonObject> commit(JsonObject expected, JsonObject next)
            {
                current.addProperty("issuerUser", next.get("issuerUser").getAsLong());
                return CompletableFuture.completedFuture(current.deepCopy());
            }
        };
        var state = new ServiceState(directory.resolve("services.json"));
        state.useShared(source).join();
        var delayed = state.refreshShared();
        state.update(root -> root.addProperty("issuerUser", 2));
        var old = new JsonObject();
        old.addProperty("issuerUser", 1);
        stale.complete(old);
        assertFalse(delayed.join());
        assertEquals(2, state.snapshot().get("issuerUser").getAsLong());
        assertEquals(2, new ServiceState(directory.resolve("services.json")).snapshot().get("issuerUser").getAsLong());
    }
}
