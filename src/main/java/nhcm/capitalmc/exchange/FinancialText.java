package nhcm.capitalmc.exchange;

import net.minecraft.server.level.ServerPlayer;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.gui.TradingGui;
import nhcm.capitalmc.service.*;

import java.util.List;

/**
 * Human decimal formatting shared by CLI and administrator investigations.
 */
public final class FinancialText
{
    private FinancialText()
    {
    }

    public static String row(Object row, List<SymbolView> markets)
    {
        return row(row, markets, null);
    }

    public static String row(Object row, List<SymbolView> markets, ServerPlayer player)
    {
        if (row instanceof BalanceView balance)
        {
            var asset = asset(balance.assetId());
            return text(player, "ui.financial.balance", asset.displayName(), asset.format(balance.active()), asset.format(balance.reserved()));
        }
        long market = row instanceof OrderView order ? order.symbolId() : row instanceof PositionView position ? position.symbolId() : row instanceof TradeView trade ? trade.symbolId() : 0;
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
        if (row instanceof OrderView order)
        {
            return text(player, "ui.financial.order", symbol.name(), order.orderId(), order.side(), order.type(), order.timeInForce(),
                        base.units().price(order.price(), quote.units()), quote.displayName(), quantity.format(order.filling().unfilled()), quantityName, order.filling().status());
        }
        if (row instanceof PositionView position)
        {
            return text(player, "ui.financial.position", symbol.name(), position.positionId(), position.side(), quantity.format(position.size()), quantityName,
                        base.units().price(position.averageEntryPrice(), quote.units()), quote.format(position.realizedPnL()), quote.format(position.unrealizedPnL()), quote.displayName(), position.leverage(), position.marginMode());
        }
        var trade = (TradeView) row;
        return text(player, "ui.financial.trade", symbol.name(), trade.recordId(), base.units().price(trade.trade().price(), quote.units()), quote.displayName(), quantity.format(trade.trade().quantity()), quantityName, trade.timestamp());
    }

    private static String text(ServerPlayer player, String key, Object... args)
    {
        for (int i = 0; i < args.length; i++)
            if (args[i] instanceof Enum<?> value)
            {
                args[i] = ExchangeMessages.label(player, value);
            }
        return TradingGui.text(player, key, args).getString();
    }

    private static McAssetRegistry.Asset asset(long id)
    {
        return GameServices.get().assets.find(id).orElseThrow();
    }
}
