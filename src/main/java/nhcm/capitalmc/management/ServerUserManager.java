package nhcm.capitalmc.management;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import nhcm.capitalmc.account.ServerUser;
import nhcm.advmatch.Exchange;
import nhcm.advmatch.object.User;
import java.util.concurrent.CompletableFuture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Independent accounts and per-player login sessions. No Minecraft player objects are retained. */
public class ServerUserManager {
    public static final int MAX_ACCOUNTS_PER_UUID = 3;
    private final Map<UUID, ServerUser> usersById = new HashMap<>();
    private final Map<String, UUID> idsByUsername = new HashMap<>();
    private final Map<UUID, UUID> sessions = new HashMap<>();
    private final Path storageFile;
    private Exchange exchange;
    private ExchangeUsers exchangeUsers;
    private final Map<UUID, CompletableFuture<ServerUser>> linking = new HashMap<>();
    private final Map<UUID, Long> createdExchangeIds = new HashMap<>();

    /** Adapter also allows testing the binding lifecycle without starting the native exchange. */
    public interface ExchangeUsers {
        CompletableFuture<Long> create();
        CompletableFuture<Void> validate(long id);
    }

    public synchronized CompletableFuture<Void> connectExchange(Exchange exchange) {
        Objects.requireNonNull(exchange, "exchange");
        if (this.exchange != null && this.exchange != exchange) {
            throw new IllegalStateException("Cannot replace the connected exchange");
        }
        this.exchange = exchange;
        return connectExchangeUsers(new ExchangeUsers() {
            public CompletableFuture<Long> create() {
                return exchange.users().create().thenApply(User::id);
            }
            public CompletableFuture<Void> validate(long id) {
                return exchange.users().find(id).thenApply(user -> null);
            }
        });
    }

    synchronized CompletableFuture<Void> connectExchangeUsers(ExchangeUsers access) {
        exchangeUsers = Objects.requireNonNull(access, "access");
        return CompletableFuture.allOf(usersById.keySet().stream()
                .map(this::ensureExchangeUser).toArray(CompletableFuture[]::new));
    }

    /** Deduplicates concurrent creation. A missing persisted backend user fails rather than silently rebinding. */
    public synchronized CompletableFuture<ServerUser> ensureExchangeUser(UUID accountId) {
        ServerUser user = get(accountId).orElseThrow(() -> new IllegalArgumentException("Unknown account"));
        if (exchangeUsers == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Exchange is not connected"));
        }
        CompletableFuture<ServerUser> pending = linking.get(accountId);
        if (pending != null) return pending.copy();
        CompletableFuture<ServerUser> result = new CompletableFuture<>();
        linking.put(accountId, result);
        try {
            long knownId = user.getExchangeUserId() != 0 ? user.getExchangeUserId()
                    : createdExchangeIds.getOrDefault(accountId, 0L);
            CompletableFuture<Long> id = knownId == 0 ? exchangeUsers.create()
                    : exchangeUsers.validate(knownId).thenApply(unused -> knownId);
            id.whenComplete((value, error) -> {
                synchronized (ServerUserManager.this) {
                    try {
                        if (error != null) throw new java.util.concurrent.CompletionException(error);
                        if (knownId == 0 && value != null && value > 0) createdExchangeIds.put(accountId, value);
                        ServerUser current = get(accountId).orElseThrow(() ->
                                new IllegalStateException("Account was removed while linking"));
                        ServerUser bound = current.withExchangeUserId(value);
                        if (current.getExchangeUserId() == 0) {
                            if (usersById.values().stream().anyMatch(other -> other.getExchangeUserId() == value)) {
                                throw new IllegalStateException("Exchange user is already linked to another account");
                            }
                            Map<UUID, ServerUser> next = new HashMap<>(usersById);
                            next.put(accountId, bound);
                            persist(next);
                            usersById.put(accountId, bound);
                        }
                        linking.remove(accountId, result);
                        createdExchangeIds.remove(accountId);
                        result.complete(current.getExchangeUserId() == 0 ? bound : current);
                    } catch (Throwable failure) {
                        linking.remove(accountId, result);
                        result.completeExceptionally(failure);
                    }
                }
            });
        } catch (Throwable error) {
            linking.remove(accountId, result);
            result.completeExceptionally(error);
        }
        return result.copy();
    }

    public CompletableFuture<ServerUser> registerAsync(String displayName, String username,
                                                      String password, UUID ownerUuid) {
        return CompletableFuture.supplyAsync(() -> register(displayName, username, password, ownerUuid))
                .thenCompose(user -> ensureExchangeUser(user.getUuid()));
    }

    public synchronized CompletableFuture<Optional<User>> getLoggedInExchangeUser(UUID playerUuid) {
        Optional<ServerUser> account = getLoggedInUser(playerUuid);
        if (account.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
        if (exchange == null) return CompletableFuture.failedFuture(new IllegalStateException("Exchange is not connected"));
        UUID accountId = account.get().getUuid();
        return ensureExchangeUser(accountId).thenApply(bound -> {
            synchronized (ServerUserManager.this) {
                if (!Objects.equals(sessions.get(playerUuid), accountId)) return Optional.empty();
                return Optional.of(exchange.users().get(bound.getExchangeUserId()));
            }
        });
    }

    /** In-memory registry, primarily for tests or callers providing their own persistence. */
    public ServerUserManager() {
        storageFile = null;
    }

    /** Loads persisted accounts. Login sessions are intentionally never persisted. */
    public ServerUserManager(Path storageFile) {
        this.storageFile = Objects.requireNonNull(storageFile, "storageFile").toAbsolutePath();
        load();
    }

    /** The supplied UUID is the creator's Minecraft UUID, not the new account ID. */
    public synchronized ServerUser register(String displayName, String username, String password, UUID ownerUuid) {
        validateRegistration(ownerUuid, username);
        return register(new ServerUser(displayName, username, password, UUID.randomUUID(), ownerUuid));
    }

    public synchronized ServerUser register(ServerUser user) {
        Objects.requireNonNull(user, "user");
        validateRegistration(user.getOwnerUuid(), user.getUsername());
        if (usersById.containsKey(user.getUuid())) {
            throw new IllegalArgumentException("Account ID is already registered");
        }
        if (user.getExchangeUserId() != 0 && usersById.values().stream()
                .anyMatch(other -> other.getExchangeUserId() == user.getExchangeUserId())) {
            throw new IllegalArgumentException("Exchange user is already linked to another account");
        }
        Map<UUID, ServerUser> next = new HashMap<>(usersById);
        next.put(user.getUuid(), user);
        persist(next);
        usersById.put(user.getUuid(), user);
        idsByUsername.put(normalizeUsername(user.getUsername()), user.getUuid());
        if (exchangeUsers != null) {
            ensureExchangeUser(user.getUuid()).whenComplete((bound, error) -> {
                if (error != null) System.getLogger(ServerUserManager.class.getName())
                        .log(System.Logger.Level.ERROR, "Cannot link account " + user.getUuid(), error);
            });
        }
        return user;
    }

    public synchronized Optional<ServerUser> get(UUID accountId) {
        return Optional.ofNullable(usersById.get(Objects.requireNonNull(accountId, "accountId")));
    }

    public synchronized Optional<ServerUser> findByUsername(String username) {
        return Optional.ofNullable(usersById.get(idsByUsername.get(normalizeUsername(username))));
    }

    public synchronized List<ServerUser> getOwnedAccounts(UUID ownerUuid) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        return usersById.values().stream().filter(user -> user.getOwnerUuid().equals(ownerUuid)).toList();
    }

    public synchronized List<ServerUser> getUsers() {
        return List.copyOf(usersById.values());
    }

    public synchronized int size() {
        return usersById.size();
    }

    /** Verifies credentials without changing a player's active account. */
    public synchronized Optional<ServerUser> authenticate(String username, String password) {
        return findByUsername(username).filter(user -> user.verifyPassword(password));
    }

    /** Any player may log into any account with its credentials. Failed logins leave the session unchanged. */
    public synchronized Optional<ServerUser> login(UUID playerUuid, String username, String password) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Optional<ServerUser> authenticated = authenticate(username, password);
        authenticated.ifPresent(user -> sessions.put(playerUuid, user.getUuid()));
        return authenticated;
    }

    public synchronized Optional<ServerUser> getLoggedInUser(UUID playerUuid) {
        return getSessionAccount(Objects.requireNonNull(playerUuid, "playerUuid"));
    }

    public synchronized void logout(UUID playerUuid) {
        sessions.remove(Objects.requireNonNull(playerUuid, "playerUuid"));
    }

    public synchronized void clearSessions() {
        sessions.clear();
    }

    /** Only the creator may delete the account. All its active login sessions are then invalidated. */
    public synchronized Optional<ServerUser> remove(UUID ownerUuid, UUID accountId) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        ServerUser user = get(accountId).orElse(null);
        if (user == null) {
            return Optional.empty();
        }
        if (!user.getOwnerUuid().equals(ownerUuid)) {
            throw new SecurityException("Only the account creator may delete it");
        }
        if (linking.containsKey(accountId)) {
            throw new IllegalStateException("Account is currently linking to the exchange");
        }
        Map<UUID, ServerUser> next = new HashMap<>(usersById);
        next.remove(accountId);
        persist(next);
        usersById.remove(accountId);
        createdExchangeIds.remove(accountId);
        idsByUsername.remove(normalizeUsername(user.getUsername()));
        sessions.values().removeIf(accountId::equals);
        return Optional.of(user);
    }

    /** Requires the old password, stores a fresh salted hash, and invalidates existing sessions. */
    public synchronized boolean changePassword(String username, String oldPassword, String newPassword) {
        ServerUser user = authenticate(username, oldPassword).orElse(null);
        if (user == null) {
            return false;
        }
        ServerUser replacement = new ServerUser(user.getDisplayName(), user.getUsername(), newPassword,
                user.getUuid(), user.getOwnerUuid());
        if (user.getExchangeUserId() != 0) replacement = replacement.withExchangeUserId(user.getExchangeUserId());
        Map<UUID, ServerUser> next = new HashMap<>(usersById);
        next.put(user.getUuid(), replacement);
        persist(next);
        usersById.put(user.getUuid(), replacement);
        sessions.values().removeIf(user.getUuid()::equals);
        return true;
    }

    private Optional<ServerUser> getSessionAccount(UUID playerUuid) {
        return Optional.ofNullable(usersById.get(sessions.get(playerUuid)));
    }

    private void validateRegistration(UUID ownerUuid, String username) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        if (idsByUsername.containsKey(normalizeUsername(username))) {
            throw new IllegalArgumentException("Username is already registered");
        }
        if (getOwnedAccounts(ownerUuid).size() >= MAX_ACCOUNTS_PER_UUID) {
            throw new IllegalStateException("A player UUID may create at most three accounts");
        }
    }

    private static String normalizeUsername(String username) {
        Objects.requireNonNull(username, "username");
        if (username.isBlank() || username.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Username must not be blank or contain whitespace");
        }
        return username.toLowerCase(Locale.ROOT);
    }

    private void load() {
        if (!Files.exists(storageFile)) {
            return;
        }
        try (var reader = Files.newBufferedReader(storageFile, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) {
                throw new IllegalArgumentException("Unsupported account storage version");
            }
            for (var entry : root.getAsJsonArray("users")) {
                JsonObject data = entry.getAsJsonObject();
                ServerUser user = ServerUser.restore(data.get("displayName").getAsString(),
                        data.get("username").getAsString(), data.get("passwordHash").getAsString(),
                        UUID.fromString(data.get("uuid").getAsString()),
                        UUID.fromString(data.get("ownerUuid").getAsString()));
                if (data.has("exchangeUserId") && data.get("exchangeUserId").getAsLong() != 0) {
                    user = user.withExchangeUserId(data.get("exchangeUserId").getAsLong());
                    long id = user.getExchangeUserId();
                    if (usersById.values().stream().anyMatch(other -> other.getExchangeUserId() == id)) {
                        throw new IllegalArgumentException("Duplicate persisted exchange user ID");
                    }
                }
                validateRegistration(user.getOwnerUuid(), user.getUsername());
                if (usersById.putIfAbsent(user.getUuid(), user) != null) {
                    throw new IllegalArgumentException("Duplicate persisted account ID");
                }
                idsByUsername.put(normalizeUsername(user.getUsername()), user.getUuid());
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot load account registry", exception);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid account registry: " + storageFile, exception);
        }
    }

    /** Persist before committing to memory, so failed writes leave the live registry unchanged. */
    private void persist(Map<UUID, ServerUser> users) {
        if (storageFile == null) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray entries = new JsonArray();
        for (ServerUser user : users.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", user.getUuid().toString());
            entry.addProperty("ownerUuid", user.getOwnerUuid().toString());
            entry.addProperty("displayName", user.getDisplayName());
            entry.addProperty("username", user.getUsername());
            entry.addProperty("passwordHash", user.getPasswordHash());
            entry.addProperty("exchangeUserId", user.getExchangeUserId());
            entries.add(entry);
        }
        root.add("users", entries);
        try {
            Files.createDirectories(storageFile.getParent());
            Path temporary = Files.createTempFile(storageFile.getParent(), "users-", ".tmp");
            try {
                Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(root),
                        StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, storageFile, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, storageFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot save account registry", exception);
        }
    }
}
