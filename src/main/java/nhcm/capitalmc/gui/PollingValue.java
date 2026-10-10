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
    private Throwable error;

    public PollingValue(T empty)
    {
        this.empty = empty;
        value = empty;
    }

    public T get(Object context, Supplier<CompletableFuture<T>> query)
    {
        return get(context, 50, query);
    }

    public T get(Object context, long intervalMillis, Supplier<CompletableFuture<T>> query)
    {
        if (!Objects.equals(context, key))
        {
            key = context;
            generation++;
            queried = 0;
            pending = false;
            value = empty;
            error = null;
        }
        if (!pending && (queried == 0 || System.nanoTime() - queried >= Math.max(50, intervalMillis) * 1_000_000L))
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
                    error = failure;
                    if (failure == null)
                    {
                        value = result;
                    }
                }));
            } catch (RuntimeException failure)
            {
                pending = false;
                error = failure;
            }
        }
        return value;
    }

    public Throwable error()
    {
        return error;
    }

    public void invalidate()
    {
        generation++;
        key = null;
        pending = false;
        queried = 0;
        value = empty;
        error = null;
    }
}
