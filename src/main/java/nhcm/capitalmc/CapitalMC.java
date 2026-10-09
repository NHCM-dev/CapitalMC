package nhcm.capitalmc;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import nhcm.advmatch.Exchange;
import nhcm.advmatch.TransportOptions;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.command.CapitalCommands;
import nhcm.capitalmc.management.GuiManager;
import nhcm.capitalmc.management.ServerUserManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

public final class CapitalMC implements DedicatedServerModInitializer
{
    public static Exchange exchange = null;
    public static final GuiManager guiManager = new GuiManager();
    public static final ServerUserManager serverUserManager = new ServerUserManager(
            FabricLoader.getInstance().getGameDir().resolve("capitalmc").resolve("users.json"));
    public static final Logger logger = LoggerFactory.getLogger("CapitalMC");

    @Override
    public void onInitializeServer()
    {
        CapitalCommands.register();
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            guiManager.removePlayer(handler.player);
            serverUserManager.logout(handler.player.getUUID());
        });

        initializeExchange();

        registerStops();

        logger.info("CapitalMC initialized.");
    }

    private void initializeExchange()
    {
        logger.info("Loading internal exchange...");

        ExchangeConfig config = exchangeConfig();
        Executor callback = ForkJoinPool.commonPool();

        int responseBatchSize = 256;
        int spinCount = 64;
        long activeParkNanos = 1000L;
        int maxOutstanding = (int)Math.min(config.maxPendingRequests(), 2147483647L);

        TransportOptions transportOptions = new TransportOptions(
                responseBatchSize, spinCount, activeParkNanos, maxOutstanding
        );

        exchange = new Exchange(config, callback, transportOptions);
        // Initialization happens before serving players; existing IDs must align before trading starts.
        try {
            serverUserManager.connectExchange(exchange).join();
        } catch (RuntimeException failure) {
            exchange.close();
            throw failure;
        }

        logger.info("Finished loading internal exchange.");
    }

    private ExchangeConfig exchangeConfig()
    {
        FlushPolicy flushPolicy = FlushPolicy.Batch;
        TailRecovery tailRecovery = TailRecovery.TruncateIncomplete;
        String dataSaveDir = FabricLoader.getInstance()
                                         .getGameDir()
                                         .resolve("capitalmc")
                                         .resolve("exchange")
                                         .toString();
        long flushEveryCommands = 256L;
        long flushIntervalMilliseconds = 10L;
        long checkpointEveryCommands = 0L;
        long retainedCheckpoints = 2L;
        boolean pruneJournalAfterCheckpoint = true;
        long maxRecordBytes = 1048576L;
        long maxSnapshotBytes = 4294967296L;
        long retainedRequests = 10000L;
        boolean verifyRecovery = true;

        PersistenceConfig persistenceConfig = new PersistenceConfig(
                true, dataSaveDir, flushPolicy, flushEveryCommands, flushIntervalMilliseconds,
                checkpointEveryCommands, retainedCheckpoints, pruneJournalAfterCheckpoint,
                tailRecovery, maxRecordBytes, maxSnapshotBytes, retainedRequests, verifyRecovery
        );

        long maxRows = 1024L;
        long maxScan = 16384L;
        long maxBatchQueries = 16L;
        long maxBatchScan = 65536L;
        boolean indexUserOpenOrders = false;
        long expectedUsersWithOrders = 0L;
        long tradeHistoryCapacity = 4096L;
        long contractEventCapacity = 4096L;

        ObservationConfig observationConfig = new ObservationConfig(
                maxRows, maxScan, maxBatchQueries, maxBatchScan,
                indexUserOpenOrders, expectedUsersWithOrders,
                tradeHistoryCapacity, contractEventCapacity
        );

        int ringBufferSize = 1024;
        long progressBatchSize = 0L;
        long observationCacheSlots = 1024L;
        long maxHistoryRows = 1024L;
        long maxPendingRequests = 1024L;
        long maxRequestBytes = 4194304L;

        return new ExchangeConfig(
                persistenceConfig, observationConfig,
                ringBufferSize, progressBatchSize, observationCacheSlots,
                maxHistoryRows, maxPendingRequests, maxRequestBytes
        );
    }

    private void registerStops()
    {
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            guiManager.closeAll();
            serverUserManager.clearSessions();
            logger.info("Stopping CapitalMC...");
            logger.info("Shutting down internal exchange...");
            exchange.close();
            logger.info("Internal exchange shut down...");
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            logger.info("CapitalMC stopped.");
        });
    }

}
