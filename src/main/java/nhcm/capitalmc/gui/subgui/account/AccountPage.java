package nhcm.capitalmc.gui.subgui.account;

import net.minecraft.world.item.Items;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.exchange.*;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.service.GameServices;

import java.util.*;

public class AccountPage extends BaseSubGui
{
    private AccountType accountType = AccountType.Spot;

    public AccountType getAccountType()
    {
        return accountType;
    }

    private record Balances(List<BalanceView> values, List<AssetView> assets)
    {
    }

    private final PollingValue<Balances> balances = new PollingValue<>(new Balances(List.of(), List.of()));
    private int page, count;
    private String search = "";

    public AccountPage(TradingGui gui)
    {
        super(gui, PageType.MY_ACCOUNT);
        setCenterItem(owner -> owner.getUser().map(user -> GuiWidgets.accountHead(owner, user, null)).orElse(TradingGui.icon(Items.PLAYER_HEAD)));
    }

    public void setAccountType(AccountType type)
    {
        accountType = Objects.requireNonNull(type);
        page = 0;
        balances.invalidate();
    }

    @Override
    public void render(TradingGui gui)
    {
        if (!GuiActions.ready(gui))
        {
            return;
        }
        GuiWidgets.button(gui, 0, Items.COMPASS, "ui.search", search, () -> GuiActions.input(gui, "ui.search.input", String::strip, value -> {
            search = value.toLowerCase(Locale.ROOT);
            page = 0;
        }));
        GuiWidgets.button(gui, 1, Items.SPONGE, "ui.search.clear", "", () -> {
            search = "";
            page = 0;
        });
        GuiWidgets.button(gui, 2, Items.BOOK, "ui.activity.account", "ui.activity.account_hint", () -> gui.showActivity(
                accountType == AccountType.Spot ? SymbolType.Spot : SymbolType.Perpetual, PageType.ORDERS));
        GuiWidgets.button(gui, 4, Items.NAME_TAG, "gui.capitalmc.account.deposit_withdraw", "", () -> gui.showPage(PageType.DEPOSIT_WITHDRAW));
        GuiWidgets.button(gui, 5, Items.EMERALD, "ui.peg", "", () -> gui.showPage(PageType.PEG));
        GuiWidgets.button(gui, 6, Items.RAIL, "gui.capitalmc.account.transfer", "", () -> gui.showPage(PageType.TRANSFER));
        GuiWidgets.option(gui, 7, Items.GOLDEN_BOOTS, "gui.capitalmc.account.spot", "", accountType == AccountType.Spot, () -> setAccountType(AccountType.Spot));
        GuiWidgets.option(gui, 8, Items.CHAINMAIL_BOOTS, "gui.capitalmc.account.contract", "", accountType == AccountType.Contract, () -> setAccountType(AccountType.Contract));

        GuiWidgets.separator(gui);
        if (gui.getUser().isEmpty())
        {
            count = 0;
            GuiWidgets.button(gui, 22, Items.PLAYER_HEAD, "gui.capitalmc.account.login_required", "", () -> gui.showPage(PageType.ACCESS));
            return;
        }
        var user = gui.getUser().orElseThrow();
        var type = accountType;
        var data = balances.get(List.of(user.getUuid(), type, CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID())), () -> (type == AccountType.Spot ? BalanceUtil.allSpotBalances(user) : BalanceUtil.allContractBalances(user)).thenCombine(AssetUtil.allAssets(), Balances::new));
        var registered = GameServices.get().assets;
        var filtered = data.values.stream().filter(balance -> {
            var asset = data.assets.stream().filter(a -> a.assetId() == balance.assetId()).findFirst();
            return asset.map(a -> (a.displayName() + " " + a.assetName()).toLowerCase(Locale.ROOT).contains(search)).orElse(search.isEmpty());
        }).sorted(Comparator.comparingLong(BalanceView::assetId)).toList();
        count = filtered.size();
        page = GuiWidgets.clamp(page, count);
        gui.setSlot(3, TradingGui.icon(gui.getPlayer(), Items.DIAMOND, GuiWidgets.text(gui, "ui.account.count", count), ""));
        for (int i = page * 27; i < Math.min(count, page * 27 + 27); i++)
        {
            var balance = filtered.get(i);
            var asset = data.assets.stream().filter(a -> a.assetId() == balance.assetId()).findFirst();
            var units = registered.units(balance.assetId(), asset.map(AssetView::scale).orElse(0));
            String lore = GuiWidgets.text(gui, "ui.account.balances", units.format(balance.active()), units.format(balance.reserved()), units.format(Math.addExact(balance.active(), balance.reserved())));
            var element = GuiWidgets.asset(gui, balance.assetId(), lore, () -> gui.showAsset(balance.assetId()));
            if (registered.find(balance.assetId()).isEmpty() && asset.isPresent())
            {
                element = TradingGui.icon(gui.getPlayer(), element, asset.get().displayName(), lore);
            }
            gui.setSlot(18 + i - page * 27, element);
        }
        if (count == 0)
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PAPER, balances.error() != null ? "ui.query_failed" : balances.loaded() ? "ui.empty" : "ui.loading", ""));
        }
    }

    @Override
    public void renderOverlay(TradingGui gui)
    {
        GuiWidgets.paging(gui, page, count, value -> page = value);
    }
}
