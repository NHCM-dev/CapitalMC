package nhcm.capitalmc.gui.subgui;

import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.*;
import nhcm.advmatch.BusinessException;
import nhcm.advmatch.command.persistence.ReadPersistenceStatus;
import nhcm.advmatch.command.read.ReadBalances;
import nhcm.advmatch.command.read.ReadOrdersNewest;
import nhcm.advmatch.command.read.ReadPositionsNewest;
import nhcm.advmatch.command.read.ReadTradesNewest;
import nhcm.advmatch.command.read.ReadUser;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.backend.BackendClient;
import com.google.gson.JsonObject;
import nhcm.capitalmc.exchange.*;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.service.*;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Owner-only management; each delayed continuation checks permission again.
 */
public class AdminPage extends BaseSubGui
{
    private enum Tab
    {
        ASSETS, SYMBOLS, PEGS, USERS, JOURNAL, STATUS
    }

    private Tab tab = Tab.ASSETS;
    private int page, count;
    private long selected;
    private final PollingValue<List<SymbolView>> symbols = new PollingValue<>(List.of());

    private enum Investigation
    {
        SPOT, CONTRACT, ORDERS, POSITIONS, TRADES
    }

    private Investigation investigation = Investigation.SPOT;
    private ReadCursor cursor = new ReadCursor();
    private final Deque<ReadCursor> previous = new ArrayDeque<>();

    private record InvestigationData(ReadResponse<?> rows, List<SymbolView> markets)
    {
    }

    private final PollingValue<InvestigationData> investigated = new PollingValue<>(null);
    private InvestigationData investigationData;

    private void resetInvestigation()
    {
        cursor = new ReadCursor();
        previous.clear();
        investigated.invalidate();
        investigationData = null;
    }

    private int journalKind;
    private String selectedOperation;
    private static final List<String> JOURNALS = List.of("itemOperations", "pegOperations", "crossTransfers", "commands", "adminOperations");
    private final PollingValue<ServiceState.Entries> journal = new PollingValue<>(null);

    private record Status(PersistenceStatus persistence, TradingStatusView trading, JsonObject metrics)
    {
    }

    private final PollingValue<Status> status = new PollingValue<>(null);
    private AuditReport audit;
    private boolean auditing;
    private String search = "";
    private final PollingValue<CurrencyService.Supply> supply = new PollingValue<>(null);

    private boolean matches(String value)
    {
        return value.toLowerCase(Locale.ROOT).contains(search);
    }

    private String assetLore(TradingGui gui, McAssetRegistry.Asset asset)
    {
        String lore = "&7" + asset.name() + " &8#" + asset.id()
                      + "\n" + GuiWidgets.unitsDescription(gui, asset);
        if (asset.special())
        {
            lore += "\n" + GuiWidgets.text(gui, "ui.asset.special");
        }
        var data = supply.get("supply", GameServices.get().currency::supply);
        if (data == null || supply.error() != null)
        {
            return lore + "\n" + GuiWidgets.text(gui, supply.error() == null ? "ui.loading" : "ui.query_failed");
        }
        var totals = data.audit().assets().stream().filter(value -> value.assetId() == asset.id()).findFirst().orElse(null);
        var reserve = data.issuer().stream().filter(value -> value.assetId() == asset.id()).findFirst().orElse(new BalanceView());
        String issued = totals == null ? "0" : asset.format(new BigInteger(totals.externalFlow()));
        String actual = totals == null ? "0" : asset.format(new BigInteger(totals.actual()));
        lore += "\n" + GuiWidgets.text(gui, "ui.asset.circulation", asset.format(data.circulating(asset.id())));
        if (!asset.electronic() || asset.special())
        {
            return lore + "\n" + GuiWidgets.text(gui, "ui.asset.supply", issued, actual);
        }
        return lore + "\n" + GuiWidgets.text(gui, "ui.admin.supply", issued, actual, asset.format(reserve.active()),
                                             asset.format(reserve.reserved()), GameServices.get().currency.issuerUserId(),
                                             GuiWidgets.text(gui, "ui.boolean." + (totals == null || totals.conserved())));
    }

    public AdminPage(TradingGui gui)
    {
        super(gui, PageType.ADMIN);
    }

    private void allowed()
    {
        if (getGui().getPlayer().hasDisconnected() || !getGui().getPlayer().permissions().hasPermission(Permissions.COMMANDS_OWNER))
        {
            throw new SecurityException("ui.admin.denied");
        }
    }

    private void edit(String action, String preset, String prompt)
    {
        allowed();
        GuiActions.message(getGui(), "ui.form.context", ExchangeMessages.action(getGui().getPlayer(), action), preset.isBlank() ? GuiWidgets.text(getGui(), "ui.form.new") : preset);
        if (action.equals("register") || action.equals("electronic"))
        {
            registerAsset(action);
            return;
        }
        if (prompt.equals("ui.admin.enter_continue"))
        {
            reason(action, preset, "");
            return;
        }
        var gui = getGui();
        var held = gui.getPlayer().getMainHandItem().copy();
        var available = symbols.get("all", SymbolUtil::allSymbols);
        var symbol = available == null ? null : available.stream().filter(value -> Long.toString(value.symbolId()).equals(preset)).findFirst().orElse(null);
        AdminInput.collect(gui, action, preset, symbol)
                  .thenAccept(value -> value.ifPresent(args -> GuiActions.run(gui, () -> submit(action, args, held))))
                  .exceptionally(e -> {
                      GuiActions.error(gui, e);
                      return null;
                  });
    }

    private void reason(String action, String preset, String input)
    {
        allowed();
        boolean single = Set.of("display", "rules", "schedule", "reference").contains(action);
        String[] tail = input.isBlank() ? new String[0] : single ? new String[]{input} : input.split("\\|", -1);
        String[] args = new String[tail.length + (preset.isBlank() ? 0 : 1)];
        int offset = 0;
        if (!preset.isBlank())
        {
            args[0] = preset;
            offset = 1;
        }
        for (int i = 0; i < tail.length; i++)
            args[offset + i] = tail[i].strip();
        submit(action, args, getGui().getPlayer().getMainHandItem().copy());
    }

    private void submit(String action, String[] args, ItemStack held)
    {
        allowed();
        String target = args.length == 0 ? GuiWidgets.text(getGui(), "ui.form.new") : args[action.equals("symbol") ? 1 : 0];
        String confirmation = GuiWidgets.text(getGui(), "ui.form.confirm_action", ExchangeMessages.action(getGui().getPlayer(), action), target);
        if (Set.of("mint", "burn", "unbind", "asset-disable", "halt", "expire", "deliver", "refund", "review-items", "review-pegs", "review-transfers", "review-commands", "asset-units", "lot", "reserve-in", "reserve-out").contains(action))
        {
            GuiActions.input(getGui(), "ui.admin.reason_submit", String::strip, v -> {
                      })
                      .thenAccept(note -> note.ifPresent(value -> GuiActions.confirm(getGui(), confirmation, () -> execute(action, args, held, value))))
                      .exceptionally(error -> {
                          GuiActions.error(getGui(), error);
                          return null;
                      });
        }
        else
        {
            GuiActions.confirm(getGui(), confirmation, () -> execute(action, args, held, "GUI " + action + ": " + String.join(" | ", args)));
        }
    }

    private void execute(String action, String[] args, ItemStack held, String note)
    {
        allowed();
        GuiActions.task(getGui(), GameServices.get().administration.action(action, args, held, getGui().getPlayer().getUUID().toString(), note,
                                                                           () -> !getGui().getPlayer().hasDisconnected() && getGui().getPlayer().permissions().hasPermission(Permissions.COMMANDS_OWNER)), "ui.success.admin", ExchangeMessages.action(getGui().getPlayer(), action));
    }

    private void registerAsset(String action)
    {
        var held = getGui().getPlayer().getMainHandItem().copy();
        if (held.isEmpty())
        {
            GuiActions.error(getGui(), new IllegalArgumentException("ui.admin.hold_item"));
            return;
        }
        registrationField(action, held, new String[action.equals("electronic") ? 3 : 2], 0);
    }

    private void registrationField(String action, ItemStack held, String[] args, int index)
    {
        String prompt = switch (index)
        {
            case 0 -> "ui.admin.asset_id";
            case 1 -> "ui.admin.asset_display";
            default -> "ui.admin.asset_scale";
        };
        GuiActions.input(getGui(), prompt, text -> {
            if (index == 0)
            {
                if (!text.matches("[a-z0-9_]+"))
                {
                    throw new IllegalArgumentException("ui.admin.asset_id_invalid");
                }
                if (GameServices.get().assets.find(text).isPresent())
                {
                    throw new IllegalArgumentException("ui.admin.asset_exists");
                }
            }
            else if (index == 2)
            {
                try
                {
                    int scale = Integer.parseInt(text);
                    if (scale < 0 || scale > 7)
                    {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException error)
                {
                    throw new IllegalArgumentException("ui.admin.asset_scale_invalid");
                }
            }
            return text;
        }, v -> {
        }).thenAccept(value -> value.ifPresent(text -> GuiActions.run(getGui(), () -> {
            allowed();
            args[index] = text;
            if (index + 1 < args.length)
            {
                registrationField(action, held, args, index + 1);
            }
            else
            {
                submit(action, args, held);
            }
        }))).exceptionally(error -> {
            GuiActions.error(getGui(), error);
            return null;
        });
    }

    private void button(TradingGui gui, int slot, Item icon, String key, String action, String preset, String prompt)
    {
        GuiWidgets.button(gui, slot, icon, key, "", () -> edit(action, preset, prompt));
    }

    @Override
    public void render(TradingGui gui)
    {
        if (!GuiActions.ready(gui))
        {
            return;
        }
        if (!gui.getPlayer().permissions().hasPermission(Permissions.COMMANDS_OWNER))
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.BARRIER, "ui.admin.denied", ""));
            return;
        }
        GuiWidgets.separator(gui);
        var services = GameServices.get();
        var tabs = Tab.values();
        var icons = new Item[]{Items.DIAMOND, Items.BOOK, Items.EMERALD, Items.PLAYER_HEAD, Items.PAPER, Items.COMPASS};
        for (int i = 0; i < tabs.length; i++)
        {
            var choice = tabs[i];
            GuiWidgets.option(gui, i, icons[i], "ui.admin." + choice.name().toLowerCase(Locale.ROOT), "", tab == choice, () -> {
                tab = choice;
                page = 0;
                selected = 0;
                selectedOperation = null;
                resetInvestigation();
            });
        }
        GuiWidgets.button(gui, 6, Items.COMPARATOR, "ui.settings.title", "ui.settings.entry", () -> gui.showTradingSettings(0));
        if (tab == Tab.ASSETS)
        {
            if (selected != 0)
            {
                var a = services.assets.find(selected).orElseThrow();
                gui.setSlot(22, GuiWidgets.asset(gui, a.id(), assetLore(gui, a), null));
                button(gui, 18, Items.NAME_TAG, "ui.admin.display", "display", a.name(), "ui.admin.input_display");
                if (!a.special())
                {
                    button(gui, 19, Items.CHEST, "ui.admin.bind", "bind", a.name(), "ui.admin.enter_continue");
                    button(gui, 20, Items.PAPER, "ui.admin.unbind", "unbind", a.name(), "ui.admin.enter_continue");
                }
                button(gui, 27, Items.DYE.lime(), "ui.admin.enable", "asset-enable", a.name(), "ui.admin.enter_continue");
                button(gui, 28, Items.DYE.red(), "ui.admin.disable", "asset-disable", a.name(), "ui.admin.enter_continue");
                button(gui, 32, Items.REPEATER, "ui.admin.units", "asset-units", a.name(), "ui.admin.input_units");
                if (a.electronic() && !a.special())
                {
                    button(gui, 30, Items.EMERALD, "ui.admin.mint", "mint", a.name(), "ui.transfer.amount");
                    button(gui, 31, Items.BARRIER, "ui.admin.burn", "burn", a.name(), "ui.transfer.amount");
                }
                GuiWidgets.button(gui, 8, Items.ARROW, "gui.capitalmc.back", "", () -> selected = 0);
                count = 0;
                return;
            }
            button(gui, 7, Items.CHEST, "ui.admin.register", "register", "", "ui.admin.input_asset");
            button(gui, 8, Items.PAPER, "ui.admin.electronic", "electronic", "", "ui.admin.input_electronic");
            var values = services.assets.all().stream().filter(asset -> matches(asset.name() + " " + asset.displayName() + " " + asset.id())).toList();
            count = values.size();
            page = GuiWidgets.clamp(page, count);
            for (int i = page * 27; i < Math.min(count, page * 27 + 27); i++)
            {
                var asset = values.get(i);
                gui.setSlot(18 + i - page * 27, GuiWidgets.asset(gui, asset.id(), assetLore(gui, asset), () -> selected = asset.id()));
            }
        }
        else if (tab == Tab.SYMBOLS)
        {
            var values = symbols.get("all", SymbolUtil::allSymbols);
            if (selected != 0)
            {
                var s = values.stream().filter(v -> v.symbolId() == selected).findFirst().orElse(null);
                if (s == null)
                {
                    return;
                }
                String id = Long.toString(s.symbolId());
                GuiWidgets.button(gui, 8, Items.ARROW, "gui.capitalmc.back", "", () -> selected = 0);
                gui.setSlot(22, services.markets.icon(gui, s, () -> gui.showTrading(s)));
                GuiWidgets.button(gui, 21, Items.COMPARATOR, "ui.settings.title", "ui.settings.entry", () -> gui.showTradingSettings(s.symbolId()));
                button(gui, 18, Items.DYE.red(), "ui.admin.halt", "halt", id, "ui.admin.enter_continue");
                button(gui, 19, Items.DYE.lime(), "ui.admin.resume", "resume", id, "ui.admin.enter_continue");
                button(gui, 20, Items.WRITABLE_BOOK, "ui.admin.rules", "rules", id, "ui.admin.input_rules");
                button(gui, 23, Items.IRON_INGOT, "ui.admin.price_tick", "price-tick", id, "ui.trade.input_price");
                button(gui, 24, Items.WOOL.white(), "ui.admin.quantity_step", "quantity-step", id, "ui.transfer.amount");
                button(gui, 25, Items.WOOL.lightGray(), "ui.admin.min_quantity", "min-quantity", id, "ui.transfer.amount");
                button(gui, 26, Items.GOLD_INGOT, "ui.admin.min_notional", "min-notional", id, "ui.transfer.amount");
                button(gui, 36, Items.EMERALD, "ui.admin.fees", "fees", id, "ui.admin.input_fees");
                button(gui, 37, Items.PAPER, "ui.admin.lot", "lot", id, "ui.admin.input_lot");
                if (s.type() != SymbolType.Spot)
                {
                    button(gui, 27, Items.CLOCK, "ui.admin.schedule", "schedule", id, "ui.admin.input_json");
                    button(gui, 28, Items.COMPASS, "ui.admin.reference", "reference", id, "ui.admin.input_json");
                    button(gui, 29, Items.GOLD_INGOT, "ui.admin.mark", "mark", id, "ui.trade.input_price");
                    button(gui, 30, Items.EMERALD, "ui.admin.funding", "funding-percent", id, "ui.admin.input_funding");
                    button(gui, 31, Items.BARRIER, "ui.admin.expire", "expire", id, "ui.admin.enter_continue");
                    button(gui, 32, Items.CHEST, "ui.admin.deliver", "deliver", id, "ui.trade.input_price");
                }
                count = 0;
                return;
            }
            button(gui, 8, Items.NETHER_STAR, "ui.admin.create_market", "symbol", "", "ui.admin.input_symbol");
            values = values.stream().filter(s -> matches(s.name() + " " + s.symbolId() + " " + ExchangeMessages.label(gui.getPlayer(), s.type()))).toList();
            count = values.size();
            page = GuiWidgets.clamp(page, count);
            for (int i = page * 27; i < Math.min(count, page * 27 + 27); i++)
            {
                var s = values.get(i);
                gui.setSlot(18 + i - page * 27, services.markets.icon(gui, s, () -> selected = s.symbolId()));
            }
        }
        else if (tab == Tab.PEGS)
        {
            button(gui, 8, Items.EMERALD, "ui.admin.configure_peg", "peg-human", "", "ui.admin.input_peg");
            button(gui, 7, Items.CHEST, "ui.admin.reserve_in", "reserve-in", "", "ui.admin.input_reserve");
            button(gui, 6, Items.HOPPER, "ui.admin.reserve_out", "reserve-out", "", "ui.admin.input_reserve");
            var values = services.currency.peggedAssets().stream().filter(id -> matches(id + " " + services.currency.describePeg(id))).toList();
            count = values.size();
            page = GuiWidgets.clamp(page, count);
            for (int i = page * 27; i < Math.min(count, page * 27 + 27); i++)
            {
                long id = values.get(i);
                gui.setSlot(18 + i - page * 27, GuiWidgets.asset(gui, id, services.currency.describePeg(id), () -> edit("peg-human", "", "ui.admin.input_peg")));
            }
        }
        else if (tab == Tab.USERS)
        {
            if (selected != 0)
            {
                renderInvestigation(gui);
                count = 0;
                return;
            }
            var values = CapitalMC.serverUserManager.getUsers().stream().filter(user -> matches(user.getUsername() + " " + user.getDisplayName() + " " + user.getExchangeUserId())).toList();
            count = values.size();
            page = GuiWidgets.clamp(page, count);
            for (int i = page * 27; i < Math.min(count, page * 27 + 27); i++)
            {
                var u = values.get(i);
                gui.setSlot(18 + i - page * 27, GuiWidgets.accountHead(gui, u, () -> {
                    selected = u.getExchangeUserId();
                    resetInvestigation();
                }));
            }
        }
        else if (tab == Tab.JOURNAL)
        {
            button(gui, 8, Items.EMERALD, "ui.admin.refund", "refund", "", "ui.admin.input_operation");
            String kind = JOURNALS.get(journalKind);
            GuiWidgets.button(gui, 6, Items.BOOK, "ui.admin.journal_type", kind, () -> {
                journalKind = (journalKind + 1) % JOURNALS.size();
                page = 0;
                selectedOperation = null;
                journal.invalidate();
            });
            GuiWidgets.button(gui, 7, Items.ARROW, "gui.capitalmc.back", "", () -> {
                if (selectedOperation != null)
                {
                    selectedOperation = null;
                }
                else
                {
                    tab = Tab.ASSETS;
                    page = 0;
                    selected = 0;
                }
            });
            if (selectedOperation != null)
            {
                count = 0;
                gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PAPER, selectedOperation, ""));
                String review = switch (kind)
                {
                    case "itemOperations" -> "review-items";
                    case "pegOperations" -> "review-pegs";
                    case "crossTransfers" -> "review-transfers";
                    case "commands" -> "review-commands";
                    default -> null;
                };
                if (review != null)
                {
                    GuiWidgets.button(gui, 24, Items.WRITABLE_BOOK, "ui.admin.reconcile", "ui.admin.review_evidence", () -> reason(review, selectedOperation, ""));
                }
                if (kind.equals("itemOperations"))
                {
                    GuiWidgets.button(gui, 20, Items.EMERALD, "ui.admin.refund", "", () -> reason("refund", selectedOperation, ""));
                }
                return;
            }
            int requestedPage = page;
            var entries = journal.get(List.of(kind, requestedPage, search), () -> {
                String requestedSearch = search;
                return ApplicationExecutors.io(() -> services.state.entries(kind, requestedPage * 27, 27, true, requestedSearch));
            });
            if (entries == null)
            {
                gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PAPER, journal.error() == null ? "ui.loading" : "ui.query_failed", ""));
                count = 0;
                return;
            }
            count = entries.total();
            page = GuiWidgets.clamp(page, count);
            int slot = 18;
            for (var e : entries.values())
            {
                var record = e.getValue().getAsJsonObject();
                var lines = new StringBuilder();
                for (String key : List.of("phase", "type", "action", "operator", "actor", "user", "account", "timestamp", "reason", "requestId", "debitRequest", "creditRequest", "failurePhase"))
                    if (record.has(key))
                    {
                        lines.append(key).append(": ").append(record.get(key).getAsString()).append('\n');
                    }
                if (record.has("amounts"))
                {
                    for (var amount : record.getAsJsonObject("amounts").entrySet())
                    {
                        var asset = services.assets.find(Long.parseLong(amount.getKey()));
                        lines.append(asset.map(McAssetRegistry.Asset::displayName).orElse(amount.getKey())).append(": ").append(GuiWidgets.amount(Long.parseLong(amount.getKey()), amount.getValue().getAsLong())).append('\n');
                    }
                }
                GuiWidgets.button(gui, slot++, Items.PAPER, e.getKey(), lines.toString(), () -> {
                    selectedOperation = e.getKey();
                    GuiActions.message(gui, e.getKey() + "\n" + lines);
                });
            }
        }
        else
        {
            count = 0;
            var data = status.get("status", 1000, () -> CapitalMC.exchange.submit(new ReadPersistenceStatus())
                    .thenCombine(CapitalMC.exchange.trading().status(), (persistence, trading) -> new Status(persistence, trading, null))
                    .thenCombine(CapitalMC.runtime.backend.call(BackendClient.INFO, new JsonObject()),
                                 (current, info) -> new Status(current.persistence(), current.trading(), info.getAsJsonObject().getAsJsonObject("metrics"))));
            String text = data == null ? GuiWidgets.text(gui, status.error() == null ? "ui.loading" : "ui.query_failed")
                    : GuiWidgets.text(gui, "ui.admin.status_details", GuiWidgets.text(gui, "ui.boolean." + data.persistence.enabled()), GuiWidgets.text(gui, "ui.boolean." + data.persistence.faulted()),
                                      data.persistence.sequence(), data.persistence.durableThrough(), GuiWidgets.text(gui, "ui.boolean." + data.trading.exchangeHalted()), data.trading.exchangeReason());
            if (data != null && data.metrics() != null)
            {
                var subscriptions = data.metrics().getAsJsonObject("subscriptions");
                var gateway = data.metrics().getAsJsonObject("gateway");
                text += "\n" + GuiWidgets.text(gui, "ui.admin.performance_subscriptions", subscriptions.get("activeQueries").getAsInt(),
                                                subscriptions.get("failedBatches").getAsLong(), subscriptions.get("deferredTicks").getAsLong());
                if (gateway != null)
                {
                    text += "\n" + GuiWidgets.text(gui, "ui.admin.performance_gateway", gateway.get("connections").getAsInt(),
                                                    gateway.get("queuedCommands").getAsInt(), gateway.get("runningCommands").getAsInt(),
                                                    gateway.get("rejectedCommands").getAsLong(), gateway.get("coalescedUpdates").getAsLong());
                }
            }
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.COMPASS, "ui.admin.status", text));
            button(gui, 24, Items.CHEST, "ui.admin.checkpoint", "checkpoint", "", "ui.admin.enter_continue");
            String result = auditing ? GuiWidgets.text(gui, "ui.loading") : audit == null ? "" : GuiWidgets.text(gui, "ui.admin.audit_details", GuiWidgets.text(gui, "ui.boolean." + audit.conserved()), GuiWidgets.text(gui, "ui.boolean." + audit.consistent()), audit.checkedAccounts(), audit.checkedOrders(), audit.checkedPositions(), audit.issueCount());
            GuiWidgets.button(gui, 20, Items.BOOK, "ui.admin.audit", result, () -> {
                allowed();
                if (auditing)
                {
                    throw new IllegalStateException("ui.operation.busy");
                }
                auditing = true;
                CapitalMC.exchange.audit().report(64).whenComplete((report, error) -> services.server.execute(() -> {
                    auditing = false;
                    gui.requestRefresh();
                    if (gui.getPlayer().hasDisconnected() || !gui.getPlayer().permissions().hasPermission(Permissions.COMMANDS_OWNER))
                    {
                        return;
                    }
                    if (error == null)
                    {
                        audit = report;
                    }
                    else
                    {
                        GuiActions.error(gui, error);
                    }
                }));
            });
        }
    }

    private void renderInvestigation(TradingGui gui)
    {
        GuiWidgets.button(gui, 6, Items.BOOK, "ui.admin.investigation", GuiWidgets.text(gui, "ui.admin.investigation." + investigation.name()), () -> {
            investigation = Investigation.values()[(investigation.ordinal() + 1) % Investigation.values().length];
            resetInvestigation();
        });
        GuiWidgets.option(gui, 10, Items.GOLDEN_BOOTS, "gui.capitalmc.account.spot", "", investigation == Investigation.SPOT, () -> {
            investigation = Investigation.SPOT;
            resetInvestigation();
        });
        GuiWidgets.option(gui, 11, Items.CHAINMAIL_BOOTS, "gui.capitalmc.account.contract", "", investigation == Investigation.CONTRACT, () -> {
            investigation = Investigation.CONTRACT;
            resetInvestigation();
        });
        GuiWidgets.button(gui, 7, Items.END_CRYSTAL, "gui.capitalmc.refresh", "", this::resetInvestigation);
        GuiWidgets.button(gui, 8, Items.ARROW, "gui.capitalmc.back", "", () -> selected = 0);
        var query = new ObservationQuery().withUserId(selected).withCursor(cursor).withLimit(27).withScanBudget(2048).withIncludeTerminal(true).withPositionFilter(PositionFilter.All);
        long requestedUser = selected;
        var kind = investigation;
        investigationData = investigated.get(requestedUser + ":" + kind + ":" + cursor, () -> {
            CompletableFuture<? extends ReadResponse<?>> work = switch (kind)
            {
                case ORDERS -> CapitalMC.exchange.submit(new ReadOrdersNewest(query));
                case POSITIONS -> CapitalMC.exchange.submit(new ReadPositionsNewest(query));
                case TRADES -> CapitalMC.exchange.submit(new ReadTradesNewest(query));
                default -> CapitalMC.exchange.submit(new ReadUser(new ObservationQuery().withUserId(requestedUser)))
                                             .thenCompose(response -> {
                                                 if (response.page().state() != ReadState.Success || response.values().isEmpty())
                                                 {
                                                     throw new BusinessException(response.page());
                                                 }
                                                 var user = response.values().getFirst();
                                                 return CapitalMC.exchange.submit(new ReadBalances(query.withUserId(0).withAccountId(kind == Investigation.SPOT ? user.spotAccountId() : user.contractAccountId())));
                                             });
            };
            return work.thenCombine(SymbolUtil.allSymbols(), (rows, markets) -> {
                if (rows.page().state() != ReadState.Success && rows.page().state() != ReadState.NotFound)
                {
                    throw new BusinessException(rows.page());
                }
                return new InvestigationData(rows, markets);
            });
        });
        if (investigationData == null)
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PAPER, investigated.error() == null ? "ui.loading" : "ui.query_failed", ""));
            return;
        }
        int slot = 18;
        for (var row : ActivityScope.newest(investigationData.rows.values()))
        {
            String description = FinancialText.lore(row, investigationData.markets, gui.getPlayer());
            GuiWidgets.button(gui, slot++, Items.PAPER, description, "", () -> GuiActions.message(gui, description));
        }
        if (investigationData.rows.values().isEmpty())
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PAPER, "ui.empty", investigationData.rows.page().hasMore() ? "ui.history.scan_more" : ""));
        }
    }

    @Override
    public void renderOverlay(TradingGui gui)
    {
        if (!gui.getPlayer().permissions().hasPermission(Permissions.COMMANDS_OWNER))
        {
            return;
        }
        GuiWidgets.versionWarning(gui, 47);
        GuiWidgets.button(gui, 50, Items.COMPASS, "ui.search", "&7" + search, () -> GuiActions.input(gui, "ui.search.input", String::strip, value -> {
            search = value.toLowerCase(Locale.ROOT);
            page = 0;
            selected = 0;
            selectedOperation = null;
            journal.invalidate();
        }));
        GuiWidgets.button(gui, 48, Items.SPONGE, "ui.search.clear", "", () -> {
            search = "";
            page = 0;
            journal.invalidate();
        });
        if (tab == Tab.USERS && selected != 0)
        {
            if (!previous.isEmpty())
            {
                GuiWidgets.button(gui, 51, Items.ARROW, "ui.page.previous", "", () -> {
                    cursor = previous.pop();
                    investigated.invalidate();
                });
            }
            if (investigationData != null && investigationData.rows.page().hasMore())
            {
                var next = investigationData.rows.page().next();
                GuiWidgets.button(gui, 52, Items.ARROW, "ui.page.next", "", () -> {
                    previous.push(cursor);
                    cursor = next;
                    investigated.invalidate();
                });
            }
        }
        else if (count > 0)
        {
            GuiWidgets.paging(gui, page, count, p -> page = p);
        }
    }
}
