package nhcm.capitalmc.gui.subgui.account;

import net.minecraft.world.item.Items;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.service.*;

public class PegPage extends BaseSubGui
{
    private int page, count;

    public PegPage(TradingGui gui)
    {
        super(gui, PageType.PEG);
    }

    private void redeem(long item, boolean buy)
    {
        var gui = getGui();
        var user = GuiActions.user(gui);
        var asset = GameServices.get().assets.find(item).orElseThrow();
        var currency = GameServices.get().currency;
        var quote = GameServices.get().assets.find(currency.quoteAsset(item)).orElseThrow();
        var limits = currency.limits(item);
        long epoch = CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID());
        String prompt = GuiWidgets.text(gui, buy ? "ui.peg.input_buy" : "ui.peg.input_sell", asset.displayName(), quote.displayName(), asset.format(limits.minimum()), asset.format(limits.maximum()));
        GuiActions.input(gui, prompt, text -> {
            long raw = asset.parse(text);
            currency.quote(item, raw, buy);
            return raw;
        }, value -> {
        }).thenAccept(amount -> {
            if (amount.isEmpty())
            {
                return;
            }
            var preview = currency.quote(item, amount.get(), buy);
            currency.check(user, preview).whenComplete((unused, error) -> GameServices.get().onServer(() -> {
                if (gui.getPlayer().hasDisconnected() || gui.getCurrentPage() != PageType.PEG
                    || epoch != CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID())
                    || gui.getUser().map(value -> !value.getUuid().equals(user.getUuid())).orElse(true))
                {
                    return null;
                }
                if (error != null)
                {
                    GuiActions.error(gui, error);
                    return null;
                }
                var paidAsset = GameServices.get().assets.find(buy ? preview.quoteAsset() : preview.baseAsset()).orElseThrow();
                var receivedAsset = GameServices.get().assets.find(buy ? preview.baseAsset() : preview.quoteAsset()).orElseThrow();
                var feeAsset = GameServices.get().assets.find(preview.quoteAsset()).orElseThrow();
                GuiActions.message(gui, "ui.peg.preview", paidAsset.format(buy ? preview.quoteAmount() : preview.baseAmount()), paidAsset.displayName(),
                                   receivedAsset.format(buy ? preview.baseAmount() : preview.quoteAmount()), receivedAsset.displayName(), feeAsset.format(preview.fee()), feeAsset.displayName());
                GuiActions.confirm(gui, "ui.confirm.redeem", () -> GuiActions.task(gui, currency.exchange(user, preview), "ui.success.peg", paidAsset.format(buy ? preview.quoteAmount() : preview.baseAmount()), paidAsset.displayName(), receivedAsset.format(buy ? preview.baseAmount() : preview.quoteAmount()), receivedAsset.displayName()));
                return null;
            }));
        }).exceptionally(error -> {
            GuiActions.error(gui, error);
            return null;
        });
    }

    private boolean buy = true;

    @Override
    public void render(TradingGui gui)
    {
        if (!GuiActions.ready(gui))
        {
            return;
        }
        GuiWidgets.option(gui, 0, Items.EMERALD, "ui.peg.buy", "ui.peg.buy_hint", buy, () -> buy = true);
        GuiWidgets.option(gui, 1, Items.GOLD_INGOT, "ui.peg.sell", "ui.peg.sell_hint", !buy, () -> buy = false);
        GuiWidgets.separator(gui);
        var assets = GameServices.get().currency.peggedAssets();
        count = assets.size();
        page = GuiWidgets.clamp(page, count);
        for (int i = page * 27; i < Math.min(count, page * 27 + 27); i++)
        {
            long id = assets.get(i);
            var currency = GameServices.get().currency;
            var base = GameServices.get().assets.find(id).orElseThrow();
            var quote = GameServices.get().assets.find(currency.quoteAsset(id)).orElseThrow();
            String direction = GuiWidgets.text(gui, "ui.peg.direction", buy ? quote.displayName() : base.displayName(), buy ? base.displayName() : quote.displayName());
            gui.setSlot(18 + i - page * 27, GuiWidgets.asset(gui, id, currency.describePeg(id) + "\n" + direction, () -> redeem(id, buy)));
        }
        if (count == 0)
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PAPER, "ui.peg.none", ""));
        }
    }

    @Override
    public void renderOverlay(TradingGui gui)
    {
        GuiWidgets.paging(gui, page, count, value -> page = value);
    }
}
