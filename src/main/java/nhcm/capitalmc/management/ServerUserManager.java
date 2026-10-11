package nhcm.capitalmc.management;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import nhcm.advmatch.Exchange;
import nhcm.advmatch.object.User;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.backend.AccountDirectory;
import nhcm.capitalmc.backend.AccountViews;
import nhcm.capitalmc.service.ApplicationExecutors;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Independent accounts and per-player login sessions. No Minecraft player objects are retained.
 */
public class ServerUserManager
{
    public static final int MAX_ACCOUNTS_PER_UUID = 3;
    private volatile Predicate<ServerUser> accessAllowed = user -> true;
    private volatile AccountDirectory directory;
    private volatile long directoryGeneration;
    private List<ServerUser> directoryImage;

    public void useDirectory(AccountDirectory directory)
    {
        this.directory = Objects.requireNonNull(directory);
    }

    public CompletableFuture<Void> refreshDirectory()
    {
        if (directory == null) return CompletableFuture.completedFuture(null);
        long expected = directoryGeneration;
        return directory.snapshot().thenAccept(users -> {
            synchronized (this)
            {
                if (directoryGeneration == expected) publishDirectory(users);
            }
        });
    }

    private synchronized void publishDirectory(List<ServerUser> users)
    {
        if (directoryImage == users) return;
        directoryImage = users;
        var next = new HashMap<UUID, ServerUser>();
        for (var user : users)
        {
            var current = directoryIndex.source().get(user.getUuid());
            if (current != null && AccountViews.encode(current).equals(AccountViews.encode(user)))
            {
                user = current;
            }
            else if (current != null)
            {
                revokeSessions(user.getUuid());
            }
            next.put(user.getUuid(), user);
        }
        directoryIndex.source().keySet().stream().filter(id -> !next.containsKey(id)).forEach(this::revokeSessions);
        publishUsers(next);
        directoryGeneration++;
    }

    private synchronized ServerUser publishAccount(ServerUser user)
    {
        var next = new HashMap<>(directoryIndex.source());
        var current = next.get(user.getUuid());
        if (current != null && AccountViews.encode(current).equals(AccountViews.encode(user))) return current;
        if (current != null) revokeSessions(user.getUuid());
        next.put(user.getUuid(), user);
        publishUsers(next);
        directoryImage = null;
        directoryGeneration++;
        return user;
    }

    public void setAccessPolicy(Predicate<ServerUser> policy)
    {
        accessAllowed = Objects.requireNonNull(policy);
    }

    public void requireAccess(ServerUser user)
    {
        if (!accessAllowed.test(user))
        {
            throw new IllegalStateException("cli.user.banned");
        }
    }

    public List<UUID> playersUsing(UUID account)
    {
        synchronized (sessions)
        {
            return sessions.entrySet().stream().filter(entry -> entry.getValue().equals(account)).map(Map.Entry::getKey).toList();
        }
    }

    public void revokeSessions(UUID account)
    {
        synchronized (sessions)
        {
            for (UUID player : List.copyOf(sessions.keySet()))
            {
                if (account.equals(sessions.get(player)))
                {
                    logout(player);
                }
            }
        }
    }

    private final Map<UUID, UUID> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sessionVersions = new ConcurrentHashMap<>();
    private final Path storageFile;
    private final Path legacyFile;
    private volatile Exchange exchange;
    private final Set<Long> validatedExchangeIds = ConcurrentHashMap.newKeySet();
    private ExchangeUsers exchangeUsers;
    private final Map<UUID, CompletableFuture<ServerUser>> linking = new HashMap<>();
    private final Map<UUID, Long> createdExchangeIds = new HashMap<>();

    /**
     * Adapter also allows testing the binding lifecycle without starting the native exchange.
     */
    public interface ExchangeUsers
    {
        CompletableFuture<Long> create();

        CompletableFuture<Void> validate(long id);
    }

    public synchronized CompletableFuture<Void> connectExchange(Exchange exchange)
    {
        Objects.requireNonNull(exchange, "exchange");
        if (this.exchange != null && this.exchange != exchange)
        {
            throw new IllegalStateException("Cannot replace the connected exchange");
        }
        this.exchange = exchange;
        if (directory != null) return refreshDirectory();
        return connectExchangeUsers(new ExchangeUsers()
        {
            public CompletableFuture<Long> create()
            {
                return exchange.users().create().thenApply(User::id);
            }

            public CompletableFuture<Void> validate(long id)
            {
                return exchange.users().find(id).thenApply(user -> null);
            }
        });
    }

    synchronized CompletableFuture<Void> connectExchangeUsers(ExchangeUsers access)
    {
        exchangeUsers = Objects.requireNonNull(access, "access");
        return CompletableFuture.allOf(directoryIndex.source().keySet().stream()
                                                .map(this::ensureExchangeUser).toArray(CompletableFuture[]::new));
    }

    /**
     * Deduplicates concurrent creation. A missing persisted backend user fails rather than silently rebinding.
     */
    public CompletableFuture<ServerUser> ensureExchangeUser(UUID accountId)
    {
        if (directory != null) return directory.ensure(accountId).thenApply(this::publishAccount);
        ServerUser known = get(accountId).orElse(null);
        if (known != null && known.getExchangeUserId() != 0 && validatedExchangeIds.contains(known.getExchangeUserId()))
        {
            return CompletableFuture.completedFuture(known);
        }
        return ApplicationExecutors.io(() -> ensureExchangeUserInternal(accountId)).thenCompose(future -> future);
    }

    private synchronized CompletableFuture<ServerUser> ensureExchangeUserInternal(UUID accountId)
    {
        ServerUser user = get(accountId).orElseThrow(() -> new IllegalArgumentException("Unknown account"));
        if (exchangeUsers == null)
        {
            return CompletableFuture.failedFuture(new IllegalStateException("Exchange is not connected"));
        }
        CompletableFuture<ServerUser> pending = linking.get(accountId);
        if (pending != null)
        {
            return pending.copy();
        }
        CompletableFuture<ServerUser> result = new CompletableFuture<>();
        linking.put(accountId, result);
        try
        {
            long knownId = user.getExchangeUserId() != 0 ? user.getExchangeUserId()
                    : createdExchangeIds.getOrDefault(accountId, 0L);
            CompletableFuture<Long> id = knownId == 0 ? exchangeUsers.create()
                    : exchangeUsers.validate(knownId).thenApply(unused -> knownId);
            id.whenComplete((value, error) -> {
                synchronized (ServerUserManager.this)
                {
                    // Preserve a completed native creation even if metadata admission
                    // is full; a later attempt must bind this ID, not create another.
                    if (error == null && knownId == 0 && value != null && value > 0) createdExchangeIds.put(accountId, value);
                }
            }).whenCompleteAsync((value, error) -> {
                synchronized (ServerUserManager.this)
                {
                    try
                    {
                        if (error != null)
                        {
                            throw new CompletionException(error);
                        }
                        if (knownId == 0 && value != null && value > 0)
                        {
                            createdExchangeIds.put(accountId, value);
                        }
                        ServerUser current = get(accountId).orElseThrow(() ->
                                                                                new IllegalStateException("Account was removed while linking"));
                        ServerUser bound = current.withExchangeUserId(value);
                        if (current.getExchangeUserId() == 0)
                        {
                            if (findByExchangeId(value).isPresent())
                            {
                                throw new IllegalStateException("Exchange user is already linked to another account");
                            }
                            Map<UUID, ServerUser> next = new HashMap<>(directoryIndex.source());
                            next.put(accountId, bound);
                            persist(next);
                            publishUsers(next);
                        }
                        linking.remove(accountId, result);
                        createdExchangeIds.remove(accountId);
                        validatedExchangeIds.add(bound.getExchangeUserId());
                        result.complete(current.getExchangeUserId() == 0 ? bound : current);
                    } catch (Throwable failure)
                    {
                        linking.remove(accountId, result);
                        result.completeExceptionally(failure);
                    }
                }
            }, ApplicationExecutors.IO).whenComplete((value, failure) -> {
                if (failure != null)
                {
                    synchronized (ServerUserManager.this)
                    {
                        linking.remove(accountId, result);
                        result.completeExceptionally(failure);
                    }
                }
            });
        } catch (Throwable error)
        {
            linking.remove(accountId, result);
            result.completeExceptionally(error);
        }
        return result.copy();
    }

    public CompletableFuture<ServerUser> registerAsync(String displayName, String username,
                                                       String password, UUID ownerUuid)
    {
        return registerAsync(displayName, username, password, ownerUuid, null);
    }

    public CompletableFuture<ServerUser> registerAsync(String displayName, String username,
                                                       String password, UUID ownerUuid, JsonObject profile)
    {
        if (directory != null)
        {
            return directory.register(displayName, username, password, ownerUuid, profile).thenApply(this::publishAccount);
        }
        JsonObject snapshot = profile == null ? null : profile.deepCopy();
        return CompletableFuture.supplyAsync(() -> new ServerUser(displayName, username, password,
                                                                  UUID.randomUUID(), ownerUuid).withProfile(snapshot), ApplicationExecutors.CREDENTIALS)
                                .thenCompose(user -> ApplicationExecutors.io(() -> register(user)))
                                .thenCompose(user -> ensureExchangeUser(user.getUuid()));
    }

    public CompletableFuture<Optional<ServerUser>> authenticateAsync(String username, String password)
    {
        if (directory != null)
        {
            return directory.authenticate(username, password).thenApply(found -> found.map(this::publishAccount));
        }
        ServerUser candidate = findByUsername(username).orElse(null);
        if (candidate != null && !accessAllowed.test(candidate))
        {
            return CompletableFuture.failedFuture(new IllegalStateException("cli.user.banned"));
        }
        return CompletableFuture.supplyAsync(() -> candidate != null && accessAllowed.test(candidate) && candidate.verifyPassword(password)
                                                   && directoryIndex.source().get(candidate.getUuid()) == candidate ? Optional.of(candidate) : Optional.empty(),
                                             ApplicationExecutors.CREDENTIALS);
    }

    public CompletableFuture<Optional<ServerUser>> loginAsync(UUID player, String username, String password)
    {
        final long version;
        synchronized (sessions)
        {
            version = sessionVersions.merge(player, 1L, Math::addExact);
        }
        return authenticateAsync(username, password).thenApply(result -> {
            synchronized (sessions)
            {
                if (!Objects.equals(sessionVersions.get(player), version))
                {
                    return Optional.empty();
                }
                Optional<ServerUser> current = result.filter(user -> directoryIndex.source().get(user.getUuid()) == user);
                current.ifPresent(user -> sessions.put(player, user.getUuid()));
                return current;
            }
        });
    }

    /**
     * Trusted application continuation after authenticateAsync; rejects a credential changed in the meantime.
     */
    public void activateVerified(UUID player, ServerUser verified)
    {
        synchronized (sessions)
        {
            requireAccess(verified);
            if (directoryIndex.source().get(verified.getUuid()) != verified)
            {
                throw new IllegalStateException("ui.session.changed");
            }
            sessionVersions.merge(player, 1L, Math::addExact);
            sessions.put(player, verified.getUuid());
        }
    }

    public CompletableFuture<Boolean> changePasswordAsync(String username, String oldPassword, String newPassword)
    {
        if (directory != null)
        {
            return directory.password(username, oldPassword, newPassword).thenCompose(changed -> refreshDirectory().thenApply(unused -> changed));
        }
        return authenticateAsync(username, oldPassword).thenCompose(found -> {
            if (found.isEmpty())
            {
                return CompletableFuture.completedFuture(false);
            }
            var user = found.get();
            return CompletableFuture.supplyAsync(() -> new ServerUser(user.getDisplayName(), user.getUsername(),
                                                                      newPassword, user.getUuid(), user.getOwnerUuid()).withProfile(user.getProfile().orElse(null)),
                                                 ApplicationExecutors.CREDENTIALS).thenCompose(replacement -> ApplicationExecutors.io(() -> {
                synchronized (ServerUserManager.this)
                {
                    if (directoryIndex.source().get(user.getUuid()) != user)
                    {
                        return false;
                    }
                    replaceCredential(user, replacement);
                    return true;
                }
            }));
        });
    }

    public CompletableFuture<Optional<User>> getLoggedInExchangeUser(UUID playerUuid)
    {
        Optional<ServerUser> account = getLoggedInUser(playerUuid);
        if (account.isEmpty())
        {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (exchange == null)
        {
            return CompletableFuture.failedFuture(new IllegalStateException("Exchange is not connected"));
        }
        UUID accountId = account.get().getUuid();
        return ensureExchangeUser(accountId).thenApply(bound -> {
            synchronized (sessions)
            {
                if (!Objects.equals(sessions.get(playerUuid), accountId))
                {
                    return Optional.empty();
                }
                return Optional.of(exchange.users().get(bound.getExchangeUserId()));
            }
        });
    }

    /**
     * In-memory registry, primarily for tests or callers providing their own persistence.
     */
    public ServerUserManager()
    {
        storageFile = null;
        legacyFile = null;
    }

    /**
     * Loads persisted accounts. Login sessions are intentionally never persisted.
     */
    public ServerUserManager(Path storageFile)
    {
        Path requested = Objects.requireNonNull(storageFile, "storageFile").toAbsolutePath();
        String name = requested.getFileName().toString();
        this.storageFile = name.endsWith(".json") ? requested.resolveSibling(name.substring(0, name.length() - 5) + ".dat") : requested;
        this.legacyFile = name.endsWith(".json") ? requested : requested.resolveSibling("users.json");
        load();
    }

    /**
     * The supplied UUID is the creator's Minecraft UUID, not the new account ID.
     */
    public synchronized ServerUser register(String displayName, String username, String password, UUID ownerUuid)
    {
        validateRegistration(ownerUuid, username);
        return register(new ServerUser(displayName, username, password, UUID.randomUUID(), ownerUuid));
    }

    public synchronized ServerUser register(ServerUser user)
    {
        requireLocalDirectory();
        Objects.requireNonNull(user, "user");
        validateRegistration(user.getOwnerUuid(), user.getUsername());
        if (directoryIndex.source().containsKey(user.getUuid()))
        {
            throw new IllegalArgumentException("Account ID is already registered");
        }
        if (user.getExchangeUserId() != 0 && directoryIndex.source().values().stream()
                                                      .anyMatch(other -> other.getExchangeUserId() == user.getExchangeUserId()))
        {
            throw new IllegalArgumentException("Exchange user is already linked to another account");
        }
        Map<UUID, ServerUser> next = new HashMap<>(directoryIndex.source());
        next.put(user.getUuid(), user);
        persist(next);
        publishUsers(next);
        if (exchangeUsers != null)
        {
            ensureExchangeUser(user.getUuid()).whenComplete((bound, error) -> {
                if (error != null)
                {
                    System.getLogger(ServerUserManager.class.getName())
                          .log(System.Logger.Level.ERROR, "Cannot link account " + user.getUuid(), error);
                }
            });
        }
        return user;
    }

    public Optional<ServerUser> get(UUID accountId)
    {
        return Optional.ofNullable(directoryIndex.source().get(Objects.requireNonNull(accountId, "accountId")));
    }

    private record DirectoryIndex(Map<UUID, ServerUser> source, Map<String, ServerUser> usernames,
                                  Map<Long, ServerUser> exchangeIds, Map<UUID, List<ServerUser>> owners,
                                  List<ServerUser> users)
    {
    }

    private volatile DirectoryIndex directoryIndex = new DirectoryIndex(Map.of(), Map.of(), Map.of(), Map.of(), List.of());

    private void publishUsers(Map<UUID, ServerUser> values)
    {
        var source = Map.copyOf(values);
        var usernames = new HashMap<String, ServerUser>();
        var exchangeIds = new HashMap<Long, ServerUser>();
        var owners = new HashMap<UUID, List<ServerUser>>();
        for (var user : source.values())
        {
            if (usernames.put(normalizeUsername(user.getUsername()), user) != null)
            {
                throw new IllegalStateException("Duplicate account username");
            }
            if (user.getExchangeUserId() != 0 && exchangeIds.put(user.getExchangeUserId(), user) != null)
            {
                throw new IllegalStateException("Duplicate exchange identity");
            }
            owners.computeIfAbsent(user.getOwnerUuid(), ignored -> new ArrayList<>()).add(user);
        }
        owners.replaceAll((owner, accounts) -> List.copyOf(accounts));
        directoryIndex = new DirectoryIndex(source, Map.copyOf(usernames), Map.copyOf(exchangeIds),
                                            Map.copyOf(owners), List.copyOf(source.values()));
    }

    public Optional<ServerUser> findByExchangeId(long id)
    {
        return Optional.ofNullable(directoryIndex.exchangeIds().get(id));
    }

    public Optional<ServerUser> findByUsername(String username)
    {
        String normalized = normalizeUsername(username);
        return Optional.ofNullable(directoryIndex.usernames().get(normalized));
    }

    public List<ServerUser> getOwnedAccounts(UUID ownerUuid)
    {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        return directoryIndex.owners().getOrDefault(ownerUuid, List.of());
    }

    public List<ServerUser> getUsers()
    {
        return directoryIndex.users();
    }

    public int size()
    {
        return directoryIndex.source().size();
    }

    /**
     * Verifies credentials without changing a player's active account.
     */
    public Optional<ServerUser> authenticate(String username, String password)
    {
        requireLocalDirectory();
        return findByUsername(username).filter(accessAllowed).filter(user -> user.verifyPassword(password));
    }

    /**
     * Any player may log into any account with its credentials. Failed logins leave the session unchanged.
     */
    public synchronized Optional<ServerUser> login(UUID playerUuid, String username, String password)
    {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Optional<ServerUser> authenticated = authenticate(username, password);
        authenticated.ifPresent(user -> activateVerified(playerUuid, user));
        return authenticated;
    }

    public Optional<ServerUser> getLoggedInUser(UUID playerUuid)
    {
        return getSessionAccount(Objects.requireNonNull(playerUuid, "playerUuid"));
    }

    public long getSessionVersion(UUID playerUuid)
    {
        return sessionVersions.getOrDefault(playerUuid, 0L);
    }

    public void logout(UUID playerUuid)
    {
        synchronized (sessions)
        {
            sessionVersions.merge(playerUuid, 1L, Math::addExact);
            sessions.remove(Objects.requireNonNull(playerUuid, "playerUuid"));
        }
    }

    public synchronized void clearSessions()
    {
        synchronized (sessions)
        {
            sessionVersions.replaceAll((player, version) -> Math.addExact(version, 1));
            sessions.clear();
        }
    }

    /**
     * Only the creator may delete the account. All its active login sessions are then invalidated.
     */
    public synchronized Optional<ServerUser> remove(UUID ownerUuid, UUID accountId)
    {
        requireLocalDirectory();
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        ServerUser user = get(accountId).orElse(null);
        if (user == null)
        {
            return Optional.empty();
        }
        if (!user.getOwnerUuid().equals(ownerUuid))
        {
            throw new SecurityException("Only the account creator may delete it");
        }
        if (linking.containsKey(accountId))
        {
            throw new IllegalStateException("Account is currently linking to the exchange");
        }
        Map<UUID, ServerUser> next = new HashMap<>(directoryIndex.source());
        next.remove(accountId);
        persist(next);
        publishUsers(next);
        createdExchangeIds.remove(accountId);
        sessions.values().removeIf(accountId::equals);
        return Optional.of(user);
    }

    /**
     * Requires the old password, stores a fresh salted hash, and invalidates existing sessions.
     */
    public synchronized boolean changePassword(String username, String oldPassword, String newPassword)
    {
        ServerUser user = authenticate(username, oldPassword).orElse(null);
        if (user == null)
        {
            return false;
        }
        ServerUser replacement = new ServerUser(user.getDisplayName(), user.getUsername(), newPassword,
                                                user.getUuid(), user.getOwnerUuid()).withProfile(user.getProfile().orElse(null));
        replaceCredential(user, replacement);
        return true;
    }

    private void replaceCredential(ServerUser user, ServerUser replacement)
    {
        if (user.getExchangeUserId() != 0)
        {
            replacement = replacement.withExchangeUserId(user.getExchangeUserId());
        }
        Map<UUID, ServerUser> next = new HashMap<>(directoryIndex.source());
        next.put(user.getUuid(), replacement);
        persist(next);
        publishUsers(next);
        synchronized (sessions)
        {
            sessions.values().removeIf(user.getUuid()::equals);
        }
    }

    private Optional<ServerUser> getSessionAccount(UUID playerUuid)
    {
        UUID account = sessions.get(playerUuid);
        return account == null ? Optional.empty() : Optional.ofNullable(directoryIndex.source().get(account)).filter(accessAllowed);
    }

    private void validateRegistration(UUID ownerUuid, String username)
    {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        if (findByUsername(username).isPresent())
        {
            throw new IllegalArgumentException("Username is already registered");
        }
        if (getOwnedAccounts(ownerUuid).size() >= MAX_ACCOUNTS_PER_UUID)
        {
            throw new IllegalStateException("A player UUID may create at most three accounts");
        }
    }

    private static String normalizeUsername(String username)
    {
        Objects.requireNonNull(username, "username");
        if (username.isBlank() || username.codePoints().anyMatch(Character::isWhitespace))
        {
            throw new IllegalArgumentException("Username must not be blank or contain whitespace");
        }
        return username.toLowerCase(Locale.ROOT);
    }

    private void load()
    {
        if (Files.exists(storageFile))
        {
            try
            {
                acceptLoaded(UserStorage.read(storageFile));
                return;
            } catch (IOException error)
            {
                throw new UncheckedIOException("Cannot load binary account registry", error);
            }
        }
        if (!Files.exists(legacyFile))
        {
            return;
        }
        try (var reader = Files.newBufferedReader(legacyFile, StandardCharsets.UTF_8))
        {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.get("version").getAsInt() != 1)
            {
                throw new IllegalArgumentException("Unsupported account storage version");
            }
            var loaded = new ArrayList<ServerUser>();
            for (var entry : root.getAsJsonArray("users"))
            {
                JsonObject data = entry.getAsJsonObject();
                ServerUser user = ServerUser.restore(data.get("displayName").getAsString(),
                                                     data.get("username").getAsString(), data.get("passwordHash").getAsString(),
                                                     UUID.fromString(data.get("uuid").getAsString()),
                                                     UUID.fromString(data.get("ownerUuid").getAsString()));
                if (data.has("exchangeUserId") && data.get("exchangeUserId").getAsLong() != 0)
                {
                    user = user.withExchangeUserId(data.get("exchangeUserId").getAsLong());
                }
                if (data.has("profile"))
                {
                    user = user.withProfile(data.getAsJsonObject("profile"));
                }
                loaded.add(user);
            }
            acceptLoaded(loaded);
            persist(directoryIndex.source()); // One-way migration; keep the legacy file untouched as a backup.
        } catch (IOException exception)
        {
            throw new UncheckedIOException("Cannot load account registry", exception);
        } catch (RuntimeException exception)
        {
            throw new IllegalStateException("Invalid account registry: " + storageFile, exception);
        }
    }

    private void acceptLoaded(List<ServerUser> loaded)
    {
        var values = new HashMap<UUID, ServerUser>();
        var usernames = new HashSet<String>();
        var exchangeIds = new HashSet<Long>();
        var owners = new HashMap<UUID, Integer>();
        for (var user : loaded)
        {
            Objects.requireNonNull(user.getOwnerUuid(), "ownerUuid");
            if (values.put(user.getUuid(), user) != null || !usernames.add(normalizeUsername(user.getUsername()))
                || user.getExchangeUserId() != 0 && !exchangeIds.add(user.getExchangeUserId()))
            {
                throw new IllegalArgumentException("Duplicate persisted account or exchange user ID");
            }
            if (owners.merge(user.getOwnerUuid(), 1, Integer::sum) > MAX_ACCOUNTS_PER_UUID)
            {
                throw new IllegalStateException("A player UUID may create at most three accounts");
            }
        }
        publishUsers(values);
    }

    /**
     * Persist and force the binary snapshot before publishing any account changes.
     */
    private void persist(Map<UUID, ServerUser> users)
    {
        if (storageFile == null)
        {
            return;
        }
        try
        {
            UserStorage.write(storageFile, users.values());
        } catch (IOException error)
        {
            throw new UncheckedIOException("Cannot save binary account registry", error);
        }
    }

    private void requireLocalDirectory()
    {
        if (directory != null) throw new IllegalStateException("Use asynchronous account operations with REMOTE mode");
    }
}
