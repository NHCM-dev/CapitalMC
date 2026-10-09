package nhcm.capitalmc.management;

import net.minecraft.server.level.ServerPlayer;
import nhcm.capitalmc.gui.TradingGui;
import nhcm.capitalmc.gui.PageType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GuiManager {
    private final Map<UUID, TradingGui> tradingGuis = new HashMap<>();

    public void openTradingGui(ServerPlayer player) {
        TradingGui gui = tradingGuis.computeIfAbsent(player.getUUID(), id -> new TradingGui(player));
        if (!gui.isOpen()) {
            gui.refresh();
            gui.open();
        }
    }

    public void openTradingGui(ServerPlayer player, PageType page) {
        TradingGui gui = tradingGuis.computeIfAbsent(player.getUUID(), id -> new TradingGui(player));
        gui.showPage(page);
        if (!gui.isOpen() && !gui.open()) {
            throw new IllegalStateException("GUI could not be opened");
        }
    }

    public void closeTradingGui(ServerPlayer player) {
        TradingGui gui = tradingGuis.get(player.getUUID());
        if (gui != null && gui.isOpen()) {
            gui.close();
        }
    }

    public void removePlayer(ServerPlayer player) {
        closeTradingGui(player);
        tradingGuis.remove(player.getUUID());
    }

    public void closeAll() {
        var openGuis = java.util.List.copyOf(tradingGuis.values());
        tradingGuis.clear();
        openGuis.forEach(TradingGui::close);
    }
}
