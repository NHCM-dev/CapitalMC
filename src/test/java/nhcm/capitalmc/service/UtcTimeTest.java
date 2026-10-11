package nhcm.capitalmc.service;

import org.junit.jupiter.api.Test;
import nhcm.capitalmc.exchange.FinancialText;
import static org.junit.jupiter.api.Assertions.*;

class UtcTimeTest
{
    @Test
    void acceptsDatesTimesOffsetsAndExistingMillisecondInput()
    {
        long expected = 1791676800000L;
        assertEquals(expected, UtcTime.parse("2026-10-11"));
        assertEquals(expected, UtcTime.parse("2026-10-11 00:00:00"));
        assertEquals(expected, UtcTime.parse("2026-10-11T00:00:00Z"));
        assertEquals(expected, UtcTime.parse("2026-10-11T08:00:00+08:00"));
        assertEquals(expected, UtcTime.parse(Long.toString(expected)));
        assertEquals("2026-10-11 00:00:00.000 UTC", FinancialText.time(expected));
        assertEquals("—", FinancialText.time(0));
        assertThrows(IllegalArgumentException.class, () -> UtcTime.parse("bad date"));
        assertThrows(IllegalArgumentException.class, () -> UtcTime.future("2000-01-01"));
    }
}
