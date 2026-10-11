package nhcm.capitalmc.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ServiceStateTest
{
    @Test
    void groupedDiskFailureDoesNotPublishOrAcknowledgeAnyEdit() throws Exception
    {
        Path file = directory.resolve("group-fault.json");
        var state = new ServiceState(file);
        state.update(root -> root.addProperty("value", 1));
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep"), "Evidence");
        var writes = new ArrayList<CompletableFuture<Void>>();
        for (int index = 0; index < 10; index++)
        {
            int id = index;
            writes.add(state.updateEntryAsync("commands", Integer.toString(id), entry -> entry.addProperty("status", "PENDING")));
        }
        for (var write : writes) assertThrows(Exception.class, () -> write.get(5, TimeUnit.SECONDS));
        assertFalse(state.snapshot().has("commands"));
        assertEquals(1, state.snapshot().get("value").getAsInt());
        assertTrue(state.metrics().faulted());
        assertThrows(Exception.class, () -> state.updateAsync(root -> root.addProperty("value", 2)).get(5, TimeUnit.SECONDS));
    }

    @Test
    void entryGroupsAcknowledgeAfterDurabilityAndIsolateFailedEdits() throws Exception
    {
        var state = new ServiceState(directory.resolve("group.json"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ApplicationExecutors.IO.execute(() -> {
            entered.countDown();
            try
            {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out");
            }
            catch (InterruptedException failure)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var writes = new ArrayList<CompletableFuture<Void>>();
        CompletableFuture<Void> rejected;
        try
        {
            for (int index = 0; index < 100; index++)
            {
                int value = index;
                writes.add(state.updateEntryAsync("commands", Integer.toString(value), row -> row.addProperty("value", value)));
            }
            rejected = state.updateEntryAsync("commands", "bad", row -> {
                row.addProperty("value", 999);
                throw new IllegalArgumentException("Invalid edit");
            });
            assertTrue(writes.stream().noneMatch(CompletableFuture::isDone));
            assertFalse(writes.getFirst().cancel(false));
        }
        finally
        {
            release.countDown();
        }
        CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        assertThrows(Exception.class, () -> rejected.get(5, TimeUnit.SECONDS));
        var recovered = new ServiceState(directory.resolve("group.json"));
        assertEquals(100, recovered.snapshot().getAsJsonObject("commands").size());
        assertFalse(recovered.snapshot().getAsJsonObject("commands").has("bad"));
        assertEquals(100, state.metrics().edits());
        assertEquals(2, state.metrics().groups());
        var image = state.snapshot();
        state.updateEntryAsync("commands", "1", row -> row.addProperty("value", 500)).join();
        assertEquals(1, image.getAsJsonObject("commands").getAsJsonObject("1").get("value").getAsInt());
        assertEquals(500, new ServiceState(directory.resolve("group.json")).snapshot().getAsJsonObject("commands")
                                                                           .getAsJsonObject("1").get("value").getAsInt());
    }

    @TempDir
    Path directory;

    @Test
    void requestsSurviveRestartAndSnapshotsCannotChangeStore()
    {
        Path file = directory.resolve("state.json");
        var state = new ServiceState(file);
        long first = state.nextRequest();
        var snapshot = state.snapshot();
        snapshot.addProperty("request", 0);
        assertEquals(first + 1, new ServiceState(file).nextRequest());
    }

    @Test
    void failedEditDoesNotCommitPartialState()
    {
        var state = new ServiceState(directory.resolve("state.json"));
        state.update(root -> root.addProperty("balance", 2));
        assertThrows(IllegalStateException.class, () -> state.update(root -> {
            root.addProperty("balance", 100);
            throw new IllegalStateException();
        }));
        assertEquals(2, state.snapshot().get("balance").getAsInt());
        assertEquals(2, new ServiceState(directory.resolve("state.json")).snapshot().get("balance").getAsInt());
    }

    @Test
    void failedDiskWriteDoesNotChangeMemory() throws Exception
    {
        Path path = directory.resolve("state.json");
        var state = new ServiceState(path);
        state.update(root -> root.addProperty("value", 3));
        Files.delete(path);
        Files.createDirectory(path);
        Files.writeString(path.resolve("keep"), "x");
        assertThrows(UncheckedIOException.class, () -> state.update(root -> root.addProperty("value", 4)));
        assertEquals(3, state.snapshot().get("value").getAsInt());
    }

    @Test
    void oldJsonMigratesAndIncrementalPatchesSurviveRestart() throws Exception
    {
        Path file = directory.resolve("legacy.json");
        Files.writeString(file, "{\"containerCredits\":{\"owner:CHEST\":2}}");
        var state = new ServiceState(file);
        state.updateAsync(root -> root.getAsJsonObject("containerCredits").addProperty("owner:CHEST", 3)).join();
        state.updateAsync(root -> root.addProperty("request", 120)).join();
        assertEquals(3, new ServiceState(file).snapshot().getAsJsonObject("containerCredits").get("owner:CHEST").getAsInt());
        assertEquals(120, new ServiceState(file).snapshot().get("request").getAsLong());
        assertEquals(2, JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("containerCredits").get("owner:CHEST").getAsInt());
    }

    @Test
    void incompleteTailIsTruncatedBeforeAppendingNewRecords() throws Exception
    {
        Path file = directory.resolve("state.json"), wal = directory.resolve("state.json.wal");
        var state = new ServiceState(file);
        state.update(root -> root.addProperty("value", 8));
        Files.writeString(wal, "{broken", StandardOpenOption.APPEND);
        var recovered = new ServiceState(file);
        assertEquals(8, recovered.snapshot().get("value").getAsInt());
        recovered.update(root -> root.addProperty("value", 9));
        assertEquals(9, new ServiceState(file).snapshot().get("value").getAsInt());
    }

    @Test
    void completeCorruptRecordFailsRecoveryInsteadOfSilentlyLosingState() throws Exception
    {
        Path file = directory.resolve("state.json"), wal = directory.resolve("state.json.wal");
        var state = new ServiceState(file);
        state.update(root -> root.addProperty("value", 8));
        String record = Files.readString(wal);
        Files.writeString(wal, record.replace("value", "other"));
        assertThrows(IllegalStateException.class, () -> new ServiceState(file));
    }

    @Test
    void checkpointSkipsAnOldJournalThatSurvivedRename() throws Exception
    {
        Path file = directory.resolve("state.json"), wal = directory.resolve("state.json.wal");
        var state = new ServiceState(file);
        state.update(root -> root.addProperty("value", 1));
        String old = Files.readString(wal);
        for (int value = 2; value <= 128; value++)
        {
            int next = value;
            state.update(root -> root.addProperty("value", next));
        }
        assertEquals(0, Files.size(wal));
        Files.writeString(wal, old);
        var recovered = new ServiceState(file);
        assertEquals(128, recovered.snapshot().get("value").getAsInt());
        recovered.update(root -> root.addProperty("value", 129));
        assertEquals(129, new ServiceState(file).snapshot().get("value").getAsInt());
    }

    @Test
    void journalDeletionAndNestedUpdatesDoNotResurrectRemovedFields()
    {
        Path file = directory.resolve("state.json");
        var state = new ServiceState(file);
        state.update(root -> {
            var nested = new JsonObject();
            nested.addProperty("old", 1);
            root.add("nested", nested);
        });
        state.update(root -> {
            root.getAsJsonObject("nested").remove("old");
            root.getAsJsonObject("nested").addProperty("new", 2);
        });
        var restored = new ServiceState(file).snapshot().getAsJsonObject("nested");
        assertFalse(restored.has("old"));
        assertEquals(2, restored.get("new").getAsInt());
    }

    @Test
    void boundedAuditPagesHaveStableNewestOrderAndCannotMutatePublishedState()
    {
        var state = new ServiceState(directory.resolve("state.json"));
        state.update(root -> {
            var records = new JsonObject();
            for (int i = 0; i < 35; i++)
            {
                var value = new JsonObject();
                value.addProperty("phase", "COMPLETE");
                records.add(Integer.toString(i), value);
            }
            root.add("commands", records);
        });
        var first = state.entries("commands", 0, 27, true);
        var second = state.entries("commands", 27, 27, true);
        assertEquals(35, first.total());
        assertEquals(27, first.values().size());
        assertEquals("34", first.values().getFirst().getKey());
        assertEquals(8, second.values().size());
        assertEquals("7", second.values().getFirst().getKey());
        first.values().getFirst().getValue().getAsJsonObject().addProperty("phase", "BROKEN");
        assertEquals("COMPLETE", state.entries("commands", 0, 1, true).values().getFirst().getValue().getAsJsonObject().get("phase").getAsString());
    }

    @Test
    void searchFiltersBeforePagingAndCountsAllMatchingRecords()
    {
        var state = new ServiceState(directory.resolve("state.json"));
        state.update(root -> {
            var records = new JsonObject();
            for (int i = 0; i < 60; i++)
            {
                var value = new JsonObject();
                value.addProperty("phase", i % 2 == 0 ? "REVIEW" : "COMPLETE");
                records.add("request-" + i, value);
            }
            root.add("commands", records);
        });
        var first = state.entries("commands", 0, 27, true, "review");
        var second = state.entries("commands", 27, 27, true, "REVIEW");
        assertEquals(30, first.total());
        assertEquals("request-58", first.values().getFirst().getKey());
        assertEquals(3, second.values().size());
        assertEquals("request-4", second.values().getFirst().getKey());
        assertEquals(1, state.entries("commands", 0, 27, true, "request-59").total());
    }
}
