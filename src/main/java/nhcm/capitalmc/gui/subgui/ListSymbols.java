package nhcm.capitalmc.gui.subgui;

import net.minecraft.world.item.Items;
import nhcm.capitalmc.gui.BaseSubGui;
import nhcm.capitalmc.gui.TradingGui;
import nhcm.capitalmc.gui.PageType;

public class ListSymbols extends BaseSubGui {
    public ListSymbols(TradingGui gui) {
        super(gui, PageType.LIST_SYMBOLS);
    }

    @Override
    public void render(TradingGui gui) {
        gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PAPER, "gui.capitalmc.symbols", "gui.capitalmc.symbols.unavailable"));
    }
}
