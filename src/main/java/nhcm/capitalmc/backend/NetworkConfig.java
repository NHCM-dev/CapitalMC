package nhcm.capitalmc.backend;

import com.google.gson.GsonBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Arrays;

/** The same configuration is used by Minecraft gateways and the standalone owner. */
public class NetworkConfig
{
    public enum Mode
    {
        LOCAL, REMOTE
    }

    public Mode mode = Mode.LOCAL;
    public String instanceId = UUID.randomUUID().toString();
    public boolean listen = false;
    public String bind = "127.0.0.1";
    public String host = "127.0.0.1";
    public int port = 25570;
    public String token = newToken();
    public boolean tls = false;
    public boolean allowPlaintextNetwork = false;
    public String keyStore = "";
    public String keyStorePassword = "";
    public String expectedExchangeId = "";
    public int maxConnections = 64;
    public int maxOutstanding = 256;
    public int requestTimeoutMillis = 30000;
    public int maxFrameBytes = 4194304;
    private transient Path file;

    public static NetworkConfig load(Path file) throws IOException
    {
        var json = new GsonBuilder().setPrettyPrinting().create();
        var config = Files.exists(file) ? json.fromJson(Files.readString(file), NetworkConfig.class) : new NetworkConfig();
        config.file = file.toAbsolutePath();
        config.validate();
        config.save(); // Persist generated instance identity and defaults even for a minimal existing configuration.
        return config;
    }

    public void validate()
    {
        if (mode == null || instanceId == null || !instanceId.matches("[A-Za-z0-9_-]{1,64}")
            || token == null || token.length() < 32 || bind == null || host == null || host.isBlank()
            || port < 1 || port > 65535 || maxConnections < 1 || maxConnections > 256
            || maxOutstanding < 1 || maxOutstanding > 4096 || requestTimeoutMillis < 1000
            || maxFrameBytes < 1024 || maxFrameBytes > 16777216 || expectedExchangeId == null)
        {
            throw new IllegalArgumentException("Invalid CapitalMC exchange connection configuration");
        }
        if (!expectedExchangeId.isEmpty()) UUID.fromString(expectedExchangeId);
    }

    public synchronized void pin(String identity) throws IOException
    {
        if (!expectedExchangeId.isEmpty() && !expectedExchangeId.equals(identity))
        {
            throw new IllegalStateException("This gateway is bound to a different exchange");
        }
        expectedExchangeId = identity;
        save();
    }

    public synchronized void save() throws IOException
    {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(this));
        try
        {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    public SSLContext serverTls() throws Exception
    {
        if (!tls) return null;
        if (keyStore.isBlank()) throw new IllegalArgumentException("TLS listener requires a PKCS12 keyStore");
        var store = KeyStore.getInstance("PKCS12");
        var path = file.getParent().resolve(keyStore).normalize();
        char[] password = keyStorePassword.toCharArray();
        try (var input = Files.newInputStream(path))
        {
            store.load(input, password);
        }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        var context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        Arrays.fill(password, '\0');
        return context;
    }

    private static String newToken()
    {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
