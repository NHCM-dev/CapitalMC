package nhcm.capitalmc.gui.subgui.trading;

import net.minecraft.world.item.Items;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.exchange.PositionOrders;
import nhcm.capitalmc.exchange.FinancialText;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.service.GameServices;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongConsumer;
import java.util.function.Function;

/**
 * An editable ticket only; waiting orders and their triggers belong to AdvMatch.
 */
public class ConditionalTicketPage extends BaseSubGui
{
    private OrderSide side = OrderSide.Ask;
    private ConditionalOrderKind kind = ConditionalOrderKind.StopLoss;
    private TriggerPriceSource source = TriggerPriceSource.LastTrade;
    private OrderType type = OrderType.Market;
    private TimeInForce tif = TimeInForce.IOC;
    private MarginMode margin = MarginMode.Isolated;
    private int leverage = 1;
    private long trigger, quantity, price, protection, oco;
    private Object context;

    public ConditionalTicketPage(TradingGui gui)
    {
        super(gui, PageType.CONDITIONAL_TICKET);
    }

    private long price(SymbolView symbol, String text)
    {
        var assets = GameServices.get().assets;
        return assets.find(symbol.baseAssetId()).orElseThrow().units().parsePrice(text, assets.find(symbol.quoteAssetId()).orElseThrow().units());
    }

    private void input(String prompt, Function<String, Long> parse, LongConsumer next)
    {
        GuiActions.input(getGui(), prompt, parse).thenAccept(value -> value.ifPresent(next::accept))
                  .exceptionally(error -> {
                      GuiActions.error(getGui(), error);
                      return null;
                  });
    }

    private void submit(SymbolView symbol)
    {
        var gui = getGui();
        var user = GuiActions.user(gui);
        if (trigger <= 0 || quantity <= 0 || type == OrderType.Limit && price <= 0)
        {
            throw new IllegalArgumentException("ui.conditional.incomplete");
        }
        boolean contract = symbol.type() != SymbolType.Spot;
        var options = contract ? new ContractOrderOptions().withLeverage(leverage).withMarginMode(margin).withPriceLimit(protection) : null;
        var request = new ConditionalOrderRequest().withSymbolId(symbol.symbolId()).withSide(side).withKind(kind).withSource(source)
                                                   .withTriggerPrice(trigger).withType(type).withTimeInForce(tif).withQuantity(quantity).withPrice(type == OrderType.Market ? 0 : price)
                                                   .withContractOptions(options).withOcoOrderId(oco);
        long epoch = CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID());
        CompletableFuture<Long> cap = CompletableFuture.completedFuture(protection);
        if (contract && type == OrderType.Market && protection == 0)
        {
            cap = CapitalMC.exchange.contracts().market(CapitalMC.exchange.symbols().get(symbol.symbolId()))
                                    .thenApply(market -> PositionOrders.triggerProtection(request.triggerPrice(), request.side(), market.spec().priceBandRate(), symbol.tradingRules().priceTick()));
        }
        cap.thenCompose(bound -> GameServices.get().onServer(() -> {
            if (gui.getPlayer().hasDisconnected() || gui.getSelectedSymbol() == null || gui.getSelectedSymbol().symbolId() != symbol.symbolId()
                || gui.getCurrentPage() != PageType.CONDITIONAL_TICKET || epoch != CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID())
                || gui.getUser().filter(value -> value.getUuid().equals(user.getUuid())).isEmpty())
            {
                return null;
            }
            var ticket = request.contractOptions() == null ? request : request.withContractOptions(request.contractOptions().withPriceLimit(bound));
            var quote = GameServices.get().assets.find(symbol.quoteAssetId()).orElseThrow();
            var base = GameServices.get().assets.find(symbol.baseAssetId()).orElseThrow();
            String summary = GuiWidgets.text(gui, "ui.conditional.ticket_confirm", symbol.name(), ticket.kind(), ticket.side(), ticket.source(),
                                             base.units().price(ticket.triggerPrice(), quote.units()), quote.displayName(), base.displayName(), ticket.type(),
                                             ticket.type() == OrderType.Market ? GuiWidgets.text(gui, "ui.enum.OrderType.Market") : base.units().price(ticket.price(), quote.units()),
                                             FinancialText.quantity(symbol, ticket.quantity(), gui.getPlayer()), ticket.timeInForce(), ticket.ocoOrderId(),
                                             bound == 0 ? "—" : base.units().price(bound, quote.units()));
            if (contract)
            {
                summary += "\n" + GuiWidgets.text(gui, "ui.conditional.open_position_hint");
            }
            GuiActions.confirm(gui, summary, () -> GuiActions.task(gui, GameServices.get().settlements.placeConditional(user, ticket)
                                                                                                      .thenCompose(receipt -> GameServices.get().onServer(() -> {
                                                                                                          if (!gui.getPlayer().hasDisconnected() && gui.getCurrentPage() == PageType.CONDITIONAL_TICKET
                                                                                                              && gui.getSelectedSymbol() != null && gui.getSelectedSymbol().symbolId() == symbol.symbolId()
                                                                                                              && epoch == CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID())
                                                                                                              && gui.getUser().filter(value -> value.getUuid().equals(user.getUuid())).isPresent())
                                                                                                          {
                                                                                                              GuiActions.message(gui, GuiWidgets.text(gui, "ui.conditional.created", receipt.result().entityId()));
                                                                                                          }
                                                                                                          return receipt;
                                                                                                      })), "ui.conditional.submitted"));
            return null;
        })).exceptionally(error -> {
            GameServices.get().server.execute(() -> GuiActions.error(gui, error));
            return null;
        });
    }

    @Override
    public void render(TradingGui gui)
    {
        if (!GuiActions.ready(gui) || gui.getUser().isEmpty())
        {
            return;
        }
        var symbol = gui.getSelectedSymbol();
        if (symbol == null)
        {
            GuiWidgets.button(gui, 22, Items.COMPASS, "ui.conditional.choose_symbol", "", () -> gui.showPage(PageType.LIST_SYMBOLS));
            return;
        }
        boolean contract = symbol.type() != SymbolType.Spot;
        var key = List.of(gui.getUser().orElseThrow().getUuid(), CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID()), symbol.symbolId());
        if (!key.equals(context))
        {
            context = key;
            trigger = quantity = price = protection = oco = 0;
            leverage = 1;
            margin = MarginMode.Isolated;
            source = contract ? TriggerPriceSource.Mark : TriggerPriceSource.LastTrade;
        }
        var assets = GameServices.get().assets;
        var base = assets.find(symbol.baseAssetId()).orElseThrow();
        var quote = assets.find(symbol.quoteAssetId()).orElseThrow();
        GuiWidgets.option(gui, 0, Items.WOOL.lime(), contract ? "ui.trade.open_long" : "ui.enum.OrderSide.Bid", "", side == OrderSide.Bid, () -> side = OrderSide.Bid);
        GuiWidgets.option(gui, 1, Items.WOOL.red(), contract ? "ui.trade.open_short" : "ui.enum.OrderSide.Ask", "", side == OrderSide.Ask, () -> side = OrderSide.Ask);
        GuiWidgets.option(gui, 2, Items.EMERALD, "ui.conditional.take_profit", "", kind == ConditionalOrderKind.TakeProfit, () -> kind = ConditionalOrderKind.TakeProfit);
        GuiWidgets.option(gui, 3, Items.REDSTONE, "ui.conditional.stop_loss", "", kind == ConditionalOrderKind.StopLoss, () -> kind = ConditionalOrderKind.StopLoss);
        GuiWidgets.option(gui, 4, Items.PAPER, "ui.enum.OrderType.Market", "", type == OrderType.Market, () -> {
            type = OrderType.Market;
            tif = TimeInForce.IOC;
        });
        GuiWidgets.option(gui, 5, Items.BOW, "ui.enum.OrderType.Limit", "", type == OrderType.Limit, () -> {
            type = OrderType.Limit;
            tif = TimeInForce.GTC;
        });
        GuiWidgets.option(gui, 6, Items.COMPASS, GuiWidgets.text(gui, "ui.conditional.source", source), "ui.conditional.source_hint", true,
                          () -> source = contract ? TriggerPriceSource.values()[(source.ordinal() + 1) % TriggerPriceSource.values().length] : TriggerPriceSource.LastTrade);
        GuiWidgets.option(gui, 7, Items.CLOCK, GuiWidgets.text(gui, "ui.conditional.tif", tif), "", true, () -> {
            var choices = type == OrderType.Market ? List.of(TimeInForce.IOC, TimeInForce.FOK) : List.of(TimeInForce.GTC, TimeInForce.IOC, TimeInForce.FOK, TimeInForce.PostOnly);
            tif = choices.get((choices.indexOf(tif) + 1) % choices.size());
        });
        GuiWidgets.button(gui, 8, Items.WRITABLE_BOOK, "ui.conditional.review", contract ? "ui.conditional.open_position_hint" : "ui.conditional.draft_hint", () -> submit(symbol));
        GuiWidgets.separator(gui);
        String pricePrompt = GuiWidgets.priceInput(gui, symbol.baseAssetId(), symbol.quoteAssetId());
        GuiWidgets.button(gui, 18, Items.COMPARATOR, GuiWidgets.text(gui, "ui.conditional.trigger_value", base.units().price(trigger, quote.units())), "ui.conditional.trigger_hint",
                          () -> input(GuiWidgets.text(gui, "ui.conditional.trigger_input") + "\n" + pricePrompt, text -> price(symbol, text), value -> trigger = value));
        GuiWidgets.button(gui, 19, Items.BOW, GuiWidgets.text(gui, "ui.conditional.price_value", type == OrderType.Market && protection == 0 ? "auto" : base.units().price(type == OrderType.Market ? protection : price, quote.units())),
                          type == OrderType.Market ? "ui.conditional.cap_hint" : "ui.position.limit_hint", () -> {
                    if (type == OrderType.Limit)
                    {
                        input(pricePrompt, text -> price(symbol, text), value -> price = value);
                    }
                    else if (contract)
                    {
                        input(GuiWidgets.text(gui, "ui.conditional.cap_input") + "\n" + pricePrompt, text -> text.equalsIgnoreCase("auto") ? 0L : price(symbol, text), value -> protection = value);
                    }
                });
        GuiWidgets.button(gui, 20, Items.PAPER, GuiWidgets.text(gui, "ui.conditional.size", FinancialText.quantity(symbol, quantity, gui.getPlayer())), "ui.conditional.quantity_input",
                          () -> input(GuiWidgets.text(gui, "ui.conditional.quantity_input"), text -> GameServices.get().markets.quantityUnits(symbol).parse(text), value -> quantity = value));
        if (contract)
        {
            GuiWidgets.button(gui, 21, Items.ANVIL, GuiWidgets.text(gui, "ui.conditional.leverage", leverage), "", () -> input(GuiWidgets.text(gui, "ui.input.leverage"), text -> {
                long value = Long.parseLong(text);
                if (value < 1 || value > 1000)
                {
                    throw new IllegalArgumentException("ui.trade.input_leverage");
                }
                return value;
            }, value -> leverage = (int) value));
            GuiWidgets.option(gui, 22, Items.ENDER_CHEST, GuiWidgets.text(gui, "ui.conditional.margin", margin), "", true, () -> margin = margin == MarginMode.Isolated ? MarginMode.Cross : MarginMode.Isolated);
        }
        GuiWidgets.button(gui, 23, Items.TRIPWIRE_HOOK, GuiWidgets.text(gui, "ui.conditional.oco", oco), "ui.conditional.oco_hint",
                          () -> input(GuiWidgets.text(gui, "ui.conditional.oco_input"), text -> {
                              long value = Long.parseLong(text);
                              if (value < 0)
                              {
                                  throw new IllegalArgumentException("ui.conditional.oco_input");
                              }
                              return value;
                          }, value -> oco = value));
        GuiWidgets.button(gui, 27, Items.REDSTONE_TORCH, "ui.conditional.page", "", () -> gui.showPage(PageType.CONDITIONALS));
    }
}
