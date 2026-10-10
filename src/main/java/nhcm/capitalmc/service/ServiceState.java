package nhcm.capitalmc.service;

import com.google.gson.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * CapitalMC metadata only. Durable patch journal avoids rewriting growing audit records for every phase.
 */
public class ServiceState
{
    private static final Gson JSON = new Gson();
    private final Path file, journal;
    private volatile JsonObject data;
    private long sequence;
    private int sinceCheckpoint;
    private boolean faulted;

    public ServiceState(Path file)
    {
        this.file = file.toAbsolutePath();
        journal = this.file.resolveSibling(this.file.getFileName() + ".wal");
        try
        {
            data = Files.exists(this.file) ? JsonParser.parseString(Files.readString(this.file)).getAsJsonObject() : new JsonObject();
            sequence = data.has("_stateSequence") ? data.remove("_stateSequence").getAsLong() : 0;
            recover();
        } catch (IOException error)
        {
            throw new UncheckedIOException(error);
        }
    }

    public JsonObject snapshot()
    {
        return data.deepCopy();
    }

    public record Entries(int total, List<Map.Entry<String, JsonElement>> values)
    {
    }

    /**
     * Immutable publication makes bounded audit browsing independent of journal size/copy cost.
     */
    public Entries entries(String section, int offset, int limit, boolean newestFirst)
    {
        return entries(section, offset, limit, newestFirst, "");
    }

    public Entries entries(String section, int offset, int limit, boolean newestFirst, String search)
    {
        if (offset < 0 || limit < 1 || limit > 256)
        {
            throw new IllegalArgumentException("Invalid audit page");
        }
        var root = data;
        var object = root.has(section) ? root.getAsJsonObject(section) : new JsonObject();
        String term = search.toLowerCase(Locale.ROOT);
        var matching = term.isEmpty() ? object.entrySet() : object.entrySet().stream()
                                                                  .filter(entry -> (entry.getKey() + " " + entry.getValue()).toLowerCase(Locale.ROOT).contains(term)).toList();
        int total = matching.size(), start = newestFirst ? Math.max(0, total - offset - limit) : offset, end = newestFirst ? Math.max(0, total - offset) : Math.min(total, offset + limit);
        var values = new ArrayList<Map.Entry<String, JsonElement>>();
        int index = 0;
        for (var entry : matching)
        {
            if (index >= end)
            {
                break;
            }
            if (index++ >= start)
            {
                values.add(Map.entry(entry.getKey(), entry.getValue().deepCopy()));
            }
        }
        if (newestFirst)
        {
            Collections.reverse(values);
        }
        return new Entries(total, List.copyOf(values));
    }

    public JsonObject section(String key)
    {
        JsonElement value = data.get(key);
        JsonObject result = new JsonObject();
        if (value != null)
        {
            result.add(key, value.deepCopy());
        }
        return result;
    }

    public CompletableFuture<Void> updateAsync(Consumer<JsonObject> edit)
    {
        return ApplicationExecutors.io(() -> {
            update(edit);
            return null;
        });
    }

    /**
     * Blocking compatibility API; application callers use updateAsync or the metadata executor.
     */
    public synchronized void update(Consumer<JsonObject> edit)
    {
        if (faulted)
        {
            throw new IllegalStateException("Metadata persistence fault; restart and reconciliation required");
        }
        JsonObject next = data.deepCopy();
        edit.accept(next);
        if (next.has("_stateSequence"))
        {
            throw new IllegalArgumentException("Reserved metadata field");
        }
        JsonArray changes = new JsonArray();
        diff(data, next, new JsonArray(), changes);
        if (changes.isEmpty())
        {
            return;
        }
        long nextSequence = Math.addExact(sequence, 1);
        JsonObject payload = new JsonObject();
        payload.addProperty("sequence", nextSequence);
        payload.add("changes", changes);
        String body = JSON.toJson(payload);
        JsonObject record = new JsonObject();
        record.addProperty("payload", body);
        record.addProperty("crc", checksum(body));
        byte[] bytes = (JSON.toJson(record) + "\n").getBytes(StandardCharsets.UTF_8);
        try
        {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && !Files.isRegularFile(file))
            {
                throw new IOException("Metadata checkpoint is not a regular file");
            }
            if (!Files.exists(file))
            {
                checkpoint();
            }
            try (var channel = FileChannel.open(journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND))
            {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining())
                    channel.write(buffer);
                channel.force(true);
            }
            data = next;
            sequence = nextSequence;
            sinceCheckpoint++;
            if (sinceCheckpoint >= 128 || Files.size(journal) >= 4 * 1024 * 1024)
            {
                checkpoint();
            }
        } catch (IOException error)
        {
            faulted = true;
            throw new UncheckedIOException("Metadata write result may be uncertain; further writes blocked", error);
        }
    }

    public synchronized long nextRequest()
    {
        long next = Math.addExact(data.has("request") ? data.get("request").getAsLong() : Math.multiplyExact(System.currentTimeMillis(), 1000), 1);
        update(root -> root.addProperty("request", next));
        return next;
    }

    private void recover() throws IOException
    {
        if (!Files.exists(journal))
        {
            return;
        }
        byte[] bytes = Files.readAllBytes(journal);
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] != '\n')
            end--;
        // Only a physically incomplete final record is discarded. Complete corrupt records fail startup.
        if (end != bytes.length)
        {
            try (var channel = FileChannel.open(journal, StandardOpenOption.WRITE))
            {
                channel.truncate(end);
                channel.force(true);
            }
        }
        try (var reader = new BufferedReader(new StringReader(new String(bytes, 0, end, StandardCharsets.UTF_8))))
        {
            for (String line; (line = reader.readLine()) != null; )
            {
                JsonObject record = JsonParser.parseString(line).getAsJsonObject();
                String body = record.get("payload").getAsString();
                if (record.get("crc").getAsLong() != checksum(body))
                {
                    throw new IllegalStateException("Metadata journal checksum mismatch");
                }
                JsonObject payload = JsonParser.parseString(body).getAsJsonObject();
                long id = payload.get("sequence").getAsLong();
                if (id <= sequence)
                {
                    continue; // old journal survived a checkpoint rename
                }
                if (id != Math.addExact(sequence, 1))
                {
                    throw new IllegalStateException("Metadata journal sequence gap");
                }
                for (JsonElement change : payload.getAsJsonArray("changes"))
                    apply(data, change.getAsJsonObject());
                sequence = id;
                sinceCheckpoint++;
            }
        }
    }

    private synchronized void checkpoint() throws IOException
    {
        JsonObject saved = data.deepCopy();
        saved.addProperty("_stateSequence", sequence);
        Path temporary = Files.createTempFile(file.getParent(), "capital-", ".tmp");
        try
        {
            Files.writeString(temporary, JSON.toJson(saved), StandardCharsets.UTF_8);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
            {
                channel.force(true);
            }
            try
            {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored)
            {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            try (var channel = FileChannel.open(journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE))
            {
                channel.truncate(0);
                channel.force(true);
            }
            sinceCheckpoint = 0;
        } finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    private static long checksum(String value)
    {
        CRC32 crc = new CRC32();
        crc.update(value.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    private static void diff(JsonElement before, JsonElement after, JsonArray path, JsonArray changes)
    {
        if (Objects.equals(before, after))
        {
            return;
        }
        if (before != null && after != null && before.isJsonObject() && after.isJsonObject())
        {
            Set<String> keys = new LinkedHashSet<>(before.getAsJsonObject().keySet());
            keys.addAll(after.getAsJsonObject().keySet());
            for (String key : keys)
            {
                JsonArray child = path.deepCopy();
                child.add(key);
                diff(before.getAsJsonObject().get(key), after.getAsJsonObject().get(key), child, changes);
            }
        }
        else
        {
            JsonObject change = new JsonObject();
            change.add("path", path);
            change.addProperty("remove", after == null);
            if (after != null)
            {
                change.add("value", after.deepCopy());
            }
            changes.add(change);
        }
    }

    private static void apply(JsonObject root, JsonObject change)
    {
        JsonArray path = change.getAsJsonArray("path");
        if (path.isEmpty())
        {
            throw new IllegalStateException("Invalid metadata patch");
        }
        JsonObject target = root;
        for (int i = 0; i < path.size() - 1; i++)
            target = target.getAsJsonObject(path.get(i).getAsString());
        String key = path.get(path.size() - 1).getAsString();
        if (change.get("remove").getAsBoolean())
        {
            target.remove(key);
        }
        else
        {
            target.add(key, change.get("value").deepCopy());
        }
    }
}
