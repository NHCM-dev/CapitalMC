package nhcm.capitalmc.gui.input;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class InputWindowTest
{
    @Test
    void acceptsValidInputExactlyOnce()
    {
        var window = new InputWindow<>(Duration.ofSeconds(30), InputWindow::positiveLong, 100);
        assertEquals(12L, window.submit(" 12 ", 101));
        assertTrue(window.isFinished());
        assertThrows(IllegalStateException.class, () -> window.submit("13", 102));
    }

    @Test
    void invalidInputDoesNotConsumeWindowOrExtendDeadline()
    {
        var window = new InputWindow<>(Duration.ofNanos(10), InputWindow::positiveLong, 100);
        for (String invalid : new String[]{"hello", "0", "-1", "12.5", "9223372036854775808", ""})
        {
            assertThrows(IllegalArgumentException.class, () -> window.submit(invalid, 109));
        }
        assertFalse(window.isFinished());
        assertTrue(window.isExpired(110));
        assertThrows(IllegalStateException.class, () -> window.submit("1", 110));
    }

    @Test
    void cancellationPreventsLaterInput()
    {
        var window = new InputWindow<>(Duration.ofSeconds(30), InputWindow::positiveLong, 100);
        window.cancel();
        assertThrows(IllegalStateException.class, () -> window.submit("1", 101));
    }

    @Test
    void validatesTimeoutAndSupportsCustomParsers()
    {
        assertThrows(IllegalArgumentException.class,
                     () -> new InputWindow<>(Duration.ZERO, String::trim, 0));
        var window = new InputWindow<>(Duration.ofSeconds(1), String::toUpperCase, 0);
        assertEquals("HELLO", window.submit(" hello ", 1));
    }

    @Test
    void monotonicDeadlineSurvivesNanoTimeWraparound()
    {
        long start = Long.MAX_VALUE - 5;
        var window = new InputWindow<>(Duration.ofNanos(10), InputWindow::positiveLong, start);
        assertFalse(window.isExpired(start + 9));
        assertTrue(window.isExpired(start + 10));
    }

    @Test
    void credentialParserReceivesTheOriginalTextWithoutTrimming()
    {
        var window = new InputWindow<>(Duration.ofSeconds(30), Function.identity(), 0, false);
        assertEquals(" secret ", window.submit(" secret ", 1));
    }
}
