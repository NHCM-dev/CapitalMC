package nhcm.capitalmc.backend;

import com.google.gson.*;
import nhcm.advmatch.Exchange;
import nhcm.advmatch.BusinessException;
import nhcm.advmatch.RawCommand;
import nhcm.advmatch.internal.WireReader;
import nhcm.advmatch.model.*;
import nhcm.advmatch.remote.ExchangeServer;
import nhcm.capitalmc.management.ServerUserManager;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.service.ServiceState;
import nhcm.capitalmc.service.BinaryValues;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Authoritative account directory and shared application metadata; contains no Minecraft objects. */
public class BackendCoordinator implements AutoCloseable
{
    private record Lease(String token, String connection, Semaphore semaphore) {}

    private final Exchange exchange;
    private final ServerUserManager users;
    private final ServiceState shared;
    private final String exchangeId;
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1024), Thread.ofPlatform().daemon().name("capitalmc-backend-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final ExecutorService waits = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("capitalmc-backend-maintenance").factory());
    private final Map<String, Semaphore> namespaces = new ConcurrentHashMap<>();
    private final Map<String, Lease> leases = new ConcurrentHashMap<>();
    private volatile CompletableFuture<Void> updating = CompletableFuture.completedFuture(null);
    private long lastMaintenanceError;
    private List<ServerUser> accountImage = List.of();
    private JsonArray accountRows = new JsonArray();
    private String accountRevision = UUID.randomUUID().toString();
    private JsonObject metadataImage = new JsonObject();
    private String metadataRevision = UUID.randomUUID().toString();
    private long metadataSequence = -1;
    private final Object custodyAdmission = new Object();
    private final DataCompatibility compatibility;
    private volatile ExchangeServer endpoint;

    public BackendCoordinator(Exchange exchange, ServerUserManager users, Path directory) throws IOException
    {
        this(exchange, users, directory, DataCompatibility.inspect(directory));
    }

    public BackendCoordinator(Exchange exchange, ServerUserManager users, Path directory, DataCompatibility compatibility) throws IOException
    {
        this.compatibility = compatibility;
        this.exchange = exchange;
        this.users = users;
        Files.createDirectories(directory);
        Path identity = directory.resolve("exchange.id");
        if (!Files.exists(identity))
        {
            Files.writeString(identity, UUID.randomUUID().toString(), StandardOpenOption.CREATE_NEW);
            try (var channel = FileChannel.open(identity, StandardOpenOption.WRITE))
            {
                channel.force(true);
            }
        }
        exchangeId = Files.readString(identity).strip();
        UUID.fromString(exchangeId);
        Path metadata = directory.resolve("shared-services.json");
        shared = new ServiceState(metadata);
        if (!shared.snapshot().has("initialized"))
        {
            var legacy = new ServiceState(directory.resolve("services.json"));
            var imported = SharedMetadata.select(legacy.snapshot());
            shared.update(root -> {
                imported.entrySet().forEach(entry -> root.add(entry.getKey(), entry.getValue()));
                root.addProperty("initialized", true);
                root.addProperty("requestNext", 4_000_000_000_000_000_000L);
            });
        }
        users.setAccessPolicy(user -> {
            var restrictions = shared.section("userRestrictions").getAsJsonObject("userRestrictions");
            var value = restrictions == null ? null : restrictions.getAsJsonObject(user.getUuid().toString());
            return value == null || !value.get("banned").getAsBoolean();
        });
        maintenance.scheduleWithFixedDelay(this::maintain, 1, 1, TimeUnit.SECONDS);
    }

    public String exchangeId()
    {
        return exchangeId;
    }

    public BackendClient localClient(String instanceId)
    {
        var context = new ExchangeServer.ClientContext(instanceId);
        return new BackendClient((operation, request) -> call(context, operation, request));
    }

    public void expose(ExchangeServer server)
    {
        endpoint = server;
        server.extensions((context, operation, bytes) -> {
            JsonObject request;
            try
            {
                request = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            catch (RuntimeException error)
            {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid backend request"));
            }
            return call(context, operation, request).thenApply(result -> result.toString().getBytes(StandardCharsets.UTF_8));
        }, this::disconnected);
        server.commandValidator(this::validateCommand);
    }

    private void validateCommand(ExchangeServer.ClientContext context, byte[] packet)
    {
        if (packet.length < 15)
        {
            return;
        }
        var reader = new WireReader(packet);
        if (reader.u16() != 1 || reader.u8() != 0)
        {
            return;
        }
        if (reader.u16() != 2)
        {
            throw new IllegalArgumentException("Unsupported mutation protocol");
        }
        reader.u64();
        int operation = reader.u16();
        if (operation == 10 || operation == 30)
        {
            long userId = reader.u64();
            users.findByExchangeId(userId).ifPresent(users::requireAccess);
        }
    }

    private CompletableFuture<JsonElement> call(ExchangeServer.ClientContext context, int operation, JsonObject request)
    {
        if (!context.active().get())
        {
            return CompletableFuture.failedFuture(new IllegalStateException("Instance is disconnected"));
        }
        try
        {
            return CompletableFuture.supplyAsync(() -> dispatch(context, operation, request), workers).thenCompose(future -> future);
        }
        catch (RejectedExecutionException failure)
        {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletableFuture<JsonElement> dispatch(ExchangeServer.ClientContext context, int operation, JsonObject request)
    {
        switch (operation)
        {
            case BackendClient.INFO ->
            {
                var info = new JsonObject();
                info.addProperty("exchangeId", exchangeId);
                info.addProperty("protocol", 1);
                info.addProperty("capitalmcVersion", DataCompatibility.version());
                info.add("versionWarnings", new Gson().toJsonTree(compatibility.warnings()));
                var metrics = new JsonObject();
                var gson = new Gson();
                metrics.add("subscriptions", gson.toJsonTree(exchange.subscriptions().metrics()));
                metrics.add("metadata", gson.toJsonTree(shared.metrics()));
                if (endpoint != null) metrics.add("gateway", gson.toJsonTree(endpoint.metrics()));
                info.add("metrics", metrics);
                return CompletableFuture.completedFuture(info);
            }
            case BackendClient.ACCOUNTS ->
            {
                return CompletableFuture.completedFuture(accountSnapshot(request));
            }
            case BackendClient.REGISTER ->
            {
                var profile = request.get("profile");
                return users.registerAsync(text(request, "display"), text(request, "username"), text(request, "password"),
                                           UUID.fromString(text(request, "owner")), profile.isJsonNull() ? null : profile.getAsJsonObject())
                            .thenApply(AccountViews::encode);
            }
            case BackendClient.AUTHENTICATE ->
            {
                return users.authenticateAsync(text(request, "username"), text(request, "password"))
                            .thenApply(found -> found.map(user -> (JsonElement) AccountViews.encode(user)).orElse(JsonNull.INSTANCE));
            }
            case BackendClient.PASSWORD ->
            {
                return users.changePasswordAsync(text(request, "username"), text(request, "old"), text(request, "new"))
                            .thenApply(JsonPrimitive::new);
            }
            case BackendClient.ENSURE ->
            {
                var id = UUID.fromString(text(request, "account"));
                var user = users.get(id).orElseThrow();
                users.requireAccess(user);
                return users.ensureExchangeUser(id).thenApply(AccountViews::encode);
            }
            case BackendClient.REMOVE ->
            {
                var removed = users.remove(UUID.fromString(text(request, "owner")), UUID.fromString(text(request, "account")));
                return CompletableFuture.completedFuture(removed.map(user -> (JsonElement) AccountViews.encode(user)).orElse(JsonNull.INSTANCE));
            }
            case BackendClient.METADATA ->
            {
                return CompletableFuture.completedFuture(metadataSnapshot(request));
            }
            case BackendClient.COMMIT ->
            {
                synchronized (custodyAdmission)
                {
                    var expected = request.getAsJsonObject("expected");
                    var next = request.getAsJsonObject("next");
                    if (!SharedMetadata.FIELDS.containsAll(expected.keySet()) || !SharedMetadata.FIELDS.containsAll(next.keySet()))
                    {
                        throw new IllegalArgumentException("Unknown shared metadata section");
                    }
                    shared.update(root -> {
                        for (String field : SharedMetadata.FIELDS)
                        {
                            if (BinaryValues.sameValues(expected.get(field), next.get(field)))
                            {
                                continue;
                            }
                            if (!BinaryValues.sameValues(root.get(field), expected.get(field)))
                            {
                                throw new IllegalStateException("Shared metadata changed; refresh before retrying configuration");
                            }
                            if (next.has(field))
                            {
                                root.add(field, next.get(field).deepCopy());
                            }
                            else
                            {
                                root.remove(field);
                            }
                        }
                    });
                    return CompletableFuture.completedFuture(SharedMetadata.select(shared.snapshot()));
                }
            }
            case BackendClient.CUSTODY ->
            {
                synchronized (custodyAdmission)
                {
                    var bindings = request.getAsJsonObject("bindings");
                    if (bindings.isEmpty() || bindings.size() > 256)
                    {
                        throw new IllegalArgumentException("Missing custody configuration evidence");
                    }
                    var current = shared.snapshot();
                    for (var entry : bindings.entrySet())
                    {
                        var proof = BindingProof.asset(current, Long.parseLong(entry.getKey()));
                        if (!proof.get("enabled").getAsBoolean() || !BinaryValues.sameValues(proof, entry.getValue()))
                        {
                            throw new IllegalStateException("ui.custody.binding_changed");
                        }
                    }
                    String encoded = request.get("packet").getAsString();
                    if (encoded.length() > 1048576)
                    {
                        throw new IllegalArgumentException("Custody packet exceeds its limit");
                    }
                    byte[] packet = Base64.getDecoder().decode(encoded);
                    validateCustody(packet, bindings);
                    return exchange.submit(new RawCommand(packet)).thenApply(bytes -> new JsonPrimitive(Base64.getEncoder().encodeToString(bytes)));
                }
            }
            case BackendClient.REQUEST_RANGE ->
            {
                int count = request.get("count").getAsInt();
                if (count < 1 || count > 65536)
                {
                    throw new IllegalArgumentException("Invalid request range");
                }
                var range = new JsonObject();
                shared.update(root -> {
                    var counters = root.has("requestNamespaces") ? root.getAsJsonObject("requestNamespaces") : new JsonObject();
                    String instance = context.instanceId();
                    var counter = counters.has(instance) ? counters.getAsJsonObject(instance) : new JsonObject();
                    if (!counter.has("namespace"))
                    {
                        int namespace = root.has("nextRequestNamespace") ? root.get("nextRequestNamespace").getAsInt() : 0;
                        if (namespace >= 32768) throw new IllegalStateException("Request namespaces exhausted");
                        counter.addProperty("namespace", namespace);
                        counter.addProperty("next", 1L);
                        root.addProperty("nextRequestNamespace", namespace + 1);
                    }
                    long next = counter.get("next").getAsLong();
                    long limit = Math.addExact(next, count);
                    if (limit >= (1L << 48)) throw new IllegalStateException("Gateway request counter exhausted");
                    long prefix = Long.MIN_VALUE | ((long) counter.get("namespace").getAsInt() << 48);
                    long first = prefix | next;
                    long end = prefix | limit;
                    counter.addProperty("next", limit);
                    counters.add(instance, counter);
                    root.add("requestNamespaces", counters);
                    range.addProperty("first", first);
                    range.addProperty("end", end);
                });
                return CompletableFuture.completedFuture(range);
            }
            case BackendClient.ACQUIRE ->
            {
                String name = text(request, "name");
                if (!name.equals("configuration"))
                {
                    throw new IllegalArgumentException("Unknown metadata namespace");
                }
                return CompletableFuture.supplyAsync(() -> {
                    var semaphore = namespaces.computeIfAbsent(name, ignored -> new Semaphore(1));
                    try
                    {
                        if (!semaphore.tryAcquire(20, TimeUnit.SECONDS))
                        {
                            throw new IllegalStateException("Shared configuration is busy");
                        }
                        var lease = new Lease(UUID.randomUUID().toString(), context.connectionId(), semaphore);
                        leases.put(lease.token(), lease);
                        if (!context.active().get())
                        {
                            if (leases.remove(lease.token(), lease)) semaphore.release();
                            throw new IllegalStateException("Instance disconnected while acquiring configuration");
                        }
                        return (JsonElement) new JsonPrimitive(lease.token());
                    }
                    catch (InterruptedException error)
                    {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(error);
                    }
                }, waits);
            }
            case BackendClient.RELEASE ->
            {
                var token = text(request, "lease");
                var lease = leases.get(token);
                if (lease == null || !lease.connection().equals(context.connectionId()))
                {
                    throw new IllegalStateException("Configuration lease is no longer owned");
                }
                if (leases.remove(token, lease)) lease.semaphore().release();
                return CompletableFuture.completedFuture(JsonNull.INSTANCE);
            }
            default -> throw new IllegalArgumentException("Unknown application operation");
        }
    }

    private static void validateCustody(byte[] packet, JsonObject bindings)
    {
        var reader = new WireReader(packet);
        if (reader.u16() != 1 || reader.u8() != 0 || reader.u16() != 2 || reader.u64() == 0)
        {
            throw new IllegalArgumentException("Invalid custody mutation header");
        }
        int operation = reader.u16();
        long count;
        if (operation == 6)
        {
            count = 1;
        }
        else if (operation == 36)
        {
            count = reader.u64();
        }
        else
        {
            throw new IllegalArgumentException("Unsupported custody mutation");
        }
        if (count < 1 || count > 256)
        {
            throw new IllegalArgumentException("Invalid custody mutation size");
        }
        var assets = new HashSet<String>();
        for (long index = 0; index < count; index++)
        {
            reader.u64();
            assets.add(Long.toString(reader.u32()));
            reader.i64();
        }
        if (operation == 36) reader.bool();
        reader.finish();
        if (!assets.equals(bindings.keySet()))
        {
            throw new IllegalArgumentException("Custody evidence does not cover every asset");
        }
    }

    private static String text(JsonObject request, String key)
    {
        String value = request.get(key).getAsString();
        if (value.length() > 4096)
        {
            throw new IllegalArgumentException("Application field exceeds its limit");
        }
        return value;
    }

    private synchronized JsonObject accountSnapshot(JsonObject request)
    {
        var current = users.getUsers();
        if (!current.equals(accountImage))
        {
            accountImage = current;
            accountRows = new JsonArray();
            current.forEach(user -> accountRows.add(AccountViews.encode(user)));
            accountRevision = UUID.randomUUID().toString();
        }
        return imageResponse(request, accountRevision, accountRows);
    }

    private synchronized JsonObject metadataSnapshot(JsonObject request)
    {
        long sequence = shared.revision();
        if (sequence != metadataSequence)
        {
            var current = SharedMetadata.select(shared.snapshot());
            if (!BinaryValues.sameValues(current, metadataImage))
            {
                metadataImage = current;
                metadataRevision = UUID.randomUUID().toString();
            }
            metadataSequence = sequence;
        }
        return imageResponse(request, metadataRevision, metadataImage);
    }

    private static JsonObject imageResponse(JsonObject request, String revision, JsonElement image)
    {
        var response = new JsonObject();
        response.addProperty("revision", revision);
        boolean unchanged = request.has("known") && revision.equals(request.get("known").getAsString());
        response.add("image", unchanged ? JsonNull.INSTANCE : image.deepCopy());
        return response;
    }

    private void disconnected(ExchangeServer.ClientContext context)
    {
        for (var entry : leases.entrySet())
        {
            if (entry.getValue().connection().equals(context.connectionId()) && leases.remove(entry.getKey(), entry.getValue()))
            {
                entry.getValue().semaphore().release();
            }
        }
    }

    private void maintain()
    {
        if (!updating.isDone()) return;
        updating = symbols(new ObservationQuery().withLimit(1024), new ArrayList<>()).thenCompose(symbols -> {
            var jobs = symbols.stream().filter(symbol -> symbol.active() && symbol.type() != SymbolType.Spot)
                           .map(symbol -> exchange.contracts().refresh(exchange.symbols().get(symbol.symbolId()), System.currentTimeMillis()))
                           .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(jobs);
        });
        updating.exceptionally(error -> {
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            boolean noReference = cause instanceof BusinessException business && business.receipt() != null
                                  && business.receipt().result().state() == ContractState.PriceUnavailable.code();
            long now = System.currentTimeMillis();
            if (!noReference && now - lastMaintenanceError > 60000)
            {
                lastMaintenanceError = now;
                System.getLogger(BackendCoordinator.class.getName()).log(System.Logger.Level.WARNING, "Exchange contract maintenance failed", error);
            }
            return null;
        });
    }

    private CompletableFuture<List<SymbolView>> symbols(ObservationQuery query, List<SymbolView> values)
    {
        return exchange.symbols().list(query).thenCompose(page -> {
            if (page.page().state() != ReadState.Success) throw new BusinessException(page.page());
            values.addAll(page.values());
            return page.page().hasMore() ? symbols(query.withCursor(page.page().next()), values)
                                        : CompletableFuture.completedFuture(List.copyOf(values));
        });
    }

    @Override
    public void close()
    {
        maintenance.shutdownNow();
        waits.shutdownNow();
        workers.shutdown();
        leases.values().forEach(lease -> lease.semaphore().release());
        leases.clear();
    }
}
