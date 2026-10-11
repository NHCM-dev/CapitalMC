package nhcm.capitalmc.gui.subgui.trading;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.world.item.Items;
import nhcm.advmatch.model.SymbolType;
import nhcm.advmatch.model.SymbolView;
import nhcm.capitalmc.exchange.OrderBookData;
import nhcm.capitalmc.exchange.SymbolUtil;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.service.GameServices;
import nhcm.capitalmc.service.DecimalUnits;
import nhcm.capitalmc.i18n.I18n;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;

/**
 * Professional depth and its display options share the cached trading window.
 */
public class OrderBookPage extends BaseSubGui
{
    private final boolean configuration;
    private final PollingValue<SymbolUtil.Snapshot> statistics = new PollingValue<>(null);
    private final PollingValue<SymbolView> symbol = new PollingValue<>(null);
    private long marketId;
    private int page;
    private int pageCount;

    public OrderBookPage(TradingGui gui, boolean configuration)
    {
        super(gui, configuration ? PageType.BOOK_SETTINGS : PageType.ORDER_BOOK);
        this.configuration = configuration;
        setCenterItem(window -> window.getSelectedSymbol() == null ? null
                : GameServices.get().markets.icon(window, window.getSelectedSymbol(), null));
    }

    public static void comparator(TradingGui gui, int slot, String details)
    {
        var builder = new GuiElementBuilder(Items.COMPARATOR).setName(TradingGui.text(gui.getPlayer(), "ui.book.professional"));
        for (var line : I18n.lines(gui.getPlayer().clientInformation().language(), details + "\n" + GuiWidgets.text(gui, "ui.book.open_hint")))
        {
            builder.addLoreLine(TradingGui.lore(line));
        }
        gui.setSlot(slot, builder.setCallback((index, type, action, window) -> GuiActions.run(gui, () -> {
            if (type.isRight)
            {
                gui.showPage(PageType.BOOK_SETTINGS);
            }
            else if (type.isLeft)
            {
                gui.showPage(PageType.ORDER_BOOK);
            }
        })).build());
    }

    public static List<OrderBookData.Level> levels(TradingGui gui, SymbolView market, SymbolUtil.Snapshot data, boolean ask)
    {
        return gui.bookSettings().levels(market, data, ask);
    }

    public static String levelLore(TradingGui gui, SymbolView market, SymbolUtil.Snapshot data, OrderBookData.Level level)
    {
        var services = GameServices.get();
        var base = services.assets.find(market.baseAssetId()).orElseThrow();
        var quote = services.assets.find(market.quoteAssetId()).orElseThrow();
        long multiplier = data.contract() == null ? 1 : data.contract().spec().contractMultiplier();
        String quantity = services.markets.quantityUnits(market).format(level.quantity());
        if (services.markets.lotSize(market.symbolId()) > 0)
        {
            quantity += " " + GuiWidgets.text(gui, market.type() == SymbolType.Spot ? "ui.lot.spot" : "ui.lot.contract");
        }
        String lore = GuiWidgets.text(gui, "ui.book.detail", quantity,
                                      level.orders(), base.displayName(), base.format(level.quantity().multiply(BigInteger.valueOf(multiplier))),
                                      quote.displayName(), quote.format(level.notional()));
        if (level.low() != level.high())
        {
            lore += "\n" + GuiWidgets.text(gui, "ui.book.range", base.units().price(level.low(), quote.units()), base.units().price(level.high(), quote.units()));
        }
        if (gui.bookSettings().cumulative)
        {
            lore += "\n" + GuiWidgets.text(gui, "ui.book.cumulative", base.format(level.cumulativeQuantity().multiply(BigInteger.valueOf(multiplier))),
                                           quote.format(level.cumulativeNotional()), base.displayName(), quote.displayName());
        }
        return lore + "\n" + GuiWidgets.text(gui, gui.bookSettings().selectPrice ? "ui.book.pick_hint" : "ui.book.read_only");
    }

    private String summary(TradingGui gui, SymbolView market, SymbolUtil.Snapshot data,
                           List<OrderBookData.Level> bids, List<OrderBookData.Level> asks)
    {
        var services = GameServices.get();
        var base = services.assets.find(market.baseAssetId()).orElseThrow();
        var quote = services.assets.find(market.quoteAssetId()).orElseThrow();
        long multiplier = data.contract() == null ? 1 : data.contract().spec().contractMultiplier();
        String missing = GuiWidgets.text(gui, "ui.book.unavailable");
        String bestBid = bids.isEmpty() ? missing : base.units().price(bids.getFirst().price(), quote.units());
        String bestAsk = asks.isEmpty() ? missing : base.units().price(asks.getFirst().price(), quote.units());
        String spread = missing, mid = missing, relative = missing;
        if (!bids.isEmpty() && !asks.isEmpty())
        {
            long buy = bids.getFirst().price(), sell = asks.getFirst().price();
            spread = base.units().price(sell - buy, quote.units());
            BigDecimal midpoint = BigDecimal.valueOf(buy).add(BigDecimal.valueOf(sell)).divide(BigDecimal.valueOf(2));
            mid = base.units().price(BigInteger.valueOf(buy).add(BigInteger.valueOf(sell)), BigInteger.TWO, quote.units());
            relative = BigDecimal.valueOf(sell).subtract(BigDecimal.valueOf(buy)).multiply(BigDecimal.valueOf(100))
                                 .divide(midpoint, 4, RoundingMode.HALF_UP).toPlainString() + "%";
        }
        var bidQuantity = OrderBookData.quantity(bids);
        var askQuantity = OrderBookData.quantity(asks);
        var bidCash = OrderBookData.notional(bids);
        var askCash = OrderBookData.notional(asks);
        var bidWeight = gui.bookSettings().notionalRatio ? bidCash : bidQuantity;
        var askWeight = gui.bookSettings().notionalRatio ? askCash : askQuantity;
        var total = bidWeight.add(askWeight);
        String lore = GuiWidgets.text(gui, "ui.book.summary", market.name(), bestBid, bestAsk, spread, relative, mid,
                                      base.displayName(), quote.displayName(), gui.bookSettings().depth, bids.size(), asks.size());
        lore += "\n" + GuiWidgets.text(gui, "ui.book.totals", base.format(bidQuantity.multiply(BigInteger.valueOf(multiplier))),
                                       base.format(askQuantity.multiply(BigInteger.valueOf(multiplier))), quote.format(bidCash), quote.format(askCash));
        lore += "\n" + GuiWidgets.text(gui, "ui.book.ratio", GuiWidgets.text(gui, gui.bookSettings().notionalRatio ? "ui.book.by_notional" : "ui.book.by_quantity"),
                                       OrderBookData.share(bidWeight, total), OrderBookData.share(askWeight, total),
                                       total.signum() == 0 ? missing : OrderBookData.share(bidWeight.subtract(askWeight), total));
        BigInteger volume = BigInteger.ZERO, turnover = BigInteger.ZERO;
        long low = Long.MAX_VALUE, high = 0;
        for (var row : data.trades())
        {
            var amount = BigInteger.valueOf(row.trade().quantity()).multiply(BigInteger.valueOf(multiplier));
            volume = volume.add(amount);
            turnover = turnover.add(amount.multiply(BigInteger.valueOf(row.trade().price())));
            low = Math.min(low, row.trade().price());
            high = Math.max(high, row.trade().price());
        }
        var latest = data.trades().stream().max(Comparator.comparingLong(row -> row.recordId()));
        lore += "\n" + GuiWidgets.text(gui, "ui.book.tape", latest.map(row -> base.units().price(row.trade().price(), quote.units())).orElse(missing),
                                       high == 0 ? missing : base.units().price(low, quote.units()), high == 0 ? missing : base.units().price(high, quote.units()),
                                       base.format(volume), quote.format(turnover), data.trades().size(),
                                       volume.signum() == 0 ? missing : base.units().price(turnover, volume, quote.units()));
        if (data.contract() != null)
        {
            var contract = data.contract();
            lore += "\n" + GuiWidgets.text(gui, "ui.trade.contract_statistics", base.units().price(contract.referencePrices().indexPrice(), quote.units()),
                                           base.units().price(contract.markPrice(), quote.units()),
                                           DecimalUnits.format(contract.referencePrices().fundingRate(), 4),
                                           DecimalUnits.format(contract.lastFundingRate(), 4), quote.displayName(), base.displayName());
        }
        return lore + "\n" + GuiWidgets.text(gui, "ui.book.snapshot_hint");
    }

    @Override
    public void render(TradingGui gui)
    {
        pageCount = 0;
        if (!GuiActions.ready(gui))
        {
            return;
        }
        if (configuration)
        {
            renderSettings(gui);
            return;
        }
        var selected = gui.getSelectedSymbol();
        if (selected == null)
        {
            GuiWidgets.button(gui, 22, Items.PAPER, "ui.trade.select_symbol", "", () -> gui.showPage(PageType.LIST_SYMBOLS));
            return;
        }
        if (marketId != selected.symbolId())
        {
            marketId = selected.symbolId();
            page = 0;
        }
        var market = symbol.get(marketId, 500, () -> SymbolUtil.find(selected.symbolId()).thenApply(Optional::orElseThrow));
        var data = statistics.get(marketId, gui.bookSettings().refreshMillis, () -> SymbolUtil.statistics(selected.symbolId(), selected.type() != SymbolType.Spot));
        if (market == null || data == null || statistics.error() != null || symbol.error() != null)
        {
            comparator(gui, 22, GuiWidgets.text(gui, statistics.error() != null || symbol.error() != null ? "ui.query_failed" : "ui.loading"));
            return;
        }
        var bids = levels(gui, market, data, false);
        var asks = levels(gui, market, data, true);
        int pages = Math.max(1, (Math.max(bids.size(), asks.size()) + 21) / 22);
        pageCount = pages;
        page = Math.min(page, pages - 1);
        for (boolean ask : new boolean[]{true, false})
        {
            var values = ask ? asks : bids;
            for (int i = 0; i < 22; i++)
            {
                int index = page * 22 + i;
                int slot = OrderBookData.slot(ask, i);
                if (index >= values.size())
                {
                    gui.setSlot(slot, TradingGui.icon(gui.getPlayer(), Items.STAINED_GLASS_PANE.white(), "ui.book.empty", ""));
                    continue;
                }
                var level = values.get(index);
                var base = GameServices.get().assets.find(market.baseAssetId()).orElseThrow();
                var quote = GameServices.get().assets.find(market.quoteAssetId()).orElseThrow();
                var item = ask ? (index == 0 ? Items.STAINED_GLASS_PANE.orange() : Items.STAINED_GLASS_PANE.red())
                        : (index == 0 ? Items.STAINED_GLASS_PANE.lime() : Items.STAINED_GLASS_PANE.green());
                GuiWidgets.button(gui, slot, item, GuiWidgets.text(gui, ask ? "ui.book.ask" : "ui.book.bid", base.units().price(level.price(), quote.units())),
                                  levelLore(gui, market, data, level), () -> {
                            if (!gui.bookSettings().selectPrice)
                            {
                                return;
                            }
                            var trade = (TradePage) gui.page(market.type() == SymbolType.Spot ? PageType.TRADE_SPOT : PageType.TRADE_CONTRACT);
                            if (trade.getSymbol() == null || trade.getSymbol().id() != market.symbolId())
                            {
                                gui.showTrading(market);
                            }
                            else
                            {
                                gui.showPage(trade.getPage());
                            }
                            trade.selectBookPrice(market.symbolId(), level.price());
                        });
            }
        }
        comparator(gui, 22, summary(gui, market, data, bids, asks));
    }

    private void renderSettings(TradingGui gui)
    {
        var settings = gui.bookSettings();
        GuiWidgets.separator(gui);
        gui.setSlot(4, TradingGui.icon(gui.getPlayer(), Items.COMPARATOR, "ui.book.settings", "ui.book.settings_hint"));
        int[] depths = {22, 44, 88, 176}, groups = {1, 5, 10, 50}, intervals = {500, 1000, 2000};
        for (int i = 0; i < depths.length; i++)
        {
            int depth = depths[i], group = groups[i];
            GuiWidgets.option(gui, 18 + i, Items.PAPER, GuiWidgets.text(gui, "ui.book.depth", depth), "ui.book.depth_hint", settings.depth == depth, () -> settings.depth = depth);
            GuiWidgets.option(gui, 27 + i, Items.COMPARATOR, GuiWidgets.text(gui, "ui.book.group", group), "ui.book.group_hint", settings.groupTicks == group, () -> settings.groupTicks = group);
        }
        for (int i = 0; i < intervals.length; i++)
        {
            int interval = intervals[i];
            GuiWidgets.option(gui, 36 + i, Items.CLOCK, GuiWidgets.text(gui, "ui.book.interval", interval), "ui.book.interval_hint", settings.refreshMillis == interval, () -> settings.refreshMillis = interval);
        }
        GuiWidgets.option(gui, 23, Items.BOOK, "ui.book.show_cumulative", "ui.book.cumulative_hint", settings.cumulative, () -> settings.cumulative = !settings.cumulative);
        GuiWidgets.option(gui, 24, Items.GOLD_INGOT, "ui.book.notional_ratio", "ui.book.ratio_hint", settings.notionalRatio, () -> settings.notionalRatio = !settings.notionalRatio);
        GuiWidgets.option(gui, 25, Items.BOW, "ui.book.select_price", "ui.book.pick_hint", settings.selectPrice, () -> settings.selectPrice = !settings.selectPrice);
        GuiWidgets.button(gui, 40, Items.SPONGE, "ui.book.reset", "", () -> {
            settings.depth = 22;
            settings.groupTicks = 1;
            settings.refreshMillis = 500;
            settings.cumulative = true;
            settings.notionalRatio = false;
            settings.selectPrice = true;
        });
        GuiWidgets.button(gui, 41, Items.COMPARATOR, "ui.book.professional", "ui.book.open_hint", () -> gui.showPage(PageType.ORDER_BOOK));
    }

    @Override
    public void renderOverlay(TradingGui gui)
    {
        if (!configuration && pageCount > 0)
        {
            int count = pageCount;
            GuiWidgets.button(gui, 51, Items.ARROW, "ui.book.previous", GuiWidgets.text(gui, "ui.book.page", page + 1, count), () -> page = Math.max(0, page - 1));
            GuiWidgets.button(gui, 52, Items.ARROW, "ui.book.next", GuiWidgets.text(gui, "ui.book.page", page + 1, count), () -> page = Math.min(count - 1, page + 1));
        }
    }
}
