package nhcm.capitalmc.test;

import com.mojang.authlib.GameProfile;
import com.google.gson.JsonParser;
import eu.pb4.sgui.api.ClickType;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.*;
import nhcm.advmatch.model.AccountType;
import nhcm.advmatch.model.AuditReport;
import nhcm.advmatch.model.ContractOrderOptions;
import nhcm.advmatch.model.ContractSpec;
import nhcm.advmatch.model.MarginMode;
import nhcm.advmatch.model.OrderSide;
import nhcm.advmatch.model.OrderType;
import nhcm.advmatch.model.OrderView;
import nhcm.advmatch.model.PositionSide;
import nhcm.advmatch.model.PositionView;
import nhcm.advmatch.model.SymbolType;
import nhcm.advmatch.model.SymbolView;
import nhcm.advmatch.model.TimeInForce;
import nhcm.advmatch.object.Symbol;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.exchange.BalanceUtil;
import nhcm.capitalmc.exchange.ExchangeMessages;
import nhcm.capitalmc.gui.GuiActions;
import nhcm.capitalmc.exchange.SymbolUtil;
import nhcm.capitalmc.exchange.TradeUtil;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.service.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Exercises real server inventories, drop mixins, containers, SGui and JNI together.
 */
public final class CapitalGameTests
{
    @GameTest(maxTicks = 2400)
    public void batchDropsAccumulateAndChangedPreviewReleasesInterception(GameTestHelper helper)
    {
        var test = new Steps(helper);
        test.add(() -> GameServices.get().ready());
        test.add(() -> {
            test.player = survivalPlayer(helper);
            return CapitalMC.serverUserManager.registerAsync("Batch", "batch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "", test.player.getUUID());
        });
        test.add(() -> {
            test.user = (ServerUser) test.last;
            CapitalMC.serverUserManager.activateVerified(test.player.getUUID(), test.user);
            test.diamond = GameServices.get().assets.find("mc_diamond").orElseThrow().id();
            test.gold = GameServices.get().assets.find("mc_gold_ingot").orElseThrow().id();
            test.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 8));
            test.player.getInventory().setItem(1, new ItemStack(Items.GOLD_INGOT, 6));
            var items = GameServices.get().items;
            items.beginBatchSelection(test.player, test.user, false, Duration.ofSeconds(30));
            test.player.getInventory().setSelectedSlot(0);
            test.player.drop(false);
            test.player.drop(false);
            test.player.getInventory().setSelectedSlot(1);
            test.player.drop(true);
            test.player.drop(true);
            check(test.player.getInventory().getItem(0).getCount() == 8 && test.player.getInventory().getItem(1).getCount() == 6, "Batch preview removed items");
            var choice = items.finishBatchSelection(test.player);
            check(choice.amounts().get(test.diamond) == 2 && choice.amounts().get(test.gold) == 6, "Batch drop counts duplicated or were lost");
            test.player.getInventory().setSelectedSlot(2);
            var deposited = items.deposit(test.player, test.user, choice);
            check(!items.interceptDrop(test.player, false), "Submitted custody operation still blocks ordinary drops");
            return deposited;
        });
        test.add(() -> BalanceUtil.spotAvailable(test.user, test.diamond));
        test.add(() -> {
            check((Long) test.last == 2 && test.player.getInventory().getItem(0).getCount() == 6 && test.player.getInventory().getItem(1).isEmpty(), "Batch debit or credit differs");
            var items = GameServices.get().items;
            test.player.getInventory().setSelectedSlot(0);
            var pending = items.beginSelection(test.player, test.user, false, Duration.ofSeconds(30));
            test.player.drop(false);
            var choice = pending.getNow(null);
            test.player.getInventory().getItem(0).shrink(1);
            try
            {
                items.deposit(test.player, test.user, choice);
                throw new AssertionError("Changed preview took custody");
            } catch (IllegalStateException expected)
            {
            }
            check(!items.interceptDrop(test.player, false), "Rejected preview still intercepts normal drops");
            items.beginBatchSelection(test.player, test.user, false, Duration.ofSeconds(30));
            test.player.drop(false);
            items.cancelSelection(test.player.getUUID());
            check(!items.interceptDrop(test.player, false), "Cancelled batch still intercepts drops");
            return BalanceUtil.spotAvailable(test.user, test.diamond);
        });
        test.add(() -> {
            check((Long) test.last == 2, "Rejected or cancelled preview credited funds");
            return CompletableFuture.completedFuture(null);
        });
        test.run();
    }

    @GameTest(maxTicks = 2400)
    public void batchContainersRevalidateAllAndCountEachOnce(GameTestHelper helper)
    {
        var test = new Steps(helper);
        var choice = new ItemBankService.DepositSelection[1];
        var positions = new BlockPos[2];
        test.add(() -> GameServices.get().ready());
        test.add(() -> {
            test.player = survivalPlayer(helper);
            var pos = helper.absolutePos(new BlockPos(3, 2, 3));
            test.player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            return CapitalMC.serverUserManager.registerAsync("Containers", "box_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "", test.player.getUUID());
        });
        test.add(() -> {
            test.user = (ServerUser) test.last;
            CapitalMC.serverUserManager.activateVerified(test.player.getUUID(), test.user);
            test.diamond = GameServices.get().assets.find("mc_diamond").orElseThrow().id();
            positions[0] = test.player.blockPosition().relative(Direction.NORTH);
            positions[1] = test.player.blockPosition().relative(Direction.SOUTH);
            test.player.level().setBlockAndUpdate(positions[0], Blocks.CHEST.defaultBlockState());
            test.player.level().setBlockAndUpdate(positions[1], Blocks.SHULKER_BOX.defaultBlockState());
            ((Container) test.player.level().getBlockEntity(positions[0])).setItem(0, new ItemStack(Items.DIAMOND, 3));
            ((Container) test.player.level().getBlockEntity(positions[1])).setItem(0, new ItemStack(Items.DIAMOND, 5));
            var items = GameServices.get().items;
            items.beginBatchSelection(test.player, test.user, true, Duration.ofSeconds(30));
            for (var pos : new BlockPos[]{positions[0], positions[0], positions[1]})
                UseBlockCallback.EVENT.invoker().interact(test.player, test.player.level(), InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
            choice[0] = items.finishBatchSelection(test.player);
            check(choice[0].amounts().get(test.diamond) == 8 && choice[0].chests() == 1 && choice[0].shulkers() == 1, "Duplicate container was counted");
            ((Container) test.player.level().getBlockEntity(positions[1])).getItem(0).shrink(1);
            try
            {
                items.deposit(test.player, test.user, choice[0]);
                throw new AssertionError("Changed container batch took custody");
            } catch (IllegalStateException expected)
            {
            }
            check(!test.player.level().getBlockState(positions[0]).isAir() && !test.player.level().getBlockState(positions[1]).isAir(), "Unchanged container removed after batch rejection");
            items.beginBatchSelection(test.player, test.user, true, Duration.ofSeconds(30));
            for (var pos : positions)
                UseBlockCallback.EVENT.invoker().interact(test.player, test.player.level(), InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
            choice[0] = items.finishBatchSelection(test.player);
            return items.deposit(test.player, test.user, choice[0]);
        });
        test.add(() -> BalanceUtil.spotAvailable(test.user, test.diamond));
        test.add(() -> {
            check((Long) test.last == 7, "Batch container balance differs");
            for (var pos : positions)
                check(test.player.level().getBlockState(pos).isAir(), "Deposited batch container remained");
            var items = GameServices.get().items;
            check(items.containerCredit(test.user, ItemBankService.ContainerKind.CHEST) == 1 && items.containerCredit(test.user, ItemBankService.ContainerKind.SHULKER) == 1, "Batch container credits differ");
            return CompletableFuture.completedFuture(null);
        });
        test.run();
    }

    @GameTest(maxTicks = 2400)
    public void contractTicketShowsCapacityAndUsesRequestedIcons(GameTestHelper helper)
    {
        var test = new Steps(helper);
        var loaded = new CompletableFuture<Void>();
        var calculated = new CompletableFuture<Void>();
        test.add(() -> GameServices.get().ready());
        test.add(() -> {
            test.player = survivalPlayer(helper);
            return CapitalMC.serverUserManager.registerAsync("Capacity", "cap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "", test.player.getUUID());
        });
        test.add(() -> {
            test.user = (ServerUser) test.last;
            CapitalMC.serverUserManager.activateVerified(test.player.getUUID(), test.user);
            test.diamond = GameServices.get().assets.find("mc_diamond").orElseThrow().id();
            test.gold = GameServices.get().assets.find("mc_gold_ingot").orElseThrow().id();
            return GameServices.get().settlements.account(test.user, AccountType.Contract)
                                                 .thenCompose(id -> GameServices.get().settlements.update(id, test.gold, 1100));
        });
        test.add(() -> SymbolUtil.allSymbols().thenCompose(markets -> {
            var existing = markets.stream().filter(s -> s.type() == SymbolType.Future && s.baseAssetId() == test.diamond && s.quoteAssetId() == test.gold).findFirst();
            if (existing.isPresent())
            {
                return CompletableFuture.completedFuture(existing.get());
            }
            return GameServices.get().markets.createFuture("Capacity GUI Test", test.diamond, test.gold, 100,
                                                           new ContractSpec().withMakerFeeRate(0).withTakerFeeRate(0).withExpiryTimestamp(System.currentTimeMillis() + 86400000))
                                             .thenCompose(Symbol::snapshot);
        }));
        test.add(() -> {
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            gui.showTrading((SymbolView) test.last);
            gui.open();
            test.poll = () -> {
                gui.refresh();
                if (gui.getGuiElement(18) != null)
                {
                    loaded.complete(null);
                }
            };
            return loaded;
        });
        test.add(() -> {
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            click(gui, 18);
            check(!chat(test.player, "90"), "Price was broadcast");
            test.poll = () -> {
                gui.refresh();
                var item = gui.getGuiElement(29);
                if (item != null && item.getItemStackForDisplay(gui).getOrDefault(DataComponents.LORE, ItemLore.EMPTY)
                                        .lines().stream().anyMatch(line -> line.getString().equals("Available quantity: 5")))
                {
                    calculated.complete(null);
                }
            };
            return calculated;
        });
        test.add(() -> {
            test.poll = () -> {
            };
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            check(gui.getGuiElement(4).getItemStackForDisplay(gui).is(Items.STAINED_GLASS_PANE.orange()), "Best ask is not orange");
            check(gui.getGuiElement(5).getItemStackForDisplay(gui).is(Items.STAINED_GLASS_PANE.red()), "Other asks are not red");
            check(gui.getGuiElement(22).getItemStackForDisplay(gui).is(Items.STAINED_GLASS_PANE.lime()), "Best bid is not lime");
            check(gui.getGuiElement(23).getItemStackForDisplay(gui).is(Items.STAINED_GLASS_PANE.green()), "Other bids are not green");
            check(gui.getGuiElement(13).getItemStackForDisplay(gui).is(Items.DIAMOND), "Middle item is not the base asset");
            check(gui.getGuiElement(14).getItemStackForDisplay(gui).is(Items.PAPER), "Statistics icon is not paper");
            for (int slot = 28; slot <= 30; slot++)
                check(!gui.getGuiElement(slot).getItemStackForDisplay(gui).hasFoil(), "Unselected quantity button glowed");
            click(gui, 29);
            gui.refresh();
            for (int slot = 28; slot <= 30; slot++)
            {
                check(gui.getGuiElement(slot).getItemStackForDisplay(gui).hasFoil() == (slot == 29), "Wrong quantity fraction glowed");
            }
            check(gui.getGuiElement(21).getItemStackForDisplay(gui).getOrDefault(DataComponents.LORE, ItemLore.EMPTY)
                     .lines().stream().noneMatch(line -> line.getString().contains("ui.trade.toggle_margin")), "Margin translation key leaked");
            check(gui.getGuiElement(37).getItemStackForDisplay(gui).getOrDefault(DataComponents.LORE, ItemLore.EMPTY)
                     .lines().stream().anyMatch(line -> line.getString().contains("Quantity: 5")), "Half quantity click did not fill the ticket");
            check(gui.getGuiElement(21).getItemStackForDisplay(gui).is(Items.ENDER_CHEST), "Isolated icon is incorrect");
            click(gui, 21);
            gui.refresh();
            check(gui.getGuiElement(21).getItemStackForDisplay(gui).is(Items.CHEST), "Cross icon is incorrect");
            for (int slot = 0; slot < 4; slot++)
            {
                var stack = gui.getGuiElement(slot).getItemStackForDisplay(gui);
                check(!stack.is(Items.CHEST) && !stack.is(Items.ENDER_CHEST), "Account shortcut remained in contract ticket");
            }
            check(gui.getGuiElement(0).getItemStackForDisplay(gui).hasFoil(), "Open long is not selected");
            click(gui, 11);
            click(gui, 4);
            check(CapitalMC.guiInputManager.isPaused(test.player.getUUID()), "Empty book click did not request a limit price");
            check(!chat(test.player, "110"), "Book price input broadcast");
            check(gui.getGuiElement(10).getItemStackForDisplay(gui).hasFoil(), "Price input did not switch to limit");
            check(gui.getGuiElement(19).getItemStackForDisplay(gui).getHoverName().getString().contains("110"), "Limit price display was not updated");
            return CompletableFuture.completedFuture(null);
        });
        test.run();
    }

    @GameTest(maxTicks = 12000, setupTicks = 2000)
    public void contractMarketFractionsProtectionAndSubmission(GameTestHelper helper)
    {
        var test = new Steps(helper);
        var market = new SymbolView[1];
        var counter = new ServerUser[1];
        var loaded = new CompletableFuture<Void>();
        var filled = new CompletableFuture<Void>();
        test.add(() -> GameServices.get().ready());
        test.add(() -> {
            test.player = survivalPlayer(helper);
            return CapitalMC.serverUserManager.registerAsync("Market test", "market_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "", test.player.getUUID());
        });
        test.add(() -> {
            test.user = (ServerUser) test.last;
            CapitalMC.serverUserManager.activateVerified(test.player.getUUID(), test.user);
            test.gold = GameServices.get().assets.find("mc_gold_ingot").orElseThrow().id();
            return GameServices.get().assets.registerElectronic("market_base_" + UUID.randomUUID().toString().replace("-", ""), "Market base", 0, new ItemStack(Items.PAPER));
        });
        test.add(() -> {
            test.diamond = ((McAssetRegistry.Asset) test.last).id();
            return GameServices.get().markets.createPerpetual("Market " + UUID.randomUUID(), test.diamond, test.gold, 100,
                                                              new ContractSpec().withMakerFeeRate(0).withTakerFeeRate(0)).thenCompose(Symbol::snapshot);
        });
        test.add(() -> {
            market[0] = (SymbolView) test.last;
            return CapitalMC.serverUserManager.registerAsync("Market maker", "maker_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "", UUID.randomUUID());
        });
        test.add(() -> {
            counter[0] = (ServerUser) test.last;
            return GameServices.get().settlements.account(test.user, AccountType.Contract).thenCompose(account -> GameServices.get().settlements.update(account, test.gold, 1100))
                                                 .thenCompose(unused -> GameServices.get().settlements.account(counter[0], AccountType.Contract))
                                                 .thenCompose(account -> GameServices.get().settlements.update(account, test.gold, 5000));
        });
        test.add(() -> GameServices.get().settlements.place(counter[0], market[0].symbolId(), OrderSide.Ask, OrderType.Limit, TimeInForce.GTC, 100, 20,
                                                            new ContractOrderOptions().withLeverage(1)));
        test.add(() -> {
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            gui.showTrading(market[0]);
            gui.open();
            test.poll = () -> {
                gui.refresh();
                if (gui.getGuiElement(11) != null)
                {
                    click(gui, 11);
                    gui.refresh();
                    if (gui.getGuiElement(30).getItemStackForDisplay(gui).getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().stream()
                           .anyMatch(line -> line.getString().equals("Available quantity: 10")))
                    {
                        loaded.complete(null);
                    }
                }
            };
            return loaded;
        });
        test.add(() -> {
            test.poll = () -> {
            };
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            for (int i = 0; i < 3; i++)
            {
                click(gui, 28 + i);
                gui.refresh();
                String expected = "Quantity: " + new int[]{2, 5, 10}[i];
                check(gui.getGuiElement(37).getItemStackForDisplay(gui).getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().stream().anyMatch(line -> line.getString().contains(expected)), "Market fraction did not set quantity");
                check(gui.getGuiElement(28 + i).getItemStackForDisplay(gui).hasFoil(), "Market fraction not selected");
            }
            click(gui, 18);
            check(!chat(test.player, "105"), "Protection input broadcast");
            gui.refresh();
            check(gui.getGuiElement(11).getItemStackForDisplay(gui).hasFoil(), "Protection input switched to limit mode");
            click(gui, 37);
            check(!chat(test.player, "yes"), "Confirmation broadcast");
            test.poll = () -> TradeUtil.positions(test.user).whenComplete((positions, error) -> GameServices.get().server.execute(() -> {
                if (error != null)
                {
                    filled.completeExceptionally(error);
                }
                else if (positions.stream().anyMatch(position -> position.symbolId() == market[0].symbolId() && position.size() == 10 && !position.closed()))
                {
                    filled.complete(null);
                }
            }));
            return filled;
        });
        test.run();
    }

    // GameTest ticks run faster than real time; allow disk-backed asynchronous work to finish.
    @GameTest(maxTicks = 12000)
    public void contractCloseButtonsSubmitOnlyAgainstSelectedPositions(GameTestHelper helper)
    {
        var test = new Steps(helper);
        var counter = new ServerUser[1];
        var market = new SymbolView[1];
        var loaded = new CompletableFuture<Void>();
        var longLoaded = new CompletableFuture<Void>();
        var longSubmitted = new CompletableFuture<Void>();
        var bookLoaded = new CompletableFuture<Void>();
        var shortSubmitted = new CompletableFuture<Void>();
        int[] waiting = {0};
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        test.add(() -> GameServices.get().ready());
        // The binding test briefly halts the entire exchange; let it finish before placing orders.
        test.add(() -> {
            var settled = new CompletableFuture<Void>();
            int[] ticks = {0};
            test.poll = () -> {
                if (++ticks[0] >= 200)
                {
                    settled.complete(null);
                }
            };
            return settled;
        });
        test.add(() -> {
            test.poll = () -> {
            };
            test.player = survivalPlayer(helper);
            return CapitalMC.serverUserManager.registerAsync("Close ticket", "close_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "", test.player.getUUID());
        });
        test.add(() -> {
            test.user = (ServerUser) test.last;
            CapitalMC.serverUserManager.activateVerified(test.player.getUUID(), test.user);
            return CapitalMC.serverUserManager.registerAsync("Counterparty", "counter_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "", UUID.randomUUID());
        });
        test.add(() -> {
            counter[0] = (ServerUser) test.last;
            test.diamond = GameServices.get().assets.find("mc_diamond").orElseThrow().id();
            test.gold = GameServices.get().assets.find("mc_gold_ingot").orElseThrow().id();
            return GameServices.get().settlements.account(test.user, AccountType.Contract)
                                                 .thenCompose(id -> GameServices.get().settlements.update(id, test.gold, 10000))
                                                 .thenCompose(unused -> GameServices.get().settlements.account(counter[0], AccountType.Contract))
                                                 .thenCompose(id -> GameServices.get().settlements.update(id, test.gold, 10000));
        });
        // Unique assets keep old failed-test orders out of this fixture's book.
        test.add(() -> GameServices.get().assets.registerElectronic("close_base_" + suffix, "Close ticket base", 0, new ItemStack(Items.LAPIS_LAZULI)));
        test.add(() -> {
            test.diamond = ((McAssetRegistry.Asset) test.last).id();
            return GameServices.get().markets.createFuture("Close " + suffix, test.diamond, test.gold, 100,
                                                           new ContractSpec().withMakerFeeRate(0).withTakerFeeRate(0).withExpiryTimestamp(System.currentTimeMillis() + 86400000)).thenCompose(Symbol::snapshot);
        });
        test.add(() -> {
            market[0] = (SymbolView) test.last;
            var options = new ContractOrderOptions().withLeverage(1).withMarginMode(MarginMode.Isolated);
            var settlement = GameServices.get().settlements;
            return settlement.place(counter[0], market[0].symbolId(), OrderSide.Ask, OrderType.Limit, TimeInForce.GTC, 100, 6, options)
                             .thenCompose(unused -> settlement.place(test.user, market[0].symbolId(), OrderSide.Bid, OrderType.Limit, TimeInForce.IOC, 100, 6, options))
                             .thenCompose(unused -> settlement.place(counter[0], market[0].symbolId(), OrderSide.Bid, OrderType.Limit, TimeInForce.GTC, 100, 4, options))
                             .thenCompose(unused -> settlement.place(test.user, market[0].symbolId(), OrderSide.Ask, OrderType.Limit, TimeInForce.IOC, 100, 4, options));
        });
        test.add(() -> {
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            gui.showTrading(market[0]);
            gui.open();
            test.poll = () -> {
                gui.refresh();
                if (gui.getGuiElement(18) != null)
                {
                    loaded.complete(null);
                }
            };
            return loaded;
        });
        test.add(() -> {
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            click(gui, 2);
            test.poll = () -> {
                gui.refresh();
                var position = gui.getGuiElement(12);
                if (++waiting[0] > 1000)
                {
                    throw new AssertionError("Closing position did not load: " + (position == null ? "missing shield" :
                            position.getItemStackForDisplay(gui).getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines()));
                }
                if (position != null && position.getItemStackForDisplay(gui).getOrDefault(DataComponents.LORE, ItemLore.EMPTY)
                                                .lines().stream().anyMatch(line -> line.getString().equals("Available to close: 6")))
                {
                    longLoaded.complete(null);
                }
            };
            return longLoaded;
        });
        test.add(() -> {
            test.poll = () -> {
            };
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            check(gui.getGuiElement(2).getItemStackForDisplay(gui).hasFoil(), "Close long did not glow");
            click(gui, 18);
            check(!chat(test.player, "110"), "Close price broadcast");
            click(gui, 29);
            click(gui, 37);
            check(!chat(test.player, "yes"), "Close confirmation broadcast");
            test.poll = () -> {
                if (!CapitalMC.guiInputManager.isPaused(test.player.getUUID()))
                {
                    longSubmitted.complete(null);
                }
            };
            return longSubmitted;
        });
        test.add(() -> TradeUtil.openOrders(test.user));
        test.add(() -> {
            check(((List<?>) test.last).stream().anyMatch(value -> value instanceof OrderView order
                                                                   && order.symbolId() == market[0].symbolId() && order.reduceOnly() && order.positionId() != 0
                                                                   && order.side() == OrderSide.Ask && order.quantity() == 3), "Close long did not create a bound reduce-only sell");
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            test.poll = () -> {
                gui.refresh();
                if (gui.getGuiElement(4).getItemStackForDisplay(gui).getHoverName().getString().contains("110"))
                {
                    bookLoaded.complete(null);
                }
            };
            return bookLoaded;
        });
        test.add(() -> {
            test.poll = () -> {
            };
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            click(gui, 11);
            click(gui, 4);
            check(!CapitalMC.guiInputManager.isPaused(test.player.getUUID()), "Populated book did not set price directly");
            check(gui.getGuiElement(10).getItemStackForDisplay(gui).hasFoil(), "Populated book did not switch to limit");
            check(gui.getGuiElement(19).getItemStackForDisplay(gui).getHoverName().getString().contains("110"), "Populated book price did not update");
            click(gui, 3);
            click(gui, 18);
            check(!chat(test.player, "90"), "Close short price broadcast");
            click(gui, 27);
            check(!chat(test.player, "2"), "Close short quantity broadcast");
            click(gui, 37);
            check(!chat(test.player, "yes"), "Close short confirmation broadcast");
            test.poll = () -> {
                if (!CapitalMC.guiInputManager.isPaused(test.player.getUUID()))
                {
                    shortSubmitted.complete(null);
                }
            };
            return shortSubmitted;
        });
        test.add(() -> TradeUtil.openOrders(test.user));
        test.add(() -> {
            check(((List<?>) test.last).stream().anyMatch(value -> value instanceof OrderView order
                                                                   && order.symbolId() == market[0].symbolId() && order.reduceOnly() && order.positionId() != 0
                                                                   && order.side() == OrderSide.Bid && order.quantity() == 2), "Close short did not create a bound reduce-only buy");
            return TradeUtil.positions(test.user);
        });
        test.add(() -> {
            var positions = (List<?>) test.last;
            check(positions.stream().anyMatch(value -> value instanceof PositionView position
                                                       && position.side() == PositionSide.Long && position.size() == 6), "Resting close long changed the position");
            check(positions.stream().anyMatch(value -> value instanceof PositionView position
                                                       && position.side() == PositionSide.Short && position.size() == 4), "Resting close short changed the position");
            test.poll = () -> {
            };
            return CompletableFuture.completedFuture(null);
        });
        test.run();
    }

    @GameTest(maxTicks = 1200)
    public void repliesArePrivateAndCredentialsAreNotEchoed(GameTestHelper helper)
    {
        var test = new Steps(helper);
        test.add(() -> GameServices.get().ready());
        test.add(() -> {
            test.player = survivalPlayer(helper);
            var player = (MessagePlayer) test.player;
            var gui = CapitalMC.guiManager.getTradingGui(player);
            gui.showPage(PageType.HOME);
            gui.open();
            var input = gui.promptInput(Component.literal("Name"), Duration.ofSeconds(5), String::strip, value -> {
            });
            check(!chat(player, "&aPrivate reply"), "Reply was broadcast");
            check(player.messages.stream().anyMatch(text -> text.equals("[Reply] &aPrivate reply")), "Local reply was missing or interpreted as formatting");
            return input;
        });
        test.add(() -> {
            var player = (MessagePlayer) test.player;
            var gui = CapitalMC.guiManager.getTradingGui(player);
            var input = CapitalMC.guiInputManager.awaitCredential(gui, Component.literal("Password"), Duration.ofSeconds(5));
            player.messages.clear();
            check(!chat(player, "private-secret"), "Credential was broadcast");
            check(player.messages.stream().noneMatch(text -> text.contains("private-secret")), "Credential was echoed");
            return input;
        });
        test.add(() -> {
            var player = (MessagePlayer) test.player;
            var gui = CapitalMC.guiManager.getTradingGui(player);
            player.messages.clear();
            var feedback = new CompletableFuture<Void>();
            test.poll = () -> {
                if (player.messages.stream().anyMatch(text -> text.contains("Logged in as TestAccount")))
                {
                    feedback.complete(null);
                }
            };
            GuiActions.task(gui, CompletableFuture.completedFuture(null), "ui.success.login", "TestAccount");
            return feedback;
        });
        test.add(() -> {
            var player = (MessagePlayer) test.player;
            var gui = CapitalMC.guiManager.getTradingGui(player);
            player.messages.clear();
            var feedback = new CompletableFuture<Void>();
            test.poll = () -> {
                check(player.messages.stream().noneMatch(text -> text.contains("Logged in as GhostAccount")), "Failed operation emitted success feedback");
                if (player.messages.stream().anyMatch(text -> text.contains("[Failed]")))
                {
                    feedback.complete(null);
                }
            };
            GuiActions.task(gui, CompletableFuture.failedFuture(new IllegalArgumentException("ui.access.bad_password")), "ui.success.login", "GhostAccount");
            return feedback;
        });
        test.add(() -> {
            test.poll = () -> {
            };
            return CompletableFuture.completedFuture(null);
        });
        test.run();
    }

    @GameTest(maxTicks = 1200)
    public void adminSupplySearchAndTransferAssetPickerUseNativeViews(GameTestHelper helper)
    {
        var test = new Steps(helper);
        String name = "picker_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var asset = new McAssetRegistry.Asset[1];
        test.add(() -> GameServices.get().ready());
        test.add(() -> {
            test.player = survivalPlayer(helper);
            test.player.level().getServer().getPlayerList().op(new NameAndId(test.player.getGameProfile()), Optional.of(LevelBasedPermissionSet.OWNER), Optional.of(false));
            return CapitalMC.serverUserManager.registerAsync(name, name, "", test.player.getUUID());
        });
        test.add(() -> {
            test.user = (ServerUser) test.last;
            CapitalMC.serverUserManager.activateVerified(test.player.getUUID(), test.user);
            return GameServices.get().assets.registerElectronic(name, name, 2, new ItemStack(Items.COPPER_INGOT));
        });
        test.add(() -> {
            asset[0] = (McAssetRegistry.Asset) test.last;
            return GameServices.get().currency.mint(asset[0].id(), 12345, "gametest", "Supply display");
        });
        test.add(() -> {
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            gui.showPage(PageType.TRANSFER);
            gui.open();
            click(gui, 0);
            check(!chat(test.player, name), "Asset search broadcast");
            gui.refresh();
            check(gui.getGuiElement(18).getItemStackForDisplay(gui).getHoverName().getString().equals(name), "Transfer picker did not filter assets");
            click(gui, 18);
            check(gui.getCurrentPage() == PageType.TRANSFER && gui.getSelectedAsset() == asset[0].id(), "Asset choice left the transfer page");
            check(gui.getGuiElement(13).getItemStackForDisplay(gui).is(Items.COPPER_INGOT), "Selected transfer asset was not displayed");
            click(gui, 13);
            gui.refresh();
            check(gui.getGuiElement(18).getItemStackForDisplay(gui).getHoverName().getString().equals(name), "Cannot change transfer asset");
            gui.showPage(PageType.ADMIN);
            click(gui, 50);
            check(!chat(test.player, name), "Admin search broadcast");
            gui.refresh();
            check(gui.getGuiElement(18).getItemStackForDisplay(gui).getHoverName().getString().equals(name), "Admin asset search did not filter");
            check(!gui.getGuiElement(49).getItemStackForDisplay(gui).is(Items.END_CRYSTAL), "Redundant refresh crystal remained");
            click(gui, 18);
            var displayed = new CompletableFuture<Void>();
            test.poll = () -> {
                gui.refresh();
                var lore = gui.getGuiElement(22).getItemStackForDisplay(gui).getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines();
                if (lore.stream().anyMatch(line -> line.getString().equals("Net issued supply: 123.45"))
                    && lore.stream().anyMatch(line -> line.getString().equals("Issuer available: 123.45")))
                {
                    test.poll = null;
                    displayed.complete(null);
                }
            };
            return displayed;
        });
        test.run();
    }

    @GameTest(maxTicks = 12000)
    public void pegReservesAndBothDirectionsUseAtomicNativeTransfers(GameTestHelper helper)
    {
        var test = new Steps(helper);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var base = new McAssetRegistry.Asset[1];
        var quote = new McAssetRegistry.Asset[1];
        test.add(() -> GameServices.get().ready());
        test.add(() -> {
            test.player = survivalPlayer(helper);
            return CapitalMC.serverUserManager.registerAsync("Peg", "peg_" + suffix, "", test.player.getUUID());
        });
        test.add(() -> {
            test.user = (ServerUser) test.last;
            CapitalMC.serverUserManager.activateVerified(test.player.getUUID(), test.user);
            return GameServices.get().assets.registerElectronic("peg_cash_" + suffix, "Peg cash " + suffix, 2, new ItemStack(Items.PAPER));
        });
        test.add(() -> {
            base[0] = (McAssetRegistry.Asset) test.last;
            var item = new ItemStack(Items.COBBLESTONE);
            item.set(DataComponents.CUSTOM_NAME, Component.literal(suffix));
            return GameServices.get().assets.registerItem("peg_item_" + suffix, "Peg item " + suffix, item);
        });
        test.add(() -> {
            quote[0] = (McAssetRegistry.Asset) test.last;
            return GameServices.get().currency.mint(base[0].id(), 1000, "gametest", "Peg backing");
        });
        test.add(() -> GameServices.get().currency.issue(test.user, base[0].id(), 1000));
        test.add(() -> {
            test.player.getInventory().setItem(0, quote[0].prototype().copyWithCount(20));
            test.player.getInventory().setSelectedSlot(0);
            var selected = GameServices.get().items.beginSelection(test.player, test.user, false, Duration.ofSeconds(30));
            test.player.drop(true);
            return selected;
        });
        test.add(() -> GameServices.get().items.deposit(test.player, test.user, (ItemBankService.DepositSelection) test.last));
        test.add(() -> GameServices.get().currency.configurePeg(base[0].id(), quote[0].id(), 100, 10, 0, 100, 1000, true, true, true));
        test.add(() -> expectedRejection(GameServices.get().currency.exchange(test.user, base[0].id(), 100, false), "ui.peg.reserve_funds"));
        test.add(() -> GameServices.get().administration.action("reserve-in", new String[]{test.user.getUsername(), quote[0].name(), "20"}, ItemStack.EMPTY, "gametest", "Fund physical reserve"));
        test.add(() -> GameServices.get().currency.exchange(test.user, base[0].id(), 100, false));
        test.add(() -> BalanceUtil.spotAvailable(test.user, quote[0].id()));
        test.add(() -> {
            check((Long) test.last == 10, "Selling electronic base did not deliver physical quote balance");
            return GameServices.get().currency.exchange(test.user, base[0].id(), 100, true);
        });
        test.add(() -> BalanceUtil.spotAvailable(test.user, base[0].id()));
        test.add(() -> {
            check((Long) test.last == 1000, "Buying electronic base did not restore its native amount");
            return BalanceUtil.spotAvailable(test.user, quote[0].id());
        });
        test.add(() -> {
            check((Long) test.last == 0, "Round trip created extra quote balance");
            return GameServices.get().currency.check(test.user, GameServices.get().currency.quote(base[0].id(), 100, true)).handle((unused, error) -> {
                check(error != null, "Buying with no physical quote balance was allowed");
                while (error.getCause() != null)
                {
                    error = error.getCause();
                }
                check(error instanceof CurrencyService.FundsException, "Missing structured funds rejection");
                var funds = (CurrencyService.FundsException) error;
                check(funds.assetId == quote[0].id() && funds.required == 10 && funds.available == 0, "Wrong paid asset or balance in peg rejection");
                check(ExchangeMessages.error("en_us", funds).contains(quote[0].displayName()), "Paid asset absent from English rejection");
                check(ExchangeMessages.error("zh_cn", funds).contains("需要 10，可用 0"), "Missing localized peg amounts");
                return null;
            });
        });
        test.add(() -> {
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            gui.showPage(PageType.PEG);
            gui.open();
            check(gui.getGuiElement(1).getItemStackForDisplay(gui).getHoverName().getString().equals("Buy base (pay quote)"), "Peg still assumes physical base");
            check(gui.getGuiElement(1).getItemStackForDisplay(gui).hasFoil() && !gui.getGuiElement(2).getItemStackForDisplay(gui).hasFoil(), "Peg must initially select buy base");
            int index = GameServices.get().currency.peggedAssets().indexOf(base[0].id());
            for (int page = 0; page < index / 27; page++)
            {
                click(gui, 52);
                gui.refresh();
            }
            int slot = 18 + index % 27;
            String direction = "Pay: " + quote[0].displayName() + " → Receive: " + base[0].displayName();
            check(gui.getGuiElement(slot).getItemStackForDisplay(gui).get(DataComponents.LORE).lines().stream().anyMatch(line -> line.getString().contains(direction)), "Buy tooltip reverses payment direction");
            click(gui, slot);
            check(((MessagePlayer) test.player).messages.getLast().contains("Pay " + quote[0].displayName() + " → receive " + base[0].displayName()), "Buy input does not name the paid asset");
            check(!chat(test.player, "cancel"), "Peg cancellation was broadcast");
            click(gui, 2);
            gui.refresh();
            String reverse = "Pay: " + base[0].displayName() + " → Receive: " + quote[0].displayName();
            check(gui.getGuiElement(slot).getItemStackForDisplay(gui).get(DataComponents.LORE).lines().stream().anyMatch(line -> line.getString().contains(reverse)), "Sell tooltip reverses payment direction");
            return CapitalMC.exchange.audit().report();
        });
        test.add(() -> {
            check(((AuditReport) test.last).conserved(), "Peg reserve movements broke native conservation");
            return CompletableFuture.completedFuture(null);
        });
        test.run();
    }

    @GameTest(maxTicks = 1200)
    public void denominationsCustodyMinimaAndSpotContractLotsPreserveRawAmounts(GameTestHelper helper)
    {
        var test = new Steps(helper);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var asset = new McAssetRegistry.Asset[1];
        var market = new Symbol[1];
        test.add(() -> GameServices.get().ready());
        test.add(() -> {
            test.player = survivalPlayer(helper);
            return CapitalMC.serverUserManager.registerAsync("Units", "units_" + suffix, "", test.player.getUUID());
        });
        test.add(() -> {
            test.user = (ServerUser) test.last;
            CapitalMC.serverUserManager.activateVerified(test.player.getUUID(), test.user);
            var stack = new ItemStack(Items.COPPER_INGOT);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(suffix));
            return GameServices.get().assets.registerItem("unit_item_" + suffix, "Unit item " + suffix, stack);
        });
        test.add(() -> {
            asset[0] = (McAssetRegistry.Asset) test.last;
            return GameServices.get().administration.action("asset-units", new String[]{asset[0].name(), "100", "0.05", "0.03"}, ItemStack.EMPTY, "gametest", "Unit and custody limits");
        });
        test.add(() -> {
            asset[0] = GameServices.get().assets.find(asset[0].id()).orElseThrow();
            check(asset[0].format(1).equals("0.01") && asset[0].minimumDeposit() == 5 && asset[0].minimumWithdrawal() == 3, "Unit settings changed native custody units");
            test.player.getInventory().setItem(0, asset[0].prototype().copyWithCount(4));
            test.player.getInventory().setSelectedSlot(0);
            var selected = GameServices.get().items.beginSelection(test.player, test.user, false, Duration.ofSeconds(30));
            test.player.drop(true);
            return selected;
        });
        test.add(() -> {
            try
            {
                GameServices.get().items.deposit(test.player, test.user, (ItemBankService.DepositSelection) test.last);
                throw new AssertionError("Deposit below minimum was accepted");
            } catch (IllegalArgumentException error)
            {
                check(error.getMessage().equals("ui.deposit.minimum"), "Wrong deposit rejection");
            }
            check(test.player.getInventory().getItem(0).getCount() == 4, "Rejected deposit removed physical items");
            test.player.getInventory().setItem(0, asset[0].prototype().copyWithCount(6));
            var selected = GameServices.get().items.beginSelection(test.player, test.user, false, Duration.ofSeconds(30));
            test.player.drop(true);
            return selected;
        });
        test.add(() -> GameServices.get().items.deposit(test.player, test.user, (ItemBankService.DepositSelection) test.last));
        test.add(() -> BalanceUtil.spotAvailable(test.user, asset[0].id()));
        test.add(() -> {
            check((Long) test.last == 6, "Denomination changed credited raw item count");
            return expectedRejection(GameServices.get().items.withdraw(test.player, test.user, asset[0].id(), 2, ItemBankService.Output.ITEMS), "ui.withdraw.minimum");
        });
        test.add(() -> GameServices.get().items.withdraw(test.player, test.user, asset[0].id(), 3, ItemBankService.Output.ITEMS));
        test.add(() -> BalanceUtil.spotAvailable(test.user, asset[0].id()));
        test.add(() -> {
            check((Long) test.last == 3, "Scaled withdrawal debited a display quantity instead of raw items");
            return GameServices.get().markets.createSpot("Units spot " + suffix, asset[0].id(), GameServices.get().assets.find("cmc").orElseThrow().id());
        });
        test.add(() -> {
            market[0] = (Symbol) test.last;
            return GameServices.get().administration.action("lot", new String[]{Long.toString(market[0].id()), "0.02", "1", "0.5"}, ItemStack.EMPTY, "gametest", "Spot lots");
        });
        test.add(() -> market[0].snapshot());
        test.add(() -> {
            var view = (SymbolView) test.last;
            check(view.tradingRules().minQuantity() == 2 && view.tradingRules().quantityStep() == 1, "Spot lot limits did not reach the native engine");
            return GameServices.get().markets.createPerpetual("Units perp " + suffix, asset[0].id(), view.quoteAssetId(), 100, new ContractSpec().withContractMultiplier(4));
        });
        test.add(() -> {
            market[0] = (Symbol) test.last;
            return GameServices.get().administration.action("lot", new String[]{Long.toString(market[0].id()), "0.12", "1", "1/3"}, ItemStack.EMPTY, "gametest", "Contract denomination");
        });
        test.add(() -> market[0].snapshot());
        test.add(() -> {
            var view = (SymbolView) test.last;
            check(view.tradingRules().minQuantity() == 3 && view.tradingRules().quantityStep() == 1, "Contract lot ignored native multiplier");
            check(GameServices.get().markets.quantityUnits(view).parse("1") == 3, "Contract count did not convert to native quantity");
            var reloaded = new McAssetRegistry(GameServices.get());
            return reloaded.initialize().thenApply(unused -> reloaded);
        });
        test.add(() -> {
            var restored = ((McAssetRegistry) test.last).find(asset[0].id()).orElseThrow();
            check(restored.format(1).equals("0.01") && restored.minimumWithdrawal() == 3, "Restart lost denomination or custody limits");
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            gui.showPage(PageType.MY_ACCOUNT);
            gui.open();
            check(gui.getGuiElement(7).getItemStackForDisplay(gui).hasFoil(), "Spot account selection does not glow");
            click(gui, 8);
            gui.refresh();
            check(gui.getGuiElement(8).getItemStackForDisplay(gui).hasFoil() && !gui.getGuiElement(7).getItemStackForDisplay(gui).hasFoil(), "Account glow did not follow selection");
            gui.showPage(PageType.DEPOSIT_WITHDRAW);
            click(gui, 20);
            gui.refresh();
            check(gui.getGuiElement(24).getItemStackForDisplay(gui).is(Items.SHULKER_BOX), "Container deposit is not a shulker icon");
            return CompletableFuture.completedFuture(null);
        });
        test.run();
    }

    private CompletableFuture<Void> expectedRejection(CompletableFuture<?> operation, String key)
    {
        return operation.handle((unused, error) -> {
            check(error != null, "Expected rejection: " + key);
            while (error.getCause() != null)
            {
                error = error.getCause();
            }
            check(key.equals(error.getMessage()), "Unexpected rejection: " + error);
            return null;
        });
    }

    @GameTest(maxTicks = 1200)
    public void selectedOptionsGlowAndKeepTheirCallbacks(GameTestHelper helper)
    {
        var test = new Steps(helper);
        test.add(() -> GameServices.get().ready());
        test.add(() -> {
            test.player = survivalPlayer(helper);
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            gui.showPage(PageType.LIST_SYMBOLS);
            gui.open();
            check(!gui.getGuiElement(49).getItemStackForDisplay(gui).is(Items.END_CRYSTAL), "List refresh crystal remained");
            check(gui.getGuiElement(7).getItemStackForDisplay(gui).hasFoil(), "Spot filter did not glow");
            check(gui.getGuiElement(7).getItemStackForDisplay(gui).getHoverName().getString().equals("Spot trading pairs"), "Spot filter still labels an account");
            check(!gui.getGuiElement(8).getItemStackForDisplay(gui).hasFoil(), "Unselected contract filter glowed");
            click(gui, 8);
            gui.refresh();
            check(!gui.getGuiElement(7).getItemStackForDisplay(gui).hasFoil(), "Previous filter retained its glow");
            check(gui.getGuiElement(8).getItemStackForDisplay(gui).hasFoil(), "Contract filter did not glow after click");
            check(gui.getGuiElement(8).getItemStackForDisplay(gui).getHoverName().getString().equals("Contract trading pairs"), "Contract filter still labels an account");
            return CompletableFuture.completedFuture(null);
        });
        test.run();
    }

    @GameTest(maxTicks = 1200)
    public void adminRegistrationAsksOnlyIdAndDisplayAndValidatesBeforeSubmission(GameTestHelper helper)
    {
        var test = new Steps(helper);
        String name = "gui_" + UUID.randomUUID().toString().replace("-", "");
        var registered = new CompletableFuture<McAssetRegistry.Asset>();
        test.add(() -> GameServices.get().ready());
        test.add(() -> {
            test.player = survivalPlayer(helper);
            test.player.level().getServer().getPlayerList().op(new NameAndId(test.player.getGameProfile()), Optional.of(LevelBasedPermissionSet.OWNER), Optional.of(false));
            var held = new ItemStack(Items.FLINT);
            held.set(DataComponents.CUSTOM_NAME, Component.literal(name));
            test.player.getInventory().setItem(test.player.getInventory().getSelectedSlot(), held);
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            gui.showPage(PageType.ADMIN);
            gui.open();
            click(gui, 7);
            check(!chat(test.player, "中文 ID"), "Invalid ID was broadcast");
            check(CapitalMC.guiInputManager.isPaused(test.player.getUUID()), "Invalid ID closed the question");
            check(GameServices.get().assets.find(name).isEmpty(), "Invalid ID submitted a registration");
            check(!chat(test.player, name), "Asset ID was broadcast");
            check(CapitalMC.guiInputManager.isPaused(test.player.getUUID()), "Display name was not requested");
            check(!chat(test.player, "&b火石"), "Display name was broadcast");
            check(!chat(test.player, "yes"), "Registration confirmation was broadcast");
            test.poll = () -> {
                if (registered.isDone())
                {
                    return;
                }
                if (!CapitalMC.guiInputManager.isPaused(test.player.getUUID()))
                {
                    GameServices.get().assets.find(name).ifPresent(registered::complete);
                }
            };
            return registered;
        });
        test.add(() -> {
            var asset = (McAssetRegistry.Asset) test.last;
            check(asset.displayName().equals("&b火石"), "Display name was altered");
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            check(gui.isOpen(), "Registration did not restore GUI");
            var log = GameServices.get().state.section("adminOperations").getAsJsonObject("adminOperations");
            var record = log.entrySet().stream().map(row -> row.getValue().getAsJsonObject()).filter(row -> row.get("action").getAsString().equals("register") && row.getAsJsonArray("payload").get(0).getAsString().equals(name)).findFirst().orElseThrow();
            check(record.get("phase").getAsString().equals("COMPLETE") && !record.get("reason").getAsString().isBlank(), "Simplified registration lost its durable audit");
            return CompletableFuture.completedFuture(null);
        });
        test.run();
    }

    // Start after the 1200-tick global halt fixture; both tests share one exchange.
    @GameTest(maxTicks = 12000, setupTicks = 2000)
    public void professionalBookNavigationDepthAndPriceSelection(GameTestHelper helper)
    {
        var test = new Steps(helper);
        var market = new SymbolView[1];
        var loaded = new CompletableFuture<Void>();
        var bookLoaded = new CompletableFuture<Void>();
        test.add(() -> GameServices.get().ready());
        test.add(() -> {
            test.player = survivalPlayer(helper);
            return CapitalMC.serverUserManager.registerAsync("Book Test", "book_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "", test.player.getUUID());
        });
        test.add(() -> {
            test.user = (ServerUser) test.last;
            CapitalMC.serverUserManager.activateVerified(test.player.getUUID(), test.user);
            test.gold = GameServices.get().assets.find("mc_gold_ingot").orElseThrow().id();
            return GameServices.get().assets.registerElectronic("book_base_" + UUID.randomUUID().toString().replace("-", ""), "Book base", 0, new ItemStack(Items.PAPER));
        });
        test.add(() -> {
            test.diamond = ((McAssetRegistry.Asset) test.last).id();
            return GameServices.get().markets.createSpot("Book " + UUID.randomUUID(), test.diamond, test.gold).thenCompose(Symbol::snapshot);
        });
        test.add(() -> {
            market[0] = (SymbolView) test.last;
            return GameServices.get().settlements.account(test.user, AccountType.Spot).thenCompose(account ->
                                                                                                           GameServices.get().settlements.update(account, test.diamond, 100).thenCompose(unused -> GameServices.get().settlements.update(account, test.gold, 100000)));
        });
        test.add(() -> {
            CompletableFuture<?> placed = CompletableFuture.completedFuture(null);
            for (int i = 0; i < 24; i++)
            {
                long ask = 110 + i, bid = 90 - i;
                placed = placed.thenCompose(unused -> TradeUtil.placeSpot(test.user, market[0].symbolId(), OrderSide.Ask, OrderType.Limit, TimeInForce.GTC, ask, 1, System.currentTimeMillis()))
                               .thenCompose(unused -> TradeUtil.placeSpot(test.user, market[0].symbolId(), OrderSide.Bid, OrderType.Limit, TimeInForce.GTC, bid, 1, System.currentTimeMillis()));
            }
            return placed;
        });
        test.add(() -> {
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            gui.showTrading(market[0]);
            gui.open();
            test.poll = () -> {
                gui.refresh();
                if (gui.getGuiElement(15) != null && gui.getGuiElement(4).getItemStackForDisplay(gui).getHoverName().getString().contains("110"))
                {
                    loaded.complete(null);
                }
            };
            return loaded;
        });
        test.add(() -> {
            test.poll = () -> {
            };
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            check(gui.getGuiElement(15).getItemStackForDisplay(gui).is(Items.COMPARATOR), "Comparator not beside statistics paper");
            click(gui, 27);
            chat(test.player, "2");
            gui.getGuiElement(15).getGuiCallback().click(15, ClickType.MOUSE_RIGHT, ContainerInput.PICKUP, gui);
            check(gui.getCurrentPage() == PageType.BOOK_SETTINGS, "Right click did not open settings");
            click(gui, 19);
            gui.refresh();
            check(gui.bookSettings().depth == 44 && gui.getGuiElement(19).getItemStackForDisplay(gui).hasFoil(), "Depth setting did not glow or apply");
            click(gui, 41);
            test.poll = () -> {
                gui.refresh();
                if (gui.getGuiElement(21).getItemStackForDisplay(gui).is(Items.STAINED_GLASS_PANE.orange()))
                {
                    bookLoaded.complete(null);
                }
            };
            return bookLoaded;
        });
        test.add(() -> {
            test.poll = () -> {
            };
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            check(gui.getCurrentPage() == PageType.ORDER_BOOK && gui.getGuiElement(22).getItemStackForDisplay(gui).is(Items.COMPARATOR), "Missing center comparator");
            check(gui.getGuiElement(23).getItemStackForDisplay(gui).is(Items.STAINED_GLASS_PANE.lime()), "Best bid not right of center");
            check(gui.getGuiElement(20).getItemStackForDisplay(gui).getHoverName().getString().contains("111"), "Asks not ordered right to left");
            check(gui.getGuiElement(24).getItemStackForDisplay(gui).getHoverName().getString().contains("89"), "Bids not ordered left to right");
            var lore = gui.getGuiElement(22).getItemStackForDisplay(gui).getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines();
            check(lore.stream().anyMatch(line -> line.getString().contains("50.00%")), "Book comparison missing percentages");
            click(gui, 52);
            gui.refresh();
            check(gui.getGuiElement(21).getItemStackForDisplay(gui).getHoverName().getString().contains("132"), "Second book page did not advance asks");
            check(gui.getGuiElement(25).getItemStackForDisplay(gui).is(Items.STAINED_GLASS_PANE.white()), "Missing depth not shown as white glass");
            click(gui, 51);
            gui.refresh();
            click(gui, 21);
            check(gui.getCurrentPage() == PageType.TRADE_SPOT && gui.getGuiElement(19).getItemStackForDisplay(gui).getHoverName().getString().contains("110"), "Book click did not set ticket price");
            check(gui.getGuiElement(37).getItemStackForDisplay(gui).getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().stream().anyMatch(line -> line.getString().contains("Quantity: 2")), "Book click discarded ticket quantity");
            click(gui, 15);
            check(gui.getCurrentPage() == PageType.ORDER_BOOK, "Left comparator click did not open book");
            check(CapitalMC.guiManager.getTradingGui(test.player) == gui, "Book navigation recreated the window");
            return TradeUtil.openOrders(test.user);
        });
        test.add(() -> {
            check(((List<?>) test.last).size() == 48, "Price selection submitted an order");
            return CompletableFuture.completedFuture(null);
        });
        test.run();
    }

    @GameTest(maxTicks = 1200)
    public void guidedAdminInputRetriesCancelsAndConvertsUnits(GameTestHelper helper)
    {
        var test = new Steps(helper);
        var base = new McAssetRegistry.Asset[1];
        var market = new SymbolView[1];
        String suffix = UUID.randomUUID().toString().replace("-", "");
        test.add(() -> GameServices.get().ready());
        test.add(() -> {
            test.player = survivalPlayer(helper);
            test.player.level().getServer().getPlayerList().op(new NameAndId(test.player.getGameProfile()), Optional.of(LevelBasedPermissionSet.OWNER), Optional.of(false));
            return GameServices.get().assets.registerElectronic("form_" + suffix, "Form cash " + suffix, 2, new ItemStack(Items.PAPER));
        });
        test.add(() -> {
            base[0] = (McAssetRegistry.Asset) test.last;
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            gui.showPage(PageType.ADMIN);
            gui.open();
            var input = AdminInput.collect(gui, "peg-human", "", null);
            for (String answer : List.of(base[0].displayName(), "mc_diamond", "1", "10", "0", "1"))
            {
                check(!chat(test.player, answer), "Form answer was broadcast");
            }
            check(!chat(test.player, "0"), "Invalid maximum was broadcast");
            check(!input.isDone() && CapitalMC.guiInputManager.isPaused(test.player.getUUID()), "Invalid maximum advanced the form");
            check(!GameServices.get().currency.peggedAssets().contains(base[0].id()), "Input took effect before confirmation");
            for (String answer : List.of("10", "是", "否", "1"))
            {
                check(!chat(test.player, answer), "Form answer was broadcast");
            }
            return input;
        });
        test.add(() -> {
            @SuppressWarnings("unchecked")
            var input = (Optional<String[]>) test.last;
            String[] args = input.orElseThrow();
            check(args[0].equals(base[0].name()) && args[7].equals("true") && args[8].equals("false"), "Asset aliases or choices were not normalized");
            return GameServices.get().administration.action("peg-human", args, ItemStack.EMPTY, "test-owner", "Verify guided input");
        });
        test.add(() -> {
            var quote = GameServices.get().currency.quote(base[0].id(), 100, true);
            check(quote.quoteAmount() == 10, "Guided decimal ratio lost its asset scales");
            try
            {
                GameServices.get().currency.quote(base[0].id(), 100, false);
                throw new AssertionError("Disabled sell direction was accepted");
            } catch (IllegalStateException expected)
            {
                check(expected.getMessage().equals("ui.peg.disabled"), "Unexpected sell rejection");
            }
            return GameServices.get().markets.createSpot("Form market " + suffix, base[0].id(), GameServices.get().assets.find("mc_diamond").orElseThrow().id());
        });
        test.add(() -> SymbolUtil.find(((Symbol) test.last).id()).thenApply(Optional::orElseThrow));
        test.add(() -> {
            market[0] = (SymbolView) test.last;
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            var input = AdminInput.collect(gui, "rules", Long.toString(market[0].symbolId()), market[0]);
            test.poll = () -> {
                if (CapitalMC.guiInputManager.isPaused(test.player.getUUID()))
                {
                    test.poll = () -> {
                    };
                    check(!chat(test.player, "1"), "Setting selection was broadcast");
                    check(!chat(test.player, "200"), "Price tick was broadcast");
                }
            };
            return input;
        });
        test.add(() -> {
            @SuppressWarnings("unchecked")
            var input = (Optional<String[]>) test.last;
            String[] args = input.orElseThrow();
            var patch = JsonParser.parseString(args[1]).getAsJsonObject();
            check(patch.size() == 1 && patch.get("priceTick").getAsLong() == 2, "Guided patch changed unrelated fields or used display units as raw units");
            return GameServices.get().administration.action("rules", args, ItemStack.EMPTY, "test-owner", "Verify guided patch");
        });
        test.add(() -> SymbolUtil.find(market[0].symbolId()).thenApply(Optional::orElseThrow));
        test.add(() -> {
            var changed = (SymbolView) test.last;
            check(changed.tradingRules().priceTick() == 2 && changed.tradingRules().quantityStep() == market[0].tradingRules().quantityStep(), "Native rules did not preserve unrelated settings");
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            var input = AdminInput.collect(gui, "asset-units", base[0].name(), null);
            chat(test.player, "100");
            chat(test.player, "cancel");
            return input;
        });
        test.add(() -> {
            check(((Optional<?>) test.last).isEmpty(), "Cancelled form returned a configuration");
            check(GameServices.get().assets.find(base[0].id()).orElseThrow().unitSize().compareTo(base[0].unitSize()) == 0, "Cancelled form changed asset units");
            check(CapitalMC.guiManager.getTradingGui(test.player).isOpen(), "Cancelled form did not restore GUI");
            return CompletableFuture.completedFuture(null);
        });
        test.run();
    }

    @GameTest(maxTicks = 1200)
    public void assetBindingChangeRejectsDepositAndReservesDisabledTemplates(GameTestHelper helper)
    {
        var test = new Steps(helper);
        var selection = new ItemBankService.DepositSelection[1];
        var market = new long[1];
        test.add(() -> GameServices.get().ready());
        test.add(() -> {
            test.player = survivalPlayer(helper);
            return CapitalMC.serverUserManager.registerAsync("Binding Test", "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "", test.player.getUUID());
        });
        test.add(() -> {
            test.user = (ServerUser) test.last;
            CapitalMC.serverUserManager.activateVerified(test.player.getUUID(), test.user);
            return GameServices.get().assets.registerItem("test_binding_guard", "Echo Shard", new ItemStack(Items.ECHO_SHARD));
        });
        test.add(() -> {
            test.diamond = ((McAssetRegistry.Asset) test.last).id();
            return GameServices.get().assets.edit(test.diamond, "Echo Shard", null, null, true)
                                            .thenCompose(unused -> GameServices.get().markets.createSpot("Binding Test Market", test.diamond, GameServices.get().assets.find("mc_gold_ingot").orElseThrow().id()));
        });
        test.add(() -> {
            market[0] = ((Symbol) test.last).id();
            test.player.getInventory().setItem(test.player.getInventory().getSelectedSlot(), new ItemStack(Items.ECHO_SHARD, 4));
            var selected = GameServices.get().items.beginSelection(test.player, test.user, false, Duration.ofSeconds(30));
            test.player.drop(true);
            return selected;
        });
        test.add(() -> {
            selection[0] = (ItemBankService.DepositSelection) test.last;
            var assets = GameServices.get().assets;
            var edit = assets.edit(test.diamond, "Echo Shard", null, null, false);
            check(assets.changing(test.diamond) && assets.match(test.player.getMainHandItem()).isEmpty(), "Pending binding edit accepted physical matching");
            var concurrent = assets.edit(test.diamond, "Other name", null, null, true).handle((unused, error) -> {
                check(error != null, "Concurrent asset edit was accepted");
                return null;
            });
            return CompletableFuture.allOf(edit, concurrent);
        });
        test.add(() -> {
            try
            {
                GameServices.get().items.deposit(test.player, test.user, selection[0]);
                throw new AssertionError("Disabled preview took custody");
            } catch (IllegalArgumentException expected)
            {
                check(test.player.getMainHandItem().getCount() == 4, "Rejected preview removed items");
            }
            GameServices.get().items.cancelSelection(test.player.getUUID());
            try
            {
                GameServices.get().assets.registerItem("test_duplicate_shard", "Duplicate", new ItemStack(Items.ECHO_SHARD));
                throw new AssertionError("Disabled template was reused");
            } catch (IllegalArgumentException expected)
            {
            }
            return GameServices.get().administration.action("resume", new String[]{Long.toString(market[0])}, ItemStack.EMPTY, "test-owner", "Verify disabled market guard")
                                                    .handle((unused, error) -> {
                                                        check(error != null, "Resumed a market containing a disabled asset");
                                                        return null;
                                                    });
        });
        test.add(() -> {
            return GameServices.get().assets.edit(test.diamond, "Echo Shard", null, null, true);
        });
        test.add(() -> GameServices.get().administration.action("halt", new String[]{"0"}, ItemStack.EMPTY, "test-owner", "Test global halt"));
        test.add(() -> GameServices.get().administration.action("resume", new String[]{"0"}, ItemStack.EMPTY, "test-owner", "Test global resume"));
        test.add(() -> BalanceUtil.spotAvailable(test.user, test.diamond));
        test.add(() -> {
            check((Long) test.last == 0, "Changed binding credited a balance");
            return CompletableFuture.completedFuture(null);
        });
        test.run();
    }

    @GameTest(maxTicks = 1200)
    public void chatRetryCancelAndSessionChange(GameTestHelper helper)
    {
        var test = new Steps(helper);
        test.add(() -> GameServices.get().ready());
        test.add(() -> {
            test.player = survivalPlayer(helper);
            return CapitalMC.serverUserManager.registerAsync("Input Test", "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "", test.player.getUUID());
        });
        test.add(() -> {
            test.user = (ServerUser) test.last;
            CapitalMC.serverUserManager.activateVerified(test.player.getUUID(), test.user);
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            gui.open();
            var input = gui.promptInput(Component.literal("Price"), Duration.ofSeconds(5), text -> DecimalUnits.parse(text, 2), value -> {
            });
            check(!chat(test.player, "1.234"), "Invalid input was allowed to broadcast");
            check(!input.isDone() && !gui.isOpen(), "Invalid precision did not stay paused");
            check(!chat(test.player, "1.25"), "Valid input was allowed to broadcast");
            return input;
        });
        test.add(() -> {
            check(((Optional<?>) test.last).orElseThrow().equals(125L), "Decimal input was not converted exactly");
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            check(gui.isOpen(), "Input did not restore cached GUI");
            var input = gui.promptInput(Component.literal("Cancel"), Duration.ofSeconds(5), String::strip, value -> {
            });
            check(!chat(test.player, "cancel"), "Cancellation was allowed to broadcast");
            return input;
        });
        test.add(() -> {
            check(((Optional<?>) test.last).isEmpty(), "Cancelled input produced a value");
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            var input = gui.promptInput(Component.literal("Timeout"), Duration.ofMillis(2), String::strip, value -> {
            });
            return input;
        });
        test.add(() -> {
            check(((Optional<?>) test.last).isEmpty(), "Timed out input produced a value");
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            check(gui.isOpen(), "Timeout did not restore GUI");
            var input = gui.promptInput(Component.literal("Session"), Duration.ofSeconds(5), String::strip, value -> {
            });
            CapitalMC.serverUserManager.logout(test.player.getUUID());
            return input;
        });
        test.add(() -> {
            check(((Optional<?>) test.last).isEmpty(), "Stale session accepted input");
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            check(!gui.isOpen(), "Old session reopened its GUI");
            test.player.level().getServer().getPlayerList().op(new NameAndId(test.player.getGameProfile()), Optional.of(LevelBasedPermissionSet.OWNER), Optional.of(false));
            gui.showPage(PageType.ADMIN);
            gui.open();
            check(test.player.permissions().hasPermission(Permissions.COMMANDS_OWNER), "Test owner permission was not applied");
            for (int tab = 0; tab < 6; tab++)
            {
                click(gui, tab);
                gui.refresh();
                if (tab == 3 && gui.getGuiElement(18) != null)
                {
                    click(gui, 18);
                    gui.refresh();
                    for (int kind = 0; kind < 5; kind++)
                    {
                        click(gui, 6);
                        gui.refresh();
                    }
                }
            }
            return CompletableFuture.completedFuture(null);
        });
        test.run();
    }

    @GameTest(maxTicks = 1200)
    public void batchDepositChatCancellationReleasesSelection(GameTestHelper helper)
    {
        var test = new Steps(helper);
        test.add(() -> GameServices.get().ready());
        test.add(() -> {
            test.player = survivalPlayer(helper);
            return CapitalMC.serverUserManager.registerAsync("Batch Chat", "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "", test.player.getUUID());
        });
        test.add(() -> {
            test.user = (ServerUser) test.last;
            CapitalMC.serverUserManager.activateVerified(test.player.getUUID(), test.user);
            test.player.getInventory().setItem(test.player.getInventory().getSelectedSlot(), new ItemStack(Items.DIAMOND, 8));
            var gui = CapitalMC.guiManager.getTradingGui(test.player);
            gui.showPage(PageType.DEPOSIT_WITHDRAW);
            gui.open();
            click(gui, 20);
            gui.refresh();
            click(gui, 20);
            test.player.drop(true);
            check(test.player.getMainHandItem().getCount() == 8, "Batch preview removed inventory");
            check(!chat(test.player, "done"), "Batch completion broadcast to chat");
            check(CapitalMC.guiInputManager.isPaused(test.player.getUUID()), "Batch confirmation prompt missing");
            check(!chat(test.player, "cancel"), "Batch confirmation cancellation broadcast to chat");
            check(gui.isOpen() && !GameServices.get().items.interceptDrop(test.player, false), "Cancelled confirmation kept blocking drops");
            click(gui, 20);
            check(!chat(test.player, "cancel"), "Batch selection cancellation broadcast to chat");
            check(gui.isOpen() && !GameServices.get().items.interceptDrop(test.player, false), "Cancelled selection kept blocking drops");
            var wait = gui.pauseUntil(Duration.ofSeconds(5), () -> false, () -> {
            });
            check(!chat(test.player, "cancel"), "Condition cancellation broadcast to chat");
            return wait;
        });
        test.add(() -> {
            check(Boolean.FALSE.equals(test.last), "Condition cancellation resumed its action");
            check(CapitalMC.guiManager.getTradingGui(test.player).isOpen(), "Condition cancellation did not restore GUI");
            return CompletableFuture.completedFuture(null);
        });
        test.run();
    }

    private static boolean chat(ServerPlayer player, String text)
    {
        return ServerMessageEvents.ALLOW_CHAT_MESSAGE.invoker().allowChatMessage(
                PlayerChatMessage.unsigned(player.getUUID(), text), player,
                ChatType.bind(ChatType.CHAT, player));
    }

    private static void click(TradingGui gui, int slot)
    {
        var element = gui.getGuiElement(slot);
        check(element != null, "Missing button at " + slot);
        element.getGuiCallback().click(slot, ClickType.MOUSE_LEFT, ContainerInput.PICKUP, gui);
    }

    @GameTest(maxTicks = 2400)
    public void enchantedBookRetainsComponentsAndStacksTo64(GameTestHelper helper)
    {
        var test = new Steps(helper);
        test.add(() -> GameServices.get().ready());
        test.add(() -> {
            test.player = survivalPlayer(helper);
            var pos = helper.absolutePos(new BlockPos(3, 2, 3));
            test.player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            return CapitalMC.serverUserManager.registerAsync("Book Test", "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "", test.player.getUUID());
        });
        test.add(() -> {
            test.user = (ServerUser) test.last;
            CapitalMC.serverUserManager.activateVerified(test.player.getUUID(), test.user);
            var protection = test.player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION);
            var book = EnchantmentHelper.createBook(new EnchantmentInstance(protection, 4));
            return GameServices.get().assets.registerItem("test_protection_4", "Protection IV", book);
        });
        test.add(() -> {
            var asset = (McAssetRegistry.Asset) test.last;
            test.diamond = asset.id();
            test.player.getInventory().setItem(test.player.getInventory().getSelectedSlot(), asset.stack(64));
            var selected = GameServices.get().items.beginSelection(test.player, test.user, false, Duration.ofSeconds(30));
            test.player.drop(true);
            return selected;
        });
        test.add(() -> GameServices.get().items.deposit(test.player, test.user, (ItemBankService.DepositSelection) test.last));
        test.add(() -> BalanceUtil.spotAvailable(test.user, test.diamond));
        test.add(() -> {
            check((Long) test.last == 64, "Book deposit amount differs");
            return GameServices.get().items.withdraw(test.player, test.user, test.diamond, 64, ItemBankService.Output.ITEMS);
        });
        test.add(() -> {
            var asset = GameServices.get().assets.find(test.diamond).orElseThrow();
            var delivered = test.player.level().getEntitiesOfClass(ItemEntity.class, test.player.getBoundingBox().inflate(4))
                                       .stream().map(ItemEntity::getItem).filter(item -> item.getCount() == 64 && McAssetRegistry.matches(asset.prototype(), item)).findFirst()
                                       .orElse(test.player.getMainHandItem());
            check(delivered.getCount() == 64 && delivered.getMaxStackSize() == 64 && McAssetRegistry.matches(asset.prototype(), delivered), "Book enchantment or 64 stack was lost");
            return BalanceUtil.spotAvailable(test.user, test.diamond);
        });
        test.add(() -> {
            check((Long) test.last == 0, "Book withdrawal was not debited");
            return CompletableFuture.completedFuture(null);
        });
        test.run();
    }

    @GameTest(maxTicks = 12000)
    public void physicalCustodyAndGui(GameTestHelper helper)
    {
        var test = new Steps(helper);
        test.add(() -> GameServices.get().ready());
        test.add(() -> {
            test.player = survivalPlayer(helper);
            test.player.setGameMode(GameType.SURVIVAL);
            var pos = helper.absolutePos(new BlockPos(3, 2, 3));
            test.player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            test.player.setYRot(0);
            return CapitalMC.serverUserManager.registerAsync("Custody Test", "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "", test.player.getUUID());
        });
        test.add(() -> {
            test.user = (ServerUser) test.last;
            CapitalMC.serverUserManager.activateVerified(test.player.getUUID(), test.user);
            test.diamond = GameServices.get().assets.find("mc_diamond").orElseThrow().id();
            test.gold = GameServices.get().assets.find("mc_gold_ingot").orElseThrow().id();
            test.player.getInventory().setItem(test.player.getInventory().getSelectedSlot(), new ItemStack(Items.DIAMOND, 8));
            var selected = GameServices.get().items.beginSelection(test.player, test.user, false, Duration.ofSeconds(30));
            test.player.drop(true); // invokes the actual mixin before inventory removal
            check(test.player.getMainHandItem().getCount() == 8, "Drop preview removed items");
            test.player.drop(true);
            return selected;
        });
        test.add(() -> GameServices.get().items.deposit(test.player, test.user, (ItemBankService.DepositSelection) test.last));
        test.add(() -> {
            check(test.player.getMainHandItem().isEmpty(), "Confirmed drop was not removed");
            return BalanceUtil.spotAvailable(test.user, test.diamond);
        });
        test.add(() -> {
            check((Long) test.last == 8, "Drop balance differs or duplicate packet credited twice");
            for (var kind : List.of(Blocks.CHEST, Blocks.SHULKER_BOX))
            {
                // Each cycle deposits a two-asset basket, consumes its container and reuses eligibility.
                test.addContainerCycle(kind);
            }
            test.addContainerCycle(Blocks.CHEST, true);
            test.add(() -> GameServices.get().items.withdraw(test.player, test.user, test.diamond, 2, ItemBankService.Output.ITEMS));
            test.add(() -> {
                var entities = test.player.level().getEntitiesOfClass(ItemEntity.class, test.player.getBoundingBox().inflate(4));
                check(entities.stream().anyMatch(e -> e.getItem().is(Items.DIAMOND) && e.getItem().getCount() == 2 && e.entityTags().stream().anyMatch(tag -> tag.startsWith("capitalmc:"))), "Entity withdrawal was not delivered");
                return BalanceUtil.spotAvailable(test.user, test.diamond);
            });
            test.add(() -> {
                check((Long) test.last == 6, "Entity withdrawal did not debit balance");
                return CompletableFuture.completedFuture(null);
            });
            test.add(() -> {
                var gui = CapitalMC.guiManager.getTradingGui(test.player);
                gui.open();
                return gui.pauseUntil(Duration.ofSeconds(2), () -> true, () -> {
                });
            });
            test.add(() -> {
                check((Boolean) test.last, "GUI condition did not resume");
                check(CapitalMC.guiManager.getTradingGui(test.player).isOpen(), "Paused GUI was not reopened");
                return CompletableFuture.completedFuture(null);
            });
            return CompletableFuture.completedFuture(null);
        });
        test.run();
    }

    @GameTest(maxTicks = 1200)
    public void changedSelectionIsRejected(GameTestHelper helper)
    {
        var test = new Steps(helper);
        test.add(() -> GameServices.get().ready());
        test.add(() -> {
            test.player = survivalPlayer(helper);
            test.player.setGameMode(GameType.SURVIVAL);
            return CapitalMC.serverUserManager.registerAsync("Preview Test", "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16), "", test.player.getUUID());
        });
        test.add(() -> {
            test.user = (ServerUser) test.last;
            CapitalMC.serverUserManager.activateVerified(test.player.getUUID(), test.user);
            test.player.getInventory().setItem(test.player.getInventory().getSelectedSlot(), new ItemStack(Items.DIAMOND, 4));
            var selected = GameServices.get().items.beginSelection(test.player, test.user, false, Duration.ofSeconds(30));
            test.player.drop(false);
            return selected;
        });
        test.add(() -> {
            var selected = (ItemBankService.DepositSelection) test.last;
            test.player.getMainHandItem().shrink(1);
            try
            {
                GameServices.get().items.deposit(test.player, test.user, selected);
                throw new AssertionError("Changed selection accepted");
            } catch (IllegalStateException expected)
            {
                check(test.player.getMainHandItem().getCount() == 3, "Rejected selection took custody");
            }
            GameServices.get().items.cancelSelection(test.player.getUUID());
            long asset = GameServices.get().assets.find("mc_diamond").orElseThrow().id();
            return BalanceUtil.spotAvailable(test.user, asset);
        });
        test.add(() -> {
            check((Long) test.last == 0, "Rejected preview credited money");
            return CompletableFuture.completedFuture(null);
        });
        test.run();
    }

    private static ServerPlayer survivalPlayer(GameTestHelper helper)
    {
        var profile = new GameProfile(UUID.randomUUID(), "test_" + UUID.randomUUID().toString().substring(0, 8));
        var server = helper.getLevel().getServer();
        var player = new MessagePlayer(server, helper.getLevel(), profile);
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class MessagePlayer extends ServerPlayer
    {
        final List<String> messages = new ArrayList<>();

        MessagePlayer(MinecraftServer server, ServerLevel level, GameProfile profile)
        {
            super(server, level, profile, ClientInformation.createDefault());
        }

        @Override
        public void sendSystemMessage(Component message)
        {
            if (messages != null)
            {
                messages.add(message.getString());
            }
            super.sendSystemMessage(message);
        }
    }

    private static final class Steps
    {
        final GameTestHelper helper;
        final ArrayDeque<Supplier<CompletableFuture<?>>> steps = new ArrayDeque<>();
        boolean finished;
        CompletableFuture<?> pending;
        Object last;
        int step, pendingTicks;
        ServerPlayer player;
        ServerUser user;
        long diamond, gold;
        Runnable poll = () -> {
        };

        Steps(GameTestHelper helper)
        {
            this.helper = helper;
        }

        void add(Supplier<CompletableFuture<?>> work)
        {
            steps.add(work);
        }

        void addContainerCycle(Block block)
        {
            addContainerCycle(block, false);
        }

        void addContainerCycle(Block block, boolean large)
        {
            add(() -> {
                var gui = CapitalMC.guiManager.getTradingGui(player);
                for (var page : PageType.values())
                {
                    gui.showPage(page);
                    gui.refresh();
                }
                gui.showPage(PageType.MY_ACCOUNT);
                gui.open();
                gui.refresh();
                gui.close();
                check(CapitalMC.guiManager.getTradingGui(player) == gui, "GUI was recreated");
                var pos = player.blockPosition().relative(player.getDirection());
                if (large)
                {
                    var first = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT);
                    player.level().setBlockAndUpdate(pos, first);
                    player.level().setBlockAndUpdate(ChestBlock.getConnectedBlockPos(pos, first), first.setValue(ChestBlock.TYPE, ChestType.RIGHT));
                }
                else
                {
                    player.level().setBlockAndUpdate(pos, block.defaultBlockState());
                }
                var container = (Container) player.level().getBlockEntity(pos);
                container.setItem(0, new ItemStack(Items.DIAMOND, 6));
                container.setItem(1, new ItemStack(Items.GOLD_INGOT, 3));
                ((BlockEntity) container).setChanged();
                var selected = GameServices.get().items.beginSelection(player, user, true, Duration.ofSeconds(30));
                UseBlockCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                return selected;
            });
            add(() -> GameServices.get().items.deposit(player, user, (ItemBankService.DepositSelection) last));
            add(() -> {
                var pos = player.blockPosition().relative(player.getDirection());
                check(player.level().getBlockState(pos).isAir(), "Deposited container remains");
                return BalanceUtil.spotAvailable(user, gold);
            });
            add(() -> {
                long expected = large ? 9 : block == Blocks.CHEST ? 3 : 6;
                check((Long) last == expected, "Basket gold credit differs");
                return GameServices.get().items.withdraw(player, user, diamond, 6, large ? ItemBankService.Output.LARGE_CHEST : block == Blocks.CHEST ? ItemBankService.Output.SMALL_CHEST : ItemBankService.Output.SHULKER);
            });
            add(() -> {
                var pos = player.blockPosition().relative(player.getDirection());
                var container = (Container) player.level().getBlockEntity(pos);
                check(container != null && container.getItem(0).is(Items.DIAMOND) && container.getItem(0).getCount() == 6, "Withdrawal container contents differ");
                var kind = block == Blocks.CHEST ? ItemBankService.ContainerKind.CHEST : ItemBankService.ContainerKind.SHULKER;
                check(GameServices.get().items.containerCredit(user, kind) == 0, "Withdrawal did not consume container eligibility");
                if (large)
                {
                    var neighbor = ChestBlock.getConnectedBlockPos(pos, player.level().getBlockState(pos));
                    var other = (Container) player.level().getBlockEntity(neighbor);
                    check(other != null, "Large chest has no second half");
                    other.clearContent();
                    player.level().removeBlock(neighbor, false);
                }
                container.clearContent();
                player.level().removeBlock(pos, false);
                return BalanceUtil.spotAvailable(user, diamond);
            });
            add(() -> {
                check((Long) last == 8, "Withdrawal balance differs");
                return CompletableFuture.completedFuture(null);
            });
        }

        void run()
        {
            helper.onEachTick(() -> {
                if (finished)
                {
                    return;
                }
                try
                {
                    poll.run();
                    if (pending != null)
                    {
                        if (!pending.isDone())
                        {
                            if (++pendingTicks == 500)
                            {
                                CapitalMC.logger.warn("GameTest player {} waiting at step {}, {} steps remain", player == null ? "none" : player.getGameProfile().name(), step, steps.size());
                            }
                            return;
                        }
                        pendingTicks = 0;
                        last = pending.getNow(null);
                        pending = null;
                    }
                    if (steps.isEmpty())
                    {
                        if (player != null)
                        {
                            CapitalMC.guiManager.removePlayer(player);
                            CapitalMC.serverUserManager.logout(player.getUUID());
                        }
                        finished = true;
                        helper.succeed();
                        return;
                    }
                    step++;
                    pending = steps.removeFirst().get();
                } catch (Throwable error)
                {
                    finished = true;
                    CapitalMC.logger.error("CapitalMC game test failed", error);
                    helper.fail(error.toString());
                }
            });
        }
    }
}
