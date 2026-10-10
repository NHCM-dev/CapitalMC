package nhcm.capitalmc.gui.subgui.account;

import net.minecraft.world.item.Items;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.exchange.*;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.service.GameServices;
import nhcm.capitalmc.service.CurrencyService;
import java.math.BigInteger;

import java.util.*;

public class AssetPage extends BaseSubGui
{
    private final PollingValue<CurrencyService.Supply> supply = new PollingValue<>(null);
    private final PollingValue<Optional<BalanceView>> balance = new PollingValue<>(Optional.empty());

    public AssetPage(TradingGui gui)
    {
        super(gui, PageType.ASSET);
    }

    @Override
    public void render(TradingGui gui)
    {
        if (!GuiActions.ready(gui))
        {
            return;
        }
        long asset = gui.getSelectedAsset();
        if (asset <= 0)
        {
            gui.setSlot(22, TradingGui.icon(Items.PAPER));
            return;
        }
        var registered = GameServices.get().assets.find(asset);
        String lore = registered.map(a -> GuiWidgets.text(gui, a.electronic() ? "ui.asset.electronic" : "ui.asset.physical") + "\n" + GuiWidgets.text(gui, "ui.asset.precision", a.scale())).orElse("ui.asset.unregistered");
        if (registered.isPresent())
        {
            var info = registered.get();
            lore += "\n" + GuiWidgets.unitsDescription(gui, info);
            if (info.special())
            {
                lore += "\n" + GuiWidgets.text(gui, "ui.asset.special");
            }
            var data = supply.get("supply", GameServices.get().currency::supply);
            if (data == null || supply.error() != null)
            {
                lore += "\n" + GuiWidgets.text(gui, supply.error() == null ? "ui.loading" : "ui.query_failed");
            }
            else
            {
                var total = data.audit().assets().stream().filter(value -> value.assetId() == asset).findFirst().orElse(null);
                BigInteger actual = total == null ? BigInteger.ZERO : new BigInteger(total.actual());
                lore += "\n" + GuiWidgets.text(gui, "ui.asset.circulation", info.format(data.circulating(asset)))
                        + "\n" + GuiWidgets.text(gui, "ui.asset.supply", total == null ? "0" : info.format(new BigInteger(total.externalFlow())), info.format(actual));
            }
        }
        if (gui.getUser().isPresent())
        {
            var user = gui.getUser().orElseThrow();
            var type = gui.accountPage().getAccountType();
            var current = balance.get(List.of(user.getUuid(), CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID()), type, asset), () -> GameServices.get().settlements.account(user, type).thenCompose(id -> BalanceUtil.balance(id, asset)));
            if (current.isPresent())
            {
                var v = current.get();
                lore += "\n" + GuiWidgets.text(gui, "ui.account.balances", GuiWidgets.amount(asset, v.active()), GuiWidgets.amount(asset, v.reserved()), GuiWidgets.amount(asset, Math.addExact(v.active(), v.reserved())));
            }
        }
        gui.setSlot(13, GuiWidgets.asset(gui, asset, lore, null));
        GuiWidgets.button(gui, 20, Items.NAME_TAG, "gui.capitalmc.account.deposit_withdraw", "", () -> gui.showPage(PageType.DEPOSIT_WITHDRAW));
        GuiWidgets.button(gui, 22, Items.RAIL, "ui.transfer", "", () -> gui.showPage(PageType.TRANSFER));
        if (registered.isEmpty() || !registered.get().special())
        {
            GuiWidgets.button(gui, 24, Items.EMERALD, "ui.peg", "", () -> gui.showPage(PageType.PEG));
        }
    }
}
