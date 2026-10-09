package nhcm.capitalmc.gui.subgui;

import net.minecraft.world.item.Items;
import nhcm.capitalmc.gui.BaseSubGui;
import nhcm.capitalmc.gui.TradingGui;
import nhcm.capitalmc.gui.PageType;

public class HomePage extends BaseSubGui {
    public HomePage(TradingGui gui) {
        super(gui, PageType.HOME);
    }

    @Override
    public void render(TradingGui gui) {
        gui.setSlot(20, TradingGui.button(gui.getPlayer(), Items.EMERALD, "gui.capitalmc.symbols", "gui.capitalmc.symbols.description",
                () -> gui.showPage(PageType.LIST_SYMBOLS)));
        gui.setSlot(24, TradingGui.button(gui.getPlayer(), Items.BOOK, "gui.capitalmc.account", "gui.capitalmc.account.description",
                () -> gui.showPage(PageType.MY_ACCOUNT)));
    }
}
