package nhcm.capitalmc.gui.input;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

/**
 * Pure deadline and parsing state, using monotonic nanoseconds.
 */
public class InputWindow<T>
{
    private final long started;
    private final long timeout;
    private final Function<String, T> parser;
    private final boolean strip;
    private boolean finished;

    public InputWindow(Duration timeout, Function<String, T> parser, long now)
    {
        this(timeout, parser, now, true);
    }

    public InputWindow(Duration timeout, Function<String, T> parser, long now, boolean strip)
    {
        this.strip = strip;
        this.timeout = Objects.requireNonNull(timeout, "timeout").toNanos();
        if (this.timeout <= 0)
        {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        this.parser = Objects.requireNonNull(parser, "parser");
        started = now;
    }

    public boolean isExpired(long now)
    {
        return now - started >= timeout;
    }

    public boolean isFinished()
    {
        return finished;
    }

    public T submit(String input, long now)
    {
        if (finished || isExpired(now))
        {
            throw new IllegalStateException("Input window is closed");
        }
        T value = Objects.requireNonNull(parser.apply(strip ? input.strip() : input), "Parser returned null");
        finished = true;
        return value;
    }

    public void cancel()
    {
        finished = true;
    }

    public static long positiveLong(String input)
    {
        try
        {
            long value = Long.parseLong(input.strip());
            if (value > 0)
            {
                return value;
            }
        } catch (NumberFormatException ignored)
        {
        }
        throw new IllegalArgumentException("gui.capitalmc.input.positive_integer");
    }
}
