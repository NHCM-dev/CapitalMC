package nhcm.capitalmc.gui;

import com.mojang.serialization.JsonOps;
import eu.pb4.sgui.api.elements.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ResolvableProfile;
import nhcm.advmatch.model.SymbolType;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.exchange.ExchangeMessages;
import nhcm.capitalmc.i18n.I18n;
import nhcm.capitalmc.service.GameServices;
import nhcm.capitalmc.service.McAssetRegistry;

import java.util.*;
import java.util.function.IntConsumer;

public class GuiWidgets
{
    private GuiWidgets()
    {
    }

    public static GuiElement accountHead(TradingGui gui, ServerUser user, Runnable action)
    {
        var stack = new ItemStack(Items.PLAYER_HEAD);
        user.getProfile().ifPresent(profile -> stack.set(DataComponents.PROFILE,
                                                         ResolvableProfile.CODEC.parse(JsonOps.INSTANCE, profile).getOrThrow()));
        var builder = new GuiElementBuilder(stack).setName(TradingGui.text(gui.getPlayer(), user.getDisplayName()))
                                                  .addLoreLine(TradingGui.text(gui.getPlayer(), user.getUsername()));
        if (action != null)
        {
            builder.setCallback((i, t, c, g) -> GuiActions.run(gui, action));
        }
        return builder.build();
    }

    /**
     * Retains enchantments/components and their tooltip; only appends account information.
     */
    public static GuiElement asset(TradingGui gui, long id, String lore, Runnable action)
    {
        var registered = GameServices.get().assets.find(id);
        var stack = registered.map(value -> value.prototype()).orElseGet(() -> new ItemStack(Items.PAPER));
        var builder = new GuiElementBuilder(stack).setName(TradingGui.text(gui.getPlayer(),
                                                                           registered.map(value -> value.displayName()).orElse("Asset #" + id)));
        for (String line : lore.split("\n"))
            if (!line.isEmpty())
            {
                builder.addLoreLine(TradingGui.lore(TradingGui.text(gui.getPlayer(), line)));
            }
        if (action != null)
        {
            builder.setCallback((i, t, c, g) -> GuiActions.run(gui, action));
        }
        return builder.build();
    }

    public static void button(TradingGui gui, int slot, Item item, String name, String lore, Runnable action)
    {
        gui.setSlot(slot, action == null ? TradingGui.icon(gui.getPlayer(), item, name, lore)
                                        : TradingGui.button(gui.getPlayer(), item, name, lore, action));
    }

    /**
     * Fresh button each render: the previous choice cannot retain its glint or callback.
     */
    public static void option(TradingGui gui, int slot, Item item, String name, String lore, boolean selected, Runnable action)
    {
        String description = lore.isEmpty() ? "" : text(gui, lore);
        button(gui, slot, item, name, selected ? "&a" + text(gui, "ui.option.selected")
                                                 + (description.isEmpty() ? "" : "\n" + description) : description, action);
        if (selected)
        {
            gui.setSlot(slot, TradingGui.glowingIcon(gui.getGuiElement(slot)));
        }
    }

    public static void separator(TradingGui gui)
    {
        for (int i = 9; i < 18; i++)
            gui.setSlot(i, TradingGui.GRAY_GLASS_PANE);
    }

    public static String text(TradingGui gui, String key, Object... args)
    {
        for (int i = 0; i < args.length; i++)
            if (args[i] instanceof Enum<?> value)
            {
                args[i] = ExchangeMessages.label(gui.getPlayer(), value);
            }
        return I18n.format(gui.getPlayer().clientInformation().language(), key, args);
    }

    public static String amount(long asset, long raw)
    {
        return GameServices.get().assets.find(asset).map(value -> value.format(raw)).orElse(Long.toString(raw));
    }

    public static void activityNavigation(TradingGui gui, PageType current)
    {
        var types = new SymbolType[]{SymbolType.Spot, SymbolType.Perpetual, SymbolType.Future};
        var icons = new Item[]{Items.GOLDEN_BOOTS, Items.CHAINMAIL_BOOTS, Items.CLOCK};
        for (int index = 0; index < types.length; index++)
        {
            var type = types[index];
            option(gui, index, icons[index], "ui.enum.SymbolType." + type, "ui.activity.scope_hint", gui.activityType() == type,
                   () -> gui.showActivity(type, type == SymbolType.Spot && (current == PageType.POSITIONS || current == PageType.POSITION_HISTORY) ? PageType.ORDERS : current));
        }
        option(gui, 3, Items.BOOK, "ui.orders", "ui.activity.scope_hint", current == PageType.ORDERS, () -> gui.showPage(PageType.ORDERS));
        if (gui.activityType() != SymbolType.Spot)
        {
            option(gui, 4, Items.SHIELD, "ui.positions", "ui.activity.scope_hint", current == PageType.POSITIONS, () -> gui.showPage(PageType.POSITIONS));
            option(gui, 6, Items.WRITABLE_BOOK, "ui.position_history", "", current == PageType.POSITION_HISTORY, () -> gui.showPage(PageType.POSITION_HISTORY));
        }
        option(gui, 5, Items.WRITABLE_BOOK, "ui.order_history", "", current == PageType.ORDER_HISTORY, () -> gui.showPage(PageType.ORDER_HISTORY));
        option(gui, 7, Items.REDSTONE_TORCH, "ui.conditional.page", "ui.activity.scope_hint", current == PageType.CONDITIONALS, () -> gui.showPage(PageType.CONDITIONALS));
        option(gui, 8, Items.PAPER, "ui.trades", "", current == PageType.TRADES, () -> gui.showPage(PageType.TRADES));
        separator(gui);
    }

    public static void versionWarning(TradingGui gui, int slot)
    {
        if (CapitalMC.runtime == null || CapitalMC.runtime.compatibility.warnings().isEmpty()) return;
        StringBuilder lore = new StringBuilder();
        for (var warning : CapitalMC.runtime.compatibility.warnings())
        {
            if (!lore.isEmpty()) lore.append('\n');
            lore.append(text(gui, "ui.data.version_details", warning.source(), warning.stored().equals("unknown") ? text(gui, "ui.data.version_unknown") : warning.stored(), warning.running()));
        }
        lore.append('\n').append(text(gui, "ui.data.version_hint"));
        button(gui, slot, Items.REDSTONE_TORCH, "ui.data.version_warning", lore.toString(), null);
    }

    public static String amountInput(TradingGui gui, long id, long minimum)
    {
        var asset = GameServices.get().assets.find(id).orElseThrow();
        return text(gui, "ui.input.amount", asset.displayName(), asset.format(minimum));
    }

    public static String priceInput(TradingGui gui, long base, long quote)
    {
        var assets = GameServices.get().assets;
        return text(gui, "ui.input.price", assets.find(quote).orElseThrow().displayName(), assets.find(base).orElseThrow().displayName());
    }

    public static String unitsDescription(TradingGui gui, McAssetRegistry.Asset asset)
    {
        String size = asset.unitSize().stripTrailingZeros().toPlainString();
        if (asset.electronic())
        {
            return text(gui, "ui.units.electronic_details", size);
        }
        return text(gui, "ui.units.details", size, asset.format(asset.minimumDeposit()), asset.format(asset.minimumWithdrawal()));
    }

    public static int clamp(int page, int count)
    {
        return Math.max(0, Math.min(page, Math.max(0, (count - 1) / 27)));
    }

    public static void paging(TradingGui gui, int page, int count, IntConsumer select)
    {
        button(gui, 51, Items.ARROW, text(gui, "ui.page.previous"), text(gui, "ui.page.number", page + 1, Math.max(1, (count + 26) / 27)), () -> select.accept(clamp(page - 1, count)));
        button(gui, 52, Items.ARROW, "ui.page.next", text(gui, "ui.page.number", page + 1, Math.max(1, (count + 26) / 27)), () -> select.accept(clamp(page + 1, count)));
    }
}
