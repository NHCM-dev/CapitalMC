package nhcm.capitalmc.backend;

import com.google.gson.JsonObject;
import nhcm.advmatch.Exchange;
import nhcm.advmatch.TransportOptions;
import nhcm.advmatch.command.persistence.Flush;
import nhcm.advmatch.model.*;
import nhcm.advmatch.remote.*;
import nhcm.capitalmc.management.ServerUserManager;
import nhcm.capitalmc.service.ApplicationExecutors;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

/** Owns one connection or one native engine; Minecraft never owns remote financial files. */
public class ExchangeRuntime implements AutoCloseable
{
    public final Exchange exchange;
    public final BackendClient backend;
    public final CompletableFuture<Void> ready;
    public final NetworkConfig config;
    public final DataCompatibility compatibility;
    private BackendCoordinator coordinator;
    private ExchangeServer endpoint;

    public ExchangeRuntime(NetworkConfig config, Path directory, ServerUserManager users) throws Exception
    {
        this.config = config;
        compatibility = DataCompatibility.inspect(directory);
        if (config.mode == NetworkConfig.Mode.REMOTE)
        {
            if (users.size() != 0 || Files.exists(directory.resolve("exchange")))
            {
                throw new IllegalStateException("Move the existing LOCAL exchange and account registry to the owner before enabling REMOTE; see cross-server documentation");
            }
            var channel = new RemoteChannel(new RemoteOptions(config.host, config.port, config.instanceId, config.token,
                    config.tls, config.allowPlaintextNetwork, 5000, config.requestTimeoutMillis,
                    config.maxOutstanding, config.maxFrameBytes, config.expectedExchangeId));
            exchange = new Exchange(channel, ApplicationExecutors.CALLBACKS);
            backend = new BackendClient(channel);
            users.useDirectory(new RemoteAccountDirectory(backend));
            ready = backend.call(BackendClient.INFO, new JsonObject()).thenCompose(info ->
                    ApplicationExecutors.io(() -> {
                        try
                        {
                            String identity = info.getAsJsonObject().get("exchangeId").getAsString();
                            compatibility.remote(info.getAsJsonObject());
                            Path previousIdentity = directory.resolve("exchange.id");
                            if (Files.exists(previousIdentity) && !Files.readString(previousIdentity).strip().equals(identity))
                            {
                                throw new IllegalStateException("The local recovery journal belongs to a different exchange");
                            }
                            config.pin(identity);
                        }
                        catch (IOException error)
                        {
                            throw new UncheckedIOException(error);
                        }
                        return null;
                    })).thenCompose(unused -> users.connectExchange(exchange));
        }
        else
        {
            exchange = new Exchange(nativeConfig(directory.resolve("exchange")), ApplicationExecutors.CALLBACKS,
                                    new TransportOptions(256, 64, 1000, 1024));
            try
            {
                coordinator = new BackendCoordinator(exchange, users, directory, compatibility);
                backend = coordinator.localClient(config.instanceId);
                if (config.listen)
                {
                    endpoint = new ExchangeServer(exchange, new ExchangeServer.Options(config.bind, config.port, config.token,
                            coordinator.exchangeId(), config.allowPlaintextNetwork, config.maxConnections,
                            config.maxOutstanding, config.maxFrameBytes), config.serverTls());
                    endpoint.reserveInstance(config.instanceId);
                    coordinator.expose(endpoint);
                }
                ready = users.connectExchange(exchange).thenCompose(unused -> exchange.contracts().setAutomaticLiquidationBudget(32))
                             .thenCompose(receipt -> {
                                 if (!receipt.result().accepted()) throw new IllegalStateException("Cannot configure exchange maintenance");
                                 return exchange.submit(new Flush()).thenApply(status -> null);
                             });
            }
            catch (Throwable error)
            {
                if (endpoint != null) endpoint.close();
                if (coordinator != null) coordinator.close();
                exchange.closeAsync();
                throw error;
            }
        }
    }

    public static ExchangeConfig nativeConfig(Path directory)
    {
        var persistence = new PersistenceConfig(true, directory.toString(), FlushPolicy.Batch, 256, 10, 0, 2, true,
                TailRecovery.TruncateIncomplete, 1048576, 4294967296L, 10000, true);
        var observation = new ObservationConfig(1024, 16384, 256, 131072, true, 0, 4096, 4096);
        return new ExchangeConfig(persistence, observation, 1024, 0, 1024, 1024, 1024, 4194304);
    }

    public CompletableFuture<Void> stop()
    {
        if (endpoint != null) endpoint.close();
        if (coordinator != null) coordinator.close();
        return exchange.closeAsync();
    }

    @Override
    public void close()
    {
        stop().join(); // Standalone lifecycle only. Minecraft uses stop() without waiting.
    }
}
