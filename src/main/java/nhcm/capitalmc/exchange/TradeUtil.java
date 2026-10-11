package nhcm.capitalmc.exchange;

import nhcm.advmatch.BusinessException;
import nhcm.advmatch.command.read.ReadOrdersNewest;
import nhcm.advmatch.command.read.ReadPositionsNewest;
import nhcm.advmatch.command.read.ReadTradesNewest;
import nhcm.advmatch.command.read.ReadUserOpenOrders;
import nhcm.advmatch.command.read.ReadConditionalOrdersNewest;
import nhcm.advmatch.model.*;
import nhcm.advmatch.object.*;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.service.ApplicationExecutors;
import nhcm.capitalmc.service.GameServices;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class TradeUtil
{
    private TradeUtil()
    {
    }

    public static CompletableFuture<Order> placeSpot(ServerUser user, long symbolId, OrderSide side,
                                                     OrderType type, TimeInForce timeInForce, long price, long quantity, long timestamp)
    {
        var exchange = QueryUtil.exchange();
        return SymbolUtil.getSymbol(symbolId).thenCompose(symbol -> symbol.snapshot().thenCompose(view -> {
            if (view.type() != SymbolType.Spot)
            {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Symbol is not a spot market"));
            }
            return UserUtil.getUser(user).thenCompose(backend -> exchange.matching().place(
                    backend, symbol, side, type, timeInForce, price, quantity, timestamp,
                    null));
        }));
    }

    public static CompletableFuture<List<OrderView>> openOrders(ServerUser user)
    {
        return UserUtil.getUser(user).thenCompose(value -> QueryUtil.all(value::orders));
    }

    public static CompletableFuture<List<PositionView>> positions(ServerUser user)
    {
        return UserUtil.getUser(user).thenCompose(value -> QueryUtil.all(value::positions));
    }

    public static CompletableFuture<ReadResponse<OrderView>> orderPage(ServerUser user, boolean history, long symbol, ReadCursor cursor)
    {
        return UserUtil.getUser(user).thenCompose(backend -> {
            var query = bounded(backend.id(), symbol, cursor).withIncludeTerminal(history);
            if (!history && !cursor.started())
            {
                return QueryUtil.liveRows(query.withKind(ReadKind.OrdersNewest), OrderView.class).thenApply(TradeUtil::checked);
            }
            return QueryUtil.exchange().submit(new ReadOrdersNewest(query)).thenApply(TradeUtil::checked);
        });
    }

    public static CompletableFuture<ReadResponse<PositionView>> positionPage(ServerUser user, boolean history, long symbol, ReadCursor cursor)
    {
        return UserUtil.getUser(user).thenCompose(backend -> {
            var query = bounded(backend.id(), symbol, cursor).withIncludeTerminal(history)
                                                             .withPositionFilter(history ? PositionFilter.ClosedExecuted : PositionFilter.Open);
            return !history && !cursor.started() ? QueryUtil.liveRows(query.withKind(ReadKind.PositionsNewest), PositionView.class).thenApply(TradeUtil::checked)
                                                : QueryUtil.exchange().submit(new ReadPositionsNewest(query)).thenApply(TradeUtil::checked);
        });
    }

    public static CompletableFuture<ReadResponse<TradeView>> tradePage(ServerUser user, long symbol, ReadCursor cursor)
    {
        return UserUtil.getUser(user).thenCompose(backend -> QueryUtil.exchange().submit(new ReadTradesNewest(
                bounded(backend.id(), symbol, cursor))).thenApply(TradeUtil::checked));
    }

    public static CompletableFuture<ReadResponse<ConditionalOrderView>> conditionalPage(ServerUser user, boolean history, ReadCursor cursor)
    {
        return UserUtil.getUser(user).thenCompose(backend -> {
            var query = bounded(backend.id(), 0, cursor).withIncludeTerminal(history).withKind(ReadKind.ConditionalOrdersNewest);
            return !history && !cursor.started() ? QueryUtil.liveRows(query, ConditionalOrderView.class).thenApply(TradeUtil::checked)
                                                : QueryUtil.exchange().submit(new ReadConditionalOrdersNewest(query)).thenApply(TradeUtil::checked);
        });
    }

    private static ObservationQuery bounded(long user, long symbol, ReadCursor cursor)
    {
        return new ObservationQuery().withUserId(user).withSymbolId(symbol).withLimit(27).withScanBudget(512).withCursor(cursor);
    }

    private static <T> ReadResponse<T> checked(ReadResponse<T> response)
    {
        if (response.page().state() != ReadState.Success && response.page().state() != ReadState.NotFound)
        {
            throw new BusinessException(response.page());
        }
        return response;
    }

    /**
     * Drain bounded native open-order pages without materializing an unbounded order list.
     */
    public static CompletableFuture<Integer> cancelPage(ServerUser user)
    {
        return cancelPages(user, 0, 0);
    }

    private static CompletableFuture<Integer> cancelPages(ServerUser user, int total, int pages)
    {
        if (pages >= 1024)
        {
            return CompletableFuture.failedFuture(new IllegalStateException("Cancellation scan limit reached; invoke again"));
        }
        return UserUtil.getUser(user).thenCompose(backend -> QueryUtil.exchange().submit(new ReadUserOpenOrders(
                bounded(backend.id(), 0, new ReadCursor()).withIncludeTerminal(false)))).thenApply(TradeUtil::checked).thenCompose(response -> {
            if (response.values().isEmpty())
            {
                return CompletableFuture.completedFuture(total);
            }
            var jobs = response.values().stream().map(order -> cancel(user, order.orderId(), System.currentTimeMillis())).toList();
            return CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new))
                                    .thenComposeAsync(unused -> cancelPages(user, total + jobs.size(), pages + 1), ApplicationExecutors.CALLBACKS);
        });
    }

    /**
     * Prices and quantities are native fixed-point units; timestamp is passed explicitly.
     */
    public static CompletableFuture<Order> place(ServerUser user, long symbolId, OrderSide side,
                                                 OrderType type, TimeInForce timeInForce, long price, long quantity, long timestamp,
                                                 ContractOrderOptions options)
    {
        var exchange = QueryUtil.exchange();
        return UserUtil.getUser(user).thenCombine(SymbolUtil.getSymbol(symbolId),
                                                  (backend, symbol) -> exchange.matching().place(backend, symbol, side, type,
                                                                                                 timeInForce, price, quantity, timestamp, options)).thenCompose(value -> value);
    }

    public static CompletableFuture<CommandReceipt> cancel(ServerUser user, long orderId, long timestamp)
    {
        QueryUtil.positive(orderId);
        return GameServices.get().settlements.cancel(user, orderId, timestamp);
    }
}
