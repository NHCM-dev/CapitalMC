package nhcm.capitalmc.service;

import nhcm.advmatch.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdministrationSettingsTest
{
    @Test
    void nestedPatchPreservesCompleteNativeSettings()
    {
        var original = new TradingRules().withPriceTick(5).withQuantityStep(10).withFees(new FeeSchedule(200, 300));
        var edited = AdministrationService.patch(original, "{\"fees\":{\"takerRate\":400}}", TradingRules.class);
        assertEquals(5, edited.priceTick());
        assertEquals(10, edited.quantityStep());
        assertEquals(200, edited.fees().makerRate());
        assertEquals(400, edited.fees().takerRate());
        assertThrows(IllegalArgumentException.class, () -> AdministrationService.patch(original, "{\"unknown\":1}", TradingRules.class));
        assertThrows(IllegalArgumentException.class, () -> AdministrationService.patch(original, "{\"fees\":null}", TradingRules.class));
    }

    @Test
    void malformedArgumentListsCannotStartAnAdministrationIntent()
    {
        assertThrows(IllegalArgumentException.class, () -> AdministrationService.validateArguments("symbol", new String[]{"future", "market", "base", "quote"}));
        assertThrows(IllegalArgumentException.class, () -> AdministrationService.validateArguments("mint", new String[]{"cash"}));
        assertThrows(IllegalArgumentException.class, () -> AdministrationService.validateArguments("bogus", new String[]{}));
        assertDoesNotThrow(() -> AdministrationService.validateArguments("protection", new String[]{"1", "0"}));
        assertDoesNotThrow(() -> AdministrationService.validateArguments("protection", new String[]{"1", "12.5"}));
        for (String invalid : new String[]{"100", "-1", "0.00001", "not-a-number"})
        {
            assertThrows(IllegalArgumentException.class, () -> AdministrationService.validateArguments("protection", new String[]{"1", invalid}));
        }
        assertThrows(IllegalArgumentException.class, () -> AdministrationService.validateArguments("protection", new String[]{"1"}));
        assertDoesNotThrow(() -> AdministrationService.validateArguments("halt", new String[]{"1", "yes"}));
    }
}
