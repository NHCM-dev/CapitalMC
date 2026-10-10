package nhcm.capitalmc.gui.subgui;

import net.minecraft.world.item.Items;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.exchange.SymbolUtil;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.service.GameServices;

import java.util.*;

public class ListSymbols extends BaseSubGui
{
    private final PollingValue<List<SymbolView>> symbols = new PollingValue<>(List.of());
    private final Map<Long, PollingValue<SymbolUtil.Quote>> quotes = new HashMap<>();
    private boolean contract;
    private String search = "";
    private int page, count;

    public void setContract(boolean contract)
    {
        this.contract = contract;
        page = 0;
    }

    public ListSymbols(TradingGui gui)
    {
        super(gui, PageType.LIST_SYMBOLS);
    }

    private String quoteLore(TradingGui gui, SymbolView symbol)
    {
        var polling = quotes.computeIfAbsent(symbol.symbolId(), id -> new PollingValue<>(null));
        var quote = polling.get(symbol.symbolId(), () -> SymbolUtil.quote(symbol.symbolId()));
        String lore = GuiWidgets.text(gui, "ui.symbols.type", symbol.type());
        if (quote == null || polling.error() != null)
        {
            return lore + "\n" + GuiWidgets.text(gui, polling.error() == null ? "ui.loading" : "ui.query_failed");
        }
        var registry = GameServices.get().assets;
        int baseScale = registry.find(symbol.baseAssetId()).map(asset -> asset.scale()).orElse(0);
        int quoteScale = registry.find(symbol.quoteAssetId()).map(asset -> asset.scale()).orElse(0);
        String price = quote.latest() <= 0 ? GuiWidgets.text(gui, "ui.trade.no_trades")
                : registry.units(symbol.baseAssetId(), baseScale).price(quote.latest(), registry.units(symbol.quoteAssetId(), quoteScale));
        lore += "\n" + GuiWidgets.text(gui, "ui.trade.latest_price", price);
        if (quote.previousClose() <= 0 || quote.latest() <= 0)
        {
            return lore + "\n" + GuiWidgets.text(gui, "ui.symbols.no_previous_close");
        }
        String color = quote.latest() > quote.previousClose() ? "&a" : quote.latest() < quote.previousClose() ? "&c" : "&7";
        return lore + "\n" + GuiWidgets.text(gui, "ui.symbols.daily_change", color + quote.change());
    }

    @Override
    public void render(TradingGui gui)
    {
        if (!GuiActions.ready(gui))
        {
            return;
        }
        GuiWidgets.button(gui, 0, Items.COMPASS, "ui.search", search, () -> GuiActions.input(gui, "ui.search.input", String::strip, value -> {
            search = value.toLowerCase(Locale.ROOT);
            page = 0;
        }));
        GuiWidgets.button(gui, 1, Items.SPONGE, "ui.search.clear", "", () -> {
            search = "";
            page = 0;
        });
        GuiWidgets.option(gui, 7, Items.GOLDEN_BOOTS, "ui.symbols.spot", "", !contract, () -> {
            contract = false;
            page = 0;
            gui.refresh();
        });
        GuiWidgets.option(gui, 8, Items.CHAINMAIL_BOOTS, "ui.symbols.contract", "", contract, () -> {
            contract = true;
            page = 0;
            gui.refresh();
        });

        GuiWidgets.separator(gui);
        var filtered = symbols.get("symbols", SymbolUtil::allSymbols).stream()
                              .filter(s -> s.active() && (s.type() != SymbolType.Spot) == contract && (s.name().toLowerCase(Locale.ROOT).contains(search) || Long.toString(s.symbolId()).equals(search)))
                              .sorted(Comparator.comparing(SymbolView::name)).toList();
        count = filtered.size();
        page = GuiWidgets.clamp(page, count);
        for (int i = page * 27; i < Math.min(count, page * 27 + 27); i++)
        {
            var symbol = filtered.get(i);
            gui.setSlot(18 + i - page * 27, TradingGui.icon(gui.getPlayer(),
                                                            GuiWidgets.asset(gui, symbol.baseAssetId(), "", () -> gui.showTrading(symbol)), symbol.name(), quoteLore(gui, symbol)));
        }
        if (count == 0)
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PAPER, symbols.error() == null ? "ui.empty" : "ui.query_failed", ""));
        }
    }

    @Override
    public void renderOverlay(TradingGui gui)
    {
        GuiWidgets.paging(gui, page, count, value -> page = value);
    }
}
