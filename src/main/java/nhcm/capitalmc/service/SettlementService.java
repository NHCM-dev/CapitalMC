package nhcm.capitalmc.service;

import com.google.gson.*;
import nhcm.advmatch.BusinessException;
import nhcm.advmatch.Exchange;
import nhcm.advmatch.command.account.*;
import nhcm.advmatch.command.Command;
import nhcm.advmatch.command.contract.AddMargin;
import nhcm.advmatch.command.order.CancelOrder;
import nhcm.advmatch.command.order.PlaceOrder;
import nhcm.advmatch.command.order.PlaceConditionalOrder;
import nhcm.advmatch.command.order.ModifyConditionalOrder;
import nhcm.advmatch.command.order.CancelConditionalOrder;
import nhcm.advmatch.model.ConditionalOrderRequest;
import nhcm.advmatch.command.persistence.Flush;
import nhcm.advmatch.command.read.ReadPosition;
import nhcm.advmatch.model.ObservationQuery;
import nhcm.advmatch.model.AccountType;
import nhcm.advmatch.model.BalanceDelta;
import nhcm.advmatch.model.CommandReceipt;
import nhcm.advmatch.model.ContractOrderOptions;
import nhcm.advmatch.model.OrderSide;
import nhcm.advmatch.model.OrderType;
import nhcm.advmatch.model.TimeInForce;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.exchange.UserUtil;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Audited, idempotent exchange mutations. In-doubt commands remain in the journal for reconciliation.
 */
public class SettlementService
{
    public record Mutation(long requestId, CommandReceipt receipt)
    {
    }

    private final Exchange exchange;
    private final ServiceState state;
    private final McAssetRegistry assets;
    private final LongSupplier requestIds;
    private final Set<Long> activeRequests = ConcurrentHashMap.newKeySet();

    public SettlementService(GameServices services)
    {
        this.exchange = CapitalMC.exchange;
        this.state = services.state;
        this.assets = services.assets;
        this.requestIds = CapitalMC.runtime == null ? null : CapitalMC.runtime.backend::nextRequestId;
    }

    public SettlementService(Exchange exchange, ServiceState state)
    {
        this.exchange = exchange;
        this.state = state;
        this.assets = null;
        this.requestIds = null;
    }

    public CompletableFuture<Long> account(ServerUser user, AccountType type)
    {
        return UserUtil.getUser(user).thenCompose(value -> type == AccountType.Spot
                ? value.spotAccount().thenApply(account -> account.id()) : value.contractAccount().thenApply(account -> account.id()));
    }

    public CompletableFuture<CommandReceipt> update(long account, long asset, long change)
    {
        return update(account, asset, change, null);
    }

    CompletableFuture<CommandReceipt> update(long account, long asset, long change, String operation)
    {
        return adjustment(account, asset, change, operation == null ? "ADMIN_ADJUSTMENT" : change > 0 ? "DEPOSIT" : "WITHDRAW",
                          "capitalmc/service", "Application settlement", operation);
    }

    public CompletableFuture<CommandReceipt> adjustment(long account, long asset, long change, String type,
                                                        String actor, String reason, String operation)
    {
        return adjustmentWithRequest(account, asset, change, type, actor, reason, operation).thenApply(Mutation::receipt);
    }

    public CompletableFuture<Mutation> adjustmentWithRequest(long account, long asset, long change, String type,
                                                             String actor, String reason, String operation)
    {
        if (assets != null && assets.find(asset).map(McAssetRegistry.Asset::special).orElse(false)
            && !type.equals("DEPOSIT"))
        {
            return CompletableFuture.failedFuture(new IllegalArgumentException("ui.asset.special_restricted"));
        }
        if (!Set.of("MINT", "BURN", "DEPOSIT", "WITHDRAW", "TRANSFER", "ADMIN_ADJUSTMENT").contains(type)
            || actor == null || actor.isBlank() || reason == null || reason.isBlank())
        {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Audited operation type, actor and reason required"));
        }
        if ((type.equals("MINT") || type.equals("DEPOSIT")) && change <= 0
            || (type.equals("BURN") || type.equals("WITHDRAW")) && change >= 0)
        {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Balance change direction does not match its business type"));
        }
        return ApplicationExecutors.io(() -> {
            if (operation != null)
            {
                JsonObject records = state.section("commands");
                if (records.has("commands"))
                {
                    for (var record : records.getAsJsonObject("commands").entrySet())
                    {
                        var entry = record.getValue().getAsJsonObject();
                        if (!entry.has("operation") || !entry.get("operation").getAsString().equals(operation)
                            || !entry.get("type").getAsString().equals(type))
                        {
                            continue;
                        }
                        if (entry.get("source").getAsLong() != account || entry.get("asset").getAsLong() != asset || entry.get("amount").getAsLong() != change || !entry.get("actor").getAsString().equals(actor)
                            || !entry.get("reason").getAsString().equals(reason))
                        {
                            throw new IllegalArgumentException("Operation ID reused with a different payload");
                        }
                        if (!entry.has("receipt"))
                        {
                            throw new IllegalStateException("ui.operation.review");
                        }
                        var receipt = new Gson().fromJson(entry.get("receipt"), CommandReceipt.class);
                        if (entry.get("status").getAsString().equals("REJECTED"))
                        {
                            throw new BusinessException(receipt);
                        }
                        if (!entry.get("status").getAsString().equals("DURABLE"))
                        {
                            throw new IllegalStateException("ui.operation.review");
                        }
                        return new Request(Long.parseLong(record.getKey()), receipt);
                    }
                }
            }
            long request = begin(type, account, 0, asset, change, entry -> {
                entry.addProperty("actor", actor);
                entry.addProperty("reason", reason);
                if (operation != null)
                {
                    entry.addProperty("operation", operation);
                }
            });
            return new Request(request, null);
        }).thenCompose(request -> request.previous != null ? CompletableFuture.completedFuture(new Mutation(request.id, request.previous))
                : accepted(submitCustody(new UpdateBalance(account, asset, change, request.id), type, operation), request.id)
                  .thenApply(receipt -> new Mutation(request.id, receipt)));
    }

    private record Request(long id, CommandReceipt previous)
    {
    }

    private CompletableFuture<CommandReceipt> submitCustody(Command<CommandReceipt> command, String type, String operation)
    {
        if (assets == null || CapitalMC.runtime == null || !Set.of("DEPOSIT", "WITHDRAW").contains(type)) return exchange.submit(command);
        var records = state.section("itemOperations").getAsJsonObject("itemOperations");
        var record = records == null || operation == null ? null : records.getAsJsonObject(operation);
        if (record == null || !record.has("bindings"))
        {
            return CompletableFuture.failedFuture(new IllegalStateException("ui.operation.review"));
        }
        return CapitalMC.runtime.backend.custody(command, record.getAsJsonObject("bindings"));
    }

    /**
     * One native command, one WAL decision: no partially credited basket or transfer.
     */
    public CompletableFuture<Mutation> transactionWithRequest(List<BalanceDelta> changes,
                                                              boolean conserve, String type, String actor, String reason, String operation)
    {
        var legs = List.copyOf(changes);
        if (assets != null && legs.stream().anyMatch(leg -> assets.find(leg.assetId()).map(McAssetRegistry.Asset::special).orElse(false))
            && !type.equals("DEPOSIT") && !type.equals("TRANSFER"))
        {
            return CompletableFuture.failedFuture(new IllegalArgumentException("ui.asset.special_restricted"));
        }
        if (legs.isEmpty() || legs.size() > 256 || actor == null || actor.isBlank() || reason == null || reason.isBlank()
            || !Set.of("DEPOSIT", "TRANSFER", "PEG", "ADMIN_ADJUSTMENT").contains(type)
            || !conserve && !Set.of("DEPOSIT", "ADMIN_ADJUSTMENT").contains(type)
            || type.equals("DEPOSIT") && legs.stream().anyMatch(leg -> leg.change() <= 0))
        {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid audited balance transaction"));
        }
        return ApplicationExecutors.io(() -> {
            JsonElement payload = new Gson().toJsonTree(legs);
            if (operation != null)
            {
                var records = state.section("commands");
                if (records.has("commands"))
                {
                    for (var row : records.getAsJsonObject("commands").entrySet())
                    {
                        var entry = row.getValue().getAsJsonObject();
                        if (!entry.has("changes") || !entry.has("operation") || !operation.equals(entry.get("operation").getAsString()))
                        {
                            continue;
                        }
                        if (!payload.equals(entry.get("changes")) || !type.equals(entry.get("type").getAsString())
                            || conserve != entry.get("conserve").getAsBoolean() || !actor.equals(entry.get("actor").getAsString())
                            || !reason.equals(entry.get("reason").getAsString()))
                        {
                            throw new IllegalArgumentException("Operation ID reused with a different payload");
                        }
                        if (!entry.has("receipt"))
                        {
                            throw new IllegalStateException("ui.operation.review");
                        }
                        var receipt = new Gson().fromJson(entry.get("receipt"), CommandReceipt.class);
                        if (entry.get("status").getAsString().equals("REJECTED"))
                        {
                            throw new BusinessException(receipt);
                        }
                        if (!entry.get("status").getAsString().equals("DURABLE"))
                        {
                            throw new IllegalStateException("ui.operation.review");
                        }
                        return new Request(Long.parseLong(row.getKey()), receipt);
                    }
                }
            }
            long request = begin(type, legs.getFirst().accountId(), 0, 0, 0, entry -> {
                entry.add("changes", payload);
                entry.addProperty("conserve", conserve);
                entry.addProperty("actor", actor);
                entry.addProperty("reason", reason);
                if (operation != null)
                {
                    entry.addProperty("operation", operation);
                }
            });
            return new Request(request, null);
        }).thenCompose(request -> request.previous != null ? CompletableFuture.completedFuture(new Mutation(request.id, request.previous))
                : accepted(submitCustody(new BalanceTransaction(legs, conserve, request.id), type, operation), request.id)
                  .thenApply(receipt -> new Mutation(request.id, receipt)));
    }

    public CompletableFuture<CommandReceipt> transfer(long source, long target, long asset, long amount)
    {
        if (amount <= 0 || source == target)
        {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid transfer"));
        }
        if (unresolvedTransfer(source) || unresolvedTransfer(target))
        {
            return CompletableFuture.failedFuture(new IllegalStateException("ui.operation.review"));
        }
        return transactionWithRequest(List.of(new BalanceDelta(source, asset, -amount),
                                              new BalanceDelta(target, asset, amount)), true, "TRANSFER", "capitalmc/service",
                                      "Account transfer", UUID.randomUUID().toString()).thenApply(Mutation::receipt);
    }

    public boolean unresolvedTransfer(long account)
    {
        var commands = state.section("commands");
        if (commands.has("commands"))
        {
            for (var row : commands.getAsJsonObject("commands").entrySet())
            {
                var entry = row.getValue().getAsJsonObject();
                if (!entry.has("changes") || !entry.get("type").getAsString().equals("TRANSFER")
                    || Set.of("DURABLE", "REJECTED", "RECONCILED").contains(entry.get("status").getAsString()))
                {
                    continue;
                }
                for (var leg : entry.getAsJsonArray("changes"))
                    if (leg.getAsJsonObject().get("accountId").getAsLong() == account)
                    {
                        return true;
                    }
            }
        }
        var root = state.section("crossTransfers");
        if (!root.has("crossTransfers"))
        {
            return false;
        }
        return root.getAsJsonObject("crossTransfers").entrySet().stream().map(entry -> entry.getValue().getAsJsonObject()).anyMatch(entry ->
                                                                                                                                            (entry.get("source").getAsLong() == account || entry.get("target").getAsLong() == account) && !Set.of("COMPLETE", "REJECTED", "REVERSED", "RECONCILED").contains(entry.get("phase").getAsString()));
    }

    public CompletableFuture<Void> reconcileTransfer(UUID id, String note)
    {
        if (note.isBlank())
        {
            throw new IllegalArgumentException("Review note required");
        }
        return state.updateAsync(root -> {
            if (!root.has("crossTransfers") || !root.getAsJsonObject("crossTransfers").has(id.toString()))
            {
                throw new IllegalArgumentException("Unknown legacy transfer");
            }
            var op = root.getAsJsonObject("crossTransfers").getAsJsonObject(id.toString());
            if (Set.of("COMPLETE", "REJECTED", "REVERSED", "RECONCILED").contains(op.get("phase").getAsString()))
            {
                throw new IllegalStateException("Operation already resolved");
            }
            op.addProperty("phase", "RECONCILED");
            op.addProperty("review", note);
            op.addProperty("reviewTimestamp", System.currentTimeMillis());
        });
    }

    public CompletableFuture<CommandReceipt> between(ServerUser user, AccountType source, long asset, long amount)
    {
        return account(user, source).thenCombine(account(user, source == AccountType.Spot ? AccountType.Contract : AccountType.Spot),
                                                 (from, to) -> transfer(from, to, asset, amount)).thenCompose(value -> value);
    }

    public CompletableFuture<CommandReceipt> playerTransfer(ServerUser source, ServerUser target, long asset, long amount)
    {
        return account(source, AccountType.Spot).thenCombine(account(target, AccountType.Spot),
                                                             (from, to) -> transfer(from, to, asset, amount)).thenCompose(value -> value);
    }

    public CompletableFuture<CommandReceipt> place(ServerUser user, long symbol, OrderSide side,
                                                   OrderType type, TimeInForce tif, long price, long quantity,
                                                   ContractOrderOptions options)
    {
        return exchange.symbols().get(symbol).snapshot().thenCompose(view -> {
            if (assets != null && (!assets.enabled(view.baseAssetId()) || !assets.enabled(view.quoteAssetId())))
            {
                throw new IllegalArgumentException("ui.asset.disabled");
            }
            return UserUtil.getUser(user);
        }).thenComposeAsync(backend -> {
            long request = begin("order", backend.id(), symbol, 0, quantity);
            state.update(root -> {
                JsonObject entry = root.getAsJsonObject("commands").getAsJsonObject(Long.toString(request));
                entry.addProperty("price", price);
                entry.addProperty("side", side.name());
                entry.addProperty("orderType", type.name());
                entry.addProperty("timeInForce", tif.name());
                entry.addProperty("timestamp", System.currentTimeMillis());
                if (options != null)
                {
                    entry.add("contractOptions", new Gson().toJsonTree(options));
                }
            });
            return accepted(exchange.submit(new PlaceOrder(
                    backend.id(), symbol, side, type, tif, price, quantity, System.currentTimeMillis(), options, request)), request);
        }, ApplicationExecutors.IO);
    }

    public CompletableFuture<CommandReceipt> cancel(ServerUser user, long order, long timestamp)
    {
        return UserUtil.getUser(user).thenCompose(backend -> exchange.orders().get(order).snapshot().thenComposeAsync(view -> {
            if (view.userId() != backend.id())
            {
                throw new SecurityException("Order belongs to another user");
            }
            long request = begin("CANCEL_ORDER", backend.id(), order, 0, 0);
            return accepted(exchange.submit(new CancelOrder(backend.id(), order, timestamp, request)), request);
        }, ApplicationExecutors.IO));
    }

    public CompletableFuture<CommandReceipt> placeConditional(ServerUser user, ConditionalOrderRequest ticket)
    {
        return exchange.symbols().get(ticket.symbolId()).snapshot().thenCompose(symbol -> {
            if (assets != null && (!assets.enabled(symbol.baseAssetId()) || !assets.enabled(symbol.quoteAssetId())))
            {
                throw new IllegalArgumentException("ui.asset.disabled");
            }
            return UserUtil.getUser(user);
        }).thenComposeAsync(backend -> {
            var request = ticket.withUserId(backend.id()).withTimestamp(System.currentTimeMillis());
            long id = begin("PLACE_CONDITIONAL", backend.id(), request.symbolId(), 0, request.quantity(), entry -> entry.add("ticket", new Gson().toJsonTree(request)));
            return accepted(exchange.submit(new PlaceConditionalOrder(request, id)), id);
        }, ApplicationExecutors.IO);
    }

    public CompletableFuture<CommandReceipt> cancelConditional(ServerUser user, long conditional)
    {
        return UserUtil.getUser(user).thenCompose(backend -> exchange.conditionalOrders().get(conditional).snapshot().thenComposeAsync(view -> {
            if (view.request().userId() != backend.id())
            {
                throw new SecurityException("ui.conditional.owner");
            }
            long id = begin("CANCEL_CONDITIONAL", backend.id(), conditional, 0, 0);
            return accepted(exchange.submit(new CancelConditionalOrder(backend.id(), conditional, System.currentTimeMillis(), id)), id);
        }, ApplicationExecutors.IO));
    }

    public CompletableFuture<CommandReceipt> modifyConditional(ServerUser user, long conditional, long trigger, long price, long quantity)
    {
        return UserUtil.getUser(user).thenCompose(backend -> exchange.conditionalOrders().get(conditional).snapshot().thenComposeAsync(view -> {
            if (view.request().userId() != backend.id())
            {
                throw new SecurityException("ui.conditional.owner");
            }
            long id = begin("MODIFY_CONDITIONAL", backend.id(), conditional, 0, quantity, entry -> {
                entry.addProperty("trigger", trigger);
                entry.addProperty("price", price);
            });
            return accepted(exchange.submit(new ModifyConditionalOrder(backend.id(), conditional, trigger, price, quantity, System.currentTimeMillis(), id)), id);
        }, ApplicationExecutors.IO));
    }

    private long begin(String type, long source, long target, long asset, long amount)
    {
        return begin(type, source, target, asset, amount, entry -> {
        });
    }

    /**
     * Explicit evidence of an externally resolved outcome; never replays a command or alters balances.
     */
    public CompletableFuture<Void> reconcileCommand(long request, String actor, String evidence)
    {
        if (actor == null || actor.isBlank() || evidence == null || evidence.isBlank())
        {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Reconciliation operator and evidence required"));
        }
        if (!activeRequests.add(request))
        {
            return CompletableFuture.failedFuture(new IllegalStateException("ui.operation.busy"));
        }
        return state.updateAsync(root -> {
            if (!root.has("commands") || !root.getAsJsonObject("commands").has(Long.toString(request)))
            {
                throw new IllegalArgumentException("Unknown exchange command");
            }
            var entry = root.getAsJsonObject("commands").getAsJsonObject(Long.toString(request));
            if (Set.of("DURABLE", "REJECTED", "RECONCILED").contains(entry.get("status").getAsString()))
            {
                throw new IllegalStateException("Operation already resolved");
            }
            entry.addProperty("previousStatus", entry.get("status").getAsString());
            entry.addProperty("status", "RECONCILED");
            entry.addProperty("reviewer", actor);
            entry.addProperty("review", evidence);
            entry.addProperty("reviewTimestamp", System.currentTimeMillis());
        }).whenComplete((unused, error) -> activeRequests.remove(request));
    }

    public CompletableFuture<CommandReceipt> addMargin(ServerUser user, long position, long amount)
    {
        return UserUtil.getUser(user).thenCompose(backend -> addMargin(backend.id(), position, amount));
    }

    /**
     * Native ownership validation remains authoritative; the journal records the durable request.
     */
    CompletableFuture<CommandReceipt> addMargin(long user, long position, long amount)
    {
        if (amount <= 0)
        {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Margin must be positive"));
        }
        return changeMargin(user, position, amount);
    }

    public CompletableFuture<CommandReceipt> removeMargin(ServerUser user, long position, long amount)
    {
        if (amount <= 0)
        {
            return CompletableFuture.failedFuture(new IllegalArgumentException("ui.position.margin_positive"));
        }
        return UserUtil.getUser(user).thenCompose(backend -> changeMargin(backend.id(), position, Math.negateExact(amount)));
    }

    private CompletableFuture<CommandReceipt> changeMargin(long user, long position, long amount)
    {
        return exchange.users().get(user).contractAccount().thenCompose(account -> exchange.submit(new ReadPosition(
                new ObservationQuery().withAccountId(account.id()).withUserId(user).withPositionId(position))))
                .thenApply(response -> response.values().stream().findFirst().orElseThrow(() -> new IllegalArgumentException("ui.position.unavailable")))
                .thenComposeAsync(view -> {
            if (view.userId() != user)
            {
                throw new SecurityException("Position belongs to another user");
            }
            long request = begin(amount > 0 ? "ADD_MARGIN" : "REMOVE_MARGIN", user, position, 0, amount, entry -> {
                entry.addProperty("symbol", view.symbolId());
                entry.addProperty("actor", Long.toString(user));
                entry.addProperty("reason", amount > 0 ? "User requested additional position margin" : "User requested margin reduction");
            });
            return accepted(exchange.submit(new AddMargin(position, user, view.symbolId(), amount, request)), request);
        }, ApplicationExecutors.IO);
    }

    private long begin(String type, long source, long target, long asset, long amount, Consumer<JsonObject> metadata)
    {
        long[] request = new long[1];
        long allocated = requestIds == null ? 0 : requestIds.getAsLong();
        state.update(root -> {
            long id = allocated != 0 ? allocated : Math.addExact(root.has("request") ? root.get("request").getAsLong() : Math.multiplyExact(System.currentTimeMillis(), 1000), 1);
            request[0] = id;
            root.addProperty("request", id);
            JsonObject journal = root.has("commands") ? root.getAsJsonObject("commands") : new JsonObject();
            JsonObject entry = new JsonObject();
            entry.addProperty("type", type);
            entry.addProperty("source", source);
            entry.addProperty("target", target);
            entry.addProperty("asset", asset);
            entry.addProperty("amount", amount);
            entry.addProperty("timestamp", System.currentTimeMillis());
            metadata.accept(entry);
            entry.addProperty("status", "PENDING");
            journal.add(Long.toString(id), entry);
            root.add("commands", journal);
            if (!activeRequests.add(id))
            {
                throw new IllegalStateException("ui.operation.busy");
            }
        });
        return request[0];
    }

    private CompletableFuture<CommandReceipt> accepted(CompletableFuture<CommandReceipt> result, long request)
    {
        return result.thenComposeAsync(receipt -> {
            state.update(root -> {
                JsonObject entry = root.getAsJsonObject("commands").getAsJsonObject(Long.toString(request));
                entry.addProperty("status", receipt.result().accepted() ? "ACCEPTED" : "REJECTED");
                entry.addProperty("sequence", receipt.sequence());
                entry.addProperty("entityId", receipt.result().entityId());
                entry.addProperty("state", receipt.result().state());
                entry.add("receipt", new Gson().toJsonTree(receipt));
            });
            if (!receipt.result().accepted())
            {
                throw new BusinessException(receipt);
            }
            return exchange.submit(new Flush()).thenApplyAsync(status -> {
                if (!status.enabled() || status.faulted() || status.durableThrough() < receipt.sequence())
                {
                    throw new IllegalStateException("Exchange command is not durable; requires reconciliation");
                }
                state.update(root -> {
                    JsonObject entry = root.getAsJsonObject("commands").getAsJsonObject(Long.toString(request));
                    entry.addProperty("status", "DURABLE");
                    entry.addProperty("sequence", receipt.sequence());
                    entry.add("receipt", new Gson().toJsonTree(new CommandReceipt(receipt.sequence(), status.durableThrough(), receipt.duplicate(), receipt.result())));
                });
                return receipt;
            }, ApplicationExecutors.IO);
        }, ApplicationExecutors.IO).whenComplete((receipt, error) -> activeRequests.remove(request));
    }
}
