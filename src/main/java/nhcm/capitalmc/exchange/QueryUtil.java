package nhcm.capitalmc.exchange;

import nhcm.advmatch.BusinessException;
import nhcm.advmatch.Exchange;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.CapitalMC;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

class QueryUtil {
    private QueryUtil() {}
    static Exchange exchange() {
        return Objects.requireNonNull(CapitalMC.exchange, "Exchange is not initialized");
    }
    static void positive(long id) {
        if (id <= 0) throw new IllegalArgumentException("ID must be positive");
    }
    static <T> Optional<T> one(ReadResponse<T> response) {
        if (response.page().state() == ReadState.NotFound) return Optional.empty();
        if (response.page().state() != ReadState.Success) throw new BusinessException(response.page());
        if (response.values().size() > 1) throw new IllegalStateException("Expected a single result");
        return response.values().stream().findFirst();
    }
    static <T> CompletableFuture<List<T>> all(Function<ObservationQuery, CompletableFuture<ReadResponse<T>>> read) {
        return page(read, new ObservationQuery().withLimit(256), new ArrayList<>());
    }
    private static <T> CompletableFuture<List<T>> page(
            Function<ObservationQuery, CompletableFuture<ReadResponse<T>>> read,
            ObservationQuery query, List<T> values) {
        try {
            return read.apply(query).thenComposeAsync(response -> {
                if (response.page().state() != ReadState.Success) throw new BusinessException(response.page());
                values.addAll(response.values());
                if (!response.page().hasMore()) return CompletableFuture.completedFuture(List.copyOf(values));
                if (response.page().next() == null || !response.page().next().started()
                        || response.page().next().equals(query.cursor())) {
                    throw new IllegalStateException("Pagination cursor did not advance");
                }
                return page(read, query.withCursor(response.page().next()), values);
            });
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }
}
