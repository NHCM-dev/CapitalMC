package nhcm.capitalmc.account;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** An independent trading account; its ID is distinct from its creator's player UUID. */
public final class ServerUser {
    private static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String displayName;
    private final String username;
    private final String passwordHash;
    private final UUID uuid;
    private final UUID ownerUuid;
    private final long exchangeUserId;

    public ServerUser(String displayName, String username, String password, UUID uuid, UUID ownerUuid) {
        this(uuid, ownerUuid, displayName, username, hashPassword(password));
    }

    private ServerUser(UUID uuid, UUID ownerUuid, String displayName, String username, String passwordHash) {
        this(uuid, ownerUuid, displayName, username, passwordHash, 0);
    }

    private ServerUser(UUID uuid, UUID ownerUuid, String displayName, String username, String passwordHash,
                       long exchangeUserId) {
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.username = Objects.requireNonNull(username, "username");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        if (exchangeUserId < 0) throw new IllegalArgumentException("Invalid exchange user ID");
        this.exchangeUserId = exchangeUserId;
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("Display name must not be blank");
        }
        validateHash(passwordHash);
    }

    /** Restores an already hashed credential from storage, without hashing it again. */
    public static ServerUser restore(String displayName, String username, String passwordHash,
                                     UUID uuid, UUID ownerUuid) {
        return new ServerUser(uuid, ownerUuid, displayName, username, passwordHash);
    }

    public String getDisplayName() { return displayName; }
    public String getUsername() { return username; }
    public UUID getUuid() { return uuid; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public String getPasswordHash() { return passwordHash; }
    /** Zero means the account has not been linked yet. */
    public long getExchangeUserId() { return exchangeUserId; }

    public ServerUser withExchangeUserId(long id) {
        if (id <= 0) throw new IllegalArgumentException("Exchange user ID must be positive");
        if (exchangeUserId != 0 && exchangeUserId != id) {
            throw new IllegalStateException("Account is already linked to another exchange user");
        }
        return new ServerUser(uuid, ownerUuid, displayName, username, passwordHash, id);
    }

    public boolean hasPassword() { return true; }

    public boolean verifyPassword(String password) {
        if (password == null || password.isEmpty()) {
            return false;
        }
        String[] parts = passwordHash.split("\\$");
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        byte[] actual = derive(password, salt, Integer.parseInt(parts[1]));
        try {
            return MessageDigest.isEqual(expected, actual);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    private static String hashPassword(String password) {
        Objects.requireNonNull(password, "password");
        if (password.isBlank()) {
            throw new IllegalArgumentException("Password must not be blank");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(password, salt, ITERATIONS);
        try {
            return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                    + "$" + Base64.getEncoder().encodeToString(hash);
        } finally {
            Arrays.fill(hash, (byte) 0);
        }
    }

    private static void validateHash(String hash) {
        String[] parts = hash.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) {
            throw new IllegalArgumentException("Unsupported password hash format");
        }
        int iterations = Integer.parseInt(parts[1]);
        if (iterations < 100_000 || iterations > 1_000_000
                || Base64.getDecoder().decode(parts[2]).length != 16
                || Base64.getDecoder().decode(parts[3]).length != 32) {
            throw new IllegalArgumentException("Invalid password hash parameters");
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, 256);
        Arrays.fill(chars, '\0');
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Password hashing is unavailable", exception);
        } finally {
            spec.clearPassword();
        }
    }
}
