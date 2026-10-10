package nhcm.capitalmc.gui;

import nhcm.advmatch.model.SymbolView;
import nhcm.capitalmc.exchange.OrderBookData;
import nhcm.capitalmc.exchange.SymbolUtil;

import java.util.List;

/**
 * Display preferences retained by the player's cached window, never financial state.
 */
public class BookSettings
{
    public int depth = 22;
    public int groupTicks = 1;
    public int refreshMillis = 500;
    public boolean cumulative = true;
    public boolean notionalRatio;
    public boolean selectPrice = true;

    private SymbolUtil.Snapshot snapshot;
    private long symbolId, tick;
    private int cachedDepth, cachedGrouping;
    private List<OrderBookData.Level> bids = List.of(), asks = List.of();

    public List<OrderBookData.Level> levels(SymbolView symbol, SymbolUtil.Snapshot data, boolean ask)
    {
        if (snapshot != data || symbolId != symbol.symbolId() || tick != symbol.tradingRules().priceTick()
            || cachedDepth != depth || cachedGrouping != groupTicks)
        {
            long multiplier = data.contract() == null ? 1 : data.contract().spec().contractMultiplier();
            var buy = OrderBookData.levels(data.depth().bids(), false, symbol.tradingRules().priceTick(), groupTicks, depth, multiplier);
            var sell = OrderBookData.levels(data.depth().asks(), true, symbol.tradingRules().priceTick(), groupTicks, depth, multiplier);
            bids = buy;
            asks = sell;
            snapshot = data;
            symbolId = symbol.symbolId();
            tick = symbol.tradingRules().priceTick();
            cachedDepth = depth;
            cachedGrouping = groupTicks;
        }
        return ask ? asks : bids;
    }
}
