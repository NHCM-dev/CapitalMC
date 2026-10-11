package nhcm.capitalmc.gui;

import eu.pb4.sgui.api.elements.GuiElement;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A page renders into its owning window; it does not create another inventory.
 */
public abstract class BaseSubGui
{
    private final TradingGui gui;
    private final PageType page;
    private boolean bottomBarEnabled = true;
    private Predicate<TradingGui> backButtonVisible = TradingGui::canGoBack;
    private Function<TradingGui, GuiElement> centerItem = gui -> null;

    protected BaseSubGui(TradingGui gui, PageType page)
    {
        this.gui = Objects.requireNonNull(gui, "gui");
        this.page = Objects.requireNonNull(page, "page");
    }

    public void onEnter()
    {
    }

    public final TradingGui getGui()
    {
        return gui;
    }

    public final void render()
    {
        render(gui);
    }

    public final void renderBottomBar()
    {
        renderBottomBar(gui);
    }

    public final PageType getPage()
    {
        return page;
    }

    /**
     * Disabling the bar leaves the last row entirely under this page's control.
     */
    public final void setBottomBarEnabled(boolean enabled)
    {
        bottomBarEnabled = enabled;
    }

    public final boolean isBottomBarEnabled()
    {
        return bottomBarEnabled;
    }

    public final void setBackButtonVisible(boolean visible)
    {
        backButtonVisible = gui -> visible;
    }

    /**
     * Evaluated on every render, allowing visibility to follow page context.
     */
    public final void setBackButtonVisible(Predicate<TradingGui> visible)
    {
        backButtonVisible = Objects.requireNonNull(visible, "visible");
    }

    /**
     * The factory can create any icon or clickable element with the current player's language.
     */
    public final void setCenterItem(Function<TradingGui, GuiElement> factory)
    {
        centerItem = Objects.requireNonNull(factory, "factory");
    }

    public final void setCenterItem(Item item)
    {
        setCenterItem(TradingGui.icon(item));
    }

    public final void setCenterItem(GuiElement element)
    {
        Objects.requireNonNull(element, "element");
        centerItem = gui -> element;
    }

    public final void hideCenterItem()
    {
        centerItem = gui -> null;
    }

    /**
     * Applied after page content, reserving the last row only when enabled.
     */
    public final void renderBottomBar(TradingGui gui)
    {
        if (!bottomBarEnabled)
        {
            return;
        }
        int first = gui.getSize() - gui.getWidth();
        for (int slot = first; slot < gui.getSize(); slot++)
        {
            gui.setSlot(slot, TradingGui.GRAY_GLASS_PANE);
        }
        if (backButtonVisible.test(gui) && gui.canGoBack())
        {
            gui.setSlot(first, TradingGui.button(gui.getPlayer(), Items.ARROW, "gui.capitalmc.back",
                                                 "gui.capitalmc.back.description", gui::goBack));
        }
        GuiElement center = centerItem.apply(gui);
        if (center != null)
        {
            gui.setSlot(first + gui.getWidth() / 2, center);
        }
        gui.setSlot(gui.getSize() - 1, TradingGui.button(gui.getPlayer(), Items.BARRIER, "gui.capitalmc.close",
                                                         "gui.capitalmc.close.description", gui::close));
    }

    public abstract void render(TradingGui gui);

    public void renderOverlay(TradingGui gui)
    {
    }
}
