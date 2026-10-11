package nhcm.capitalmc;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import nhcm.advmatch.Exchange;
import nhcm.capitalmc.backend.ExchangeRuntime;
import nhcm.capitalmc.backend.NetworkConfig;
import nhcm.capitalmc.command.CapitalCommands;
import nhcm.capitalmc.management.GuiInputManager;
import nhcm.capitalmc.management.GuiManager;
import nhcm.capitalmc.management.ServerUserManager;
import nhcm.capitalmc.service.ApplicationExecutors;
import nhcm.capitalmc.service.GameServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

public class CapitalMC implements DedicatedServerModInitializer
{
    public static GameServices services;
    public static Exchange exchange = null;
    public static CompletableFuture<Void> accountsReady;
    public static ExchangeRuntime runtime;
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
                services.refreshShared();
            }
        });

        registerStops();

        logger.info("CapitalMC initialized.");
    }

    private void initializeExchange()
    {
        try
        {
            var directory = FabricLoader.getInstance().getGameDir().resolve("capitalmc");
            var config = NetworkConfig.load(FabricLoader.getInstance().getConfigDir().resolve("capitalmc.json"));
            runtime = new ExchangeRuntime(config, directory, serverUserManager);
            exchange = runtime.exchange;
            accountsReady = runtime.ready;
            accountsReady.whenComplete((unused, failure) -> {
                if (failure != null) logger.error("Exchange connection or account recovery failed; trading remains unavailable", failure);
            });
            logger.info("CapitalMC exchange mode: {}", config.mode);
        }
        catch (Exception error)
        {
            throw new IllegalStateException("Cannot initialize CapitalMC exchange connection", error);
        }
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
            new Thread(() -> {
                try
                {
                    runtime.stop().join();
                }
                catch (Throwable failure)
                {
                    logger.error("Exchange shutdown failed; recovery is required", failure);
                }
                finally
                {
                    ApplicationExecutors.shutdown();
                }
            }, "capitalmc-shutdown").start();
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            logger.info("CapitalMC stopped.");
        });
    }

}
