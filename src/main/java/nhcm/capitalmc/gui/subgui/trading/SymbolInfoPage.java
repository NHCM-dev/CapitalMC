package nhcm.capitalmc.gui.subgui.trading;

import net.minecraft.world.item.Items;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.exchange.*;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.service.DecimalUnits;
import nhcm.capitalmc.service.GameServices;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class SymbolInfoPage extends BaseSubGui
{
    private record Info(SymbolView symbol, AssetView base, AssetView quote, MarketView contract)
    {
    }

    private final PollingValue<Info> info = new PollingValue<>(null);

    public SymbolInfoPage(TradingGui gui)
    {
        super(gui, PageType.SYMBOL_INFO);
    }

    @Override
    public void render(TradingGui gui)
    {
        if (!GuiActions.ready(gui))
        {
            return;
        }
        var selected = gui.getSelectedSymbol();
        if (selected == null)
        {
            GuiWidgets.button(gui, 22, Items.PAPER, "ui.trade.select_symbol", "", () -> gui.showPage(PageType.LIST_SYMBOLS));
            return;
        }
        var data = info.get(selected.symbolId(), () -> CapitalMC.exchange.symbols().get(selected.symbolId()).snapshot().thenCompose(s -> AssetUtil.find(s.baseAssetId()).thenCombine(AssetUtil.find(s.quoteAssetId()), (b, q) -> new Info(s, b.orElseThrow(), q.orElseThrow(), null))
                                                                                                                                                  .thenCompose(i -> s.type() == SymbolType.Spot ? CompletableFuture.completedFuture(i) : CapitalMC.exchange.contracts().market(CapitalMC.exchange.symbols().get(s.symbolId())).thenApply(m -> new Info(s, i.base, i.quote, m)))));
        if (data == null)
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.CLOCK, info.error() == null ? "ui.loading" : "ui.query_failed", ""));
            return;
        }
        var rules = data.symbol.tradingRules();
        var baseUnits = AssetUtil.units(data.base.assetId(), data.base.scale());
        var quoteUnits = AssetUtil.units(data.quote.assetId(), data.quote.scale());
        var quantityUnits = GameServices.get().markets.quantityUnits(data.symbol);
        gui.setSlot(20, GuiWidgets.asset(gui, data.base.assetId(), GuiWidgets.text(gui, "ui.trade.base"), () -> gui.showAsset(data.base.assetId())));
        gui.setSlot(24, GuiWidgets.asset(gui, data.quote.assetId(), GuiWidgets.text(gui, "ui.trade.quote"), () -> gui.showAsset(data.quote.assetId())));
        String lore = GuiWidgets.text(gui, "ui.trade.symbol_info", data.symbol.type(), baseUnits.price(rules.priceTick(), quoteUnits), quantityUnits.format(rules.quantityStep()), quantityUnits.format(rules.minQuantity()));
        lore += "\n" + GuiWidgets.text(gui, "ui.symbol.minimum_notional", quoteUnits.format(rules.minOrderNotional()));
        lore += "\n" + GuiWidgets.text(gui, "ui.symbol.self_trade", GuiWidgets.text(gui, "ui.enum.SelfTradePrevention." + rules.selfTradePrevention()));
        long maker = data.contract == null ? rules.fees().makerRate() : data.contract.spec().makerFeeRate();
        long taker = data.contract == null ? rules.fees().takerRate() : data.contract.spec().takerFeeRate();
        lore += "\n" + GuiWidgets.text(gui, "ui.symbol.fees", DecimalUnits.format(maker, 4), DecimalUnits.format(taker, 4));
        if (data.contract != null)
        {
            lore += "\n" + GuiWidgets.text(gui, "ui.symbol.contract", baseUnits.price(data.contract.markPrice(), quoteUnits), data.contract.spec().maxLeverage(), data.contract.status());
            if (data.contract.referencePrices().indexPrice() <= 0)
            {
                lore += "\n" + GuiWidgets.text(gui, "ui.symbol.no_index");
            }
        }
        long size = GameServices.get().markets.lotSize(data.symbol.symbolId());
        if (size > 0)
        {
            long multiplier = data.contract == null ? 1 : data.contract.spec().contractMultiplier();
            lore += "\n" + GuiWidgets.text(gui, "ui.lot.equivalent", GuiWidgets.text(gui, data.contract == null ? "ui.lot.spot" : "ui.lot.contract"),
                                           baseUnits.format(BigInteger.valueOf(size).multiply(BigInteger.valueOf(multiplier))), data.base.displayName());
        }
        gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.OAK_SIGN, data.symbol.name(), lore));
    }
}
