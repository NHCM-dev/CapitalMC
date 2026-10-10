package nhcm.capitalmc.service;

import com.google.gson.*;
import nhcm.advmatch.BusinessException;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.exchange.BalanceUtil;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The issuer account owns issued electronic currency and redemption reserves.
 */
public class CurrencyService
{
    private final GameServices services;
    private volatile long issuerUser;
    private volatile long issuerAccount;
    private final Set<UUID> exchanging = ConcurrentHashMap.newKeySet();

    public CurrencyService(GameServices services)
    {
        this.services = services;
    }

    public CompletableFuture<Void> initialize()
    {
        JsonObject root = services.state.snapshot();
        var user = root.has("issuerUser") ? CapitalMC.exchange.users().find(root.get("issuerUser").getAsLong()) : CapitalMC.exchange.users().create();
        return user.thenCompose(value -> value.spotAccount().thenCompose(account -> ApplicationExecutors.io(() -> {
            issuerUser = value.id();
            issuerAccount = account.id();
            services.state.update(data -> data.addProperty("issuerUser", issuerUser));
            return (Void) null;
        })));
    }

    public record Supply(AuditReport audit, List<BalanceView> issuer)
    {
        public BigInteger circulating(long assetId)
        {
            BigInteger total = audit.assets().stream().filter(value -> value.assetId() == assetId)
                    .map(value -> new BigInteger(value.actual())).findFirst().orElse(BigInteger.ZERO);
            BigInteger held = issuer.stream().filter(value -> value.assetId() == assetId)
                    .map(value -> BigInteger.valueOf(value.active()).add(BigInteger.valueOf(value.reserved())))
                    .reduce(BigInteger.ZERO, BigInteger::add);
            return total.subtract(held);
        }
    }

    private CompletableFuture<Supply> supplyQuery;
    private long supplyQueried;

    /**
     * Read-only native views; never infer currency supply from application journals.
     */
    public synchronized CompletableFuture<Supply> supply()
    {
        if (supplyQuery == null || System.nanoTime() - supplyQueried >= 500_000_000L && supplyQuery.isDone())
        {
            supplyQueried = System.nanoTime();
            supplyQuery = CapitalMC.exchange.audit().report(1).thenCombine(BalanceUtil.allSpotBalances(issuerUser), Supply::new);
        }
        return supplyQuery.copy();
    }

    public long issuerUserId()
    {
        return issuerUser;
    }

    /**
     * Privileged server/plugin API: minting never happens through a player-facing button.
     */
    public CompletableFuture<CommandReceipt> mint(long electronicAsset, long amount)
    {
        return mint(electronicAsset, amount, "capitalmc/plugin", "Legacy privileged mint API");
    }

    public CompletableFuture<CommandReceipt> mint(long electronicAsset, long amount, String operator, String reason)
    {
        return mint(electronicAsset, amount, operator, reason, UUID.randomUUID());
    }

    public CompletableFuture<CommandReceipt> mint(long electronicAsset, long amount, String operator, String reason, UUID operation)
    {
        requireOrdinaryAsset(electronicAsset);
        if (amount <= 0 || !asset(electronicAsset).electronic())
        {
            throw new IllegalArgumentException("Mint only electronic assets");
        }
        return services.settlements.adjustment(issuerAccount, electronicAsset, amount, "MINT", operator, reason, operation.toString());
    }

    public CompletableFuture<CommandReceipt> burn(long electronicAsset, long amount, String operator, String reason)
    {
        return burn(electronicAsset, amount, operator, reason, UUID.randomUUID());
    }

    public CompletableFuture<CommandReceipt> burn(long electronicAsset, long amount, String operator, String reason, UUID operation)
    {
        requireOrdinaryAsset(electronicAsset);
        if (amount <= 0 || !asset(electronicAsset).electronic())
        {
            throw new IllegalArgumentException("Burn only electronic assets");
        }
        return services.settlements.adjustment(issuerAccount, electronicAsset, -amount, "BURN", operator, reason, operation.toString());
    }

    public CompletableFuture<CommandReceipt> issue(ServerUser recipient, long electronicAsset, long amount)
    {
        requireOrdinaryAsset(electronicAsset);
        if (!asset(electronicAsset).electronic())
        {
            throw new IllegalArgumentException("Not an electronic asset");
        }
        return services.settlements.account(recipient, AccountType.Spot).thenCompose(target -> services.settlements.transfer(issuerAccount, target, electronicAsset, amount));
    }

    public record Limits(long minimum, long maximum)
    {
    }

    public static class FundsException extends IllegalArgumentException
    {
        public final long assetId, required, available;

        public FundsException(String key, long assetId, long required, long available)
        {
            super(key);
            this.assetId = assetId;
            this.required = required;
            this.available = available;
        }
    }

    public long quoteAsset(long base)
    {
        var root = services.state.section("pegs");
        if (!root.has("pegs") || !root.getAsJsonObject("pegs").has(Long.toString(base)))
        {
            throw new IllegalArgumentException("ui.peg.none");
        }
        return root.getAsJsonObject("pegs").getAsJsonObject(Long.toString(base)).get("currency").getAsLong();
    }

    public Limits limits(long base)
    {
        var root = services.state.section("pegs");
        if (!root.has("pegs") || !root.getAsJsonObject("pegs").has(Long.toString(base)))
        {
            throw new IllegalArgumentException("ui.peg.none");
        }
        var peg = root.getAsJsonObject("pegs").getAsJsonObject(Long.toString(base));
        return new Limits(peg.has("minimum") ? peg.get("minimum").getAsLong() : 1,
                          peg.has("maximum") ? peg.get("maximum").getAsLong() : Long.MAX_VALUE);
    }

    /**
     * Advisory native funds check. The subsequent atomic transaction still validates every leg.
     */
    public CompletableFuture<Void> check(ServerUser user, Quote preview)
    {
        if (!preview.equals(quote(preview.baseAsset(), preview.baseAmount(), preview.buy())))
        {
            return CompletableFuture.failedFuture(new IllegalStateException("ui.peg.changed"));
        }
        long paidAsset = preview.buy() ? preview.quoteAsset() : preview.baseAsset();
        long paid = preview.buy() ? preview.quoteAmount() : preview.baseAmount();
        long receivedAsset = preview.buy() ? preview.baseAsset() : preview.quoteAsset();
        long received = preview.buy() ? preview.baseAmount() : preview.quoteAmount();
        return BalanceUtil.spotBalance(user, paidAsset).thenCombine(BalanceUtil.balance(issuerAccount, receivedAsset), (own, reserve) -> {
            if (own.map(BalanceView::active).orElse(0L) < paid)
            {
                throw new FundsException("ui.peg.user_funds", paidAsset, paid, own.map(BalanceView::active).orElse(0L));
            }
            if (reserve.map(BalanceView::active).orElse(0L) < received)
            {
                // Public failures must not carry the issuer's private balance.
                throw new IllegalStateException("ui.peg.reserve_funds");
            }
            return (Void) null;
        });
    }

    public CompletableFuture<CommandReceipt> reserve(ServerUser user, long asset, long amount, boolean deposit, String operator, String reason)
    {
        requireOrdinaryAsset(asset);
        if (amount <= 0 || !services.assets.enabled(asset))
        {
            return CompletableFuture.failedFuture(new IllegalArgumentException("ui.units.invalid"));
        }
        return services.settlements.account(user, AccountType.Spot).thenCompose(account -> {
            long from = deposit ? account : issuerAccount, to = deposit ? issuerAccount : account;
            if (services.settlements.unresolvedTransfer(from) || services.settlements.unresolvedTransfer(to))
            {
                throw new IllegalStateException("ui.operation.review");
            }
            return services.settlements.transactionWithRequest(List.of(new BalanceDelta(from, asset, -amount), new BalanceDelta(to, asset, amount)),
                                                               true, "TRANSFER", operator, (deposit ? "Reserve deposit: " : "Reserve return: ") + reason, UUID.randomUUID().toString()).thenApply(SettlementService.Mutation::receipt);
        });
    }

    public CompletableFuture<Void> setPeg(long itemAsset, long currencyAsset, long currencyRawPerItem)
    {
        return configurePeg(itemAsset, currencyAsset, asset(itemAsset).parse("1"),
                            currencyRawPerItem, 0, 1, Long.MAX_VALUE, true, true, true);
    }

    /**
     * Ratios and limits use the respective asset's raw units, never floating point.
     */
    public CompletableFuture<Void> configurePeg(long base, long quote, long baseUnits, long quoteUnits, int feePpm,
                                                long minimum, long maximum, boolean buy, boolean sell, boolean enabled)
    {
        requireOrdinaryAsset(base);
        requireOrdinaryAsset(quote);
        asset(base);
        asset(quote);
        if (base == quote || baseUnits <= 0 || quoteUnits <= 0 || feePpm < 0 || feePpm >= 1_000_000 || minimum <= 0 || maximum < minimum)
        {
            throw new IllegalArgumentException("ui.peg.configuration_invalid");
        }
        return services.state.updateAsync(root -> {
            JsonObject pegs = root.has("pegs") ? root.getAsJsonObject("pegs") : new JsonObject();
            JsonObject peg = new JsonObject();
            peg.addProperty("currency", quote);
            peg.addProperty("rate", quoteUnits);
            peg.addProperty("baseUnits", baseUnits);
            peg.addProperty("feePpm", feePpm);
            peg.addProperty("minimum", minimum);
            peg.addProperty("maximum", maximum);
            peg.addProperty("buy", buy);
            peg.addProperty("sell", sell);
            peg.addProperty("enabled", enabled);
            pegs.add(Long.toString(base), peg);
            root.add("pegs", pegs);
        });
    }

    public List<Long> peggedAssets()
    {
        JsonObject data = services.state.section("pegs");
        return data.has("pegs") ? data.getAsJsonObject("pegs").keySet().stream().map(Long::parseLong).toList() : List.of();
    }

    public String describePeg(long itemAsset)
    {
        JsonObject peg = services.state.section("pegs").getAsJsonObject("pegs").getAsJsonObject(Long.toString(itemAsset));
        var item = asset(itemAsset);
        var currency = asset(peg.get("currency").getAsLong());
        return item.format(peg.has("baseUnits") ? peg.get("baseUnits").getAsLong() : 1) + " " + item.displayName()
               + " = " + currency.format(peg.get("rate").getAsLong()) + " " + currency.displayName();
    }

    public record Quote(long baseAsset, long quoteAsset, long baseAmount, long quoteAmount, long fee, boolean buy,
                        String configuration)
    {
    }

    /**
     * An exact preview; confirmations fail if the ratio, fees or limits change meanwhile.
     */
    public Quote quote(long base, long amount, boolean buy)
    {
        JsonObject pegs = services.state.section("pegs").getAsJsonObject("pegs");
        if (pegs == null || !pegs.has(Long.toString(base)))
        {
            throw new IllegalArgumentException("ui.peg.none");
        }
        var peg = pegs.getAsJsonObject(Long.toString(base));
        long quoteAsset = peg.get("currency").getAsLong();
        if (!services.assets.enabled(base) || !services.assets.enabled(quoteAsset))
        {
            throw new IllegalStateException("ui.peg.disabled");
        }
        return calculate(base, amount, buy, peg);
    }

    static Quote calculate(long base, long amount, boolean buy, JsonObject peg)
    {
        if (amount <= 0)
        {
            throw new IllegalArgumentException("ui.input.precision");
        }
        if (peg.has("enabled") && !peg.get("enabled").getAsBoolean()
            || peg.has(buy ? "buy" : "sell") && !peg.get(buy ? "buy" : "sell").getAsBoolean())
        {
            throw new IllegalStateException("ui.peg.disabled");
        }
        if (amount < (peg.has("minimum") ? peg.get("minimum").getAsLong() : 1)
            || amount > (peg.has("maximum") ? peg.get("maximum").getAsLong() : Long.MAX_VALUE))
        {
            throw new IllegalArgumentException("ui.peg.limits");
        }
        var ratio = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(peg.get("rate").getAsLong()))
                              .divideAndRemainder(BigInteger.valueOf(peg.has("baseUnits") ? peg.get("baseUnits").getAsLong() : 1));
        if (ratio[1].signum() != 0)
        {
            throw new IllegalArgumentException("ui.peg.precision");
        }
        var fee = ratio[0].multiply(BigInteger.valueOf(peg.has("feePpm") ? peg.get("feePpm").getAsInt() : 0))
                          .add(BigInteger.valueOf(999_999)).divide(BigInteger.valueOf(1_000_000));
        long cash;
        try
        {
            cash = (buy ? ratio[0].add(fee) : ratio[0].subtract(fee)).longValueExact();
        } catch (ArithmeticException error)
        {
            throw new ArithmeticException("ui.peg.overflow");
        }
        if (cash <= 0)
        {
            throw new IllegalArgumentException("ui.peg.zero");
        }
        return new Quote(base, peg.get("currency").getAsLong(), amount, cash, fee.longValueExact(), buy, peg.toString());
    }

    public CompletableFuture<CommandReceipt> exchange(ServerUser user, long base, long amount, boolean buy)
    {
        try
        {
            return exchange(user, quote(base, amount, buy));
        } catch (RuntimeException error)
        {
            return CompletableFuture.failedFuture(error);
        }
    }

    public CompletableFuture<CommandReceipt> exchange(ServerUser user, Quote preview)
    {
        if (!exchanging.add(user.getUuid()))
        {
            return CompletableFuture.failedFuture(new IllegalStateException("ui.operation.busy"));
        }
        try
        {
            if (unresolved(user.getUuid()))
            {
                throw new IllegalStateException("ui.operation.review");
            }
            return check(user, preview).thenCompose(unused -> ApplicationExecutors.io(() -> exchangeInternal(user, preview)))
                                       .thenCompose(future -> future).whenComplete((value, error) -> exchanging.remove(user.getUuid()));
        } catch (RuntimeException error)
        {
            exchanging.remove(user.getUuid());
            return CompletableFuture.failedFuture(error);
        }
    }

    private CompletableFuture<CommandReceipt> exchangeInternal(ServerUser user, Quote preview)
    {
        if (!preview.equals(quote(preview.baseAsset(), preview.baseAmount(), preview.buy())))
        {
            throw new IllegalStateException("ui.peg.changed");
        }
        long itemAsset = preview.baseAsset(), itemCount = preview.baseAmount(), currency = preview.quoteAsset(), cash = preview.quoteAmount();
        boolean redeemCurrency = preview.buy();
        UUID operation = UUID.randomUUID();
        services.state.update(data -> {
            JsonObject operations = data.has("pegOperations") ? data.getAsJsonObject("pegOperations") : new JsonObject();
            JsonObject entry = new JsonObject();
            entry.addProperty("user", user.getUuid().toString());
            entry.addProperty("item", itemAsset);
            entry.addProperty("currency", currency);
            entry.addProperty("count", itemCount);
            entry.addProperty("cash", cash);
            entry.addProperty("redeem", redeemCurrency);
            entry.addProperty("phase", "PENDING");
            operations.add(operation.toString(), entry);
            data.add("pegOperations", operations);
        });
        return services.settlements.account(user, AccountType.Spot).thenCompose(account -> {
            long fromAsset = redeemCurrency ? currency : itemAsset;
            long fromAmount = redeemCurrency ? cash : itemCount;
            long toAsset = redeemCurrency ? itemAsset : currency;
            long toAmount = redeemCurrency ? itemCount : cash;
            return services.settlements.transactionWithRequest(List.of(new BalanceDelta(account, fromAsset, -fromAmount),
                                                                       new BalanceDelta(issuerAccount, fromAsset, fromAmount), new BalanceDelta(issuerAccount, toAsset, -toAmount),
                                                                       new BalanceDelta(account, toAsset, toAmount)), true, "PEG", user.getUuid().toString(),
                                                               "Reserve-backed peg conversion", operation.toString()).thenApply(SettlementService.Mutation::receipt);
        }).whenCompleteAsync((receipt, error) -> phase(operation, error == null ? "COMPLETE" : definiteRejection(error) ? "REJECTED" : "IN_DOUBT"), ApplicationExecutors.IO);
    }

    private boolean definiteRejection(Throwable error)
    {
        while (error.getCause() != null)
            error = error.getCause();
        return error instanceof BusinessException;
    }

    private void phase(UUID id, String phase)
    {
        services.state.update(root -> root.getAsJsonObject("pegOperations").getAsJsonObject(id.toString()).addProperty("phase", phase));
    }

    public boolean unresolved(UUID user)
    {
        var root = services.state.section("pegOperations");
        if (!root.has("pegOperations"))
        {
            return false;
        }
        return root.getAsJsonObject("pegOperations").entrySet().stream().map(e -> e.getValue().getAsJsonObject()).anyMatch(op -> op.get("user").getAsString().equals(user.toString()) && !Set.of("COMPLETE", "REJECTED", "REVERSED", "RECONCILED").contains(op.get("phase").getAsString()));
    }

    public CompletableFuture<Void> reconcile(UUID id, String note)
    {
        if (note.isBlank())
        {
            throw new IllegalArgumentException("Review note required");
        }
        var records = services.state.section("pegOperations");
        if (!records.has("pegOperations") || !records.getAsJsonObject("pegOperations").has(id.toString()))
        {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown peg operation"));
        }
        var user = UUID.fromString(records.getAsJsonObject("pegOperations").getAsJsonObject(id.toString()).get("user").getAsString());
        if (!exchanging.add(user))
        {
            return CompletableFuture.failedFuture(new IllegalStateException("ui.operation.busy"));
        }
        return services.state.updateAsync(root -> {
            var op = root.getAsJsonObject("pegOperations").getAsJsonObject(id.toString());
            if (Set.of("COMPLETE", "REJECTED", "REVERSED", "RECONCILED").contains(op.get("phase").getAsString()))
            {
                throw new IllegalStateException("Operation already resolved");
            }
            op.addProperty("phase", "RECONCILED");
            op.addProperty("review", note);
            op.addProperty("reviewTimestamp", System.currentTimeMillis());
        }).whenComplete((unused, error) -> exchanging.remove(user));
    }

    private void requireOrdinaryAsset(long id)
    {
        if (asset(id).special())
        {
            throw new IllegalArgumentException("ui.asset.special_restricted");
        }
    }

    private McAssetRegistry.Asset asset(long id)
    {
        return services.assets.find(id).orElseThrow();
    }
}
