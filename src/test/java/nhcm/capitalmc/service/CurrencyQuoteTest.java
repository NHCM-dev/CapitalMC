package nhcm.capitalmc.service;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CurrencyQuoteTest
{
    private JsonObject peg()
    {
        return JsonParser.parseString("{\"currency\":2,\"rate\":250,\"baseUnits\":100,\"feePpm\":1000,\"minimum\":1,\"maximum\":10000,\"buy\":true,\"sell\":true,\"enabled\":true}").getAsJsonObject();
    }

    @Test
    void feesRoundUpAndTheTwoDirectionsUseExactRawAmounts()
    {
        var buy = CurrencyService.calculate(1, 150, true, peg());
        var sell = CurrencyService.calculate(1, 150, false, peg());
        assertEquals(375, sell.quoteAmount() + sell.fee());
        assertEquals(1, buy.fee());
        assertEquals(376, buy.quoteAmount());
        assertEquals(374, sell.quoteAmount());
    }

    @Test
    void nonrepresentableRatiosAndZeroNetOutputAreRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> CurrencyService.calculate(1, 1, true, peg()));
        var p = peg();
        p.addProperty("rate", 1);
        p.addProperty("baseUnits", 1);
        assertThrows(IllegalArgumentException.class, () -> CurrencyService.calculate(1, 1, false, p));
    }

    @Test
    void disabledDirectionLimitsAndOverflowAreRejected()
    {
        var p = peg();
        p.addProperty("buy", false);
        assertThrows(IllegalStateException.class, () -> CurrencyService.calculate(1, 100, true, p));
        assertThrows(IllegalArgumentException.class, () -> CurrencyService.calculate(1, 20000, false, p));
        p.addProperty("maximum", Long.MAX_VALUE);
        p.addProperty("rate", Long.MAX_VALUE);
        p.addProperty("baseUnits", 1);
        assertThrows(ArithmeticException.class, () -> CurrencyService.calculate(1, 2, false, p));
    }

    @Test
    void legacyPegDefaultsAndConfigurationFingerprintArePreserved()
    {
        var p = JsonParser.parseString("{\"currency\":2,\"rate\":250}").getAsJsonObject();
        var old = CurrencyService.calculate(1, 2, true, p);
        assertEquals(500, old.quoteAmount());
        p.addProperty("rate", 251);
        assertNotEquals(old.configuration(), CurrencyService.calculate(1, 2, true, p).configuration());
    }
}
