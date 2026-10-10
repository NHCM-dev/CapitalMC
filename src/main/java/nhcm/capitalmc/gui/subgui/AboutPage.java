package nhcm.capitalmc.gui.subgui;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.Items;
import nhcm.capitalmc.gui.BaseSubGui;
import nhcm.capitalmc.gui.PageType;
import nhcm.capitalmc.gui.TradingGui;

public class AboutPage extends BaseSubGui
{
    public AboutPage(TradingGui gui)
    {
        super(gui, PageType.ABOUT);
        setCenterItem(owner -> TradingGui.icon(Items.BOOK));
    }

    public static String version()
    {
        return FabricLoader.getInstance().getModContainer("nhcm_capitalmc").orElseThrow()
                           .getMetadata().getVersion().getFriendlyString();
    }

    @Override
    public void render(TradingGui gui)
    {
        gui.setSlot(13, TradingGui.icon(gui.getPlayer(), Items.BOOK, "CapitalMC", "ui.about.description"));
        gui.setSlot(20, TradingGui.icon(gui.getPlayer(), Items.NAME_TAG, "ui.about.version", version()));
        gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PLAYER_HEAD, "ui.about.author", "NHCM"));
        gui.setSlot(24, TradingGui.icon(gui.getPlayer(), Items.COMPARATOR, "AdvMatch", "ui.about.engine"));
        gui.setSlot(31, TradingGui.icon(gui.getPlayer(), Items.WRITABLE_BOOK, "ui.about.links", "ui.about.links_lore"));
    }
}
