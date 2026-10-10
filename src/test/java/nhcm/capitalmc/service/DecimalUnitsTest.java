package nhcm.capitalmc.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;

class DecimalUnitsTest
{
    @Test
    void exactDecimalInputAndFormatting()
    {
        assertEquals(12345, DecimalUnits.parse("123.45", 2));
        assertEquals("123.45", DecimalUnits.format(12345, 2));
        assertEquals("-0.01", DecimalUnits.format(-1, 2));
        assertEquals(1, DecimalUnits.parse("1.000", 0));
    }

    @Test
    void neverRoundsOrOverflows()
    {
        for (String value : new String[]{"0", "-1", "NaN", "0.001", "9223372036854775808"})
            assertThrows(IllegalArgumentException.class, () -> DecimalUnits.parse(value, 2));
        assertThrows(IllegalArgumentException.class, () -> DecimalUnits.parse("1.1", 0));
        assertThrows(ArithmeticException.class, () -> DecimalUnits.notional(Long.MAX_VALUE, 2, 0));
    }

    @Test
    void pricesAccountForBothAssetScales()
    {
        int precision = DecimalUnits.priceScale(2, 4);
        long price = DecimalUnits.parse("2.50", precision), quantity = DecimalUnits.parse("1.50", 2);
        assertEquals(37500, DecimalUnits.notional(price, quantity, 2));
        assertEquals("3.75", DecimalUnits.format(price * quantity, 4));
        assertEquals(150, DecimalUnits.affordable(37500, price, 2, 1));
        assertEquals(140, DecimalUnits.affordable(37500, price, 2, 20));
        assertEquals(2, DecimalUnits.parse("200", -2));
        assertThrows(IllegalArgumentException.class, () -> DecimalUnits.parse("201", -2));
    }

    @Test
    void notionalLoreUsesBothScalesAndContractMultiplierWithoutOverflow()
    {
        assertEquals("3.75", DecimalUnits.quoteAmount(250, 150, 1, 4));
        assertEquals("37.5", DecimalUnits.quoteAmount(250, 150, 10, 4));
        assertEquals("18446744073709551614", DecimalUnits.quoteAmount(Long.MAX_VALUE, 2, 1, 0));
    }

    @Test
    void displayUnitsPreserveNativeCountsAndBothPriceDenominations()
    {
        var items = new DecimalUnits.Unit(0, new BigDecimal("100"));
        var cash = new DecimalUnits.Unit(2, new BigDecimal("2"));
        assertEquals("0.01", items.format(1));
        assertEquals(1, items.parse("0.01"));
        assertEquals("12.5", items.price(25, cash));
        assertEquals(25, items.parsePrice("12.5", cash));
        assertEquals(50, new DecimalUnits.Unit(0, new BigDecimal("100")).parse("0.5"));
        assertThrows(IllegalArgumentException.class, () -> items.parse("0.001"));
        assertThrows(IllegalArgumentException.class, () -> items.parsePrice("12.51", cash));
    }

    @Test
    void arbitraryLotSizesUseExactFractionsInsteadOfRounding()
    {
        var lots = new DecimalUnits.Unit(0, new BigDecimal("3"));
        assertEquals("1/3", lots.format(1));
        assertEquals(1, lots.parse("1/3"));
        assertEquals(3, lots.parse("1"));
        assertThrows(IllegalArgumentException.class, () -> lots.parse("0.33333333"));
        assertThrows(IllegalArgumentException.class, () -> lots.parse("1/0"));
        assertThrows(IllegalArgumentException.class, () -> lots.parse("1e1000000"));
        assertThrows(IllegalArgumentException.class, () -> new DecimalUnits.Unit(0, BigDecimal.ZERO));
    }
}
