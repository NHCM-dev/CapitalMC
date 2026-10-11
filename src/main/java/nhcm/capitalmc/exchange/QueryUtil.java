package nhcm.capitalmc.exchange;

import nhcm.advmatch.BusinessException;
import nhcm.advmatch.Exchange;
import nhcm.advmatch.ObservationSubscription;
import nhcm.advmatch.SubscriptionSnapshot;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.service.ApplicationExecutors;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;

class QueryUtil
{
    private record Shared(Exchange owner, long at, CompletableFuture<?> result)
    {
    }

    private static final Map<String, Shared> SHARED = new HashMap<>();
    private record LiveKey(Exchange owner, ObservationQuery query) {}
    private static class Live
    {
        ObservationSubscription subscription;
        long used = System.nanoTime();
    }
    private static final Map<LiveKey, Live> LIVE = new HashMap<>();

    static
    {
        var cleanup = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("capitalmc-subscription-cleanup").factory());
        cleanup.scheduleWithFixedDelay(QueryUtil::expire, 1, 1, TimeUnit.SECONDS);
    }

    private static synchronized void expire()
    {
        long now = System.nanoTime();
        LIVE.entrySet().removeIf(entry -> {
            if (entry.getKey().owner != CapitalMC.exchange || now - entry.getValue().used > 10_000_000_000L)
            {
                entry.getValue().subscription.close();
                return true;
            }
            return false;
        });
    }

    static synchronized CompletableFuture<ObservationResult> live(Exchange owner, ObservationQuery query)
    {
        var key = new LiveKey(owner, query);
        var feed = LIVE.get(key);
        if (feed == null)
        {
            expire();
            if (LIVE.size() >= 1024) return CompletableFuture.failedFuture(new IllegalStateException("ui.exchange.subscription_limit"));
            feed = new Live();
            feed.subscription = owner.subscriptions().subscribe(query, Duration.ofMillis(250), image -> {});
            LIVE.put(key, feed);
        }
        feed.used = System.nanoTime();
        var image = feed.subscription.latest();
        return image == null ? feed.subscription.first().thenApply(QueryUtil::liveResult)
                             : CompletableFuture.completedFuture(image).thenApply(QueryUtil::liveResult);
    }

    private static ObservationResult liveResult(SubscriptionSnapshot image)
    {
        if (image.state() != SubscriptionSnapshot.State.LIVE)
        {
            throw new IllegalStateException("ui.exchange.connection_stale", image.error());
        }
        return image.result();
    }

    static <T> CompletableFuture<ReadResponse<T>> liveRows(ObservationQuery query, Class<T> type)
    {
        return live(exchange(), query).thenApply(image -> image.typed(type));
    }

    /** Keep complete list semantics: oversized snapshots use the existing cursor reader. */
    static <T> CompletableFuture<List<T>> liveList(ObservationQuery query, Class<T> type,
                                                   Function<ObservationQuery, CompletableFuture<ReadResponse<T>>> pages)
    {
        return liveRows(query.withLimit(1024), type).thenCompose(response -> {
            if (response.page().state() != ReadState.Success) throw new BusinessException(response.page());
            return response.page().hasMore() ? shared("overflow:" + query, 1_000_000_000L, () -> all(pages))
                                            : CompletableFuture.completedFuture(response.values());
        });
    }

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
