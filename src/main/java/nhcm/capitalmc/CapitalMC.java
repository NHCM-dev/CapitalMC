package nhcm.capitalmc;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import nhcm.advmatch.Exchange;
import nhcm.advmatch.TransportOptions;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.command.CapitalCommands;
import nhcm.capitalmc.management.GuiInputManager;
import nhcm.capitalmc.management.GuiManager;
import nhcm.capitalmc.management.ServerUserManager;
import nhcm.capitalmc.service.ApplicationExecutors;
import nhcm.capitalmc.service.GameServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class CapitalMC implements DedicatedServerModInitializer
{
    public static GameServices services;
    public static Exchange exchange = null;
    public static CompletableFuture<Void> accountsReady;
    public static final GuiManager guiManager = new GuiManager();
    public static final GuiInputManager guiInputManager = new GuiInputManager();
    public static final ServerUserManager serverUserManager = new ServerUserManager(
            FabricLoader.getInstance().getGameDir().resolve("capitalmc").resolve("users.dat"));
    public static final Logger logger = LoggerFactory.getLogger("CapitalMC");

    @Override
    public void onInitializeServer()
    {
        CapitalCommands.register();
        guiInputManager.registerEvents();
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            guiInputManager.cancel(handler.player.getUUID());
            if (services != null)
            {
                services.items.cancelSelection(handler.player.getUUID());
            }
            guiManager.removePlayer(handler.player);
            serverUserManager.logout(handler.player.getUUID());
        });

        initializeExchange();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            services = new GameServices(server);
            guiManager.startRefreshing(server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (services != null && server.getTickCount() % 20 == 0)
            {
                services.markets.tick();
            }
        });

        registerStops();

        logger.info("CapitalMC initialized.");
    }

    private void initializeExchange()
    {
        logger.info("Loading internal exchange...");

        ExchangeConfig config = exchangeConfig();
        Executor callback = ApplicationExecutors.CALLBACKS;

        int responseBatchSize = 256;
        int spinCount = 64;
        long activeParkNanos = 1000L;
        int maxOutstanding = (int) Math.min(config.maxPendingRequests(), 2147483647L);

        TransportOptions transportOptions = new TransportOptions(
                responseBatchSize, spinCount, activeParkNanos, maxOutstanding
        );

        exchange = new Exchange(config, callback, transportOptions);
        accountsReady = serverUserManager.connectExchange(exchange);
        accountsReady.whenComplete((unused, failure) -> {
            if (failure != null)
            {
                logger.error("Account binding recovery failed; trading services remain unavailable", failure);
            }
        });

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
        boolean indexUserOpenOrders = true;
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
            guiManager.stopRefreshing();
            guiManager.closeAll();
            guiInputManager.clear();
            if (services != null)
            {
                services.items.clearSelections();
            }
            serverUserManager.clearSessions();
            logger.info("Stopping CapitalMC...");
            logger.info("Shutting down internal exchange...");
            exchange.close();
            logger.info("Internal exchange shut down...");
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ApplicationExecutors.shutdown();
            logger.info("CapitalMC stopped.");
        });
    }

}
