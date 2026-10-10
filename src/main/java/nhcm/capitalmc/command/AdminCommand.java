package nhcm.capitalmc.command;

import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.*;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.*;
import nhcm.advmatch.command.persistence.ReadPersistenceStatus;
import nhcm.advmatch.command.read.ReadBalances;
import nhcm.advmatch.command.read.ReadOrder;
import nhcm.advmatch.command.read.ReadPosition;
import nhcm.advmatch.command.read.ReadTrades;
import nhcm.advmatch.command.read.ReadUser;
import nhcm.advmatch.model.ObservationQuery;
import nhcm.advmatch.object.Symbol;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.exchange.ExchangeMessages;
import nhcm.capitalmc.gui.PageType;
import nhcm.capitalmc.gui.TradingGui;
import nhcm.capitalmc.service.*;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Owner-only administration. Configuration mutations share the GUI application boundary.
 */
public final class AdminCommand extends BaseCommand
{
    private static final List<String> ACTIONS = List.of("register", "electronic", "symbol", "icon", "issue", "peg", "mark", "bind", "unbind", "display",
                                                        "asset-enable", "asset-disable", "halt", "resume", "rules", "schedule", "reference", "funding", "expire", "deliver", "peg-config", "checkpoint",
                                                        "review-items", "review-pegs", "review-transfers", "review-commands", "refund", "funding-percent", "price-tick", "quantity-step", "min-quantity", "min-notional", "fees", "peg-human", "asset-units", "lot", "reserve-in", "reserve-out");

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
        MoneyCommands.register(root);
        root.then(Commands.literal("gui").executes(c -> {
            CapitalMC.guiManager.openTradingGui(c.getSource().getPlayerOrException(), PageType.ADMIN);
            return 1;
        }));
        root.then(Commands.literal("config").then(Commands.argument("action", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(ACTIONS, b))
                                                          .then(Commands.argument("arguments", StringArgumentType.greedyString()).executes(this::configuration))));
        root.then(Commands.literal("journal").then(Commands.argument("section", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("items", "pegs", "transfers", "commands", "admin"), b))
                                                           .executes(c -> journal(c, 0)).then(Commands.argument("page", IntegerArgumentType.integer(1)).executes(c -> journal(c, IntegerArgumentType.getInteger(c, "page") - 1)))));
        for (String action : List.of("register", "electronic", "symbol", "icon", "issue", "peg", "mark", "review", "refund"))
            root.then(Commands.literal(action).then(Commands.argument("arguments", StringArgumentType.greedyString()).executes(c -> legacy(c, action))));
        for (String read : List.of("status", "audit", "account", "balance", "order", "position", "trades"))
        {
            var node = Commands.literal(read);
            if (read.equals("status") || read.equals("audit"))
            {
                node.executes(c -> investigate(c, read, 0));
            }
            else
            {
                node.then(Commands.argument("id", LongArgumentType.longArg(1)).executes(c -> investigate(c, read, LongArgumentType.getLong(c, "id"))));
            }
            root.then(node);
        }
    }

    @Override
    protected int execute(CommandContext<CommandSourceStack> c)
    {
        reply(c.getSource(), "cli.admin_help");
        return 1;
    }

    private int configuration(CommandContext<CommandSourceStack> c)
    {
        return guarded(c, () -> {
            String action = StringArgumentType.getString(c, "action");
            if (!ACTIONS.contains(action))
            {
                throw new IllegalArgumentException("Unsupported action");
            }
            String[] parts = StringArgumentType.getString(c, "arguments").split("\\|", -1);
            if (parts.length < 2)
            {
                throw new IllegalArgumentException("Arguments must end with | reason");
            }
            String reason = parts[parts.length - 1].strip();
            String[] args = Arrays.stream(Arrays.copyOf(parts, parts.length - 1)).map(String::strip).toArray(String[]::new);
            return mutate(c, action, args, reason);
        });
    }

    private int legacy(CommandContext<CommandSourceStack> c, String original)
    {
        return guarded(c, () -> {
            var raw = StringArgumentType.getString(c, "arguments");
            var a = raw.strip().split("\\s+");
            String reason = "Administrative " + original;
            String action = original;
            String[] args = a;
            if (action.equals("register"))
            {
                if (a.length < 2)
                {
                    throw new IllegalArgumentException("register <name> <display>");
                }
                args = new String[]{a[0], String.join(" ", Arrays.copyOfRange(a, 1, a.length))};
            }
            if (action.equals("electronic"))
            {
                if (a.length < 3)
                {
                    throw new IllegalArgumentException("electronic <name> <scale> <display>");
                }
                args = new String[]{a[0], String.join(" ", Arrays.copyOfRange(a, 2, a.length)), a[1]};
            }
            if (action.equals("review"))
            {
                if (a.length < 3)
                {
                    throw new IllegalArgumentException("review <items|pegs|transfers|commands> <id> <evidence>");
                }
                action = "review-" + a[0];
                reason = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                args = new String[]{a[1]};
            }
            if (action.equals("refund"))
            {
                if (a.length < 2)
                {
                    throw new IllegalArgumentException("refund <id> <reason>");
                }
                args = new String[]{a[0]};
                reason = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
            }
            return mutate(c, action, args, reason);
        });
    }

    private int mutate(CommandContext<CommandSourceStack> c, String action, String[] args, String reason)
    {
        var source = c.getSource();
        var player = source.getPlayer();
        ItemStack held = player == null || player.getMainHandItem().isEmpty() ? new ItemStack(Items.PAPER) : player.getMainHandItem().copy();
        return observe(source, GameServices.get().administration.action(action, args, held, player == null ? "console:" + source.getTextName() : player.getUUID().toString(), reason,
                                                                        () -> player == null ? canUse(source) : !player.hasDisconnected() && player.permissions().hasPermission(Permissions.COMMANDS_OWNER)), action);
    }

    private int journal(CommandContext<CommandSourceStack> c, int page)
    {
        return guarded(c, () -> {
            String key = switch (StringArgumentType.getString(c, "section"))
            {
                case "items" -> "itemOperations";
                case "pegs" -> "pegOperations";
                case "transfers" -> "crossTransfers";
                case "commands" -> "commands";
                case "admin" -> "adminOperations";
                default -> throw new IllegalArgumentException("Unknown journal");
            };
            var entries = GameServices.get().state.entries(key, Math.multiplyExact(page, 20), 20, true);
            reply(c.getSource(), entries.values().stream().map(e -> e.getKey() + ": " + e.getValue()).collect(Collectors.joining("\n")));
            return 1;
        });
    }

    private int investigate(CommandContext<CommandSourceStack> c, String kind, long id)
    {
        return guarded(c, () -> {
            var e = CapitalMC.exchange;
            var q = new ObservationQuery().withLimit(27).withScanBudget(2048).withIncludeInactive(true).withIncludeTerminal(true);
            CompletableFuture<?> read = switch (kind)
            {
                case "status" ->
                        e.submit(new ReadPersistenceStatus()).thenCombine(e.trading().status(), (a, b) -> a + "\n" + b);
                case "audit" -> e.audit().report(64);
                case "account" -> e.submit(new ReadUser(q.withUserId(id)));
                case "balance" -> e.submit(new ReadBalances(q.withAccountId(id)));
                case "order" -> e.submit(new ReadOrder(q.withOrderId(id)));
                case "position" -> e.submit(new ReadPosition(q.withPositionId(id)));
                default -> e.submit(new ReadTrades(q.withUserId(id)));
            };
            return observe(c.getSource(), read);
        });
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

    private int observe(CommandSourceStack source, CompletableFuture<?> work)
    {
        return observe(source, work, null);
    }

    private int observe(CommandSourceStack source, CompletableFuture<?> work, String action)
    {
        work.whenComplete((value, error) -> source.getServer().execute(() -> {
            if (source.getPlayer() != null && (source.getPlayer().hasDisconnected() || !canUse(source)))
            {
                return;
            }
            if (error != null)
            {
                failure(source, error);
            }
            else if (action != null)
            {
                source.sendSuccess(() -> TradingGui.text(source.getPlayer(), "ui.success.admin", ExchangeMessages.action(source.getPlayer(), action)), false);
            }
            else
            {
                reply(source, value == null ? "ui.success" : value instanceof Symbol symbol ? "Symbol #" + symbol.id() : value instanceof McAssetRegistry.Asset asset ? asset.name() + " #" + asset.id() : value.toString());
            }
        }));
        return 1;
    }

    private static void reply(CommandSourceStack source, String text)
    {
        source.sendSuccess(() -> TradingGui.text(source.getPlayer(), text), false);
    }

    private static void failure(CommandSourceStack source, Throwable error)
    {
        while (error.getCause() != null)
            error = error.getCause();
        source.sendFailure(TradingGui.text(source.getPlayer(), "ui.error", ExchangeMessages.error(source.getPlayer(), error)));
    }
}
