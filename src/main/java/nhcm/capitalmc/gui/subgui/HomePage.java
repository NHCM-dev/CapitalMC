package nhcm.capitalmc.gui.subgui;

import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.Items;
import nhcm.capitalmc.gui.BaseSubGui;
import nhcm.capitalmc.gui.GuiWidgets;
import nhcm.capitalmc.gui.PageType;
import nhcm.capitalmc.gui.TradingGui;

public class HomePage extends BaseSubGui
{
    public HomePage(TradingGui gui)
    {
        super(gui, PageType.HOME);
    }

    @Override
    public void render(TradingGui gui)
    {
        if (gui.getPlayer().permissions().hasPermission(Permissions.COMMANDS_OWNER))
        {
            GuiWidgets.button(gui, 8, Items.COMPASS, "ui.admin", "", () -> gui.showPage(PageType.ADMIN));
        }
        GuiWidgets.button(gui, 22, Items.PLAYER_HEAD, "ui.access", "", () -> gui.showPage(PageType.ACCESS));
        gui.setSlot(20, TradingGui.button(gui.getPlayer(), Items.EMERALD, "gui.capitalmc.symbols", "gui.capitalmc.symbols.description",
                                          () -> gui.showPage(PageType.LIST_SYMBOLS)));
        gui.setSlot(24, TradingGui.button(gui.getPlayer(), Items.BOOK, "gui.capitalmc.account", "gui.capitalmc.account.description",
                                          () -> gui.showPage(PageType.MY_ACCOUNT)));
    }
}
