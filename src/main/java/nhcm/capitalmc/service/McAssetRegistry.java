package nhcm.capitalmc.service;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.*;
import nhcm.advmatch.model.AssetView;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.exchange.AssetUtil;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Exact item and component registrations. Item assets use scale 0; electronic assets may use decimal scales.
 */
public final class McAssetRegistry
{
    public record Asset(long id, String name, String displayName, int scale, boolean electronic, ItemStack prototype,
                        BigDecimal unitSize, long minimumDeposit, long minimumWithdrawal)
    {
        public Asset(long id, String name, String displayName, int scale, boolean electronic, ItemStack prototype)
        {
            this(id, name, displayName, scale, electronic, prototype, BigDecimal.ONE, 1, 1);
        }

        public Asset
        {
            new DecimalUnits.Unit(scale, unitSize);
            if (minimumDeposit <= 0 || minimumWithdrawal <= 0)
            {
                throw new IllegalArgumentException("ui.units.invalid");
            }
            prototype = prototype.copyWithCount(1);
        }

        @Override
        public ItemStack prototype()
        {
            return prototype.copy();
        }

        public ItemStack stack(int count)
        {
            if (electronic)
            {
                throw new IllegalStateException("Electronic assets cannot be withdrawn as items");
            }
            ItemStack stack = prototype.copyWithCount(count);
            stack.set(DataComponents.MAX_STACK_SIZE, 64);
            return stack;
        }

        public DecimalUnits.Unit units()
        {
            return new DecimalUnits.Unit(scale, unitSize);
        }

        public long parse(String input)
        {
            return units().parse(input);
        }

        public String format(BigInteger raw)
        {
            return units().format(raw);
        }

        public String format(long raw)
        {
            return units().format(raw);
        }
    }

    public synchronized DecimalUnits.Unit units(long id, int nativeScale)
    {
        return find(id).map(Asset::units).orElseGet(() -> new DecimalUnits.Unit(nativeScale, BigDecimal.ONE));
    }

    public CompletableFuture<Void> configureUnits(long id, BigDecimal size, long minimumDeposit, long minimumWithdrawal)
    {
        return services.onServer(() -> {
            if (changing(id))
            {
                throw new IllegalStateException("ui.operation.busy");
            }
            var previous = find(id).orElseThrow();
            var next = new Asset(id, previous.name(), previous.displayName(), previous.scale(), previous.electronic(), previous.prototype(), size, minimumDeposit, minimumWithdrawal);
            synchronized (this)
            {
                editingTemplates.put(id, previous.electronic() ? ItemStack.EMPTY : previous.prototype());
            }
            return services.state.updateAsync(root -> {
                var all = root.has("assetUnits") ? root.getAsJsonObject("assetUnits") : new JsonObject();
                var entry = new JsonObject();
                entry.addProperty("size", size.toPlainString());
                entry.addProperty("minimumDeposit", minimumDeposit);
                entry.addProperty("minimumWithdrawal", minimumWithdrawal);
                all.add(Long.toString(id), entry);
                root.add("assetUnits", all);
            }).thenCompose(unused -> services.onServer(() -> {
                synchronized (this)
                {
                    assets.put(id, next);
                }
                return (Void) null;
            })).whenComplete((unused, error) -> {
                synchronized (this)
                {
                    editingTemplates.remove(id);
                }
            });
        }).thenCompose(future -> future);
    }

    private final GameServices services;
    private final Map<Long, Asset> assets = new LinkedHashMap<>();
    private final Set<Long> unbound = new HashSet<>();
    private final Set<Long> disabled = new HashSet<>();
    private final Map<String, CompletableFuture<Asset>> registering = new HashMap<>();
    private final Map<String, ItemStack> registeringTemplates = new HashMap<>();
    private final Map<String, Registration> registrationArguments = new HashMap<>();
    private final Map<Long, ItemStack> editingTemplates = new HashMap<>();

    private record Registration(String displayName, int scale, boolean electronic, ItemStack prototype)
    {
    }

    public McAssetRegistry(GameServices services)
    {
        this.services = services;
    }

    public synchronized Optional<Asset> find(long id)
    {
        return Optional.ofNullable(assets.get(id));
    }

    public synchronized Optional<Asset> find(String name)
    {
        return assets.values().stream().filter(asset -> asset.name().equals(name)).findFirst();
    }

    public synchronized List<Asset> all()
    {
        return List.copyOf(assets.values());
    }

    public synchronized Optional<Asset> match(ItemStack stack)
    {
        ItemStack normalized = normalize(stack);
        return assets.values().stream().filter(asset -> !asset.electronic() && !disabled.contains(asset.id()) && !editingTemplates.containsKey(asset.id())
                                                        && matches(asset.prototype(), normalized)).findFirst();
    }

    public static boolean matches(ItemStack prototype, ItemStack candidate)
    {
        return !prototype.isEmpty() && !candidate.isEmpty()
               && ItemStack.isSameItemSameComponents(normalize(prototype), normalize(candidate));
    }

    public static void validateDepositStack(ItemStack stack)
    {
        var contents = stack.get(DataComponents.CONTAINER);
        if (contents != null && contents.nonEmptyItemCopyStream().findAny().isPresent())
        {
            throw new IllegalArgumentException("ui.deposit.nested");
        }
    }

    private static ItemStack normalize(ItemStack stack)
    {
        ItemStack copy = stack.copyWithCount(1);
        copy.remove(DataComponents.MAX_STACK_SIZE);
        return copy;
    }

    public CompletableFuture<Void> initialize()
    {
        JsonObject root = services.state.snapshot();
        return AssetUtil.allAssetsFresh().thenCompose(backend -> services.onServer(() -> {
                            for (var view : backend)
                            {
                                assets.put(view.assetId(), new Asset(view.assetId(), view.assetName(), view.displayName(), view.scale(), true, new ItemStack(Items.PAPER)));
                                unbound.add(view.assetId());
                            }
                            if (root.has("assets"))
                            {
                                for (JsonElement element : root.getAsJsonArray("assets"))
                                {
                                    JsonObject entry = element.getAsJsonObject();
                                    long id = entry.get("id").getAsLong();
                                    AssetView view = backend.stream().filter(value -> value.assetId() == id).findFirst()
                                                            .orElseThrow(() -> new IllegalStateException("Persisted asset is missing: " + id));
                                    if (!view.assetName().equals(entry.get("name").getAsString()) || view.scale() != entry.get("scale").getAsInt())
                                    {
                                        throw new IllegalStateException("Asset identity/scale mismatch: " + id);
                                    }
                                    ItemStack item = ItemStack.CODEC.parse(services.server.registryAccess().createSerializationContext(JsonOps.INSTANCE), entry.get("item"))
                                                                    .getOrThrow();
                                    assets.put(id, new Asset(id, view.assetName(), entry.get("displayName").getAsString(), view.scale(), entry.get("electronic").getAsBoolean(), item));
                                    unbound.remove(id);
                                    if (entry.has("enabled") && !entry.get("enabled").getAsBoolean())
                                    {
                                        disabled.add(id);
                                    }
                                }
                            }
                            if (root.has("assetUnits"))
                            {
                                for (var entry : root.getAsJsonObject("assetUnits").entrySet())
                                {
                                    long id = Long.parseLong(entry.getKey());
                                    var previous = assets.get(id);
                                    if (previous == null)
                                    {
                                        throw new IllegalStateException("Unit configuration references an unknown asset");
                                    }
                                    var settings = entry.getValue().getAsJsonObject();
                                    assets.put(id, new Asset(id, previous.name(), previous.displayName(), previous.scale(), previous.electronic(), previous.prototype(),
                                                             new BigDecimal(settings.get("size").getAsString()), settings.get("minimumDeposit").getAsLong(), settings.get("minimumWithdrawal").getAsLong()));
                                }
                            }
                            return null;
                        }));
    }

    public CompletableFuture<Asset> registerItem(String name, String displayName, ItemStack prototype)
    {
        validateDepositStack(prototype);
        return register(name, displayName, 0, false, prototype);
    }

    public CompletableFuture<Asset> registerElectronic(String name, String displayName, int scale, ItemStack icon)
    {
        return register(name, displayName, scale, true, icon);
    }

    private synchronized CompletableFuture<Asset> register(String name, String displayName, int scale, boolean electronic, ItemStack prototype)
    {
        if (!name.matches("[a-z0-9_]+") || displayName.isBlank() || prototype.isEmpty() || scale < 0 || scale > 7)
        {
            throw new IllegalArgumentException("ui.admin.asset_invalid");
        }
        var existing = find(name);
        if (existing.isPresent() && !unbound.contains(existing.get().id()))
        {
            Asset asset = existing.get();
            if (asset.scale() != scale || asset.electronic() != electronic
                || !ItemStack.isSameItemSameComponents(normalize(asset.prototype()), normalize(prototype)))
            {
                throw new IllegalArgumentException("Asset name already has a different registration");
            }
            return CompletableFuture.completedFuture(asset);
        }
        if (!electronic && occupiedTemplate(prototype, 0))
        {
            throw new IllegalArgumentException("ui.admin.template_registered");
        }
        if (registering.containsKey(name))
        {
            var pending = registrationArguments.get(name);
            if (!pending.displayName().equals(displayName) || pending.scale() != scale || pending.electronic() != electronic
                || !matches(pending.prototype(), prototype))
            {
                throw new IllegalArgumentException("Asset name is pending a different registration");
            }
            return registering.get(name).copy();
        }
        if (!electronic && registeringTemplates.values().stream().anyMatch(item -> ItemStack.isSameItemSameComponents(normalize(item), normalize(prototype))))
        {
            throw new IllegalArgumentException("Item template is already being registered");
        }
        ItemStack copy = prototype.copyWithCount(1);
        var result = AssetUtil.allAssetsFresh().thenCompose(values -> {
            var view = values.stream().filter(value -> value.assetName().equals(name)).findFirst();
            if (view.isPresent())
            {
                if (view.get().scale() != scale)
                {
                    return CompletableFuture.<Long>failedFuture(new IllegalStateException("Backend asset scale differs"));
                }
                return CompletableFuture.completedFuture(view.get().assetId());
            }
            return CapitalMC.exchange.assets().create(displayName, name, scale).thenApply(value -> value.id());
        }).thenCompose(id -> services.onServer(() -> {
            if (!electronic && occupiedTemplate(copy, 0))
            {
                throw new IllegalArgumentException("ui.admin.template_registered");
            }
            Asset asset = new Asset(id, name, displayName, scale, electronic, copy);
            JsonObject entry = new JsonObject();
            entry.addProperty("id", asset.id());
            entry.addProperty("name", asset.name());
            entry.addProperty("displayName", asset.displayName());
            entry.addProperty("scale", asset.scale());
            entry.addProperty("electronic", asset.electronic());
            entry.add("item", ItemStack.CODEC.encodeStart(services.server.registryAccess().createSerializationContext(JsonOps.INSTANCE), asset.prototype()).getOrThrow());
            return services.state.updateAsync(root -> {
                JsonArray entries = root.has("assets") ? root.getAsJsonArray("assets") : new JsonArray();
                if (entries.asList().stream().anyMatch(value -> value.getAsJsonObject().get("id").getAsLong() == id))
                {
                    throw new IllegalStateException("Asset binding already persisted");
                }
                entries.add(entry);
                root.add("assets", entries);
            }).thenCompose(unused -> services.onServer(() -> {
                synchronized (McAssetRegistry.this)
                {
                    assets.put(id, asset);
                    unbound.remove(id);
                }
                return asset;
            }));
        })).thenCompose(future -> future);
        registering.put(name, result);
        registrationArguments.put(name, new Registration(displayName, scale, electronic, copy));
        if (!electronic)
        {
            registeringTemplates.put(name, copy);
        }
        result.whenComplete((value, error) -> {
            synchronized (McAssetRegistry.this)
            {
                registering.remove(name);
                registeringTemplates.remove(name);
                registrationArguments.remove(name);
            }
        });
        return result.copy();
    }

    public synchronized boolean enabled(long id)
    {
        return assets.containsKey(id) && !disabled.contains(id);
    }

    public synchronized boolean changing(long id)
    {
        return editingTemplates.containsKey(id);
    }

    private synchronized boolean occupiedTemplate(ItemStack template, long except)
    {
        return assets.values().stream().anyMatch(asset -> asset.id() != except && !asset.electronic() && matches(asset.prototype(), template))
               || editingTemplates.entrySet().stream().anyMatch(entry -> entry.getKey() != except && matches(entry.getValue(), template));
    }

    private synchronized boolean pendingTemplate(ItemStack template)
    {
        return registeringTemplates.values().stream().anyMatch(item -> matches(item, template));
    }

    /**
     * Presentation/binding metadata only: native balances and asset identity remain intact.
     */
    public CompletableFuture<Void> edit(long id, String displayName, ItemStack icon, Boolean electronic, boolean enabled)
    {
        return services.onServer(() -> {
            if (changing(id))
            {
                throw new IllegalStateException("ui.operation.busy");
            }
            Asset previous = find(id).orElseThrow();
            ItemStack template = icon == null ? previous.prototype() : icon.copyWithCount(1);
            boolean virtual = electronic == null ? previous.electronic() : electronic;
            if (displayName.isBlank() || template.isEmpty() || !virtual && previous.scale() != 0)
            {
                throw new IllegalArgumentException("Physical bindings require scale 0 and a nonempty item");
            }
            validateDepositStack(template);
            if (!virtual && (occupiedTemplate(template, id) || pendingTemplate(template)))
            {
                throw new IllegalArgumentException("Item template is already registered");
            }
            Asset next = new Asset(id, previous.name(), displayName, previous.scale(), virtual, template, previous.unitSize(), previous.minimumDeposit(), previous.minimumWithdrawal());
            JsonObject entry = new JsonObject();
            entry.addProperty("id", id);
            entry.addProperty("name", next.name());
            entry.addProperty("displayName", displayName);
            entry.addProperty("scale", next.scale());
            entry.addProperty("electronic", virtual);
            entry.addProperty("enabled", enabled);
            entry.add("item", ItemStack.CODEC.encodeStart(services.server.registryAccess().createSerializationContext(JsonOps.INSTANCE), template).getOrThrow());
            synchronized (this)
            {
                editingTemplates.put(id, virtual ? ItemStack.EMPTY : template);
            }
            return services.state.updateAsync(root -> {
                JsonArray entries = root.has("assets") ? root.getAsJsonArray("assets") : new JsonArray();
                entries.asList().removeIf(value -> value.getAsJsonObject().get("id").getAsLong() == id);
                entries.add(entry);
                root.add("assets", entries);
            }).thenCompose(unused -> services.onServer(() -> {
                synchronized (this)
                {
                    assets.put(id, next);
                    unbound.remove(id);
                    if (enabled)
                    {
                        disabled.remove(id);
                    }
                    else
                    {
                        disabled.add(id);
                    }
                }
                return (Void) null;
            })).whenComplete((unused, error) -> {
                synchronized (this)
                {
                    editingTemplates.remove(id);
                }
            });
        }).thenCompose(future -> future);
    }
}
