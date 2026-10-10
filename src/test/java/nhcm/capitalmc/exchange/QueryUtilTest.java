package nhcm.capitalmc.exchange;

import nhcm.advmatch.BusinessException;
import nhcm.advmatch.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class QueryUtilTest
{
    @Test
    void expiredCursorRestartsWithoutKeepingPartialRows()
    {
        var calls = new AtomicInteger();
        var result = QueryUtil.<String>all(query -> {
            int call = calls.incrementAndGet();
            if (call == 1)
            {
                var next = new ReadCursor(1, 1, 1, 1, 0, new ReadIdentity(), true);
                return CompletableFuture.completedFuture(new ReadResponse<>(query,
                        new ReadPage(ReadState.Success, new ReadStamp(), next, 1, 1, true), List.of("old")));
            }
            if (call == 2)
            {
                assertTrue(query.cursor().started());
                return CompletableFuture.completedFuture(new ReadResponse<>(query,
                        new ReadPage(ReadState.CursorExpired, new ReadStamp(), new ReadCursor(), 0, 0, false), List.of()));
            }
            assertFalse(query.cursor().started());
            return CompletableFuture.completedFuture(new ReadResponse<>(query, new ReadPage(), List.of("fresh")));
        }).join();
        assertEquals(List.of("fresh"), result);
        assertEquals(3, calls.get());
    }

    @Test
    void repeatedExpiryIsBoundedAndStillFails()
    {
        var calls = new AtomicInteger();
        var result = QueryUtil.<String>all(query -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new ReadResponse<>(query,
                    new ReadPage(ReadState.CursorExpired, new ReadStamp(), new ReadCursor(), 0, 0, false), List.of()));
        });
        assertInstanceOf(BusinessException.class, assertThrows(CompletionException.class, result::join).getCause());
        assertEquals(6, calls.get());
    }

    @Test
    void otherReadFailuresAreNotRetried()
    {
        var calls = new AtomicInteger();
        var result = QueryUtil.<String>all(query -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new ReadResponse<>(query,
                    new ReadPage(ReadState.SourceFaulted, new ReadStamp(), new ReadCursor(), 0, 0, false), List.of()));
        });
        assertInstanceOf(BusinessException.class, assertThrows(CompletionException.class, result::join).getCause());
        assertEquals(1, calls.get());
    }
}
