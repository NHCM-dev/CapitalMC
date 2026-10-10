package nhcm.capitalmc.exchange;

import nhcm.advmatch.model.DepthView;
import nhcm.advmatch.model.OrderSide;
import org.junit.jupiter.api.Test;
import nhcm.capitalmc.service.DecimalUnits;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookDataTest
{
    @Test
    void groupingPreservesActualPricesNotionalAndCumulativeAmounts()
    {
        var asks = List.of(new DepthView(1, OrderSide.Ask, 103, 3, 2), new DepthView(1, OrderSide.Ask, 101, 2, 1), new DepthView(1, OrderSide.Ask, 111, 4, 1));
        var result = OrderBookData.levels(asks, true, 1, 5, 22, 10);
        assertEquals(2, result.size());
        var first = result.getFirst();
        assertEquals(101, first.price());
        assertEquals(103, first.high());
        assertEquals(BigInteger.valueOf(5), first.quantity());
        assertEquals(BigInteger.valueOf(5110), first.notional());
        assertEquals(BigInteger.valueOf(9), result.getLast().cumulativeQuantity());
        assertEquals(BigInteger.valueOf(9550), result.getLast().cumulativeNotional());
        var bids = OrderBookData.levels(List.of(new DepthView(1, OrderSide.Bid, 99, 1, 1), new DepthView(1, OrderSide.Bid, 98, 1, 1), new DepthView(1, OrderSide.Bid, 94, 1, 1)), false, 1, 5, 1, 1);
        assertEquals(1, bids.size());
        assertEquals(99, bids.getFirst().price());
        assertEquals(BigInteger.valueOf(197), bids.getFirst().notional());
    }

    @Test
    void totalsDoNotOverflowAndEmptySharesAreUnavailable()
    {
        var result = OrderBookData.levels(List.of(new DepthView(1, OrderSide.Bid, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
                                                  new DepthView(1, OrderSide.Bid, Long.MAX_VALUE, Long.MAX_VALUE, 1)), false, 1, 1, 22, Long.MAX_VALUE);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), OrderBookData.quantity(result));
        assertTrue(OrderBookData.notional(result).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0);
        assertEquals("—", OrderBookData.share(BigInteger.ZERO, BigInteger.ZERO));
        assertEquals("25.00%", OrderBookData.share(BigInteger.ONE, BigInteger.valueOf(4)));
        assertEquals("-50.00%", OrderBookData.share(BigInteger.valueOf(-2), BigInteger.valueOf(4)));
    }

    @Test
    void bookSlotsFollowBothSidesOutwardFromTheCenterWithoutOverlap()
    {
        for (int i = 0; i < 22; i++)
        {
            assertEquals(21 - i, OrderBookData.slot(true, i));
            assertEquals(23 + i, OrderBookData.slot(false, i));
        }
        assertThrows(IllegalArgumentException.class, () -> OrderBookData.slot(true, 22));
        assertThrows(IllegalArgumentException.class, () -> OrderBookData.slot(false, -1));
    }

    @Test
    void averagePricesRemainExactEvenBelowEightDecimals()
    {
        var base = new DecimalUnits.Unit(0, BigDecimal.ONE);
        var quote = new DecimalUnits.Unit(18, BigDecimal.ONE);
        assertEquals("0.0000000000000000015", base.price(BigInteger.valueOf(3), BigInteger.TWO, quote));
        assertEquals("1/3", base.price(BigInteger.ONE, BigInteger.valueOf(3), base));
    }
}
