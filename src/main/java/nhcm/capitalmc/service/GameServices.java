package nhcm.capitalmc.service;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import nhcm.advmatch.command.persistence.Flush;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.backend.NetworkConfig;
import nhcm.capitalmc.gui.TradingGui;

import java.util.concurrent.*;
import java.util.function.Supplier;

public class GameServices
{
    public final MinecraftServer server;
    public final ServiceState state;
    public final McAssetRegistry assets;
    public final SettlementService settlements;
    public final CurrencyService currency;
    public final ItemBankService items;
    public final MarketService markets;
    public final AdministrationService administration;
    private final CompletableFuture<Void> ready;
    private CompletableFuture<Void> refreshing = CompletableFuture.completedFuture(null);
    private boolean reloadPending;
    private volatile long appliedSharedRevision;

    public GameServices(MinecraftServer server)
    {
        this.server = server;
        state = new ServiceState(FabricLoader.getInstance().getGameDir().resolve("capitalmc/services.json"));
        if (CapitalMC.runtime.config.mode == NetworkConfig.Mode.REMOTE) CapitalMC.serverUserManager.setAccessPolicy(user -> {
            var restrictions = state.section("userRestrictions").getAsJsonObject("userRestrictions");
            var restriction = restrictions == null ? null : restrictions.getAsJsonObject(user.getUuid().toString());
            return restriction == null || !restriction.get("banned").getAsBoolean();
        });
        assets = new McAssetRegistry(this);
        settlements = new SettlementService(this);
        currency = new CurrencyService(this);
        items = new ItemBankService(this);
        markets = new MarketService(this);
        administration = new AdministrationService(this);
        items.registerEvents();
        ready = CapitalMC.accountsReady.thenCompose(unused -> state.useShared(CapitalMC.runtime.backend))
                                       .thenCompose(unused -> items.initialize())
                                       .thenCompose(unused -> CapitalMC.runtime.backend.exclusive("configuration", () -> state.refreshShared()
                                               .thenCompose(changed -> assets.initialize())
                                               .thenCompose(ignored -> onServer(() -> assets.registerElectronic("mc_exp", "Exp", 0, new ItemStack(Items.EXPERIENCE_BOTTLE))).thenCompose(registration -> registration))
                                               .thenCompose(ignored -> currency.initialize())))
                                       .thenCompose(unused -> markets.initialize())
                                       .thenCompose(unused -> CapitalMC.exchange.submit(new Flush()).thenAccept(status -> {
                                           if (!status.enabled() || status.faulted() || status.durableThrough() < status.sequence())
                                           {
                                               throw new IllegalStateException("Service initialization not durable");
                                           }
                                       }));
        ready.whenComplete((unused, error) -> {
            if (error == null) appliedSharedRevision = state.sharedRevision();
            if (error != null)
            {
                CapitalMC.logger.error("Cannot initialize Minecraft exchange services", error);
            }
        });
    }

    public CompletableFuture<Void> ready()
    {
        return ready;
    }

    public void refreshShared()
    {
        if (!ready.isDone() || ready.isCompletedExceptionally() || !refreshing.isDone() || assets.editing()) return;
        refreshing = CapitalMC.serverUserManager.refreshDirectory().thenCompose(unused -> state.refreshShared())
                              .thenCompose(changed -> onServer(() -> {
                                  long revision = state.sharedRevision();
                                  reloadPending |= changed || appliedSharedRevision != revision;
                                  if (!reloadPending || assets.editing()) return CompletableFuture.<Void>completedFuture(null);
                                  return assets.initialize().thenCompose(unused -> markets.reload()).thenCompose(unused -> onServer(() -> {
                                      reloadPending = false;
                                      appliedSharedRevision = revision;
                                      return (Void) null;
                                  }));
                              }).thenCompose(future -> future));
        refreshing.exceptionally(error -> {
            CapitalMC.logger.debug("Shared exchange metadata refresh failed", error);
            return null;
        });
    }

    public <T> CompletableFuture<T> onServer(Supplier<T> work)
    {
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try
            {
                future.complete(work.get());
            } catch (Throwable error)
            {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    public void message(ServerPlayer player, String key, Object... args)
    {
        player.sendSystemMessage(TradingGui.text(player, key, args));
    }

    public static GameServices get()
    {
        if (CapitalMC.services == null)
        {
            throw new IllegalStateException("Services are not initialized");
        }
        return CapitalMC.services;
    }
}
