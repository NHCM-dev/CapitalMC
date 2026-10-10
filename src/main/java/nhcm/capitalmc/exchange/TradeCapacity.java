package nhcm.capitalmc.exchange;

import nhcm.advmatch.model.*;

import java.math.BigInteger;
import java.util.List;

/**
 * Display-only sizing from immutable native views. The exchange validates the actual order.
 */
public class TradeCapacity
{
    private static final BigInteger RATE = BigInteger.valueOf(1_000_000), MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private TradeCapacity()
    {
    }

    private static BigInteger n(long value)
    {
        return BigInteger.valueOf(value);
    }

    private static BigInteger ceil(BigInteger amount, long divisor)
    {
        return amount.add(n(divisor - 1)).divide(n(divisor));
    }

    public static long budget(long active, CrossMarginView cross)
    {
        if (cross == null)
        {
            return Math.max(0, active);
        }
        if (cross.liquidating())
        {
            return 0;
        }
        return n(active).add(n(cross.unrealizedPnL())).subtract(n(cross.initialMargin()))
                        .min(n(active)).max(BigInteger.ZERO).longValueExact();
    }

    public record Reservation(BigInteger price, BigInteger margin, BigInteger fee)
    {
        public BigInteger total()
        {
            return margin.add(fee);
        }
    }

    private static BigInteger reservationPrice(MarketView contract, long price, long tick)
    {
        return n(price).max(reservationBand(contract, tick).upper());
    }

    public record PriceBand(BigInteger lower, BigInteger upper)
    {
        public boolean contains(long price)
        {
            return n(price).compareTo(lower) >= 0 && n(price).compareTo(upper) <= 0;
        }
    }

    public static PriceBand priceBand(MarketView contract)
    {
        return priceBand(contract, 0);
    }

    public static PriceBand priceBand(MarketView contract, long priceTick)
    {
        if (contract.spec().priceBandRate() == 0)
        {
            return new PriceBand(BigInteger.ONE, MAX);
        }
        return reservationBand(contract, priceTick);
    }

    private static PriceBand reservationBand(MarketView contract, long priceTick)
    {
        long rate = contract.spec().priceBandRate() == 0 ? 100_000 : contract.spec().priceBandRate();
        BigInteger lower = ceil(n(contract.markPrice()).multiply(RATE.subtract(n(rate))), 1_000_000);
        BigInteger upper = n(contract.markPrice()).multiply(RATE.add(n(rate))).divide(RATE);
        if (rate > 0)
        {
            BigInteger tick = n(priceTick);
            lower = lower.min(n(contract.markPrice()).subtract(tick).max(BigInteger.ONE));
            upper = upper.max(n(contract.markPrice()).add(tick).min(MAX));
        }
        return new PriceBand(lower, upper);
    }

    /**
     * Tick-aligned native execution band; zero means no representable positive limit.
     */
    public static long marketPriceLimit(MarketView contract, OrderSide side, long tick)
    {
        return marketPriceLimit(contract, side, tick, false);
    }

    public static long marketPriceLimit(MarketView contract, OrderSide side, long tick, boolean tickAware)
    {
        if (contract == null || tick <= 0 || contract.markPrice() <= 0)
        {
            return 0;
        }
        BigInteger step = n(tick);
        var band = priceBand(contract, tickAware ? tick : 0);
        BigInteger lower = band.lower(), upper = band.upper().min(MAX);
        lower = lower.max(BigInteger.ONE).add(step.subtract(BigInteger.ONE)).divide(step).multiply(step);
        upper = upper.divide(step).multiply(step);
        return lower.compareTo(upper) > 0 ? 0 : (side == OrderSide.Bid ? upper : lower).longValueExact();
    }

    /**
     * Same conservative reservation as native ContractRiskEngine; a display estimate, not approval.
     */
    public static Reservation reservation(MarketView contract, long price, long quantity, int leverage, boolean reduceOnly)
    {
        return reservation(contract, price, quantity, leverage, reduceOnly, 0);
    }

    public static Reservation reservation(MarketView contract, long price, long quantity, int leverage, boolean reduceOnly, long tick)
    {
        if (price < 0 || quantity < 0 || leverage <= 0)
        {
            throw new IllegalArgumentException("Invalid reservation estimate");
        }
        BigInteger reference = reservationPrice(contract, price, tick);
        BigInteger value = reference.multiply(n(quantity)).multiply(n(contract.spec().contractMultiplier()));
        BigInteger margin = reduceOnly ? BigInteger.ZERO : ceil(value, leverage);
        BigInteger fee = ceil(value.multiply(n(Math.max(contract.spec().makerFeeRate(), contract.spec().takerFeeRate()))), 1_000_000);
        return new Reservation(reference, margin, fee);
    }

    public static long quantity(SymbolView symbol, MarketView contract, OrderSide side, OrderType type, long price,
                                int leverage, long budget, int divisor, List<DepthView> opposite)
    {
        if (budget <= 0 || divisor <= 0 || (!symbol.active()))
        {
            return 0;
        }
        long step = Math.max(1, symbol.tradingRules().quantityStep());
        if (contract != null && (price <= 0 || leverage <= 0 || leverage > contract.spec().maxLeverage() || contract.status() != ContractStatus.Trading))
        {
            return 0;
        }
        if (contract == null && type == OrderType.Limit && price <= 0)
        {
            return 0;
        }
        BigInteger funds = n(budget / divisor);
        BigInteger unitPrice = n(price), multiplier = BigInteger.ONE;
        if (contract != null)
        {
            var spec = contract.spec();
            unitPrice = reservationPrice(contract, type == OrderType.Market ? 0 : price, symbol.tradingRules().tickAwarePriceBand() ? symbol.tradingRules().priceTick() : 0);
            multiplier = n(spec.contractMultiplier());
        }
        final BigInteger p = unitPrice, m = multiplier;
        long maximum = Long.MAX_VALUE;
        var rules = symbol.tradingRules();
        var risk = rules.risk();
        if (risk.maxOrderQuantity() > 0)
        {
            maximum = Math.min(maximum, risk.maxOrderQuantity());
        }
        if (contract != null && risk.maxLeverage() > 0 && leverage > risk.maxLeverage())
        {
            return 0;
        }
        if (type == OrderType.Market)
        {
            BigInteger liquidity = BigInteger.ZERO;
            for (var level : opposite)
                if (contract == null || executable(contract, side, price, level.price(), symbol.tradingRules().tickAwarePriceBand() ? symbol.tradingRules().priceTick() : 0))
                {
                    liquidity = liquidity.add(n(level.quantity()));
                }
            maximum = Math.min(maximum, liquidity.min(MAX).longValueExact());
        }
        long low = 0, high = maximum / step;
        while (low < high)
        {
            long mid = low + (high - low) / 2 + 1;
            BigInteger q = n(mid).multiply(n(step));
            BigInteger notional = p.multiply(q).multiply(m), cost;
            if (contract != null)
            {
                var spec = contract.spec();
                long referencePrice = price;
                if (type == OrderType.Market)
                {
                    referencePrice = 0;
                    long remaining = q.longValueExact();
                    for (var level : opposite)
                    {
                        if (!executable(contract, side, price, level.price(), rules.tickAwarePriceBand() ? rules.priceTick() : 0))
                        {
                            continue;
                        }
                        referencePrice = Math.max(referencePrice, level.price());
                        remaining -= Math.min(remaining, level.quantity());
                        if (remaining == 0)
                        {
                            break;
                        }
                    }
                }
                var estimate = reservation(contract, referencePrice, q.longValueExact(), leverage, false, rules.tickAwarePriceBand() ? rules.priceTick() : 0);
                cost = estimate.total();
                notional = estimate.price().multiply(q).multiply(m);
                if (notional.compareTo(n(spec.maxPositionNotional())) > 0)
                {
                    cost = MAX.add(BigInteger.ONE);
                }
            }
            else if (side == OrderSide.Ask)
            {
                cost = q;
                if (type == OrderType.Market && !opposite.isEmpty())
                {
                    notional = n(opposite.getFirst().price()).multiply(q);
                }
            }
            else if (type == OrderType.Limit)
            {
                cost = notional;
            }
            else
            {
                cost = BigInteger.ZERO;
                BigInteger remaining = q;
                for (var level : opposite)
                {
                    BigInteger take = remaining.min(n(level.quantity()));
                    cost = cost.add(take.multiply(n(level.price())));
                    remaining = remaining.subtract(take);
                    if (remaining.signum() == 0)
                    {
                        break;
                    }
                }
                if (remaining.signum() > 0)
                {
                    cost = MAX.add(BigInteger.ONE);
                }
                notional = cost;
            }
            boolean fits = cost.compareTo(funds) <= 0 && notional.compareTo(MAX) <= 0;
            if (risk.maxOrderNotional() > 0 && notional.compareTo(n(risk.maxOrderNotional())) > 0)
            {
                fits = false;
            }
            if (contract != null && risk.maxPositionNotional() > 0 && notional.compareTo(n(risk.maxPositionNotional())) > 0)
            {
                fits = false;
            }
            if (fits)
            {
                low = mid;
            }
            else
            {
                high = mid - 1;
            }
        }
        long result = low * step;
        if (result < rules.minQuantity())
        {
            return 0;
        }
        if (type == OrderType.Limit && n(price).multiply(n(result)).multiply(m).compareTo(n(rules.minOrderNotional())) < 0)
        {
            return 0;
        }
        if (type == OrderType.Market && contract == null && !opposite.isEmpty())
        {
            BigInteger minimum = n(rules.minOrderNotional());
            if (side == OrderSide.Ask && n(opposite.getFirst().price()).multiply(n(result)).compareTo(minimum) < 0)
            {
                return 0;
            }
            if (side == OrderSide.Bid)
            {
                BigInteger remaining = n(result), value = BigInteger.ZERO;
                for (var level : opposite)
                {
                    BigInteger take = remaining.min(n(level.quantity()));
                    value = value.add(take.multiply(n(level.price())));
                    remaining = remaining.subtract(take);
                    if (remaining.signum() == 0)
                    {
                        break;
                    }
                }
                if (value.compareTo(minimum) < 0)
                {
                    return 0;
                }
            }
        }
        return result;
    }

    private static boolean executable(MarketView contract, OrderSide side, long limit, long price, long tick)
    {
        return priceBand(contract, tick).contains(price)
               && (side == OrderSide.Bid ? price <= limit : price >= limit);
    }
}
