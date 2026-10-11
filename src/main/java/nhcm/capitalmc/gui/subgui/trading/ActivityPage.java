package nhcm.capitalmc.gui.subgui.trading;

import net.minecraft.world.item.Items;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.exchange.*;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.service.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ActivityPage extends BaseSubGui
{
    private record Data(List<OrderView> orders, List<PositionView> positions, List<SymbolView> symbols,
                        List<AssetView> assets, ReadPage queryPage)
    {
    }

    private final PollingValue<Data> cache = new PollingValue<>(new Data(List.of(), List.of(), List.of(), List.of(), new ReadPage()));
    private int page, count;
    private UUID context;
    private long epoch;
    private SymbolType filter;
    private ReadCursor cursor = new ReadCursor();
    private final Deque<ReadCursor> previous = new ArrayDeque<>();
    private ReadPage queryPage = new ReadPage();

    private void reset()
    {
        cursor = new ReadCursor();
        previous.clear();
        cache.invalidate();
        page = 0;
    }

    public ActivityPage(TradingGui gui, PageType type)
    {
        super(gui, type);
    }

    private SymbolView symbol(Data data, long id)
    {
        return data.symbols.stream().filter(s -> s.symbolId() == id).findFirst().orElseThrow();
    }

    private int scale(Data data, long id)
    {
        return data.assets.stream().filter(a -> a.assetId() == id).map(AssetView::scale).findFirst().orElse(0);
    }

    private String price(Data data, SymbolView symbol, long raw)
    {
        return AssetUtil.units(symbol.baseAssetId(), scale(data, symbol.baseAssetId())).price(raw, AssetUtil.units(symbol.quoteAssetId(), scale(data, symbol.quoteAssetId())));
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
        var user = gui.getUser().orElseThrow();
        long version = CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID());
        var type = gui.activityType();
        if (!Objects.equals(context, user.getUuid()) || epoch != version || filter != type)
        {
            context = user.getUuid();
            epoch = version;
            filter = type;
            reset();
        }
        var data = cache.get(List.of(context, epoch, filter, cursor), () -> {
            return SymbolUtil.allSymbols().thenCompose(symbols -> {
                CompletableFuture<Data> rows = getPage() == PageType.ORDERS
                        ? ActivityScope.page(next -> TradeUtil.orderPage(user, false, 0, next), cursor,
                                             row -> ActivityScope.matches(row, symbols, type) && !ActivityScope.terminal(row))
                                       .thenApply(r -> new Data(r.values(), List.of(), symbols, List.of(), r.page()))
                        : ActivityScope.page(next -> TradeUtil.positionPage(user, false, 0, next), cursor,
                                             row -> ActivityScope.matches(row, symbols, type) && !row.closed() && row.size() > 0)
                                       .thenApply(r -> new Data(List.of(), r.values(), symbols, List.of(), r.page()));
                return rows;
            })
                       .thenCombine(AssetUtil.allAssets(), (d, a) -> new Data(d.orders, d.positions, d.symbols, a, d.queryPage));
        });
        queryPage = data.queryPage;
        GuiWidgets.activityNavigation(gui, getPage());
        if (cache.error() != null)
        {
            count = 0;
            queryPage = new ReadPage();
            GuiWidgets.button(gui, 22, Items.PAPER, "ui.query_failed", ExchangeMessages.error(gui.getPlayer(), cache.error()), this::reset);
            return;
        }
        if (!cache.loaded())
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.CLOCK, "ui.loading", ""));
            return;
        }
        if (getPage() == PageType.ORDERS)
        {
            var orders = data.orders;
            count = orders.size();
            page = GuiWidgets.clamp(page, count);
            for (int i = page * 27; i < Math.min(count, page * 27 + 27); i++)
            {
                var order = orders.get(i);
                var s = symbol(data, order.symbolId());
                String lore = FinancialText.lore(order, data.symbols, gui.getPlayer()) + "\n" + GuiWidgets.text(gui, "ui.activity.cancel");
                GuiWidgets.button(gui, 18 + i - page * 27, Items.PAPER, s.name() + " #" + order.orderId(), lore, () -> GuiActions.confirm(gui, "ui.confirm.cancel_order", () -> GuiActions.task(gui, TradeUtil.cancel(user, order.orderId(), System.currentTimeMillis()), "ui.success.cancel_order", order.orderId())));
            }
        }
        else
        {
            var positions = data.positions;
            count = positions.size();
            page = GuiWidgets.clamp(page, count);
            for (int i = page * 27; i < Math.min(count, page * 27 + 27); i++)
            {
                var position = positions.get(i);
                var s = symbol(data, position.symbolId());
                String lore = FinancialText.lore(position, data.symbols, gui.getPlayer()) + "\n" + GuiWidgets.text(gui, "ui.activity.position");
                GuiWidgets.button(gui, 18 + i - page * 27, Items.SHIELD, s.name() + " #" + position.positionId(), lore, () -> {
                    gui.selectPosition(position.positionId());
                    gui.showPage(PageType.POSITION);
                });
            }
        }
        if (count == 0)
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PAPER, "ui.empty", queryPage.hasMore() ? "ui.history.scan_more" : ""));
        }
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
        if (queryPage.hasMore())
        {
            var next = queryPage.next();
            GuiWidgets.button(gui, 52, Items.ARROW, "ui.page.next", "", () -> {
                previous.push(cursor);
                cursor = next;
                cache.invalidate();
            });
        }
    }
}
