package nhcm.capitalmc.management;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.gui.TradingGui;
import nhcm.capitalmc.gui.input.InputWindow;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

/**
 * Server-thread pause controller. Chat is consumed before vanilla broadcast and logging.
 */
public final class GuiInputManager
{
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private final Map<UUID, Pending> pending = new HashMap<>();
    private boolean registered;

    public void registerEvents()
    {
        if (registered)
        {
            return;
        }
        registered = true;
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, player, type) -> {
            Pending request = pending.get(player.getUUID());
            if (request == null)
            {
                return true;
            }
            if (!request.valid.getAsBoolean())
            {
                finish(request, null, false);
                return false;
            }
            if (request.parse == null)
            {
                String input = message.signedContent().strip();
                if (input.equalsIgnoreCase("cancel") || input.equals("取消"))
                {
                    finish(request, null, true);
                    return false;
                }
                return !request.privateWait;
            }
            // Keep the value local to this callback; never retain or echo credentials.
            if (!request.valid.getAsBoolean())
            {
                finish(request, null, false);
            }
            else if (request.expired.getAsBoolean())
            {
                finish(request, null, true);
            }
            else
            {
                try
                {
                    receive(request, message.signedContent());
                } catch (RuntimeException error)
                {
                    fail(request, error);
                }
            }
            return false;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (Pending request : List.copyOf(pending.values()))
                try
                {
                    if (request.gui.getPlayer().hasDisconnected() || !request.valid.getAsBoolean())
                    {
                        finish(request, null, false);
                    }
                    else if (request.expired.getAsBoolean())
                    {
                        request.gui.getPlayer().sendSystemMessage(TradingGui.text(request.gui.getPlayer(),
                                                                                  request.parse == null ? "ui.pause.timeout" : "gui.capitalmc.input.timeout"));
                        finish(request, null, true);
                    }
                    else if (request.gui.getPlayer().containerMenu != request.gui.getPlayer().inventoryMenu)
                    {
                        finish(request, null, false);
                    }
                    else if (request.condition != null && request.condition.getAsBoolean())
                    {
                        finish(request, request.onReady, true);
                    }
                } catch (RuntimeException error)
                {
                    fail(request, error);
                }
        });
    }

    public <T> CompletableFuture<Optional<T>> awaitChat(TradingGui gui, Component prompt, Duration timeout,
                                                        Function<String, T> parser, Consumer<T> onValue)
    {
        return awaitInput(gui, prompt, timeout, parser, onValue, true);
    }

    public CompletableFuture<Optional<String>> awaitCredential(TradingGui gui, Component prompt, Duration timeout)
    {
        return awaitInput(gui, prompt, timeout, Function.identity(), value -> {
        }, false);
    }

    private <T> CompletableFuture<Optional<T>> awaitInput(TradingGui gui, Component prompt, Duration timeout,
                                                          Function<String, T> parser, Consumer<T> onValue, boolean strip)
    {
        Objects.requireNonNull(prompt);
        Objects.requireNonNull(onValue);
        InputWindow<T> window = new InputWindow<>(timeout, parser, System.nanoTime(), strip);
        CompletableFuture<Optional<T>> result = new CompletableFuture<>();
        Pending request = prepare(gui);
        request.prompt = prompt;
        request.echoReply = strip; // Credentials preserve whitespace and must never be echoed.
        request.expired = () -> window.isExpired(System.nanoTime());
        request.parse = input -> {
            T value = window.submit(input, System.nanoTime());
            request.success = () -> result.complete(Optional.of(value));
            return () -> onValue.accept(value);
        };
        request.cancel = () -> {
            window.cancel();
            result.complete(Optional.empty());
        };
        request.fail = result::completeExceptionally;
        begin(request);
        gui.getPlayer().sendSystemMessage(Component.empty()
                                                   .append(TradingGui.text(gui.getPlayer(), "ui.chat.prefix"))
                                                   .append(prompt.copy().withStyle(ChatFormatting.AQUA))
                                                   .append(TradingGui.text(gui.getPlayer(), "ui.chat.hint", Math.max(1, timeout.toSeconds()))));
        return result;
    }

    /**
     * @deprecated General text input now uses chat.
     */
    @Deprecated
    public <T> CompletableFuture<Optional<T>> awaitSign(TradingGui gui, Component prompt, Duration timeout,
                                                        Function<String, T> parser, Consumer<T> onValue)
    {
        return awaitChat(gui, prompt, timeout, parser, onValue);
    }

    public CompletableFuture<Boolean> awaitCondition(TradingGui gui, Duration timeout,
                                                     BooleanSupplier condition, Runnable onReady)
    {
        return awaitCondition(gui, timeout, condition, onReady, false);
    }

    public CompletableFuture<Boolean> awaitPrivateCondition(TradingGui gui, Duration timeout,
                                                            BooleanSupplier condition, Runnable onReady)
    {
        return awaitCondition(gui, timeout, condition, onReady, true);
    }

    private CompletableFuture<Boolean> awaitCondition(TradingGui gui, Duration timeout,
                                                      BooleanSupplier condition, Runnable onReady, boolean privateWait)
    {
        Objects.requireNonNull(condition);
        Objects.requireNonNull(onReady);
        InputWindow<String> window = new InputWindow<>(timeout, Function.identity(), System.nanoTime());
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Pending request = prepare(gui);
        request.privateWait = privateWait;
        request.expired = () -> window.isExpired(System.nanoTime());
        request.condition = condition;
        request.onReady = onReady;
        request.success = () -> result.complete(true);
        request.cancel = () -> result.complete(false);
        request.fail = result::completeExceptionally;
        begin(request);
        return result;
    }

    private Pending prepare(TradingGui gui)
    {
        if (!gui.isOpen())
        {
            throw new IllegalStateException("GUI must be open before pausing");
        }
        var page = gui.getCurrentPage();
        UUID account = gui.getUser().map(value -> value.getUuid()).orElse(null);
        var level = gui.getPlayer().level();
        long sessionVersion = CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID());
        Pending request = new Pending(gui);
        request.valid = () -> gui.getPlayer().level() == level && gui.getCurrentPage() == page && Objects.equals(account, gui.getUser().map(value -> value.getUuid()).orElse(null))
                              && sessionVersion == CapitalMC.serverUserManager.getSessionVersion(gui.getPlayer().getUUID());
        return request;
    }

    private void begin(Pending request)
    {
        if (pending.putIfAbsent(request.gui.getPlayer().getUUID(), request) != null)
        {
            throw new IllegalStateException("Already paused");
        }
        request.gui.close();
    }

    private void receive(Pending request, String input)
    {
        if (request.echoReply)
        {
            request.gui.getPlayer().sendSystemMessage(Component.empty()
                                                               .append(TradingGui.text(request.gui.getPlayer(), "ui.chat.reply_prefix"))
                                                               .append(Component.literal(input).withStyle(ChatFormatting.WHITE)));
        }
        if (input.isBlank() || input.strip().equalsIgnoreCase("cancel") || input.strip().equals("取消"))
        {
            finish(request, null, true);
            return;
        }
        Runnable continuation;
        try
        {
            continuation = request.parse.apply(input);
        } catch (IllegalArgumentException error)
        {
            request.gui.getPlayer().sendSystemMessage(Component.empty()
                                                               .append(TradingGui.text(request.gui.getPlayer(), "ui.chat.retry_prefix"))
                                                               .append(TradingGui.text(request.gui.getPlayer(), error.getMessage() == null ? "gui.capitalmc.input.retry" : error.getMessage())
                                                                                 .copy().withStyle(ChatFormatting.RED)));
            return;
        }
        finish(request, continuation, true);
    }

    private void finish(Pending request, Runnable action, boolean reopen)
    {
        if (!pending.remove(request.gui.getPlayer().getUUID(), request))
        {
            return;
        }
        try
        {
            if (action != null)
            {
                action.run();
            }
            if (reopen && !request.gui.getPlayer().hasDisconnected()
                && request.gui.getPlayer().containerMenu == request.gui.getPlayer().inventoryMenu)
            {
                request.gui.refresh();
                request.gui.open();
            }
            if (action != null)
            {
                request.success.run();
            }
            else
            {
                request.cancel.run();
            }
        } catch (RuntimeException error)
        {
            request.fail.accept(error);
            CapitalMC.logger.error("GUI continuation failed", error);
            if (reopen && !request.gui.getPlayer().hasDisconnected() && !request.gui.isOpen())
            {
                try
                {
                    request.gui.refresh();
                    request.gui.open();
                } catch (RuntimeException failure)
                {
                    CapitalMC.logger.error("Cannot restore GUI", failure);
                }
            }
        }
    }

    private void fail(Pending request, RuntimeException error)
    {
        finish(request, () -> {
            throw error;
        }, true);
    }

    public void cancel(UUID player)
    {
        Pending request = pending.get(player);
        if (request != null)
        {
            finish(request, null, false);
        }
    }

    public void clear()
    {
        for (UUID player : List.copyOf(pending.keySet()))
            cancel(player);
    }

    public boolean isPaused(UUID player)
    {
        return pending.containsKey(player);
    }

    private static final class Pending
    {
        final TradingGui gui;
        Component prompt;
        boolean privateWait, echoReply;
        BooleanSupplier valid, expired, condition;
        Function<String, Runnable> parse;
        Runnable onReady;
        Runnable success = () -> {
        }, cancel = () -> {
        };
        Consumer<Throwable> fail = error -> {
        };

        Pending(TradingGui gui)
        {
            this.gui = gui;
        }
    }
}
