package nhcm.capitalmc.management;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.gui.PageType;
import nhcm.capitalmc.gui.TradingGui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class GuiManager
{
    private ScheduledExecutorService refreshThread;
    private final AtomicBoolean queued = new AtomicBoolean();

    public void startRefreshing(MinecraftServer server)
    {
        stopRefreshing();
        refreshThread = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "CapitalMC-gui-refresh");
            thread.setDaemon(true);
            return thread;
        });
        refreshThread.scheduleAtFixedRate(() -> {
            if (!queued.compareAndSet(false, true))
            {
                return;
            }
            server.execute(() -> {
                try
                {
                    for (TradingGui gui : List.copyOf(tradingGuis.values()))
                        if (gui.isOpen())
                        {
                            try
                            {
                                gui.refresh();
                            } catch (RuntimeException error)
                            {
                                CapitalMC.logger.error("Cannot refresh GUI", error);
                            }
                        }
                } finally
                {
                    queued.set(false);
                }
            });
        }, 50, 50, TimeUnit.MILLISECONDS);
    }

    public void stopRefreshing()
    {
        if (refreshThread != null)
        {
            refreshThread.shutdownNow();
            refreshThread = null;
        }
        queued.set(false);
    }

    private final Map<UUID, TradingGui> tradingGuis = new HashMap<>();

    public TradingGui getTradingGui(ServerPlayer player)
    {
        return tradingGuis.computeIfAbsent(player.getUUID(), id -> new TradingGui(player));
    }

    public void openTradingGui(ServerPlayer player)
    {
        CapitalMC.guiInputManager.cancel(player.getUUID());
        TradingGui gui = tradingGuis.computeIfAbsent(player.getUUID(), id -> new TradingGui(player));
        if (!gui.isOpen())
        {
            gui.refresh();
            gui.open();
        }
    }

    public void openTradingGui(ServerPlayer player, PageType page)
    {
        CapitalMC.guiInputManager.cancel(player.getUUID());
        TradingGui gui = tradingGuis.computeIfAbsent(player.getUUID(), id -> new TradingGui(player));
        gui.showPage(page);
        if (!gui.isOpen() && !gui.open())
        {
            throw new IllegalStateException("GUI could not be opened");
        }
    }

    public void closeTradingGui(ServerPlayer player)
    {
        TradingGui gui = tradingGuis.get(player.getUUID());
        if (gui != null && gui.isOpen())
        {
            gui.close();
        }
    }

    public void removePlayer(ServerPlayer player)
    {
        closeTradingGui(player);
        tradingGuis.remove(player.getUUID());
    }

    public void closeAll()
    {
        var openGuis = List.copyOf(tradingGuis.values());
        tradingGuis.clear();
        openGuis.stream().filter(TradingGui::isOpen).forEach(TradingGui::close);
    }
}
