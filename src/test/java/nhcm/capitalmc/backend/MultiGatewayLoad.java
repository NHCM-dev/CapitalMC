package nhcm.capitalmc.backend;

import com.google.gson.GsonBuilder;
import nhcm.advmatch.Exchange;
import nhcm.advmatch.ObservationSubscription;
import nhcm.advmatch.command.account.UpdateBalance;
import nhcm.advmatch.command.persistence.Flush;
import nhcm.advmatch.model.*;
import nhcm.advmatch.remote.ExchangeServer;
import nhcm.advmatch.remote.RemoteChannel;
import nhcm.advmatch.remote.RemoteOptions;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.management.ServerUserManager;
import nhcm.capitalmc.service.ServiceState;
import nhcm.capitalmc.service.SettlementService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/** Real sockets, shared native ledger and CapitalMC durable order workflow; no synthetic Minecraft clients. */
public class MultiGatewayLoad
{
    private static final String TOKEN = "load-test-012345678901234567890123456789";

    private record Gateway(Exchange exchange, SettlementService settlement, ServiceState state) implements AutoCloseable
    {
        @Override
        public void close()
        {
            exchange.close();
        }
    }

    private static void require(boolean value, String message)
    {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception
    {
        int users = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        int rate = args.length > 2 ? Integer.parseInt(args[2]) : 100;
        Path directory = args.length > 3 ? Path.of(args[3]).toAbsolutePath() : Files.createTempDirectory("capitalmc-load-");
        require(users >= 2 && users <= 5000 && seconds >= 5 && rate >= 1, "Invalid load parameters");
        Files.createDirectories(directory);
        require(!Files.exists(directory.resolve("exchange")) && !Files.exists(directory.resolve("exchange.store-path")),
                "Use a fresh directory; load tests never reset existing ledgers");
        int gatewayCount = Math.min(50, Math.max(2, (users + 99) / 100));
        var report = new LinkedHashMap<String, Object>();
        report.put("users", users);
        report.put("gateways", gatewayCount);
        report.put("targetActionsPerSecond", rate);
        report.put("durationSeconds", seconds);
        report.put("transport", "loopback TCP; three private subscriptions per account; durable application order workflow");
        var resources = new ArrayList<Gateway>();
        var subscriptions = new ArrayList<ObservationSubscription>();
        long expectedStateHash;
        long expectedCommands;
        try (var owner = new Exchange(ExchangeRuntime.nativeConfig(directory.resolve("exchange"))))
        {
            var accounts = new ServerUserManager(directory.resolve("users.dat"));
            try (var coordinator = new BackendCoordinator(owner, accounts, directory);
                 var endpoint = new ExchangeServer(owner, new ExchangeServer.Options("127.0.0.1", 0, TOKEN, coordinator.exchangeId(), false, 64, 256, 4194304)))
            {
                coordinator.expose(endpoint);
                var base = owner.assets().create("load_base", "Base", 0).join();
                var quote = owner.assets().create("load_quote", "Quote", 0).join();
                var spot = owner.symbols().create("LOAD/SPOT", SymbolType.Spot, base, quote).join();
                owner.books().addSpot(spot, OrderBookImplementation.Radix).join();
                var perpetual = owner.symbols().create("LOAD/PERP", SymbolType.Perpetual, base, quote).join();
                long timestamp = System.currentTimeMillis();
                var contractSpec = new ContractSpec().withDefaultLeverage(2).withMaxLeverage(10)
                                                     .withFirstFundingTimestamp(timestamp + 28800000);
                owner.books().addContract(perpetual, contractSpec, 100, timestamp, OrderBookImplementation.Radix).join();
                var prototype = new ServerUser("Load", "prototype", "", UUID.randomUUID(), UUID.randomUUID());
                var identities = new ArrayList<ServerUser>();
                for (int index = 0; index < users; index++)
                {
                    var user = owner.users().create().join();
                    var spotAccount = user.spotAccount().join();
                    var contractAccount = user.contractAccount().join();
                    owner.submit(new UpdateBalance(spotAccount.id(), base.id(), 100000)).join();
                    owner.submit(new UpdateBalance(spotAccount.id(), quote.id(), 10000000)).join();
                    owner.submit(new UpdateBalance(contractAccount.id(), quote.id(), 10000000)).join();
                    identities.add(ServerUser.restore("Trader " + index, "load_" + index, prototype.getPasswordHash(), UUID.randomUUID(), UUID.randomUUID())
                                             .withExchangeUserId(user.id()));
                }
                for (int index = 0; index < gatewayCount; index++)
                {
                    var channel = new RemoteChannel(new RemoteOptions("127.0.0.1", endpoint.port(), "gateway-" + index, TOKEN));
                    var exchange = new Exchange(channel, ForkJoinPool.commonPool());
                    var backend = new BackendClient(channel);
                    var state = new ServiceState(directory.resolve("gateway-" + index).resolve("services.json"));
                    resources.add(new Gateway(exchange, new SettlementService(exchange, state, backend::nextRequestId), state));
                }
                var initial = new ArrayList<CompletableFuture<?>>();
                for (int index = 0; index < users; index++)
                {
                    var gateway = resources.get(index % gatewayCount);
                    for (var kind : List.of(ReadKind.UserOpenOrders, ReadKind.Positions, ReadKind.Balances))
                    {
                        var query = new ObservationQuery().withKind(kind).withUserId(identities.get(index).getExchangeUserId())
                                                          .withLimit(27).withScanBudget(512).withIncludeTerminal(false);
                        var subscription = gateway.exchange().subscriptions().subscribe(query, Duration.ofMillis(250), ignored -> {});
                        subscriptions.add(subscription);
                        initial.add(subscription.first());
                    }
                }
                CompletableFuture.allOf(initial.toArray(CompletableFuture[]::new)).get(90, TimeUnit.SECONDS);
                report.put("subscriptions", subscriptions.size());
                var latency = new ConcurrentLinkedQueue<Long>();
                var failures = new AtomicLong();
                var errors = new ConcurrentHashMap<String, AtomicLong>();
                var pending = new Semaphore(256);
                var completed = new AtomicLong();
                long started = System.nanoTime();
                long operations = (long) seconds * rate;
                for (long action = 0; action < operations; action++)
                {
                    long due = started + action * 1_000_000_000L / rate;
                    long wait = due - System.nanoTime();
                    if (wait > 0) LockSupport.parkNanos(wait);
                    pending.acquire();
                    int index = (int) (action % users);
                    var gateway = resources.get(index % gatewayCount);
                    var user = identities.get(index);
                    boolean contract = (action / 2) % 4 == 0;
                    boolean sell = index % 2 == 0;
                    long beginning = System.nanoTime();
                    boolean cancel = action % 16 == 10;
                    CompletableFuture<CommandReceipt> operation;
                    if (action % 20 == 18)
                    {
                        boolean profit = (action / 20) % 2 == 0;
                        var ticket = new ConditionalOrderRequest().withSymbolId(spot.id()).withQuantity(1)
                                                                 .withKind(profit ? ConditionalOrderKind.TakeProfit : ConditionalOrderKind.StopLoss)
                                                                 .withTriggerPrice(profit ? 120 : 80);
                        operation = gateway.settlement().placeConditional(user, ticket).thenCompose(receipt -> {
                            long id = receipt.result().entityId();
                            return gateway.settlement().modifyConditional(user, id, profit ? 130 : 70, 0, 1)
                                          .thenCompose(ignored -> gateway.settlement().cancelConditional(user, id));
                        });
                    }
                    else
                    {
                        operation = gateway.settlement().place(user, contract ? perpetual.id() : spot.id(), sell ? OrderSide.Ask : OrderSide.Bid,
                                                               OrderType.Limit, sell ? TimeInForce.GTC : TimeInForce.IOC, cancel ? 110 : 100, 1,
                                                               contract ? new ContractOrderOptions().withLeverage(2).withMarginMode(MarginMode.Isolated) : null)
                                           .thenCompose(receipt -> cancel && receipt.result().unfilled() > 0
                                                                  ? gateway.settlement().cancel(user, receipt.result().entityId(), System.currentTimeMillis())
                                                                  : CompletableFuture.completedFuture(receipt));
                    }
                    operation.whenComplete((receipt, failure) -> {
                               latency.add(System.nanoTime() - beginning);
                               if (failure != null)
                               {
                                   failures.incrementAndGet();
                                   Throwable root = failure;
                                   while (root.getCause() != null) root = root.getCause();
                                   errors.computeIfAbsent(root.getClass().getSimpleName() + ":" + root.getMessage(), ignored -> new AtomicLong()).incrementAndGet();
                               }
                               completed.incrementAndGet();
                               pending.release();
                           });
                }
                require(pending.tryAcquire(256, 90, TimeUnit.SECONDS), "Load did not drain");
                double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
                var samples = latency.stream().mapToLong(Long::longValue).sorted().toArray();
                report.put("completedActions", completed.get());
                report.put("failedActions", failures.get());
                report.put("errors", errors);
                report.put("actualActionsPerSecond", completed.get() / elapsed);
                report.put("latencyP50Millis", samples[samples.length / 2] / 1_000_000.0);
                report.put("latencyP95Millis", samples[Math.min(samples.length - 1, (int) (samples.length * .95))] / 1_000_000.0);
                report.put("latencyP99Millis", samples[Math.min(samples.length - 1, (int) (samples.length * .99))] / 1_000_000.0);
                report.put("endpoint", endpoint.metrics());
                report.put("subscriptionsBackend", owner.subscriptions().metrics());
                report.put("metadata", resources.stream().map(gateway -> gateway.state().metrics()).toList());
                report.put("flushGroups", resources.stream().mapToLong(gateway -> gateway.settlement().flushGroups()).sum());
                var audit = owner.audit().report(64).join();
                report.put("audit", audit);
                report.put("persistence", owner.submit(new Flush()).join());
                require(audit.conserved() && audit.consistent() && audit.issues().isEmpty(), "Load audit failed");
                require(completed.get() == operations, "Missing operation results");
                expectedStateHash = audit.stateHash();
                expectedCommands = resources.stream().mapToLong(gateway -> gateway.state().entries("commands", 0, 1, false).total()).sum();
                if (failures.get() != 0)
                {
                    Files.writeString(directory.resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
                }
                require(failures.get() == 0, "Load had rejected or unknown actions; inspect report.json");
            }
            finally
            {
                subscriptions.forEach(ObservationSubscription::close);
                for (var gateway : resources) gateway.close();
            }
        }
        long recoveryBeginning = System.nanoTime();
        try (var recovered = new Exchange(ExchangeRuntime.nativeConfig(directory.resolve("exchange"))))
        {
            var audit = recovered.audit().report(64).join();
            require(audit.conserved() && audit.consistent() && audit.issues().isEmpty(), "Recovery audit failed");
            require(audit.stateHash() == expectedStateHash, "Recovery changed the financial state");
            long recoveredCommands = 0;
            for (int index = 0; index < gatewayCount; index++)
            {
                var state = new ServiceState(directory.resolve("gateway-" + index).resolve("services.json"));
                var commands = state.snapshot().getAsJsonObject("commands");
                if (commands == null) continue;
                recoveredCommands += commands.size();
                for (var entry : commands.entrySet())
                {
                    require(entry.getValue().getAsJsonObject().get("status").getAsString().equals("DURABLE"),
                            "An acknowledged command lost its durable application receipt");
                }
            }
            require(recoveredCommands == expectedCommands, "Recovery lost command journal entries");
            report.put("recoveredCommands", recoveredCommands);
            report.put("recoveryStateHashMatches", true);
            report.put("recoveryIncludingMetadataMillis", (System.nanoTime() - recoveryBeginning) / 1_000_000.0);
            report.put("recoveryAudit", audit);
        }
        Path nativeStore = directory.resolve(Files.readString(directory.resolve("exchange.store-path")).strip());
        try (var segments = Files.list(nativeStore.resolve("archive")))
        {
            report.put("nativeArchivedSegments", segments.count());
        }
        try (var snapshots = Files.list(nativeStore.resolve("snapshots")))
        {
            report.put("nativeRetainedSnapshots", snapshots.count());
        }
        Files.writeString(directory.resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("MULTI_GATEWAY_REPORT " + new GsonBuilder().create().toJson(report));
    }
}
