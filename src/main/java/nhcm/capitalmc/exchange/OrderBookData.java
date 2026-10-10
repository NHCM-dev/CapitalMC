package nhcm.capitalmc.exchange;

import nhcm.advmatch.model.DepthView;
import nhcm.advmatch.model.OrderSide;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;

/**
 * Read-only aggregation. Prices for order entry always remain actual quoted prices.
 */
public class OrderBookData
{
    public record Level(long price, long low, long high, BigInteger quantity, BigInteger notional,
                        BigInteger orders, BigInteger cumulativeQuantity, BigInteger cumulativeNotional)
    {
    }

    public static List<Level> levels(List<DepthView> source, boolean ask, long tick, int grouping, int depth, long multiplier)
    {
        if (tick <= 0 || grouping <= 0 || depth <= 0 || multiplier <= 0)
        {
            throw new IllegalArgumentException("Invalid order book display configuration");
        }
        BigInteger width = BigInteger.valueOf(tick).multiply(BigInteger.valueOf(grouping));
        var groups = new TreeMap<BigInteger, List<DepthView>>(ask ? Comparator.naturalOrder() : Comparator.reverseOrder());
        for (var row : source)
        {
            if (row.price() <= 0 || row.quantity() <= 0 || row.side() != (ask ? OrderSide.Ask : OrderSide.Bid))
            {
                continue;
            }
            var price = BigInteger.valueOf(row.price());
            var bucket = grouping == 1 ? price : (ask ? price.add(width).subtract(BigInteger.ONE) : price).divide(width);
            groups.computeIfAbsent(bucket, unused -> new ArrayList<>()).add(row);
        }
        var result = new ArrayList<Level>();
        BigInteger cumulative = BigInteger.ZERO, cumulativeCash = BigInteger.ZERO;
        for (var rows : groups.values())
        {
            if (result.size() == depth)
            {
                break;
            }
            long low = rows.stream().mapToLong(DepthView::price).min().orElseThrow();
            long high = rows.stream().mapToLong(DepthView::price).max().orElseThrow();
            BigInteger quantity = BigInteger.ZERO, cash = BigInteger.ZERO, orders = BigInteger.ZERO;
            for (var row : rows)
            {
                var amount = BigInteger.valueOf(row.quantity());
                quantity = quantity.add(amount);
                cash = cash.add(amount.multiply(BigInteger.valueOf(row.price())).multiply(BigInteger.valueOf(multiplier)));
                orders = orders.add(BigInteger.valueOf(row.orderCount()));
            }
            cumulative = cumulative.add(quantity);
            cumulativeCash = cumulativeCash.add(cash);
            result.add(new Level(ask ? low : high, low, high, quantity, cash, orders, cumulative, cumulativeCash));
        }
        return List.copyOf(result);
    }

    public static String share(BigInteger value, BigInteger total)
    {
        return total.signum() == 0 ? "—" : new BigDecimal(value).multiply(BigDecimal.valueOf(100))
                                                                .divide(new BigDecimal(total), 2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    public static BigInteger quantity(List<Level> levels)
    {
        return levels.isEmpty() ? BigInteger.ZERO : levels.getLast().cumulativeQuantity();
    }

    public static BigInteger notional(List<Level> levels)
    {
        return levels.isEmpty() ? BigInteger.ZERO : levels.getLast().cumulativeNotional();
    }

    public static int slot(boolean ask, int index)
    {
        if (index < 0 || index >= 22)
        {
            throw new IllegalArgumentException("Invalid book slot");
        }
        return ask ? 21 - index : 23 + index;
    }
}
