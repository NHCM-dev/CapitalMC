package nhcm.capitalmc.gui;

import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import nhcm.capitalmc.gui.subgui.account.AccountPage;
import nhcm.capitalmc.gui.subgui.HomePage;
import nhcm.capitalmc.gui.subgui.ListSymbols;
import nhcm.capitalmc.gui.subgui.trading.TradeSpotPage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import nhcm.capitalmc.i18n.I18n;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.account.ServerUser;
import nhcm.advmatch.object.User;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Owns the inventory, navigation history, and shared controls. */
public class TradingGui extends SimpleGui
{
    /** Shared decorative filler with no visible name, tooltip, or click action. */
    public static final GuiElement GRAY_GLASS_PANE = new GuiElementBuilder(Items.STAINED_GLASS_PANE.gray())
            .setName(Component.literal(" ")).hideTooltip().build();

    private final Map<PageType, BaseSubGui> pages;
    private final Deque<PageType> history = new ArrayDeque<>();
    private PageType currentPage = PageType.HOME;

    public TradingGui(ServerPlayer player) {
        super(MenuType.GENERIC_9x6, player, false);
        pages = Map.of(
                PageType.HOME, new HomePage(this),
                PageType.LIST_SYMBOLS, new ListSymbols(this),
                PageType.MY_ACCOUNT, new AccountPage(this),
                PageType.TRADE_SPOT, new TradeSpotPage(this));
        setLockPlayerInventory(true);
        renderPage();
    }
    public PageType getCurrentPage() {
        return currentPage;
    }

    /** Reads the live login session, so switching accounts never leaves a stale cached user. */
    public Optional<ServerUser> getUser() {
        return CapitalMC.serverUserManager.getLoggedInUser(getPlayer().getUUID());
    }

    /** Creates or validates the binding asynchronously. Empty means there is no current login. */
    public CompletableFuture<Optional<User>> getExchangeUser() {
        return CapitalMC.serverUserManager.getLoggedInExchangeUser(getPlayer().getUUID());
    }

    public void showPage(PageType page) {
        Objects.requireNonNull(page, "page");
        if (!pages.containsKey(page)) {
            throw new IllegalArgumentException("Page is not implemented: " + page);
        }
        if (page != currentPage) {
            history.push(currentPage);
            currentPage = page;
        }
        renderPage();
    }

    public void goBack() {
        if (!history.isEmpty()) {
            currentPage = history.pop();
            renderPage();
        }
    }

    public boolean canGoBack() {
        return !history.isEmpty();
    }

    public void refresh() {
        renderPage();
    }

    private void renderPage() {
        for (int slot = 0; slot < getSize(); slot++) {
            clearSlot(slot);
        }
        setTitle(text(getPlayer(), currentPage.getTitleKey()));
        BaseSubGui page = pages.get(currentPage);
        page.render();
        page.renderBottomBar();
    }

    /** Uses the item's default name and components without requiring custom text. */
    public static GuiElement icon(Item item) {
        return new GuiElementBuilder(Objects.requireNonNull(item, "item")).build();
    }

    public static GuiElement glowingIcon(Item item) {
        return new GuiElementBuilder(Objects.requireNonNull(item, "item")).glow().build();
    }

    public static GuiElement button(Item item, Runnable action) {
        Objects.requireNonNull(action, "action");
        return new GuiElementBuilder(Objects.requireNonNull(item, "item"))
                .setCallback((index, type, clickAction, gui) -> action.run()).build();
    }

    public void setSlot(int slot, Item item) {
        setSlot(slot, icon(item));
    }

    /** Creates a customized pane without modifying the shared decorative filler. */
    public static GuiElement grayGlass(String name, String description, Object... args) {
        return icon(Items.STAINED_GLASS_PANE.gray(), name, description, args);
    }

    public static GuiElement grayGlass(ServerPlayer player, String name, String description, Object... args) {
        return icon(player, Items.STAINED_GLASS_PANE.gray(), name, description, args);
    }

    public static GuiElement grayGlass(String name, Object[] nameArgs,
                                       String description, Object... descriptionArgs) {
        return icon(Items.STAINED_GLASS_PANE.gray(), name, nameArgs, description, descriptionArgs);
    }

    public static GuiElement grayGlass(ServerPlayer player, String name, Object[] nameArgs,
                                       String description, Object... descriptionArgs) {
        return icon(player, Items.STAINED_GLASS_PANE.gray(), name, nameArgs, description, descriptionArgs);
    }

    /** Uses English for translation keys when no player is supplied. Literal strings also work. */
    public static GuiElement icon(Item item, String name, String description, Object... args) {
        return icon(null, item, name, description, args);
    }

    public static GuiElement icon(ServerPlayer player, Item item, String name, String description, Object... args) {
        return element(player, item, name, args, description, args).build();
    }

    public static GuiElement icon(Item item, String name, Object[] nameArgs,
                                  String description, Object... descriptionArgs) {
        return icon(null, item, name, nameArgs, description, descriptionArgs);
    }

    public static GuiElement icon(ServerPlayer player, Item item, String name, Object[] nameArgs,
                                  String description, Object... descriptionArgs) {
        return element(player, item, name, nameArgs, description, descriptionArgs).build();
    }

    /** Adds only the visual enchantment glint, without adding enchantments or their attributes. */
    public static GuiElement glowingIcon(Item item, String name, String description, Object... args) {
        return glowingIcon(null, item, name, description, args);
    }

    public static GuiElement glowingIcon(ServerPlayer player, Item item, String name,
                                         String description, Object... args) {
        return element(player, item, name, args, description, args).glow().build();
    }

    public static GuiElement glowingIcon(Item item, String name, Object[] nameArgs,
                                         String description, Object... descriptionArgs) {
        return glowingIcon(null, item, name, nameArgs, description, descriptionArgs);
    }

    public static GuiElement glowingIcon(ServerPlayer player, Item item, String name, Object[] nameArgs,
                                         String description, Object... descriptionArgs) {
        return element(player, item, name, nameArgs, description, descriptionArgs).glow().build();
    }

    public static GuiElement button(Item item, String name, String description, Runnable action, Object... args) {
        return button(null, item, name, description, action, args);
    }

    public static GuiElement button(ServerPlayer player, Item item, String name, String description,
                                    Runnable action, Object... args) {
        Objects.requireNonNull(action, "action");
        return element(player, item, name, args, description, args)
                .setCallback((index, type, clickAction, gui) -> action.run()).build();
    }

    public static GuiElement button(Item item, String name, Object[] nameArgs, String description,
                                    Runnable action, Object... descriptionArgs) {
        return button(null, item, name, nameArgs, description, action, descriptionArgs);
    }

    public static GuiElement button(ServerPlayer player, Item item, String name, Object[] nameArgs,
                                    String description, Runnable action, Object... descriptionArgs) {
        Objects.requireNonNull(action, "action");
        return element(player, item, name, nameArgs, description, descriptionArgs)
                .setCallback((index, type, clickAction, gui) -> action.run()).build();
    }

    /** Copies the displayed stack and callback; the source element is never modified. */
    public static GuiElement icon(GuiElement source) {
        return copyElement(source).build();
    }

    public static GuiElement glowingIcon(GuiElement source) {
        return copyElement(source).glow().build();
    }

    public static GuiElement button(GuiElement source, Runnable action) {
        Objects.requireNonNull(action, "action");
        return copyElement(source).setCallback((index, type, clickAction, gui) -> action.run()).build();
    }

    public static GuiElement grayGlass(GuiElement source) {
        return copyElement(source).setItem(Items.STAINED_GLASS_PANE.gray()).build();
    }

    public static GuiElement grayGlass(GuiElement source, String name, String description, Object... args) {
        return grayGlass(null, source, name, description, args);
    }

    public static GuiElement grayGlass(ServerPlayer player, GuiElement source,
                                       String name, String description, Object... args) {
        return element(player, source, name, args, description, args)
                .setItem(Items.STAINED_GLASS_PANE.gray()).build();
    }

    public static GuiElement grayGlass(GuiElement source, String name, Object[] nameArgs,
                                       String description, Object... descriptionArgs) {
        return grayGlass(null, source, name, nameArgs, description, descriptionArgs);
    }

    public static GuiElement grayGlass(ServerPlayer player, GuiElement source, String name, Object[] nameArgs,
                                       String description, Object... descriptionArgs) {
        return element(player, source, name, nameArgs, description, descriptionArgs)
                .setItem(Items.STAINED_GLASS_PANE.gray()).build();
    }

    /** Uses English for translation keys when no player is supplied. Literal strings also work. */
    public static GuiElement icon(GuiElement item, String name, String description, Object... args) {
        return icon(null, item, name, description, args);
    }

    public static GuiElement icon(ServerPlayer player, GuiElement item, String name, String description, Object... args) {
        return element(player, item, name, args, description, args).build();
    }

    public static GuiElement icon(GuiElement item, String name, Object[] nameArgs,
                                  String description, Object... descriptionArgs) {
        return icon(null, item, name, nameArgs, description, descriptionArgs);
    }

    public static GuiElement icon(ServerPlayer player, GuiElement item, String name, Object[] nameArgs,
                                  String description, Object... descriptionArgs) {
        return element(player, item, name, nameArgs, description, descriptionArgs).build();
    }

    /** Adds only the visual enchantment glint, without adding enchantments or their attributes. */
    public static GuiElement glowingIcon(GuiElement item, String name, String description, Object... args) {
        return glowingIcon(null, item, name, description, args);
    }

    public static GuiElement glowingIcon(ServerPlayer player, GuiElement item, String name,
                                         String description, Object... args) {
        return element(player, item, name, args, description, args).glow().build();
    }

    public static GuiElement glowingIcon(GuiElement item, String name, Object[] nameArgs,
                                         String description, Object... descriptionArgs) {
        return glowingIcon(null, item, name, nameArgs, description, descriptionArgs);
    }

    public static GuiElement glowingIcon(ServerPlayer player, GuiElement item, String name, Object[] nameArgs,
                                         String description, Object... descriptionArgs) {
        return element(player, item, name, nameArgs, description, descriptionArgs).glow().build();
    }

    public static GuiElement button(GuiElement item, String name, String description, Runnable action, Object... args) {
        return button(null, item, name, description, action, args);
    }

    public static GuiElement button(ServerPlayer player, GuiElement item, String name, String description,
                                    Runnable action, Object... args) {
        Objects.requireNonNull(action, "action");
        return element(player, item, name, args, description, args)
                .setCallback((index, type, clickAction, gui) -> action.run()).build();
    }

    public static GuiElement button(GuiElement item, String name, Object[] nameArgs, String description,
                                    Runnable action, Object... descriptionArgs) {
        return button(null, item, name, nameArgs, description, action, descriptionArgs);
    }

    public static GuiElement button(ServerPlayer player, GuiElement item, String name, Object[] nameArgs,
                                    String description, Runnable action, Object... descriptionArgs) {
        Objects.requireNonNull(action, "action");
        return element(player, item, name, nameArgs, description, descriptionArgs)
                .setCallback((index, type, clickAction, gui) -> action.run()).build();
    }

    public static Component text(String keyOrText, Object... args) {
        return text((ServerPlayer) null, keyOrText, args);
    }

    public static Component text(ServerPlayer player, String keyOrText, Object... args) {
        return I18n.text(player == null ? null : player.clientInformation().language(), keyOrText, args);
    }

    private static GuiElementBuilder element(ServerPlayer player, Item item, String name, Object[] nameArgs,
                                              String description, Object[] descriptionArgs) {
        GuiElementBuilder builder = new GuiElementBuilder(item)
                .setName(text(player, name, nameArgs)).hideDefaultTooltip();
        if (!description.isEmpty()) {
            builder.addLoreLine(text(player, description, descriptionArgs));
        }
        return builder;
    }
    private static GuiElementBuilder copyElement(GuiElement source) {
        Objects.requireNonNull(source, "source");
        return new GuiElementBuilder(source.getItemStack().copy()).setCallback(source.getGuiCallback());
    }

    private static GuiElementBuilder element(ServerPlayer player, GuiElement source, String name,
                                              Object[] nameArgs, String description, Object[] descriptionArgs) {
        return copyElement(source).setName(text(player, name, nameArgs))
                .setLore(description.isEmpty() ? java.util.List.of()
                        : java.util.List.of(text(player, description, descriptionArgs)))
                .setComponent(net.minecraft.core.component.DataComponents.TOOLTIP_DISPLAY,
                        net.minecraft.world.item.component.TooltipDisplay.DEFAULT)
                .hideDefaultTooltip();
    }
}
