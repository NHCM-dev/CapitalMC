package nhcm.capitalmc.gui.subgui.account;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.exchange.BalanceUtil;
import nhcm.advmatch.model.BalanceView;
import nhcm.advmatch.model.AccountType;
import nhcm.capitalmc.service.*;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

public class DepositWithdrawPage extends BaseSubGui
{
    private enum Mode
    {
        CHOOSE, DEPOSIT, WITHDRAW
    }

    private Mode mode = Mode.CHOOSE;
    private boolean choosingAsset = true;
    private int page, count;
    private String search = "";
    private final PollingValue<List<BalanceView>> balances = new PollingValue<>(null);

    public void resetSelection()
    {
        mode = Mode.CHOOSE;
        choosingAsset = true;
        page = 0;
        count = 0;
        search = "";
        balances.invalidate();
    }

    private List<BalanceView> availableBalances(TradingGui gui)
    {
        var user = GuiActions.user(gui);
        return balances.get(List.of(user.getUuid(), CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID())),
                            () -> BalanceUtil.allSpotBalances(user));
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
        var amounts = availableBalances(gui);
        if (amounts == null || balances.error() != null)
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.CLOCK, balances.error() == null ? "ui.loading" : "ui.query_failed", ""));
            return;
        }
        var registry = GameServices.get().assets;
        var assets = registry.all().stream().filter(asset -> !asset.electronic() && registry.enabled(asset.id()))
                             .filter(asset -> amounts.stream().anyMatch(value -> value.assetId() == asset.id() && value.active() > 0 && value.active() >= asset.minimumWithdrawal()))
                             .filter(asset -> (asset.name() + " " + asset.displayName() + " " + asset.id()).toLowerCase(Locale.ROOT).contains(search)).toList();
        count = assets.size();
        page = GuiWidgets.clamp(page, count);
        for (int i = page * 27; i < Math.min(count, page * 27 + 27); i++)
        {
            var asset = assets.get(i);
            long available = amounts.stream().filter(value -> value.assetId() == asset.id()).mapToLong(BalanceView::active).findFirst().orElse(0);
            gui.setSlot(18 + i - page * 27, GuiWidgets.asset(gui, asset.id(),
                        GuiWidgets.text(gui, "ui.withdraw.asset_balance", asset.format(available), asset.format(asset.minimumWithdrawal())), () -> {
                gui.selectAsset(asset.id());
                choosingAsset = false;
            }));
        }
        if (count == 0)
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PAPER, "ui.withdraw.no_assets", ""));
        }
    }

    @Override
    public void renderOverlay(TradingGui gui)
    {
        if (mode == Mode.CHOOSE)
        {
            return;
        }
        if (mode == Mode.WITHDRAW && choosingAsset && count > 0)
        {
            GuiWidgets.paging(gui, page, count, value -> page = value);
        }
        GuiWidgets.button(gui, 45, Items.ARROW, "gui.capitalmc.back", "", () -> {
            if (mode == Mode.WITHDRAW && !choosingAsset)
            {
                choosingAsset = true;
                gui.selectAsset(0);
            }
            else
            {
                resetSelection();
                gui.selectAsset(0);
            }
        });
    }

    public DepositWithdrawPage(TradingGui gui)
    {
        super(gui, PageType.DEPOSIT_WITHDRAW);
        setCenterItem(Items.NAME_TAG);
    }

    private void confirmSpecial(ItemBankService.DepositSelection selection)
    {
        var gui = getGui();
        var user = GuiActions.user(gui);
        var items = GameServices.get().items;
        GuiActions.input(gui, "ui.confirm.deposit", text -> {
            if (!text.equalsIgnoreCase("yes") && !text.equals("确认"))
            {
                throw new IllegalArgumentException("ui.confirm.yes");
            }
            return true;
        }).thenAccept(answer -> {
            if (answer.isEmpty())
            {
                items.cancelSelection(gui.getPlayer().getUUID());
                return;
            }
            GuiActions.task(gui, items.deposit(gui.getPlayer(), user, selection), "ui.success.deposit");
        }).exceptionally(error -> {
            items.cancelSelection(gui.getPlayer().getUUID());
            GuiActions.error(gui, error);
            return null;
        });
    }

    private void depositCredits(ItemBankService.ContainerKind kind)
    {
        var gui = getGui();
        var user = GuiActions.user(gui);
        String name = GuiWidgets.text(gui, kind == ItemBankService.ContainerKind.CHEST ? "ui.deposit.chest_credits" : "ui.deposit.shulker_credits");
        GuiActions.input(gui, GuiWidgets.text(gui, "ui.deposit.credits_input", name, gui.getPlayer().getMainHandItem().getCount()), Integer::parseInt)
                  .thenAccept(amount -> {
                      if (amount.isPresent())
                      {
                          var selection = GameServices.get().items.prepareContainerCredits(gui.getPlayer(), user, kind, amount.get());
                          GuiActions.message(gui, "ui.deposit.credits_summary", name, amount.get());
                          confirmSpecial(selection);
                      }
                  }).exceptionally(error -> {
                      GuiActions.error(gui, error);
                      return null;
                  });
    }

    private void depositExperience()
    {
        var gui = getGui();
        var user = GuiActions.user(gui);
        var asset = GameServices.get().assets.find("mc_exp").orElseThrow();
        GuiActions.input(gui, GuiWidgets.text(gui, "ui.deposit.experience_input", asset.minimumDeposit(), ItemBankService.experienceAvailable(gui.getPlayer())),
                         Integer::parseInt)
                  .thenAccept(amount -> {
                      if (amount.isPresent())
                      {
                          var selection = GameServices.get().items.prepareExperience(gui.getPlayer(), user, amount.get());
                          GuiActions.message(gui, "ui.deposit.experience_summary", amount.get());
                          confirmSpecial(selection);
                      }
                  }).exceptionally(error -> {
                      GuiActions.error(gui, error);
                      return null;
                  });
    }

    public void deposit(boolean container)
    {
        var gui = getGui();
        var user = GuiActions.user(gui);
        var items = GameServices.get().items;
        items.beginBatchSelection(gui.getPlayer(), user, container, Duration.ofSeconds(90));
        try
        {
            GuiActions.message(gui, container ? "ui.deposit.batch_containers" : "ui.deposit.batch_drops");
            GuiActions.input(gui, "ui.deposit.batch_done", text -> {
                if (!text.equalsIgnoreCase("done") && !text.equals("完成"))
                {
                    throw new IllegalArgumentException("ui.deposit.batch_done");
                }
                return items.finishBatchSelection(gui.getPlayer());
            }, value -> {
            }).thenAccept(result -> {
                if (result.isEmpty())
                {
                    items.cancelSelection(gui.getPlayer().getUUID());
                    return;
                }
                var choice = result.get();
                for (var entry : choice.amounts().entrySet())
                {
                    var asset = GameServices.get().assets.find(entry.getKey()).orElseThrow();
                    GuiActions.message(gui, "ui.deposit.summary_item", asset.displayName(), asset.format(entry.getValue()));
                }
                GuiActions.message(gui, "ui.deposit.summary_containers", choice.chests(), choice.shulkers());
                GuiActions.input(gui, "ui.confirm.deposit", text -> {
                    if (!text.equalsIgnoreCase("yes") && !text.equals("确认"))
                    {
                        throw new IllegalArgumentException("ui.confirm.yes");
                    }
                    return true;
                }, value -> {
                }).thenAccept(confirm -> {
                    if (confirm.isEmpty())
                    {
                        items.cancelSelection(gui.getPlayer().getUUID());
                        return;
                    }
                    GuiActions.task(gui, items.deposit(gui.getPlayer(), user, choice), "ui.success.deposit");
                }).exceptionally(error -> {
                    items.cancelSelection(gui.getPlayer().getUUID());
                    GuiActions.error(gui, error);
                    return null;
                });
            }).exceptionally(error -> {
                items.cancelSelection(gui.getPlayer().getUUID());
                GuiActions.error(gui, error);
                return null;
            });
        } catch (RuntimeException error)
        {
            items.cancelSelection(gui.getPlayer().getUUID());
            throw error;
        }
    }

    private void withdraw(ItemBankService.Output output)
    {
        var gui = getGui();
        var user = GuiActions.user(gui);
        long assetId = gui.getSelectedAsset();
        var asset = GameServices.get().assets.find(assetId).orElseThrow(() -> new IllegalArgumentException("ui.withdraw.select_asset"));
        if (asset.electronic())
        {
            throw new IllegalArgumentException("ui.withdraw.electronic");
        }
        var amounts = availableBalances(gui);
        if (amounts == null || balances.error() != null)
        {
            GuiActions.message(gui, balances.error() == null ? "ui.loading" : "ui.query_failed");
            return;
        }
        long available = amounts.stream().filter(value -> value.assetId() == assetId).mapToLong(BalanceView::active).findFirst().orElse(0);
        GuiActions.input(gui, GuiWidgets.text(gui, "ui.input.withdraw_amount", asset.displayName(), asset.format(asset.minimumWithdrawal()), asset.format(available)), text -> {
            long raw = asset.parse(text);
            if (raw < asset.minimumWithdrawal() || raw > available)
            {
                throw new IllegalArgumentException("ui.withdraw.minimum");
            }
            return raw;
        }, v -> {
        }).thenAccept(amount -> {
            if (amount.isEmpty())
            {
                return;
            }
            GuiActions.message(gui, "ui.withdraw.summary", asset.displayName(), asset.format(amount.get()), output);
            GuiActions.confirm(gui, "ui.confirm.withdraw", () -> GuiActions.task(gui, GameServices.get().items.withdraw(gui.getPlayer(), user, assetId, amount.get(), output), "ui.success.withdraw", asset.format(amount.get()), asset.displayName()));
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
        GuiWidgets.option(gui, 7, Items.GOLDEN_BOOTS, "gui.capitalmc.account.spot", "ui.bank.spot", true,
                          () -> gui.accountPage().setAccountType(AccountType.Spot));
        GuiWidgets.button(gui, 8, Items.CHAINMAIL_BOOTS, "gui.capitalmc.account.contract", "ui.bank.contract", () -> {
            gui.accountPage().setAccountType(AccountType.Contract);
            gui.showPage(PageType.TRANSFER);
        });
        if (mode == Mode.CHOOSE)
        {
            GuiWidgets.button(gui, 20, Items.MUSHROOM_STEW, "ui.deposit", "", () -> mode = Mode.DEPOSIT);
            GuiWidgets.button(gui, 24, Items.BEETROOT_SOUP, "ui.withdraw", "", () -> {
                mode = Mode.WITHDRAW;
                choosingAsset = gui.getSelectedAsset() <= 0;
            });
            return;
        }
        if (mode == Mode.WITHDRAW && (choosingAsset || gui.getSelectedAsset() <= 0))
        {
            count = 0;
            renderAssets(gui);
            return;
        }
        if (mode == Mode.WITHDRAW)
        {
            GuiWidgets.button(gui, 6, Items.DIAMOND, "ui.withdraw.select_asset", "", () -> {
                choosingAsset = true;
                gui.selectAsset(0);
            });
        }
        if (gui.getSelectedAsset() > 0)
        {
            var asset = GameServices.get().assets.find(gui.getSelectedAsset()).orElseThrow();
            gui.setSlot(4, GuiWidgets.asset(gui, asset.id(), GuiWidgets.unitsDescription(gui, asset), null));
        }
        if (mode == Mode.DEPOSIT)
        {
            GuiWidgets.button(gui, 20, Items.DIAMOND, "ui.deposit.drops", "ui.deposit.batch_drops", () -> deposit(false));
            GuiWidgets.button(gui, 24, Items.SHULKER_BOX, "ui.deposit.container", "ui.deposit.batch_containers", () -> deposit(true));
            GuiWidgets.button(gui, 30, Items.CHEST, "ui.deposit.chest_credits", "ui.deposit.credits_lore", () -> depositCredits(ItemBankService.ContainerKind.CHEST));
            GuiWidgets.button(gui, 32, Items.SHULKER_BOX, "ui.deposit.shulker_credits", "ui.deposit.credits_lore", () -> depositCredits(ItemBankService.ContainerKind.SHULKER));
            GuiWidgets.button(gui, 34, Items.EXPERIENCE_BOTTLE, "ui.deposit.experience", GuiWidgets.text(gui, "ui.deposit.experience_lore", ItemBankService.experienceAvailable(gui.getPlayer())), this::depositExperience);
        }
        else
        {
            var outputs = ItemBankService.Output.values();
            var icons = new Item[]{Items.DIAMOND, Items.CHEST, Items.CHEST, Items.SHULKER_BOX};
            var keys = new String[]{"ui.withdraw.items", "ui.withdraw.small_chest", "ui.withdraw.large_chest", "ui.withdraw.shulker"};
            for (int i = 0; i < outputs.length; i++)
            {
                var output = outputs[i];
                GuiWidgets.button(gui, 19 + i * 2, icons[i], keys[i], "", () -> withdraw(output));
            }
        }
        gui.getUser().ifPresent(user -> gui.setSlot(31, TradingGui.icon(gui.getPlayer(), Items.CHEST, GuiWidgets.text(gui, "ui.container.credits", GameServices.get().items.containerCredit(user, ItemBankService.ContainerKind.CHEST), GameServices.get().items.containerCredit(user, ItemBankService.ContainerKind.SHULKER)), "")));
    }
}
