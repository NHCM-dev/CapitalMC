package nhcm.capitalmc.exchange;

import nhcm.advmatch.model.*;
import nhcm.advmatch.command.read.ReadPosition;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.service.GameServices;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;

/**
 * Creates native tickets from immutable position views; stores no financial state.
 */
public class PositionOrders
{
    public static CompletableFuture<PositionView> owned(ServerUser user, long id)
    {
        return UserUtil.contractAccount(user).thenCompose(account -> CapitalMC.exchange.submit(new ReadPosition(
                new ObservationQuery().withPositionId(id).withAccountId(account.id()).withUserId(user.getExchangeUserId())))
                .thenApply(response -> {
            var position = QueryUtil.one(response).orElseThrow(() -> new IllegalArgumentException("ui.position.unavailable"));
            if (position.userId() != user.getExchangeUserId() || position.closed() || position.size() <= 0)
            {
                throw new IllegalArgumentException("cli.position.owner");
            }
            return position;
        }));
    }

    public static long quantity(PositionView position, long requested)
    {
        long value = requested == 0 ? position.size() : requested;
        if (value <= 0 || value > position.size())
        {
            throw new IllegalArgumentException("ui.trade.close_quantity");
        }
        return value;
    }

    public static ContractOrderOptions options(PositionView position)
    {
        return new ContractOrderOptions().withPositionId(position.positionId()).withReduceOnly(true)
                                         .withLeverage(position.leverage()).withMarginMode(position.marginMode());
    }

    public static OrderSide side(PositionView position)
    {
        return position.side() == PositionSide.Long ? OrderSide.Ask : OrderSide.Bid;
    }

    private record Data(PositionView position, SymbolView symbol, MarketView market)
    {
    }

    private static CompletableFuture<Data> load(ServerUser user, long id)
    {
        return owned(user, id).thenCompose(position -> {
            var symbol = CapitalMC.exchange.symbols().get(position.symbolId());
            return symbol.snapshot().thenCombine(CapitalMC.exchange.contracts().market(symbol),
                                                 (snapshot, market) -> new Data(position, snapshot, market));
        });
    }

    public static CompletableFuture<CommandReceipt> close(ServerUser user, long id, OrderType type, long requested, long price, Runnable validate)
    {
        return load(user, id).thenCompose(data -> {
            var position = data.position;
            var symbol = data.symbol;
            long cap = type == OrderType.Market
                    ? TradeCapacity.marketPriceLimit(data.market, side(position), symbol.tradingRules().priceTick(), symbol.tradingRules().tickAwarePriceBand()) : 0;
            return GameServices.get().onServer(() -> {
                validate.run();
                return GameServices.get().settlements.place(user, symbol.symbolId(), side(position), type,
                                                            type == OrderType.Market ? TimeInForce.IOC : TimeInForce.GTC,
                                                            type == OrderType.Market ? 0 : price, quantity(position, requested), options(position).withPriceLimit(cap));
            }).thenCompose(result -> result);
        });
    }

    /**
     * Guard at the trigger level, rather than today's price, for a future market execution.
     */
    public static long triggerProtection(long trigger, OrderSide side, long bandRate, long tick)
    {
        var value = BigInteger.valueOf(trigger);
        var distance = value.multiply(BigInteger.valueOf(bandRate)).add(BigInteger.valueOf(999_999)).divide(BigInteger.valueOf(1_000_000)).max(BigInteger.valueOf(tick));
        var bound = side == OrderSide.Bid ? value.add(distance).min(BigInteger.valueOf(Long.MAX_VALUE)) : value.subtract(distance).max(BigInteger.ONE);
        var step = BigInteger.valueOf(tick);
        bound = side == OrderSide.Bid ? bound.divide(step).multiply(step) : bound.add(step.subtract(BigInteger.ONE)).divide(step).multiply(step);
        return bound.max(BigInteger.ONE).longValueExact();
    }

    public static CompletableFuture<CommandReceipt> protect(ServerUser user, long id, ConditionalOrderKind kind, TriggerPriceSource source,
                                                            OrderType type, long trigger, long price, long requested, long oco, Runnable validate)
    {
        return load(user, id).thenCompose(data -> {
            var position = data.position;
            var symbol = data.symbol;
            long cap = type == OrderType.Market ? triggerProtection(trigger, side(position), data.market.spec().priceBandRate(), symbol.tradingRules().priceTick()) : 0;
            var request = new ConditionalOrderRequest().withSymbolId(symbol.symbolId()).withSide(side(position)).withType(type)
                                                       .withTimeInForce(type == OrderType.Market ? TimeInForce.IOC : TimeInForce.GTC).withPrice(type == OrderType.Market ? 0 : price)
                                                       .withQuantity(quantity(position, requested)).withKind(kind).withSource(source).withTriggerPrice(trigger)
                                                       .withContractOptions(options(position).withPriceLimit(cap)).withOcoOrderId(oco);
            return GameServices.get().onServer(() -> {
                validate.run();
                return GameServices.get().settlements.placeConditional(user, request);
            }).thenCompose(result -> result);
        });
    }
}
