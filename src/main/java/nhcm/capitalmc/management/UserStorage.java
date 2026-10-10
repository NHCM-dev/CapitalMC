package nhcm.capitalmc.management;

import com.google.gson.*;
import nhcm.capitalmc.account.ServerUser;

import java.io.*;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;

/**
 * Versioned binary account snapshots. Credentials are already hashed; sessions are never stored.
 */
class UserStorage
{
    private static final int MAGIC = 0x434d4355, VERSION = 1, LIMIT = 64 * 1024 * 1024;

    static List<ServerUser> read(Path file) throws IOException
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

    static void write(Path file, Collection<ServerUser> users) throws IOException
    {
        var bytes = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(bytes))
        {
            data.writeInt(users.size());
            for (var user : users.stream().sorted(Comparator.comparing(ServerUser::getUuid)).toList())
            {
                uuid(data, user.getUuid());
                uuid(data, user.getOwnerUuid());
                data.writeUTF(user.getDisplayName());
                data.writeUTF(user.getUsername());
                data.writeUTF(user.getPasswordHash());
                data.writeLong(user.getExchangeUserId());
                tree(data, user.getProfile().map(p -> (JsonElement) p).orElse(JsonNull.INSTANCE), 0);
            }
        }
        byte[] payload = bytes.toByteArray();
        if (payload.length > LIMIT)
        {
            throw new IOException("Account store too large");
        }
        var crc = new CRC32C();
        crc.update(payload);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "users-", ".tmp");
        try
        {
            try (var data = new DataOutputStream(Files.newOutputStream(temporary)))
            {
                data.writeInt(MAGIC);
                data.writeInt(VERSION);
                data.writeInt(payload.length);
                data.writeLong(crc.getValue());
                data.write(payload);
            }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
            {
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    private static UUID uuid(DataInputStream in) throws IOException
    {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void uuid(DataOutputStream out, UUID id) throws IOException
    {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
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

    // Profile structure is encoded as typed binary values, never as JSON text.
    private static void tree(DataOutputStream out, JsonElement value, int depth) throws IOException
    {
        if (depth > 64)
        {
            throw new IOException("Profile nesting limit exceeded");
        }
        if (value.isJsonNull())
        {
            out.writeByte(0);
            return;
        }
        if (value.isJsonObject())
        {
            out.writeByte(1);
            out.writeInt(value.getAsJsonObject().size());
            for (var e : value.getAsJsonObject().entrySet())
            {
                out.writeUTF(e.getKey());
                tree(out, e.getValue(), depth + 1);
            }
            return;
        }
        if (value.isJsonArray())
        {
            out.writeByte(2);
            out.writeInt(value.getAsJsonArray().size());
            for (var e : value.getAsJsonArray())
                tree(out, e, depth + 1);
            return;
        }
        var primitive = value.getAsJsonPrimitive();
        out.writeByte(primitive.isBoolean() ? 3 : primitive.isNumber() ? 4 : 5);
        out.writeUTF(primitive.getAsString());
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
