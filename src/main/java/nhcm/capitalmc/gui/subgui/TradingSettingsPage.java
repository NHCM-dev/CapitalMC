package nhcm.capitalmc.gui.subgui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import nhcm.advmatch.model.ContractSchedule;
import nhcm.advmatch.model.MarketView;
import nhcm.advmatch.model.SymbolType;
import nhcm.advmatch.model.SelfTradePrevention;
import nhcm.advmatch.model.SymbolView;
import nhcm.advmatch.model.TradingStatusView;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.exchange.ExchangeMessages;
import nhcm.capitalmc.exchange.SymbolUtil;
import nhcm.capitalmc.gui.AdminInput;
import nhcm.capitalmc.gui.BaseSubGui;
import nhcm.capitalmc.gui.GuiActions;
import nhcm.capitalmc.gui.GuiWidgets;
import nhcm.capitalmc.gui.PageType;
import nhcm.capitalmc.gui.PollingValue;
import nhcm.capitalmc.gui.TradingGui;
import nhcm.capitalmc.service.GameServices;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Owner settings use native snapshots and the shared audited administration service. */
public class TradingSettingsPage extends BaseSubGui
{
    private enum Tab
    {
        RULES, PROTECTION, REFERENCE, SCHEDULE, EXCHANGE
    }

    private record Snapshot(SymbolView symbol, MarketView market, TradingStatusView trading)
    {
    }

    private record Field(String path, JsonPrimitive value)
    {
        String name()
        {
            return path.substring(path.lastIndexOf('.') + 1);
        }
    }

    private static final Gson JSON = new Gson();
    private final PollingValue<List<SymbolView>> symbols = new PollingValue<>(null);
    private final PollingValue<Snapshot> settings = new PollingValue<>(null);
    private long symbolId;
    private int page;
    private Tab tab = Tab.RULES;

    public TradingSettingsPage(TradingGui gui)
    {
        super(gui, PageType.TRADING_SETTINGS);
    }

    public void select(long id)
    {
        symbolId = id;
        page = 0;
        tab = Tab.RULES;
        settings.invalidate();
    }

    private boolean allowed()
    {
        var gui = getGui();
        return !gui.getPlayer().hasDisconnected() && gui.getCurrentPage() == PageType.TRADING_SETTINGS
               && gui.getPlayer().permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    private CompletableFuture<Snapshot> query(long id)
    {
        var status = id == 0 ? CapitalMC.exchange.trading().status()
                : CapitalMC.exchange.trading().status(CapitalMC.exchange.symbols().get(id));
        if (id == 0)
        {
            return status.thenApply(value -> new Snapshot(null, null, value));
        }
        var symbol = CapitalMC.exchange.symbols().get(id);
        return symbol.snapshot().thenCompose(view -> {
            CompletableFuture<MarketView> market = view.type() == SymbolType.Spot
                    ? CompletableFuture.completedFuture(null) : CapitalMC.exchange.contracts().market(symbol);
            return market.thenCombine(status, (contract, trading) -> new Snapshot(view, contract, trading));
        });
    }

    @Override
    public void render(TradingGui gui)
    {
        if (!allowed())
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.BARRIER, "ui.admin.denied", ""));
            return;
        }
        if (!GuiActions.ready(gui))
        {
            return;
        }
        var tabs = Tab.values();
        Item[] icons = {Items.WRITABLE_BOOK, Items.SHIELD, Items.COMPASS, Items.CLOCK, Items.COMPARATOR};
        for (int i = 0; i < tabs.length; i++)
        {
            var choice = tabs[i];
            GuiWidgets.option(gui, i, icons[i], "ui.settings.tab." + choice.name().toLowerCase(Locale.ROOT),
                              "ui.settings.tab_hint." + choice.name().toLowerCase(Locale.ROOT), tab == choice, () -> {
                tab = choice;
                page = 0;
            });
        }
        GuiWidgets.button(gui, 7, Items.BOOK, "ui.settings.help", "ui.settings.help_lore", () ->
                GuiActions.message(gui, "ui.settings.help_lore"));
        GuiWidgets.button(gui, 8, Items.NAME_TAG, "ui.settings.choose", "ui.settings.choose_lore", () -> select(0));
        long requested = symbolId;
        var snapshot = settings.get(requested, 500, () -> query(requested));
        if (snapshot == null || settings.error() != null)
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.CLOCK, settings.error() == null ? "ui.loading" : "ui.query_failed", ""));
            return;
        }
        if (tab == Tab.EXCHANGE)
        {
            renderExchange(gui, snapshot);
            return;
        }
        if (snapshot.symbol() == null)
        {
            renderSymbols(gui);
            return;
        }
        var symbol = snapshot.symbol();
        String state = GuiWidgets.text(gui, snapshot.trading().halted() ? "ui.settings.halted" : "ui.settings.running");
        gui.setSlot(6, TradingGui.icon(gui.getPlayer(), Items.PAPER, symbol.name(),
                                     GuiWidgets.text(gui, "ui.settings.market", symbol.symbolId(), symbol.type(), state)));
        if (tab != Tab.RULES && snapshot.market() == null)
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.GOLDEN_BOOTS, "ui.settings.contract_only", "ui.settings.contract_only_lore"));
            return;
        }
        if (tab == Tab.PROTECTION)
        {
            renderProtection(gui, snapshot);
            return;
        }
        String kind = tab.name().toLowerCase(Locale.ROOT);
        Object configuration = switch (tab)
        {
            case RULES -> symbol.tradingRules();
            case REFERENCE -> snapshot.market().referenceConfig();
            case SCHEDULE -> new ContractSchedule(snapshot.market().spec().fundingInterval(), snapshot.market().nextFundingTimestamp(),
                                                  snapshot.market().spec().maxFundingRate(), snapshot.market().spec().expiryTimestamp());
            default -> throw new IllegalStateException();
        };
        var fields = new ArrayList<Field>();
        flatten(JSON.toJsonTree(configuration).getAsJsonObject(), "", fields);
        if (tab == Tab.SCHEDULE)
        {
            fields.removeIf(field -> symbol.type() == SymbolType.Perpetual
                    ? field.name().equals("expiryTimestamp") : !field.name().equals("expiryTimestamp"));
        }
        if (tab == Tab.RULES && symbol.type() == SymbolType.Spot)
        {
            fields.removeIf(field -> field.name().equals("maxPositionNotional") || field.name().equals("maxLeverage")
                                     || field.name().equals("tickAwarePriceBand"));
        }
        int slot = 9;
        for (var field : fields)
        {
            String value = display(gui, symbol, field.name(), field.value());
            String unit = AdminInput.patchUnit(gui, symbol, field.name());
            String hint = GuiWidgets.text(gui, "ui.settings.effect." + field.name());
            String lore = GuiWidgets.text(gui, "ui.settings.field", value, field.value().getAsString(), unit, hint);
            GuiWidgets.button(gui, slot++, field.value().isBoolean() ? Items.LEVER : Items.PAPER,
                              GuiWidgets.text(gui, "ui.form.field." + field.name()), lore, () -> edit(kind, symbol, field));
        }
        if (tab == Tab.RULES)
        {
            GuiWidgets.button(gui, 34, Items.DYE.red(), "ui.admin.halt", "ui.settings.halt_lore", () -> action("halt", Long.toString(symbolId)));
            GuiWidgets.button(gui, 35, Items.DYE.lime(), "ui.admin.resume", "ui.settings.resume_lore", () -> action("resume", Long.toString(symbolId)));
        }
        if (tab == Tab.REFERENCE)
        {
            String sources = snapshot.market().referenceConfig().sources().stream()
                    .map(source -> "&7" + source.type() + " &e#" + source.sourceId() + " &7× &b" + source.weight())
                    .reduce((a, b) -> a + "\n" + b).orElse(GuiWidgets.text(gui, "ui.empty"));
            gui.setSlot(26, TradingGui.icon(gui.getPlayer(), Items.COMPASS, "ui.settings.sources", sources + "\n" + GuiWidgets.text(gui, "ui.settings.sources_help")));
        }
    }

    private void renderSymbols(TradingGui gui)
    {
        GuiWidgets.separator(gui);
        var values = symbols.get("all", 1000, SymbolUtil::allSymbols);
        if (symbols.error() != null || values == null)
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.CLOCK,
                                           symbols.error() == null ? "ui.loading" : "ui.query_failed", ""));
            return;
        }
        if (values.isEmpty())
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PAPER, "ui.settings.no_pairs", "ui.settings.no_pairs_lore"));
            return;
        }
        int pages = Math.max(1, (values.size() + 26) / 27);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = page * 27; i < Math.min(values.size(), page * 27 + 27); i++)
        {
            var symbol = values.get(i);
            gui.setSlot(18 + i - page * 27, GameServices.get().markets.icon(gui, symbol, () -> select(symbol.symbolId())));
        }
        GuiWidgets.button(gui, 51, Items.ARROW, "ui.page.previous", GuiWidgets.text(gui, "ui.page.number", page + 1, pages), () -> page = Math.max(0, page - 1));
        GuiWidgets.button(gui, 52, Items.ARROW, "ui.page.next", "", () -> page = Math.min(pages - 1, page + 1));
    }

    private void renderProtection(TradingGui gui, Snapshot snapshot)
    {
        var market = snapshot.market();
        long rate = market.spec().priceBandRate();
        String current = rate == 0 ? GuiWidgets.text(gui, "ui.form.off") : percentage(rate);
        GuiWidgets.option(gui, 9, Items.SHIELD, "ui.settings.protection", GuiWidgets.text(gui, "ui.settings.protection_lore", current),
                          rate != 0, () -> protection(snapshot.symbol(), rate == 0 ? "10" : "0"));
        var field = new Field("priceBandRate", new JsonPrimitive(rate));
        GuiWidgets.button(gui, 10, Items.COMPARATOR, "ui.settings.band", GuiWidgets.text(gui, "ui.settings.band_lore", current),
                          () -> edit("protection", snapshot.symbol(), field));
        var tick = new Field("tickAwarePriceBand", new JsonPrimitive(snapshot.symbol().tradingRules().tickAwarePriceBand()));
        GuiWidgets.option(gui, 11, Items.REPEATER, "ui.form.field.tickAwarePriceBand", "ui.form.hint.tickAwarePriceBand",
                          tick.value().getAsBoolean(), () -> edit("rules", snapshot.symbol(), tick));
        var assets = GameServices.get().assets;
        var base = assets.find(snapshot.symbol().baseAssetId()).orElseThrow();
        var quote = assets.find(snapshot.symbol().quoteAssetId()).orElseThrow();
        String mark = base.units().price(market.markPrice(), quote.units());
        gui.setSlot(18, TradingGui.icon(gui.getPlayer(), Items.GOLD_INGOT, "ui.settings.mark",
                                       GuiWidgets.text(gui, "ui.settings.mark_lore", mark, quote.displayName(), base.displayName(),
                                                       base.units().price(snapshot.symbol().tradingRules().priceTick(), quote.units()))));
        gui.setSlot(19, TradingGui.icon(gui.getPlayer(), Items.BOOK, "ui.settings.safety", "ui.settings.safety_lore"));
    }

    private void renderExchange(TradingGui gui, Snapshot snapshot)
    {
        GuiWidgets.separator(gui);
        GuiWidgets.button(gui, 18, Items.DYE.red(), "ui.settings.global_halt", "ui.settings.global_halt_lore", () -> action("halt", "0"));
        GuiWidgets.button(gui, 19, Items.DYE.lime(), "ui.settings.global_resume", "ui.settings.global_resume_lore", () -> action("resume", "0"));
        GuiWidgets.button(gui, 20, Items.WRITABLE_BOOK, "ui.admin.checkpoint", "ui.settings.checkpoint_lore", () -> action("checkpoint"));
        gui.setSlot(27, TradingGui.icon(gui.getPlayer(), snapshot.trading().exchangeHalted() ? Items.BARRIER : Items.COMPARATOR,
                                       "ui.settings.exchange_status", GuiWidgets.text(gui, "ui.settings.exchange_lore",
                                       GuiWidgets.text(gui, snapshot.trading().exchangeHalted() ? "ui.settings.halted" : "ui.settings.running"),
                                       snapshot.trading().exchangeReason())));
    }

    private static void flatten(JsonObject object, String prefix, List<Field> fields)
    {
        for (var entry : object.entrySet())
        {
            String path = prefix + entry.getKey();
            if (entry.getValue().isJsonObject())
            {
                flatten(entry.getValue().getAsJsonObject(), path + ".", fields);
            }
            else if (entry.getValue().isJsonPrimitive())
            {
                fields.add(new Field(path, entry.getValue().getAsJsonPrimitive()));
            }
        }
    }

    private static String percentage(long rate)
    {
        return BigDecimal.valueOf(rate, 4).stripTrailingZeros().toPlainString() + "%";
    }

    private static String display(TradingGui gui, SymbolView symbol, String name, JsonPrimitive value)
    {
        if (value.isBoolean())
        {
            return GuiWidgets.text(gui, value.getAsBoolean() ? "ui.form.on" : "ui.form.off");
        }
        if (value.isString())
        {
            return ExchangeMessages.label(gui.getPlayer(), SelfTradePrevention.valueOf(value.getAsString()));
        }
        long raw = value.getAsLong();
        if (name.endsWith("Rate"))
        {
            return percentage(raw);
        }
        if (name.endsWith("Timestamp"))
        {
            return raw == 0 ? "0" : Instant.ofEpochMilli(raw).toString();
        }
        if (name.equals("fundingInterval") || name.equals("maxAge"))
        {
            return raw + " ms";
        }
        var services = GameServices.get();
        var base = services.assets.find(symbol.baseAssetId()).orElseThrow();
        var quote = services.assets.find(symbol.quoteAssetId()).orElseThrow();
        if (name.equals("priceTick"))
        {
            return base.units().price(raw, quote.units());
        }
        if (name.toLowerCase(Locale.ROOT).contains("notional"))
        {
            return quote.format(raw) + " " + quote.displayName();
        }
        if (name.equals("quantityStep") || name.equals("minQuantity") || name.equals("maxOrderQuantity"))
        {
            return services.markets.quantityUnits(symbol).format(raw);
        }
        return Long.toString(raw);
    }

    private void edit(String kind, SymbolView symbol, Field field)
    {
        var gui = getGui();
        if (!allowed())
        {
            return;
        }
        String name = GuiWidgets.text(gui, "ui.form.field." + field.name());
        String unit = field.name().equals("priceBandRate") ? GuiWidgets.text(gui, "ui.settings.protection_range")
                : AdminInput.patchUnit(gui, symbol, field.name());
        String question = GuiWidgets.text(gui, "ui.settings.input", name, display(gui, symbol, field.name(), field.value()), unit);
        GuiActions.input(gui, question, input -> {
            var value = AdminInput.patchValue(symbol, field.name(), field.value(), input.strip());
            if (field.name().equals("priceBandRate") && (value.getAsLong() < 0 || value.getAsLong() >= 1_000_000))
            {
                throw new IllegalArgumentException("ui.settings.protection_range");
            }
            if (field.name().equals("maxLeverage") || field.name().equals("interestRate"))
            {
                Math.toIntExact(value.getAsLong());
            }
            return value;
        }).thenAccept(result -> result.ifPresent(value -> {
            if (!allowed() || symbolId != symbol.symbolId())
            {
                return;
            }
            if (kind.equals("protection"))
            {
                protection(symbol, BigDecimal.valueOf(value.getAsLong(), 4).toPlainString());
                return;
            }
            var patch = new JsonObject();
            var parent = patch;
            String[] path = field.path().split("\\.");
            for (int i = 0; i < path.length - 1; i++)
            {
                var child = new JsonObject();
                parent.add(path[i], child);
                parent = child;
            }
            parent.add(field.name(), value);
            review(kind, new String[]{Long.toString(symbol.symbolId()), patch.toString()},
                   name + ": " + display(gui, symbol, field.name(), field.value()) + " → " + display(gui, symbol, field.name(), value));
        })).exceptionally(error -> {
            GuiActions.error(gui, error);
            return null;
        });
    }

    private void protection(SymbolView symbol, String percent)
    {
        review("protection", new String[]{Long.toString(symbol.symbolId()), percent},
               GuiWidgets.text(getGui(), "ui.settings.protection_change", symbol.name(), percent));
    }

    private void action(String action, String... args)
    {
        review(action, args, GuiWidgets.text(getGui(), "ui.settings.action_review", ExchangeMessages.action(getGui().getPlayer(), action)));
    }

    private void review(String action, String[] args, String summary)
    {
        if (!allowed())
        {
            return;
        }
        var gui = getGui();
        long target = symbolId;
        long epoch = CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID());
        UUID account = gui.getUser().map(user -> user.getUuid()).orElse(null);
        GuiActions.input(gui, summary + "\n" + GuiWidgets.text(gui, "cli.reason.required"), String::strip)
                  .thenAccept(result -> result.ifPresent(reason -> {
                      if (!allowed() || target != symbolId || epoch != CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID())
                          || !Objects.equals(account, gui.getUser().map(user -> user.getUuid()).orElse(null)))
                      {
                          return;
                      }
                      GuiActions.confirm(gui, GuiWidgets.text(gui, "ui.settings.confirm", summary), () -> {
                          if (!allowed() || target != symbolId)
                          {
                              return;
                          }
                          var work = GameServices.get().administration.action(action, args, ItemStack.EMPTY,
                                      gui.getPlayer().getUUID().toString(), reason, () -> allowed() && target == symbolId
                                              && epoch == CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID())
                                              && Objects.equals(account, gui.getUser().map(user -> user.getUuid()).orElse(null)));
                          work.whenComplete((value, error) -> GameServices.get().server.execute(settings::invalidate));
                          GuiActions.task(gui, work, "ui.success.admin", ExchangeMessages.action(gui.getPlayer(), action));
                      });
                  })).exceptionally(error -> {
                      GuiActions.error(gui, error);
                      return null;
                  });
    }
}
