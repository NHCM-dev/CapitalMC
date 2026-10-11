package nhcm.capitalmc.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Shared date input for contract schedules and administrator commands. */
public class UtcTime
{
    public static long parse(String input)
    {
        String value = input.strip().replace(' ', 'T');
        try
        {
            if (value.matches("[0-9]+"))
            {
                return Long.parseLong(value);
            }
            if (value.length() == 10)
            {
                return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            }
            if (value.endsWith("Z"))
            {
                return Instant.parse(value).toEpochMilli();
            }
            if (value.substring(10).contains("+") || value.substring(10).contains("-"))
            {
                return OffsetDateTime.parse(value).toInstant().toEpochMilli();
            }
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        catch (RuntimeException error)
        {
            throw new IllegalArgumentException("ui.form.timestamp", error);
        }
    }

    public static long future(String input)
    {
        long timestamp = parse(input);
        if (timestamp <= System.currentTimeMillis())
        {
            throw new IllegalArgumentException("ui.form.future_time");
        }
        return timestamp;
    }
}
