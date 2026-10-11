package nhcm.capitalmc.management;

import com.google.gson.*;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.service.BinaryStateStore;
import nhcm.capitalmc.service.PersistenceSettings;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;

/**
 * Versioned binary account snapshots. Credentials are already hashed; sessions are never stored.
 */
class UserStorage
{
    private static final Map<Path, BinaryStateStore> stores = new WeakHashMap<>();

    static boolean exists(Path file)
    {
        if (Files.exists(file))
        {
            return true;
        }
        Path pin = file.resolveSibling(file.getFileName().toString().replaceFirst("\\.dat$", "") + ".store-path");
        if (!Files.exists(pin))
        {
            return false;
        }
        try
        {
            Path directory = file.getParent().resolve(Files.readString(pin).strip()).normalize();
            if (Files.exists(directory.resolve("snapshot.head")) || Files.exists(directory.resolve("journal.wal")))
            {
                return true;
            }
            Path snapshots = directory.resolve("snapshots");
            if (Files.isDirectory(snapshots))
            {
                try (var files = Files.list(snapshots))
                {
                    return files.anyMatch(path -> path.getFileName().toString().endsWith(".bin"));
                }
            }
            return false;
        }
        catch (IOException error)
        {
            throw new UncheckedIOException(error);
        }
    }

    static List<ServerUser> read(Path file) throws IOException
    {
        var store = open(file);
        synchronized (stores)
        {
            stores.put(file, store);
        }
        var result = new ArrayList<ServerUser>();
        for (var entry : store.snapshot().entrySet())
        {
            var record = entry.getValue().getAsJsonObject();
            var user = ServerUser.restore(record.get("display").getAsString(), record.get("username").getAsString(),
                                          record.get("passwordHash").getAsString(), UUID.fromString(entry.getKey()),
                                          UUID.fromString(record.get("owner").getAsString()));
            long exchange = record.get("exchange").getAsLong();
            if (exchange != 0)
            {
                user = user.withExchangeUserId(exchange);
            }
            if (record.has("profile"))
            {
                user = user.withProfile(record.getAsJsonObject("profile"));
            }
            result.add(user);
        }
        return List.copyOf(result);
    }

    static void write(Path file, Collection<ServerUser> users) throws IOException
    {
        BinaryStateStore store;
        synchronized (stores)
        {
            store = stores.get(file);
            if (store == null)
            {
                store = open(file);
                stores.put(file, store);
            }
        }
        var next = records(users);
        if (!store.initialized() && next.isEmpty())
        {
            store.importState(next, 0);
        }
        else
        {
            store.replace(next);
        }
    }

    private static BinaryStateStore open(Path file) throws IOException
    {
        var options = PersistenceSettings.load(file.getParent()).metadataOptions();
        var store = new BinaryStateStore(PersistenceSettings.storeDirectory(file), options);
        if (!store.initialized() && Files.exists(file))
        {
            store.importState(records(readLegacy(file)), 0);
        }
        return store;
    }

    private static JsonObject records(Collection<ServerUser> users)
    {
        var result = new JsonObject();
        for (var user : users)
        {
            var record = new JsonObject();
            record.addProperty("display", user.getDisplayName());
            record.addProperty("username", user.getUsername());
            record.addProperty("passwordHash", user.getPasswordHash());
            record.addProperty("owner", user.getOwnerUuid().toString());
            record.addProperty("exchange", user.getExchangeUserId());
            user.getProfile().ifPresent(profile -> record.add("profile", profile));
            result.add(user.getUuid().toString(), record);
        }
        return result;
    }

    private static final int MAGIC = 0x434d4355, VERSION = 1, LIMIT = 64 * 1024 * 1024;

    static List<ServerUser> readLegacy(Path file) throws IOException
    {
        if (Files.size(file) > LIMIT + 20L)
        {
            throw new IOException("Account store too large");
        }
        try (var input = new DataInputStream(Files.newInputStream(file)))
        {
            if (input.readInt() != MAGIC || input.readInt() != VERSION)
            {
                throw new IOException("Invalid account store header");
            }
            int size = input.readInt();
            long checksum = input.readLong();
            if (size < 0 || size > LIMIT)
            {
                throw new IOException("Invalid account payload length");
            }
            byte[] payload = input.readNBytes(size);
            var crc = new CRC32C();
            crc.update(payload);
            if (payload.length != size || input.read() != -1 || crc.getValue() != checksum)
            {
                throw new IOException("Account checksum mismatch");
            }
            try (var data = new DataInputStream(new ByteArrayInputStream(payload)))
            {
                int count = count(data);
                var users = new ArrayList<ServerUser>();
                for (int i = 0; i < count; i++)
                {
                    UUID id = uuid(data), owner = uuid(data);
                    var user = ServerUser.restore(data.readUTF(), data.readUTF(), data.readUTF(), id, owner);
                    long exchange = data.readLong();
                    if (exchange != 0)
                    {
                        user = user.withExchangeUserId(exchange);
                    }
                    var profile = tree(data, 0);
                    if (!profile.isJsonNull())
                    {
                        user = user.withProfile(profile.getAsJsonObject());
                    }
                    users.add(user);
                }
                if (data.read() != -1)
                {
                    throw new IOException("Trailing account payload");
                }
                return List.copyOf(users);
            }
        }
    }

    private static UUID uuid(DataInputStream in) throws IOException
    {
        return new UUID(in.readLong(), in.readLong());
    }

    private static int count(DataInputStream in) throws IOException
    {
        int count = in.readInt();
        if (count < 0 || count > 1_000_000)
        {
            throw new IOException("Invalid collection length");
        }
        return count;
    }

    private static JsonElement tree(DataInputStream in, int depth) throws IOException
    {
        if (depth > 64)
        {
            throw new IOException("Profile nesting limit exceeded");
        }
        return switch (in.readUnsignedByte())
        {
            case 0 -> JsonNull.INSTANCE;
            case 1 ->
            {
                var value = new JsonObject();
                int count = count(in);
                for (int i = 0; i < count; i++)
                {
                    String key = in.readUTF();
                    if (value.has(key))
                    {
                        throw new IOException("Duplicate profile key");
                    }
                    value.add(key, tree(in, depth + 1));
                }
                yield value;
            }
            case 2 ->
            {
                var value = new JsonArray();
                int count = count(in);
                for (int i = 0; i < count; i++)
                    value.add(tree(in, depth + 1));
                yield value;
            }
            case 3 -> new JsonPrimitive(Boolean.parseBoolean(in.readUTF()));
            case 4 -> new JsonPrimitive(new BigDecimal(in.readUTF()));
            case 5 -> new JsonPrimitive(in.readUTF());
            default -> throw new IOException("Invalid profile value type");
        };
    }
}
