package nhcm.capitalmc.gui.subgui.trading;

import net.minecraft.world.item.Items;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.account.ServerUser;


import nhcm.capitalmc.exchange.*;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.service.GameServices;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongConsumer;

public class PositionPage extends BaseSubGui
{
    private record Data(PositionView position, SymbolView symbol, MarketView market)
    {
    }

    private final PollingValue<Data> cache = new PollingValue<>(new Data(null, null, null));
    private TriggerPriceSource source = TriggerPriceSource.Mark;
    private OrderType execution = OrderType.Market;
    private long protectedQuantity, oco;
    private Object selection;

    public PositionPage(TradingGui gui)
    {
        super(gui, PageType.POSITION);
    }

    private CompletableFuture<Data> load(ServerUser user, long id)
    {
        return PositionOrders.owned(user, id).thenCompose(position -> {
            var symbol = CapitalMC.exchange.symbols().get(position.symbolId());
            return symbol.snapshot().thenCombine(CapitalMC.exchange.contracts().market(symbol),
                                                 (snapshot, market) -> new Data(position, snapshot, market));
        });
    }

    private String priceSummary(Data data, long trigger, long price, long quantity, OrderType type)
    {
        var gui = getGui();
        var assets = GameServices.get().assets;
        var base = assets.find(data.symbol.baseAssetId()).orElseThrow();
        var quote = assets.find(data.symbol.quoteAssetId()).orElseThrow();
        long cap = type == OrderType.Market ? PositionOrders.triggerProtection(trigger, PositionOrders.side(data.position), data.market.spec().priceBandRate(), data.symbol.tradingRules().priceTick()) : 0;
        long size = quantity == 0 ? data.position.size() : quantity;
        var baseAmount = BigInteger.valueOf(size).multiply(BigInteger.valueOf(data.market.spec().contractMultiplier()));
        return GuiWidgets.text(gui, "ui.conditional.price_summary", base.units().price(trigger, quote.units()),
                               type == OrderType.Market ? GuiWidgets.text(gui, "ui.enum.OrderType.Market") : base.units().price(price, quote.units()),
                               cap == 0 ? "—" : base.units().price(cap, quote.units()), quote.displayName(), base.displayName())
               + "\n" + GuiWidgets.text(gui, "ui.trade.amounts", base.displayName(), base.units().format(baseAmount), quote.displayName(),
                                        quote.units().format(baseAmount.multiply(BigInteger.valueOf(type == OrderType.Market ? trigger : price))));
    }

    private long price(SymbolView symbol, String text)
    {
        var assets = GameServices.get().assets;
        return assets.find(symbol.baseAssetId()).orElseThrow().units().parsePrice(text, assets.find(symbol.quoteAssetId()).orElseThrow().units());
    }

    private void priceInput(SymbolView symbol, String title, LongConsumer next)
    {
        GuiActions.input(getGui(), GuiWidgets.text(getGui(), title) + "\n" + GuiWidgets.priceInput(getGui(), symbol.baseAssetId(), symbol.quoteAssetId()),
                         text -> price(symbol, text)).thenAccept(value -> value.ifPresent(next::accept)).exceptionally(error -> {
            GuiActions.error(getGui(), error);
            return null;
        });
    }

    private void quantityInput(Data data, LongConsumer next)
    {
        GuiActions.input(getGui(), GuiWidgets.text(getGui(), "ui.input.close_quantity", GameServices.get().markets.quantityUnits(data.symbol).format(data.position.size())),
                         text -> PositionOrders.quantity(data.position, GameServices.get().markets.quantityUnits(data.symbol).parse(text))).thenAccept(value -> value.ifPresent(next::accept))
                  .exceptionally(error -> {
                      GuiActions.error(getGui(), error);
                      return null;
                  });
    }

    private void close(Data data, OrderType type, boolean all)
    {
        if (type == OrderType.Market)
        {
            if (all)
            {
                confirmClose(data, type, 0, 0);
            }
            else
            {
                quantityInput(data, quantity -> confirmClose(data, type, quantity, 0));
            }
        }
        else
        {
            priceInput(data.symbol, "ui.position.limit_input", price -> {
                if (all)
                {
                    confirmClose(data, type, 0, price);
                }
                else
                {
                    quantityInput(data, quantity -> confirmClose(data, type, quantity, price));
                }
            });
        }
    }

    private void confirmClose(Data data, OrderType type, long quantity, long price)
    {
        var gui = getGui();
        var user = GuiActions.user(gui);
        String summary = GuiWidgets.text(gui, "ui.position.close_summary", data.position.positionId(), type,
                                         GameServices.get().markets.quantityUnits(data.symbol).format(quantity == 0 ? data.position.size() : quantity));
        if (type == OrderType.Limit)
        {
            var base = GameServices.get().assets.find(data.symbol.baseAssetId()).orElseThrow();
            var quote = GameServices.get().assets.find(data.symbol.quoteAssetId()).orElseThrow();
            summary += "\n" + GuiWidgets.text(gui, "ui.conditional.limit_review", base.units().price(price, quote.units()), quote.displayName(), base.displayName());
        }
        GuiActions.confirm(gui, summary, () -> {
            long epoch = CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID());
            var work = PositionOrders.close(user, data.position.positionId(), type, quantity, price,
                                            () -> requireActive(user, epoch, data.position.positionId()));
            GuiActions.task(gui, work, "ui.success.close_order", data.position.positionId());
        });
    }

    private void margin(Data data, boolean remove)
    {
        var gui = getGui();
        var user = GuiActions.user(gui);
        var asset = GameServices.get().assets.find(data.symbol.quoteAssetId()).orElseThrow();
        GuiActions.input(gui, GuiWidgets.text(gui, remove ? "ui.position.remove_margin_input" : "ui.position.add_margin_input", asset.displayName()),
                         asset::parse).thenAccept(result -> result.ifPresent(amount -> {
            String summary = GuiWidgets.text(gui, "ui.position.margin_summary", data.position.positionId(), asset.format(amount), asset.displayName(),
                                             GuiWidgets.text(gui, remove ? "ui.position.remove_margin" : "ui.position.margin"));
            GuiActions.confirm(gui, summary, () -> GuiActions.task(gui,
                                                                   remove ? GameServices.get().settlements.removeMargin(user, data.position.positionId(), amount)
                                                                           : GameServices.get().settlements.addMargin(user, data.position.positionId(), amount),
                                                                   remove ? "ui.success.margin_removed" : "ui.success.margin", data.position.positionId()));
        })).exceptionally(error -> {
            GuiActions.error(gui, error);
            return null;
        });
    }

    private boolean active(ServerUser user, long epoch, long positionId)
    {
        var gui = getGui();
        return !gui.getPlayer().hasDisconnected() && gui.getCurrentPage() == PageType.POSITION
               && gui.getSelectedPosition() == positionId
               && epoch == CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID())
               && gui.getUser().filter(value -> value.getUuid().equals(user.getUuid())).isPresent();
    }

    private void requireActive(ServerUser user, long epoch, long positionId)
    {
        if (!active(user, epoch, positionId))
        {
            throw new IllegalStateException("ui.session.changed");
        }
    }

    private void protect(Data data, ConditionalOrderKind kind)
    {
        var selectedSource = source;
        var type = execution;
        long quantity = protectedQuantity;
        long sibling = oco;
        String prompt = kind == ConditionalOrderKind.TakeProfit ? "ui.conditional.tp_input" : "ui.conditional.sl_input";
        priceInput(data.symbol, prompt, trigger -> {
            LongConsumer next = price -> confirmProtection(data, kind, selectedSource, type, trigger, price, quantity, sibling);
            if (type == OrderType.Limit)
            {
                priceInput(data.symbol, "ui.position.limit_input", next);
            }
            else
            {
                next.accept(0);
            }
        });
    }

    private void confirmProtection(Data data, ConditionalOrderKind kind, TriggerPriceSource selectedSource,
                                   OrderType type, long trigger, long price, long quantity, long sibling)
    {
        var gui = getGui();
        var user = GuiActions.user(gui);
        long positionId = data.position.positionId();
        String size = quantity == 0 ? GuiWidgets.text(gui, "ui.position.all") : GameServices.get().markets.quantityUnits(data.symbol).format(quantity);
        String summary = GuiWidgets.text(gui, "ui.conditional.confirm", kind, selectedSource, type, positionId, size, sibling)
                         + "\n" + priceSummary(data, trigger, price, quantity, type);
        GuiActions.confirm(gui, summary, () -> {
            long epoch = CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID());
            var work = PositionOrders.protect(user, positionId, kind, selectedSource, type, trigger, price, quantity, sibling, () -> requireActive(user, epoch, positionId))
                                     .thenCompose(receipt -> GameServices.get().onServer(() -> {
                                         if (active(user, epoch, positionId))
                                         {
                                             GuiActions.message(gui, GuiWidgets.text(gui, "ui.conditional.created", receipt.result().entityId()));
                                         }
                                         return receipt;
                                     }));
            GuiActions.task(gui, work, "ui.conditional.submitted");
        });
    }

    private void pair(Data data)
    {
        var selectedSource = source;
        var type = execution;
        long quantity = protectedQuantity;
        priceInput(data.symbol, "ui.conditional.tp_input", tp -> priceInput(data.symbol, "ui.conditional.sl_input", sl -> {
            if ((data.position.side() == PositionSide.Long && tp <= sl) || (data.position.side() == PositionSide.Short && tp >= sl))
            {
                throw new IllegalArgumentException("ui.conditional.pair_order");
            }
            pairPrices(data, selectedSource, type, quantity, tp, sl);
        }));
    }

    private void pairPrices(Data data, TriggerPriceSource selectedSource, OrderType type, long quantity, long tp, long sl)
    {
        if (type == OrderType.Market)
        {
            confirmPair(data, selectedSource, type, quantity, tp, sl, 0, 0);
            return;
        }
        priceInput(data.symbol, "ui.conditional.tp_limit_input", tpPrice ->
                priceInput(data.symbol, "ui.conditional.sl_limit_input", slPrice ->
                        confirmPair(data, selectedSource, type, quantity, tp, sl, tpPrice, slPrice)));
    }

    private void confirmPair(Data data, TriggerPriceSource selectedSource, OrderType type, long quantity,
                             long tp, long sl, long tpPrice, long slPrice)
    {
        var gui = getGui();
        String summary = GuiWidgets.text(gui, "ui.conditional.pair_confirm")
                         + "\n" + GuiWidgets.text(gui, "ui.conditional.take_profit") + "\n" + priceSummary(data, tp, tpPrice, quantity, type)
                         + "\n" + GuiWidgets.text(gui, "ui.conditional.stop_loss") + "\n" + priceSummary(data, sl, slPrice, quantity, type);
        GuiActions.confirm(gui, summary, () -> submitPair(data, selectedSource, type, quantity, tp, sl, tpPrice, slPrice));
    }

    private void submitPair(Data data, TriggerPriceSource selectedSource, OrderType type, long quantity,
                            long tp, long sl, long tpPrice, long slPrice)
    {
        var gui = getGui();
        var user = GuiActions.user(gui);
        long positionId = data.position.positionId();
        long epoch = CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID());
        var work = PositionOrders.protect(user, positionId, ConditionalOrderKind.TakeProfit, selectedSource, type, tp, tpPrice, quantity, 0, () -> requireActive(user, epoch, positionId))
                                 .thenCompose(first -> GameServices.get().onServer(() -> {
                                     if (!active(user, epoch, positionId))
                                     {
                                         throw new IllegalStateException("ui.session.changed");
                                     }
                                     // Each submission has its own durability decision. Retain the first ID if the second fails.
                                     GuiActions.message(gui, GuiWidgets.text(gui, "ui.conditional.pair_first", first.result().entityId()));
                                     return PositionOrders.protect(user, positionId, ConditionalOrderKind.StopLoss, selectedSource, type, sl, slPrice, quantity, first.result().entityId(), () -> requireActive(user, epoch, positionId));
                                 })).thenCompose(result -> result);
        GuiActions.task(gui, work, "ui.conditional.pair_done");
    }

    @Override
    public void render(TradingGui gui)
    {
        if (!GuiActions.ready(gui) || gui.getUser().isEmpty())
        {
            return;
        }
        var user = GuiActions.user(gui);
        long id = gui.getSelectedPosition();
        var key = List.of(user.getUuid(), CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID()), id);
        if (!key.equals(selection))
        {
            selection = key;
            protectedQuantity = 0;
            oco = 0;
        }
        if (id == 0)
        {
            GuiWidgets.button(gui, 22, Items.SHIELD, "ui.positions", "", () -> gui.showPage(PageType.POSITIONS));
            return;
        }
        var data = cache.get(key, () -> load(user, id));
        if (data.position == null || cache.error() != null)
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PAPER, cache.error() == null ? "ui.loading" : "ui.position.unavailable", ""));
            return;
        }
        var p = data.position;
        var s = data.symbol;
        String detail = FinancialText.row(p, List.of(s), gui.getPlayer()) + "\n" + GuiWidgets.text(gui, "ui.position.margin_details",
                                                                                                   GuiWidgets.amount(s.quoteAssetId(), p.initialMargin()), GuiWidgets.amount(s.quoteAssetId(), p.maintenanceMargin()),
                                                                                                   GameServices.get().assets.find(s.quoteAssetId()).orElseThrow().displayName());
        GuiWidgets.button(gui, 22, Items.SHIELD, s.name() + " #" + id, detail, null);
        GuiWidgets.button(gui, 0, Items.NETHER_STAR, "ui.position.close_all", "ui.position.market_hint", () -> close(data, OrderType.Market, true));
        GuiWidgets.button(gui, 1, Items.SHEARS, "ui.position.close_market_partial", "ui.position.market_hint", () -> close(data, OrderType.Market, false));
        GuiWidgets.button(gui, 2, Items.BOW, "ui.position.close_limit_all", "ui.position.limit_hint", () -> close(data, OrderType.Limit, true));
        GuiWidgets.button(gui, 3, Items.CROSSBOW, "ui.position.close_limit", "ui.position.limit_hint", () -> close(data, OrderType.Limit, false));
        GuiWidgets.button(gui, 4, Items.ANVIL, "ui.position.margin", "ui.position.margin_hint", () -> margin(data, false));
        GuiWidgets.button(gui, 5, Items.IRON_INGOT, "ui.position.remove_margin", "ui.position.margin_hint", () -> margin(data, true));
        GuiWidgets.option(gui, 6, Items.COMPASS, GuiWidgets.text(gui, "ui.conditional.source", source), "ui.conditional.source_hint", true,
                          () -> source = TriggerPriceSource.values()[(source.ordinal() + 1) % TriggerPriceSource.values().length]);
        GuiWidgets.option(gui, 7, Items.PAPER, GuiWidgets.text(gui, "ui.conditional.execution", execution), "ui.conditional.execution_hint", true,
                          () -> execution = execution == OrderType.Market ? OrderType.Limit : OrderType.Market);
        GuiWidgets.button(gui, 8, Items.REDSTONE_TORCH, "ui.conditional.page", "", () -> gui.showPage(PageType.CONDITIONALS));
        GuiWidgets.separator(gui);
        GuiWidgets.button(gui, 18, Items.EMERALD, "ui.conditional.take_profit", "ui.conditional.position_hint", () -> protect(data, ConditionalOrderKind.TakeProfit));
        GuiWidgets.button(gui, 19, Items.REDSTONE, "ui.conditional.stop_loss", "ui.conditional.position_hint", () -> protect(data, ConditionalOrderKind.StopLoss));
        GuiWidgets.button(gui, 20, Items.TRIPWIRE_HOOK, "ui.conditional.pair", "ui.conditional.pair_hint", () -> pair(data));
        GuiWidgets.button(gui, 27, Items.PAPER, GuiWidgets.text(gui, "ui.conditional.size", protectedQuantity == 0 ? GuiWidgets.text(gui, "ui.position.all") : GameServices.get().markets.quantityUnits(s).format(protectedQuantity)),
                          "ui.conditional.size_hint", () -> quantityInput(data, amount -> protectedQuantity = amount));
        GuiWidgets.button(gui, 28, Items.NETHER_STAR, "ui.conditional.size_all", "", () -> protectedQuantity = 0);
        GuiWidgets.button(gui, 29, Items.TRIPWIRE_HOOK, GuiWidgets.text(gui, "ui.conditional.oco", oco), "ui.conditional.oco_hint", () ->
                GuiActions.input(gui, GuiWidgets.text(gui, "ui.conditional.oco_input"), text -> {
                    long value = Long.parseLong(text);
                    if (value < 0)
                    {
                        throw new IllegalArgumentException("ui.conditional.oco_input");
                    }
                    return value;
                }, value -> oco = value).exceptionally(error -> {
                    GuiActions.error(gui, error);
                    return null;
                }));
    }
}
