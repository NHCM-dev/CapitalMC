package nhcm.capitalmc.command;

import java.time.LocalDate;
import java.time.ZoneOffset;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.*;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.*;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.*;
import nhcm.advmatch.BusinessException;
import nhcm.advmatch.command.persistence.ReadPersistenceStatus;
import nhcm.advmatch.command.read.*;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.backend.BackendClient;
import nhcm.capitalmc.backend.PersistenceAdministration;
import nhcm.capitalmc.exchange.*;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.service.*;

import java.math.BigDecimal;
import java.util.*;
import java.math.BigInteger;
import java.util.function.Function;

import com.mojang.brigadier.Command;
import nhcm.advmatch.object.Symbol;
import nhcm.capitalmc.account.ServerUser;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Explicit Brigadier syntax over the same audited services used by administration pages.
 */
public class AdminCommand extends BaseCommand
{
    private static final List<String> TOPICS = List.of("asset", "market", "user", "balance", "peg", "journal", "exchange", "gui", "order", "position", "persistence");
    private static final Gson JSON = new Gson();

    public AdminCommand()
    {
        super("cmcadmin");
    }

    @Override
    protected boolean canUse(CommandSourceStack source)
    {
        return source.permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    @Override
    protected void configure(LiteralArgumentBuilder<CommandSourceStack> root)
    {
        root.then(Commands.literal("help").executes(this::execute)
                          .then(CommandArguments.choice("topic", TOPICS.toArray(String[]::new)).executes(c -> help(c, CommandArguments.string(c, "topic"), 1))
                                                .then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(c -> help(c, CommandArguments.string(c, "topic"), IntegerArgumentType.getInteger(c, "page"))))));
        root.then(Commands.literal("gui").executes(c -> {
            CapitalMC.guiManager.openTradingGui(c.getSource().getPlayerOrException(), PageType.ADMIN);
            return 1;
        }));
        mutation(root, "asset add-item", "register", "name display");
        mutation(root, "asset add-virtual", "electronic", "name display scale");
        mutation(root, "asset rename", "display", "asset display");
        mutation(root, "asset bind", "bind", "asset");
        mutation(root, "asset unbind", "unbind", "asset");
        mutation(root, "asset enable", "asset-enable", "asset");
        mutation(root, "asset disable", "asset-disable", "asset");
        mutation(root, "asset units", "asset-units", "asset unitSize minimumDeposit minimumWithdrawal");
        mutation(root, "asset mint", "mint", "asset amount");
        mutation(root, "asset burn", "burn", "asset amount");
        mutation(root, "market add spot", "symbol", "name base quote");
        mutation(root, "market add perpetual", "symbol", "name base quote price");
        mutation(root, "market add future", "symbol", "name base quote price expiry");
        mutation(root, "market icon", "icon", "symbol");
        mutation(root, "market halt", "halt", "symbol");
        mutation(root, "market resume", "resume", "symbol");
        mutation(root, "market tick", "price-tick", "symbol price");
        mutation(root, "market quantity-step", "quantity-step", "symbol quantity");
        mutation(root, "market minimum-quantity", "min-quantity", "symbol quantity");
        mutation(root, "market minimum-notional", "min-notional", "symbol amount");
        mutation(root, "market fees", "fees", "symbol makerPercent takerPercent");
        mutation(root, "market lot", "lot", "symbol baseAmount minimumLots lotStep");
        mutation(root, "market protection set", "protection", "symbol percent");
        query(root, "market protection show", "symbol", c -> protection(c));
        attach(root, "market settings", List.of("symbol"), c -> {
            var player = c.getSource().getPlayerOrException();
            CapitalMC.guiManager.getTradingGui(player).showTradingSettings(LongArgumentType.getLong(c, "symbol"));
            CapitalMC.guiManager.openTradingGui(player);
            return 1;
        });
        mutation(root, "market mark", "mark", "symbol price");
        mutation(root, "market funding", "funding-percent", "symbol percent");
        mutation(root, "market expiry", "schedule", "symbol expiry");
        mutation(root, "market expire", "expire", "symbol");
        mutation(root, "market deliver", "deliver", "symbol price");
        for (String kind : List.of("rules", "schedule", "reference"))
        {
            mutation(root, "market " + kind + " set", kind, "symbol field value");
        }
        mutation(root, "market reference source-add", "source-add", "symbol sourceType sourceId weight numerator denominator");
        mutation(root, "market reference source-remove", "source-remove", "symbol sourceType sourceId");
        mutation(root, "user ban", "ban", "username");
        mutation(root, "user unban", "unban", "username");
        for (String direction : List.of("add", "remove"))
        {
            for (String type : List.of("spot", "contract"))
            {
                mutation(root, "balance " + direction + " " + type, "balance-" + direction, "username asset amount");
            }
        }
        mutation(root, "peg configure", "peg-human", "base quote baseAmount quoteAmount percent minimum maximum buyEnabled sellEnabled enabled");
        mutation(root, "peg issue", "issue", "username asset amount");
        mutation(root, "peg reserve-in", "reserve-in", "username asset amount");
        mutation(root, "peg reserve-out", "reserve-out", "username asset amount");
        mutation(root, "journal refund", "refund", "operation");
        for (String kind : List.of("items", "pegs", "transfers", "commands"))
        {
            mutation(root, "journal review " + kind, "review-" + kind, "operation");
        }
        mutation(root, "exchange halt", "halt", "");
        mutation(root, "exchange resume", "resume", "");
        mutation(root, "exchange checkpoint", "checkpoint", "");
        query(root, "asset list", "", c -> assets(c, false));
        query(root, "asset supply", "", c -> assets(c, true));
        query(root, "market list", "", c -> SymbolUtil.allSymbols().thenCompose(rows -> GameServices.get().onServer(() -> rows.stream().map(s -> "&e#" + s.symbolId() + " &b" + s.name() + " &7" + ExchangeMessages.label(c.getSource().getPlayer(), s.type())).collect(Collectors.joining("\n")))));
        query(root, "market info", "symbol", c -> marketInfo(c));
        query(root, "user list", "", c -> CompletableFuture.completedFuture(CapitalMC.serverUserManager.getUsers().stream().map(u -> "&b" + u.getUsername() + " &7#" + u.getExchangeUserId()).collect(Collectors.joining("\n"))));
        query(root, "user info", "username", this::userInfo);
        query(root, "asset info", "asset", c -> assets(c, false).thenApply(text -> text.lines().filter(line -> line.contains("#" + GameServices.get().assets.find(CommandArguments.string(c, "asset")).orElseThrow().id() + " ")).collect(Collectors.joining("\n"))));
        query(root, "asset search", "search", c -> assets(c, false).thenApply(text -> search(text, CommandArguments.string(c, "search"))));
        query(root, "user search", "search", c -> CompletableFuture.completedFuture(CapitalMC.serverUserManager.getUsers().stream()
                                                                                                               .filter(u -> (u.getUsername() + " " + u.getDisplayName()).toLowerCase(Locale.ROOT).contains(CommandArguments.string(c, "search").toLowerCase(Locale.ROOT)))
                                                                                                               .map(u -> "&b" + u.getUsername() + " &7#" + u.getExchangeUserId()).collect(Collectors.joining("\n"))));
        query(root, "market search", "search", c -> SymbolUtil.allSymbols().thenCompose(rows -> GameServices.get().onServer(() -> rows.stream()
                                                                                                                                      .filter(m -> m.name().toLowerCase(Locale.ROOT).contains(CommandArguments.string(c, "search").toLowerCase(Locale.ROOT)))
                                                                                                                                      .map(m -> localized(c, "cli.market", m.name(), m.symbolId(), ExchangeMessages.label(c.getSource().getPlayer(), m.type()), m.baseAssetId(), m.quoteAssetId())).collect(Collectors.joining("\n")))));
        for (String kind : List.of("rules", "schedule", "reference"))
        {
            query(root, "market " + kind + " show", "symbol", c -> settings(c, kind));
        }
        for (String type : List.of("spot", "contract"))
        {
            query(root, "balance show " + type, "username", c -> balances(c, type));
        }
        for (String kind : List.of("orders", "positions", "trades"))
        {
            query(root, "user " + kind, "username page", c -> records(c, kind));
        }
        query(root, "order", "order", c -> record(c, false));
        query(root, "position", "position", c -> record(c, true));
        for (String kind : List.of("items", "pegs", "transfers", "commands", "admin"))
        {
            query(root, "journal list " + kind, "page", c -> journal(c, kind));
            query(root, "journal search " + kind, "search page", c -> journal(c, kind));
        }
        query(root, "persistence status", "", this::status);
        query(root, "persistence flush", "", c -> persistence(c, "flush"));
        query(root, "persistence snapshot list", "", c -> persistence(c, "snapshot-list"));
        query(root, "persistence wal list", "", c -> persistence(c, "wal-list"));
        query(root, "persistence verify", "", c -> persistence(c, "verify"));
        query(root, "persistence config show", "", c -> persistence(c, "config-show"));
        query(root, "persistence backup list", "", c -> persistence(c, "backup-list"));
        query(root, "persistence backup save", "backup", c -> CompletableFuture.completedFuture(localized(c, "cli.persistence.offline_save", CommandArguments.string(c, "backup"))));
        query(root, "persistence snapshot load", "backup", c -> CompletableFuture.completedFuture(localized(c, "cli.persistence.offline_load", CommandArguments.string(c, "backup"))));
        query(root, "persistence wal save", "backup", c -> CompletableFuture.completedFuture(localized(c, "cli.persistence.offline_wal", CommandArguments.string(c, "backup"))));
        query(root, "persistence wal load", "backup", c -> CompletableFuture.completedFuture(localized(c, "cli.persistence.offline_load", CommandArguments.string(c, "backup"))));
        mutation(root, "persistence snapshot save", "storage-snapshot", "");
        mutation(root, "persistence config set", "storage-config", "storageField storageValue");
        query(root, "exchange status", "", this::status);
        query(root, "exchange audit", "", c -> CapitalMC.exchange.audit().report(64).thenCompose(r -> GameServices.get().onServer(() -> localized(c, "cli.audit", r.conserved(), r.consistent(), r.checkedAccounts(), r.checkedOrders(), r.checkedPositions(), r.issueCount()) + r.issues().stream().map(i -> "\n&c" + i).collect(Collectors.joining()))));
    }

    private void attach(LiteralArgumentBuilder<CommandSourceStack> root, String path, List<String> names, Command<CommandSourceStack> handler)
    {
        ArgumentBuilder<CommandSourceStack, ?> tail = names.isEmpty() ? null : argument(names.getLast());
        if (tail != null)
        {
            tail.executes(handler);
            for (int i = names.size() - 2; i >= 0; i--)
            {
                tail = argument(names.get(i)).then(tail);
            }
        }
        String[] literals = path.split(" ");
        var branch = Commands.literal(literals[literals.length - 1]).executes(c -> help(c, literals[0], 1));
        if (tail == null)
        {
            branch.executes(handler);
        }
        else
        {
            branch.then(tail);
        }
        for (int i = literals.length - 2; i >= 0; i--)
        {
            branch = Commands.literal(literals[i]).executes(c -> help(c, literals[0], 1)).then(branch);
        }
        root.then(branch);
    }

    private ArgumentBuilder<CommandSourceStack, ?> argument(String name)
    {
        if (name.equals("reason"))
        {
            return Commands.argument(name, StringArgumentType.greedyString());
        }
        if (name.equals("expiry"))
        {
            return Commands.argument(name, StringArgumentType.string()).suggests((context, builder) ->
                    SharedSuggestionProvider.suggest(List.of(LocalDate.now(ZoneOffset.UTC).plusDays(7).toString()), builder));
        }
        if (Set.of("symbol", "order", "position", "sourceId", "weight", "numerator", "denominator").contains(name))
        {
            var node = Commands.argument(name, LongArgumentType.longArg(1));
            if (name.equals("symbol"))
            {
                return node.suggests(CommandArguments.SYMBOLS);
            }
            if (name.equals("order") || name.equals("position"))
            {
                return node.suggests((c, b) -> {
                    var q = new ObservationQuery().withLimit(100).withIncludeTerminal(true).withIncludeInactive(true);
                    if (name.equals("order"))
                    {
                        return CapitalMC.exchange.orders().list(q).thenApply(rows -> SharedSuggestionProvider.suggest(rows.values().stream().map(v -> Long.toString(v.orderId())), b).getNow(b.build()));
                    }
                    return CapitalMC.exchange.positions().list(q).thenApply(rows -> SharedSuggestionProvider.suggest(rows.values().stream().map(v -> Long.toString(v.positionId())), b).getNow(b.build()));
                });
            }
            return node.suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("1"), b));
        }
        if (name.equals("page") || name.equals("scale"))
        {
            return Commands.argument(name, IntegerArgumentType.integer(name.equals("page") ? 1 : 0, name.equals("page") ? 100 : 18));
        }
        var node = CommandArguments.text(name);
        if (name.equals("storageField"))
        {
            return node.suggests((c, b) -> SharedSuggestionProvider.suggest(PersistenceAdministration.FIELDS, b));
        }
        if (name.equals("storageValue"))
        {
            return node.suggests((c, b) -> {
                String value = switch (CommandArguments.string(c, "storageField"))
                {
                    case "directory" -> "persistence";
                    case "exchangeSnapshotEverySequences" -> "10000";
                    case "exchangeRetainedSnapshots", "metadataRetainedSnapshots" -> "2";
                    case "flushEveryCommands" -> "256";
                    case "flushIntervalMilliseconds" -> "10";
                    case "metadataSnapshotEverySequences" -> "128";
                    case "metadataSnapshotWalBytes" -> "4194304";
                    default -> "1";
                };
                return SharedSuggestionProvider.suggest(List.of(value), b);
            });
        }
        if (name.equals("backup"))
        {
            return node.suggests((c, b) -> {
                var request = new JsonObject();
                request.addProperty("action", "backup-list");
                return CapitalMC.runtime.backend.call(BackendClient.PERSISTENCE, request).thenCompose(value ->
                        SharedSuggestionProvider.suggest(value.getAsJsonObject().getAsJsonArray("files").asList().stream().map(v -> v.getAsString()), b))
                        .exceptionally(error -> b.build());
            });
        }
        if (Set.of("asset", "base", "quote").contains(name))
        {
            return node.suggests(CommandArguments.ASSETS);
        }
        if (name.equals("username"))
        {
            return node.suggests(CommandArguments.USERS);
        }
        if (name.endsWith("Enabled") || name.equals("enabled"))
        {
            return CommandArguments.choice(name, "true", "false");
        }
        if (name.equals("sourceType"))
        {
            return CommandArguments.choice(name, "spot", "external");
        }
        if (name.equals("field"))
        {
            return node.suggests((c, b) -> SharedSuggestionProvider.suggest(CommandConfiguration.fields(CommandConfiguration.model(configurationKind(c))).keySet(), b));
        }
        if (name.equals("value"))
        {
            return node.suggests((c, b) -> {
                var type = CommandConfiguration.fields(CommandConfiguration.model(configurationKind(c))).get(CommandArguments.string(c, "field"));
                var values = type == boolean.class ? List.of("true", "false") : type != null && type.isEnum() ? Arrays.stream(type.getEnumConstants()).map(Object::toString).toList() : List.of("0", "1");
                return SharedSuggestionProvider.suggest(values, b);
            });
        }
        return node.suggests((c, b) -> SharedSuggestionProvider.suggest(name.equals("operation") ? operationIds(c) : name.equals("display") || name.equals("name") ? List.of() : List.of("1", "0.1", "10"), b));
    }

    private List<String> operationIds(CommandContext<CommandSourceStack> c)
    {
        String kind = c.getNodes().stream().map(n -> n.getNode().getName()).filter(k -> List.of("items", "pegs", "transfers", "commands").contains(k)).findFirst().orElse("items");
        var section = GameServices.get().state.snapshot().getAsJsonObject(journalKey(kind));
        return section == null ? List.of() : new ArrayList<>(section.keySet());
    }

    private static String configurationKind(CommandContext<CommandSourceStack> c)
    {
        return c.getNodes().stream().map(n -> n.getNode().getName()).filter(k -> List.of("rules", "schedule", "reference").contains(k)).findFirst().orElseThrow();
    }

    private void mutation(LiteralArgumentBuilder<CommandSourceStack> root, String path, String action, String parameters)
    {
        var names = parameters.isBlank() ? new ArrayList<String>() : new ArrayList<>(List.of(parameters.split(" ")));
        names.add("reason");
        attach(root, path, names, c -> guarded(c, () -> {
            var args = names.stream().filter(n -> !n.equals("reason")).map(n -> c.getArgument(n, Object.class).toString()).toArray(String[]::new);
            if (action.equals("symbol"))
            {
                var list = new ArrayList<>(List.of(args));
                list.addFirst(path.substring(path.lastIndexOf(' ') + 1));
                if (list.getFirst().equals("future"))
                {
                    list.set(5, "{\"expiryTimestamp\":" + UtcTime.future(list.get(5)) + "}");
                }
                args = list.toArray(String[]::new);
            }
            else if (action.startsWith("balance-"))
            {
                args = new String[]{args[0], path.endsWith("spot") ? "spot" : "contract", args[1], args[2]};
            }
            else if (path.startsWith("exchange") && !action.equals("checkpoint"))
            {
                args = new String[]{"0"};
            }
            else if (path.equals("market expiry"))
            {
                args = new String[]{args[0], "{\"expiryTimestamp\":" + UtcTime.future(args[1]) + "}"};
            }
            else if (Set.of("rules", "schedule", "reference").contains(action))
            {
                args = new String[]{args[0], CommandConfiguration.patch(CommandConfiguration.model(action), args[1], args[2]).toString()};
            }
            String reason = CommandArguments.string(c, "reason").strip();
            if (reason.isBlank())
            {
                throw new IllegalArgumentException("cli.reason.required");
            }
            var player = c.getSource().getPlayer();
            if (Set.of("register", "bind", "icon").contains(action) && (player == null || player.getMainHandItem().isEmpty()))
            {
                throw new IllegalArgumentException("cli.held.required");
            }
            ItemStack held = player == null ? new ItemStack(Items.PAPER) : player.getMainHandItem().copy();
            String actor = player == null ? "console:" + c.getSource().getTextName() : player.getUUID().toString();
            CompletableFuture<?> work = action.startsWith("source-") ? sources(c, action, args, held, actor, reason)
                    : GameServices.get().administration.action(action, args, held, actor, reason, () -> canUse(c.getSource()) && (player == null || !player.hasDisconnected()));
            return observe(c, work.thenCompose(value -> GameServices.get().onServer(() -> {
                String message = localized(c, "cli.admin.done", path, value instanceof Symbol s ? "#" + s.id()
                        : value instanceof McAssetRegistry.Asset a ? a.name() + " #" + a.id() : reason);
                return action.equals("storage-config") ? message + "\n" + localized(c, "cli.persistence.restart") : message;
            })));
        }));
    }

    private CompletableFuture<?> sources(CommandContext<CommandSourceStack> c, String action, String[] args, ItemStack held, String actor, String reason)
    {
        var symbol = CapitalMC.exchange.symbols().get(Long.parseLong(args[0]));
        return CapitalMC.exchange.contracts().market(symbol).thenCompose(view -> GameServices.get().onServer(() -> {
            if (!canUse(c.getSource()) || c.getSource().getPlayer() != null && c.getSource().getPlayer().hasDisconnected())
            {
                throw new SecurityException("ui.admin.denied");
            }
            var type = Arrays.stream(PriceSourceType.values()).filter(v -> v.name().equalsIgnoreCase(args[1])).findFirst().orElseThrow(() -> new IllegalArgumentException("cli.field.enum"));
            long id = Long.parseLong(args[2]);
            var sources = new ArrayList<>(view.referenceConfig().sources());
            sources.removeIf(s -> s.type() == type && s.sourceId() == id);
            if (action.equals("source-add"))
            {
                sources.add(new PriceSource(type, id, Long.parseLong(args[3]), Long.parseLong(args[4]), Long.parseLong(args[5])));
            }
            var patch = new JsonObject();
            patch.add("sources", JSON.toJsonTree(sources));
            return GameServices.get().administration.action("reference", new String[]{args[0], patch.toString()}, held, actor, reason, () -> canUse(c.getSource()));
        })).thenCompose(work -> work);
    }

    private void query(LiteralArgumentBuilder<CommandSourceStack> root, String path, String parameters, Function<CommandContext<CommandSourceStack>, CompletableFuture<String>> work)
    {
        attach(root, path, parameters.isBlank() ? List.of() : List.of(parameters.split(" ")), c -> guarded(c, () -> observe(c, work.apply(c))));
    }

    private CompletableFuture<String> assets(CommandContext<CommandSourceStack> c, boolean supply)
    {
        String list = GameServices.get().assets.all().stream().map(a -> localized(c, "cli.asset", a.id(), a.name(), a.displayName(), a.electronic(), a.unitSize(), a.format(a.minimumDeposit()), a.format(a.minimumWithdrawal()))).collect(Collectors.joining("\n"));
        if (!supply)
        {
            return CompletableFuture.completedFuture(list);
        }
        return GameServices.get().currency.supply().thenCompose(data -> GameServices.get().onServer(() -> list + data.audit().assets().stream().map(a -> {
            var asset = GameServices.get().assets.find(a.assetId()).orElseThrow();
            return "\n" + localized(c, "cli.supply", asset.displayName(), asset.format(new BigInteger(a.externalFlow())), asset.format(new BigInteger(a.actual())))
                   + "\n" + localized(c, "ui.asset.circulation", asset.format(data.circulating(asset.id())));
        }).collect(Collectors.joining())));
    }

    private static String search(String text, String term)
    {
        return text.lines().filter(line -> line.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT))).collect(Collectors.joining("\n"));
    }

    private static String optionalSearch(CommandContext<CommandSourceStack> c)
    {
        try
        {
            return CommandArguments.string(c, "search");
        } catch (IllegalArgumentException missing)
        {
            return "";
        }
    }

    private CompletableFuture<String> protection(CommandContext<CommandSourceStack> c)
    {
        var symbol = CapitalMC.exchange.symbols().get(LongArgumentType.getLong(c, "symbol"));
        return CapitalMC.exchange.contracts().market(symbol).thenCompose(value -> GameServices.get().onServer(() ->
                localized(c, "ui.settings.protection_status", symbol.id(), value.spec().priceBandRate() == 0
                        ? localized(c, "ui.form.off") : new BigDecimal(value.spec().priceBandRate()).movePointLeft(4).stripTrailingZeros().toPlainString() + "%")));
    }

    private CompletableFuture<String> settings(CommandContext<CommandSourceStack> c, String kind)
    {
        var symbol = CapitalMC.exchange.symbols().get(LongArgumentType.getLong(c, "symbol"));
        CompletableFuture<?> work = kind.equals("rules") ? symbol.snapshot().thenApply(v -> v.tradingRules())
                : CapitalMC.exchange.contracts().market(symbol).thenApply(v -> kind.equals("reference") ? v.referenceConfig()
                : new ContractSchedule(v.spec().fundingInterval(), v.nextFundingTimestamp(), v.spec().maxFundingRate(), v.spec().expiryTimestamp()));
        return work.thenCompose(value -> GameServices.get().onServer(() -> {
            var data = JSON.toJsonTree(value).getAsJsonObject();
            return CommandConfiguration.fields(CommandConfiguration.model(kind)).keySet().stream().map(field -> {
                var selected = data;
                String[] path = field.split("\\.");
                for (int i = 0; i < path.length - 1; i++)
                {
                    selected = selected.getAsJsonObject(path[i]);
                }
                return "&7" + field + ": &b" + selected.get(path[path.length - 1]).getAsString();
            }).collect(Collectors.joining("\n")) + (kind.equals("reference") ? data.getAsJsonArray("sources").asList().stream().map(source -> {
                var row = source.getAsJsonObject();
                return "\n&7source: &b" + row.get("type").getAsString() + " #" + row.get("sourceId").getAsString() + " &7weight: &b" + row.get("weight").getAsString()
                       + " &7ratio: &b" + row.get("numerator").getAsString() + "/" + row.get("denominator").getAsString();
            }).collect(Collectors.joining()) : "");
        }));
    }

    private CompletableFuture<String> marketInfo(CommandContext<CommandSourceStack> c)
    {
        return CapitalMC.exchange.symbols().get(LongArgumentType.getLong(c, "symbol")).snapshot().thenCompose(s -> GameServices.get().onServer(() -> localized(c, "cli.market", s.name(), s.symbolId(), ExchangeMessages.label(c.getSource().getPlayer(), s.type()), s.baseAssetId(), s.quoteAssetId())));
    }

    private CompletableFuture<String> userInfo(CommandContext<CommandSourceStack> c)
    {
        var user = user(c);
        return CapitalMC.exchange.users().get(user.getExchangeUserId()).snapshot().thenCompose(view -> GameServices.get().onServer(() -> localized(c, "cli.admin.user", user.getUsername(), user.getDisplayName(), view.userId(), user.getOwnerUuid(), view.spotAccountId(), view.contractAccountId())));
    }

    private ServerUser user(CommandContext<CommandSourceStack> c)
    {
        return CapitalMC.serverUserManager.findByUsername(CommandArguments.string(c, "username")).orElseThrow(() -> new IllegalArgumentException("cli.user.unknown"));
    }

    private CompletableFuture<String> balances(CommandContext<CommandSourceStack> c, String type)
    {
        var backend = CapitalMC.exchange.users().get(user(c).getExchangeUserId());
        return (type.equals("spot") ? backend.spotAccount() : backend.contractAccount()).thenCompose(a -> BalanceUtil.allBalances(a)).thenCompose(rows -> formatted(c, rows));
    }

    private CompletableFuture<String> records(CommandContext<CommandSourceStack> c, String kind)
    {
        var q = new ObservationQuery().withUserId(user(c).getExchangeUserId()).withLimit(20).withScanBudget(2048).withIncludeInactive(true).withIncludeTerminal(true);
        return readPage(q, kind, IntegerArgumentType.getInteger(c, "page")).thenCompose(rows -> formatted(c, rows.values()));
    }

    private CompletableFuture<ReadResponse<?>> readPage(ObservationQuery q, String kind, int page)
    {
        CompletableFuture<ReadResponse<?>> read = switch (kind)
        {
            case "orders" -> CapitalMC.exchange.submit(new ReadOrders(q)).thenApply(r -> r);
            case "positions" -> CapitalMC.exchange.submit(new ReadPositions(q)).thenApply(r -> r);
            default -> CapitalMC.exchange.submit(new ReadTrades(q)).thenApply(r -> r);
        };
        return read.thenCompose(rows -> {
            if (rows.page().state() != ReadState.Success && rows.page().state() != ReadState.NotFound)
            {
                throw new BusinessException(rows.page());
            }
            return page <= 1 ? CompletableFuture.completedFuture(rows) : rows.page().hasMore() ? readPage(q.withCursor(rows.page().next()), kind, page - 1)
                    : CompletableFuture.failedFuture(new IllegalArgumentException("ui.history.end"));
        });
    }

    private CompletableFuture<String> record(CommandContext<CommandSourceStack> c, boolean position)
    {
        return position ? CapitalMC.exchange.positions().get(LongArgumentType.getLong(c, "position")).snapshot().thenCompose(r -> formatted(c, List.of(r)))
                : CapitalMC.exchange.orders().get(LongArgumentType.getLong(c, "order")).snapshot().thenCompose(r -> formatted(c, List.of(r)));
    }

    private CompletableFuture<String> formatted(CommandContext<CommandSourceStack> c, List<?> rows)
    {
        return SymbolUtil.allSymbols().thenCompose(markets -> GameServices.get().onServer(() -> rows.stream().map(r -> FinancialText.row(r, markets, c.getSource().getPlayer())).collect(Collectors.joining("\n"))));
    }

    private static String journalKey(String kind)
    {
        return switch (kind)
        {
            case "items" -> "itemOperations";
            case "pegs" -> "pegOperations";
            case "transfers" -> "crossTransfers";
            case "commands" -> "commands";
            default -> "adminOperations";
        };
    }

    private CompletableFuture<String> journal(CommandContext<CommandSourceStack> c, String kind)
    {
        var rows = GameServices.get().state.entries(journalKey(kind), (IntegerArgumentType.getInteger(c, "page") - 1) * 20, 20, true, optionalSearch(c));
        return CompletableFuture.completedFuture(rows.values().stream().map(entry -> {
            var value = entry.getValue().getAsJsonObject();
            return "&e" + entry.getKey() + " &7" + List.of("action", "type", "phase", "status", "operator", "actor", "reason").stream().filter(value::has).map(k -> k + ": " + value.get(k).getAsString()).collect(Collectors.joining(" | "));
        }).collect(Collectors.joining("\n")));
    }

    private CompletableFuture<String> persistence(CommandContext<CommandSourceStack> c, String action)
    {
        var request = new JsonObject();
        request.addProperty("action", action);
        return CapitalMC.runtime.backend.call(BackendClient.PERSISTENCE, request).thenCompose(value -> GameServices.get().onServer(() -> {
            var response = value.getAsJsonObject();
            if (action.equals("flush"))
                return localized(c, "cli.persistence.flushed", response.get("sequence").getAsLong(), response.get("durableThrough").getAsLong());
            if (action.equals("verify")) return localized(c, "cli.persistence.verified");
            if (action.equals("config-show"))
            {
                var rows = response.getAsJsonObject("settings").entrySet().stream()
                                   .map(entry -> "&e" + entry.getKey() + " &b" + entry.getValue().getAsString()).collect(Collectors.joining("\n"));
                return rows + "\n" + localized(c, "cli.persistence.restart");
            }
            var rows = response.getAsJsonArray("files");
            if (rows.isEmpty()) return localized(c, "ui.empty");
            return rows.asList().stream().map(row -> row.isJsonPrimitive() ? "&b" + row.getAsString()
                    : localized(c, "cli.persistence.file", row.getAsJsonObject().get("path").getAsString(), row.getAsJsonObject().get("bytes").getAsLong()))
                       .collect(Collectors.joining("\n"));
        }));
    }

    private CompletableFuture<String> status(CommandContext<CommandSourceStack> c)
    {
        return CapitalMC.exchange.submit(new ReadPersistenceStatus()).thenCombine(CapitalMC.exchange.trading().status(), (p, t) -> new Object[]{p.enabled(), p.faulted(), p.sequence(), p.durableThrough(), t.halted(), t.exchangeReason()})
                                 .thenCompose(values -> GameServices.get().onServer(() -> localized(c, "cli.status", values)));
    }

    private int guarded(CommandContext<CommandSourceStack> c, Callable<Integer> work)
    {
        try
        {
            if (!canUse(c.getSource()))
            {
                throw new SecurityException("ui.admin.denied");
            }
            if (!GameServices.get().ready().isDone() || GameServices.get().ready().isCompletedExceptionally())
            {
                throw new IllegalStateException("ui.loading");
            }
            return work.call();
        } catch (Exception error)
        {
            failure(c.getSource(), error);
            return 0;
        }
    }

    private int observe(CommandContext<CommandSourceStack> c, CompletableFuture<String> work)
    {
        var source = c.getSource();
        var player = source.getPlayer();
        long session = player == null ? 0 : CapitalMC.serverUserManager.getSessionVersion(player.getUUID());
        work.whenComplete((value, error) -> source.getServer().execute(() -> {
            if (!canUse(source) || player != null && player.hasDisconnected())
            {
                return;
            }
            if (player != null && session != CapitalMC.serverUserManager.getSessionVersion(player.getUUID()))
            {
                CommandHelp.send(source, error == null ? "cli.admin.context_done" : "cli.admin.context_failed");
            }
            else if (error != null)
            {
                failure(source, error);
            }
            else
            {
                CommandHelp.send(source, value == null || value.isBlank() ? "ui.empty" : value);
            }
        }));
        return 1;
    }

    private static void failure(CommandSourceStack source, Throwable error)
    {
        source.sendFailure(TradingGui.text(source.getPlayer(), "ui.error", ExchangeMessages.error(source.getPlayer(), error)));
    }

    private static String localized(CommandContext<CommandSourceStack> c, String key, Object... args)
    {
        for (int i = 0; i < args.length; i++)
        {
            if (args[i] instanceof Boolean value)
            {
                args[i] = TradingGui.text(c.getSource().getPlayer(), "ui.boolean." + value).getString();
            }
        }
        return TradingGui.text(c.getSource().getPlayer(), key, args).getString();
    }

    private int help(CommandContext<CommandSourceStack> c, String topic, int page)
    {
        try
        {
            return CommandHelp.show(c, "cmcadmin", topic, page);
        } catch (Exception error)
        {
            failure(c.getSource(), error);
            return 0;
        }
    }

    @Override
    protected int execute(CommandContext<CommandSourceStack> c)
    {
        return help(c, "", 1);
    }
}
