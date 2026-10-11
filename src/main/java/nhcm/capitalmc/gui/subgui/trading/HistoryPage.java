package nhcm.capitalmc.gui.subgui.trading;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.world.item.Items;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.exchange.*;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.service.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * One native cursor page per request, including scan-budget-limited empty pages.
 */
public class HistoryPage extends BaseSubGui
{
    private record Context(UUID user, long epoch, SymbolType type, ReadCursor cursor)
    {
    }

    private record Data(ReadResponse<?> rows, List<SymbolView> symbols, List<AssetView> assets)
    {
    }

    private final PollingValue<Data> cache = new PollingValue<>(null);
    private final Deque<ReadCursor> previous = new ArrayDeque<>();
    private ReadCursor cursor = new ReadCursor();
    private UUID user;
    private long epoch;
    private SymbolType type;
    private Data displayed;

    public HistoryPage(TradingGui gui, PageType page)
    {
        super(gui, page);
    }

    @Override
    public void onEnter()
    {
        reset();
    }

    private void reset()
    {
        cursor = new ReadCursor();
        previous.clear();
        cache.invalidate();
        displayed = null;
    }

    @Override
    public void render(TradingGui gui)
    {
        if (!GuiActions.ready(gui))
        {
            return;
        }
        if (gui.getUser().isEmpty())
        {
            GuiWidgets.button(gui, 22, Items.PLAYER_HEAD, "gui.capitalmc.account.login_required", "", () -> gui.showPage(PageType.ACCESS));
            return;
        }
        var current = gui.getUser().orElseThrow();
        long version = CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID());
        var selected = gui.activityType();
        if (!Objects.equals(user, current.getUuid()) || version != epoch || selected != type)
        {
            user = current.getUuid();
            epoch = version;
            type = selected;
            reset();
        }
        GuiWidgets.activityNavigation(gui, getPage());
        var context = new Context(user, epoch, type, cursor);
        displayed = cache.get(context, 5000, () -> SymbolUtil.allSymbols().thenCompose(symbols -> {
            var scope = ActivityScope.matching(symbols, context.type());
            CompletableFuture<? extends ReadResponse<?>> rows = switch (getPage())
            {
                case ORDER_HISTORY -> ActivityScope.page(next -> TradeUtil.orderPage(current, true, 0, next), context.cursor(),
                                                        row -> scope.test(row) && ActivityScope.terminal(row));
                case POSITION_HISTORY -> ActivityScope.page(next -> TradeUtil.positionPage(current, true, 0, next), context.cursor(),
                                                           row -> scope.test(row) && row.closed());
                default -> ActivityScope.page(next -> TradeUtil.tradePage(current, 0, next), context.cursor(),
                                              scope);
            };
            return rows.thenApply(r -> new Data(r, symbols, List.of()))
                       .thenCombine(AssetUtil.allAssets(), (d, a) -> new Data(d.rows, d.symbols, a));
        }));
        if (displayed == null || cache.error() != null)
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PAPER, cache.error() == null ? "ui.loading" : "ui.query_failed", ""));
            return;
        }
        int slot = 18;
        for (Object row : ActivityScope.newest(displayed.rows.values()))
        {
            long id, market;
            String details;
            if (row instanceof OrderView o)
            {
                id = o.orderId();
                market = o.symbolId();
                details = FinancialText.lore(o, displayed.symbols, gui.getPlayer());
            }
            else if (row instanceof PositionView p)
            {
                id = p.positionId();
                market = p.symbolId();
                details = FinancialText.lore(p, displayed.symbols, gui.getPlayer());
            }
            else
            {
                var t = (TradeView) row;
                id = t.recordId();
                market = t.symbolId();
                details = FinancialText.lore(t, displayed.symbols, gui.getPlayer());
            }
            var view = market(market);
            var builder = new GuiElementBuilder(GameServices.get().assets.find(view.baseAssetId()).orElseThrow().prototype())
                    .setName(TradingGui.text(gui.getPlayer(), view.name() + " #" + id));
            for (String line : details.split("\n"))
                builder.addLoreLine(TradingGui.text(gui.getPlayer(), line));
            builder.setCallback((i, t, c, g) -> GuiActions.run(gui, () -> gui.showTrading(view)));
            gui.setSlot(slot++, builder.build());
        }
        if (displayed.rows.values().isEmpty())
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PAPER, "ui.empty", displayed.rows.page().hasMore() ? "ui.history.scan_more" : ""));
        }
    }

    private SymbolView market(long id)
    {
        return displayed.symbols.stream().filter(s -> s.symbolId() == id).findFirst().orElseThrow();
    }

    private int scale(long asset)
    {
        return displayed.assets.stream().filter(a -> a.assetId() == asset).findFirst().orElseThrow().scale();
    }

    private String price(long id, long raw)
    {
        var s = market(id);
        return AssetUtil.units(s.baseAssetId(), scale(s.baseAssetId())).price(raw, AssetUtil.units(s.quoteAssetId(), scale(s.quoteAssetId())));
    }

    private String quantity(long id, long raw)
    {
        return GameServices.get().markets.quantityUnits(market(id)).format(raw);
    }

    private String quote(long id, long raw)
    {
        return AssetUtil.units(market(id).quoteAssetId(), scale(market(id).quoteAssetId())).format(raw);
    }

    @Override
    public void renderOverlay(TradingGui gui)
    {
        GuiWidgets.button(gui, 46, Items.END_CRYSTAL, "gui.capitalmc.refresh", "", this::reset);
        if (!previous.isEmpty())
        {
            GuiWidgets.button(gui, 51, Items.ARROW, "ui.page.previous", "", () -> {
                cursor = previous.pop();
                cache.invalidate();
            });
        }
        if (cache.error() == null && displayed != null && displayed.rows.page().hasMore())
        {
            var next = displayed.rows.page().next();
            GuiWidgets.button(gui, 52, Items.ARROW, "ui.page.next", "", () -> {
                previous.push(cursor);
                cursor = next;
                cache.invalidate();
            });
        }
    }
}
