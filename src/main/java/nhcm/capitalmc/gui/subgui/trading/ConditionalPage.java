package nhcm.capitalmc.gui.subgui.trading;

import net.minecraft.world.item.Items;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.exchange.*;
import nhcm.capitalmc.gui.*;
import nhcm.capitalmc.service.GameServices;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ConditionalPage extends BaseSubGui
{
    private record Data(List<ConditionalOrderView> rows, List<SymbolView> symbols, ReadPage page,
                        ConditionalOrderView selected)
    {
    }

    private final PollingValue<Data> cache = new PollingValue<>(new Data(List.of(), List.of(), new ReadPage(), null));
    private ReadCursor cursor = new ReadCursor();
    private final Deque<ReadCursor> previous = new ArrayDeque<>();
    private boolean history;
    private Object context;
    private ReadPage queryPage = new ReadPage();
    private Data displayed;

    public ConditionalPage(TradingGui gui)
    {
        super(gui, PageType.CONDITIONALS);
    }

    private void reset()
    {
        cursor = new ReadCursor();
        previous.clear();
        cache.invalidate();
    }

    private SymbolView symbol(Data data, ConditionalOrderView row)
    {
        return data.symbols.stream().filter(s -> s.symbolId() == row.request().symbolId()).findFirst().orElseThrow();
    }

    private long price(SymbolView symbol, String text)
    {
        var assets = GameServices.get().assets;
        return assets.find(symbol.baseAssetId()).orElseThrow().units().parsePrice(text, assets.find(symbol.quoteAssetId()).orElseThrow().units());
    }

    private void modify(Data data, ConditionalOrderView row, String field)
    {
        var gui = getGui();
        var user = GuiActions.user(gui);
        var symbol = symbol(data, row);
        String prompt = field.equals("quantity") ? GuiWidgets.text(gui, "ui.conditional.quantity_input")
                : GuiWidgets.text(gui, field.equals("trigger") ? "ui.conditional.trigger_input" : "ui.position.limit_input") + "\n" + GuiWidgets.priceInput(gui, symbol.baseAssetId(), symbol.quoteAssetId());
        GuiActions.input(gui, prompt, text -> field.equals("quantity") ? GameServices.get().markets.quantityUnits(symbol).parse(text) : price(symbol, text))
                  .thenAccept(value -> value.ifPresent(amount -> {
                      var request = row.request();
                      long trigger = field.equals("trigger") ? amount : request.triggerPrice();
                      long execution = field.equals("price") ? amount : request.price();
                      long quantity = field.equals("quantity") ? amount : request.quantity();
                      GuiActions.confirm(gui, GuiWidgets.text(gui, "ui.conditional.modify_confirm", row.conditionalOrderId()),
                                         () -> GuiActions.task(gui, GameServices.get().settlements.modifyConditional(user, row.conditionalOrderId(), trigger, execution, quantity),
                                                               "ui.conditional.modify_done", row.conditionalOrderId()));
                  })).exceptionally(error -> {
                      GuiActions.error(gui, error);
                      return null;
                  });
    }

    @Override
    public void render(TradingGui gui)
    {
        if (!GuiActions.ready(gui) || gui.getUser().isEmpty())
        {
            return;
        }
        var user = GuiActions.user(gui);
        long epoch = CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID());
        var type = gui.activityType();
        var key = List.of(user.getUuid(), epoch, type);
        if (!key.equals(context))
        {
            context = key;
            gui.selectConditional(0);
            reset();
        }
        long selected = gui.getSelectedConditional();
        boolean showHistory = history;
        var currentCursor = cursor;
        var data = cache.get(List.of(key, history, cursor, selected), () -> {
            CompletableFuture<ConditionalOrderView> detail = selected == 0 ? CompletableFuture.completedFuture(null) : CapitalMC.exchange.conditionalOrders().get(selected).snapshot().thenApply(value -> {
                if (value.request().userId() != user.getExchangeUserId())
                {
                    throw new SecurityException("ui.conditional.owner");
                }
                return value;
            });
            return SymbolUtil.allSymbols().thenCompose(symbols -> ActivityScope.page(next -> CapitalMC.exchange.conditionalOrders().list(
                    new ObservationQuery().withUserId(user.getExchangeUserId()).withIncludeTerminal(showHistory).withLimit(27).withCursor(next)), currentCursor,
                    row -> ActivityScope.matches(row, symbols, type) && (showHistory ? row.status() != ConditionalOrderStatus.Waiting : row.status() == ConditionalOrderStatus.Waiting))
                    .thenApply(result -> new Data(result.values(), symbols, result.page(), null)))
                       .thenCombine(detail, (result, value) -> new Data(result.rows, result.symbols, result.page, value));
        });
        displayed = data;
        queryPage = data.page;
        GuiWidgets.activityNavigation(gui, getPage());
        GuiWidgets.option(gui, 7, Items.REDSTONE_TORCH, history ? "ui.conditional.history" : "ui.conditional.waiting", "ui.conditional.toggle_history", true, () -> {
            history = !history;
            gui.selectConditional(0);
            reset();
        });
        if (cache.error() != null)
        {
            displayed = null;
            queryPage = new ReadPage();
            GuiWidgets.button(gui, 22, Items.PAPER, "ui.query_failed", ExchangeMessages.error(gui.getPlayer(), cache.error()), () -> {
                gui.selectConditional(0);
                reset();
            });
            return;
        }
        if (!cache.loaded())
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.CLOCK, "ui.loading", ""));
            return;
        }
        for (int i = 0; i < data.rows.size(); i++)
        {
            var row = data.rows.get(i);
            GuiWidgets.button(gui, 18 + i, row.request().kind() == ConditionalOrderKind.TakeProfit ? Items.EMERALD : Items.REDSTONE,
                              symbol(data, row).name() + " #" + row.conditionalOrderId(), FinancialText.lore(row, data.symbols, gui.getPlayer()),
                              () -> gui.selectConditional(row.conditionalOrderId()));
        }
        if (data.rows.isEmpty())
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.PAPER, cache.error() == null ? "ui.empty" : "ui.query_failed", ""));
        }
    }

    @Override
    public void renderOverlay(TradingGui gui)
    {
        GuiWidgets.button(gui, 46, Items.END_CRYSTAL, "gui.capitalmc.refresh", "", () -> {
            gui.selectConditional(0);
            reset();
        });
        if (displayed != null && displayed.selected != null)
        {
            var data = displayed;
            var row = data.selected;
            var user = GuiActions.user(gui);
            GuiWidgets.button(gui, 46, Items.PAPER, "#" + row.conditionalOrderId(), FinancialText.lore(row, data.symbols, gui.getPlayer()), () -> gui.selectConditional(0));
            if (row.status() == ConditionalOrderStatus.Waiting)
            {
                GuiWidgets.button(gui, 47, Items.BARRIER, "ui.conditional.cancel", "ui.conditional.cancel_hint", () -> GuiActions.confirm(gui, GuiWidgets.text(gui, "ui.conditional.cancel_confirm", row.conditionalOrderId()),
                        () -> GuiActions.task(gui, GameServices.get().settlements.cancelConditional(user, row.conditionalOrderId()), "ui.conditional.cancel_done", row.conditionalOrderId())));
                GuiWidgets.button(gui, 48, Items.COMPASS, "ui.conditional.modify_trigger", "", () -> modify(data, row, "trigger"));
                GuiWidgets.button(gui, 49, Items.PAPER, "ui.conditional.modify_quantity", "", () -> modify(data, row, "quantity"));
                if (row.request().type() == OrderType.Limit)
                {
                    GuiWidgets.button(gui, 50, Items.BOW, "ui.conditional.modify_price", "", () -> modify(data, row, "price"));
                }
            }
        }
        else
        {
            GuiWidgets.button(gui, 50, Items.WRITABLE_BOOK, "ui.conditional.ticket", "", () -> gui.showPage(PageType.CONDITIONAL_TICKET));
        }
        if (!previous.isEmpty())
        {
            GuiWidgets.button(gui, 51, Items.ARROW, "ui.page.previous", "", () -> {
                cursor = previous.pop();
                cache.invalidate();
            });
        }
        if (queryPage.hasMore())
        {
            GuiWidgets.button(gui, 52, Items.ARROW, "ui.page.next", "", () -> {
                previous.push(cursor);
                cursor = queryPage.next();
                cache.invalidate();
            });
        }
    }
}
