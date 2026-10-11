package nhcm.capitalmc.service;

import com.google.gson.*;
import nhcm.capitalmc.backend.SharedMetadata;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.CRC32;

/**
 * CapitalMC metadata only. Durable patch journal avoids rewriting growing audit records for every phase.
 */
public class ServiceState
{
    private final Path file, journal;
    private final BinaryStateStore binary;
    private volatile JsonObject data;
    private volatile long sequence;
    private boolean faulted;
    private volatile SharedMetadata shared;
    private JsonObject durableData;
    private volatile long sharedGeneration;
    private record PendingEdit(Consumer<JsonObject> edit, CompletableFuture<Void> result, String section, String key) {}
    private final ArrayDeque<PendingEdit> pendingEdits = new ArrayDeque<>();
    private boolean drainScheduled;
    private long committedGroups, committedEdits;

    public record Metrics(long groups, long edits, int queued, boolean faulted) {}

    public synchronized Metrics metrics()
    {
        synchronized (pendingEdits)
        {
            return new Metrics(committedGroups, committedEdits, pendingEdits.size(), faulted);
        }
    }

    public CompletableFuture<Void> useShared(SharedMetadata shared)
    {
        this.shared = Objects.requireNonNull(shared);
        return refreshShared().thenApply(changed -> null);
    }

    public CompletableFuture<Boolean> refreshShared()
    {
        if (shared == null) return CompletableFuture.completedFuture(false);
        long expected = sharedGeneration;
        return shared.read().thenApplyAsync(image -> {
            synchronized (this)
            {
                // A delayed read must not overwrite a commit completed after that read began.
                return sharedGeneration == expected && publishShared(image);
            }
        }, ApplicationExecutors.IO);
    }

    private synchronized boolean publishShared(JsonObject image)
    {
        if (BinaryValues.sameValues(SharedMetadata.select(data), image)) return false;
        var next = data.deepCopy();
        overlay(next, image);
        data = next;
        sharedGeneration++;
        return true;
    }

    private static void overlay(JsonObject target, JsonObject source)
    {
        for (String field : SharedMetadata.FIELDS)
        {
            target.remove(field);
            if (source.has(field)) target.add(field, source.get(field).deepCopy());
        }
    }

    public ServiceState(Path file)
    {
        this.file = file.toAbsolutePath();
        journal = this.file.resolveSibling(this.file.getFileName() + ".wal");
        try
        {
            binary = new BinaryStateStore(PersistenceSettings.storeDirectory(this.file), PersistenceSettings.load(this.file.getParent()).metadataOptions());
            if (!binary.initialized() && (Files.exists(this.file) || Files.exists(journal)))
            {
                data = Files.exists(this.file) ? JsonParser.parseString(Files.readString(this.file)).getAsJsonObject() : new JsonObject();
                sequence = data.has("_stateSequence") ? data.remove("_stateSequence").getAsLong() : 0;
                recover(); // Read-only legacy import, including its adjacent WAL.
                binary.importState(data, sequence);
            }
            data = binary.snapshot();
            sequence = binary.sequence();
            durableData = data;
        }
        catch (IOException error)
        {
            throw new UncheckedIOException(error);
        }
    }

    public JsonObject snapshot()
    {
        return data.deepCopy();
    }

    public long revision()
    {
        return sequence;
    }

    public long sharedRevision()
    {
        return sharedGeneration;
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
        return enqueue(edit, null, null);
    }

    /** Copy only the edited record; existing records remain immutable and shared. */
    public CompletableFuture<Void> updateEntryAsync(String section, String key, Consumer<JsonObject> edit)
    {
        if (SharedMetadata.FIELDS.contains(section) || section.equals("_stateSequence"))
        {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Entry updates require a local metadata section"));
        }
        return enqueue(edit, Objects.requireNonNull(section), Objects.requireNonNull(key));
    }

    private CompletableFuture<Void> enqueue(Consumer<JsonObject> edit, String section, String key)
    {
        var result = new CompletableFuture<Void>()
        {
            @Override
            public boolean cancel(boolean interrupt)
            {
                return false;
            }
        };
        synchronized (pendingEdits)
        {
            if (pendingEdits.size() >= 4096)
            {
                return CompletableFuture.failedFuture(new RejectedExecutionException("Metadata write queue is full"));
            }
            pendingEdits.addLast(new PendingEdit(edit, result, section, key));
            if (!drainScheduled)
            {
                drainScheduled = true;
                try
                {
                    ApplicationExecutors.IO.execute(this::drainEdits);
                }
                catch (RuntimeException failure)
                {
                    pendingEdits.removeLast();
                    drainScheduled = false;
                    result.completeExceptionally(failure);
                }
            }
        }
        return result;
    }

    private void drainEdits()
    {
        var group = new ArrayList<PendingEdit>();
        synchronized (pendingEdits)
        {
            while (group.size() < 64 && !pendingEdits.isEmpty()) group.add(pendingEdits.removeFirst());
        }
        commitGroup(group);
        var rejected = new ArrayList<PendingEdit>();
        RuntimeException rejection = null;
        synchronized (pendingEdits)
        {
            if (pendingEdits.isEmpty())
            {
                drainScheduled = false;
            }
            else
            {
                try
                {
                    // Yield the shared IO executor between groups so one gateway's
                    // history cannot starve another gateway's durable preparation.
                    ApplicationExecutors.IO.execute(this::drainEdits);
                }
                catch (RuntimeException failure)
                {
                    rejection = failure;
                    rejected.addAll(pendingEdits);
                    pendingEdits.clear();
                    drainScheduled = false;
                }
            }
        }
        for (var request : rejected) request.result().completeExceptionally(rejection);
    }

    private void commitGroup(List<PendingEdit> group)
    {
        var accepted = new ArrayList<PendingEdit>();
        var rejected = new HashMap<PendingEdit, Throwable>();
        try
        {
            synchronized (this)
            {
                if (faulted) throw new IllegalStateException("Metadata persistence fault; reconciliation required");
                JsonObject next = data;
                boolean entriesOnly = true;
                var copiedSections = new HashSet<String>();
                for (var request : group)
                {
                    try
                    {
                        JsonObject candidate;
                        if (request.section() == null)
                        {
                            candidate = next.deepCopy();
                            request.edit().accept(candidate);
                            entriesOnly = false;
                            copiedSections.clear();
                        }
                        else
                        {
                            candidate = next == data ? shallow(next) : next;
                            var records = next.has(request.section()) ? next.getAsJsonObject(request.section()) : new JsonObject();
                            if (!copiedSections.contains(request.section())) records = shallow(records);
                            var record = records.has(request.key()) ? records.getAsJsonObject(request.key()).deepCopy() : new JsonObject();
                            request.edit().accept(record);
                            records.add(request.key(), record);
                            candidate.add(request.section(), records);
                            copiedSections.add(request.section());
                        }
                        if (candidate.has("_stateSequence")) throw new IllegalArgumentException("Reserved metadata field");
                        next = candidate;
                        accepted.add(request);
                    }
                    catch (RuntimeException failure)
                    {
                        rejected.put(request, failure);
                    }
                }
                if (!accepted.isEmpty())
                {
                    JsonArray patches = null;
                    if (entriesOnly)
                    {
                        patches = new JsonArray();
                        var paths = new HashSet<String>();
                        for (var request : accepted)
                        {
                            var path = new JsonArray();
                            path.add(request.section());
                            if (data.has(request.section())) path.add(request.key());
                            if (!paths.add(path.toString())) continue;
                            JsonElement before = data.get(request.section());
                            JsonElement after = next.get(request.section());
                            if (path.size() == 2)
                            {
                                before = before.getAsJsonObject().get(request.key());
                                after = after.getAsJsonObject().get(request.key());
                            }
                            diff(before, after, path, patches);
                        }
                    }
                    commit(next, patches);
                    committedGroups++;
                    committedEdits += accepted.size();
                }
            }
            // Every accepted edit is acknowledged only after the shared commit,
            // local WAL force and immutable publication have completed.
            accepted.forEach(request -> request.result().complete(null));
            rejected.forEach((request, failure) -> request.result().completeExceptionally(failure));
        }
        catch (Throwable failure)
        {
            group.forEach(request -> request.result().completeExceptionally(rejected.getOrDefault(request, failure)));
        }
    }

    private static JsonObject shallow(JsonObject source)
    {
        var result = new JsonObject();
        source.entrySet().forEach(entry -> result.add(entry.getKey(), entry.getValue()));
        return result;
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
        commit(next);
    }

    private void commit(JsonObject next)
    {
        commit(next, null);
    }

    private void commit(JsonObject next, JsonArray patches)
    {
        if (next.has("_stateSequence"))
        {
            throw new IllegalArgumentException("Reserved metadata field");
        }
        if (shared != null)
        {
            var before = SharedMetadata.select(data);
            var after = SharedMetadata.select(next);
            if (!BinaryValues.sameValues(before, after))
            {
                overlay(next, shared.commit(before, after).join());
                sharedGeneration++;
            }
        }
        JsonArray changes = patches == null ? new JsonArray() : patches;
        if (patches == null) diff(durableData, next, new JsonArray(), changes);
        if (changes.isEmpty())
        {
            return;
        }
        try
        {
            binary.commit(next, changes);
            data = next;
            durableData = next;
            sequence = binary.sequence();
        }
        catch (IOException error)
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
            }
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
        BinaryStateStore.diff(before, after, path, changes);
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
