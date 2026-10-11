package nhcm.capitalmc.exchange;

import nhcm.advmatch.BusinessException;
import nhcm.advmatch.command.read.ReadDepth;
import nhcm.advmatch.command.read.ReadSymbol;
import nhcm.advmatch.command.read.ReadTrades;
import nhcm.advmatch.model.*;
import nhcm.advmatch.object.Symbol;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class SymbolUtil
{
    private SymbolUtil()
    {
    }

    public static CompletableFuture<Optional<SymbolView>> find(long id)
    {
        QueryUtil.positive(id);
        return QueryUtil.exchange().submit(new ReadSymbol(new ObservationQuery().withSymbolId(id))).thenApply(QueryUtil::one);
    }

    public static CompletableFuture<Boolean> exists(long id)
    {
        return find(id).thenApply(Optional::isPresent);
    }

    public static CompletableFuture<Symbol> getSymbol(long id)
    {
        QueryUtil.positive(id);
        return QueryUtil.exchange().symbols().find(id);
    }

    public static CompletableFuture<List<SymbolView>> allSymbols()
    {
        var query = new ObservationQuery().withKind(ReadKind.Symbols).withIncludeInactive(true);
        return QueryUtil.liveList(query, SymbolView.class, q -> QueryUtil.exchange().symbols().list(q.withIncludeInactive(true)));
    }

    public static CompletableFuture<List<SymbolView>> allSymbolsFresh()
    {
        return QueryUtil.all(query -> QueryUtil.exchange().symbols().list(query.withIncludeInactive(true)));
    }

    public static CompletableFuture<List<SymbolView>> findByNameFresh(String name)
    {
        return allSymbolsFresh().thenApply(values -> values.stream().filter(value -> name.equals(value.name())).toList());
    }

    public static CompletableFuture<List<SymbolView>> findByName(String name)
    {
        Objects.requireNonNull(name, "name");
        return allSymbols().thenApply(values -> values.stream().filter(value -> name.equals(value.name())).toList());
    }

    public record Quote(long latest, long previousClose)
    {
        public String change()
        {
            if (latest <= 0 || previousClose <= 0)
            {
                return "";
            }
            BigDecimal percentage = BigDecimal.valueOf(latest).subtract(BigDecimal.valueOf(previousClose))
                                              .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(previousClose), 2, RoundingMode.HALF_UP);
            return (latest > previousClose ? "+" : "") + percentage.toPlainString() + "%";
        }
    }

    /**
     * Yesterday's last execution in UTC; missing retained history is never presented as a zero change.
     */
    public static Quote quote(List<TradeView> trades, long now)
    {
        long today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        var ordering = Comparator.comparingLong(TradeView::timestamp).thenComparingLong(TradeView::recordId);
        long latest = trades.stream().filter(row -> row.timestamp() <= now).max(Comparator.comparingLong(TradeView::recordId)).map(row -> row.trade().price()).orElse(0L);
        long close = trades.stream().filter(row -> row.timestamp() >= today - 86_400_000L && row.timestamp() < today)
                           .max(ordering).map(row -> row.trade().price()).orElse(0L);
        return new Quote(latest, close);
    }

    public static CompletableFuture<Quote> quote(long symbolId)
    {
        QueryUtil.positive(symbolId);
        return QueryUtil.shared("quote:" + symbolId, 500_000_000L,
                                () -> QueryUtil.all(query -> QueryUtil.exchange().submit(new ReadTrades(query.withSymbolId(symbolId))))
                                               .thenApply(trades -> quote(trades, System.currentTimeMillis())));
    }

    public record Depth(List<DepthView> bids, List<DepthView> asks)
    {
        public Depth
        {
            bids = List.copyOf(bids);
            asks = List.copyOf(asks);
        }
    }

    public record Snapshot(List<TradeView> trades, MarketView contract, Depth depth)
    {
    }

    /**
     * Shared retained tape and full depth; volume is explicitly labelled as retained-history volume.
     */
    public static CompletableFuture<Snapshot> statistics(long symbolId, boolean contract)
    {
        return QueryUtil.shared("stats:" + symbolId, 500_000_000L, () -> {
            var exchange = QueryUtil.exchange();
            var query = new ObservationQuery().withSymbolId(symbolId);
            var trades = QueryUtil.liveList(query.withKind(ReadKind.Trades), TradeView.class, q -> exchange.submit(new ReadTrades(q.withSymbolId(symbolId))));
            var bids = QueryUtil.liveList(query.withKind(ReadKind.Depth).withSide(OrderSide.Bid), DepthView.class,
                                         q -> exchange.submit(new ReadDepth(q.withSymbolId(symbolId).withSide(OrderSide.Bid))));
            var asks = QueryUtil.liveList(query.withKind(ReadKind.Depth).withSide(OrderSide.Ask), DepthView.class,
                                         q -> exchange.submit(new ReadDepth(q.withSymbolId(symbolId).withSide(OrderSide.Ask))));
            CompletableFuture<MarketView> market = contract ? QueryUtil.liveRows(query.withKind(ReadKind.Market).withLimit(1), MarketView.class)
                                                                     .thenApply(response -> QueryUtil.one(response).orElseThrow()) : CompletableFuture.completedFuture(null);
            return trades.thenCombine(market, (t, m) -> new Snapshot(t, m, null))
                         .thenCombine(bids.thenCombine(asks, Depth::new), (s, d) -> new Snapshot(s.trades, s.contract, d));
        });
    }

    public static CompletableFuture<Depth> depth(long symbolId)
    {
        QueryUtil.positive(symbolId);
        return QueryUtil.shared("depth:" + symbolId, 50_000_000L, () -> {
            var query = new ObservationQuery().withKind(ReadKind.Depth).withSymbolId(symbolId).withLimit(5);
            return QueryUtil.liveRows(query.withSide(OrderSide.Bid), DepthView.class)
                            .thenCombine(QueryUtil.liveRows(query.withSide(OrderSide.Ask), DepthView.class), (b, a) -> {
                if (b.page().state() != ReadState.Success)
                {
                    throw new BusinessException(b.page());
                }
                if (a.page().state() != ReadState.Success)
                {
                    throw new BusinessException(a.page());
                }
                return new Depth(b.values(), a.values());
            });
        });
    }
}
