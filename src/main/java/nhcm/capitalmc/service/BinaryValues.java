package nhcm.capitalmc.service;

import com.google.gson.*;
import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/** Typed binary values. JsonElement is an in-memory adapter, never serialized JSON text. */
public class BinaryValues
{
    private static final int LIMIT = 256 * 1024 * 1024;

    public static boolean sameValues(JsonElement before, JsonElement after)
    {
        if (before == after)
        {
            return true;
        }
        if (before == null || after == null)
        {
            return false;
        }
        if (before.isJsonPrimitive() && after.isJsonPrimitive()
            && before.getAsJsonPrimitive().isNumber() && after.getAsJsonPrimitive().isNumber())
        {
            return before.getAsBigDecimal().compareTo(after.getAsBigDecimal()) == 0;
        }
        if (before.isJsonObject() && after.isJsonObject())
        {
            var left = before.getAsJsonObject();
            var right = after.getAsJsonObject();
            if (!left.keySet().equals(right.keySet()))
            {
                return false;
            }
            for (String key : left.keySet())
            {
                if (!sameValues(left.get(key), right.get(key)))
                {
                    return false;
                }
            }
            return true;
        }
        if (before.isJsonArray() && after.isJsonArray())
        {
            var left = before.getAsJsonArray();
            var right = after.getAsJsonArray();
            if (left.size() != right.size())
            {
                return false;
            }
            for (int index = 0; index < left.size(); index++)
            {
                if (!sameValues(left.get(index), right.get(index)))
                {
                    return false;
                }
            }
            return true;
        }
        return before.equals(after);
    }

    public static byte[] encode(JsonElement value) throws IOException
    {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes))
        {
            write(output, value, 0);
        }
        return bytes.toByteArray();
    }

    public static JsonElement decode(byte[] bytes) throws IOException
    {
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes)))
        {
            var value = read(input, 0);
            if (input.read() != -1)
            {
                throw new IOException("Trailing binary values");
            }
            return value;
        }
    }

    private static void string(DataOutputStream output, String value) throws IOException
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT)
        {
            throw new IOException("Binary string too large");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String string(DataInputStream input) throws IOException
    {
        int size = input.readInt();
        if (size < 0 || size > LIMIT || size > input.available())
        {
            throw new IOException("Invalid binary string length");
        }
        return new String(input.readNBytes(size), StandardCharsets.UTF_8);
    }

    private static void write(DataOutputStream output, JsonElement value, int depth) throws IOException
    {
        if (depth > 128)
        {
            throw new IOException("Binary nesting limit exceeded");
        }
        if (value.isJsonNull())
        {
            output.writeByte(0);
        }
        else if (value.isJsonObject())
        {
            output.writeByte(1);
            output.writeInt(value.getAsJsonObject().size());
            for (var entry : value.getAsJsonObject().entrySet())
            {
                string(output, entry.getKey());
                write(output, entry.getValue(), depth + 1);
            }
        }
        else if (value.isJsonArray())
        {
            output.writeByte(2);
            output.writeInt(value.getAsJsonArray().size());
            for (var item : value.getAsJsonArray())
            {
                write(output, item, depth + 1);
            }
        }
        else
        {
            var primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean())
            {
                output.writeByte(3);
                output.writeBoolean(primitive.getAsBoolean());
            }
            else if (primitive.isNumber())
            {
                Number numberValue = primitive.getAsNumber();
                if (numberValue instanceof Long || numberValue instanceof Integer
                    || numberValue instanceof Short || numberValue instanceof Byte)
                {
                    output.writeByte(6);
                    output.writeLong(numberValue.longValue());
                    return;
                }
                output.writeByte(4);
                var number = primitive.getAsBigDecimal();
                output.writeInt(number.scale());
                byte[] bytes = number.unscaledValue().toByteArray();
                output.writeInt(bytes.length);
                output.write(bytes);
            }
            else
            {
                output.writeByte(5);
                string(output, primitive.getAsString());
            }
        }
    }

    private static JsonElement read(DataInputStream input, int depth) throws IOException
    {
        if (depth > 128)
        {
            throw new IOException("Binary nesting limit exceeded");
        }
        int type = input.readUnsignedByte();
        if (type == 0)
        {
            return JsonNull.INSTANCE;
        }
        if (type == 3)
        {
            return new JsonPrimitive(input.readBoolean());
        }
        if (type == 5)
        {
            return new JsonPrimitive(string(input));
        }
        if (type == 6)
        {
            return new JsonPrimitive(input.readLong());
        }
        if (type == 4)
        {
            int scale = input.readInt(), size = input.readInt();
            if (size < 1 || size > LIMIT || size > input.available())
            {
                throw new IOException("Invalid binary number");
            }
            return new JsonPrimitive(new BigDecimal(new BigInteger(input.readNBytes(size)), scale));
        }
        int count = input.readInt();
        if (count < 0 || count > input.available())
        {
            throw new IOException("Invalid binary collection length");
        }
        if (type == 1)
        {
            var result = new JsonObject();
            for (int i = 0; i < count; i++)
            {
                String key = string(input);
                if (result.has(key))
                {
                    throw new IOException("Duplicate binary key");
                }
                result.add(key, read(input, depth + 1));
            }
            return result;
        }
        if (type == 2)
        {
            var result = new JsonArray();
            for (int i = 0; i < count; i++)
            {
                result.add(read(input, depth + 1));
            }
            return result;
        }
        throw new IOException("Unknown binary value type");
    }
}
