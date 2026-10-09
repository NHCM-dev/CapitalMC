package nhcm.capitalmc.gui.subgui.account;

import eu.pb4.sgui.api.elements.GuiElement;
import lombok.Getter;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import nhcm.advmatch.model.AccountType;
import nhcm.advmatch.model.BalanceView;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.exchange.BalanceUtil;
import nhcm.capitalmc.gui.*;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class AccountPage extends BaseSubGui {
    @Getter private AccountType accountType = AccountType.Spot;
    private List<BalanceView> balances = List.of();

    private boolean loading;
    private boolean ready;
    private boolean failed;
    private long revision;

    private final GuiElement VIEW_EDIT_INFO;
    private final GuiElement DEPOSIT_WITHDRAW_BUTTON;
    private final GuiElement SPOT_BUTTON;
    private final GuiElement CONTRACT_BUTTON;


    public AccountPage(TradingGui gui) {
        super(gui, PageType.MY_ACCOUNT);
        VIEW_EDIT_INFO = TradingGui.button(
                Items.ENDER_EYE,
                "gui.capitalmc.account.view_edit_info",
                "gui.capitalmc.account.view_edit_info.description",
                () -> {}
        );
        DEPOSIT_WITHDRAW_BUTTON = TradingGui.button(
                Items.NAME_TAG,
                "gui.capitalmc.account.deposit_withdraw",
                "gui.capitalmc.account.deposit_withdraw.description",
                () -> {}
        );
        SPOT_BUTTON = TradingGui.button(
                Blocks.CHEST.asItem(),
                "gui.capitalmc.account.spot",
                "",
                () -> this.setAccountType(AccountType.Spot)
        );
        CONTRACT_BUTTON = TradingGui.button(
                Blocks.ENDER_CHEST.asItem(),
                "gui.capitalmc.account.contract",
                "",
                () -> this.setAccountType(AccountType.Contract)
        );

        setCenterItem(owner -> TradingGui.button(
                Items.PLAYER_HEAD,
                "gui.capitalmc.account",
                "gui.capitalmc.account.description",
                () -> {}
        ));
    }

    public void setAccountType(AccountType type) {
        accountType = Objects.requireNonNull(type, "type");
        resetUserSettings();
        if (getGui().isOpen()) getGui().refresh();
    }

    private void resetUserSettings() {
        revision++;
        balances = List.of();
        loading = false;
        ready = false;
        failed = false;
    }

    private void updateBalanceInfo() {
        var user = getGui().getUser();
        if (user.isEmpty() || loading || ready || failed) return;
        loading = true;
        long request = ++revision;
        UUID id = user.get().getUuid();
        var future = accountType == AccountType.Spot ? BalanceUtil.allSpotBalances(user.get())
                : BalanceUtil.allContractBalances(user.get());
        future.whenComplete((values, error) -> getGui().getPlayer().level().getServer().execute(() -> {
            if (request != revision || getGui().getUser().filter(value -> value.getUuid().equals(id)).isEmpty()) return;
            loading = false;
            failed = error != null;
            ready = error == null;
            balances = error == null ? values : List.of();
            if (error != null) CapitalMC.logger.error("Cannot query balances for account {}", id, error);
            if (getGui().isOpen() && getGui().getCurrentPage() == PageType.MY_ACCOUNT) getGui().refresh();
        }));
    }

    @Override
    public void render(TradingGui gui) {

        gui.setSlot(0, VIEW_EDIT_INFO);

        updateBalanceInfo();
        gui.setSlot(3, TradingGui.icon(Items.DIAMOND, "gui.capitalmc.account.total_balance_count", ""/*, balances.size()*/));
        gui.setSlot(4, DEPOSIT_WITHDRAW_BUTTON);

        boolean isSpot = this.accountType == AccountType.Spot;
        gui.setSlot(7, isSpot ? TradingGui.glowingIcon(SPOT_BUTTON) : SPOT_BUTTON);
        gui.setSlot(8, !isSpot ? TradingGui.glowingIcon(CONTRACT_BUTTON) : CONTRACT_BUTTON);

        for(int i = 9; i < 18; i++)
        {
            gui.setSlot(i, TradingGui.GRAY_GLASS_PANE);
        }

        //todo: list assets impl required
    }
}
