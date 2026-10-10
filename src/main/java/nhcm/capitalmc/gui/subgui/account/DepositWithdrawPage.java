package nhcm.capitalmc.gui.subgui.account;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.service.*;

import java.time.Duration;

public class DepositWithdrawPage extends BaseSubGui
{
    private enum Mode
    {
        CHOOSE, DEPOSIT, WITHDRAW
    }

    private Mode mode = Mode.CHOOSE;

    public DepositWithdrawPage(TradingGui gui)
    {
        super(gui, PageType.DEPOSIT_WITHDRAW);
        setCenterItem(Items.NAME_TAG);
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
        GuiActions.input(gui, GuiWidgets.amountInput(gui, assetId, asset.minimumWithdrawal()), text -> {
            long raw = asset.parse(text);
            if (raw < asset.minimumWithdrawal())
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
        if (mode == Mode.CHOOSE)
        {
            GuiWidgets.button(gui, 20, Items.MUSHROOM_STEW, "ui.deposit", "", () -> mode = Mode.DEPOSIT);
            GuiWidgets.button(gui, 24, Items.BEETROOT_SOUP, "ui.withdraw", "", () -> mode = Mode.WITHDRAW);
            return;
        }
        GuiWidgets.button(gui, 0, Items.NAME_TAG, "ui.deposit_withdraw.choose", "", () -> mode = Mode.CHOOSE);
        GuiWidgets.button(gui, 8, Items.DIAMOND, "ui.withdraw.select_asset", "", () -> gui.showPage(PageType.MY_ACCOUNT));
        if (gui.getSelectedAsset() > 0)
        {
            var asset = GameServices.get().assets.find(gui.getSelectedAsset()).orElseThrow();
            gui.setSlot(4, GuiWidgets.asset(gui, asset.id(), GuiWidgets.unitsDescription(gui, asset), null));
        }
        if (mode == Mode.DEPOSIT)
        {
            GuiWidgets.button(gui, 20, Items.DIAMOND, "ui.deposit.drops", "ui.deposit.batch_drops", () -> deposit(false));
            GuiWidgets.button(gui, 24, Items.SHULKER_BOX, "ui.deposit.container", "ui.deposit.batch_containers", () -> deposit(true));
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
