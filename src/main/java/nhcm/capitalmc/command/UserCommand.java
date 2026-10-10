package nhcm.capitalmc.command;

import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
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
public final class UserCommand extends BaseCommand
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
    private static final SuggestionProvider<CommandSourceStack> SYMBOLS = (context, builder) -> SymbolUtil.allSymbols().thenApply(values -> {
        return SharedSuggestionProvider.suggest(values.stream().filter(SymbolView::active).map(s -> Long.toString(s.symbolId())), builder).getNow(null);
    });

    public UserCommand()
    {
        super("capital");
    }

    @Override
    protected boolean canUse(CommandSourceStack source)
    {
        return source.getPlayer() != null;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> text(String name)
    {
        return Commands.argument(name, StringArgumentType.word());
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
                                                                                                            .filter(a -> !a.electronic() && GameServices.get().assets.enabled(a.id())).map(McAssetRegistry.Asset::name), builder).getNow(null));
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
        root.then(Commands.literal("gui").executes(c -> open(c, PageType.HOME))
                          .then(text("page").suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(PageType.values()).map(p -> p.name().toLowerCase(Locale.ROOT)), b))
                                            .executes(c -> open(c, PageType.valueOf(arg(c, "page").toUpperCase(Locale.ROOT))))));
        var account = Commands.literal("account");
        account.then(Commands.literal("create").executes(c -> access(c, null, true)));
        for (String name : List.of("login", "switch"))
            account.then(Commands.literal(name)
                                 .then(text("username").suggests(USERS).executes(c -> access(c, arg(c, "username"), false))));
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
        root.then(Commands.literal("balance").executes(c -> balance(c, AccountType.Spot, null)).then(asset().executes(c -> balance(c, AccountType.Spot, arg(c, "asset")))));
        for (AccountType type : AccountType.values())
            root.then(Commands.literal("balance").then(Commands.literal(type.name().toLowerCase(Locale.ROOT))
                                                               .executes(c -> balance(c, type, null)).then(asset().executes(c -> balance(c, type, arg(c, "asset"))))));
        root.then(Commands.literal("deposit").executes(c -> open(c, PageType.DEPOSIT_WITHDRAW)).then(Commands.literal("drop").executes(c -> deposit(c, false)))
                          .then(Commands.literal("container").executes(c -> deposit(c, true))));
        root.then(Commands.literal("withdraw").executes(c -> open(c, PageType.DEPOSIT_WITHDRAW)).then(text("asset").suggests(WITHDRAWABLE).then(amount().then(text("output").suggests((c, b) ->
                                                                                                                                                                                              SharedSuggestionProvider.suggest(Arrays.stream(ItemBankService.Output.values()).map(o -> o.name().toLowerCase(Locale.ROOT)), b))
                                                                                                                                                                            .executes(this::withdraw)))));
        root.then(Commands.literal("transfer").executes(c -> open(c, PageType.TRANSFER)).then(text("username").suggests(USERS).then(asset().then(amount().executes(c -> safe(c, () -> {
            var a = registered(c);
            var target = CapitalMC.serverUserManager.findByUsername(arg(c, "username")).orElseThrow();
            return async(c, GameServices.get().settlements.playerTransfer(current(c), target, a.id(), a.parse(arg(c, "amount"))));
        }))))));
        for (AccountType source : AccountType.values())
            root.then(Commands.literal("move").then(Commands.literal(source.name().toLowerCase(Locale.ROOT))
                                                            .then(asset().then(amount().executes(c -> safe(c, () -> {
                                                                var a = registered(c);
                                                                return async(c, GameServices.get().settlements.between(current(c), source, a.id(), a.parse(arg(c, "amount"))));
                                                            }))))));
        for (boolean buy : List.of(true, false))
            root.then(Commands.literal("peg").then(Commands.literal(buy ? "buy" : "sell")
                                                           .then(asset().then(amount().executes(c -> safe(c, () -> {
                                                               var a = registered(c);
                                                               return async(c, GameServices.get().currency.exchange(current(c), a.id(), a.parse(arg(c, "amount")), buy));
                                                           }))))));
        root.then(Commands.literal("markets").executes(c -> asyncText(c, SymbolUtil.allSymbols().thenApply(values -> values.stream()
                                                                                                                           .map(s -> "#" + s.symbolId() + " " + s.name() + " " + s.type()).collect(Collectors.joining("\n"))))));
        root.then(Commands.literal("market").then(Commands.argument("symbol", LongArgumentType.longArg(1)).suggests(SYMBOLS).executes(c ->
                                                                                                                                              asyncText(c, SymbolUtil.find(LongArgumentType.getLong(c, "symbol")).thenApply(s -> s.orElseThrow().name() + " #" + s.orElseThrow().symbolId() + " " + s.orElseThrow().type())))));
        var order = Commands.literal("order");
        order.then(Commands.literal("cancel").then(Commands.argument("order", LongArgumentType.longArg(1)).suggests((c, b) -> {
            try
            {
                return TradeUtil.orderPage(current(c), false, 0, new ReadCursor()).thenApply(rows -> {
                    return SharedSuggestionProvider.suggest(rows.values().stream().map(o -> Long.toString(o.orderId())), b).getNow(null);
                });
            } catch (Exception e)
            {
                return b.buildFuture();
            }
        }).executes(c -> safe(c, () -> async(c, TradeUtil.cancel(current(c), LongArgumentType.getLong(c, "order"), System.currentTimeMillis()))))));
        order.then(Commands.literal("cancel-all").executes(c -> safe(c, () -> async(c, TradeUtil.cancelPage(current(c))))));
        var quantity = text("quantity").executes(c -> place(c, 1, MarginMode.Isolated));
        quantity.then(Commands.argument("leverage", IntegerArgumentType.integer(1, 1000)).then(text("margin").executes(c ->
                                                                                                                               place(c, IntegerArgumentType.getInteger(c, "leverage"), Arrays.stream(MarginMode.values()).filter(m -> m.name().equalsIgnoreCase(arg(c, "margin"))).findFirst().orElseThrow()))));
        order.then(Commands.literal("place").then(Commands.argument("symbol", LongArgumentType.longArg(1)).suggests(SYMBOLS)
                                                          .then(text("side").then(text("type").then(text("tif").then(text("price").then(quantity)))))));
        for (String kind : List.of("orders", "positions", "trades"))
        {
            var category = Commands.literal(kind).executes(c -> history(c, kind, false, false));
            category.then(Commands.literal("open").executes(c -> history(c, kind, false, false)));
            category.then(Commands.literal("next").executes(c -> history(c, kind, false, true)));
            category.then(Commands.literal("history").executes(c -> history(c, kind, true, false))
                                  .then(Commands.literal("next").executes(c -> history(c, kind, true, true))));
            root.then(category);
        }
        root.then(order);
    }

    @Override
    protected int execute(CommandContext<CommandSourceStack> c)
    {
        reply(c.getSource(), "cli.help");
        return 1;
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
                         .filter(name -> List.of("transfer", "move", "peg", "cancel", "cancel-all", "place").contains(name))
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
        return safe(c, () -> asyncText(c, (type == AccountType.Spot ? UserUtil.spotAccount(current(c)) : UserUtil.contractAccount(current(c))).thenCompose(BalanceUtil::allBalances).thenApply(rows -> rows.stream()
                                                                                                                                                                                                           .filter(b -> name == null || GameServices.get().assets.find(b.assetId()).filter(a -> a.name().equals(name)).isPresent())
                                                                                                                                                                                                           .map(b -> {
                                                                                                                                                                                                               var a = GameServices.get().assets.find(b.assetId()).orElseThrow();
                                                                                                                                                                                                               return a.displayName() + ": " + a.format(b.active()) + " / " + a.format(b.reserved());
                                                                                                                                                                                                           })
                                                                                                                                                                                                           .collect(Collectors.joining("\n")))));
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

    private int place(CommandContext<CommandSourceStack> c, int leverage, MarginMode margin)
    {
        return safe(c, () -> {
            var user = current(c);
            long id = LongArgumentType.getLong(c, "symbol");
            var work = SymbolUtil.find(id).thenCompose(view -> {
                var symbol = view.orElseThrow();
                var base = GameServices.get().assets.find(symbol.baseAssetId()).orElseThrow();
                var quote = GameServices.get().assets.find(symbol.quoteAssetId()).orElseThrow();
                OrderSide side = switch (arg(c, "side").toLowerCase(Locale.ROOT))
                {
                    case "buy", "bid" -> OrderSide.Bid;
                    case "sell", "ask" -> OrderSide.Ask;
                    default -> throw new IllegalArgumentException("ui.trade.input_side");
                };
                OrderType type = arg(c, "type").equalsIgnoreCase("market") ? OrderType.Market : arg(c, "type").equalsIgnoreCase("limit") ? OrderType.Limit : null;
                if (type == null)
                {
                    throw new IllegalArgumentException("ui.trade.input_type");
                }
                TimeInForce tif = Arrays.stream(TimeInForce.values()).filter(t -> t.name().equalsIgnoreCase(arg(c, "tif"))).findFirst().orElseThrow();
                long price = arg(c, "price").equals("0") && type == OrderType.Market && symbol.type() == SymbolType.Spot ? 0 : base.units().parsePrice(arg(c, "price"), quote.units());
                long quantity = GameServices.get().markets.quantityUnits(symbol).parse(arg(c, "quantity"));
                return GameServices.get().settlements.place(user, id, side, type, tif, type == OrderType.Market ? 0 : price, quantity,
                                                            new ContractOrderOptions().withLeverage(leverage).withMarginMode(margin).withPriceLimit(type == OrderType.Market ? price : 0));
            });
            return async(c, work);
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
                       + (rows.page().hasMore() ? "\n/capital " + kind + (historical ? " history" : "") + " next" : "");
            })));
        });
    }
}
