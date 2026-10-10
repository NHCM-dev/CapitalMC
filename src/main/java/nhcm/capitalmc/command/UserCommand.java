package nhcm.capitalmc.command;

import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.*;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.i18n.I18n;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.exchange.*;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.gui.subgui.account.*;
import nhcm.capitalmc.service.*;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Player CLI uses the same custody, identity and settlement services as SGui.
 */
public class UserCommand extends BaseCommand
{
    private record HistoryKey(UUID player, long session, String kind)
    {
    }

    private final Map<HistoryKey, ReadCursor> cursors = new HashMap<>();
    private final Set<HistoryKey> completed = new HashSet<>(); // server thread only
    private static final SuggestionProvider<CommandSourceStack> ASSETS = (context, builder) -> {
        return SharedSuggestionProvider.suggest(CapitalMC.services == null ? Stream.<String>empty() : GameServices.get().assets.all().stream().filter(a -> GameServices.get().assets.enabled(a.id())).map(McAssetRegistry.Asset::name), builder);
    };
    private static final SuggestionProvider<CommandSourceStack> USERS = (context, builder) -> {
        return SharedSuggestionProvider.suggest(CapitalMC.serverUserManager.getUsers().stream().map(ServerUser::getUsername), builder);
    };
    private static final SuggestionProvider<CommandSourceStack> SYMBOLS = CommandArguments.SYMBOLS;

    public UserCommand()
    {
        super("trade");
    }

    @Override
    protected boolean canUse(CommandSourceStack source)
    {
        return source.getPlayer() != null;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> text(String name)
    {
        return CommandArguments.text(name);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> asset()
    {
        return text("asset").suggests(ASSETS);
    }

    private static final SuggestionProvider<CommandSourceStack> WITHDRAWABLE = (context, builder) -> {
        try
        {
            var player = context.getSource().getPlayerOrException();
            var user = CapitalMC.serverUserManager.getLoggedInUser(player.getUUID()).orElseThrow();
            return BalanceUtil.allSpotBalances(user).thenApply(rows -> SharedSuggestionProvider.suggest(rows.stream().filter(b -> b.active() > 0)
                                                                                                            .map(b -> GameServices.get().assets.find(b.assetId())).flatMap(Optional::stream)
                                                                                                            .filter(a -> !a.electronic() && GameServices.get().assets.enabled(a.id())).map(McAssetRegistry.Asset::name), builder).getNow(builder.build()));
        } catch (Exception ignored)
        {
            return builder.buildFuture();
        }
    };

    private static RequiredArgumentBuilder<CommandSourceStack, String> amount()
    {
        return text("amount");
    }

    private static String arg(CommandContext<CommandSourceStack> context, String name)
    {
        return StringArgumentType.getString(context, name);
    }

    @Override
    protected void configure(LiteralArgumentBuilder<CommandSourceStack> root)
    {
        var topics = List.of("account", "balance", "deposit", "withdraw", "transfer", "move", "peg", "market", "markets", "order", "position", "orders", "positions", "trades", "gui", "open", "conditional", "wallet");
        var help = text("topic").suggests((c, b) -> SharedSuggestionProvider.suggest(topics, b))
                                .executes(c -> safe(c, () -> CommandHelp.show(c, "trade", arg(c, "topic"), 1)));
        help.then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(c -> safe(c, () -> CommandHelp.show(c, "trade", arg(c, "topic"), IntegerArgumentType.getInteger(c, "page")))));
        root.then(Commands.literal("help").executes(this::execute).then(help));
        root.then(Commands.literal("gui").executes(c -> open(c, PageType.HOME))
                          .then(text("page").suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(PageType.values()).map(p -> p.name().toLowerCase(Locale.ROOT)), b))
                                            .executes(c -> safe(c, () -> open(c, Arrays.stream(PageType.values())
                                                                                       .filter(page -> page.name().equalsIgnoreCase(arg(c, "page")))
                                                                                       .findFirst().orElseThrow(() -> new IllegalArgumentException("cli.help.unknown")))))));
        registerAccounts(root);
        registerWallet(root);
        registerMarkets(root);
        registerTickets(root);
        registerPositions(root);
        registerConditionals(root);
        registerHistory(root);
        var cancellation = Commands.argument("order", LongArgumentType.longArg(1)).suggests((c, b) -> {
            try
            {
                return TradeUtil.orderPage(current(c), false, 0, new ReadCursor()).thenApply(rows -> SharedSuggestionProvider.suggest(rows.values().stream().map(o -> Long.toString(o.orderId())), b).getNow(b.build()));
            } catch (Exception error)
            {
                return b.buildFuture();
            }
        }).executes(c -> safe(c, () -> async(c, TradeUtil.cancel(current(c), LongArgumentType.getLong(c, "order"), System.currentTimeMillis()))));
        root.then(Commands.literal("order").executes(c -> safe(c, () -> CommandHelp.show(c, "trade", "order", 1))).then(Commands.literal("cancel").then(cancellation))
                          .then(Commands.literal("cancel-all").executes(c -> safe(c, () -> async(c, TradeUtil.cancelPage(current(c)))))));
        var wallet = Commands.literal("wallet").executes(c -> safe(c, () -> CommandHelp.show(c, "trade", "wallet", 1)));
        for (var child : root.build().getChildren())
        {
            if (List.of("balance", "deposit", "withdraw", "transfer", "move", "peg").contains(child.getName()))
            {
                wallet.then(child);
            }
        }
        root.then(wallet);
    }

    private void registerAccounts(LiteralArgumentBuilder<CommandSourceStack> root)
    {
        var account = Commands.literal("account").executes(c -> safe(c, () -> CommandHelp.show(c, "trade", "account", 1)));
        account.then(Commands.literal("create").executes(c -> access(c, null, true)));
        for (String name : List.of("login", "switch"))
        {
            account.then(Commands.literal(name).then(text("username").suggests(USERS).executes(c -> access(c, arg(c, "username"), false))));
        }
        account.then(Commands.literal("logout").executes(c -> {
            CapitalMC.serverUserManager.logout(c.getSource().getPlayerOrException().getUUID());
            reply(c.getSource(), "ui.success.logout");
            return 1;
        }));
        account.then(Commands.literal("info").executes(c -> safe(c, () -> {
            var user = current(c);
            reply(c.getSource(), "cli.account", user.getUsername(), user.getDisplayName(), user.getExchangeUserId());
            return 1;
        })));
        account.then(Commands.literal("password").executes(this::password));
        account.then(Commands.literal("credential").executes(this::password));
        root.then(account);
    }

    private void registerWallet(LiteralArgumentBuilder<CommandSourceStack> root)
    {
        var balances = Commands.literal("balance").executes(c -> balance(c, AccountType.Spot, null));
        balances.then(asset().executes(c -> balance(c, AccountType.Spot, arg(c, "asset"))));
        for (AccountType type : AccountType.values())
        {
            balances.then(Commands.literal(type.name().toLowerCase(Locale.ROOT)).executes(c -> balance(c, type, null))
                                  .then(asset().executes(c -> balance(c, type, arg(c, "asset")))));
        }
        root.then(balances);
        root.then(Commands.literal("deposit").executes(c -> open(c, PageType.DEPOSIT_WITHDRAW))
                          .then(Commands.literal("drop").executes(c -> deposit(c, false)))
                          .then(Commands.literal("container").executes(c -> deposit(c, true))));
        var output = CommandArguments.choice("output", "items", "small_chest", "large_chest", "shulker").executes(this::withdraw);
        root.then(Commands.literal("withdraw").executes(c -> open(c, PageType.DEPOSIT_WITHDRAW))
                          .then(text("asset").suggests(WITHDRAWABLE).then(amount().then(output))));
        var transfer = amount().executes(c -> safe(c, () -> {
            var asset = registered(c);
            var target = CapitalMC.serverUserManager.findByUsername(arg(c, "username")).orElseThrow(() -> new IllegalArgumentException("cli.user.unknown"));
            return async(c, GameServices.get().settlements.playerTransfer(current(c), target, asset.id(), asset.parse(arg(c, "amount"))));
        }));
        root.then(Commands.literal("transfer").executes(c -> open(c, PageType.TRANSFER))
                          .then(text("username").suggests(USERS).then(asset().then(transfer))));
        for (AccountType source : AccountType.values())
        {
            var amount = amount().executes(c -> safe(c, () -> {
                var asset = registered(c);
                return async(c, GameServices.get().settlements.between(current(c), source, asset.id(), asset.parse(arg(c, "amount"))));
            }));
            root.then(Commands.literal("move").then(Commands.literal(source.name().toLowerCase(Locale.ROOT)).then(asset().then(amount))));
        }
        for (boolean buy : List.of(true, false))
        {
            var amount = amount().executes(c -> safe(c, () -> {
                var asset = registered(c);
                return async(c, GameServices.get().currency.exchange(current(c), asset.id(), asset.parse(arg(c, "amount")), buy));
            }));
            root.then(Commands.literal("peg").then(Commands.literal(buy ? "buy" : "sell").then(asset().then(amount))));
        }
    }

    private void registerMarkets(LiteralArgumentBuilder<CommandSourceStack> root)
    {
        root.then(Commands.literal("markets").executes(c -> safe(c, () -> asyncText(c, SymbolUtil.allSymbols().thenCompose(markets -> GameServices.get().onServer(() -> markets.stream()
                                                                                                                                                                               .map(s -> "&e#" + s.symbolId() + " &b" + s.name() + " &7" + ExchangeMessages.label(c.getSource().getPlayer(), s.type())).collect(Collectors.joining("\n"))))))));
        var market = Commands.argument("symbol", LongArgumentType.longArg(1)).suggests(SYMBOLS).executes(c -> safe(c, () ->
                asyncText(c, CapitalMC.exchange.symbols().get(LongArgumentType.getLong(c, "symbol")).snapshot().thenCompose(s -> GameServices.get().onServer(() ->
                                                                                                                                                                     "&b" + s.name() + " &8#" + s.symbolId() + " &7" + ExchangeMessages.label(c.getSource().getPlayer(), s.type()))))));
        root.then(Commands.literal("market").then(market));
        var trading = Commands.argument("symbol", LongArgumentType.longArg(1)).suggests(SYMBOLS).executes(c -> safe(c, () -> {
            var user = current(c);
            long epoch = CapitalMC.serverUserManager.getSessionVersion(c.getSource().getPlayerOrException().getUUID());
            return asyncText(c, CapitalMC.exchange.symbols().get(LongArgumentType.getLong(c, "symbol")).snapshot().thenCompose(view -> GameServices.get().onServer(() -> {
                checkSession(c, user, epoch);
                CapitalMC.guiManager.openTradingGui(c.getSource().getPlayer(), PageType.HOME);
                CapitalMC.guiManager.getTradingGui(c.getSource().getPlayer()).showTrading(view);
                return "cli.trade.opened";
            })));
        }));
        root.then(Commands.literal("open").then(trading));
    }

    private void registerHistory(LiteralArgumentBuilder<CommandSourceStack> root)
    {
        for (String kind : List.of("orders", "positions", "trades"))
        {
            var category = Commands.literal(kind).executes(c -> history(c, kind, false, false));
            category.then(Commands.literal("open").executes(c -> history(c, kind, false, false)));
            category.then(Commands.literal("next").executes(c -> history(c, kind, false, true)));
            category.then(Commands.literal("history").executes(c -> history(c, kind, true, false))
                                  .then(Commands.literal("next").executes(c -> history(c, kind, true, true))));
            root.then(category);
        }
    }

    @Override
    protected int execute(CommandContext<CommandSourceStack> c)
    {
        return CommandHelp.show(c, "trade", "", 1);
    }

    private void registerTickets(LiteralArgumentBuilder<CommandSourceStack> root)
    {
        for (String mode : List.of("limit", "market", "market-fok"))
        {
            boolean market = !mode.equals("limit");
            var quantity = text("quantity").executes(c -> ticket(c, market));
            var option = market ? text("protection").suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("auto", "1", "0.1"), b))
                    : CommandArguments.choice("tif", "GTC", "IOC", "FOK", "PostOnly");
            option.executes(c -> ticket(c, market));
            option.then(Commands.argument("leverage", IntegerArgumentType.integer(1, 1000)).executes(c -> ticket(c, market))
                                .then(CommandArguments.choice("margin", "cross", "isolated").executes(c -> ticket(c, market))));
            quantity.then(option);
            ArgumentBuilder<CommandSourceStack, ?> parameters = market ? quantity : text("price").then(quantity);
            root.then(Commands.literal("order").then(Commands.literal(mode)
                                                             .then(Commands.argument("symbol", LongArgumentType.longArg(1)).suggests(CommandArguments.SYMBOLS)
                                                                           .then(CommandArguments.choice("direction", "buy", "sell", "open-long", "open-short").then(parameters)))));
        }
    }

    private void registerPositions(LiteralArgumentBuilder<CommandSourceStack> root)
    {
        var position = Commands.literal("position").executes(c -> open(c, PageType.POSITIONS));
        position.then(Commands.literal("view").then(positionArgument().executes(c -> safe(c, () -> {
            CapitalMC.guiManager.getTradingGui(c.getSource().getPlayerOrException()).selectPosition(LongArgumentType.getLong(c, "position"));
            return open(c, PageType.POSITION);
        }))));
        for (String direction : List.of("add", "remove"))
        {
            position.then(Commands.literal("margin").then(Commands.literal(direction).then(positionArgument()
                                                                                                   .then(amount().executes(c -> positionAction(c, false, false, direction.equals("remove")))))));
        }
        for (boolean market : List.of(false, true))
        {
            var size = CommandArguments.choice("quantity", "all", "1", "0.1").executes(c -> positionAction(c, true, market, false));
            var id = positionArgument();
            if (market)
            {
                id.executes(c -> positionAction(c, true, true, false)).then(size);
            }
            else
            {
                id.then(text("price").executes(c -> positionAction(c, true, false, false)).then(size));
            }
            position.then(Commands.literal("close").then(Commands.literal(market ? "market" : "limit").then(id)));
        }
        for (String kind : List.of("stop-loss", "take-profit"))
        {
            for (boolean market : List.of(false, true))
            {
                var end = market ? text("trigger") : text("price");
                end.executes(c -> positionProtection(c, kind, market));
                end.then(CommandArguments.choice("quantity", "all", "1", "0.1").executes(c -> positionProtection(c, kind, market))
                                         .then(CommandArguments.choice("source", "mark", "last", "index").executes(c -> positionProtection(c, kind, market))
                                                               .then(Commands.argument("oco", LongArgumentType.longArg(0)).suggests(this::conditionalSuggestions).executes(c -> positionProtection(c, kind, market)))));
                var parameters = market ? end : text("trigger").then(end);
                position.then(Commands.literal("protect").then(Commands.literal(kind).then(Commands.literal(market ? "market" : "limit")
                                                                                                   .then(positionArgument().then(parameters)))));
            }
        }
        root.then(position);
    }

    private void registerConditionals(LiteralArgumentBuilder<CommandSourceStack> root)
    {
        var conditional = Commands.literal("conditional").executes(c -> history(c, "conditional", false, false));
        conditional.then(Commands.literal("list").executes(c -> history(c, "conditional", false, false))
                                 .then(Commands.literal("next").executes(c -> history(c, "conditional", false, true))));
        conditional.then(Commands.literal("history").executes(c -> history(c, "conditional", true, false))
                                 .then(Commands.literal("next").executes(c -> history(c, "conditional", true, true))));
        for (String action : List.of("view", "cancel", "modify"))
        {
            var id = Commands.argument("conditional", LongArgumentType.longArg(1)).suggests(this::conditionalSuggestions);
            if (action.equals("modify"))
            {
                id.then(text("trigger").then(text("quantity").then(text("price").executes(c -> conditionalAction(c, action)))));
            }
            else
            {
                id.executes(c -> conditionalAction(c, action));
            }
            conditional.then(Commands.literal(action).then(id));
        }
        conditional.then(conditionalEntry(true));
        conditional.then(conditionalEntry(false));
        root.then(conditional);
    }

    private LiteralArgumentBuilder<CommandSourceStack> conditionalEntry(boolean market)
    {
        var quantity = text("quantity").then(conditionalParameters(market));
        if (market)
        {
            quantity.executes(c -> conditionalTicket(c, true));
        }
        var trigger = text("trigger").then(quantity);
        var source = CommandArguments.choice("source", "last", "mark", "index").then(trigger);
        var kind = CommandArguments.choice("kind", "stop-loss", "take-profit").then(source);
        var direction = CommandArguments.choice("direction", "buy", "sell", "open-long", "open-short").then(kind);
        var symbol = Commands.argument("symbol", LongArgumentType.longArg(1)).suggests(SYMBOLS).then(direction);
        return Commands.literal(market ? "market" : "limit").then(symbol);
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> conditionalParameters(boolean market)
    {
        var margin = CommandArguments.choice("margin", "isolated", "cross").executes(c -> conditionalTicket(c, market));
        var leverage = Commands.argument("leverage", IntegerArgumentType.integer(1, 1000))
                               .executes(c -> conditionalTicket(c, market)).then(margin);
        var oco = Commands.argument("oco", LongArgumentType.longArg(0)).suggests(this::conditionalSuggestions)
                          .executes(c -> conditionalTicket(c, market)).then(leverage);
        var choices = market ? new String[]{"IOC", "FOK"} : new String[]{"GTC", "IOC", "FOK", "PostOnly"};
        var tif = CommandArguments.choice("tif", choices).executes(c -> conditionalTicket(c, market)).then(oco);
        var price = market ? CommandArguments.choice("protection", "auto", "1", "0.1") : text("price");
        return price.executes(c -> conditionalTicket(c, market)).then(tif);
    }

    private CompletableFuture<Suggestions> conditionalSuggestions(CommandContext<CommandSourceStack> c, SuggestionsBuilder builder)
    {
        try
        {
            var user = current(c);
            return CapitalMC.exchange.conditionalOrders().list(new ObservationQuery().withUserId(user.getExchangeUserId()).withIncludeTerminal(false).withLimit(100))
                                     .thenApply(rows -> SharedSuggestionProvider.suggest(rows.values().stream().map(row -> Long.toString(row.conditionalOrderId())), builder).getNow(builder.build()));
        } catch (Exception error)
        {
            return builder.buildFuture();
        }
    }

    private int conditionalTicket(CommandContext<CommandSourceStack> c, boolean market)
    {
        return safe(c, () -> {
            var user = current(c);
            long epoch = CapitalMC.serverUserManager.getSessionVersion(c.getSource().getPlayerOrException().getUUID());
            var work = CapitalMC.exchange.symbols().get(LongArgumentType.getLong(c, "symbol")).snapshot().thenCompose(symbol -> {
                var side = switch (arg(c, "direction"))
                {
                    case "buy", "open-long" -> OrderSide.Bid;
                    case "sell", "open-short" -> OrderSide.Ask;
                    default -> throw new IllegalArgumentException("ui.trade.input_side");
                };
                var source = triggerSource(arg(c, "source"));
                boolean contract = symbol.type() != SymbolType.Spot;
                int leverage = Integer.parseInt(optional(c, "leverage", "1"));
                var margin = switch (optional(c, "margin", "isolated"))
                {
                    case "isolated" -> MarginMode.Isolated;
                    case "cross" -> MarginMode.Cross;
                    default -> throw new IllegalArgumentException("ui.trade.input_margin");
                };
                if (!contract && (source != TriggerPriceSource.LastTrade || arg(c, "direction").startsWith("open-") || leverage != 1 || margin != MarginMode.Isolated))
                {
                    throw new IllegalArgumentException("ui.conditional.spot_source");
                }
                long trigger = priceUnits(symbol, arg(c, "trigger"));
                String protection = optional(c, "protection", "auto");
                if (!contract && market && !protection.equalsIgnoreCase("auto"))
                {
                    throw new IllegalArgumentException("cli.order.spot_protection");
                }
                CompletableFuture<Long> cap = CompletableFuture.completedFuture(0L);
                if (contract && market)
                {
                    cap = protection.equalsIgnoreCase("auto")
                            ? CapitalMC.exchange.contracts().market(CapitalMC.exchange.symbols().get(symbol.symbolId()))
                                                .thenApply(view -> PositionOrders.triggerProtection(trigger, side, view.spec().priceBandRate(), symbol.tradingRules().priceTick()))
                            : CompletableFuture.completedFuture(priceUnits(symbol, protection));
                }
                return cap.thenCompose(bound -> GameServices.get().onServer(() -> {
                    checkSession(c, user, epoch);
                    var tif = Arrays.stream(TimeInForce.values()).filter(value -> value.name().equalsIgnoreCase(optional(c, "tif", market ? "IOC" : "GTC")))
                                    .findFirst().orElseThrow(() -> new IllegalArgumentException("ui.trade.input_tif"));
                    if (market && tif != TimeInForce.IOC && tif != TimeInForce.FOK)
                    {
                        throw new IllegalArgumentException("ui.trade.input_tif");
                    }
                    var options = contract ? new ContractOrderOptions().withLeverage(leverage).withMarginMode(margin).withPriceLimit(bound) : null;
                    var request = new ConditionalOrderRequest().withSymbolId(symbol.symbolId()).withSide(side).withKind(conditionalKind(arg(c, "kind")))
                                                               .withSource(source).withType(market ? OrderType.Market : OrderType.Limit).withTimeInForce(tif)
                                                               .withTriggerPrice(trigger).withQuantity(GameServices.get().markets.quantityUnits(symbol).parse(arg(c, "quantity")))
                                                               .withPrice(market ? 0 : priceUnits(symbol, arg(c, "price"))).withContractOptions(options).withOcoOrderId(Long.parseLong(optional(c, "oco", "0")));
                    return GameServices.get().settlements.placeConditional(user, request);
                })).thenCompose(result -> result);
            });
            return asyncText(c, work.thenCompose(receipt -> GameServices.get().onServer(() ->
                                                                                                TradingGui.text(c.getSource().getPlayer(), "ui.conditional.created", receipt.result().entityId()).getString())));
        });
    }

    private int conditionalAction(CommandContext<CommandSourceStack> c, String action)
    {
        return safe(c, () -> {
            var user = current(c);
            long epoch = CapitalMC.serverUserManager.getSessionVersion(c.getSource().getPlayerOrException().getUUID());
            long id = LongArgumentType.getLong(c, "conditional");
            var work = CapitalMC.exchange.conditionalOrders().get(id).snapshot().thenCombine(SymbolUtil.allSymbols(), Map::entry)
                                         .thenCompose(result -> GameServices.get().onServer(() -> {
                                             checkSession(c, user, epoch);
                                             var view = result.getKey();
                                             if (view.request().userId() != user.getExchangeUserId())
                                             {
                                                 throw new SecurityException("ui.conditional.owner");
                                             }
                                             if (action.equals("view"))
                                             {
                                                 return CompletableFuture.completedFuture(FinancialText.row(view, result.getValue(), c.getSource().getPlayer()));
                                             }
                                             CompletableFuture<CommandReceipt> mutation;
                                             if (action.equals("cancel"))
                                             {
                                                 mutation = GameServices.get().settlements.cancelConditional(user, id);
                                             }
                                             else
                                             {
                                                 var symbol = result.getValue().stream().filter(value -> value.symbolId() == view.request().symbolId()).findFirst().orElseThrow();
                                                 String price = arg(c, "price");
                                                 if (view.request().type() == OrderType.Market && !price.equals("0"))
                                                 {
                                                     throw new IllegalArgumentException("ui.conditional.market_modify");
                                                 }
                                                 mutation = GameServices.get().settlements.modifyConditional(user, id, priceUnits(symbol, arg(c, "trigger")),
                                                                                                             view.request().type() == OrderType.Market ? 0 : priceUnits(symbol, price), GameServices.get().markets.quantityUnits(symbol).parse(arg(c, "quantity")));
                                             }
                                             return mutation.thenCompose(receipt -> GameServices.get().onServer(() -> TradingGui.text(c.getSource().getPlayer(), "ui.conditional." + action + "_done", id).getString()));
                                         })).thenCompose(result -> result);
            return asyncText(c, work);
        });
    }

    private RequiredArgumentBuilder<CommandSourceStack, Long> positionArgument()
    {
        return Commands.argument("position", LongArgumentType.longArg(1)).suggests(this::positionSuggestions);
    }

    private CompletableFuture<Suggestions> positionSuggestions(CommandContext<CommandSourceStack> c, SuggestionsBuilder b)
    {
        try
        {
            return TradeUtil.positionPage(current(c), false, 0, new ReadCursor()).thenApply(rows -> {
                return SharedSuggestionProvider.suggest(rows.values().stream().map(p -> Long.toString(p.positionId())), b).getNow(b.build());
            });
        } catch (Exception error)
        {
            return b.buildFuture();
        }
    }

    private static String optional(CommandContext<CommandSourceStack> c, String key, String fallback)
    {
        try
        {
            return c.getArgument(key, Object.class).toString();
        } catch (IllegalArgumentException missing)
        {
            return fallback;
        }
    }

    private void checkSession(CommandContext<CommandSourceStack> c, ServerUser user, long epoch)
    {
        var player = c.getSource().getPlayer();
        if (player == null || player.hasDisconnected() || epoch != CapitalMC.serverUserManager.getSessionVersion(player.getUUID())
            || CapitalMC.serverUserManager.getLoggedInUser(player.getUUID()).filter(v -> v.getUuid().equals(user.getUuid())).isEmpty())
        {
            throw new IllegalStateException("ui.session.changed");
        }
        CapitalMC.serverUserManager.requireAccess(user);
    }

    private CompletableFuture<Long> priceLimit(SymbolView symbol, OrderSide side, String input)
    {
        var base = GameServices.get().assets.find(symbol.baseAssetId()).orElseThrow();
        var quote = GameServices.get().assets.find(symbol.quoteAssetId()).orElseThrow();
        if (!input.equalsIgnoreCase("auto"))
        {
            return CompletableFuture.completedFuture(base.units().parsePrice(input, quote.units()));
        }
        if (symbol.type() == SymbolType.Spot)
        {
            return CompletableFuture.completedFuture(0L);
        }
        return CapitalMC.exchange.contracts().market(CapitalMC.exchange.symbols().get(symbol.symbolId()))
                                 .thenApply(view -> TradeCapacity.marketPriceLimit(view, side, symbol.tradingRules().priceTick(), symbol.tradingRules().tickAwarePriceBand()));
    }

    private int ticket(CommandContext<CommandSourceStack> c, boolean market)
    {
        return safe(c, () -> {
            var user = current(c);
            long epoch = CapitalMC.serverUserManager.getSessionVersion(c.getSource().getPlayerOrException().getUUID());
            String direction = arg(c, "direction");
            OrderSide side = switch (direction)
            {
                case "buy", "open-long" -> OrderSide.Bid;
                case "sell", "open-short" -> OrderSide.Ask;
                default -> throw new IllegalArgumentException("ui.trade.input_side");
            };
            var type = market ? OrderType.Market : OrderType.Limit;
            boolean fok = c.getNodes().stream().anyMatch(node -> node.getNode().getName().equals("market-fok"));
            TimeInForce tif;
            if (market)
            {
                tif = fok ? TimeInForce.FOK : TimeInForce.IOC;
            }
            else
            {
                tif = Arrays.stream(TimeInForce.values())
                            .filter(value -> value.name().equalsIgnoreCase(optional(c, "tif", "GTC")))
                            .findFirst().orElseThrow(() -> new IllegalArgumentException("ui.trade.input_tif"));
            }
            var margin = switch (optional(c, "margin", "isolated"))
            {
                case "cross" -> MarginMode.Cross;
                case "isolated" -> MarginMode.Isolated;
                default -> throw new IllegalArgumentException("ui.trade.input_margin");
            };
            int leverage = Integer.parseInt(optional(c, "leverage", "1"));
            var work = CapitalMC.exchange.symbols().get(LongArgumentType.getLong(c, "symbol")).snapshot().thenCompose(symbol -> {
                if (symbol.type() == SymbolType.Spot && direction.startsWith("open-"))
                {
                    throw new IllegalArgumentException("cli.order.contract_only");
                }
                if (market && symbol.type() == SymbolType.Spot && !optional(c, "protection", "auto").equalsIgnoreCase("auto"))
                {
                    throw new IllegalArgumentException("cli.order.spot_protection");
                }
                if (symbol.type() == SymbolType.Spot && (leverage != 1 || margin != MarginMode.Isolated))
                {
                    throw new IllegalArgumentException("cli.order.contract_only");
                }
                long quantity = GameServices.get().markets.quantityUnits(symbol).parse(arg(c, "quantity"));
                return priceLimit(symbol, side, market ? optional(c, "protection", "auto") : arg(c, "price"))
                        .thenCompose(price -> GameServices.get().onServer(() -> {
                            checkSession(c, user, epoch);
                            var options = symbol.type() == SymbolType.Spot ? null : new ContractOrderOptions().withLeverage(leverage).withMarginMode(margin).withPriceLimit(market ? price : 0);
                            return GameServices.get().settlements.place(user, symbol.symbolId(), side, type, tif, market ? 0 : price, quantity, options);
                        })).thenCompose(result -> result);
            });
            return async(c, work);
        });
    }

    private record PositionContext(PositionView position, SymbolView symbol)
    {
    }

    private CompletableFuture<PositionContext> positionContext(ServerUser user, long id)
    {
        return PositionOrders.owned(user, id).thenCompose(position ->
                                                                  CapitalMC.exchange.symbols().get(position.symbolId()).snapshot().thenApply(symbol -> new PositionContext(position, symbol)));
    }

    private int positionAction(CommandContext<CommandSourceStack> c, boolean close, boolean market, boolean remove)
    {
        return safe(c, () -> {
            var user = current(c);
            long epoch = CapitalMC.serverUserManager.getSessionVersion(c.getSource().getPlayerOrException().getUUID());
            long id = LongArgumentType.getLong(c, "position");
            var work = positionContext(user, id).thenCompose(data -> GameServices.get().onServer(() -> {
                checkSession(c, user, epoch);
                if (!close)
                {
                    long amount = GameServices.get().assets.find(data.symbol.quoteAssetId()).orElseThrow().parse(arg(c, "amount"));
                    return remove ? GameServices.get().settlements.removeMargin(user, id, amount) : GameServices.get().settlements.addMargin(user, id, amount);
                }
                String input = optional(c, "quantity", "all");
                long quantity = input.equalsIgnoreCase("all") ? 0 : GameServices.get().markets.quantityUnits(data.symbol).parse(input);
                long price = market ? 0 : priceUnits(data.symbol, arg(c, "price"));
                return PositionOrders.close(user, id, market ? OrderType.Market : OrderType.Limit, quantity, price, () -> checkSession(c, user, epoch));
            })).thenCompose(result -> result);
            String message = close ? "ui.success.close_order" : remove ? "ui.success.margin_removed" : "ui.success.margin";
            return asyncText(c, work.thenCompose(result -> GameServices.get().onServer(() -> TradingGui.text(c.getSource().getPlayer(), message, id).getString())));
        });
    }

    private int positionProtection(CommandContext<CommandSourceStack> c, String kind, boolean market)
    {
        return safe(c, () -> {
            var user = current(c);
            long epoch = CapitalMC.serverUserManager.getSessionVersion(c.getSource().getPlayerOrException().getUUID());
            long id = LongArgumentType.getLong(c, "position");
            var work = positionContext(user, id).thenCompose(data -> GameServices.get().onServer(() -> {
                checkSession(c, user, epoch);
                String input = optional(c, "quantity", "all");
                long quantity = input.equalsIgnoreCase("all") ? 0 : GameServices.get().markets.quantityUnits(data.symbol).parse(input);
                long trigger = priceUnits(data.symbol, arg(c, "trigger"));
                long price = market ? 0 : priceUnits(data.symbol, arg(c, "price"));
                long oco = Long.parseLong(optional(c, "oco", "0"));
                return PositionOrders.protect(user, id, conditionalKind(kind), triggerSource(optional(c, "source", "mark")),
                                              market ? OrderType.Market : OrderType.Limit, trigger, price, quantity, oco, () -> checkSession(c, user, epoch));
            })).thenCompose(result -> result);
            return asyncText(c, work.thenCompose(receipt -> GameServices.get().onServer(() ->
                                                                                                TradingGui.text(c.getSource().getPlayer(), "ui.conditional.created", receipt.result().entityId()).getString())));
        });
    }

    private long priceUnits(SymbolView symbol, String value)
    {
        var assets = GameServices.get().assets;
        return assets.find(symbol.baseAssetId()).orElseThrow().units().parsePrice(value, assets.find(symbol.quoteAssetId()).orElseThrow().units());
    }

    private ConditionalOrderKind conditionalKind(String name)
    {
        return switch (name)
        {
            case "stop-loss" -> ConditionalOrderKind.StopLoss;
            case "take-profit" -> ConditionalOrderKind.TakeProfit;
            default -> throw new IllegalArgumentException("ui.conditional.kind_input");
        };
    }

    private TriggerPriceSource triggerSource(String name)
    {
        return switch (name)
        {
            case "last" -> TriggerPriceSource.LastTrade;
            case "mark" -> TriggerPriceSource.Mark;
            case "index" -> TriggerPriceSource.Index;
            default -> throw new IllegalArgumentException("ui.conditional.source_input");
        };
    }

    private int safe(CommandContext<CommandSourceStack> c, Callable<Integer> action)
    {
        try
        {
            if (!GameServices.get().ready().isDone() || GameServices.get().ready().isCompletedExceptionally())
            {
                throw new IllegalStateException("ui.loading");
            }
            return action.call();
        } catch (Exception error)
        {
            fail(c.getSource(), error);
            return 0;
        }
    }

    private static void reply(CommandSourceStack source, String key, Object... args)
    {
        source.sendSuccess(() -> TradingGui.text(source.getPlayer(), key, args), false);
    }

    private static void fail(CommandSourceStack source, Throwable error)
    {
        while (error.getCause() != null)
            error = error.getCause();
        source.sendFailure(TradingGui.text(source.getPlayer(), "ui.error", ExchangeMessages.error(source.getPlayer(), error)));
    }

    private ServerUser current(CommandContext<CommandSourceStack> c) throws CommandSyntaxException
    {
        return CapitalMC.serverUserManager.getLoggedInUser(c.getSource().getPlayerOrException().getUUID())
                                          .orElseThrow(() -> new IllegalStateException("gui.capitalmc.account.login_required"));
    }

    private McAssetRegistry.Asset registered(CommandContext<CommandSourceStack> c)
    {
        return GameServices.get().assets.find(arg(c, "asset")).orElseThrow(() -> new IllegalArgumentException("ui.withdraw.select_asset"));
    }

    private int async(CommandContext<CommandSourceStack> c, CompletableFuture<?> work)
    {
        String action = c.getNodes().stream().map(node -> node.getNode().getName())
                         .filter(name -> List.of("transfer", "move", "peg", "cancel", "cancel-all", "place", "limit", "market", "market-fok", "margin").contains(name))
                         .reduce((previous, next) -> next).orElse("operation");
        String success = I18n.format(c.getSource().getPlayer().clientInformation().language(), "ui.success.command." + action);
        return asyncText(c, work.thenApply(value -> success));
    }

    private int asyncText(CommandContext<CommandSourceStack> c, CompletableFuture<String> work)
    {
        var source = c.getSource();
        var player = source.getPlayer();
        long epoch = player == null ? 0 : CapitalMC.serverUserManager.getSessionVersion(player.getUUID());
        work.whenComplete((value, error) -> source.getServer().execute(() -> {
            if (player != null && (player.hasDisconnected() || epoch != CapitalMC.serverUserManager.getSessionVersion(player.getUUID())))
            {
                return;
            }
            if (error != null)
            {
                fail(source, error);
            }
            else
            {
                reply(source, value.isEmpty() ? "ui.empty" : value);
            }
        }));
        return 1;
    }

    private int open(CommandContext<CommandSourceStack> c, PageType page) throws CommandSyntaxException
    {
        CapitalMC.guiManager.openTradingGui(c.getSource().getPlayerOrException(), page);
        return 1;
    }

    private int access(CommandContext<CommandSourceStack> c, String username, boolean create)
    {
        return safe(c, () -> {
            open(c, PageType.ACCESS);
            var gui = CapitalMC.guiManager.getTradingGui(c.getSource().getPlayerOrException());
            var page = (AccessPage) gui.page(PageType.ACCESS);
            if (create)
            {
                page.register();
            }
            else
            {
                page.login(username);
            }
            return 1;
        });
    }

    private int password(CommandContext<CommandSourceStack> c)
    {
        return safe(c, () -> {
            var user = current(c);
            open(c, PageType.ACCESS);
            var gui = CapitalMC.guiManager.getTradingGui(c.getSource().getPlayerOrException());
            GuiActions.credentialInput(gui, "ui.access.old_password").thenAccept(old -> old.ifPresent(previous -> GuiActions.credentialInput(gui, "ui.access.new_password")
                                                                                                                            .thenAccept(next -> next.ifPresent(value -> GuiActions.task(gui, CapitalMC.serverUserManager.changePasswordAsync(user.getUsername(), previous, value).thenApply(changed -> {
                                                                                                                                if (!changed)
                                                                                                                                {
                                                                                                                                    throw new IllegalArgumentException("ui.access.bad_password");
                                                                                                                                }
                                                                                                                                return changed;
                                                                                                                            }), "ui.success.password")))
                                                                                                                            .exceptionally(e -> {
                                                                                                                                GuiActions.error(gui, e);
                                                                                                                                return null;
                                                                                                                            }))).exceptionally(e -> {
                GuiActions.error(gui, e);
                return null;
            });
            return 1;
        });
    }

    private int balance(CommandContext<CommandSourceStack> c, AccountType type, String name)
    {
        return safe(c, () -> {
            var user = current(c);
            Long selected = name == null ? null : GameServices.get().assets.find(name).orElseThrow(() -> new IllegalArgumentException("ui.withdraw.select_asset")).id();
            var account = type == AccountType.Spot ? UserUtil.spotAccount(user) : UserUtil.contractAccount(user);
            return asyncText(c, account.thenCompose(BalanceUtil::allBalances).thenCompose(rows -> GameServices.get().onServer(() -> rows.stream()
                                                                                                                                        .filter(row -> selected == null || row.assetId() == selected).map(row -> FinancialText.row(row, List.of(), c.getSource().getPlayer()))
                                                                                                                                        .collect(Collectors.joining("\n")))));
        });
    }

    private int deposit(CommandContext<CommandSourceStack> c, boolean container)
    {
        return safe(c, () -> {
            current(c);
            open(c, PageType.DEPOSIT_WITHDRAW);
            ((DepositWithdrawPage) CapitalMC.guiManager.getTradingGui(c.getSource().getPlayerOrException()).page(PageType.DEPOSIT_WITHDRAW)).deposit(container);
            return 1;
        });
    }

    private int withdraw(CommandContext<CommandSourceStack> c)
    {
        return safe(c, () -> {
            var user = current(c);
            var a = registered(c);
            var output = ItemBankService.Output.valueOf(arg(c, "output").toUpperCase(Locale.ROOT));
            long raw = a.parse(arg(c, "amount"));
            open(c, PageType.DEPOSIT_WITHDRAW);
            var gui = CapitalMC.guiManager.getTradingGui(c.getSource().getPlayerOrException());
            GuiActions.message(gui, "ui.withdraw.summary", a.displayName(), a.format(raw), output);
            GuiActions.confirm(gui, "ui.confirm.withdraw", () -> GuiActions.task(gui, GameServices.get().items.withdraw(gui.getPlayer(), user, a.id(), raw, output), "ui.success.withdraw", a.format(raw), a.displayName()));
            return 1;
        });
    }

    private int history(CommandContext<CommandSourceStack> c, String kind, boolean historical, boolean next)
    {
        return safe(c, () -> {
            var user = current(c);
            var player = c.getSource().getPlayerOrException();
            var key = new HistoryKey(player.getUUID(), CapitalMC.serverUserManager.getSessionVersion(player.getUUID()), kind + historical);
            cursors.keySet().removeIf(k -> k.player.equals(key.player) && k.session != key.session);
            completed.removeIf(k -> k.player.equals(key.player) && k.session != key.session);
            if (!next)
            {
                cursors.remove(key);
                completed.remove(key);
            }
            else if (completed.contains(key))
            {
                reply(c.getSource(), "ui.history.end");
                return 1;
            }
            ReadCursor cursor = cursors.getOrDefault(key, new ReadCursor());
            CompletableFuture<? extends ReadResponse<?>> work = switch (kind)
            {
                case "orders" -> TradeUtil.orderPage(user, historical, 0, cursor);
                case "positions" -> TradeUtil.positionPage(user, historical, 0, cursor);
                case "conditional" ->
                        CapitalMC.exchange.conditionalOrders().list(new ObservationQuery().withUserId(user.getExchangeUserId()).withIncludeTerminal(historical).withLimit(20).withCursor(cursor));
                default -> TradeUtil.tradePage(user, 0, cursor);
            };
            return asyncText(c, work.thenCombine(SymbolUtil.allSymbols(), (rows, markets) -> Map.entry(rows, markets)).thenCompose(result -> GameServices.get().onServer(() -> {
                var rows = result.getKey();
                if (CapitalMC.serverUserManager.getSessionVersion(player.getUUID()) != key.session)
                {
                    throw new IllegalStateException("ui.session.changed");
                }
                if (rows.page().hasMore())
                {
                    cursors.put(key, rows.page().next());
                }
                else
                {
                    cursors.remove(key);
                    completed.add(key);
                }
                return rows.values().stream().map(row -> FinancialText.row(row, result.getValue(), player)).collect(Collectors.joining("\n"))
                       + (rows.page().hasMore() ? "\n/trade " + kind + (historical ? " history" : kind.equals("conditional") ? " list" : "") + " next" : "");
            })));
        });
    }
}
