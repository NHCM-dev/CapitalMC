package nhcm.capitalmc.gui.subgui.account;

import net.minecraft.world.item.Items;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.exchange.BalanceUtil;
import nhcm.capitalmc.exchange.ExchangeMessages;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.service.*;

import java.util.*;

public class TransferPage extends BaseSubGui
{
    private boolean choosing = true;
    private String search = "";
    private int page, count;
    private final PollingValue<List<BalanceView>> balances = new PollingValue<>(null);

    public TransferPage(TradingGui gui)
    {
        super(gui, PageType.TRANSFER);
        setCenterItem(Items.RAIL);
    }

    public void resetSelection()
    {
        choosing = true;
        search = "";
        page = 0;
        count = 0;
        balances.invalidate();
    }

    private void transfer(boolean player)
    {
        var gui = getGui();
        var user = GuiActions.user(gui);
        long assetId = gui.getSelectedAsset();
        var asset = GameServices.get().assets.find(assetId).orElseThrow(() -> new IllegalArgumentException("ui.withdraw.select_asset"));
        var source = gui.accountPage().getAccountType();
        if (player && source != AccountType.Spot)
        {
            throw new IllegalArgumentException("ui.transfer.player_spot");
        }
        var availableBalances = balances.get(List.of(user.getUuid(), source, CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID())),
                                            () -> source == AccountType.Spot ? BalanceUtil.allSpotBalances(user) : BalanceUtil.allContractBalances(user));
        if (availableBalances == null || balances.error() != null)
        {
            GuiActions.message(gui, balances.error() == null ? "ui.loading" : "ui.query_failed");
            return;
        }
        long available = availableBalances.stream().filter(value -> value.assetId() == assetId).mapToLong(BalanceView::active).findFirst().orElse(0);
        GuiActions.input(gui, GuiWidgets.text(gui, "ui.input.transfer_amount", asset.displayName(), asset.format(available)), text -> {
            long raw = asset.parse(text);
            if (raw <= 0 || raw > available)
            {
                throw new IllegalArgumentException("ui.transfer.amount_range");
            }
            return raw;
        }, v -> {
        }).thenAccept(amount -> {
            if (amount.isEmpty())
            {
                return;
            }
            if (player)
            {
                GuiActions.input(gui, "ui.transfer.username", text -> CapitalMC.serverUserManager.findByUsername(text.strip()).orElseThrow(() -> new IllegalArgumentException("ui.access.unknown_user")), v -> {
                          })
                          .thenAccept(target -> {
                              if (target.isEmpty())
                              {
                                  return;
                              }
                              GuiActions.message(gui, "ui.transfer.summary", asset.displayName(), asset.format(amount.get()), target.get().getUsername());
                              GuiActions.confirm(gui, "ui.confirm.transfer", () -> GuiActions.task(gui, GameServices.get().settlements.playerTransfer(user, target.get(), assetId, amount.get()), "ui.success.transfer", asset.format(amount.get()), asset.displayName(), target.get().getUsername()));
                          }).exceptionally(error -> {
                              GuiActions.error(gui, error);
                              return null;
                          });
            }
            else
            {
                GuiActions.message(gui, "ui.transfer.summary", asset.displayName(), asset.format(amount.get()), ExchangeMessages.label(gui.getPlayer(), source) + " → " + ExchangeMessages.label(gui.getPlayer(), source == AccountType.Spot ? AccountType.Contract : AccountType.Spot));
                GuiActions.confirm(gui, "ui.confirm.transfer", () -> GuiActions.task(gui, GameServices.get().settlements.between(user, source, assetId, amount.get()), "ui.success.move", asset.format(amount.get()), asset.displayName(), source, source == AccountType.Spot ? AccountType.Contract : AccountType.Spot));
            }
        }).exceptionally(error -> {
            GuiActions.error(gui, error);
            return null;
        });
    }

    @Override
    public void render(TradingGui gui)
    {
        if (!GuiActions.ready(gui))
        {
            return;
        }
        GuiWidgets.option(gui, 7, Items.GOLDEN_BOOTS, "gui.capitalmc.account.spot", "", gui.accountPage().getAccountType() == AccountType.Spot,
                          () -> {
                              gui.accountPage().setAccountType(AccountType.Spot);
                              choosing = true;
                              gui.selectAsset(0);
                          });
        GuiWidgets.option(gui, 8, Items.CHAINMAIL_BOOTS, "gui.capitalmc.account.contract", "", gui.accountPage().getAccountType() == AccountType.Contract,
                          () -> {
                              gui.accountPage().setAccountType(AccountType.Contract);
                              choosing = true;
                              gui.selectAsset(0);
                          });
        if (choosing || gui.getSelectedAsset() <= 0)
        {
            renderAssets(gui);
            return;
        }
        count = 0;
        GuiWidgets.button(gui, 4, Items.DIAMOND, "ui.withdraw.select_asset", "", () -> choosing = true);
        gui.setSlot(13, GuiWidgets.asset(gui, gui.getSelectedAsset(), "ui.transfer.change_asset", () -> choosing = true));
        GuiWidgets.button(gui, 20, Items.CHEST, "ui.transfer.accounts", ExchangeMessages.label(gui.getPlayer(), gui.accountPage().getAccountType()), () -> transfer(false));
        GuiWidgets.button(gui, 24, Items.ENDER_CHEST, "ui.transfer.player", "ui.transfer.player_spot", () -> transfer(true));
    }

    private void renderAssets(TradingGui gui)
    {
        GuiWidgets.button(gui, 0, Items.COMPASS, "ui.search", "&7" + search, () -> GuiActions.input(gui, "ui.search.input", String::strip, value -> {
            search = value.toLowerCase(Locale.ROOT);
            page = 0;
        }));
        GuiWidgets.button(gui, 1, Items.SPONGE, "ui.search.clear", "", () -> {
            search = "";
            page = 0;
        });
        GuiWidgets.separator(gui);
        var user = gui.getUser().orElse(null);
        if (user == null)
        {
            count = 0;
            GuiWidgets.button(gui, 22, Items.PLAYER_HEAD, "gui.capitalmc.account.login_required", "", () -> gui.showPage(PageType.ACCESS));
            return;
        }
        var source = gui.accountPage().getAccountType();
        var amounts = balances.get(List.of(user.getUuid(), source, CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID())),
                                   () -> source == AccountType.Spot ? BalanceUtil.allSpotBalances(user) : BalanceUtil.allContractBalances(user));
        if (amounts == null || balances.error() != null)
        {
            count = 0;
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.CLOCK, balances.error() == null ? "ui.loading" : "ui.query_failed", ""));
            return;
        }
        var registry = GameServices.get().assets;
        var assets = registry.all().stream().filter(asset -> registry.enabled(asset.id()))
                             .filter(asset -> amounts.stream().anyMatch(value -> value.assetId() == asset.id() && value.active() > 0))
                             .filter(asset -> (asset.name() + " " + asset.displayName() + " " + asset.id()).toLowerCase(Locale.ROOT).contains(search)).toList();
        count = assets.size();
        page = GuiWidgets.clamp(page, count);
        for (int index = page * 27; index < Math.min(count, page * 27 + 27); index++)
        {
            var asset = assets.get(index);
            var balance = amounts == null ? null : amounts.stream().filter(value -> value.assetId() == asset.id()).findFirst().orElse(null);
            String available = balances.error() != null ? GuiWidgets.text(gui, "ui.query_failed") : amounts == null
                    ? GuiWidgets.text(gui, "ui.loading") : balance == null ? "0" : asset.format(balance.active());
            String lore = GuiWidgets.text(gui, "ui.transfer.asset_balance", source, available);
            gui.setSlot(18 + index - page * 27, GuiWidgets.asset(gui, asset.id(), lore, () -> {
                gui.selectAsset(asset.id());
                choosing = false;
                gui.refresh();
            }));
        }
        if (count == 0)
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PAPER, "ui.empty", ""));
        }
    }

    @Override
    public void renderOverlay(TradingGui gui)
    {
        if (!choosing && gui.getSelectedAsset() > 0)
        {
            GuiWidgets.button(gui, 45, Items.ARROW, "gui.capitalmc.back", "", () -> {
                choosing = true;
                gui.selectAsset(0);
            });
        }
        if (count > 0 && (choosing || gui.getSelectedAsset() <= 0))
        {
            GuiWidgets.paging(gui, page, count, value -> page = value);
        }
    }
}
