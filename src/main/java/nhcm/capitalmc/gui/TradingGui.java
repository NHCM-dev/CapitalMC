package nhcm.capitalmc.gui;

import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TooltipDisplay;
import nhcm.advmatch.model.SymbolType;
import nhcm.advmatch.model.SymbolView;
import nhcm.advmatch.object.User;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.gui.subgui.AdminPage;
import nhcm.capitalmc.gui.subgui.TradingSettingsPage;
import nhcm.capitalmc.gui.subgui.HomePage;
import nhcm.capitalmc.gui.subgui.AboutPage;
import nhcm.capitalmc.gui.subgui.ListSymbols;
import nhcm.capitalmc.gui.subgui.account.*;
import nhcm.capitalmc.gui.subgui.account.AccountPage;
import nhcm.capitalmc.gui.subgui.trading.ActivityPage;
import nhcm.capitalmc.gui.subgui.trading.PositionPage;
import nhcm.capitalmc.gui.subgui.trading.ConditionalPage;
import nhcm.capitalmc.gui.subgui.trading.ConditionalTicketPage;
import nhcm.capitalmc.gui.subgui.trading.HistoryPage;
import nhcm.capitalmc.gui.subgui.trading.SymbolInfoPage;
import nhcm.capitalmc.gui.subgui.trading.TradeContractPage;
import nhcm.capitalmc.gui.subgui.trading.TradePage;
import nhcm.capitalmc.gui.subgui.trading.TradeSpotPage;
import nhcm.capitalmc.gui.subgui.trading.OrderBookPage;
import nhcm.capitalmc.i18n.I18n;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Owns the inventory, navigation history, and shared controls.
 */
public class TradingGui extends SimpleGui
{
    /**
     * Shared decorative filler with no visible name, tooltip, or click action.
     */
    public static final GuiElement GRAY_GLASS_PANE = new GuiElementBuilder(Items.STAINED_GLASS_PANE.gray())
            .setName(Component.literal(" ")).hideTooltip().build();

    private final Map<PageType, BaseSubGui> pages;
    private final Deque<PageType> history = new ArrayDeque<>();
    private PageType currentPage = PageType.HOME;
    private boolean loginNoticeShown;
    private long selectedAsset;
    private long selectedPosition;
    private long selectedConditional;

    public long getSelectedPosition()
    {
        return selectedPosition;
    }

    public void selectPosition(long value)
    {
        selectedPosition = value;
    }

    public long getSelectedConditional()
    {
        return selectedConditional;
    }

    public void selectConditional(long value)
    {
        selectedConditional = value;
    }

    private SymbolView selectedSymbol;
    private final BookSettings bookSettings = new BookSettings();

    public BookSettings bookSettings()
    {
        return bookSettings;
    }

    public void clearSymbolFilter()
    {
        selectedSymbol = null;
    }

    public long getSelectedAsset()
    {
        return selectedAsset;
    }

    public SymbolView getSelectedSymbol()
    {
        return selectedSymbol;
    }

    public void showTradingSettings(long symbolId)
    {
        ((TradingSettingsPage) pages.get(PageType.TRADING_SETTINGS)).select(symbolId);
        showPage(PageType.TRADING_SETTINGS);
    }

    public AccountPage accountPage()
    {
        return (AccountPage) pages.get(PageType.MY_ACCOUNT);
    }

    public BaseSubGui page(PageType page)
    {
        return pages.get(page);
    }

    public void selectAsset(long asset)
    {
        selectedAsset = asset;
    }

    public void showAsset(long asset)
    {
        selectAsset(asset);
        showPage(PageType.ASSET);
    }

    public void showTrading(SymbolView view)
    {
        selectedSymbol = view;
        PageType page = view.type() == SymbolType.Spot ? PageType.TRADE_SPOT : PageType.TRADE_CONTRACT;
        ((TradePage) pages.get(page)).setSymbol(CapitalMC.exchange.symbols().get(view.symbolId()));
        showPage(page);
    }

    public TradingGui(ServerPlayer player)
    {
        super(MenuType.GENERIC_9x6, player, false);
        pages = Map.ofEntries(
                Map.entry(PageType.HOME, new HomePage(this)),
                Map.entry(PageType.ABOUT, new AboutPage(this)),
                Map.entry(PageType.LIST_SYMBOLS, new ListSymbols(this)),
                Map.entry(PageType.MY_ACCOUNT, new AccountPage(this)),
                Map.entry(PageType.TRADE_SPOT, new TradeSpotPage(this)),
                Map.entry(PageType.TRADE_CONTRACT, new TradeContractPage(this)),
                Map.entry(PageType.DEPOSIT_WITHDRAW, new DepositWithdrawPage(this)),
                Map.entry(PageType.ACCESS, new AccessPage(this)),
                Map.entry(PageType.ASSET, new AssetPage(this)),
                Map.entry(PageType.TRANSFER, new TransferPage(this)),
                Map.entry(PageType.PEG, new PegPage(this)),
                Map.entry(PageType.ORDERS, new ActivityPage(this, PageType.ORDERS)),
                Map.entry(PageType.POSITIONS, new ActivityPage(this, PageType.POSITIONS)),
                Map.entry(PageType.POSITION, new PositionPage(this)),
                Map.entry(PageType.CONDITIONALS, new ConditionalPage(this)),
                Map.entry(PageType.CONDITIONAL_TICKET, new ConditionalTicketPage(this)),
                Map.entry(PageType.ORDER_HISTORY, new HistoryPage(this, PageType.ORDER_HISTORY)),
                Map.entry(PageType.POSITION_HISTORY, new HistoryPage(this, PageType.POSITION_HISTORY)),
                Map.entry(PageType.TRADES, new HistoryPage(this, PageType.TRADES)),
                Map.entry(PageType.ADMIN, new AdminPage(this)),
                Map.entry(PageType.TRADING_SETTINGS, new TradingSettingsPage(this)),
                Map.entry(PageType.SYMBOL_INFO, new SymbolInfoPage(this)),
                Map.entry(PageType.ORDER_BOOK, new OrderBookPage(this, false)),
                Map.entry(PageType.BOOK_SETTINGS, new OrderBookPage(this, true)));
        setLockPlayerInventory(true);
        renderPage();
    }

    public PageType getCurrentPage()
    {
        return currentPage;
    }

    /**
     * Reads the live login session, so switching accounts never leaves a stale cached user.
     */
    public Optional<ServerUser> getUser()
    {
        return CapitalMC.serverUserManager.getLoggedInUser(getPlayer().getUUID());
    }

    /**
     * Creates or validates the binding asynchronously. Empty means there is no current login.
     */
    public CompletableFuture<Optional<User>> getExchangeUser()
    {
        return CapitalMC.serverUserManager.getLoggedInExchangeUser(getPlayer().getUUID());
    }

    public <T> CompletableFuture<Optional<T>> promptInput(Component prompt, Duration timeout,
                                                          Function<String, T> parser, Consumer<T> onValue)
    {
        return CapitalMC.guiInputManager.awaitChat(this, prompt, timeout, parser, onValue);
    }

    public CompletableFuture<Boolean> pauseUntil(Duration timeout, BooleanSupplier condition, Runnable onReady)
    {
        return CapitalMC.guiInputManager.awaitCondition(this, timeout, condition, onReady);
    }

    public void showPage(PageType page)
    {
        Objects.requireNonNull(page, "page");
        if (!pages.containsKey(page))
        {
            throw new IllegalArgumentException("Page is not implemented: " + page);
        }
        if (requiresLogin(page) && getUser().isEmpty())
        {
            page = PageType.HOME;
            history.clear();
        }
        if (page != currentPage)
        {
            resetAccountOperation();
            history.push(currentPage);
            currentPage = page;
        }
        renderPage();
    }

    public void goBack()
    {
        if (!history.isEmpty())
        {
            resetAccountOperation();
            currentPage = history.pop();
            renderPage();
        }
    }

    private void resetAccountOperation()
    {
        if (currentPage == PageType.DEPOSIT_WITHDRAW)
        {
            ((DepositWithdrawPage) pages.get(currentPage)).resetSelection();
            selectedAsset = 0;
        }
        else if (currentPage == PageType.TRANSFER)
        {
            ((TransferPage) pages.get(currentPage)).resetSelection();
            selectedAsset = 0;
        }
    }

    @Override
    public void onManualClose()
    {
        super.onManualClose();
        if (!CapitalMC.guiInputManager.isPaused(getPlayer().getUUID()))
        {
            resetAccountOperation();
        }
    }

    public boolean canGoBack()
    {
        return !history.isEmpty();
    }

    public void refresh()
    {
        renderPage();
    }

    private boolean requiresLogin(PageType page)
    {
        return switch (page)
        {
            case HOME, ACCESS, ABOUT -> false;
            case ADMIN, TRADING_SETTINGS -> !getPlayer().permissions().hasPermission(Permissions.COMMANDS_OWNER);
            default -> true;
        };
    }

    private void notifyLoginRequired()
    {
        if (getUser().isPresent())
        {
            loginNoticeShown = false;
        }
        else if (!loginNoticeShown && currentPage != PageType.ADMIN && currentPage != PageType.TRADING_SETTINGS)
        {
            GuiActions.message(this, "ui.access.required");
            loginNoticeShown = true;
        }
    }

    @Override
    public boolean open()
    {
        refresh();
        boolean opened = super.open();
        if (opened)
        {
            notifyLoginRequired();
        }
        return opened;
    }

    private void renderPage()
    {
        if (requiresLogin(currentPage) && getUser().isEmpty())
        {
            resetAccountOperation();
            currentPage = PageType.HOME;
            history.clear();
        }
        if (isOpen())
        {
            notifyLoginRequired();
        }
        rendered = new GuiElement[getSize()];
        Component title = text(getPlayer(), currentPage.getTitleKey());
        if (!title.equals(getTitle()))
        {
            setTitle(title);
        }
        BaseSubGui page = pages.get(currentPage);
        try
        {
            page.render();
            page.renderBottomBar();
            page.renderOverlay(this);
            var frame = rendered;
            rendered = null;
            for (int slot = 0; slot < getSize(); slot++)
            {
                if (frame[slot] == null)
                {
                    if (getGuiElement(slot) != null)
                    {
                        super.clearSlot(slot);
                    }
                }
                else
                {
                    var old = getGuiElement(slot);
                    if (old == frame[slot])
                    {
                        continue;
                    }
                    boolean unchanged = old != null && ItemStack.matches(old.getItemStackForDisplay(this), frame[slot].getItemStackForDisplay(this));
                    boolean automatic = getAutoUpdate();
                    if (unchanged)
                    {
                        setAutoUpdate(false); // existing wrapping slot reads the new callback dynamically
                    }
                    try
                    {
                        super.setSlot(slot, frame[slot]);
                    } finally
                    {
                        setAutoUpdate(automatic);
                    }
                }
            }
        } finally
        {
            rendered = null;
        }
    }

    // Compose a frame before publishing it. Clearing and refilling slots used to
    // invalidate every display each tick; callbacks still follow the latest data.
    private GuiElement[] rendered;

    @Override
    public GuiElement getGuiElement(int slot)
    {
        return rendered != null && slot >= 0 && slot < rendered.length ? rendered[slot] : super.getGuiElement(slot);
    }

    @Override
    public void setSlot(int slot, GuiElement element)
    {
        if (rendered != null)
        {
            rendered[slot] = element;
        }
        else
        {
            super.setSlot(slot, element);
        }
    }

    @Override
    public void clearSlot(int slot)
    {
        if (rendered != null)
        {
            rendered[slot] = null;
        }
        else
        {
            super.clearSlot(slot);
        }
    }

    /**
     * Uses the item's default name and components without requiring custom text.
     */
    public static GuiElement icon(Item item)
    {
        return new GuiElementBuilder(Objects.requireNonNull(item, "item")).build();
    }

    public static GuiElement glowingIcon(Item item)
    {
        return new GuiElementBuilder(Objects.requireNonNull(item, "item")).glow().build();
    }

    public static GuiElement button(Item item, Runnable action)
    {
        Objects.requireNonNull(action, "action");
        return new GuiElementBuilder(Objects.requireNonNull(item, "item"))
                .setCallback((index, type, clickAction, gui) -> action.run()).build();
    }

    public void setSlot(int slot, Item item)
    {
        setSlot(slot, icon(item));
    }

    /**
     * Creates a customized pane without modifying the shared decorative filler.
     */
    public static GuiElement grayGlass(String name, String description, Object... args)
    {
        return icon(Items.STAINED_GLASS_PANE.gray(), name, description, args);
    }

    public static GuiElement grayGlass(ServerPlayer player, String name, String description, Object... args)
    {
        return icon(player, Items.STAINED_GLASS_PANE.gray(), name, description, args);
    }

    public static GuiElement grayGlass(String name, Object[] nameArgs,
                                       String description, Object... descriptionArgs)
    {
        return icon(Items.STAINED_GLASS_PANE.gray(), name, nameArgs, description, descriptionArgs);
    }

    public static GuiElement grayGlass(ServerPlayer player, String name, Object[] nameArgs,
                                       String description, Object... descriptionArgs)
    {
        return icon(player, Items.STAINED_GLASS_PANE.gray(), name, nameArgs, description, descriptionArgs);
    }

    /**
     * Uses English for translation keys when no player is supplied. Literal strings also work.
     */
    public static GuiElement icon(Item item, String name, String description, Object... args)
    {
        return icon(null, item, name, description, args);
    }

    public static GuiElement icon(ServerPlayer player, Item item, String name, String description, Object... args)
    {
        return element(player, item, name, args, description, args).build();
    }

    public static GuiElement icon(Item item, String name, Object[] nameArgs,
                                  String description, Object... descriptionArgs)
    {
        return icon(null, item, name, nameArgs, description, descriptionArgs);
    }

    public static GuiElement icon(ServerPlayer player, Item item, String name, Object[] nameArgs,
                                  String description, Object... descriptionArgs)
    {
        return element(player, item, name, nameArgs, description, descriptionArgs).build();
    }

    /**
     * Adds only the visual enchantment glint, without adding enchantments or their attributes.
     */
    public static GuiElement glowingIcon(Item item, String name, String description, Object... args)
    {
        return glowingIcon(null, item, name, description, args);
    }

    public static GuiElement glowingIcon(ServerPlayer player, Item item, String name,
                                         String description, Object... args)
    {
        return element(player, item, name, args, description, args).glow().build();
    }

    public static GuiElement glowingIcon(Item item, String name, Object[] nameArgs,
                                         String description, Object... descriptionArgs)
    {
        return glowingIcon(null, item, name, nameArgs, description, descriptionArgs);
    }

    public static GuiElement glowingIcon(ServerPlayer player, Item item, String name, Object[] nameArgs,
                                         String description, Object... descriptionArgs)
    {
        return element(player, item, name, nameArgs, description, descriptionArgs).glow().build();
    }

    public static GuiElement button(Item item, String name, String description, Runnable action, Object... args)
    {
        return button(null, item, name, description, action, args);
    }

    public static GuiElement button(ServerPlayer player, Item item, String name, String description,
                                    Runnable action, Object... args)
    {
        Objects.requireNonNull(action, "action");
        return element(player, item, name, args, description, args)
                .setCallback((index, type, clickAction, gui) -> action.run()).build();
    }

    public static GuiElement button(Item item, String name, Object[] nameArgs, String description,
                                    Runnable action, Object... descriptionArgs)
    {
        return button(null, item, name, nameArgs, description, action, descriptionArgs);
    }

    public static GuiElement button(ServerPlayer player, Item item, String name, Object[] nameArgs,
                                    String description, Runnable action, Object... descriptionArgs)
    {
        Objects.requireNonNull(action, "action");
        return element(player, item, name, nameArgs, description, descriptionArgs)
                .setCallback((index, type, clickAction, gui) -> action.run()).build();
    }

    /**
     * Copies the displayed stack and callback; the source element is never modified.
     */
    public static GuiElement icon(GuiElement source)
    {
        return copyElement(source).build();
    }

    public static GuiElement glowingIcon(GuiElement source)
    {
        return copyElement(source).glow().build();
    }

    public static GuiElement button(GuiElement source, Runnable action)
    {
        Objects.requireNonNull(action, "action");
        return copyElement(source).setCallback((index, type, clickAction, gui) -> action.run()).build();
    }

    public static GuiElement grayGlass(GuiElement source)
    {
        return copyElement(source).setItem(Items.STAINED_GLASS_PANE.gray()).build();
    }

    public static GuiElement grayGlass(GuiElement source, String name, String description, Object... args)
    {
        return grayGlass(null, source, name, description, args);
    }

    public static GuiElement grayGlass(ServerPlayer player, GuiElement source,
                                       String name, String description, Object... args)
    {
        return element(player, source, name, args, description, args)
                .setItem(Items.STAINED_GLASS_PANE.gray()).build();
    }

    public static GuiElement grayGlass(GuiElement source, String name, Object[] nameArgs,
                                       String description, Object... descriptionArgs)
    {
        return grayGlass(null, source, name, nameArgs, description, descriptionArgs);
    }

    public static GuiElement grayGlass(ServerPlayer player, GuiElement source, String name, Object[] nameArgs,
                                       String description, Object... descriptionArgs)
    {
        return element(player, source, name, nameArgs, description, descriptionArgs)
                .setItem(Items.STAINED_GLASS_PANE.gray()).build();
    }

    /**
     * Uses English for translation keys when no player is supplied. Literal strings also work.
     */
    public static GuiElement icon(GuiElement item, String name, String description, Object... args)
    {
        return icon(null, item, name, description, args);
    }

    public static GuiElement icon(ServerPlayer player, GuiElement item, String name, String description, Object... args)
    {
        return element(player, item, name, args, description, args).build();
    }

    public static GuiElement icon(GuiElement item, String name, Object[] nameArgs,
                                  String description, Object... descriptionArgs)
    {
        return icon(null, item, name, nameArgs, description, descriptionArgs);
    }

    public static GuiElement icon(ServerPlayer player, GuiElement item, String name, Object[] nameArgs,
                                  String description, Object... descriptionArgs)
    {
        return element(player, item, name, nameArgs, description, descriptionArgs).build();
    }

    /**
     * Adds only the visual enchantment glint, without adding enchantments or their attributes.
     */
    public static GuiElement glowingIcon(GuiElement item, String name, String description, Object... args)
    {
        return glowingIcon(null, item, name, description, args);
    }

    public static GuiElement glowingIcon(ServerPlayer player, GuiElement item, String name,
                                         String description, Object... args)
    {
        return element(player, item, name, args, description, args).glow().build();
    }

    public static GuiElement glowingIcon(GuiElement item, String name, Object[] nameArgs,
                                         String description, Object... descriptionArgs)
    {
        return glowingIcon(null, item, name, nameArgs, description, descriptionArgs);
    }

    public static GuiElement glowingIcon(ServerPlayer player, GuiElement item, String name, Object[] nameArgs,
                                         String description, Object... descriptionArgs)
    {
        return element(player, item, name, nameArgs, description, descriptionArgs).glow().build();
    }

    public static GuiElement button(GuiElement item, String name, String description, Runnable action, Object... args)
    {
        return button(null, item, name, description, action, args);
    }

    public static GuiElement button(ServerPlayer player, GuiElement item, String name, String description,
                                    Runnable action, Object... args)
    {
        Objects.requireNonNull(action, "action");
        return element(player, item, name, args, description, args)
                .setCallback((index, type, clickAction, gui) -> action.run()).build();
    }

    public static GuiElement button(GuiElement item, String name, Object[] nameArgs, String description,
                                    Runnable action, Object... descriptionArgs)
    {
        return button(null, item, name, nameArgs, description, action, descriptionArgs);
    }

    public static GuiElement button(ServerPlayer player, GuiElement item, String name, Object[] nameArgs,
                                    String description, Runnable action, Object... descriptionArgs)
    {
        Objects.requireNonNull(action, "action");
        return element(player, item, name, nameArgs, description, descriptionArgs)
                .setCallback((index, type, clickAction, gui) -> action.run()).build();
    }

    public static Component text(String keyOrText, Object... args)
    {
        return text((ServerPlayer) null, keyOrText, args);
    }

    public static Component text(ServerPlayer player, String keyOrText, Object... args)
    {
        return I18n.text(player == null ? null : player.clientInformation().language(), keyOrText, args);
    }

    public static Component lore(Component line)
    {
        return Component.empty().withStyle(ChatFormatting.GRAY).withStyle(style -> style.withItalic(false)).append(line);
    }

    private static GuiElementBuilder element(ServerPlayer player, Item item, String name, Object[] nameArgs,
                                             String description, Object[] descriptionArgs)
    {
        GuiElementBuilder builder = new GuiElementBuilder(item)
                .setName(text(player, name, nameArgs)).hideDefaultTooltip();
        if (!description.isEmpty())
        {
            for (Component line : I18n.lines(player == null ? null : player.clientInformation().language(), description, descriptionArgs))
                builder.addLoreLine(lore(line));
        }
        return builder;
    }

    private static GuiElementBuilder copyElement(GuiElement source)
    {
        Objects.requireNonNull(source, "source");
        return new GuiElementBuilder(source.getItemStack().copy()).setCallback(source.getGuiCallback());
    }

    private static GuiElementBuilder element(ServerPlayer player, GuiElement source, String name,
                                             Object[] nameArgs, String description, Object[] descriptionArgs)
    {
        return copyElement(source).setName(text(player, name, nameArgs))
                                  .setLore(description.isEmpty() ? List.of()
                                                   : I18n.lines(player == null ? null : player.clientInformation().language(), description, descriptionArgs).stream().map(TradingGui::lore).toList())
                                  .setComponent(DataComponents.TOOLTIP_DISPLAY,
                                                TooltipDisplay.DEFAULT)
                                  .hideDefaultTooltip();
    }
}
