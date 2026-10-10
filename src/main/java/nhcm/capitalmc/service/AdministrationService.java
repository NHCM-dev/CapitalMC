package nhcm.capitalmc.service;

import com.google.gson.*;
import net.minecraft.world.item.ItemStack;
import nhcm.advmatch.BusinessException;
import nhcm.advmatch.command.management.SetTradingHalt;
import nhcm.advmatch.command.persistence.Checkpoint;
import nhcm.advmatch.command.persistence.Flush;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.exchange.SymbolUtil;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Shared privileged application operations; callers check server permission on execution.
 */
public final class AdministrationService
{
    private final GameServices services;
    private static final Gson JSON = new Gson();

    /**
     * Used only by validation that has run before submitting any mutation.
     */
    private static final class RejectedBeforeMutation extends IllegalArgumentException
    {
        RejectedBeforeMutation(String message)
        {
            super(message);
        }
    }

    public AdministrationService(GameServices services)
    {
        this.services = services;
    }

    public <T> CompletableFuture<T> perform(String operator, String reason, String action, JsonElement payload, Supplier<CompletableFuture<T>> work)
    {
        if (operator == null || operator.isBlank() || reason == null || reason.isBlank())
        {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Operator and reason required"));
        }
        UUID id = UUID.randomUUID();
        JsonElement snapshot = payload.deepCopy();
        return services.state.updateAsync(root -> {
                           var log = root.has("adminOperations") ? root.getAsJsonObject("adminOperations") : new JsonObject();
                           var record = new JsonObject();
                           record.addProperty("operator", operator);
                           record.addProperty("reason", reason);
                           record.addProperty("action", action);
                           record.addProperty("timestamp", System.currentTimeMillis());
                           record.addProperty("phase", "INTENT");
                           record.add("payload", snapshot);
                           log.add(id.toString(), record);
                           root.add("adminOperations", log);
                       }).thenCompose(unused -> services.onServer(work::get)).thenCompose(future -> future)
                             .thenCompose(value -> CapitalMC.exchange.submit(new Flush()).thenCompose(status -> {
                                 if (!status.enabled() || status.faulted() || status.durableThrough() < status.sequence())
                                 {
                                     throw new IllegalStateException("Admin operation is not durable");
                                 }
                                 return services.state.updateAsync(root -> {
                                     var record = root.getAsJsonObject("adminOperations").getAsJsonObject(id.toString());
                                     record.addProperty("phase", "COMPLETE");
                                     record.addProperty("durableThrough", status.durableThrough());
                                     if (value instanceof CommandReceipt receipt)
                                     {
                                         record.addProperty("sequence", receipt.sequence());
                                         record.add("receipt", JSON.toJsonTree(receipt));
                                     }
                                 }).thenApply(unused -> value);
                             })).exceptionallyCompose(error -> services.state.updateAsync(root -> {
                                                                           if (!root.has("adminOperations") || !root.getAsJsonObject("adminOperations").has(id.toString()))
                                                                           {
                                                                               return;
                                                                           }
                                                                           var record = root.getAsJsonObject("adminOperations").getAsJsonObject(id.toString());
                                                                           Throwable cause = error;
                                                                           while (cause.getCause() != null)
                                                                               cause = cause.getCause();
                                                                           record.addProperty("phase", cause instanceof BusinessException || cause instanceof RejectedBeforeMutation ? "REJECTED" : "IN_DOUBT");
                                                                           record.addProperty("error", cause.getClass().getSimpleName());
                                                                       }).handle((unused, journalError) -> {
                                                                           if (journalError != null)
                                                                           {
                                                                               error.addSuppressed(journalError);
                                                                           }
                                                                           return error;
                                                                       })
                                                                             .thenCompose(original -> CompletableFuture.failedFuture(original)));
    }

    /**
     * JSON patches merge with an existing complete native DTO; omitted fields retain values.
     */
    public static <T> T patch(T original, String input, Class<T> type)
    {
        JsonObject object = JSON.toJsonTree(original).getAsJsonObject();
        merge(object, JsonParser.parseString(input).getAsJsonObject());
        return JSON.fromJson(object, type);
    }

    private static void merge(JsonObject target, JsonObject patch)
    {
        for (var entry : patch.entrySet())
        {
            if (!target.has(entry.getKey()) || entry.getValue().isJsonNull())
            {
                throw new IllegalArgumentException("Unknown/null setting: " + entry.getKey());
            }
            if (entry.getValue().isJsonObject() && target.get(entry.getKey()).isJsonObject())
            {
                merge(target.getAsJsonObject(entry.getKey()), entry.getValue().getAsJsonObject());
            }
            else
            {
                target.add(entry.getKey(), entry.getValue());
            }
        }
    }

    public CompletableFuture<?> action(String action, String[] args, ItemStack held, String operator, String reason)
    {
        return action(action, args, held, operator, reason, () -> true);
    }

    public CompletableFuture<?> action(String action, String[] args, ItemStack held, String operator, String reason, BooleanSupplier authorized)
    {
        String[] captured = args.clone();
        validateArguments(action, captured);
        ItemStack snapshot = held.copy();
        return perform(operator, reason, action, JSON.toJsonTree(captured), () -> {
            if (!authorized.getAsBoolean())
            {
                throw new SecurityException("ui.admin.denied");
            }
            return dispatch(action, captured, snapshot, operator, reason);
        });
    }

    static void validateArguments(String action, String[] args)
    {
        int count = switch (action)
        {
            case "bind", "unbind", "asset-enable", "asset-disable", "halt", "resume", "expire", "icon", "refund",
                 "review-items", "review-pegs", "review-transfers", "review-commands" -> 1;
            case "display", "rules", "schedule", "reference", "funding", "funding-percent", "deliver", "mark", "mint",
                 "burn", "register", "price-tick", "quantity-step", "min-quantity", "min-notional" -> 2;
            case "electronic", "issue", "peg", "fees" -> 3;
            case "peg-config", "peg-human" -> 10;
            case "asset-units", "lot" -> 4;
            case "reserve-in", "reserve-out" -> 3;
            case "ban", "unban" -> 1;
            case "balance-add", "balance-remove" -> 4;
            case "checkpoint" -> 0;
            case "symbol" -> -1;
            default -> throw new IllegalArgumentException("Unsupported administration action: " + action);
        };
        if (action.equals("checkpoint") && args.length == 1 && args[0].equalsIgnoreCase("yes"))
        {
            return;
        }
        // The management GUI asks for an explicit acknowledgement for parameterless mutations.
        if (count == 1 && args.length == 2 && args[1].equalsIgnoreCase("yes"))
        {
            return;
        }
        if (count >= 0 && args.length != count || count == -1 && (args.length < 4 || args.length > 6
                                                                  || !args[0].equalsIgnoreCase("spot") && args.length < 5))
        {
            throw new IllegalArgumentException("Incorrect action arguments");
        }
        if (Arrays.stream(args).anyMatch(String::isBlank))
        {
            throw new IllegalArgumentException("Empty action argument");
        }
    }

    private static boolean bool(String input)
    {
        if (!input.equalsIgnoreCase("true") && !input.equalsIgnoreCase("false"))
        {
            throw new IllegalArgumentException("Boolean must be true or false");
        }
        return Boolean.parseBoolean(input);
    }

    private static long percent(String value)
    {
        return new BigDecimal(value.strip()).movePointRight(4).longValueExact();
    }

    private McAssetRegistry.Asset asset(String name)
    {
        return services.assets.find(name).orElseThrow(() -> new IllegalArgumentException("Unknown asset: " + name));
    }

    private CompletableFuture<?> dispatch(String action, String[] a, ItemStack held, String operator, String reason)
    {
        switch (action)
        {
            case "ban", "unban" ->
            {
                var user = CapitalMC.serverUserManager.findByUsername(a[0]).orElseThrow(() -> new RejectedBeforeMutation("cli.user.unknown"));
                return services.state.updateAsync(root -> {
                    var restrictions = root.has("userRestrictions") ? root.getAsJsonObject("userRestrictions") : new JsonObject();
                    var entry = new JsonObject();
                    entry.addProperty("banned", action.equals("ban"));
                    entry.addProperty("operator", operator);
                    entry.addProperty("reason", reason);
                    entry.addProperty("timestamp", System.currentTimeMillis());
                    restrictions.add(user.getUuid().toString(), entry);
                    root.add("userRestrictions", restrictions);
                }).thenCompose(unused -> services.onServer(() -> {
                    if (action.equals("ban"))
                    {
                        for (UUID player : CapitalMC.serverUserManager.playersUsing(user.getUuid()))
                        {
                            services.items.cancelSelection(player);
                            CapitalMC.guiInputManager.cancel(player);
                        }
                        CapitalMC.serverUserManager.revokeSessions(user.getUuid());
                    }
                    return null;
                }));
            }
            case "balance-add", "balance-remove" ->
            {
                var user = CapitalMC.serverUserManager.findByUsername(a[0]).orElseThrow(() -> new RejectedBeforeMutation("cli.user.unknown"));
                var asset = asset(a[2]);
                long amount = asset.parse(a[3]);
                if (amount <= 0)
                {
                    throw new RejectedBeforeMutation("ui.trade.quantity_step");
                }
                var backend = CapitalMC.exchange.users().get(user.getExchangeUserId());
                var account = a[1].equals("spot") ? backend.spotAccount() : backend.contractAccount();
                return account.thenCompose(value -> services.settlements.adjustment(value.id(), asset.id(), action.equals("balance-add") ? amount : -amount,
                                                                                    "ADMIN_ADJUSTMENT", operator, reason, null));
            }
            case "bind", "unbind", "display", "asset-enable", "asset-disable" ->
            {
                var asset = asset(a[0]);
                boolean enabled = action.equals("asset-enable") || !action.equals("asset-disable") && services.assets.enabled(asset.id());
                var halted = action.equals("asset-disable") ? SymbolUtil.allSymbols().thenCompose(symbols -> CompletableFuture.allOf(symbols.stream().filter(s -> s.active() && (s.baseAssetId() == asset.id() || s.quoteAssetId() == asset.id()))
                                                                                                                                            .map(s -> CapitalMC.exchange.submit(new SetTradingHalt(s.symbolId(), true, System.currentTimeMillis(), reason)).thenAccept(receipt -> {
                                                                                                                                                if (!receipt.result().accepted())
                                                                                                                                                {
                                                                                                                                                    throw new BusinessException(receipt);
                                                                                                                                                }
                                                                                                                                            })).toArray(CompletableFuture[]::new))) : CompletableFuture.<Void>completedFuture(null);
                return halted.thenCompose(unused -> services.assets.edit(asset.id(), action.equals("display") ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : asset.displayName(),
                                                                         action.equals("bind") ? held : null, action.equals("bind") ? Boolean.FALSE : action.equals("unbind") ? Boolean.TRUE : null, enabled));
            }
            case "icon" ->
            {
                long id = Long.parseLong(a[0]);
                return CapitalMC.exchange.symbols().get(id).snapshot()
                                         .thenCompose(view -> services.onServer(() -> services.markets.setIcon(id, held))).thenCompose(future -> future);
            }
            case "issue" ->
            {
                var user = CapitalMC.serverUserManager.findByUsername(a[0]).orElseThrow();
                var currency = asset(a[1]);
                return services.currency.issue(user, currency.id(), currency.parse(a[2]));
            }
            case "peg" ->
            {
                var base = asset(a[0]);
                var quote = asset(a[1]);
                return services.currency.setPeg(base.id(), quote.id(), quote.parse(a[2]));
            }
            case "review-items" ->
            {
                return services.items.reconcile(UUID.fromString(a[0]), operator, reason);
            }
            case "review-pegs" ->
            {
                return services.currency.reconcile(UUID.fromString(a[0]), operator + ": " + reason);
            }
            case "review-transfers" ->
            {
                return services.settlements.reconcileTransfer(UUID.fromString(a[0]), operator + ": " + reason);
            }
            case "review-commands" ->
            {
                return services.settlements.reconcileCommand(Long.parseLong(a[0]), operator, reason);
            }
            case "halt", "resume" ->
            {
                long id = Long.parseLong(a[0]);
                var validation = action.equals("resume") && id != 0 ? CapitalMC.exchange.symbols().get(id).snapshot().thenAccept(view -> {
                    if (!services.assets.enabled(view.baseAssetId()) || !services.assets.enabled(view.quoteAssetId()))
                    {
                        throw new RejectedBeforeMutation("ui.asset.disabled");
                    }
                }) : CompletableFuture.<Void>completedFuture(null);
                return validation.thenCompose(unused -> CapitalMC.exchange.submit(new SetTradingHalt(id, action.equals("halt"), System.currentTimeMillis(), reason)))
                                 .thenApply(receipt -> {
                                     if (!receipt.result().accepted())
                                     {
                                         throw new BusinessException(receipt);
                                     }
                                     return receipt;
                                 });
            }
            case "rules" ->
            {
                long id = Long.parseLong(a[0]);
                return CapitalMC.exchange.symbols().get(id).snapshot().thenCompose(view ->
                                                                                           CapitalMC.exchange.rules().configure(CapitalMC.exchange.symbols().get(id), patch(view.tradingRules(), String.join(" ", Arrays.copyOfRange(a, 1, a.length)), TradingRules.class)));
            }
            case "schedule" ->
            {
                long id = Long.parseLong(a[0]);
                var symbol = CapitalMC.exchange.symbols().get(id);
                return CapitalMC.exchange.contracts().market(symbol).thenCompose(view -> CapitalMC.exchange.contracts().configureSchedule(symbol,
                                                                                                                                          patch(new ContractSchedule().withFundingInterval(view.spec().fundingInterval()).withNextFundingTimestamp(view.nextFundingTimestamp())
                                                                                                                                                                      .withMaxFundingRate(view.spec().maxFundingRate()).withExpiryTimestamp(view.spec().expiryTimestamp()),
                                                                                                                                                String.join(" ", Arrays.copyOfRange(a, 1, a.length)), ContractSchedule.class), System.currentTimeMillis()));
            }
            case "reference" ->
            {
                long id = Long.parseLong(a[0]);
                var symbol = CapitalMC.exchange.symbols().get(id);
                return CapitalMC.exchange.contracts().market(symbol).thenCompose(view -> CapitalMC.exchange.contracts().configureReference(symbol, patch(view.referenceConfig(), String.join(" ", Arrays.copyOfRange(a, 1, a.length)), ReferencePriceConfig.class)));
            }
            case "funding", "funding-percent" ->
            {
                return CapitalMC.exchange.contracts().settleFunding(CapitalMC.exchange.symbols().get(Long.parseLong(a[0])), action.equals("funding-percent") ? Math.toIntExact(percent(a[1])) : Integer.parseInt(a[1]), System.currentTimeMillis());
            }
            case "price-tick", "quantity-step", "min-quantity", "min-notional", "fees" ->
            {
                var symbol = CapitalMC.exchange.symbols().get(Long.parseLong(a[0]));
                return symbol.snapshot().thenCompose(s -> {
                    var base = services.assets.find(s.baseAssetId()).orElseThrow();
                    var quote = services.assets.find(s.quoteAssetId()).orElseThrow();
                    var rules = s.tradingRules();
                    rules = switch (action)
                    {
                        case "price-tick" -> rules.withPriceTick(base.units().parsePrice(a[1], quote.units()));
                        case "quantity-step" -> rules.withQuantityStep(services.markets.quantityUnits(s).parse(a[1]));
                        case "min-quantity" -> rules.withMinQuantity(services.markets.quantityUnits(s).parse(a[1]));
                        case "min-notional" -> rules.withMinOrderNotional(a[1].equals("0") ? 0 : quote.parse(a[1]));
                        default -> rules.withFees(new FeeSchedule(percent(a[1]), percent(a[2])));
                    };
                    return CapitalMC.exchange.rules().configure(symbol, rules);
                });
            }
            case "expire" ->
            {
                return CapitalMC.exchange.contracts().expire(CapitalMC.exchange.symbols().get(Long.parseLong(a[0])), System.currentTimeMillis());
            }
            case "deliver", "mark" ->
            {
                long id = Long.parseLong(a[0]);
                var symbol = CapitalMC.exchange.symbols().get(id);
                return symbol.snapshot().thenCompose(s -> {
                    var base = services.assets.find(s.baseAssetId()).orElseThrow();
                    var quote = services.assets.find(s.quoteAssetId()).orElseThrow();
                    long price = base.units().parsePrice(a[1], quote.units());
                    return action.equals("deliver") ? CapitalMC.exchange.contracts().deliver(symbol, price, System.currentTimeMillis()) : CapitalMC.exchange.contracts().updateMark(symbol, price, System.currentTimeMillis());
                });
            }
            case "peg-config", "peg-human" ->
            {
                var base = asset(a[0]);
                var quote = asset(a[1]);
                return services.currency.configurePeg(base.id(), quote.id(), base.parse(a[2]), quote.parse(a[3]), action.equals("peg-human") ? Math.toIntExact(percent(a[4])) : Integer.parseInt(a[4]),
                                                      base.parse(a[5]), base.parse(a[6]), bool(a[7]), bool(a[8]), bool(a[9]));
            }
            case "asset-units" ->
            {
                var asset = asset(a[0]);
                var size = new BigDecimal(a[1]);
                var units = new DecimalUnits.Unit(asset.scale(), size);
                return services.assets.configureUnits(asset.id(), size, units.parse(a[2]), units.parse(a[3]));
            }
            case "lot" ->
            {
                long id = Long.parseLong(a[0]);
                return CapitalMC.exchange.symbols().get(id).snapshot().thenCompose(symbol -> {
                    long baseAmount = services.assets.find(symbol.baseAssetId()).orElseThrow().parse(a[1]);
                    CompletableFuture<Long> multiplier = symbol.type() == SymbolType.Spot ? CompletableFuture.completedFuture(1L)
                            : CapitalMC.exchange.contracts().market(CapitalMC.exchange.symbols().get(id)).thenApply(view -> view.spec().contractMultiplier());
                    return multiplier.thenCompose(value -> {
                        if (baseAmount % value != 0)
                        {
                            throw new IllegalArgumentException("ui.input.precision");
                        }
                        long size = baseAmount / value;
                        var unit = new DecimalUnits.Unit(0, BigDecimal.valueOf(size));
                        return services.markets.configureLot(id, size, unit.parse(a[2]), unit.parse(a[3]));
                    });
                });
            }
            case "reserve-in", "reserve-out" ->
            {
                var user = CapitalMC.serverUserManager.findByUsername(a[0]).orElseThrow(() -> new IllegalArgumentException("ui.access.unknown_user"));
                var asset = asset(a[1]);
                return services.currency.reserve(user, asset.id(), asset.parse(a[2]), action.equals("reserve-in"), operator, reason);
            }
            case "mint", "burn" ->
            {
                var asset = asset(a[0]);
                long amount = asset.parse(a[1]);
                return action.equals("mint") ? services.currency.mint(asset.id(), amount, operator, reason) : services.currency.burn(asset.id(), amount, operator, reason);
            }
            case "register", "electronic" ->
            {
                return action.equals("register") ? services.assets.registerItem(a[0], a[1], held) : services.assets.registerElectronic(a[0], a[1], Integer.parseInt(a[2]), held);
            }
            case "symbol" ->
            {
                var base = asset(a[2]);
                var quote = asset(a[3]);
                if (a[0].equalsIgnoreCase("spot"))
                {
                    return services.markets.createSpot(a[1], base.id(), quote.id());
                }
                long mark = base.units().parsePrice(a[4], quote.units());
                var spec = a.length > 5 ? patch(new ContractSpec(), a[5], ContractSpec.class) : new ContractSpec();
                return a[0].equalsIgnoreCase("future") ? services.markets.createFuture(a[1], base.id(), quote.id(), mark, spec) : a[0].equalsIgnoreCase("perpetual") ? services.markets.createPerpetual(a[1], base.id(), quote.id(), mark, spec) : CompletableFuture.failedFuture(new IllegalArgumentException("Unknown symbol type"));
            }
            case "checkpoint" ->
            {
                return CapitalMC.exchange.submit(new Checkpoint());
            }
            case "refund" ->
            {
                return services.items.refundWithdrawal(UUID.fromString(a[0]), operator, reason);
            }
            default ->
            {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported administration action: " + action));
            }
        }
    }
}
