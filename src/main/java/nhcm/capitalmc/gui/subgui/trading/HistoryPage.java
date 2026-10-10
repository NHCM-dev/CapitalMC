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
public final class HistoryPage extends BaseSubGui
{
    private record Context(UUID user, long epoch, long symbol, ReadCursor cursor)
    {
    }

    private record Data(ReadResponse<?> rows, List<SymbolView> symbols, List<AssetView> assets)
    {
    }

    private final PollingValue<Data> cache = new PollingValue<>(null);
    private final Deque<ReadCursor> previous = new ArrayDeque<>();
    private ReadCursor cursor = new ReadCursor();
    private UUID user;
    private long epoch, symbol;
    private Data displayed;

    public HistoryPage(TradingGui gui, PageType page)
    {
        super(gui, page);
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
        long selected = gui.getSelectedSymbol() == null ? 0 : gui.getSelectedSymbol().symbolId();
        if (!Objects.equals(user, current.getUuid()) || version != epoch || selected != symbol)
        {
            user = current.getUuid();
            epoch = version;
            symbol = selected;
            reset();
        }
        GuiWidgets.button(gui, 0, Items.END_CRYSTAL, "gui.capitalmc.refresh", "", this::reset);
        GuiWidgets.option(gui, 1, Items.BARRIER, "ui.history.all_symbols", "", symbol == 0, () -> {
            gui.clearSymbolFilter();
            reset();
        });
        GuiWidgets.option(gui, 6, Items.BOOK, "ui.order_history", "", getPage() == PageType.ORDER_HISTORY, () -> gui.showPage(PageType.ORDER_HISTORY));
        GuiWidgets.option(gui, 7, Items.SHIELD, "ui.position_history", "", getPage() == PageType.POSITION_HISTORY, () -> gui.showPage(PageType.POSITION_HISTORY));
        GuiWidgets.option(gui, 8, Items.PAPER, "ui.trades", "", getPage() == PageType.TRADES, () -> gui.showPage(PageType.TRADES));
        GuiWidgets.separator(gui);
        var context = new Context(user, epoch, symbol, cursor);
        displayed = cache.get(context, () -> {
            CompletableFuture<? extends ReadResponse<?>> rows = switch (getPage())
            {
                case ORDER_HISTORY -> TradeUtil.orderPage(current, true, symbol, cursor);
                case POSITION_HISTORY -> TradeUtil.positionPage(current, true, symbol, cursor);
                default -> TradeUtil.tradePage(current, symbol, cursor);
            };
            return rows.thenCombine(SymbolUtil.allSymbols(), (r, s) -> new Data(r, s, List.of()))
                       .thenCombine(AssetUtil.allAssets(), (d, a) -> new Data(d.rows, d.symbols, a));
        });
        if (displayed == null)
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PAPER, cache.error() == null ? "ui.loading" : "ui.query_failed", ""));
            return;
        }
        int slot = 18;
        for (Object row : displayed.rows.values())
        {
            long id, market;
            String details;
            if (row instanceof OrderView o)
            {
                id = o.orderId();
                market = o.symbolId();
                details = GuiWidgets.text(gui, "ui.order_history.details", o.side(), o.type(), o.timeInForce(), price(market, o.price()), quantity(market, o.filling().unfilled()), o.filling().status());
            }
            else if (row instanceof PositionView p)
            {
                id = p.positionId();
                market = p.symbolId();
                details = GuiWidgets.text(gui, "ui.position_history.details", p.side(), quantity(market, p.size()), price(market, p.averageEntryPrice()), price(market, p.predictedLiquidationPrice()), quote(market, p.realizedPnL()), p.leverage(), p.marginMode());
            }
            else
            {
                var t = (TradeView) row;
                id = t.recordId();
                market = t.symbolId();
                details = GuiWidgets.text(gui, "ui.trade_history.details", price(market, t.trade().price()), quantity(market, t.trade().quantity()), t.timestamp(), t.trade().makerOrderId(), t.trade().takerOrderId());
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
        if (!previous.isEmpty())
        {
            GuiWidgets.button(gui, 51, Items.ARROW, "ui.page.previous", "", () -> {
                cursor = previous.pop();
                cache.invalidate();
            });
        }
        if (displayed != null && displayed.rows.page().hasMore())
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
