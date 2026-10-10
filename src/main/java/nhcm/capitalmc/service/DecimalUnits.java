package nhcm.capitalmc.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

public class DecimalUnits
{
    private DecimalUnits()
    {
    }

    /**
     * Presentation denomination. Native balances and prices always retain their integer units.
     */
    public record Unit(int scale, BigDecimal size)
    {
        public Unit
        {
            if (scale < 0 || scale > 18 || size.signum() <= 0 || size.precision() > 24 || Math.abs((long) size.scale()) > 18)
            {
                throw new IllegalArgumentException("ui.units.invalid");
            }
            size = size.stripTrailingZeros();
        }

        public BigDecimal rawPerUnit()
        {
            return size.movePointRight(scale);
        }

        public long parse(String input)
        {
            return parseRatio(input, rawPerUnit(), BigDecimal.ONE);
        }

        public String format(long raw)
        {
            return format(BigInteger.valueOf(raw));
        }

        public String format(BigInteger raw)
        {
            return ratio(new BigDecimal(raw), rawPerUnit());
        }

        public long parsePrice(String input, Unit quote)
        {
            return parseRatio(input, quote.rawPerUnit(), rawPerUnit());
        }

        public String price(long raw, Unit quote)
        {
            return ratio(BigDecimal.valueOf(raw).multiply(rawPerUnit()), quote.rawPerUnit());
        }

        public String price(BigInteger numerator, BigInteger denominator, Unit quote)
        {
            if (denominator.signum() <= 0)
            {
                throw new IllegalArgumentException("Invalid price denominator");
            }
            return ratio(new BigDecimal(numerator).multiply(rawPerUnit()), new BigDecimal(denominator).multiply(quote.rawPerUnit()));
        }
    }

    private static BigDecimal inputDecimal(String value)
    {
        BigDecimal decimal = new BigDecimal(value.strip());
        if (Math.abs((long) decimal.scale()) > 96 || decimal.precision() > 128)
        {
            throw new ArithmeticException();
        }
        return decimal;
    }

    private static long parseRatio(String input, BigDecimal numerator, BigDecimal denominator)
    {
        try
        {
            String[] parts = input.strip().split("/", -1);
            if (parts.length > 2)
            {
                throw new ArithmeticException();
            }
            numerator = numerator.multiply(inputDecimal(parts[0]));
            if (parts.length == 2)
            {
                BigDecimal divisor = inputDecimal(parts[1]);
                if (divisor.signum() <= 0)
                {
                    throw new ArithmeticException();
                }
                denominator = denominator.multiply(divisor);
            }
            long result = numerator.divide(denominator, 0, RoundingMode.UNNECESSARY).longValueExact();
            if (result <= 0)
            {
                throw new ArithmeticException();
            }
            return result;
        } catch (ArithmeticException | NumberFormatException error)
        {
            throw new IllegalArgumentException("ui.input.precision");
        }
    }

    /**
     * Non-terminating decimals use a reduced fraction rather than silently rounding money.
     */
    private static String ratio(BigDecimal numerator, BigDecimal denominator)
    {
        try
        {
            return numerator.divide(denominator).stripTrailingZeros().toPlainString();
        } catch (ArithmeticException error)
        {
            int scale = Math.max(numerator.scale(), denominator.scale());
            BigInteger top = numerator.movePointRight(scale).toBigIntegerExact();
            BigInteger bottom = denominator.movePointRight(scale).toBigIntegerExact();
            BigInteger divisor = top.gcd(bottom);
            return top.divide(divisor) + "/" + bottom.divide(divisor);
        }
    }

    public static long parse(String input, int scale)
    {
        if (scale < -18 || scale > 18)
        {
            throw new IllegalArgumentException("Invalid scale");
        }
        try
        {
            BigDecimal value = new BigDecimal(input.strip());
            long raw = value.movePointRight(scale).longValueExact();
            if (raw <= 0)
            {
                throw new ArithmeticException();
            }
            return raw;
        } catch (ArithmeticException | NumberFormatException error)
        {
            throw new IllegalArgumentException("ui.input.precision");
        }
    }

    public static String format(long raw, int scale)
    {
        return BigDecimal.valueOf(raw, scale).stripTrailingZeros().toPlainString();
    }

    /**
     * AdvMatch multiplies raw price by raw quantity; asset scales are display metadata.
     */
    public static int priceScale(int baseScale, int quoteScale)
    {
        return quoteScale - baseScale;
    }

    /**
     * Exact display even when the preview exceeds the engine's long range.
     */
    public static String quoteAmount(long price, long quantity, long multiplier, int quoteScale)
    {
        BigInteger raw = BigInteger.valueOf(price).multiply(BigInteger.valueOf(quantity)).multiply(BigInteger.valueOf(multiplier));
        return format(raw, quoteScale);
    }

    public static String format(BigInteger raw, int scale)
    {
        return new BigDecimal(raw, scale).stripTrailingZeros().toPlainString();
    }

    public static long notional(long price, long quantity, int quantityScale)
    {
        return Math.multiplyExact(price, quantity);
    }

    public static long affordable(long quoteRaw, long priceRaw, int baseScale, long quantityStep)
    {
        if (priceRaw <= 0 || quantityStep <= 0)
        {
            return 0;
        }
        long units = BigDecimal.valueOf(quoteRaw)
                               .divide(BigDecimal.valueOf(priceRaw), 0, RoundingMode.DOWN).longValueExact();
        return units / quantityStep * quantityStep;
    }
}
