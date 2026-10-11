package nhcm.capitalmc.exchange;

import nhcm.advmatch.model.*;
import nhcm.advmatch.BusinessException;

import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

/** Filters bounded native pages without merging revisions or losing the continuation cursor. */
public class ActivityScope
{
    public static boolean matches(Object row, List<SymbolView> symbols, SymbolType type)
    {
        long id = symbolId(row);
        return symbols.stream().anyMatch(symbol -> symbol.symbolId() == id && symbol.type() == type);
    }

    public static Predicate<Object> matching(List<SymbolView> symbols, SymbolType type)
    {
        var ids = symbols.stream().filter(symbol -> symbol.type() == type).map(SymbolView::symbolId).collect(Collectors.toUnmodifiableSet());
        return row -> ids.contains(symbolId(row));
    }

    private static long symbolId(Object row)
    {
        return switch (row)
        {
            case OrderView order -> order.symbolId();
            case PositionView position -> position.symbolId();
            case ConditionalOrderView conditional -> conditional.request().symbolId();
            case TradeView trade -> trade.symbolId();
            default -> 0;
        };
    }

    public static boolean terminal(OrderView order)
    {
        return order.filling().status() == OrderStatus.Filled || order.filling().status() == OrderStatus.Cancelled
               || order.filling().status() == OrderStatus.Rejected;
    }

    public static <T> CompletableFuture<ReadResponse<T>> page(Function<ReadCursor, CompletableFuture<ReadResponse<T>>> read,
                                                            ReadCursor cursor, Predicate<? super T> filter)
    {
        return page(read, cursor, filter, 16);
    }

    private static <T> CompletableFuture<ReadResponse<T>> page(Function<ReadCursor, CompletableFuture<ReadResponse<T>>> read,
                                                             ReadCursor cursor, Predicate<? super T> filter, int budget)
    {
        return read.apply(cursor).thenCompose(result -> {
            if (result.page().state() != ReadState.Success && result.page().state() != ReadState.NotFound)
            {
                throw new BusinessException(result.page());
            }
            var rows = result.values().stream().filter(filter).toList();
            if (rows.isEmpty() && result.page().hasMore() && budget > 0)
            {
                if (result.page().next().equals(cursor) || !result.page().next().started())
                {
                    return CompletableFuture.failedFuture(new IllegalStateException("Pagination cursor did not advance"));
                }
                return page(read, result.page().next(), filter, budget - 1);
            }
            return CompletableFuture.completedFuture(new ReadResponse<>(result.query(), result.page(), rows));
        });
    }
}
