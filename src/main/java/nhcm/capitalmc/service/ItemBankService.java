package nhcm.capitalmc.service;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.*;
import nhcm.advmatch.BusinessException;
import nhcm.advmatch.model.AccountType;
import nhcm.advmatch.model.BalanceDelta;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.exchange.ExchangeMessages;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Main-thread physical selection; durable metadata precedes every irreversible custody step.
 */
public class ItemBankService
{
    public enum Output
    {
        ITEMS, SMALL_CHEST, LARGE_CHEST, SHULKER
    }

    public enum ContainerKind
    {
        CHEST, SHULKER
    }

    public enum Phase
    {
        CUSTODY_INTENT, ESCROWED, EXCHANGE_CREDITING, EXCHANGE_DEBITTING, DEBITED,
        DELIVERY_INTENT, REFUND_INTENT, COMPLETE, REJECTED, REVERSED, IN_DOUBT, RECONCILED;

        public boolean terminal()
        {
            return this == COMPLETE || this == REJECTED || this == REVERSED || this == RECONCILED;
        }
    }

    public static class DepositSelection
    {
        private final UUID session, player, user;
        private final ServerLevel level;
        private final int slot, count, chests, shulkers;
        private final ItemStack inventoryStack;
        private final List<BlockPos> positions;
        private final List<BlockState> blocks;
        private final List<List<ItemStack>> contents;
        private final Map<Long, Long> amounts;
        private final JsonObject recovery;
        private Map<Integer, InventoryChoice> inventory = Map.of();
        private boolean containerCreditsOnly;
        private int experiencePoints;
        private int experienceLevel, totalExperience;
        private float experienceProgress;

        private DepositSelection(Session session, ServerPlayer player, int slot, int count, ItemStack stack,
                                 List<BlockPos> positions, List<BlockState> blocks, List<List<ItemStack>> contents,
                                 Map<Long, Long> amounts, int chests, int shulkers, JsonObject recovery)
        {
            this.session = session.id;
            this.player = player.getUUID();
            this.user = session.user.getUuid();
            this.level = player.level();
            this.slot = slot;
            this.count = count;
            this.inventoryStack = stack.copy();
            if (slot >= 0)
            {
                this.inventory = Map.of(slot, new InventoryChoice(count, stack.copy()));
            }
            this.positions = List.copyOf(positions);
            this.blocks = List.copyOf(blocks);
            this.contents = contents;
            this.amounts = Map.copyOf(amounts);
            this.chests = chests;
            this.shulkers = shulkers;
            this.recovery = recovery;
        }

        public Map<Long, Long> amounts()
        {
            return amounts;
        }

        public int chests()
        {
            return chests;
        }

        public int shulkers()
        {
            return shulkers;
        }
    }

    private record InventoryChoice(int count, ItemStack stack)
    {
    }

    private static class Session
    {
        final UUID id = UUID.randomUUID();
        final ServerUser user;
        final boolean container;
        final long started = System.currentTimeMillis();
        final long expires;
        final CompletableFuture<DepositSelection> selected = new CompletableFuture<>();
        final long startedNanos = System.nanoTime(), timeoutNanos;
        final long sessionVersion;
        DepositSelection selection;
        boolean consuming, batch;
        ServerLevel level;
        final Map<Integer, DepositSelection> drops = new LinkedHashMap<>();
        final List<DepositSelection> containers = new ArrayList<>();

        Session(ServerUser user, boolean container, Duration timeout, long sessionVersion)
        {
            this.sessionVersion = sessionVersion;
            this.user = user;
            this.container = container;
            timeoutNanos = timeout.toNanos();
            expires = Math.addExact(started, timeout.toMillis());
        }

        boolean expired()
        {
            return System.nanoTime() - startedNanos >= timeoutNanos;
        }
    }

    private final GameServices services;
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Session> sessions = new HashMap<>(); // server thread only
    private boolean checkingPermission;

    public ItemBankService(GameServices services)
    {
        this.services = services;
    }

    public void registerEvents()
    {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (checkingPermission || !(player instanceof ServerPlayer serverPlayer))
            {
                return InteractionResult.PASS;
            }
            Session session = sessions.get(player.getUUID());
            if (session == null || !session.container)
            {
                return InteractionResult.PASS;
            }
            if (session.consuming)
            {
                return InteractionResult.PASS;
            }
            if (session.selection != null)
            {
                return InteractionResult.FAIL;
            }
            try
            {
                validateSession(serverPlayer, session);
                var choice = containerSelection(serverPlayer, session, hit.getBlockPos());
                if (session.batch)
                {
                    if (session.containers.stream().anyMatch(old -> old.positions.stream().anyMatch(choice.positions::contains)))
                    {
                        throw new IllegalArgumentException("ui.deposit.duplicate_container");
                    }
                    if (session.containers.size() >= 64)
                    {
                        throw new IllegalArgumentException("ui.deposit.batch_limit");
                    }
                    session.containers.add(choice);
                    services.message(serverPlayer, "ui.deposit.batch_added", session.containers.size());
                }
                else
                {
                    session.selection = choice;
                    session.selected.complete(choice);
                }
            } catch (RuntimeException error)
            {
                if (session.batch)
                {
                    services.message(serverPlayer, "ui.error", ExchangeMessages.error(serverPlayer, error));
                }
                else
                {
                    sessions.remove(player.getUUID(), session);
                    session.selected.completeExceptionally(error);
                }
            }
            return InteractionResult.FAIL;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (UUID player : List.copyOf(sessions.keySet()))
            {
                Session session = sessions.get(player);
                ServerPlayer online = server.getPlayerList().getPlayer(player);
                if (session == null)
                {
                    continue;
                }
                if (!session.consuming && (online == null || session.expired() || session.sessionVersion != CapitalMC.serverUserManager.getSessionVersion(player)
                                           || online.level() != session.level
                                           || CapitalMC.serverUserManager.getLoggedInUser(player).filter(user -> user.getUuid().equals(session.user.getUuid())).isEmpty()))
                {
                    cancelSelection(player);
                }
            }
        });
    }

    public CompletableFuture<Void> initialize()
    {
        return services.state.updateAsync(ItemBankService::recoverMetadata);
    }

    static void recoverMetadata(JsonObject root)
    {
        if (!root.has("itemOperations"))
        {
            return;
        }
        for (var entry : root.getAsJsonObject("itemOperations").entrySet())
        {
            JsonObject op = entry.getValue().getAsJsonObject();
            if (!terminal(op) && !op.get("phase").getAsString().equals("IN_DOUBT"))
            {
                op.addProperty("failurePhase", op.get("phase").getAsString());
                op.addProperty("phase", "IN_DOUBT");
                op.addProperty("failure", "Interrupted by server restart; verify both stores before recovery");
            }
        }
    }

    public CompletableFuture<DepositSelection> beginSelection(ServerPlayer player, ServerUser user, boolean container, Duration timeout)
    {
        requireSession(player, user);
        if (timeout.isNegative() || timeout.isZero())
        {
            throw new IllegalArgumentException("Positive timeout required");
        }
        if (sessions.containsKey(player.getUUID()) || sessions.values().stream().anyMatch(session -> session.user.getUuid().equals(user.getUuid()))
            || busy.contains(user.getUuid()))
        {
            throw new IllegalStateException("ui.operation.busy");
        }
        if (unresolved(user.getUuid()))
        {
            throw new IllegalStateException("ui.operation.review");
        }
        Session session = new Session(user, container, timeout, CapitalMC.serverUserManager.getSessionVersion(player.getUUID()));
        session.level = player.level();
        sessions.put(player.getUUID(), session);
        return session.selected.copy();
    }

    public static int experienceAvailable(ServerPlayer player)
    {
        long level = Math.max(0, player.experienceLevel);
        if (level > 21863)
        {
            return Integer.MAX_VALUE;
        }
        long base = level <= 16 ? level * level + 6 * level
                : level <= 31 ? (5 * level * level - 81 * level + 720) / 2
                : (9 * level * level - 325 * level + 4440) / 2;
        long progress = Math.round(player.experienceProgress * player.getXpNeededForNextLevel());
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(0, base + progress)));
    }

    public DepositSelection prepareExperience(ServerPlayer player, ServerUser user, int points)
    {
        var asset = services.assets.find("mc_exp").orElseThrow();
        if (!asset.electronic() || !services.assets.enabled(asset.id()) || points < asset.minimumDeposit()
            || points > experienceAvailable(player))
        {
            throw new IllegalArgumentException("ui.deposit.experience_amount");
        }
        beginSelection(player, user, false, Duration.ofSeconds(90));
        var session = sessions.get(player.getUUID());
        var recovery = new JsonObject();
        recovery.addProperty("experiencePoints", points);
        recovery.addProperty("experienceLevelBefore", player.experienceLevel);
        recovery.addProperty("experienceProgressBefore", player.experienceProgress);
        recovery.addProperty("totalExperienceBefore", player.totalExperience);
        var selection = new DepositSelection(session, player, -1, 0, ItemStack.EMPTY, List.of(), List.of(), List.of(),
                                             Map.of(asset.id(), (long) points), 0, 0, recovery);
        selection.experiencePoints = points;
        selection.experienceLevel = player.experienceLevel;
        selection.experienceProgress = player.experienceProgress;
        selection.totalExperience = player.totalExperience;
        session.selection = selection;
        return selection;
    }

    public DepositSelection prepareContainerCredits(ServerPlayer player, ServerUser user, ContainerKind kind, int amount)
    {
        int slot = player.getInventory().getSelectedSlot();
        var stack = player.getInventory().getItem(slot);
        boolean matches = kind == ContainerKind.CHEST ? stack.is(Items.CHEST) || stack.is(Items.TRAPPED_CHEST)
                : Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock;
        if (!matches || amount <= 0 || amount > stack.getCount()
            || stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItemCopyStream().findAny().isPresent())
        {
            throw new IllegalArgumentException("ui.deposit.credits_empty");
        }
        beginSelection(player, user, false, Duration.ofSeconds(90));
        var session = sessions.get(player.getUUID());
        var recovery = new JsonObject();
        recovery.addProperty("containerCreditsOnly", true);
        recovery.addProperty("slot", slot);
        recovery.addProperty("selectedCount", amount);
        recovery.add("stack", encode(stack));
        var selection = new DepositSelection(session, player, slot, amount, stack, List.of(), List.of(), List.of(), Map.of(),
                                             kind == ContainerKind.CHEST ? amount : 0, kind == ContainerKind.SHULKER ? amount : 0, recovery);
        selection.containerCreditsOnly = true;
        session.selection = selection;
        return selection;
    }

    public CompletableFuture<DepositSelection> beginBatchSelection(ServerPlayer player, ServerUser user, boolean container, Duration timeout)
    {
        var result = beginSelection(player, user, container, timeout);
        sessions.get(player.getUUID()).batch = true;
        return result;
    }

    public DepositSelection finishBatchSelection(ServerPlayer player)
    {
        Session session = sessions.get(player.getUUID());
        if (session == null || !session.batch || session.consuming)
        {
            throw new IllegalStateException("ui.session.changed");
        }
        validateSession(player, session);
        if (session.selection != null)
        {
            return session.selection;
        }
        var parts = new ArrayList<DepositSelection>(session.drops.values());
        parts.addAll(session.containers);
        if (parts.isEmpty())
        {
            throw new IllegalArgumentException("ui.deposit.no_items");
        }
        var positions = new ArrayList<BlockPos>();
        var blocks = new ArrayList<BlockState>();
        var contents = new ArrayList<List<ItemStack>>();
        var amounts = new LinkedHashMap<Long, Long>();
        var inventory = new LinkedHashMap<Integer, InventoryChoice>();
        var recovery = new JsonObject();
        var dropSnapshots = new JsonArray();
        var containerSnapshots = new JsonArray();
        int chests = 0, shulkers = 0;
        for (var part : parts)
        {
            positions.addAll(part.positions);
            blocks.addAll(part.blocks);
            contents.addAll(part.contents);
            inventory.putAll(part.inventory);
            part.amounts.forEach((id, count) -> amounts.merge(id, count, Math::addExact));
            chests = Math.addExact(chests, part.chests);
            shulkers = Math.addExact(shulkers, part.shulkers);
            if (!part.inventory.isEmpty())
            {
                dropSnapshots.add(part.recovery.deepCopy());
            }
            if (part.recovery.has("containers"))
            {
                for (var snapshot : part.recovery.getAsJsonArray("containers"))
                    containerSnapshots.add(snapshot.deepCopy());
            }
        }
        recovery.add("inventory", dropSnapshots);
        recovery.add("containers", containerSnapshots);
        validateDepositMinimum(amounts);
        var choice = new DepositSelection(session, player, -1, 0, ItemStack.EMPTY, positions, blocks, List.copyOf(contents), amounts, chests, shulkers, recovery);
        choice.inventory = Map.copyOf(inventory);
        session.selection = choice;
        session.selected.complete(choice);
        return choice;
    }

    public void cancelSelection(UUID player)
    {
        Session session = sessions.get(player);
        if (session == null || session.consuming)
        {
            return;
        }
        sessions.remove(player);
        session.selected.completeExceptionally(new CancellationException("Deposit selection cancelled"));
    }

    public void clearSelections()
    {
        for (UUID player : List.copyOf(sessions.keySet()))
            cancelSelection(player);
    }

    /**
     * Called at ServerPlayer.drop(boolean) HEAD, before Inventory.removeFromSelected or ItemEntity creation.
     */
    public boolean interceptDrop(ServerPlayer player, boolean entireStack)
    {
        Session session = sessions.get(player.getUUID());
        if (session == null || session.container || session.consuming)
        {
            return false;
        }
        try
        {
            if (session.selection == null && !session.consuming)
            {
                validateSession(player, session);
                ItemStack stack = player.getInventory().getSelectedItem();
                if (stack.isEmpty())
                {
                    throw new IllegalArgumentException("ui.deposit.no_items");
                }
                int slot = player.getInventory().getSelectedSlot();
                var previous = session.drops.get(slot);
                if (previous != null && !ItemStack.matches(previous.inventoryStack, stack))
                {
                    throw new IllegalArgumentException("ui.deposit.changed");
                }
                int count = entireStack ? stack.getCount() : Math.min(stack.getCount(), previous == null ? 1 : previous.count + 1);
                var amounts = new LinkedHashMap<Long, Long>();
                collect(stack.copyWithCount(count), amounts);
                JsonObject recovery = new JsonObject();
                recovery.addProperty("slot", player.getInventory().getSelectedSlot());
                recovery.add("stack", encode(stack));
                recovery.addProperty("selectedCount", count);
                var choice = new DepositSelection(session, player, slot, count, stack, List.of(), List.of(), List.of(), amounts, 0, 0, recovery);
                if (session.batch)
                {
                    session.drops.put(slot, choice);
                    services.message(player, "ui.deposit.batch_drop", session.drops.size(), count);
                }
                else
                {
                    session.selection = choice;
                    session.selected.complete(choice);
                }
            }
        } catch (RuntimeException error)
        {
            if (session.batch)
            {
                services.message(player, "ui.error", ExchangeMessages.error(player, error));
            }
            else
            {
                sessions.remove(player.getUUID(), session);
                session.selected.completeExceptionally(error);
            }
        }
        player.inventoryMenu.broadcastFullState();
        return true; // duplicate packets cannot select, remove or credit the same inventory twice
    }

    private DepositSelection containerSelection(ServerPlayer player, Session session, BlockPos selected)
    {
        BlockPos pos = selected.immutable();
        checkPosition(player, pos);
        BlockState state = player.level().getBlockState(pos);
        boolean shulker = state.getBlock() instanceof ShulkerBoxBlock;
        if (!shulker && !(state.getBlock() instanceof ChestBlock))
        {
            throw new IllegalArgumentException("ui.deposit.look_container");
        }
        List<BlockPos> positions = new ArrayList<>(List.of(pos));
        if (!shulker && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
        {
            positions.add(ChestBlock.getConnectedBlockPos(pos, state));
        }
        List<BlockState> blocks = new ArrayList<>();
        List<List<ItemStack>> contents = new ArrayList<>();
        Map<Long, Long> amounts = new LinkedHashMap<>();
        JsonArray snapshots = new JsonArray();
        for (BlockPos position : positions)
        {
            checkPosition(player, position);
            BlockEntity entity = player.level().getBlockEntity(position);
            if (!(entity instanceof Container container))
            {
                throw new IllegalArgumentException("ui.deposit.look_container");
            }
            if (entity instanceof BaseContainerBlockEntity locked && !locked.canOpen(player))
            {
                throw new IllegalArgumentException("ui.container.denied");
            }
            requireClosed(container);
            List<ItemStack> stacks = new ArrayList<>();
            JsonArray items = new JsonArray();
            for (int slot = 0; slot < container.getContainerSize(); slot++)
            {
                ItemStack stack = container.getItem(slot).copy();
                collect(stack, amounts);
                stacks.add(stack);
                items.add(encode(stack));
            }
            blocks.add(player.level().getBlockState(position));
            contents.add(List.copyOf(stacks));
            JsonObject snapshot = new JsonObject();
            snapshot.add("items", items);
            snapshot.add("block", BlockState.CODEC.encodeStart(JsonOps.INSTANCE, blocks.getLast()).getOrThrow());
            snapshot.addProperty("blockEntity", entity.saveWithFullMetadata(services.server.registryAccess()).toString());
            snapshots.add(snapshot);
        }
        JsonObject recovery = new JsonObject();
        recovery.add("containers", snapshots);
        return new DepositSelection(session, player, -1, 0, ItemStack.EMPTY, positions, blocks, List.copyOf(contents), amounts,
                                    shulker ? 0 : positions.size(), shulker ? 1 : 0, recovery);
    }

    private void requireClosed(Container container)
    {
        if (container instanceof ShulkerBoxBlockEntity shulker && !shulker.isClosed())
        {
            throw new IllegalStateException("ui.deposit.container_open");
        }
        for (ServerPlayer viewer : services.server.getPlayerList().getPlayers())
        {
            if (viewer.containerMenu == viewer.inventoryMenu)
            {
                continue;
            }
            if (viewer.containerMenu.slots.stream().anyMatch(slot -> slot.container == container
                                                                     || slot.container instanceof CompoundContainer combined && combined.contains(container)))
            {
                throw new IllegalStateException("ui.deposit.container_open");
            }
        }
    }

    private JsonElement encode(ItemStack stack)
    {
        return ItemStack.OPTIONAL_CODEC.encodeStart(services.server.registryAccess().createSerializationContext(JsonOps.INSTANCE), stack).getOrThrow();
    }

    private void validateSession(ServerPlayer player, Session session)
    {
        requireSession(player, session.user);
        if (player.level() != session.level)
        {
            throw new IllegalStateException("ui.deposit.changed");
        }
        if (session.sessionVersion != CapitalMC.serverUserManager.getSessionVersion(player.getUUID()))
        {
            throw new IllegalStateException("ui.session.changed");
        }
        if (session.expired())
        {
            throw new IllegalStateException("ui.deposit.expired");
        }
    }

    private void revalidate(ServerPlayer player, ServerUser user, DepositSelection selection)
    {
        Session session = sessions.get(player.getUUID());
        if (session == null || session.selection != selection || !session.id.equals(selection.session)
            || !selection.player.equals(player.getUUID()) || !selection.user.equals(user.getUuid()))
        {
            throw new IllegalStateException("ui.session.changed");
        }
        validateSession(player, session);
        if (player.level() != selection.level)
        {
            throw new IllegalStateException("ui.deposit.changed");
        }
        for (var entry : selection.inventory.entrySet())
            if (!ItemStack.matches(player.getInventory().getItem(entry.getKey()), entry.getValue().stack()))
            {
                throw new IllegalStateException("ui.deposit.changed");
            }
        for (int i = 0; i < selection.positions.size(); i++)
        {
            BlockPos position = selection.positions.get(i);
            checkPosition(player, position);
            if (!player.level().getBlockState(position).equals(selection.blocks.get(i)))
            {
                throw new IllegalStateException("ui.deposit.changed");
            }
            if (!(player.level().getBlockEntity(position) instanceof Container container))
            {
                throw new IllegalStateException("ui.deposit.changed");
            }
            if (container instanceof BaseContainerBlockEntity locked && !locked.canOpen(player))
            {
                throw new IllegalArgumentException("ui.container.denied");
            }
            requireClosed(container);
            if (container.getContainerSize() != selection.contents.get(i).size())
            {
                throw new IllegalStateException("ui.deposit.changed");
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++)
                if (!ItemStack.matches(container.getItem(slot), selection.contents.get(i).get(slot)))
                {
                    throw new IllegalStateException("ui.deposit.changed");
                }
        }
        if (selection.containerCreditsOnly)
        {
            return;
        }
        if (selection.experiencePoints > 0)
        {
            var asset = services.assets.find("mc_exp").orElseThrow();
            if (player.experienceLevel != selection.experienceLevel || player.experienceProgress != selection.experienceProgress
                || player.totalExperience != selection.totalExperience || !services.assets.enabled(asset.id()) || !asset.electronic()
                || selection.experiencePoints < asset.minimumDeposit() || selection.experiencePoints > experienceAvailable(player)
                || !selection.amounts.equals(Map.of(asset.id(), (long) selection.experiencePoints)))
            {
                throw new IllegalStateException("ui.deposit.changed");
            }
            return;
        }
        // An unchanged inventory is insufficient when its registered asset binding has changed.
        var currentAmounts = new LinkedHashMap<Long, Long>();
        for (var entry : selection.inventory.values())
            collect(entry.stack().copyWithCount(entry.count()), currentAmounts);
        for (var contents : selection.contents)
            for (var stack : contents)
                collect(stack, currentAmounts);
        validateDepositMinimum(currentAmounts);
        if (!currentAmounts.equals(selection.amounts))
        {
            throw new IllegalStateException("ui.deposit.changed");
        }
    }

    public CompletableFuture<Void> deposit(ServerPlayer player, ServerUser user, DepositSelection selection)
    {
        try
        {
            revalidate(player, user, selection);
        } catch (RuntimeException error)
        {
            Session failed = sessions.get(player.getUUID());
            if (failed != null && failed.id.equals(selection.session) && !failed.consuming)
            {
                cancelSelection(player.getUUID());
            }
            throw error;
        }
        Session session = sessions.get(player.getUUID());
        if (session.consuming)
        {
            return CompletableFuture.failedFuture(new IllegalStateException("ui.operation.busy"));
        }
        session.consuming = true;
        return exclusive(user, () -> services.settlements.account(user, AccountType.Spot).thenCompose(account -> services.onServer(() -> {
                                                 try
                                                 {
                                                     revalidate(player, user, selection);
                                                 } catch (RuntimeException changed)
                                                 {
                                                     throw new BeforeCustody(changed);
                                                 }
                                                 return operationData(user, "DEPOSIT", selection.amounts, selection.chests, selection.shulkers, player, account,
                                                                      selection.positions, Phase.CUSTODY_INTENT, selection.recovery, selection.session);
                                             }).thenCompose(data -> record(selection.session, data).thenCompose(unused -> services.onServer(() -> {
                                                                                                       try
                                                                                                       {
                                                                                                           revalidate(player, user, selection);
                                                                                                       } catch (RuntimeException changed)
                                                                                                       {
                                                                                                           throw new BeforeCustody(changed);
                                                                                                       }
                                                                                                       for (var entry : selection.inventory.entrySet())
                                                                                                           player.getInventory().getItem(entry.getKey()).shrink(entry.getValue().count());
                                                                                                       for (BlockPos position : selection.positions)
                                                                                                       {
                                                                                                           ((Container) player.level().getBlockEntity(position)).clearContent();
                                                                                                           if (!player.level().removeBlock(position, false))
                                                                                                           {
                                                                                                               throw new IllegalStateException("Container removal failed");
                                                                                                           }
                                                                                                       }
                                                                                                       if (selection.experiencePoints > 0)
                                                                                                       {
                                                                                                           player.giveExperiencePoints(-selection.experiencePoints);
                                                                                                       }
                                                                                                       player.inventoryMenu.broadcastFullState();
                                                                                                       saveWorld();
                                                                                                       return null;
                                                                                                   })).thenCompose(unused -> phase(selection.session, Phase.ESCROWED))
                                                                                                   .thenCompose(unused -> credit(selection.session, account, selection))))
                                                         .exceptionallyCompose(error -> services.state.updateAsync(root -> {
                                                                                                    if (beforeCustody(error) && root.has("itemOperations") && root.getAsJsonObject("itemOperations").has(selection.session.toString()))
                                                                                                    {
                                                                                                        operation(root, selection.session).addProperty("phase", Phase.REJECTED.name());
                                                                                                    }
                                                                                                    else
                                                                                                    {
                                                                                                        doubt(root, selection.session, error);
                                                                                                    }
                                                                                                })
                                                                                                      .thenCompose(unused -> CompletableFuture.failedFuture(error))))
                .handle((unused, error) -> services.onServer(() -> {
                    sessions.remove(player.getUUID(), session);
                    if (error != null)
                    {
                        throw new CompletionException(error);
                    }
                    return (Void) null;
                })).thenCompose(completed -> completed);
    }

    private void validateDepositMinimum(Map<Long, Long> amounts)
    {
        for (var amount : amounts.entrySet())
        {
            if (amount.getValue() < services.assets.find(amount.getKey()).orElseThrow().minimumDeposit())
            {
                throw new IllegalArgumentException("ui.deposit.minimum");
            }
        }
    }

    private void collect(ItemStack stack, Map<Long, Long> amounts)
    {
        if (stack.isEmpty())
        {
            return;
        }
        McAssetRegistry.validateDepositStack(stack);
        var asset = services.assets.match(stack).orElseThrow(() -> new IllegalArgumentException("ui.deposit.unregistered"));
        amounts.merge(asset.id(), (long) stack.getCount(), Math::addExact);
    }

    private CompletableFuture<Void> credit(UUID id, long account, DepositSelection selection)
    {
        var legs = selection.amounts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                                    .map(entry -> new BalanceDelta(account, entry.getKey(), entry.getValue())).toList();
        // Empty containers still earn eligibility after their saved removal.
        return phase(id, Phase.EXCHANGE_CREDITING).thenCompose(unused -> legs.isEmpty()
                ? services.state.updateAsync(root -> completeDeposit(root, id, selection))
                : services.settlements.transactionWithRequest(legs, false, "DEPOSIT", selection.player.toString(),
                                                              "Confirmed physical custody", id.toString()).thenCompose(receipt -> services.state.updateAsync(root -> {
            operation(root, id).addProperty("creditRequest", receipt.requestId());
            completeDeposit(root, id, selection);
        })));
    }

    private void completeDeposit(JsonObject root, UUID id, DepositSelection selection)
    {
        addCredits(root, selection.user, selection.chests, selection.shulkers);
        operation(root, id).addProperty("phase", Phase.COMPLETE.name());
    }

    public long containerCredit(ServerUser user, ContainerKind kind)
    {
        JsonObject root = services.state.section("containerCredits");
        String key = user.getUuid() + ":" + kind;
        return root.has("containerCredits") && root.getAsJsonObject("containerCredits").has(key)
                ? root.getAsJsonObject("containerCredits").get(key).getAsLong() : 0;
    }

    public CompletableFuture<Void> withdraw(ServerPlayer player, ServerUser user, long assetId, long count, Output output)
    {
        var asset = services.assets.find(assetId).orElseThrow();
        if (!services.assets.enabled(assetId))
        {
            return CompletableFuture.failedFuture(new IllegalStateException("Asset binding is disabled"));
        }
        if (asset.electronic())
        {
            return CompletableFuture.failedFuture(new IllegalArgumentException("ui.withdraw.electronic"));
        }
        if (count < asset.minimumWithdrawal())
        {
            return CompletableFuture.failedFuture(new IllegalArgumentException("ui.withdraw.minimum"));
        }
        int capacity = switch (output)
        {
            case ITEMS -> 4096;
            case SMALL_CHEST, SHULKER -> 27 * 64;
            case LARGE_CHEST -> 54 * 64;
        };
        if (count <= 0 || count > capacity)
        {
            return CompletableFuture.failedFuture(new IllegalArgumentException("ui.withdraw.capacity"));
        }
        return exclusive(user, () -> services.settlements.account(user, AccountType.Spot).thenCompose(account -> services.onServer(() -> {
            requireSession(player, user);
            if (count < services.assets.find(assetId).orElseThrow().minimumWithdrawal())
            {
                throw new IllegalArgumentException("ui.withdraw.minimum");
            }
            int chests = output == Output.SMALL_CHEST ? 1 : output == Output.LARGE_CHEST ? 2 : 0, shulkers = output == Output.SHULKER ? 1 : 0;
            if (containerCredit(user, ContainerKind.CHEST) < chests || containerCredit(user, ContainerKind.SHULKER) < shulkers)
            {
                throw new IllegalArgumentException("ui.withdraw.container_credit");
            }
            BlockPos position = player.blockPosition().relative(player.getDirection());
            List<BlockPos> positions = new ArrayList<>();
            BlockState first = output == Output.LARGE_CHEST ? Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
                                                                          .setValue(ChestBlock.TYPE, ChestType.LEFT) : Blocks.CHEST.defaultBlockState();
            if (output != Output.ITEMS)
            {
                positions.add(position);
                if (output == Output.LARGE_CHEST)
                {
                    positions.add(ChestBlock.getConnectedBlockPos(position, first));
                }
                for (BlockPos pos : positions)
                    checkEmpty(player, pos);
            }
            UUID id = UUID.randomUUID();
            return new Withdrawal(id, account, positions, first, chests, shulkers, player.level(), CapitalMC.serverUserManager.getSessionVersion(player.getUUID()), operationData(user,
                                                                                                                                                                                  "WITHDRAW_" + output, Map.of(assetId, count), -chests, -shulkers, player, account, positions,
                                                                                                                                                                                  Phase.EXCHANGE_DEBITTING, new JsonObject(), id));
        })).thenCompose(plan -> record(plan.id, plan.data).thenCompose(unused ->
                                                                               services.settlements.adjustmentWithRequest(plan.account, assetId, -count, "WITHDRAW", player.getUUID().toString(),
                                                                                                                          "Confirmed physical withdrawal", plan.id.toString()))
                                                          .thenCompose(receipt -> services.state.updateAsync(root -> {
                                                              var op = operation(root, plan.id);
                                                              op.addProperty("debitRequest", receipt.requestId());
                                                              op.addProperty("phase", Phase.DEBITED.name());
                                                          })).thenCompose(unused -> services.onServer(() -> {
                    validateDelivery(player, user, plan, asset);
                    return null;
                }))
                                                          .thenCompose(unused -> phase(plan.id, Phase.DELIVERY_INTENT))
                                                          .thenCompose(unused -> services.onServer(() -> {
                                                              validateDelivery(player, user, plan, asset);
                                                              List<ItemStack> stacks = split(asset, count);
                                                              if (output == Output.ITEMS)
                                                              {
                                                                  for (ItemStack stack : stacks)
                                                                  {
                                                                      ItemEntity entity = new ItemEntity(player.level(), player.getX(), player.getY() + 0.5, player.getZ(), stack);
                                                                      entity.setTarget(player.getUUID());
                                                                      entity.setDefaultPickUpDelay();
                                                                      entity.addTag("capitalmc:" + plan.id);
                                                                      if (!player.level().addFreshEntity(entity))
                                                                      {
                                                                          throw new IllegalStateException("Cannot spawn withdrawal items");
                                                                      }
                                                                  }
                                                              }
                                                              else
                                                              {
                                                                  for (int i = 0; i < plan.positions.size(); i++)
                                                                  {
                                                                      BlockState state = output == Output.SHULKER ? Blocks.SHULKER_BOX.defaultBlockState()
                                                                              : i == 0 ? plan.first : plan.first.setValue(ChestBlock.TYPE, ChestType.RIGHT);
                                                                      if (!player.level().setBlockAndUpdate(plan.positions.get(i), state))
                                                                      {
                                                                          throw new IllegalStateException("Cannot place withdrawal container");
                                                                      }
                                                                      Container container = (Container) player.level().getBlockEntity(plan.positions.get(i));
                                                                      for (int slot = 0; slot < container.getContainerSize(); slot++)
                                                                      {
                                                                          int index = i * 27 + slot;
                                                                          if (index < stacks.size())
                                                                          {
                                                                              container.setItem(slot, stacks.get(index));
                                                                          }
                                                                      }
                                                                      ((BlockEntity) container).setChanged();
                                                                  }
                                                              }
                                                              saveWorld();
                                                              return null;
                                                          })).thenCompose(unused -> services.state.updateAsync(root -> {
                    addCredits(root, user.getUuid(), -plan.chests, -plan.shulkers);
                    operation(root, plan.id).addProperty("phase", Phase.COMPLETE.name());
                })).exceptionallyCompose(error -> services.state.updateAsync(root -> {
                    JsonObject op = operation(root, plan.id);
                    if (definiteRejection(error) && op.get("phase").getAsString().equals(Phase.EXCHANGE_DEBITTING.name()))
                    {
                        op.addProperty("phase", Phase.REJECTED.name());
                    }
                    else
                    {
                        doubt(root, plan.id, error);
                    }
                }).thenCompose(unused -> CompletableFuture.failedFuture(error)))));
    }

    private void validateDelivery(ServerPlayer player, ServerUser user, Withdrawal plan, McAssetRegistry.Asset asset)
    {
        var current = services.assets.find(asset.id()).orElseThrow();
        if (services.assets.changing(asset.id()) || !services.assets.enabled(asset.id()) || current.electronic() || !McAssetRegistry.matches(asset.prototype(), current.prototype()))
        {
            throw new IllegalStateException("Asset binding changed before delivery");
        }
        requireSession(player, user);
        if (plan.sessionVersion != CapitalMC.serverUserManager.getSessionVersion(player.getUUID()))
        {
            throw new IllegalStateException("ui.session.changed");
        }
        if (player.level() != plan.level)
        {
            throw new IllegalStateException("ui.session.changed");
        }
        for (BlockPos position : plan.positions)
            checkEmpty(player, position);
    }

    private void checkEmpty(ServerPlayer player, BlockPos position)
    {
        checkPosition(player, position);
        if (!player.level().getBlockState(position).isAir())
        {
            throw new IllegalArgumentException("ui.withdraw.space");
        }
    }

    private void saveWorld()
    {
        if (!services.server.saveEverything(false, true, true))
        {
            throw new IllegalStateException("World custody checkpoint failed");
        }
    }

    private List<ItemStack> split(McAssetRegistry.Asset asset, long count)
    {
        List<ItemStack> stacks = new ArrayList<>();
        while (count > 0)
        {
            int size = (int) Math.min(64, count);
            stacks.add(asset.stack(size));
            count -= size;
        }
        return stacks;
    }

    private void checkPosition(ServerPlayer player, BlockPos position)
    {
        if (player.position().distanceToSqr(Vec3.atCenterOf(position)) > 36 || !player.level().isLoaded(position)
            || !player.mayInteract(player.level(), position))
        {
            throw new IllegalArgumentException("ui.container.denied");
        }
        checkingPermission = true;
        try
        {
            var hit = new BlockHitResult(player.position(), Direction.UP, position, false);
            if (UseBlockCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND, hit) == InteractionResult.FAIL)
            {
                throw new IllegalArgumentException("ui.container.denied");
            }
        } finally
        {
            checkingPermission = false;
        }
    }

    private void requireSession(ServerPlayer player, ServerUser user)
    {
        if (player.hasDisconnected() || player.isCreative() || player.isSpectator())
        {
            throw new IllegalStateException("ui.deposit.survival");
        }
        if (CapitalMC.serverUserManager.getLoggedInUser(player.getUUID()).filter(value -> value.getUuid().equals(user.getUuid())).isEmpty())
        {
            throw new IllegalStateException("ui.session.changed");
        }
    }

    private <T> CompletableFuture<T> exclusive(ServerUser user, Supplier<CompletableFuture<T>> work)
    {
        if (!busy.add(user.getUuid()))
        {
            return CompletableFuture.failedFuture(new IllegalStateException("ui.operation.busy"));
        }
        if (unresolved(user.getUuid()))
        {
            busy.remove(user.getUuid());
            return CompletableFuture.failedFuture(new IllegalStateException("ui.operation.review"));
        }
        try
        {
            return work.get().whenComplete((value, error) -> busy.remove(user.getUuid()));
        } catch (RuntimeException error)
        {
            busy.remove(user.getUuid());
            return CompletableFuture.failedFuture(error);
        }
    }

    public boolean unresolved(UUID user)
    {
        JsonObject root = services.state.section("itemOperations");
        if (!root.has("itemOperations"))
        {
            return false;
        }
        return root.getAsJsonObject("itemOperations").entrySet().stream().map(entry -> entry.getValue().getAsJsonObject())
                   .anyMatch(op -> op.get("user").getAsString().equals(user.toString()) && !terminal(op));
    }

    static boolean terminal(JsonObject op)
    {
        return Set.of("COMPLETE", "RECONCILED", "REJECTED", "REVERSED").contains(op.get("phase").getAsString());
    }

    public CompletableFuture<Void> reconcile(UUID id, String actor, String note)
    {
        if (actor.isBlank() || note.isBlank())
        {
            throw new IllegalArgumentException("Reconciliation operator and evidence required");
        }
        return services.state.updateAsync(root -> {
            var op = operation(root, id);
            if (busy.contains(UUID.fromString(op.get("user").getAsString())))
            {
                throw new IllegalStateException("ui.operation.busy");
            }
            if (terminal(op))
            {
                throw new IllegalStateException("Operation already resolved");
            }
            op.addProperty("phase", Phase.RECONCILED.name());
            op.addProperty("review", note);
            op.addProperty("reviewer", actor);
            op.addProperty("reviewTimestamp", System.currentTimeMillis());
        });
    }

    /**
     * Refund is safe only if durable debit is known and materialization has never started. No uncertain requests are replayed.
     */
    public CompletableFuture<Void> refundWithdrawal(UUID id, String actor, String reason)
    {
        JsonObject op = operation(services.state.section("itemOperations"), id);
        if (!canRefund(op))
        {
            return CompletableFuture.failedFuture(new IllegalStateException("No safe confirmed debit; manual reconciliation required"));
        }
        if (actor.isBlank() || reason.isBlank())
        {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Operator and refund reason required"));
        }
        UUID user = UUID.fromString(op.get("user").getAsString());
        if (!busy.add(user))
        {
            return CompletableFuture.failedFuture(new IllegalStateException("ui.operation.busy"));
        }
        var amount = op.getAsJsonObject("amounts").entrySet().iterator().next();
        return services.state.updateAsync(root -> {
                           if (!canRefund(operation(root, id)))
                           {
                               throw new IllegalStateException("Withdrawal recovery state changed");
                           }
                           operation(root, id).addProperty("phase", Phase.REFUND_INTENT.name());
                       })
                             .thenCompose(unused -> services.settlements.adjustmentWithRequest(op.get("account").getAsLong(), Long.parseLong(amount.getKey()),
                                                                                               amount.getValue().getAsLong(), "ADMIN_ADJUSTMENT", actor, reason, id.toString()))
                             .thenCompose(receipt -> services.state.updateAsync(root -> {
                                 var record = operation(root, id);
                                 record.addProperty("phase", Phase.REVERSED.name());
                                 record.addProperty("refundRequest", receipt.requestId());
                                 record.addProperty("reviewer", actor);
                                 record.addProperty("review", reason);
                             }))
                             .whenComplete((unused, error) -> busy.remove(user));
    }

    static boolean canRefund(JsonObject op)
    {
        if (!op.get("type").getAsString().startsWith("WITHDRAW_") || !op.has("debitRequest"))
        {
            return false;
        }
        String stage = op.get("phase").getAsString();
        if (stage.equals(Phase.IN_DOUBT.name()))
        {
            if (!op.has("failurePhase"))
            {
                return false;
            }
            stage = op.get("failurePhase").getAsString();
        }
        return stage.equals(Phase.DEBITED.name());
    }

    private JsonObject operationData(ServerUser user, String type, Map<Long, Long> amounts, int chests, int shulkers,
                                     ServerPlayer player, long account, List<BlockPos> positions, Phase phase, JsonObject recovery, UUID id)
    {
        JsonObject op = new JsonObject();
        op.addProperty("id", id.toString());
        op.addProperty("user", user.getUuid().toString());
        op.addProperty("type", type);
        op.addProperty("account", account);
        op.addProperty("player", player.getUUID().toString());
        op.addProperty("actor", player.getUUID().toString());
        op.addProperty("dimension", player.level().dimension().identifier().toString());
        op.addProperty("x", player.getX());
        op.addProperty("y", player.getY());
        op.addProperty("z", player.getZ());
        op.addProperty("timestamp", System.currentTimeMillis());
        JsonArray locations = new JsonArray();
        for (BlockPos position : positions)
        {
            JsonArray location = new JsonArray();
            location.add(position.getX());
            location.add(position.getY());
            location.add(position.getZ());
            locations.add(location);
        }
        op.add("positions", locations);
        op.addProperty("phase", phase.name());
        op.addProperty("chests", chests);
        op.addProperty("shulkers", shulkers);
        JsonObject values = new JsonObject();
        amounts.forEach((asset, amount) -> values.addProperty(asset.toString(), amount));
        op.add("amounts", values);
        op.add("bindings", services.assets.bindingProof(amounts.keySet()));
        op.add("recovery", recovery.deepCopy());
        return op;
    }

    private CompletableFuture<Void> record(UUID id, JsonObject op)
    {
        return services.state.updateAsync(root -> {
            JsonObject operations = root.has("itemOperations") ? root.getAsJsonObject("itemOperations") : new JsonObject();
            if (operations.has(id.toString()))
            {
                throw new IllegalStateException("Duplicate custody operation");
            }
            operations.add(id.toString(), op.deepCopy());
            root.add("itemOperations", operations);
        });
    }

    private CompletableFuture<Void> phase(UUID id, Phase phase)
    {
        return services.state.updateAsync(root -> operation(root, id).addProperty("phase", phase.name()));
    }

    private static JsonObject operation(JsonObject root, UUID id)
    {
        return root.getAsJsonObject("itemOperations").getAsJsonObject(id.toString());
    }

    private static void doubt(JsonObject root, UUID id, Throwable failure)
    {
        if (!root.has("itemOperations") || !root.getAsJsonObject("itemOperations").has(id.toString()))
        {
            return;
        }
        JsonObject op = operation(root, id);
        if (terminal(op))
        {
            return;
        }
        if (!op.get("phase").getAsString().equals(Phase.IN_DOUBT.name()))
        {
            op.addProperty("failurePhase", op.get("phase").getAsString());
        }
        op.addProperty("phase", Phase.IN_DOUBT.name());
        op.addProperty("failure", failure.getClass().getSimpleName());
        op.addProperty("failureTimestamp", System.currentTimeMillis());
    }

    private static boolean definiteRejection(Throwable error)
    {
        while (error.getCause() != null)
            error = error.getCause();
        return error instanceof BusinessException;
    }

    private static class BeforeCustody extends IllegalStateException
    {
        BeforeCustody(RuntimeException changed)
        {
            super("Selection changed before custody; no items removed", changed);
        }
    }

    private static boolean beforeCustody(Throwable error)
    {
        for (Throwable current = error; current != null; current = current.getCause())
            if (current instanceof BeforeCustody)
            {
                return true;
            }
        return false;
    }

    private static void addCredits(JsonObject root, UUID user, int chests, int shulkers)
    {
        JsonObject credits = root.has("containerCredits") ? root.getAsJsonObject("containerCredits") : new JsonObject();
        for (ContainerKind kind : ContainerKind.values())
        {
            String key = user + ":" + kind;
            long value = Math.addExact(credits.has(key) ? credits.get(key).getAsLong() : 0, kind == ContainerKind.CHEST ? chests : shulkers);
            if (value < 0)
            {
                throw new IllegalStateException("Negative container credits");
            }
            credits.addProperty(key, value);
        }
        root.add("containerCredits", credits);
    }

    private record Withdrawal(UUID id, long account, List<BlockPos> positions, BlockState first, int chests,
                              int shulkers,
                              ServerLevel level, long sessionVersion, JsonObject data)
    {
    }
}
