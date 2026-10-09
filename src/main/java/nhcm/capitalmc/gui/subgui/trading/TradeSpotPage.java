package nhcm.capitalmc.gui.subgui.trading;

import eu.pb4.sgui.api.elements.GuiElement;
import lombok.Getter;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import nhcm.advmatch.command.read.ReadAccounts;
import nhcm.advmatch.command.read.ReadBalance;
import nhcm.advmatch.model.*;
import nhcm.advmatch.object.Symbol;
import nhcm.capitalmc.ExchangeAccess;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.gui.BaseSubGui;
import nhcm.capitalmc.gui.PageType;
import nhcm.capitalmc.gui.TradingGui;

import java.util.Optional;

public class TradeSpotPage extends BaseSubGui implements ExchangeAccess
{
    @Getter
    private Symbol symbol;

    private boolean nameUpdateRequired = true;
    private String symbolName;
    private boolean assetIdReady = false;
    private long baseAssetId;
    private long quoteAssetId;

    private OrderSide orderSide;
    private OrderType orderType;
    private TimeInForce timeInForce;
    private long price;
    private long quantity;

    private boolean quantityChosen;
    private int chosenQuantitySlot = 0;

    private final GuiElement BID_MODE;
    private final GuiElement ASK_MODE;
    private final GuiElement MKT_TYPE;
    private final GuiElement LMT_TYPE;
    private final GuiElement USER_PRICE;
    private final GuiElement QUARTER_QUANTITY;
    private final GuiElement HALF_QUANTITY;
    private final GuiElement FULL_QUANTITY;
    private final GuiElement CONFIRM_ORDER;

    public TradeSpotPage(TradingGui gui)
    {
        super(gui, PageType.TRADE_SPOT);
        resetUserSetting();

        BID_MODE = TradingGui.button(
                Blocks.WOOL.lime().asItem(),
                "gui.capitalmc.trade.spot.bid",
                "gui.capitalmc.trade.spot.bid.description",
                () -> this.orderSide = OrderSide.Bid
        );
        ASK_MODE = TradingGui.button(
                Blocks.WOOL.red().asItem(),
                "gui.capitalmc.trade.spot.ask",
                "gui.capitalmc.trade.spot.ask.description",
                () -> this.orderSide = OrderSide.Ask
        );
        LMT_TYPE = TradingGui.button(
                Items.DIAMOND_PICKAXE,
                "gui.capitalmc.trade.spot.lmt",
                "gui.capitalmc.trade.spot.lmt.description",
                () -> this.orderType = OrderType.Limit
        );
        MKT_TYPE = TradingGui.button(
                Items.DIAMOND_AXE,
                "gui.capitalmc.trade.spot.mkt",
                "gui.capitalmc.trade.spot.mkt.description",
                () -> this.orderType = OrderType.Market
        );
        USER_PRICE = TradingGui.icon(
                Items.IRON_INGOT,
                "gui.capitalmc.trade.spot.user_price",
                "gui.capitalmc.trade.spot.user_price.description",
                price
        );
        QUARTER_QUANTITY = TradingGui.button(
                Items.WOOL.white(),
                "gui.capitalmc.trade.spot.quarter_quantity",
                "gui.capitalmc.trade.spot.quarter_quantity.description",
                () -> this.setQuantity(4, 1)
        );
        HALF_QUANTITY = TradingGui.button(
                Items.WOOL.lightGray(),
                "gui.capitalmc.trade.spot.half_quantity",
                "gui.capitalmc.trade.spot.half_quantity.description",
                () -> this.setQuantity(2, 2)
        );
        FULL_QUANTITY = TradingGui.button(
                Items.WOOL.gray(),
                "gui.capitalmc.trade.spot.full_quantity",
                "gui.capitalmc.trade.spot.full_quantity.description",
                () -> this.setQuantity(1, 3)
        );
        CONFIRM_ORDER = TradingGui.button(
                Items.NETHER_STAR,
                "gui.capitalmc.trade.spot.confirm_order",
                "gui.capitalmc.trade.spot.confirm_order.description",
                this::placeOrder
        );

        this.setCenterItem(o -> TradingGui.icon(o.getPlayer(),
                                                Items.GOLD_INGOT,
                                                "gui.capitalmc.trade.spot", this.symbolName == null ? "" : this.symbolName));
    }

    private void placeOrder()
    {
        //todo: place order impl required
    }

    private long quantityRequest;

    private void setQuantity(long divisor, int chosenSlot) {
        if (!assetIdReady) return;
        var user = getGui().getUser();
        if (user.isEmpty()) return;
        var accountId = user.get().getUuid();
        var selectedSymbol = symbol;
        var selectedSide = orderSide;
        long request = ++quantityRequest;
        long assetId = selectedSide == OrderSide.Bid ? quoteAssetId : baseAssetId;
        nhcm.capitalmc.exchange.BalanceUtil.spotAvailable(user.get(), assetId)
                .whenComplete((available, error) -> getGui().getPlayer().level().getServer().execute(() -> {
                    if (request != quantityRequest || symbol != selectedSymbol || orderSide != selectedSide
                            || getGui().getUser().filter(value -> value.getUuid().equals(accountId)).isEmpty()) return;
                    if (error != null) {
                        nhcm.capitalmc.CapitalMC.logger.error("Cannot query available balance", error);
                        return;
                    }
                    quantity = available / divisor;
                    quantityChosen = true;
                    chosenQuantitySlot = chosenSlot;
                    if (getGui().isOpen() && getGui().getCurrentPage() == PageType.TRADE_SPOT) getGui().refresh();
                }));
    }

    public void setSymbol(Symbol symbol)
    {
        nameUpdateRequired = true;
        this.symbol = symbol;
        assetIdReady = false;
        symbol.snapshot().thenAccept(
                symbolView -> {
                    this.baseAssetId = symbolView.baseAssetId();
                    this.quoteAssetId = symbolView.quoteAssetId();
                    assetIdReady = true;
                }
        );
        resetUserSetting();
    }

    private void resetUserSetting()
    {
        orderSide = OrderSide.Bid;
        orderType = OrderType.Limit;
        timeInForce = TimeInForce.GTC;
        price = 0;
        quantity = 0;
        quantityChosen = false;
        chosenQuantitySlot = 0;
    }

    @Override
    public void render(TradingGui gui)
    {
        if(nameUpdateRequired && symbol != null)
        {
            this.nameUpdateRequired = false;
            symbol.snapshot().thenAccept(
                    symbolView -> this.symbolName = symbolView.name()
            );
        }
        this.setCenterItem(o -> TradingGui.icon(o.getPlayer(),
                Items.GOLD_INGOT,
                "gui.capitalmc.trade.spot", this.symbolName == null ? "" : this.symbolName));

        boolean isBuy = this.orderSide == OrderSide.Bid;
        gui.setSlot(1, isBuy ? TradingGui.glowingIcon(BID_MODE) : BID_MODE);
        gui.setSlot(2, !isBuy ? TradingGui.glowingIcon(ASK_MODE) : ASK_MODE);

        boolean isMarket = this.orderType == OrderType.Market;
        gui.setSlot(10, !isMarket ? TradingGui.glowingIcon(LMT_TYPE) : LMT_TYPE);
        gui.setSlot(11, isMarket ? TradingGui.glowingIcon(MKT_TYPE) : MKT_TYPE);

        gui.setSlot(19, USER_PRICE);

        gui.setSlot(28, chosenQuantitySlot == 1 ? TradingGui.glowingIcon(QUARTER_QUANTITY) : QUARTER_QUANTITY);
        gui.setSlot(29, chosenQuantitySlot == 2 ? TradingGui.glowingIcon(HALF_QUANTITY) : HALF_QUANTITY);
        gui.setSlot(30, chosenQuantitySlot == 3 ? TradingGui.glowingIcon(FULL_QUANTITY) : FULL_QUANTITY);

        gui.setSlot(37, CONFIRM_ORDER);

        //todo: specific order settings, orderbook, price set, symbol display, trade info impl required
    }


}

