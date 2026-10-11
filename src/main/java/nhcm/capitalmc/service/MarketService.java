package nhcm.capitalmc.service;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.world.item.ItemStack;
import nhcm.advmatch.BusinessException;
import nhcm.advmatch.command.persistence.Flush;
import nhcm.advmatch.model.*;
import nhcm.advmatch.object.Symbol;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.exchange.ExchangeMessages;
import nhcm.capitalmc.exchange.SymbolUtil;
import nhcm.capitalmc.gui.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

/**
 * Market setup includes its order book; merely creating a Symbol does not enable matching.
 */
public class MarketService
{
    private final GameServices services;
    private final Map<Long, ItemStack> icons = new HashMap<>();
    private final Set<String> creating = ConcurrentHashMap.newKeySet();
    private boolean updating;
    private final Map<Long, Long> lots = new ConcurrentHashMap<>();

    public long lotSize(long symbol)
    {
        return lots.getOrDefault(symbol, 0L);
    }

    public DecimalUnits.Unit quantityUnits(SymbolView symbol)
    {
        long size = lotSize(symbol.symbolId());
        return size == 0 ? services.assets.find(symbol.baseAssetId()).orElseThrow().units()
                : new DecimalUnits.Unit(0, BigDecimal.valueOf(size));
    }

    /**
     * Rules live in AdvMatch; a lot is only a name for an existing raw quantity.
     */
    public CompletableFuture<CommandReceipt> configureLot(long id, long size, long minimum, long step)
    {
        if (size <= 0 || minimum <= 0 || step <= 0 || size % step != 0 || minimum % step != 0)
        {
            throw new IllegalArgumentException("ui.lot.invalid");
        }
        var symbol = CapitalMC.exchange.symbols().get(id);
        return symbol.snapshot().thenCompose(view -> CapitalMC.exchange.rules().configure(symbol,
                                                                                          view.tradingRules().withQuantityStep(step).withMinQuantity(minimum)))
                     .thenCompose(receipt -> CapitalMC.exchange.submit(new Flush()).thenCompose(status -> {
                         if (!status.enabled() || status.faulted() || status.durableThrough() < receipt.sequence())
                         {
                             return CompletableFuture.failedFuture(new IllegalStateException("ui.operation.review"));
                         }
                         return services.state.updateAsync(root -> {
                             var all = root.has("symbolLots") ? root.getAsJsonObject("symbolLots") : new JsonObject();
                             all.addProperty(Long.toString(id), size);
                             root.add("symbolLots", all);
                         }).thenCompose(unused -> services.onServer(() -> {
                             lots.put(id, size);
                             return receipt;
                         }));
                     }));
    }

    public MarketService(GameServices services)
    {
        this.services = services;
    }

    public CompletableFuture<Void> initialize()
    {
        return reload().thenCompose(unused -> SymbolUtil.allSymbols().thenCompose(symbols -> {
            CompletableFuture<Void> migration = CompletableFuture.completedFuture(null);
            for (var symbol : symbols)
            {
                if (symbol.type() != SymbolType.Spot)
                {
                    migration = migration.thenCompose(ignored -> enableTickBand(symbol));
                }
            }
            return migration;
        }));
    }

    public CompletableFuture<Void> reload()
    {
        return services.onServer(() -> {
            lots.clear();
            icons.clear();
            var root = services.state.snapshot();
            if (root.has("symbolLots"))
            {
                for (var entry : root.getAsJsonObject("symbolLots").entrySet())
                {
                    long size = entry.getValue().getAsLong();
                    if (size <= 0)
                    {
                        throw new IllegalStateException("Invalid persisted lot size");
                    }
                    lots.put(Long.parseLong(entry.getKey()), size);
                }
            }
            if (root.has("symbolIcons"))
            {
                for (var entry : root.getAsJsonObject("symbolIcons").entrySet())
                    icons.put(Long.parseLong(entry.getKey()), ItemStack.CODEC.parse(services.server.registryAccess().createSerializationContext(JsonOps.INSTANCE), entry.getValue()).getOrThrow());
            }
            return null;
        });
    }

    private CompletableFuture<Void> enableTickBand(SymbolView symbol)
    {
        return enableTickBand(symbol, false);
    }

    private CompletableFuture<Void> enableTickBand(SymbolView symbol, boolean leaseOwned)
    {
        if (symbol.type() == SymbolType.Spot) return CompletableFuture.completedFuture(null);
        if (leaseOwned) return enableTickBandLeased(symbol);
        return CapitalMC.runtime.backend.exclusive("configuration", () -> services.state.refreshShared()
                .thenCompose(changed -> CapitalMC.exchange.symbols().get(symbol.symbolId()).snapshot())
                .thenCompose(this::enableTickBandLeased));
    }

    private CompletableFuture<Void> enableTickBandLeased(SymbolView symbol)
    {
        if (symbol.type() == SymbolType.Spot)
        {
            return CompletableFuture.completedFuture(null);
        }
        String id = Long.toString(symbol.symbolId());
        var root = services.state.snapshot();
        if (root.has("tickBandMigrated") && root.getAsJsonObject("tickBandMigrated").has(id))
        {
            return CompletableFuture.completedFuture(null);
        }
        if (root.has("adminOperations"))
        {
            for (var entry : root.getAsJsonObject("adminOperations").entrySet())
            {
                var operation = entry.getValue().getAsJsonObject();
                if ("tick-band-migration".equals(operation.get("action").getAsString()) && operation.get("payload").getAsLong() == symbol.symbolId()
                    && Set.of("INTENT", "IN_DOUBT").contains(operation.get("phase").getAsString()))
                {
                    return CompletableFuture.failedFuture(new IllegalStateException("ui.operation.review"));
                }
            }
        }
        var configured = CapitalMC.exchange.contracts().market(CapitalMC.exchange.symbols().get(symbol.symbolId())).thenCompose(market -> {
            if (market.status() != ContractStatus.Trading || symbol.tradingRules().tickAwarePriceBand())
            {
                return CompletableFuture.completedFuture(null);
            }
            return services.administration.performLeased("capitalmc/migration", "Enable one-tick minimum contract price band", "tick-band-migration",
                                                   new JsonPrimitive(symbol.symbolId()), () -> CapitalMC.exchange.rules().configure(CapitalMC.exchange.symbols().get(symbol.symbolId()),
                                                                                                                                    symbol.tradingRules().withTickAwarePriceBand(true))).thenApply(receipt -> null);
        });
        return configured.thenCompose(unused -> services.state.updateAsync(data -> {
            var migrated = data.has("tickBandMigrated") ? data.getAsJsonObject("tickBandMigrated") : new JsonObject();
            migrated.addProperty(id, true);
            data.add("tickBandMigrated", migrated);
        }));
    }

    public CompletableFuture<Void> setIcon(long symbol, ItemStack icon)
    {
        if (symbol <= 0 || icon.isEmpty())
        {
            throw new IllegalArgumentException("Invalid symbol icon");
        }
        ItemStack copy = icon.copyWithCount(1);
        var encoded = ItemStack.CODEC.encodeStart(services.server.registryAccess().createSerializationContext(JsonOps.INSTANCE), copy).getOrThrow();
        return services.state.updateAsync(root -> {
                           var items = root.has("symbolIcons") ? root.getAsJsonObject("symbolIcons") : new JsonObject();
                           items.add(Long.toString(symbol), encoded);
                           root.add("symbolIcons", items);
                       })
                             .thenCompose(unused -> services.onServer(() -> {
                                 icons.put(symbol, copy);
                                 return null;
                             }));
    }

    public GuiElement icon(TradingGui gui, SymbolView symbol, Runnable action)
    {
        ItemStack stack = icons.get(symbol.symbolId());
        if (stack == null)
        {
            return TradingGui.icon(gui.getPlayer(), GuiWidgets.asset(gui, symbol.quoteAssetId(), "", action), symbol.name(), GuiWidgets.text(gui, "ui.symbols.type", symbol.type()));
        }
        var builder = new GuiElementBuilder(stack.copy()).setName(TradingGui.text(gui.getPlayer(), symbol.name()))
                                                         .addLoreLine(TradingGui.text(gui.getPlayer(), "ui.symbols.type", ExchangeMessages.label(gui.getPlayer(), symbol.type())));
        if (action != null)
        {
            builder.setCallback((i, t, c, g) -> GuiActions.run(gui, action));
        }
        return builder.build();
    }

    public CompletableFuture<Symbol> createSpot(String name, long base, long quote)
    {
        return create(name, SymbolType.Spot, base, quote, 0, null, false);
    }

    public CompletableFuture<Symbol> createPerpetual(String name, long base, long quote, long mark, ContractSpec spec)
    {
        return createPerpetual(name, base, quote, mark, spec, false);
    }

    CompletableFuture<Symbol> createPerpetual(String name, long base, long quote, long mark, ContractSpec spec, boolean leaseOwned)
    {
        if (mark <= 0)
        {
            throw new IllegalArgumentException("Positive initial mark required");
        }
        Objects.requireNonNull(spec);
        if (spec.firstFundingTimestamp() == 0)
        {
            spec = spec.withFirstFundingTimestamp(Math.addExact(System.currentTimeMillis(), spec.fundingInterval()));
        }
        return create(name, SymbolType.Perpetual, base, quote, mark, spec, leaseOwned);
    }

    public CompletableFuture<Symbol> createFuture(String name, long base, long quote, long mark, ContractSpec spec)
    {
        return createFuture(name, base, quote, mark, spec, false);
    }

    CompletableFuture<Symbol> createFuture(String name, long base, long quote, long mark, ContractSpec spec, boolean leaseOwned)
    {
        if (mark <= 0 || spec.expiryTimestamp() <= System.currentTimeMillis())
        {
            throw new IllegalArgumentException("Future requires a positive mark and future expiry");
        }
        return create(name, SymbolType.Future, base, quote, mark, spec, leaseOwned);
    }

    private CompletableFuture<Symbol> create(String name, SymbolType type, long base, long quote, long mark, ContractSpec spec, boolean leaseOwned)
    {
        if (base == quote || name.isBlank())
        {
            throw new IllegalArgumentException("Invalid market");
        }
        services.assets.find(base).orElseThrow();
        services.assets.find(quote).orElseThrow();
        if (!services.assets.enabled(base) || !services.assets.enabled(quote))
        {
            throw new IllegalStateException("Asset is disabled");
        }
        if (!creating.add(name))
        {
            return CompletableFuture.failedFuture(new IllegalStateException("ui.operation.busy"));
        }
        var work = SymbolUtil.findByNameFresh(name).thenCompose(existing -> {
                                 if (!existing.isEmpty())
                                 {
                                     if (existing.size() != 1 || existing.getFirst().baseAssetId() != base || existing.getFirst().quoteAssetId() != quote || existing.getFirst().type() != type)
                                     {
                                         return CompletableFuture.<Symbol>failedFuture(new IllegalArgumentException("Symbol name already has different assets/type"));
                                     }
                                     return CompletableFuture.completedFuture(CapitalMC.exchange.symbols().get(existing.getFirst().symbolId()));
                                 }
                                 return CapitalMC.exchange.symbols().create(name, type, CapitalMC.exchange.assets().get(base), CapitalMC.exchange.assets().get(quote));
                             }).thenCompose(symbol -> CapitalMC.exchange.books().list(new ObservationQuery().withSymbolId(symbol.id())).thenCompose(books -> {
                                 if (books.page().state() != ReadState.Success && books.page().state() != ReadState.NotFound)
                                 {
                                     throw new BusinessException(books.page());
                                 }
                                 if (!books.values().isEmpty())
                                 {
                                     return CompletableFuture.completedFuture(symbol);
                                 }
                                 return (type == SymbolType.Spot ? CapitalMC.exchange.books().addSpot(symbol, OrderBookImplementation.Radix)
                                         : CapitalMC.exchange.books().addContract(symbol, spec, mark, System.currentTimeMillis(), OrderBookImplementation.Radix)).thenApply(book -> symbol);
                             })).thenCompose(symbol -> {
                                 if (type == SymbolType.Spot)
                                 {
                                     return CompletableFuture.completedFuture(symbol);
                                 }
                                 return SymbolUtil.allSymbolsFresh().thenCompose(all -> {
                                     var spot = all.stream().filter(s -> s.active() && s.type() == SymbolType.Spot && s.baseAssetId() == base && s.quoteAssetId() == quote).findFirst();
                                     return spot.isEmpty() ? CompletableFuture.completedFuture(symbol) : CapitalMC.exchange.contracts().bindSpot(symbol, CapitalMC.exchange.symbols().get(spot.get().symbolId())).thenApply(receipt -> symbol);
                                 });
                             }).thenCompose(symbol -> symbol.snapshot().thenCompose(view -> enableTickBand(view, leaseOwned)).thenApply(unused -> symbol))
                             .thenCompose(symbol -> CapitalMC.exchange.submit(new Flush()).thenApply(status -> {
                                 if (!status.enabled() || status.faulted() || status.durableThrough() < status.sequence())
                                 {
                                     throw new IllegalStateException("Market setup not durable");
                                 }
                                 return symbol;
                             }));
        return work.whenComplete((value, error) -> creating.remove(name));
    }

    /**
     * Native reference/funding/liquidation maintenance. Called once a second on the server thread.
     */
    public void tick()
    {
        if (CapitalMC.runtime != null) return; // The shared owner runs maintenance once for all gateways.
        if (updating || !services.ready().isDone() || services.ready().isCompletedExceptionally())
        {
            return;
        }
        updating = true;
        SymbolUtil.allSymbols().thenCompose(symbols -> {
            var tasks = symbols.stream().filter(s -> s.active() && s.type() != SymbolType.Spot).map(s -> CapitalMC.exchange.contracts().refresh(CapitalMC.exchange.symbols().get(s.symbolId()), System.currentTimeMillis())).toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(tasks);
        }).whenComplete((unused, error) -> services.server.execute(() -> {
            updating = false;
            if (error != null)
            {
                CapitalMC.logger.debug("Contract maintenance failed", error);
            }
        }));
    }
}
