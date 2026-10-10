package nhcm.capitalmc.exchange;

import nhcm.advmatch.*;
import nhcm.advmatch.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class TradeCapacityTest
{
    @TempDir
    Path directory;

    private Exchange exchange()
    {
        return new Exchange(new ExchangeConfig().withPersistence(new PersistenceConfig().withEnabled(true).withDirectory(directory.toString())));
    }

    @Test
    void spotFractionsFollowNativeUnitsAndMarketBuyWalksMultipleLevels()
    {
        try (var e = exchange())
        {
            var b = e.assets().create("Base", "base", 2).join();
            var q = e.assets().create("Quote", "quote", 4).join();
            var symbol = e.symbols().create("base/quote", SymbolType.Spot, b, q).join();
            e.books().addSpot(symbol, OrderBookImplementation.Radix).join();
            var view = symbol.snapshot().join();
            assertEquals(100, TradeCapacity.quantity(view, null, OrderSide.Bid, OrderType.Limit, 250, 1, 100000, 4, List.of()));
            assertEquals(200, TradeCapacity.quantity(view, null, OrderSide.Bid, OrderType.Limit, 250, 1, 100000, 2, List.of()));
            assertEquals(400, TradeCapacity.quantity(view, null, OrderSide.Bid, OrderType.Limit, 250, 1, 100000, 1, List.of()));
            var levels = List.of(new DepthView(symbol.id(), OrderSide.Ask, 250, 100, 1), new DepthView(symbol.id(), OrderSide.Ask, 500, 200, 1));
            assertEquals(250, TradeCapacity.quantity(view, null, OrderSide.Bid, OrderType.Market, 0, 1, 100000, 1, levels));
            assertEquals(0, TradeCapacity.quantity(view, null, OrderSide.Bid, OrderType.Market, 0, 1, 100000, 1, List.of()));
            assertEquals(37, TradeCapacity.quantity(view, null, OrderSide.Ask, OrderType.Limit, 250, 1, 150, 4, List.of()));
            assertEquals(Long.MAX_VALUE, TradeCapacity.quantity(view, null, OrderSide.Ask, OrderType.Limit, 1, 1, Long.MAX_VALUE, 1, List.of()));
        }
    }

    @Test
    void contractSizingMatchesNativeBandMultiplierMarginAndRoundedFees()
    {
        try (var e = exchange())
        {
            var b = e.assets().create("Base", "base", 0).join();
            var q = e.assets().create("Quote", "quote", 0).join();
            var symbol = e.symbols().create("Contract", SymbolType.Perpetual, b, q).join();
            var spec = new ContractSpec().withFirstFundingTimestamp(28800001).withContractMultiplier(3).withMakerFeeRate(500).withTakerFeeRate(1000);
            e.books().addContract(symbol, spec, 100, 1, OrderBookImplementation.Radix).join();
            var view = e.contracts().market(symbol).join();
            long capacity = TradeCapacity.quantity(symbol.snapshot().join(), view, OrderSide.Bid, OrderType.Limit, 90, 10, 1000, 1, List.of());
            assertEquals(30, capacity); // ceil(110*3*30/10) + ceil(110*3*30*.001) = 1000
            var user = e.users().create().join();
            user.contractAccount().join().updateBalance(q, 1000).join();
            var options = new ContractOrderOptions().withLeverage(10).withMarginMode(MarginMode.Isolated);
            assertThrows(CompletionException.class, () -> symbol.book().placeContract(user, OrderSide.Bid, OrderType.Limit, TimeInForce.GTC, 90, capacity + 1, 2, options).join());
            assertNotNull(symbol.book().placeContract(user, OrderSide.Bid, OrderType.Limit, TimeInForce.GTC, 90, capacity, 3, options).join());
        }
    }

    @Test
    void lowLimitPriceDoesNotBypassMarkBasedMargin()
    {
        try (var e = exchange())
        {
            var b = e.assets().create("Diamond", "diamond", 0).join();
            var q = e.assets().create("Capital Currency", "cmc", 2).join();
            var symbol = e.symbols().create("DIA/CMC", SymbolType.Perpetual, b, q).join();
            e.books().addContract(symbol, new ContractSpec().withFirstFundingTimestamp(28800001), 10000, 1, OrderBookImplementation.Radix).join();
            var view = e.contracts().market(symbol).join();
            var estimate = TradeCapacity.reservation(view, 10, 1, 1, false);
            assertEquals(11000, estimate.price().longValueExact());
            assertEquals(11000, estimate.margin().longValueExact());
            assertEquals(6, estimate.fee().longValueExact());
            assertEquals(11006, estimate.total().longValueExact());
            assertEquals(0, TradeCapacity.quantity(symbol.snapshot().join(), view, OrderSide.Bid, OrderType.Limit, 10, 1, 1000, 1, List.of()));
            var user = e.users().create().join();
            user.contractAccount().join().updateBalance(q, 1000).join();
            var options = new ContractOrderOptions().withLeverage(1).withMarginMode(MarginMode.Isolated);
            var failure = assertThrows(CompletionException.class, () -> symbol.book().placeContract(user, OrderSide.Bid, OrderType.Limit, TimeInForce.GTC, 10, 1, 2, options).join());
            assertInstanceOf(BusinessException.class, failure.getCause());
            assertEquals(4, ((BusinessException) failure.getCause()).receipt().result().state());
            var balance = user.contractAccount().join().balances(new ObservationQuery().withAssetId(q.id())).join().values().getFirst();
            assertEquals(1000, balance.active());
            assertEquals(0, balance.reserved());
            assertEquals(6, TradeCapacity.reservation(view, 10, 1, 1, true).total().longValueExact());
        }
    }

    @Test
    void marketProtectionUsesTickAlignedBandsAndExecutableLiquidity()
    {
        try (var e = exchange())
        {
            var b = e.assets().create("Base", "base", 0).join();
            var q = e.assets().create("Quote", "quote", 0).join();
            var symbol = e.symbols().create("Market", SymbolType.Perpetual, b, q).join();
            e.books().addContract(symbol, new ContractSpec().withFirstFundingTimestamp(28800001).withTakerFeeRate(0), 100, 1, OrderBookImplementation.Radix).join();
            var view = e.contracts().market(symbol).join();
            assertEquals(110, TradeCapacity.marketPriceLimit(view, OrderSide.Bid, 1));
            assertEquals(90, TradeCapacity.marketPriceLimit(view, OrderSide.Ask, 1));
            assertEquals(105, TradeCapacity.marketPriceLimit(view, OrderSide.Bid, 7));
            assertEquals(91, TradeCapacity.marketPriceLimit(view, OrderSide.Ask, 7));
            var asks = List.of(new DepthView(symbol.id(), OrderSide.Ask, 100, 20, 1), new DepthView(symbol.id(), OrderSide.Ask, 200, 100, 1));
            for (int divisor : new int[]{4, 2, 1})
            {
                assertEquals(1100 / divisor / 110, TradeCapacity.quantity(symbol.snapshot().join(), view, OrderSide.Bid, OrderType.Market, 110, 1, 1100, divisor, asks));
            }
            assertEquals(0, TradeCapacity.quantity(symbol.snapshot().join(), view, OrderSide.Bid, OrderType.Market, 95, 1, 1100, 1, asks));
            assertEquals(0, TradeCapacity.quantity(symbol.snapshot().join(), view, OrderSide.Bid, OrderType.Market, 300, 1, 1100, 1, asks.subList(1, 2)));
            assertEquals(10, TradeCapacity.quantity(symbol.snapshot().join(), view, OrderSide.Bid, OrderType.Market, 300, 1, 1100, 1, asks));
            var seller = e.users().create().join();
            var buyer = e.users().create().join();
            seller.contractAccount().join().updateBalance(q, 5000).join();
            buyer.contractAccount().join().updateBalance(q, 1100).join();
            var options = new ContractOrderOptions().withLeverage(1);
            symbol.book().placeContract(seller, OrderSide.Ask, OrderType.Limit, TimeInForce.GTC, 100, 20, 2, options).join();
            assertNotNull(symbol.book().placeContract(buyer, OrderSide.Bid, OrderType.Market, TimeInForce.IOC, 0, 10, 3, options.withPriceLimit(110)).join());
        }
    }

    @Test
    void integerPriceBandAtOneDoesNotExecuteRestingOrdersAtTwo()
    {
        try (var e = exchange())
        {
            var b = e.assets().create("Diamond", "diamond", 0).join();
            var q = e.assets().create("Melon", "melon", 0).join();
            var symbol = e.symbols().create("DIA/MELON", SymbolType.Perpetual, b, q).join();
            e.books().addContract(symbol, new ContractSpec().withFirstFundingTimestamp(28800001), 1, 1, OrderBookImplementation.Radix).join();
            var view = e.contracts().market(symbol).join();
            assertEquals(1, TradeCapacity.priceBand(view).lower().longValueExact());
            assertEquals(1, TradeCapacity.priceBand(view).upper().longValueExact());
            var asks = List.of(new DepthView(symbol.id(), OrderSide.Ask, 2, 293, 1));
            for (int divisor : new int[]{4, 2, 1})
            {
                assertEquals(0, TradeCapacity.quantity(symbol.snapshot().join(), view, OrderSide.Bid, OrderType.Market, 1, 1, 577, divisor, asks));
                assertEquals(0, TradeCapacity.quantity(symbol.snapshot().join(), view, OrderSide.Bid, OrderType.Market, 2, 1, 577, divisor, asks));
            }
            var seller = e.users().create().join();
            var buyer = e.users().create().join();
            seller.contractAccount().join().updateBalance(q, 1000).join();
            buyer.contractAccount().join().updateBalance(q, 577).join();
            var options = new ContractOrderOptions().withLeverage(1);
            symbol.book().placeContract(seller, OrderSide.Ask, OrderType.Limit, TimeInForce.GTC, 2, 293, 2, options).join();
            var order = symbol.book().placeContract(buyer, OrderSide.Bid, OrderType.Market, TimeInForce.IOC, 0, 1, 3, options.withPriceLimit(2)).join();
            assertEquals(0, order.snapshot().join().filling().filled());
            assertEquals(577, buyer.contractAccount().join().balances(new ObservationQuery().withAssetId(q.id())).join().values().getFirst().active());
        }
    }

    @Test
    void tickAwareMarketContinuesTradingAtAdjacentPriceAndReplaysExactly()
    {
        long symbolId;
        try (var e = exchange())
        {
            var base = e.assets().create("Diamond", "diamond", 0).join();
            var quote = e.assets().create("Melon", "melon", 0).join();
            var symbol = e.symbols().create("DIA/MELON", SymbolType.Perpetual, base, quote).join();
            symbolId = symbol.id();
            e.books().addContract(symbol, new ContractSpec().withFirstFundingTimestamp(28800001), 1, 1, OrderBookImplementation.Radix).join();
            var buyer = e.users().create().join();
            var seller = e.users().create().join();
            buyer.contractAccount().join().updateBalance(quote, 577).join();
            seller.contractAccount().join().updateBalance(quote, 1000).join();
            var options = new ContractOrderOptions().withLeverage(1);
            symbol.book().placeContract(seller, OrderSide.Ask, OrderType.Limit, TimeInForce.GTC, 1, 10, 2, options).join();
            assertEquals(10, symbol.book().placeContract(buyer, OrderSide.Bid, OrderType.Market, TimeInForce.IOC, 0, 10, 3, options.withPriceLimit(2)).join().snapshot().join().filling().filled());
            var resting = symbol.book().placeContract(seller, OrderSide.Ask, OrderType.Limit, TimeInForce.GTC, 2, 293, 4, options).join();
            assertFalse(symbol.snapshot().join().tradingRules().tickAwarePriceBand());
            e.rules().configure(symbol, symbol.snapshot().join().tradingRules().withTickAwarePriceBand(true)).join();
            var market = e.contracts().market(symbol).join();
            assertEquals(2, TradeCapacity.marketPriceLimit(market, OrderSide.Bid, 1, true));
            var asks = List.of(new DepthView(symbol.id(), OrderSide.Ask, 2, 293, 1));
            for (int i = 0; i < 3; i++)
            {
                assertEquals(new int[]{71, 143, 288}[i], TradeCapacity.quantity(symbol.snapshot().join(), market, OrderSide.Bid, OrderType.Market, 2, 1, 577, new int[]{4, 2, 1}[i], asks));
            }
            assertEquals(500, market.spec().takerFeeRate());
            for (int i = 0; i < 3; i++)
            {
                var type = i == 1 ? OrderType.Limit : OrderType.Market;
                var order = symbol.book().placeContract(buyer, OrderSide.Bid, type, type == OrderType.Limit ? TimeInForce.GTC : TimeInForce.IOC, type == OrderType.Limit ? 2 : 0, 10, 5 + i, options.withPriceLimit(type == OrderType.Market ? 2 : 0)).join();
                assertEquals(10, order.snapshot().join().filling().filled());
            }
            resting.cancel(8).join();
            var far = symbol.book().placeContract(seller, OrderSide.Ask, OrderType.Limit, TimeInForce.GTC, 3, 10, 9, options).join();
            var blocked = symbol.book().placeContract(buyer, OrderSide.Bid, OrderType.Market, TimeInForce.IOC, 0, 10, 10, options.withPriceLimit(3)).join();
            assertEquals(0, blocked.snapshot().join().filling().filled());
            assertEquals(OrderStatus.Cancelled, far.snapshot().join().filling().status());
        }
        try (var e = exchange())
        {
            assertTrue(e.symbols().get(symbolId).snapshot().join().tradingRules().tickAwarePriceBand());
            assertEquals(2, TradeCapacity.marketPriceLimit(e.contracts().market(e.symbols().get(symbolId)).join(), OrderSide.Bid, 1, true));
        }
    }

    @Test
    void crossBudgetExcludesExistingMarginAndNeverSpendsUnrealizedProfit()
    {
        assertEquals(700, TradeCapacity.budget(1000, new CrossMarginView(1, 2, 1000, 0, 200, 0, 0, -100, 0, false, null)));
        assertEquals(1000, TradeCapacity.budget(1000, new CrossMarginView(1, 2, 1000, 0, 200, 0, 0, 500, 0, false, null)));
        assertEquals(0, TradeCapacity.budget(1000, new CrossMarginView(1, 2, 1000, 0, 200, 0, 0, 500, 0, true, null)));
    }
}
