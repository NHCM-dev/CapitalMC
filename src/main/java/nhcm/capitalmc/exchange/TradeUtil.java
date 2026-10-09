package nhcm.capitalmc.exchange;

import nhcm.advmatch.model.*;
import nhcm.advmatch.object.*;
import nhcm.capitalmc.account.ServerUser;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class TradeUtil {
    private TradeUtil() {}
    public static CompletableFuture<Order> placeSpot(ServerUser user, long symbolId, OrderSide side,
            OrderType type, TimeInForce timeInForce, long price, long quantity, long timestamp) {
        var exchange = QueryUtil.exchange();
        return SymbolUtil.getSymbol(symbolId).thenCompose(symbol -> symbol.snapshot().thenCompose(view -> {
            if (view.type() != SymbolType.Spot) return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Symbol is not a spot market"));
            return UserUtil.getUser(user).thenCompose(backend -> exchange.matching().place(
                    backend, symbol, side, type, timeInForce, price, quantity, timestamp,
                    new ContractOrderOptions()));
        }));
    }
    public static CompletableFuture<List<OrderView>> openOrders(ServerUser user) {
        return UserUtil.getUser(user).thenCompose(value -> QueryUtil.all(value::orders));
    }
    public static CompletableFuture<List<PositionView>> positions(ServerUser user) {
        return UserUtil.getUser(user).thenCompose(value -> QueryUtil.all(value::positions));
    }
    /** Prices and quantities are native fixed-point units; timestamp is passed explicitly. */
    public static CompletableFuture<Order> place(ServerUser user, long symbolId, OrderSide side,
            OrderType type, TimeInForce timeInForce, long price, long quantity, long timestamp,
            ContractOrderOptions options) {
        var exchange = QueryUtil.exchange();
        return UserUtil.getUser(user).thenCombine(SymbolUtil.getSymbol(symbolId),
                (backend, symbol) -> exchange.matching().place(backend, symbol, side, type,
                        timeInForce, price, quantity, timestamp, options)).thenCompose(value -> value);
    }
    public static CompletableFuture<CommandReceipt> cancel(ServerUser user, long orderId, long timestamp) {
        QueryUtil.positive(orderId);
        return UserUtil.getUser(user).thenCompose(backend -> QueryUtil.exchange().orders().find(orderId)
                .thenCompose(order -> order.snapshot().thenCompose(view -> {
                    if (view.userId() != backend.id()) return CompletableFuture.failedFuture(
                            new SecurityException("Order belongs to another user"));
                    return order.cancel(timestamp);
                })));
    }
}
