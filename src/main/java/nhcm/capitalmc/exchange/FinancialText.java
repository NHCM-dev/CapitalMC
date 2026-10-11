package nhcm.capitalmc.exchange;

import net.minecraft.server.level.ServerPlayer;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.gui.TradingGui;
import nhcm.capitalmc.i18n.I18n;
import nhcm.capitalmc.service.*;

import java.util.List;
import java.math.BigInteger;

/**
 * Human decimal formatting shared by CLI and administrator investigations.
 */
public class FinancialText
{
    public static String row(Object row, List<SymbolView> markets)
    {
        return row(row, markets, null);
    }

    public static String row(Object row, List<SymbolView> markets, ServerPlayer player)
    {
        long symbolId = 0;
        long recordId = 0;
        if (row instanceof OrderView order)
        {
            symbolId = order.symbolId();
            recordId = order.orderId();
        }
        else if (row instanceof PositionView position)
        {
            symbolId = position.symbolId();
            recordId = position.positionId();
        }
        else if (row instanceof TradeView trade)
        {
            symbolId = trade.symbolId();
            recordId = trade.recordId();
        }
        for (var symbol : markets)
        {
            if (symbol.symbolId() == symbolId)
            {
                return "&b" + symbol.name() + " &7#" + recordId + "\n" + lore(row, markets, player);
            }
        }
        return lore(row, markets, player);
    }

    public static String lore(Object row, List<SymbolView> markets, ServerPlayer player)
    {
        if (row instanceof BalanceView balance)
        {
            var asset = asset(balance.assetId());
            return text(player, "ui.financial.balance", asset.displayName(), asset.format(balance.active()), asset.format(balance.reserved()));
        }
        long market = switch (row)
        {
            case OrderView order -> order.symbolId();
            case PositionView position -> position.symbolId();
            case TradeView trade -> trade.symbolId();
            case ConditionalOrderView conditional -> conditional.request().symbolId();
            default -> 0;
        };
        var symbol = markets.stream().filter(s -> s.symbolId() == market).findFirst().orElse(null);
        if (symbol == null)
        {
            return row.toString();
        }
        var base = asset(symbol.baseAssetId());
        var quote = asset(symbol.quoteAssetId());
        var quantity = GameServices.get().markets.quantityUnits(symbol);
        String quantityName = GameServices.get().markets.lotSize(symbol.symbolId()) == 0 ? base.displayName()
                : TradingGui.text(player, symbol.type() == SymbolType.Spot ? "ui.lot.spot" : "ui.lot.contract").getString();
        if (row instanceof ConditionalOrderView conditional)
        {
            var request = conditional.request();
            long position = request.contractOptions() == null ? 0 : request.contractOptions().positionId();
            long cap = request.contractOptions() == null ? 0 : request.contractOptions().priceLimit();
            String details = text(player, "ui.financial.conditional", symbol.name(), conditional.conditionalOrderId(), request.kind(), request.source(), request.side(),
                                  base.units().price(request.triggerPrice(), quote.units()), request.type(), request.type() == OrderType.Market ? TradingGui.text(player, "ui.enum.OrderType.Market").getString() : base.units().price(request.price(), quote.units()),
                                  quantity.format(request.quantity()), quantityName, conditional.status(), conditional.orderId(), position, request.ocoOrderId(),
                                  cap == 0 ? "—" : base.units().price(cap, quote.units()), quote.displayName(), base.displayName(),
                                  conditional.rejectReason() == null ? "—" : ExchangeMessages.label(player, conditional.rejectReason()));
            var options = request.contractOptions();
            return details + "\n" + text(player, "ui.conditional.execution_details", request.timeInForce(), options == null ? 1 : options.leverage(),
                                         options == null ? "—" : options.marginMode(), base.units().price(conditional.triggeredPrice(), quote.units()), options != null && options.reduceOnly());
        }
        if (row instanceof OrderView order)
        {
            String result = text(player, "ui.activity.order_details", order.side(), order.type(), order.timeInForce(),
                                 order.type() == OrderType.Market ? text(player, "ui.enum.OrderType.Market") : price(symbol, order.price()),
                                 quantity(symbol, order.quantity(), player), quantity(symbol, order.filling().unfilled(), player), order.filling().status());
            if (symbol.type() != SymbolType.Spot)
            {
                result += "\n" + text(player, "ui.activity.order_contract", order.positionId(), order.leverage(), order.marginMode() == null ? "—" : order.marginMode(), order.reduceOnly());
            }
            return result;
        }
        if (row instanceof PositionView position)
        {
            String liquidation = position.marginMode() == MarginMode.Cross ? text(player, "ui.activity.cross_liquidation")
                    : position.predictedLiquidationPrice() <= 0 ? text(player, "ui.activity.no_liquidation") : price(symbol, position.predictedLiquidationPrice());
            String result = text(player, "ui.activity.position_details", position.side(), quantity(symbol, position.size(), player),
                                 price(symbol, position.averageEntryPrice()), liquidation, pnl(position.unrealizedPnL(), quote), pnl(position.realizedPnL(), quote), position.leverage(), position.marginMode());
            result += "\n" + text(player, "ui.activity.exposure", base.units().format(BigInteger.valueOf(position.size()).multiply(BigInteger.valueOf(position.contractMultiplier()))), base.displayName());
            if (!position.managed() && !position.closed() && position.size() > 0)
            {
                result += "\n" + text(player, "ui.activity.unmanaged");
            }
            return result;
        }
        var trade = (TradeView) row;
        return text(player, "ui.activity.trade_details", price(symbol, trade.trade().price()), quantity(symbol, trade.trade().quantity(), player), trade.timestamp(), trade.trade().makerOrderId(), trade.trade().takerOrderId());
    }

    public static String price(SymbolView symbol, long raw)
    {
        var base = asset(symbol.baseAssetId());
        var quote = asset(symbol.quoteAssetId());
        return base.units().price(raw, quote.units()) + " " + quote.displayName() + " / 1 " + base.displayName();
    }

    public static String quantity(SymbolView symbol, long raw, ServerPlayer player)
    {
        String name = GameServices.get().markets.lotSize(symbol.symbolId()) == 0 ? asset(symbol.baseAssetId()).displayName()
                : text(player, symbol.type() == SymbolType.Spot ? "ui.lot.spot" : "ui.lot.contract");
        return GameServices.get().markets.quantityUnits(symbol).format(raw) + " " + name;
    }

    private static String pnl(long raw, McAssetRegistry.Asset quote)
    {
        return (raw > 0 ? "&a+" : raw < 0 ? "&c" : "&7") + quote.format(raw) + " " + quote.displayName() + "&7";
    }

    public static String direction(Enum<?> value, String label)
    {
        boolean positive = value == PositionSide.Long || value == OrderSide.Bid || value == ConditionalOrderKind.TakeProfit || value == OrderStatus.Filled;
        boolean negative = value == PositionSide.Short || value == OrderSide.Ask || value == ConditionalOrderKind.StopLoss || value == OrderStatus.Rejected;
        return (positive ? "&a" : negative ? "&c" : "&e") + label + "&7";
    }

    private static String text(ServerPlayer player, String key, Object... args)
    {
        for (int i = 0; i < args.length; i++)
        {
            if (args[i] instanceof Enum<?> value)
            {
                args[i] = direction(value, ExchangeMessages.label(player, value));
            }
            else if (args[i] instanceof Boolean value)
            {
                args[i] = TradingGui.text(player, "ui.boolean." + value).getString();
            }
        }
        return I18n.format(player == null ? null : player.clientInformation().language(), key, args);
    }

    private static McAssetRegistry.Asset asset(long id)
    {
        return GameServices.get().assets.find(id).orElseThrow();
    }
}
