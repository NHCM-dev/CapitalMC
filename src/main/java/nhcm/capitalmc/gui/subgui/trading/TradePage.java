package nhcm.capitalmc.gui.subgui.trading;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import nhcm.advmatch.BusinessException;
import nhcm.advmatch.command.read.ReadCrossMargin;
import nhcm.advmatch.model.*;
import nhcm.advmatch.object.Symbol;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.exchange.*;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.service.*;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Shared spot/contract ticket. All page state changes run on the Minecraft server thread.
 */
public abstract class TradePage extends BaseSubGui
{
    private Symbol symbol;
    private SymbolView market;
    private AssetView base, quote;
    private Throwable loadingError;
    private long selection, balanceRequest;
    private OrderSide side = OrderSide.Bid;
    private OrderType type = OrderType.Limit;
    private TimeInForce tif = TimeInForce.GTC;
    private long price, quantity;
    private int leverage = 1;
    private MarginMode margin = MarginMode.Isolated;
    private boolean submitting;
    private UUID accountContext;
    private long accountEpoch;
    private int fractionSelected;
    private ContractAction contractAction = ContractAction.OPEN_LONG;
    private long closingPosition;
    private CloseData displayedClose = new CloseData(List.of(), List.of());
    private final PollingValue<CloseData> closeData = new PollingValue<>(displayedClose);

    private enum ContractAction
    {
        OPEN_LONG(OrderSide.Bid, false, "ui.trade.open_long"),
        OPEN_SHORT(OrderSide.Ask, false, "ui.trade.open_short"),
        CLOSE_LONG(OrderSide.Ask, true, "ui.trade.close_long"),
        CLOSE_SHORT(OrderSide.Bid, true, "ui.trade.close_short");

        final OrderSide side;
        final boolean closing;
        final String label;

        ContractAction(OrderSide side, boolean closing, String label)
        {
            this.side = side;
            this.closing = closing;
            this.label = label;
        }
    }

    private record CloseData(List<PositionView> positions, List<OrderView> orders)
    {
    }

    private record FundsContext(UUID user, long epoch, long selection, long asset, AccountType account)
    {
    }

    private record Funds(long available)
    {
    }

    private final PollingValue<Funds> funds = new PollingValue<>(null);
    private final PollingValue<SymbolUtil.Snapshot> statistics = new PollingValue<>(null);
    private final PollingValue<Long> quoteBalance = new PollingValue<>(null);
    private Long displayedQuoteBalance;
    private Funds displayedFunds;
    private SymbolUtil.Snapshot displayedStatistics;

    private record CapacityKey(long available, SymbolView market, SymbolUtil.Snapshot statistics, OrderSide side,
                               OrderType type, long price, int leverage)
    {
    }

    private CapacityKey capacityKey;
    private final long[] capacities = new long[3];
    private SymbolUtil.Snapshot loreSnapshot;
    private String cachedStatisticsLore;
    private final PollingValue<SymbolView> symbolState = new PollingValue<>(null);

    protected TradePage(TradingGui gui, PageType page)
    {
        super(gui, page);
        setCenterItem(owner -> TradingGui.icon(Items.COMPASS));
    }

    public Symbol getSymbol()
    {
        return symbol;
    }

    public void setSymbol(Symbol value)
    {
        symbol = Objects.requireNonNull(value);
        market = null;
        base = null;
        quote = null;
        loadingError = null;
        long request = ++selection;
        ++balanceRequest;
        price = quantity = 0;
        side = OrderSide.Bid;
        contractAction = ContractAction.OPEN_LONG;
        closingPosition = 0;
        closeData.invalidate();
        type = OrderType.Limit;
        tif = TimeInForce.GTC;
        leverage = 1;
        margin = MarginMode.Isolated;
        symbolState.invalidate();
        funds.invalidate();
        statistics.invalidate();
        fractionSelected = 0;
        value.snapshot().thenCompose(view -> AssetUtil.find(view.baseAssetId()).thenCombine(AssetUtil.find(view.quoteAssetId()),
                                                                                            (b, q) -> new Object[]{view, b.orElseThrow(), q.orElseThrow()})).whenComplete((data, error) ->
                                                                                                                                                                                  getGui().getPlayer().level().getServer().execute(() -> {
                                                                                                                                                                                      if (request != selection)
                                                                                                                                                                                      {
                                                                                                                                                                                          return;
                                                                                                                                                                                      }
                                                                                                                                                                                      loadingError = error;
                                                                                                                                                                                      if (error == null)
                                                                                                                                                                                      {
                                                                                                                                                                                          market = (SymbolView) data[0];
                                                                                                                                                                                          base = (AssetView) data[1];
                                                                                                                                                                                          quote = (AssetView) data[2];
                                                                                                                                                                                      }
                                                                                                                                                                                  }));
    }

    private boolean contract()
    {
        return getPage() == PageType.TRADE_CONTRACT;
    }

    private DecimalUnits.Unit baseUnits()
    {
        return AssetUtil.units(base.assetId(), base.scale());
    }

    private DecimalUnits.Unit quoteUnits()
    {
        return AssetUtil.units(quote.assetId(), quote.scale());
    }

    private DecimalUnits.Unit quantityUnits()
    {
        return GameServices.get().markets.quantityUnits(market);
    }

    private String quantityPrompt()
    {
        long size = GameServices.get().markets.lotSize(market.symbolId());
        return size == 0 ? GuiWidgets.text(getGui(), "ui.input.quantity", base.displayName(), quantityUnits().format(market.tradingRules().minQuantity()), quantityUnits().format(market.tradingRules().quantityStep())) : GuiWidgets.text(getGui(), "ui.lot.input", lotName(), lotEquivalent(size), base.displayName());
    }

    private String lotEquivalent(long size)
    {
        if (contract() && (displayedStatistics == null || displayedStatistics.contract() == null))
        {
            return GuiWidgets.text(getGui(), "ui.loading");
        }
        long multiplier = contract() ? displayedStatistics.contract().spec().contractMultiplier() : 1;
        return baseUnits().format(BigInteger.valueOf(size).multiply(BigInteger.valueOf(multiplier)));
    }

    private String lotName()
    {
        return GuiWidgets.text(getGui(), contract() ? "ui.lot.contract" : "ui.lot.spot");
    }

    private String priceText(long raw)
    {
        return baseUnits().price(raw, quoteUnits());
    }

    private long ticketPrice()
    {
        if (type == OrderType.Market && contract() && price == 0)
        {
            return TradeCapacity.marketPriceLimit(displayedStatistics == null ? null : displayedStatistics.contract(), side,
                                                  market == null ? 1 : Math.max(1, market.tradingRules().priceTick()), market != null && market.tradingRules().tickAwarePriceBand());
        }
        return price;
    }

    private String quantityText(long raw)
    {
        return quantityUnits().format(raw) + " " + (GameServices.get().markets.lotSize(market.symbolId()) == 0 ? base.displayName() : lotName());
    }

    private String amountsLore(long rawPrice, long rawQuantity)
    {
        if (type == OrderType.Market && contract() && rawPrice == 0)
        {
            rawPrice = ticketPrice();
        }
        String cash;
        long multiplier = contract() && displayedStatistics != null && displayedStatistics.contract() != null
                ? displayedStatistics.contract().spec().contractMultiplier() : 1;
        String baseAmount = contract() && (displayedStatistics == null || displayedStatistics.contract() == null)
                ? GuiWidgets.text(getGui(), "ui.loading")
                : baseUnits().format(BigInteger.valueOf(rawQuantity).multiply(BigInteger.valueOf(multiplier)));
        if (rawPrice <= 0)
        {
            cash = GuiWidgets.text(getGui(), "ui.trade.estimate_unavailable");
        }
        else if (contract() && (displayedStatistics == null || displayedStatistics.contract() == null))
        {
            cash = GuiWidgets.text(getGui(), "ui.loading");
        }
        else
        {
            cash = quoteUnits().format(BigInteger.valueOf(rawPrice).multiply(BigInteger.valueOf(rawQuantity)).multiply(BigInteger.valueOf(multiplier)));
        }
        return GuiWidgets.text(getGui(), "ui.trade.amounts", base.displayName(), baseAmount, quote.displayName(), cash);
    }

    private boolean unprotectedMarket()
    {
        return type == OrderType.Market && contract() && price == 0 && displayedStatistics != null
               && displayedStatistics.contract() != null && displayedStatistics.contract().spec().priceBandRate() == 0;
    }

    private String ticketPriceText()
    {
        return unprotectedMarket() ? GuiWidgets.text(getGui(), "ui.settings.market_unbounded") : priceText(ticketPrice());
    }

    private String priceLore()
    {
        if (type == OrderType.Market)
        {
            String mode = contract() ? GuiWidgets.text(getGui(), price == 0 ? "ui.trade.market_auto_limit" : "ui.trade.market_manual_limit",
                                                       ticketPrice() > 0 ? ticketPriceText() : GuiWidgets.text(getGui(), "ui.loading"))
                    : GuiWidgets.text(getGui(), "ui.trade.spot_market_price");
            long reference = ticketPrice();
            if (unprotectedMarket())
            {
                var opposite = side == OrderSide.Bid ? displayedStatistics.depth().asks() : displayedStatistics.depth().bids();
                reference = opposite.isEmpty() ? displayedStatistics.contract().markPrice() : opposite.getFirst().price();
            }
            return mode + marketBandLore() + "\n" + amountsLore(reference, quantity);
        }
        return GuiWidgets.text(getGui(), "ui.trade.price_units", base.displayName(), quote.displayName(), price > 0 ? priceText(price) : GuiWidgets.text(getGui(), "ui.trade.estimate_unavailable"))
               + "\n" + amountsLore(price, quantity);
    }

    private String reservationLore()
    {
        if (!contract())
        {
            return "";
        }
        if (displayedStatistics == null || displayedStatistics.contract() == null || displayedFunds == null)
        {
            return "\n" + GuiWidgets.text(getGui(), "ui.loading");
        }
        var position = contractAction.closing ? closePosition() : null;
        int effectiveLeverage = position == null ? leverage : position.leverage();
        long estimateQuantity = quantity > 0 ? quantity : market.tradingRules().minQuantity();
        var estimate = TradeCapacity.reservation(displayedStatistics.contract(), type == OrderType.Market ? 0 : price, estimateQuantity, effectiveLeverage, contractAction.closing, market.tradingRules().tickAwarePriceBand() ? market.tradingRules().priceTick() : 0);
        String lore = "\n" + GuiWidgets.text(getGui(), quantity > 0 ? "ui.trade.reservation" : "ui.trade.minimum_reservation",
                                             quantityText(estimateQuantity), baseUnits().price(estimate.price(), BigInteger.ONE, quoteUnits()), quoteUnits().format(estimate.margin()),
                                             quoteUnits().format(estimate.fee()), quoteUnits().format(estimate.total()), quote.displayName(), quoteUnits().format(displayedFunds.available()));
        if (estimate.total().compareTo(BigInteger.valueOf(displayedFunds.available())) > 0)
        {
            lore += "\n" + GuiWidgets.text(getGui(), "ui.trade.reservation_shortfall", quoteUnits().format(estimate.total().subtract(BigInteger.valueOf(displayedFunds.available()))), quote.displayName());
        }
        return lore;
    }

    private void limitPrice(long raw)
    {
        if (type == OrderType.Market)
        {
            tif = TimeInForce.GTC;
        }
        type = OrderType.Limit;
        price = raw;
        fractionSelected = 0;
        ++balanceRequest;
    }

    public void selectBookPrice(long symbolId, long rawPrice)
    {
        if (symbol == null || symbol.id() != symbolId || getGui().getSelectedSymbol() == null
            || getGui().getSelectedSymbol().symbolId() != symbolId || rawPrice <= 0)
        {
            throw new IllegalStateException("ui.session.changed");
        }
        limitPrice(rawPrice);
        getGui().refresh();
    }

    private void inputPrice()
    {
        editPrice(type == OrderType.Market && contract());
    }

    private String marketBandLore()
    {
        if (!contract() || displayedStatistics == null || displayedStatistics.contract() == null)
        {
            return "";
        }
        if (displayedStatistics.contract().spec().priceBandRate() == 0)
        {
            return "\n" + GuiWidgets.text(getGui(), "ui.settings.protection_disabled_player");
        }
        var band = TradeCapacity.priceBand(displayedStatistics.contract(), market.tradingRules().tickAwarePriceBand() ? market.tradingRules().priceTick() : 0);
        return "\n" + GuiWidgets.text(getGui(), "ui.trade.execution_band", priceText(displayedStatistics.contract().markPrice()),
                                      baseUnits().price(band.lower(), BigInteger.ONE, quoteUnits()), baseUnits().price(band.upper(), BigInteger.ONE, quoteUnits()));
    }

    private String marketCapacityReason()
    {
        if (displayedStatistics == null)
        {
            return GuiWidgets.text(getGui(), "ui.loading");
        }
        var opposite = side == OrderSide.Bid ? displayedStatistics.depth().asks() : displayedStatistics.depth().bids();
        if (opposite.isEmpty())
        {
            return GuiWidgets.text(getGui(), "ui.trade.market_empty");
        }
        if (contract() && displayedStatistics.contract() != null)
        {
            var band = TradeCapacity.priceBand(displayedStatistics.contract(), market.tradingRules().tickAwarePriceBand() ? market.tradingRules().priceTick() : 0);
            var inBand = opposite.stream().filter(level -> band.contains(level.price())).toList();
            if (inBand.isEmpty())
            {
                return GuiWidgets.text(getGui(), "ui.trade.market_outside_band", priceText(opposite.getFirst().price())) + marketBandLore();
            }
            if (inBand.stream().noneMatch(level -> side == OrderSide.Bid ? level.price() <= ticketPrice() : level.price() >= ticketPrice()))
            {
                return GuiWidgets.text(getGui(), "ui.trade.market_outside_limit", ticketPriceText()) + marketBandLore();
            }
        }
        return GuiWidgets.text(getGui(), "ui.trade.market_no_capacity");
    }

    private void inputLimitPrice()
    {
        editPrice(false);
    }

    private void editPrice(boolean protection)
    {
        requireMarket();
        long context = selection;
        String prompt = (protection ? GuiWidgets.text(getGui(), "ui.trade.market_limit_prompt", GuiWidgets.text(getGui(), side == OrderSide.Bid ? "ui.trade.market_buy_limit" : "ui.trade.market_sell_limit")) + "\n" : "")
                        + GuiWidgets.priceInput(getGui(), base.assetId(), quote.assetId());
        GuiActions.input(getGui(), prompt, text -> baseUnits().parsePrice(text, quoteUnits()), raw -> {
            if (context != selection)
            {
                throw new IllegalStateException("ui.session.changed");
            }
            if (protection)
            {
                price = raw;
                fractionSelected = 0;
            }
            else
            {
                limitPrice(raw);
            }
        });
    }

    private void inputQuantity()
    {
        requireMarket();
        long context = selection;
        GuiActions.input(getGui(), quantityPrompt(), text -> quantityUnits().parse(text), raw -> {
            if (context != selection)
            {
                throw new IllegalStateException("ui.session.changed");
            }
            quantity = raw;
            fractionSelected = 0;
            ++balanceRequest;
        });
    }

    private static TimeInForce parseTif(String text)
    {
        text = switch (text.strip().toLowerCase(Locale.ROOT))
        {
            case "1", "一直有效" -> "GTC";
            case "2", "立即成交其余取消" -> "IOC";
            case "3", "全部成交否则取消" -> "FOK";
            case "4", "只挂单", "只做挂单" -> "PostOnly";
            default -> text;
        };
        String choice = text;
        return Arrays.stream(TimeInForce.values()).filter(value -> value.name().equalsIgnoreCase(choice.strip())).findFirst()
                     .orElseThrow(() -> new IllegalArgumentException("ui.trade.input_tif"));
    }

    private void inputTif()
    {
        requireMarket();
        GuiActions.input(getGui(), "ui.trade.input_tif", TradePage::parseTif, value -> tif = value);
    }

    private long availableQuantity(int divisor)
    {
        if (contract() && contractAction.closing)
        {
            long available = closeAvailable();
            long step = Math.max(1, market.tradingRules().quantityStep());
            return available / divisor / step * step;
        }
        if (!contractAction.closing && (displayedFunds == null || displayedStatistics == null))
        {
            return 0;
        }
        long estimatePrice = capacityPrice();
        var key = new CapacityKey(displayedFunds.available(), market, displayedStatistics, side, type, estimatePrice, leverage);
        if (!key.equals(capacityKey))
        {
            var opposite = side == OrderSide.Bid ? displayedStatistics.depth().asks() : displayedStatistics.depth().bids();
            for (int i = 0; i < 3; i++)
                capacities[i] = TradeCapacity.quantity(market, displayedStatistics.contract(), side, type, estimatePrice, leverage, displayedFunds.available(), new int[]{4, 2, 1}[i], opposite);
            capacityKey = key;
        }
        return capacities[divisor == 4 ? 0 : divisor == 2 ? 1 : 2];
    }

    private void fraction(int divisor)
    {
        requireMarket();
        GuiActions.user(getGui());
        pollFunds(getGui());
        if (!contractAction.closing && funds.error() != null)
        {
            GuiActions.error(getGui(), funds.error());
            return;
        }
        if (!contractAction.closing && statistics.error() != null)
        {
            GuiActions.error(getGui(), statistics.error());
            return;
        }
        if (!contractAction.closing && (displayedFunds == null || displayedStatistics == null))
        {
            GuiActions.message(getGui(), "ui.loading");
            return;
        }
        long available = availableQuantity(divisor);
        if (available <= 0)
        {
            GuiActions.message(getGui(), type == OrderType.Market ? marketCapacityReason() : GuiWidgets.text(getGui(), "ui.trade.no_capacity"));
            if (contract())
            {
                GuiActions.message(getGui(), reservationLore().strip());
            }
            return;
        }
        quantity = available;
        fractionSelected = divisor;
        GuiActions.message(getGui(), "ui.trade.quantity_set", quantityText(quantity));
        getGui().refresh();
    }

    private long capacityPrice()
    {
        if (ticketPrice() > 0 || displayedStatistics == null)
        {
            return ticketPrice();
        }
        var opposite = side == OrderSide.Bid ? displayedStatistics.depth().asks() : displayedStatistics.depth().bids();
        if (!opposite.isEmpty())
        {
            return opposite.getFirst().price();
        }
        return displayedStatistics.contract() == null ? 0 : displayedStatistics.contract().markPrice();
    }

    private String quoteBalanceLore(TradingGui gui)
    {
        String available = quoteBalance.error() != null ? GuiWidgets.text(gui, "ui.query_failed")
                : displayedQuoteBalance == null ? GuiWidgets.text(gui, "ui.loading") : quoteUnits().format(displayedQuoteBalance);
        return GuiWidgets.text(gui, "ui.trade.quote_available", quote.displayName(), available);
    }

    private void pollFunds(TradingGui gui)
    {
        var user = gui.getUser().orElse(null);
        if (user == null)
        {
            displayedFunds = null;
            displayedQuoteBalance = null;
            return;
        }
        var quoteAccount = contract() ? AccountType.Contract : AccountType.Spot;
        displayedQuoteBalance = quoteBalance.get(new FundsContext(user.getUuid(), CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID()), selection, quote.assetId(), quoteAccount),
                () -> GameServices.get().settlements.account(user, quoteAccount).thenCompose(id -> BalanceUtil.balance(id, quote.assetId()))
                                  .thenApply(value -> value.map(BalanceView::active).orElse(0L)));
        long asset = contract() || side == OrderSide.Bid ? quote.assetId() : base.assetId();
        var account = contract() ? AccountType.Contract : AccountType.Spot;
        displayedFunds = funds.get(new FundsContext(user.getUuid(), CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID()), selection, asset, account), () ->
                GameServices.get().settlements.account(user, account).thenCompose(id -> BalanceUtil.balance(id, asset).thenCompose(balance -> {
                    long active = balance.map(BalanceView::active).orElse(0L);
                    if (!contract())
                    {
                        return CompletableFuture.completedFuture(new Funds(Math.max(0, active)));
                    }
                    return CapitalMC.exchange.submit(new ReadCrossMargin(new ObservationQuery().withAccountId(id).withAssetId(asset)))
                                             .thenApply(response -> {
                                                 if (response.page().state() != ReadState.Success && response.page().state() != ReadState.NotFound)
                                                 {
                                                     throw new BusinessException(response.page());
                                                 }
                                                 return new Funds(TradeCapacity.budget(active, response.values().stream().findFirst().orElse(null)));
                                             });
                })));
    }

    private String statisticsLore(TradingGui gui)
    {
        if (displayedStatistics == null)
        {
            return GuiWidgets.text(gui, statistics.error() == null ? "ui.loading" : "ui.query_failed");
        }
        if (loreSnapshot == displayedStatistics)
        {
            return cachedStatisticsLore;
        }
        BigInteger volume = BigInteger.ZERO, turnover = BigInteger.ZERO;
        long multiplier = contract() ? displayedStatistics.contract().spec().contractMultiplier() : 1;
        for (var row : displayedStatistics.trades())
        {
            var q = BigInteger.valueOf(row.trade().quantity());
            volume = volume.add(q);
            turnover = turnover.add(q.multiply(BigInteger.valueOf(row.trade().price())).multiply(BigInteger.valueOf(multiplier)));
        }
        String lore = GuiWidgets.text(gui, "ui.trade.statistics", baseUnits().format(volume.multiply(BigInteger.valueOf(multiplier))),
                                      quoteUnits().format(turnover), displayedStatistics.trades().size(), base.displayName(), quote.displayName());
        var view = displayedStatistics.contract();
        if (view != null)
        {
            lore += "\n" + GuiWidgets.text(gui, "ui.trade.contract_statistics", priceText(view.referencePrices().indexPrice()), priceText(view.markPrice()),
                                           DecimalUnits.format(view.referencePrices().fundingRate(), 4), DecimalUnits.format(view.lastFundingRate(), 4), quote.displayName(), base.displayName());
        }
        loreSnapshot = displayedStatistics;
        cachedStatisticsLore = lore;
        return lore;
    }

    private String latestPrice()
    {
        if (displayedStatistics == null)
        {
            return GuiWidgets.text(getGui(), "ui.loading");
        }
        return displayedStatistics.trades().stream().max(Comparator.comparingLong(TradeView::recordId))
                                  .map(t -> priceText(t.trade().price())).orElseGet(() -> GuiWidgets.text(getGui(), "ui.trade.no_trades"));
    }

    private void requireMarket()
    {
        if (market == null)
        {
            throw new IllegalStateException("ui.trade.select_symbol");
        }
    }

    private void selectAction(ContractAction action)
    {
        contractAction = action;
        side = action.side;
        closingPosition = 0;
        quantity = 0;
        fractionSelected = 0;
        ++balanceRequest;
        getGui().refresh();
    }

    private List<PositionView> closePositions()
    {
        PositionSide positionSide = contractAction == ContractAction.CLOSE_LONG ? PositionSide.Long : PositionSide.Short;
        return displayedClose.positions().stream()
                             .filter(position -> position.symbolId() == market.symbolId() && position.side() == positionSide
                                                 && !position.closed() && position.size() > 0)
                             .sorted(Comparator.comparingLong(PositionView::positionId)).toList();
    }

    private PositionView closePosition()
    {
        return closePositions().stream().filter(position -> position.positionId() == closingPosition).findFirst().orElse(null);
    }

    private long closeAvailable()
    {
        var position = closePosition();
        if (position == null || position.liquidating())
        {
            return 0;
        }
        long available = position.size();
        for (var order : displayedClose.orders())
        {
            if (order.positionId() == position.positionId() && order.reduceOnly())
            {
                available -= Math.min(available, Math.max(0, order.filling().unfilled()));
            }
        }
        return available;
    }

    private void renderClosePosition(TradingGui gui)
    {
        var user = gui.getUser().orElse(null);
        if (user == null)
        {
            return;
        }
        displayedClose = closeData.get(List.of(user.getUuid(), accountEpoch, market.symbolId()),
                                       () -> TradeUtil.positions(user).thenCombine(TradeUtil.openOrders(user), CloseData::new));
        var positions = closePositions();
        if (positions.stream().noneMatch(position -> position.positionId() == closingPosition))
        {
            closingPosition = positions.isEmpty() ? 0 : positions.getFirst().positionId();
            quantity = 0;
            fractionSelected = 0;
        }
        var position = closePosition();
        String lore = position == null ? GuiWidgets.text(gui, closeData.error() == null ? "ui.trade.no_position" : "ui.query_failed")
                : GuiWidgets.text(gui, "ui.trade.close_position", position.positionId(), quantityText(closeAvailable()), position.leverage(), position.marginMode());
        GuiWidgets.button(gui, 12, Items.SHIELD, "ui.trade.select_position", lore, () -> {
            if (!positions.isEmpty())
            {
                int index = 0;
                while (index < positions.size() && positions.get(index).positionId() != closingPosition)
                {
                    index++;
                }
                closingPosition = positions.get((index + 1) % positions.size()).positionId();
                quantity = 0;
                fractionSelected = 0;
                gui.refresh();
            }
        });
    }

    private String summary()
    {
        return GuiWidgets.text(getGui(), "ui.trade.summary", market.name(), contract() ? GuiWidgets.text(getGui(), contractAction.label) : side, type, tif,
                               type == OrderType.Market ? contract() ? GuiWidgets.text(getGui(), "ui.trade.market_cap", ticketPriceText()) : GuiWidgets.text(getGui(), "gui.capitalmc.trade.spot.mkt") : priceText(price), quantityText(quantity))
               + (contract() ? "\n" + GuiWidgets.text(getGui(), "ui.trade.margin", contractAction.closing && closePosition() != null ? closePosition().leverage() : leverage,
                                                      contractAction.closing && closePosition() != null ? closePosition().marginMode() : margin) : "")
               + (contract() && contractAction.closing ? "\n" + GuiWidgets.text(getGui(), "ui.trade.position_id", closingPosition) : "")
               + reservationLore();
    }

    private void submit()
    {
        requireMarket();
        var user = GuiActions.user(getGui());
        if (submitting)
        {
            throw new IllegalStateException("ui.operation.busy");
        }
        if (!market.active() || quantity <= 0 || (type == OrderType.Limit || contract()) && ticketPrice() <= 0)
        {
            throw new IllegalArgumentException("ui.trade.incomplete");
        }
        var rules = market.tradingRules();
        if (quantity < rules.minQuantity() || quantity % Math.max(1, rules.quantityStep()) != 0)
        {
            throw new IllegalArgumentException("ui.trade.quantity_step");
        }
        if ((type == OrderType.Limit || contract()) && ticketPrice() % Math.max(1, rules.priceTick()) != 0)
        {
            throw new IllegalArgumentException("ui.trade.price_tick");
        }
        if (type == OrderType.Market && (tif == TimeInForce.GTC || tif == TimeInForce.PostOnly))
        {
            throw new IllegalArgumentException("ui.trade.market_tif");
        }
        long symbolId = market.symbolId(), p = type == OrderType.Market ? 0 : price, q = quantity;
        var orderSide = side;
        var orderType = type;
        var timeInForce = tif;
        var options = contract() ? new ContractOrderOptions().withLeverage(leverage).withMarginMode(margin)
                                                             .withPriceLimit(type == OrderType.Market ? ticketPrice() : 0) : null;
        if (contract() && contractAction.closing)
        {
            var position = closePosition();
            if (position == null || quantity > closeAvailable())
            {
                throw new IllegalArgumentException("ui.trade.close_quantity");
            }
            options = options.withPositionId(position.positionId()).withReduceOnly(true)
                             .withLeverage(position.leverage()).withMarginMode(position.marginMode());
        }
        var capturedOptions = options;
        long context = selection, epoch = CapitalMC.serverUserManager.getSessionVersion(getGui().getPlayer().getUUID());
        // Review the exact captured ticket before its private chat confirmation.
        GuiActions.message(getGui(), summary());
        GuiActions.confirm(getGui(), "ui.confirm.order", () -> {
            if (context != selection || epoch != CapitalMC.serverUserManager.getSessionVersion(getGui().getPlayer().getUUID()) || getGui().getUser().filter(u -> u.getUuid().equals(user.getUuid())).isEmpty())
            {
                throw new IllegalStateException("ui.session.changed");
            }
            submitting = true;
            var work = GameServices.get().settlements.place(user, symbolId, orderSide, orderType, timeInForce, p, q, capturedOptions);
            work.whenComplete((receipt, error) -> GameServices.get().server.execute(() -> {
                submitting = false;
                if (error == null && context == selection && epoch == CapitalMC.serverUserManager.getSessionVersion(getGui().getPlayer().getUUID()) && getGui().getUser().filter(u -> u.getUuid().equals(user.getUuid())).isPresent())
                {
                    quantity = 0;
                }
            }));
            GuiActions.task(getGui(), work, "ui.success.order", market.name());
        });
    }

    /**
     * Bow beside the nether star: edit all ticket fields, then review and submit.
     */
    private void wizard()
    {
        requireMarket();
        var gui = getGui();
        if (contract())
        {
            GuiActions.input(gui, "ui.trade.input_contract_action", text -> {
                var actions = ContractAction.values();
                for (int i = 0; i < actions.length; i++)
                {
                    var action = actions[i];
                    if (text.strip().equals(Integer.toString(i + 1)) || action.name().equalsIgnoreCase(text.strip()) || GuiWidgets.text(gui, action.label).equals(text.strip()))
                    {
                        return action;
                    }
                }
                throw new IllegalArgumentException("ui.trade.input_contract_action");
            }, this::selectAction).thenAccept(chosen -> {
                if (chosen.isPresent())
                {
                    wizardType();
                }
            }).exceptionally(error -> {
                GuiActions.error(gui, error);
                return null;
            });
            return;
        }
        GuiActions.input(gui, "ui.trade.input_side", text -> switch (text.strip().toLowerCase(Locale.ROOT))
                  {
                      case "1", "买入", "买", "buy", "bid" -> OrderSide.Bid;
                      case "2", "卖出", "卖", "sell", "ask" -> OrderSide.Ask;
                      default -> throw new IllegalArgumentException("ui.trade.input_side");
                  }, v -> {
                      side = v;
                      fractionSelected = 0;
                  })
                  .thenAccept(chosen -> {
                      if (chosen.isPresent())
                      {
                          wizardType();
                      }
                  })
                  .exceptionally(error -> {
                      GuiActions.error(gui, error);
                      return null;
                  });
    }

    private void wizardType()
    {
        var gui = getGui();
        GuiActions.input(gui, "ui.trade.input_type", text -> switch (text.strip().toLowerCase(Locale.ROOT))
                  {
                      case "1", "限价", "limit" -> OrderType.Limit;
                      case "2", "市价", "market" -> OrderType.Market;
                      default -> throw new IllegalArgumentException("ui.trade.input_type");
                  }, v -> {
                      fractionSelected = 0;
                      type = v;
                      if (v == OrderType.Market)
                      {
                          price = 0;
                      }
                      tif = v == OrderType.Market ? TimeInForce.IOC : TimeInForce.GTC;
                  })
                  .thenAccept(order -> {
                      if (order.isPresent())
                      {
                          wizardTif();
                      }
                  }).exceptionally(error -> {
                      GuiActions.error(gui, error);
                      return null;
                  });
    }

    private void wizardTif()
    {
        GuiActions.input(getGui(), "ui.trade.input_tif", TradePage::parseTif, v -> tif = v).thenAccept(chosen -> {
            if (chosen.isEmpty())
            {
                return;
            }
            if (type == OrderType.Market)
            {
                wizardQuantity();
            }
            else
            {
                GuiActions.input(getGui(), GuiWidgets.priceInput(getGui(), base.assetId(), quote.assetId()), text -> baseUnits().parsePrice(text, quoteUnits()), v -> {
                              price = v;
                              fractionSelected = 0;
                          })
                          .thenAccept(p -> {
                              if (p.isPresent())
                              {
                                  wizardQuantity();
                              }
                          }).exceptionally(error -> {
                              GuiActions.error(getGui(), error);
                              return null;
                          });
            }
        }).exceptionally(error -> {
            GuiActions.error(getGui(), error);
            return null;
        });
    }

    private void wizardQuantity()
    {
        GuiActions.input(getGui(), quantityPrompt(), text -> quantityUnits().parse(text), v -> {
                      quantity = v;
                      fractionSelected = 0;
                  })
                  .thenAccept(q -> {
                      if (q.isPresent())
                      {
                          GuiActions.run(getGui(), this::submit);
                      }
                  }).exceptionally(error -> {
                      GuiActions.error(getGui(), error);
                      return null;
                  });
    }

    private void book(TradingGui gui, List<OrderBookData.Level> levels, int start, boolean ask)
    {
        for (int i = 0; i < 5; i++)
        {
            if (i >= levels.size())
            {
                GuiWidgets.button(gui, start + i, ask ? (i == 0 ? Items.STAINED_GLASS_PANE.orange() : Items.STAINED_GLASS_PANE.red()) : (i == 0 ? Items.STAINED_GLASS_PANE.lime() : Items.STAINED_GLASS_PANE.green()), "ui.book.empty", "ui.trade.input_price", this::inputLimitPrice);
                continue;
            }
            var level = levels.get(i);
            GuiWidgets.button(gui, start + i, ask ? (i == 0 ? Items.STAINED_GLASS_PANE.orange() : Items.STAINED_GLASS_PANE.red()) : (i == 0 ? Items.STAINED_GLASS_PANE.lime() : Items.STAINED_GLASS_PANE.green()),
                              GuiWidgets.text(gui, ask ? "ui.book.ask" : "ui.book.bid", priceText(level.price())),
                              OrderBookPage.levelLore(gui, market, displayedStatistics, level), () -> {
                        if (!gui.bookSettings().selectPrice)
                        {
                            return;
                        }
                        limitPrice(level.price());
                        GuiActions.message(gui, "ui.trade.price_set", priceText(price));
                        gui.refresh();
                    });
        }
    }

    @Override
    public void render(TradingGui gui)
    {
        if (!GuiActions.ready(gui))
        {
            return;
        }
        UUID currentAccount = gui.getUser().map(u -> u.getUuid()).orElse(null);
        long version = CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID());
        if (!Objects.equals(accountContext, currentAccount) || accountEpoch != version)
        {
            accountContext = currentAccount;
            accountEpoch = version;
            quantity = 0;
            fractionSelected = 0;
            funds.invalidate();
            closeData.invalidate();
            closingPosition = 0;
            ++balanceRequest;
        }
        if (market == null)
        {
            GuiWidgets.button(gui, 22, Items.PAPER, "ui.trade.select_symbol", loadingError == null ? "" : "ui.services.failed", () -> gui.showPage(PageType.LIST_SYMBOLS));
            return;
        }
        var updated = symbolState.get(symbol.id(), symbol::snapshot);
        if (updated != null)
        {
            market = updated;
        }
        displayedStatistics = statistics.get(market.symbolId(), gui.bookSettings().refreshMillis, () -> SymbolUtil.statistics(market.symbolId(), contract()));
        pollFunds(gui);
        if (contract())
        {
            var actions = ContractAction.values();
            var icons = new Item[]{Items.WOOL.lime(), Items.WOOL.red(), Items.WOOL.lightBlue(), Items.WOOL.orange()};
            for (int i = 0; i < actions.length; i++)
            {
                var action = actions[i];
                GuiWidgets.option(gui, i, icons[i], action.label, "", contractAction == action, () -> selectAction(action));
            }
            if (contractAction.closing)
            {
                renderClosePosition(gui);
            }
        }
        else
        {
            GuiWidgets.option(gui, 1, Items.WOOL.lime(), "gui.capitalmc.trade.spot.bid", "", side == OrderSide.Bid, () -> {
                fractionSelected = 0;
                side = OrderSide.Bid;
                ++balanceRequest;
            });
            GuiWidgets.option(gui, 2, Items.WOOL.red(), "gui.capitalmc.trade.spot.ask", "", side == OrderSide.Ask, () -> {
                fractionSelected = 0;
                side = OrderSide.Ask;
                ++balanceRequest;
            });
        }

        GuiWidgets.button(gui, 9, Items.BOW, GuiWidgets.text(gui, "ui.trade.tif", tif), "ui.trade.input_tif", this::inputTif);
        GuiWidgets.option(gui, 10, Items.DIAMOND_PICKAXE, "gui.capitalmc.trade.spot.lmt", "", type == OrderType.Limit, () -> {
            fractionSelected = 0;
            type = OrderType.Limit;
            tif = TimeInForce.GTC;
            ++balanceRequest;
        });
        GuiWidgets.option(gui, 11, Items.DIAMOND_AXE, "gui.capitalmc.trade.spot.mkt", contract() ? "ui.trade.market_cap_input" : "", type == OrderType.Market, () -> {
            fractionSelected = 0;
            type = OrderType.Market;
            tif = TimeInForce.IOC;
            price = 0;
            ++balanceRequest;
        });

        GuiWidgets.button(gui, 18, Items.BOW, "ui.trade.edit_price", priceLore(), this::inputPrice);
        GuiWidgets.button(gui, 19, Items.IRON_INGOT, GuiWidgets.text(gui, "ui.trade.price", type == OrderType.Market ? contract() ? GuiWidgets.text(gui, "ui.trade.market_cap", ticketPriceText()) : GuiWidgets.text(getGui(), "gui.capitalmc.trade.spot.mkt") : priceText(price)), priceLore(), this::inputPrice);
        GuiWidgets.button(gui, 27, Items.BOW, "ui.trade.edit_quantity", quoteBalanceLore(gui) + "\n" + amountsLore(price, quantity), this::inputQuantity);
        for (int i = 0; i < 3; i++)
        {
            int divisor = new int[]{4, 2, 1}[i];
            long available = availableQuantity(divisor);
            String status = contract() && contractAction.closing ? GuiWidgets.text(gui, "ui.trade.available_close", quantityText(available)) :
                    gui.getUser().isEmpty() ? GuiWidgets.text(gui, "gui.capitalmc.account.login_required") :
                            funds.error() != null || statistics.error() != null ? GuiWidgets.text(gui, "ui.query_failed") :
                                    displayedFunds == null || displayedStatistics == null ? GuiWidgets.text(gui, "ui.loading") :
                                            GuiWidgets.text(gui, contract() ? (side == OrderSide.Bid ? "ui.trade.available_long" : "ui.trade.available_short")
                                                                          : (side == OrderSide.Bid ? "ui.trade.available_buy" : "ui.trade.available_sell"), quantityText(available));
            status = quoteBalanceLore(gui) + "\n" + status;
            if (type == OrderType.Limit && price <= 0)
            {
                status += "\n" + GuiWidgets.text(gui, "ui.trade.quantity_estimate");
            }
            status += "\n" + amountsLore(capacityPrice(), available) + "\n" + GuiWidgets.text(gui, "ui.trade.quantity", quantityText(quantity))
                      + "\n" + amountsLore(price, quantity) + reservationLore() + "\n" + GuiWidgets.text(gui, "ui.trade.capacity_hint")
                      + "\n" + GuiWidgets.text(gui, "ui.lot.minimum", quantityText(market.tradingRules().minQuantity()), quantityText(market.tradingRules().quantityStep()));
            if (type == OrderType.Market && available == 0)
            {
                status += "\n" + marketCapacityReason();
            }
            if (fractionSelected == divisor)
            {
                status += "\n&a" + GuiWidgets.text(gui, "ui.option.selected");
            }
            GuiWidgets.button(gui, 28 + i, new Item[]{Items.WOOL.white(), Items.WOOL.lightGray(), Items.WOOL.gray()}[i],
                              new String[]{"gui.capitalmc.trade.spot.quarter_quantity", "gui.capitalmc.trade.spot.half_quantity", "gui.capitalmc.trade.spot.full_quantity"}[i], status, () -> fraction(divisor));
            if (fractionSelected == divisor)
            {
                gui.setSlot(28 + i, TradingGui.glowingIcon(gui.getGuiElement(28 + i)));
            }
        }
        GuiWidgets.button(gui, 36, Items.BOW, "ui.trade.wizard", "", this::wizard);
        GuiWidgets.button(gui, 37, submitting ? Items.CLOCK : Items.NETHER_STAR, "gui.capitalmc.trade.spot.confirm_order", summary() + "\n" + amountsLore(price, quantity), this::submit);
        gui.setSlot(13, GuiWidgets.asset(gui, base.assetId(), GuiWidgets.text(gui, "ui.trade.latest_price", latestPrice()), null));
        GuiWidgets.button(gui, 14, Items.PAPER, "ui.trade.market_statistics", statisticsLore(gui), () -> gui.showPage(PageType.SYMBOL_INFO));
        OrderBookPage.comparator(gui, 15, GuiWidgets.text(gui, "ui.book.depth", gui.bookSettings().depth));
        gui.setSlot(42, GuiWidgets.asset(gui, base.assetId(), GuiWidgets.text(gui, "ui.trade.base"), () -> gui.showAsset(base.assetId())));
        gui.setSlot(43, GuiWidgets.asset(gui, quote.assetId(), GuiWidgets.text(gui, "ui.trade.quote"), () -> gui.showAsset(quote.assetId())));
        GuiWidgets.button(gui, 44, Items.OAK_SIGN, market.name(), GuiWidgets.text(gui, "ui.trade.symbol_info", market.type(), priceText(market.tradingRules().priceTick()), quantityText(market.tradingRules().quantityStep()), quantityText(market.tradingRules().minQuantity())), () -> gui.showPage(PageType.SYMBOL_INFO));
        GuiWidgets.button(gui, 38, Items.REDSTONE_TORCH, "ui.conditional.page", "ui.activity.scope_hint", () -> gui.showActivity(market.type(), PageType.CONDITIONALS));
        GuiWidgets.button(gui, 39, Items.BOOK, "ui.orders", "ui.activity.scope_hint", () -> gui.showActivity(market.type(), PageType.ORDERS));
        if (contract())
        {
            if (!contractAction.closing)
            {
                GuiWidgets.button(gui, 20, Items.ANVIL, GuiWidgets.text(gui, "ui.trade.margin", leverage, margin), "ui.trade.input_leverage", () -> GuiActions.input(gui, "ui.trade.input_leverage", text -> {
                    int l = Integer.parseInt(text);
                    if (l <= 0)
                    {
                        throw new IllegalArgumentException("ui.trade.input_leverage");
                    }
                    return l;
                }, v -> {
                    leverage = v;
                    fractionSelected = 0;
                }));
                GuiWidgets.option(gui, 21, margin == MarginMode.Cross ? Items.CHEST : Items.ENDER_CHEST, ExchangeMessages.label(gui.getPlayer(), margin), "ui.trade.toggle_margin", true, () -> {
                    margin = margin == MarginMode.Isolated ? MarginMode.Cross : MarginMode.Isolated;
                    fractionSelected = 0;
                });
            }
            GuiWidgets.button(gui, 40, Items.SHIELD, "ui.positions", "ui.activity.scope_hint", () -> gui.showActivity(market.type(), PageType.POSITIONS));
        }
        book(gui, displayedStatistics == null ? List.of() : OrderBookPage.levels(gui, market, displayedStatistics, true), 4, true);
        book(gui, displayedStatistics == null ? List.of() : OrderBookPage.levels(gui, market, displayedStatistics, false), 22, false);
        if (statistics.error() != null)
        {
            gui.setSlot(17, TradingGui.icon(gui.getPlayer(), Items.BARRIER, "ui.book.failed", ""));
        }
    }
}
