package nhcm.capitalmc.service;

import com.google.gson.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;

/** Single writer, checksummed patch WAL and independently published full state snapshots. */
public class BinaryStateStore
{
    public record Options(long snapshotEverySequences, long snapshotWalBytes, int retainedSnapshots)
    {
        public Options
        {
            if (snapshotEverySequences < 1 || snapshotWalBytes < 1024 || retainedSnapshots < 1 || retainedSnapshots > 64)
            {
                throw new IllegalArgumentException("Invalid metadata persistence options");
            }
        }

        public static Options defaults()
        {
            return new Options(128, 4 * 1024 * 1024, 2);
        }
    }

    private static final int MAGIC = 0x434D4353, VERSION = 1, HEADER = 32, LIMIT = 256 * 1024 * 1024;
    private static final Map<Path, WeakReference<BinaryStateStore>> writers = new WeakHashMap<>();
    private final Path directory, journal, snapshots, head;
    private final Options options;
    private JsonObject data = new JsonObject();
    private long sequence, snapshotSequence, replayedRecords;
    private boolean faulted;

    public BinaryStateStore(Path directory, Options options) throws IOException
    {
        this.directory = directory.toAbsolutePath().normalize();
        this.options = options;
        journal = this.directory.resolve("journal.wal");
        snapshots = this.directory.resolve("snapshots");
        head = this.directory.resolve("snapshot.head");
        Files.createDirectories(snapshots);
        if (!Files.exists(head))
        {
            try (var files = Files.list(snapshots))
            {
                if (Files.exists(journal) || files.anyMatch(path -> path.getFileName().toString().matches("snapshot-[0-9]{20}\\.bin")))
                {
                    throw new IOException("Missing required snapshot manifest; restore a complete backup");
                }
            }
        }
        if (Files.exists(head))
        {
            var marker = readFile(head);
            if (marker.payload.length != 0)
            {
                throw new IOException("Invalid snapshot manifest payload");
            }
            var image = readFile(snapshotPath(marker.sequence));
            if (image.sequence != marker.sequence)
            {
                throw new IOException("Snapshot boundary mismatch");
            }
            data = BinaryValues.decode(image.payload).getAsJsonObject();
            sequence = snapshotSequence = image.sequence;
        }
        recover();
    }

    public synchronized JsonObject snapshot()
    {
        return data.deepCopy();
    }
    public synchronized long sequence()
    {
        return sequence;
    }
    public synchronized long replayedRecords()
    {
        return replayedRecords;
    }
    public boolean initialized()
    {
        return Files.exists(head) || Files.exists(journal);
    }

    public synchronized void importState(JsonObject value, long importedSequence) throws IOException
    {
        if (initialized())
        {
            throw new IOException("Cannot overwrite initialized binary state");
        }
        data = value.deepCopy();
        sequence = importedSequence;
        checkpoint();
    }

    /** Called on the application IO executor; reuse the writer's options and failure state. */
    public static boolean checkpointStored(Path directory, Options options) throws IOException
    {
        BinaryStateStore store;
        synchronized (writers)
        {
            var reference = writers.get(directory.toAbsolutePath().normalize());
            store = reference == null ? null : reference.get();
        }
        if (store == null) store = new BinaryStateStore(directory, options);
        if (!store.initialized()) return false;
        store.checkpoint();
        return true;
    }

    public synchronized void commit(JsonObject next, JsonArray changes) throws IOException
    {
        rememberWriter();
        if (faulted)
        {
            throw new IOException("Binary state is faulted; restart and reconcile");
        }
        if (changes.isEmpty())
        {
            return;
        }
        try
        {
            if (!Files.exists(head))
            {
                checkpoint();
            }
            long nextSequence = Math.addExact(sequence, 1);
            byte[] bytes = frame(nextSequence, BinaryValues.encode(changes));
            try (var channel = FileChannel.open(journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND))
            {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining())
                {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            forceDirectory(directory);
            data = next;
            sequence = nextSequence;
            if (sequence - snapshotSequence >= options.snapshotEverySequences || Files.size(journal) >= options.snapshotWalBytes)
            {
                checkpoint();
            }
        }
        catch (IOException | RuntimeException error)
        {
            faulted = true;
            throw error;
        }
    }

    public synchronized void replace(JsonObject next) throws IOException
    {
        var changes = new JsonArray();
        diff(data, next, new JsonArray(), changes);
        commit(next.deepCopy(), changes);
    }

    private void recover() throws IOException
    {
        if (!Files.exists(journal))
        {
            return;
        }
        try (var channel = FileChannel.open(journal, StandardOpenOption.READ, StandardOpenOption.WRITE))
        {
            long offset = 0, length = channel.size();
            while (offset < length)
            {
                if (length - offset < HEADER)
                {
                    channel.truncate(offset);
                    channel.force(true);
                    break;
                }
                var header = ByteBuffer.allocate(HEADER);
                readFully(channel, header);
                header.flip();
                if (header.getInt() != MAGIC || header.getInt() != VERSION)
                {
                    throw new IOException("Invalid binary WAL header");
                }
                long id = header.getLong();
                if (id < 1)
                {
                    throw new IOException("Invalid binary WAL sequence");
                }
                int size = header.getInt();
                long checksum = header.getLong();
                if (header.getInt() != headerChecksum(header.array()))
                {
                    throw new IOException("Binary WAL header checksum mismatch");
                }
                if (size < 0 || size > LIMIT)
                {
                    throw new IOException("Invalid binary WAL size");
                }
                if (length - offset - HEADER < size)
                {
                    channel.truncate(offset);
                    channel.force(true);
                    break;
                }
                var payload = ByteBuffer.allocate(size);
                readFully(channel, payload);
                byte[] bytes = payload.array();
                if (checksum(id, bytes) != checksum)
                {
                    throw new IOException("Binary WAL checksum mismatch");
                }
                if (id > sequence)
                {
                    if (id != Math.addExact(sequence, 1))
                    {
                        throw new IOException("Binary WAL sequence gap");
                    }
                    for (var change : BinaryValues.decode(bytes).getAsJsonArray())
                    {
                        apply(data, change.getAsJsonObject());
                    }
                    sequence = id;
                    replayedRecords++;
                }
                offset += HEADER + size;
            }
        }
    }

    public synchronized void checkpoint() throws IOException
    {
        rememberWriter();
        if (faulted) throw new IOException("Binary state is faulted; restart and reconcile");
        try
        {
            writeCheckpoint();
        }
        catch (IOException | RuntimeException error)
        {
            faulted = true;
            throw error;
        }
    }

    private void rememberWriter()
    {
        synchronized (writers)
        {
            var reference = writers.get(directory);
            if (reference != null && reference.get() == this) return;
            writers.remove(directory);
            writers.put(directory, new WeakReference<>(this));
        }
    }

    private void writeCheckpoint() throws IOException
    {
        publish(snapshotPath(sequence), frame(sequence, BinaryValues.encode(data)));
        // The manifest is the commit point. Never fall back to an older snapshot after WAL pruning.
        publish(head, frame(sequence, new byte[0]));
        try (var channel = FileChannel.open(journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE))
        {
            channel.truncate(0);
            channel.force(true);
        }
        forceDirectory(directory);
        snapshotSequence = sequence;
        try (var stream = Files.list(snapshots))
        {
            var retained = stream.filter(path -> path.getFileName().toString().matches("snapshot-[0-9]{20}\\.bin"))
                               .sorted(Comparator.reverseOrder())
                               .toList();
            for (int i = options.retainedSnapshots; i < retained.size(); i++)
            {
                Files.delete(retained.get(i));
            }
        }
    }

    private Path snapshotPath(long id)
    {
        return snapshots.resolve(String.format(Locale.ROOT, "snapshot-%020d.bin", id));
    }
    private record Frame(long sequence, byte[] payload)
    {
    }

    private static long checksum(long id, byte[] payload)
    {
        var crc = new CRC32C();
        crc.update(ByteBuffer.allocate(16).putInt(MAGIC).putInt(VERSION).putLong(id).array());
        crc.update(payload);
        return crc.getValue();
    }

    private static int headerChecksum(byte[] bytes)
    {
        var crc = new CRC32C();
        crc.update(bytes, 0, HEADER - 4);
        return (int)crc.getValue();
    }

    private static byte[] frame(long id, byte[] payload) throws IOException
    {
        if (payload.length > LIMIT)
        {
            throw new IOException("Binary state exceeds size limit");
        }
        var buffer = ByteBuffer.allocate(HEADER + payload.length)
                         .putInt(MAGIC)
                         .putInt(VERSION)
                         .putLong(id)
                         .putInt(payload.length)
                         .putLong(checksum(id, payload));
        buffer.putInt(headerChecksum(buffer.array())).put(payload);
        return buffer.array();
    }

    private static Frame readFile(Path file) throws IOException
    {
        if (Files.size(file) > LIMIT + HEADER)
        {
            throw new IOException("Binary snapshot exceeds size limit");
        }
        return readFrame(Files.readAllBytes(file));
    }

    private static Frame readFrame(byte[] bytes) throws IOException
    {
        if (bytes.length < HEADER)
        {
            throw new IOException("Truncated binary snapshot");
        }
        var buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != MAGIC || buffer.getInt() != VERSION)
        {
            throw new IOException("Invalid binary snapshot header");
        }
        long id = buffer.getLong();
        int size = buffer.getInt();
        long crc = buffer.getLong();
        if (buffer.getInt() != headerChecksum(bytes))
        {
            throw new IOException("Binary snapshot header checksum mismatch");
        }
        if (id < 0 || size < 0 || size > LIMIT || size != buffer.remaining())
        {
            throw new IOException("Invalid binary snapshot size");
        }
        byte[] payload = new byte[size];
        buffer.get(payload);
        if (checksum(id, payload) != crc)
        {
            throw new IOException("Binary snapshot checksum mismatch");
        }
        return new Frame(id, payload);
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer) throws IOException
    {
        while (buffer.hasRemaining())
        {
            if (channel.read(buffer) < 0)
            {
                throw new EOFException();
            }
        }
    }

    private static void publish(Path destination, byte[] bytes) throws IOException
    {
        Path temporary = Files.createTempFile(destination.getParent(), "snapshot-", ".tmp");
        try
        {
            Files.write(temporary, bytes);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
            {
                channel.force(true);
            }
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(destination.getParent());
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    private static void forceDirectory(Path directory) throws IOException
    {
        if (System.getProperty("os.name").startsWith("Windows"))
        {
            return;
        }
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ))
        {
            channel.force(true);
        }
    }

    public static void diff(JsonElement before, JsonElement after, JsonArray path, JsonArray changes)
    {
        if (BinaryValues.sameValues(before, after))
        {
            return;
        }
        if (before != null && after != null && before.isJsonObject() && after.isJsonObject())
        {
            var keys = new LinkedHashSet<>(before.getAsJsonObject().keySet());
            keys.addAll(after.getAsJsonObject().keySet());
            for (String key : keys)
            {
                var child = path.deepCopy();
                child.add(key);
                diff(before.getAsJsonObject().get(key), after.getAsJsonObject().get(key), child, changes);
            }
        }
        else
        {
            var change = new JsonObject();
            change.add("path", path);
            change.addProperty("remove", after == null);
            if (after != null)
            {
                change.add("value", after.deepCopy());
            }
            changes.add(change);
        }
    }

    public static void apply(JsonObject root, JsonObject change)
    {
        var path = change.getAsJsonArray("path");
        if (path.isEmpty())
        {
            throw new IllegalStateException("Invalid binary patch");
        }
        var target = root;
        for (int i = 0; i < path.size() - 1; i++)
        {
            target = target.getAsJsonObject(path.get(i).getAsString());
        }
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
