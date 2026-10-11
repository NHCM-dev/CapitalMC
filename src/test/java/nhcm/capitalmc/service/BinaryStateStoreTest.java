package nhcm.capitalmc.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BinaryStateStoreTest
{
    @TempDir
    Path directory;

    @Test
    void snapshotsBoundReplayAndRetainExactBinaryValues() throws Exception
    {
        var options = new BinaryStateStore.Options(10, 1024 * 1024, 2);
        var store = new BinaryStateStore(directory, options);
        var state = new JsonObject();
        state.addProperty("integer", Long.MIN_VALUE);
        state.addProperty("decimal", new BigDecimal("12345678901234567890.000000000001"));
        state.addProperty("string", "中文".repeat(40000));
        for (int i = 1; i <= 103; i++)
        {
            state.addProperty("sequence", i);
            store.replace(state.deepCopy());
        }
        var recovered = new BinaryStateStore(directory, options);
        assertEquals(103, recovered.sequence());
        assertEquals(3, recovered.replayedRecords());
        assertEquals(state, recovered.snapshot());
        try (var files = Files.list(directory.resolve("snapshots")))
        {
            assertEquals(2, files.count());
        }
        assertFalse(Files.readString(directory.resolve("snapshot.head"), StandardCharsets.ISO_8859_1).startsWith("{"));
    }

    @Test
    void missingRequiredSnapshotCannotRollBackToOlderOne() throws Exception
    {
        var options = new BinaryStateStore.Options(1, 1024, 2);
        var store = new BinaryStateStore(directory, options);
        var state = new JsonObject();
        state.addProperty("value", 1);
        store.replace(state.deepCopy());
        state.addProperty("value", 2);
        store.replace(state.deepCopy());
        Files.delete(directory.resolve("snapshots/snapshot-00000000000000000002.bin"));
        assertThrows(IOException.class, () -> new BinaryStateStore(directory, options));
    }

    @Test
    void corruptedLengthFailsRatherThanBeingDiscardedAsIncompleteTail() throws Exception
    {
        var options = BinaryStateStore.Options.defaults();
        var store = new BinaryStateStore(directory, options);
        var state = new JsonObject();
        state.addProperty("value", 1);
        store.replace(state);
        Path wal = directory.resolve("journal.wal");
        byte[] bytes = Files.readAllBytes(wal);
        bytes[19] ^= 1;
        Files.write(wal, bytes);
        assertThrows(IOException.class, () -> new BinaryStateStore(directory, options));
        assertArrayEquals(bytes, Files.readAllBytes(wal));
    }

    @Test
    void missingManifestCannotSelectAnEmptyStore() throws Exception
    {
        var options = BinaryStateStore.Options.defaults();
        var store = new BinaryStateStore(directory, options);
        var value = new JsonObject();
        value.addProperty("balance", 100);
        store.replace(value);
        Files.delete(directory.resolve("snapshot.head"));
        assertThrows(IOException.class, () -> new BinaryStateStore(directory, options));
    }

    @Test
    void changesPreserveSingleUnitsAboveDoublePrecisionAfterRecovery() throws Exception
    {
        var options = BinaryStateStore.Options.defaults();
        var store = new BinaryStateStore(directory, options);
        var value = new JsonObject();
        value.addProperty("request", 4_000_000_000_000_000_000L);
        value.addProperty("quantity", new BigDecimal("12345678901234567890.000000000001"));
        store.replace(value);
        var recovered = new BinaryStateStore(directory, options);
        value.addProperty("request", 4_000_000_000_000_000_001L);
        value.addProperty("quantity", new BigDecimal("12345678901234567890.000000000002"));
        recovered.replace(value);
        var again = new BinaryStateStore(directory, options);
        assertEquals(2, again.sequence());
        assertEquals(4_000_000_000_000_000_001L, again.snapshot().get("request").getAsLong());
        assertEquals(value.get("quantity").getAsBigDecimal(), again.snapshot().get("quantity").getAsBigDecimal());
    }

    @Test
    void locationPinPreventsConfigurationChangeFromCreatingAnEmptyStore() throws Exception
    {
        Path legacy = directory.resolve("services.json");
        Path original = PersistenceSettings.storeDirectory(legacy);
        var settings = PersistenceSettings.load(directory);
        settings.directory = "somewhere-else";
        Files.writeString(directory.resolve("persistence.json"), new Gson().toJson(settings));
        assertEquals(original, PersistenceSettings.storeDirectory(legacy));
    }
}
