package nhcm.capitalmc.exchange;

import nhcm.advmatch.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ActivityScopeTest
{
    @Test
    void recordsSortByCreationTimeWithStableIdTies()
    {
        var first = new ConditionalOrderView(1, new ConditionalOrderRequest().withTimestamp(30), ConditionalOrderStatus.Waiting, 0, 0, 0, 0, null);
        var second = new ConditionalOrderView(2, new ConditionalOrderRequest().withTimestamp(10), ConditionalOrderStatus.Waiting, 0, 0, 0, 0, null);
        var third = new ConditionalOrderView(3, new ConditionalOrderRequest().withTimestamp(30), ConditionalOrderStatus.Waiting, 0, 0, 0, 0, null);
        assertEquals(List.of(third, first, second), ActivityScope.newest(List.of(second, first, third)));
    }

    @Test
    void allSymbolsOfOneTypeMatchAndOtherTypesDoNot()
    {
        var symbols = List.of(new SymbolView(1, SymbolType.Perpetual, 1, 2, true, "A", new TradingRules()),
                              new SymbolView(2, SymbolType.Perpetual, 3, 2, true, "B", new TradingRules()),
                              new SymbolView(3, SymbolType.Future, 1, 2, true, "C", new TradingRules()),
                              new SymbolView(4, SymbolType.Spot, 1, 2, true, "D", new TradingRules()));
        for (long id = 1; id <= 4; id++)
        {
            var row = new ConditionalOrderView(id, new ConditionalOrderRequest().withSymbolId(id), ConditionalOrderStatus.Waiting, 0, 0, 0, 0, null);
            assertEquals(id <= 2, ActivityScope.matches(row, symbols, SymbolType.Perpetual));
            assertEquals(id == 3, ActivityScope.matches(row, symbols, SymbolType.Future));
            assertEquals(id == 4, ActivityScope.matches(row, symbols, SymbolType.Spot));
        }
    }

    @Test
    void filteredPagesAdvancePastOtherTypesAndRetainTheNextCursor()
    {
        var calls = new AtomicInteger();
        var response = ActivityScope.page(cursor -> {
            calls.incrementAndGet();
            long anchor = cursor.anchor();
            var next = new ReadCursor(1, 1, 1, anchor + 1, 0, new ReadIdentity(), true);
            var page = new ReadPage(ReadState.Success, new ReadStamp(), next, 1, 1, true);
            return CompletableFuture.completedFuture(new ReadResponse<>(new ObservationQuery(), page, List.of(anchor)));
        }, new ReadCursor(), row -> row == 2).join();
        assertEquals(List.of(2L), response.values());
        assertEquals(3, calls.get());
        assertEquals(3, response.page().next().anchor());
        assertTrue(response.page().hasMore());
    }

    @Test
    void scansAreBoundedAndInvalidCursorsDoNotLoop()
    {
        var calls = new AtomicInteger();
        var result = ActivityScope.page(cursor -> {
            calls.incrementAndGet();
            var next = new ReadCursor(1, 1, 1, cursor.anchor() + 1, 0, new ReadIdentity(), true);
            return CompletableFuture.completedFuture(new ReadResponse<>(new ObservationQuery(),
                    new ReadPage(ReadState.Success, new ReadStamp(), next, 0, 1, true), List.<Long>of()));
        }, new ReadCursor(), row -> true).join();
        assertEquals(17, calls.get());
        assertTrue(result.page().hasMore());
        assertThrows(CompletionException.class, () -> ActivityScope.page(cursor -> CompletableFuture.completedFuture(
                new ReadResponse<>(new ObservationQuery(), new ReadPage(ReadState.Success, new ReadStamp(), cursor, 0, 0, true), List.<Long>of())),
                new ReadCursor(), row -> true).join());
    }

    @Test
    void directionColorsArePreservedAsFormattingCodes()
    {
        assertEquals("&aLong&7", FinancialText.direction(PositionSide.Long, "Long"));
        assertEquals("&cShort&7", FinancialText.direction(PositionSide.Short, "Short"));
        assertEquals("&aBuy&7", FinancialText.direction(OrderSide.Bid, "Buy"));
        assertEquals("&cSell&7", FinancialText.direction(OrderSide.Ask, "Sell"));
    }
}
