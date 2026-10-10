package nhcm.capitalmc.exchange;

import nhcm.advmatch.BusinessException;
import nhcm.advmatch.Exchange;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.service.ApplicationExecutors;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

class QueryUtil
{
    private record Shared(Exchange owner, long at, CompletableFuture<?> result)
    {
    }

    private static final Map<String, Shared> SHARED = new HashMap<>();

    @SuppressWarnings("unchecked")
    static synchronized <T> CompletableFuture<T> shared(String key, long lifetimeNanos, Supplier<CompletableFuture<T>> read)
    {
        var previous = SHARED.get(key);
        var owner = exchange();
        long now = System.nanoTime();
        if (previous != null && previous.owner == owner && (!previous.result.isDone()
                                                            || !previous.result.isCompletedExceptionally() && now - previous.at < lifetimeNanos))
        {
            return ((CompletableFuture<T>) previous.result).copy();
        }
        if (SHARED.size() >= 256)
        {
            SHARED.entrySet().removeIf(entry -> entry.getValue().owner != owner
                                                || entry.getValue().result.isDone() && now - entry.getValue().at > 5_000_000_000L);
        }
        var result = read.get();
        if (SHARED.size() < 512 || SHARED.containsKey(key))
        {
            SHARED.put(key, new Shared(owner, now, result));
        }
        return result.copy();
    }

    private QueryUtil()
    {
    }

    static Exchange exchange()
    {
        return Objects.requireNonNull(CapitalMC.exchange, "Exchange is not initialized");
    }

    static void positive(long id)
    {
        if (id <= 0)
        {
            throw new IllegalArgumentException("ID must be positive");
        }
    }

    static <T> Optional<T> one(ReadResponse<T> response)
    {
        if (response.page().state() == ReadState.NotFound)
        {
            return Optional.empty();
        }
        if (response.page().state() != ReadState.Success)
        {
            throw new BusinessException(response.page());
        }
        if (response.values().size() > 1)
        {
            throw new IllegalStateException("Expected a single result");
        }
        return response.values().stream().findFirst();
    }

    static <T> CompletableFuture<List<T>> all(Function<ObservationQuery, CompletableFuture<ReadResponse<T>>> read)
    {
        return page(read, new ObservationQuery().withLimit(256), new ArrayList<>(), 5);
    }

    private static <T> CompletableFuture<List<T>> page(
            Function<ObservationQuery, CompletableFuture<ReadResponse<T>>> read,
            ObservationQuery query, List<T> values, int retries)
    {
        try
        {
            return read.apply(query).thenComposeAsync(response -> {
                if (response.page().state() == ReadState.CursorExpired && retries > 0)
                {
                    // Restart the complete read so rows from different revisions are never combined.
                    var delay = CompletableFuture.delayedExecutor(25L << (5 - retries), TimeUnit.MILLISECONDS,
                                                                  ApplicationExecutors.CALLBACKS);
                    return CompletableFuture.supplyAsync(() -> query.withCursor(new ReadCursor()), delay)
                                            .thenCompose(fresh -> page(read, fresh, new ArrayList<>(), retries - 1));
                }
                if (response.page().state() != ReadState.Success)
                {
                    throw new BusinessException(response.page());
                }
                values.addAll(response.values());
                if (!response.page().hasMore())
                {
                    return CompletableFuture.completedFuture(List.copyOf(values));
                }
                if (response.page().next() == null || !response.page().next().started()
                    || response.page().next().equals(query.cursor()))
                {
                    throw new IllegalStateException("Pagination cursor did not advance");
                }
                return page(read, query.withCursor(response.page().next()), values, retries);
            }, ApplicationExecutors.CALLBACKS);
        } catch (RuntimeException error)
        {
            return CompletableFuture.failedFuture(error);
        }
    }
}
