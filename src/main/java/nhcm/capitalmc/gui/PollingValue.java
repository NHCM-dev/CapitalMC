package nhcm.capitalmc.gui;

import nhcm.capitalmc.service.GameServices;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Main-thread view cache; stale async responses never overwrite a new account or page context.
 */
public class PollingValue<T>
{
    private final T empty;
    private T value;
    private Object key;
    private long generation, queried;
    private boolean pending;
    private boolean loaded;
    private Throwable error;
    private Supplier<CompletableFuture<T>> query;
    private long intervalMillis;
    private TradingGui owner;

    public void poll()
    {
        if (query != null) get(key, intervalMillis, query);
    }

    public PollingValue(T empty)
    {
        this.empty = empty;
        value = empty;
    }

    public T get(Object context, Supplier<CompletableFuture<T>> query)
    {
        return get(context, 250, query);
    }

    public T get(Object context, long intervalMillis, Supplier<CompletableFuture<T>> query)
    {
        var rendering = TradingGui.rendering();
        if (rendering != null)
        {
            owner = rendering;
            owner.track(this);
        }
        this.query = query;
        this.intervalMillis = intervalMillis;
        if (!Objects.equals(context, key))
        {
            key = context;
            generation++;
            queried = 0;
            pending = false;
            value = empty;
            loaded = false;
            error = null;
        }
        if (!pending && (queried == 0 || System.nanoTime() - queried >= Math.max(100, intervalMillis) * 1_000_000L))
        {
            pending = true;
            queried = System.nanoTime();
            long request = generation;
            try
            {
                query.get().whenComplete((result, failure) -> GameServices.get().server.execute(() -> {
                    if (generation != request)
                    {
                        return;
                    }
                    pending = false;
                    boolean changed = !loaded || !Objects.equals(value, result) || !Objects.equals(error, failure);
                    error = failure;
                    if (failure == null)
                    {
                        value = result;
                        loaded = true;
                    }
                    if (changed && owner != null) owner.invalidate(this);
                }));
            } catch (RuntimeException failure)
            {
                pending = false;
                error = failure;
                if (owner != null) owner.invalidate(this);
            }
        }
        return value;
    }

    public Throwable error()
    {
        return error;
    }

    public boolean loaded()
    {
        return loaded;
    }

    public void invalidate()
    {
        if (owner != null)
        {
            owner.invalidate(this);
        }
        generation++;
        key = null;
        pending = false;
        queried = 0;
        value = empty;
        loaded = false;
        error = null;
    }
}
