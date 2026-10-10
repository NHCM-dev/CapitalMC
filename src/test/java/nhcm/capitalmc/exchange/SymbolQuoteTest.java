package nhcm.capitalmc.exchange;

import nhcm.advmatch.model.Trade;
import nhcm.advmatch.model.TradeView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SymbolQuoteTest
{
    private TradeView trade(long id, String timestamp, long price)
    {
        return new TradeView(id, id, 1, Instant.parse(timestamp).toEpochMilli(), 1, 2, 1, 2, new Trade(1, 2, price, 1), 0, 0, 0, 0);
    }

    @Test
    void lastExecutionYesterdayUsesUtcBoundaryAndRecordIdForTies()
    {
        var quote = SymbolUtil.quote(List.of(trade(5, "2026-10-10T00:00:00Z", 120),
                                             trade(4, "2026-10-09T23:59:59Z", 100), trade(3, "2026-10-09T23:59:59Z", 90),
                                             trade(1, "2026-10-08T23:59:59Z", 50), trade(6, "2026-10-11T00:00:00Z", 900)),
                                     Instant.parse("2026-10-10T08:00:00Z").toEpochMilli());
        assertEquals(120, quote.latest());
        assertEquals(100, quote.previousClose());
        assertEquals("+20.00%", quote.change());
    }

    @Test
    void missingYesterdayIsNotReplacedByOlderTradesOrZeroChange()
    {
        var quote = SymbolUtil.quote(List.of(trade(1, "2026-10-08T23:59:59Z", 100), trade(2, "2026-10-10T01:00:00Z", 90)),
                                     Instant.parse("2026-10-10T08:00:00Z").toEpochMilli());
        assertEquals(90, quote.latest());
        assertEquals(0, quote.previousClose());
        assertEquals("", quote.change());
    }

    @Test
    void percentageUsesExactIntegersAndPreservesDirection()
    {
        assertEquals("-25.00%", new SymbolUtil.Quote(75, 100).change());
        assertEquals("0.00%", new SymbolUtil.Quote(100, 100).change());
        assertEquals("+200.00%", new SymbolUtil.Quote(Long.MAX_VALUE, Long.MAX_VALUE / 3).change());
        assertEquals("", new SymbolUtil.Quote(0, 100).change());
        var clockReversed = SymbolUtil.quote(List.of(trade(1, "2026-10-10T03:00:00Z", 70), trade(2, "2026-10-10T02:00:00Z", 75)),
                                             Instant.parse("2026-10-10T08:00:00Z").toEpochMilli());
        assertEquals(75, clockReversed.latest());
    }
}
