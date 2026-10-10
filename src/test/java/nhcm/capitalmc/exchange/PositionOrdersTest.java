package nhcm.capitalmc.exchange;

import nhcm.advmatch.model.OrderSide;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PositionOrdersTest
{
    @Test
    void triggerProtectionFollowsDirectionAndDoesNotLockLowPrices()
    {
        assertEquals(90, PositionOrders.triggerProtection(100, OrderSide.Ask, 100000, 1));
        assertEquals(110, PositionOrders.triggerProtection(100, OrderSide.Bid, 100000, 1));
        assertEquals(1, PositionOrders.triggerProtection(1, OrderSide.Ask, 100000, 1));
        assertEquals(2, PositionOrders.triggerProtection(1, OrderSide.Bid, 100000, 1));
        assertEquals(2, PositionOrders.triggerProtection(2, OrderSide.Ask, 0, 2));
        assertEquals(4, PositionOrders.triggerProtection(2, OrderSide.Bid, 0, 2));
    }

    @Test
    void triggerProtectionAlignsToTicksAndCannotOverflow()
    {
        long bid = PositionOrders.triggerProtection(Long.MAX_VALUE - 1, OrderSide.Bid, 100000, 2);
        assertTrue(bid > 0);
        assertEquals(0, bid % 2);
        assertEquals(95, PositionOrders.triggerProtection(100, OrderSide.Ask, 30000, 5));
        assertEquals(105, PositionOrders.triggerProtection(100, OrderSide.Bid, 30000, 5));
    }
}
