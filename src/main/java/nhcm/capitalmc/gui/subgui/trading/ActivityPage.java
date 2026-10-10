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
    private long epoch, filter;
    private ReadCursor cursor = new ReadCursor();
    private final Deque<ReadCursor> previous = new ArrayDeque<>();
    private ReadPage queryPage = new ReadPage();

    private void reset()
    {
        cursor = new ReadCursor();
        previous.clear();
        cache.invalidate();
        selected = null;
        page = 0;
    }

    private PositionView selected;

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

    private void closePosition(PositionView position, long quantity, OrderType type, long price)
    {
        var gui = getGui();
        var user = GuiActions.user(gui);
        GuiActions.confirm(gui, "ui.confirm.close_position", () -> GuiActions.task(gui, UserUtil.getUser(user).thenCompose(backend -> {
            if (backend.id() != position.userId())
            {
                throw new SecurityException("ui.session.changed");
            }
            return GameServices.get().settlements.place(user, position.symbolId(), position.side() == PositionSide.Long ? OrderSide.Ask : OrderSide.Bid, type, TimeInForce.IOC, type == OrderType.Market ? 0 : price, quantity,
                                                        new ContractOrderOptions().withPositionId(position.positionId()).withReduceOnly(true).withLeverage(position.leverage()).withMarginMode(position.marginMode()).withPriceLimit(type == OrderType.Market ? price : 0));
        }), "ui.success.close_order", position.positionId()));
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
        long symbolId = gui.getSelectedSymbol() == null ? 0 : gui.getSelectedSymbol().symbolId();
        if (!Objects.equals(context, user.getUuid()) || epoch != version || filter != symbolId)
        {
            context = user.getUuid();
            epoch = version;
            filter = symbolId;
            reset();
        }
        var data = cache.get(List.of(context, epoch, filter, cursor), () -> {
            CompletableFuture<Data> rows = getPage() == PageType.ORDERS
                    ? TradeUtil.orderPage(user, false, symbolId, cursor).thenApply(r -> new Data(r.values(), List.of(), List.of(), List.of(), r.page()))
                    : TradeUtil.positionPage(user, false, symbolId, cursor).thenApply(r -> new Data(List.of(), r.values(), List.of(), List.of(), r.page()));
            return rows.thenCombine(SymbolUtil.allSymbols(), (d, s) -> new Data(d.orders, d.positions, s, List.of(), d.queryPage))
                       .thenCombine(AssetUtil.allAssets(), (d, a) -> new Data(d.orders, d.positions, d.symbols, a, d.queryPage));
        });
        queryPage = data.queryPage;
        GuiWidgets.option(gui, 7, Items.BOOK, "ui.orders", "", getPage() == PageType.ORDERS, () -> gui.showPage(PageType.ORDERS));
        GuiWidgets.option(gui, 8, Items.SHIELD, "ui.positions", "", getPage() == PageType.POSITIONS, () -> gui.showPage(PageType.POSITIONS));
        GuiWidgets.button(gui, 6, Items.BOOK, getPage() == PageType.ORDERS ? "ui.order_history" : "ui.position_history", "", () -> gui.showPage(getPage() == PageType.ORDERS ? PageType.ORDER_HISTORY : PageType.POSITION_HISTORY));
        GuiWidgets.button(gui, 5, Items.PAPER, "ui.trades", "", () -> gui.showPage(PageType.TRADES));
        GuiWidgets.separator(gui);
        if (getPage() == PageType.ORDERS)
        {
            var orders = data.orders.stream().filter(o -> symbolId == 0 || o.symbolId() == symbolId).toList();
            count = orders.size();
            page = GuiWidgets.clamp(page, count);
            for (int i = page * 27; i < Math.min(count, page * 27 + 27); i++)
            {
                var order = orders.get(i);
                var s = symbol(data, order.symbolId());
                String lore = GuiWidgets.text(gui, "ui.order.details", order.side(), order.type(), order.timeInForce(), price(data, s, order.price()), GameServices.get().markets.quantityUnits(s).format(order.filling().unfilled()), order.filling().status());
                GuiWidgets.button(gui, 18 + i - page * 27, Items.PAPER, s.name() + " #" + order.orderId(), lore, () -> GuiActions.confirm(gui, "ui.confirm.cancel_order", () -> GuiActions.task(gui, TradeUtil.cancel(user, order.orderId(), System.currentTimeMillis()), "ui.success.cancel_order", order.orderId())));
            }
        }
        else
        {
            var positions = data.positions.stream().filter(p -> !p.closed() && (symbolId == 0 || p.symbolId() == symbolId)).toList();
            count = positions.size();
            page = GuiWidgets.clamp(page, count);
            if (selected != null)
            {
                selected = positions.stream().filter(p -> p.positionId() == selected.positionId()).findFirst().orElse(null);
                if (selected != null)
                {
                    var p = selected;
                    var s = symbol(data, p.symbolId());
                    GuiWidgets.button(gui, 0, Items.NETHER_STAR, "ui.position.close_all", GameServices.get().markets.quantityUnits(s).format(p.size()), () -> GuiActions.input(gui, GuiWidgets.text(gui, "ui.trade.market_cap_input") + "\n" + GuiWidgets.priceInput(gui, s.baseAssetId(), s.quoteAssetId()), text -> AssetUtil.units(s.baseAssetId(), scale(data, s.baseAssetId())).parsePrice(text, AssetUtil.units(s.quoteAssetId(), scale(data, s.quoteAssetId()))), v -> {
                                                                                                                                                                        })
                                                                                                                                                                        .thenAccept(cap -> {
                                                                                                                                                                            if (cap.isPresent())
                                                                                                                                                                            {
                                                                                                                                                                                closePosition(p, p.size(), OrderType.Market, cap.get());
                                                                                                                                                                            }
                                                                                                                                                                        }).exceptionally(error -> {
                                GuiActions.error(gui, error);
                                return null;
                            }));
                    GuiWidgets.button(gui, 1, Items.BOW, "ui.position.close_limit", "", () -> GuiActions.input(gui, GuiWidgets.priceInput(gui, s.baseAssetId(), s.quoteAssetId()), text -> AssetUtil.units(s.baseAssetId(), scale(data, s.baseAssetId())).parsePrice(text, AssetUtil.units(s.quoteAssetId(), scale(data, s.quoteAssetId()))), v -> {
                                                                                                        })
                                                                                                        .thenAccept(price -> {
                                                                                                            if (price.isPresent())
                                                                                                            {
                                                                                                                GuiActions.input(gui, GuiWidgets.text(gui, "ui.input.close_quantity", GameServices.get().markets.quantityUnits(s).format(p.size())), text -> {
                                                                                                                              long raw = GameServices.get().markets.quantityUnits(s).parse(text);
                                                                                                                              if (raw > p.size())
                                                                                                                              {
                                                                                                                                  throw new IllegalArgumentException("ui.trade.quantity_step");
                                                                                                                              }
                                                                                                                              return raw;
                                                                                                                          }, v -> {
                                                                                                                          })
                                                                                                                          .thenAccept(q -> {
                                                                                                                              if (q.isPresent())
                                                                                                                              {
                                                                                                                                  closePosition(p, q.get(), OrderType.Limit, price.get());
                                                                                                                              }
                                                                                                                          }).exceptionally(error -> {
                                                                                                                              GuiActions.error(gui, error);
                                                                                                                              return null;
                                                                                                                          });
                                                                                                            }
                                                                                                        }).exceptionally(error -> {
                                GuiActions.error(gui, error);
                                return null;
                            }));
                    GuiWidgets.button(gui, 2, Items.ANVIL, "ui.position.margin", "", () -> GuiActions.input(gui, GuiWidgets.amountInput(gui, s.quoteAssetId(), 1), text -> AssetUtil.units(s.quoteAssetId(), scale(data, s.quoteAssetId())).parse(text), v -> {
                                                                                                     })
                                                                                                     .thenAccept(amount -> {
                                                                                                         if (amount.isPresent())
                                                                                                         {
                                                                                                             GuiActions.confirm(gui, "ui.confirm.margin", () -> GuiActions.task(gui, GameServices.get().settlements.addMargin(user, p.positionId(), amount.get()), "ui.success.margin", p.positionId()));
                                                                                                         }
                                                                                                     })
                                                                                                     .exceptionally(error -> {
                                                                                                         GuiActions.error(gui, error);
                                                                                                         return null;
                                                                                                     }));
                }
            }
            for (int i = page * 27; i < Math.min(count, page * 27 + 27); i++)
            {
                var position = positions.get(i);
                var s = symbol(data, position.symbolId());
                String lore = GuiWidgets.text(gui, "ui.position.details", position.side(), GameServices.get().markets.quantityUnits(s).format(position.size()), price(data, s, position.averageEntryPrice()), price(data, s, position.predictedLiquidationPrice()), AssetUtil.units(s.quoteAssetId(), scale(data, s.quoteAssetId())).format(position.unrealizedPnL()), position.leverage(), position.marginMode());
                GuiWidgets.button(gui, 18 + i - page * 27, Items.SHIELD, s.name() + " #" + position.positionId(), lore, () -> selected = position);
            }
        }
        if (count == 0)
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PAPER, cache.error() == null ? "ui.empty" : "ui.query_failed", ""));
        }
    }

    @Override
    public void renderOverlay(TradingGui gui)
    {
        GuiWidgets.button(gui, 49, Items.END_CRYSTAL, "gui.capitalmc.refresh", "", this::reset);
        if (!previous.isEmpty())
        {
            GuiWidgets.button(gui, 51, Items.ARROW, "ui.page.previous", "", () -> {
                cursor = previous.pop();
                cache.invalidate();
                selected = null;
            });
        }
        if (queryPage.hasMore())
        {
            var next = queryPage.next();
            GuiWidgets.button(gui, 52, Items.ARROW, "ui.page.next", "", () -> {
                previous.push(cursor);
                cursor = next;
                cache.invalidate();
                selected = null;
            });
        }
    }
}
