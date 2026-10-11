package nhcm.capitalmc.gui;

import eu.pb4.sgui.api.gui.SlotBasedGui;
import net.minecraft.world.item.Items;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.exchange.ExchangeMessages;
import nhcm.capitalmc.service.GameServices;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

public class GuiActions
{
    public static ServerUser user(TradingGui gui)
    {
        return gui.getUser().orElseThrow(() -> new IllegalStateException("gui.capitalmc.account.login_required"));
    }

    public static void message(TradingGui gui, String key, Object... args)
    {
        GameServices.get().message(gui.getPlayer(), key, args);
    }

    public static void run(SlotBasedGui gui, Runnable action)
    {
        if (gui instanceof TradingGui tradingGui)
        {
            run(tradingGui, action);
        }
        else
        {
            action.run();
        }
    }

    public static void run(TradingGui gui, Runnable action)
    {
        try
        {
            action.run();
        }
        catch (RuntimeException error)
        {
            error(gui, error);
        }
        finally
        {
            gui.requestRefresh();
        }
    }

    public static void error(TradingGui gui, Throwable error)
    {
        message(gui, "ui.error", ExchangeMessages.error(gui.getPlayer(), error));
    }

    public static <T> CompletableFuture<Optional<T>> input(TradingGui gui, String prompt, Function<String, T> parser)
    {
        return input(gui, prompt, parser, value -> {
        });
    }

    public static <T> CompletableFuture<Optional<T>> input(TradingGui gui, String prompt,
                                                           Function<String, T> parser, Consumer<T> apply)
    {
        return gui.promptInput(TradingGui.text(gui.getPlayer(), prompt), Duration.ofSeconds(30), parser, apply);
    }

    public static CompletableFuture<Optional<String>> credentialInput(TradingGui gui)
    {
        return credentialInput(gui, "ui.access.password");
    }

    public static CompletableFuture<Optional<String>> credentialInput(TradingGui gui, String prompt)
    {
        return CapitalMC.guiInputManager.awaitCredential(gui,
                                                         TradingGui.text(gui.getPlayer(), prompt), Duration.ofSeconds(30))
                                        .thenApply(value -> value.map(text -> text.equals("-") ? "" : text));
    }

    public static void task(TradingGui gui, CompletableFuture<?> work)
    {
        task(gui, work, "ui.success");
    }

    public static void task(TradingGui gui, CompletableFuture<?> work, String success, Object... args)
    {
        var account = gui.getUser().map(ServerUser::getUuid).orElse(null);
        var page = gui.getCurrentPage();
        long epoch = CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID());
        gui.pauseUntil(Duration.ofSeconds(30), work::isDone, () -> {
               try
               {
                   work.getNow(null);
                   message(gui, GuiWidgets.text(gui, success, args));
               } catch (RuntimeException error)
               {
                   error(gui, error);
               }
           }).thenAccept(completed -> {
               if (!completed)
               {
                   observe(gui, work, account, page, epoch, success, args);
               }
           })
           .exceptionally(error -> {
               error(gui, error);
               return null;
           });
    }

    public static void observe(TradingGui gui, CompletableFuture<?> work)
    {
        observe(gui, work, gui.getUser().map(ServerUser::getUuid).orElse(null), gui.getCurrentPage(),
                CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID()), "ui.success");
    }

    private static void observe(TradingGui gui, CompletableFuture<?> work, UUID account, PageType page, long epoch, String success, Object... args)
    {
        work.whenComplete((value, error) -> GameServices.get().server.execute(() -> {
            if (gui.getPlayer().hasDisconnected() || gui.getCurrentPage() != page || epoch != CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID())
                || !Objects.equals(account, gui.getUser().map(ServerUser::getUuid).orElse(null)))
            {
                return;
            }
            gui.requestRefresh();
            if (error == null)
            {
                message(gui, GuiWidgets.text(gui, success, args));
            }
            else
            {
                error(gui, error);
            }
        }));
    }

    public static void confirm(TradingGui gui, String summary, Runnable action)
    {
        input(gui, summary, text -> {
            if (!text.equalsIgnoreCase("yes") && !text.equals("确认"))
            {
                throw new IllegalArgumentException("ui.confirm.yes");
            }
            return true;
        }, value -> {
        }).thenAccept(result -> {
            if (result.isPresent())
            {
                run(gui, action);
            }
        }).exceptionally(error -> {
            error(gui, error);
            return null;
        });
    }

    public static boolean ready(TradingGui gui)
    {
        if (CapitalMC.services == null || !GameServices.get().ready().isDone())
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.CLOCK, "ui.loading", ""));
            return false;
        }
        if (GameServices.get().ready().isCompletedExceptionally())
        {
            gui.setSlot(22, TradingGui.icon(gui.getPlayer(), Items.BARRIER, "ui.services.failed", ""));
            return false;
        }
        return true;
    }
}
