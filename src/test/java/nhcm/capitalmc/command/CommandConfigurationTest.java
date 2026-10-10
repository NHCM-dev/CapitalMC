package nhcm.capitalmc.command;

import nhcm.advmatch.model.TradingRules;
import nhcm.advmatch.model.ReferencePriceConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandConfigurationTest
{
    @Test
    void everyNestedScalarIsListedWithoutAcceptingRawJson()
    {
        var fields = CommandConfiguration.fields(TradingRules.class);
        assertTrue(fields.containsKey("risk.maxOrderQuantity"));
        assertTrue(fields.containsKey("fees.takerRate"));
        assertEquals(boolean.class, fields.get("tickAwarePriceBand"));
        assertFalse(CommandConfiguration.fields(ReferencePriceConfig.class).containsKey("sources"));
        assertThrows(IllegalArgumentException.class, () -> CommandConfiguration.patch(TradingRules.class, "unknown", "1"));
    }

    @Test
    void patchIsExactTypedAndDoesNotOverwriteSiblingSettings()
    {
        var patch = CommandConfiguration.patch(TradingRules.class, "risk.maxOrderQuantity", "9007199254740993");
        assertEquals(9007199254740993L, patch.getAsJsonObject("risk").get("maxOrderQuantity").getAsLong());
        assertEquals(1, patch.size());
        assertEquals(1, patch.getAsJsonObject("risk").size());
        assertThrows(IllegalArgumentException.class, () -> CommandConfiguration.patch(TradingRules.class, "priceTick", "0.5"));
        assertThrows(IllegalArgumentException.class, () -> CommandConfiguration.patch(TradingRules.class, "priceTick", "9223372036854775808"));
        assertThrows(IllegalArgumentException.class, () -> CommandConfiguration.patch(TradingRules.class, "tickAwarePriceBand", "yes"));
        assertTrue(CommandConfiguration.patch(TradingRules.class, "tickAwarePriceBand", "true").get("tickAwarePriceBand").getAsBoolean());
        assertEquals("CancelBoth", CommandConfiguration.patch(TradingRules.class, "selfTradePrevention", "cancelboth").get("selfTradePrevention").getAsString());
    }
}
