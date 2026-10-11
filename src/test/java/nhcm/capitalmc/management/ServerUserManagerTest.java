package nhcm.capitalmc.management;

import com.google.gson.JsonObject;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.service.ApplicationExecutors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ServerUserManagerTest
{
    @Test
    void metadataOverloadRetainsTheCreatedNativeUserAndDoesNotStrandBinding() throws Exception
    {
        var manager = new ServerUserManager(directory.resolve("overloaded-users.json"));
        var user = manager.register("Trader", "trader", "", UUID.randomUUID());
        var created = new CompletableFuture<Long>();
        var started = new CountDownLatch(1);
        var creations = new AtomicLong();
        var binding = manager.connectExchangeUsers(new ServerUserManager.ExchangeUsers()
        {
            public CompletableFuture<Long> create()
            {
                creations.incrementAndGet();
                started.countDown();
                return created;
            }

            public CompletableFuture<Void> validate(long id)
            {
                assertEquals(123, id);
                return CompletableFuture.completedFuture(null);
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var occupied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ApplicationExecutors.IO.execute(() -> {
            occupied.countDown();
            try
            {
                release.await();
            }
            catch (InterruptedException failure)
            {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(occupied.await(5, TimeUnit.SECONDS));
        try
        {
            var executor = (ThreadPoolExecutor) ApplicationExecutors.IO;
            while (executor.getQueue().remainingCapacity() > 0) executor.execute(() -> {});
            created.complete(123L);
            assertThrows(ExecutionException.class, () -> binding.get(5, TimeUnit.SECONDS));
        }
        finally
        {
            release.countDown();
        }
        while (((ThreadPoolExecutor) ApplicationExecutors.IO).getQueue().remainingCapacity() == 0) Thread.yield();
        ApplicationExecutors.io(() -> null).get(5, TimeUnit.SECONDS);
        assertEquals(123, manager.ensureExchangeUser(user.getUuid()).get(5, TimeUnit.SECONDS).getExchangeUserId());
        assertEquals(1, creations.get());
    }

    @Test
    void directoryIndexesPublishWithAccountsAndSurviveReload()
    {
        var manager = manager();
        var owner = UUID.randomUUID();
        var first = manager.register("First", "First", "", owner);
        var bound = manager.ensureExchangeUser(first.getUuid()).join();
        assertEquals(bound, manager.findByUsername("FIRST").orElseThrow());
        assertEquals(bound, manager.findByExchangeId(bound.getExchangeUserId()).orElseThrow());
        assertEquals(List.of(bound), manager.getOwnedAccounts(owner));
        var before = manager.getUsers();
        manager.register("Second", "second", "", UUID.randomUUID());
        assertEquals(1, before.size());
        var restored = new ServerUserManager(directory.resolve("users.json"));
        assertEquals(bound.getUuid(), restored.findByExchangeId(bound.getExchangeUserId()).orElseThrow().getUuid());
        assertEquals(2, restored.getUsers().size());
    }

    @TempDir
    Path directory;

    private ServerUserManager manager()
    {
        var manager = new ServerUserManager(directory.resolve("users.json"));
        AtomicLong ids = new AtomicLong();
        manager.connectExchangeUsers(new ServerUserManager.ExchangeUsers()
        {
            public CompletableFuture<Long> create()
            {
                return CompletableFuture.completedFuture(ids.incrementAndGet());
            }

            public CompletableFuture<Void> validate(long id)
            {
                return CompletableFuture.completedFuture(null);
            }
        }).join();
        return manager;
    }

    @Test
    void bansRejectLoginsAndVerifiedContinuationAndRevokeAllSessions()
    {
        var manager = manager();
        var user = manager.register("Restricted", "restricted", "", UUID.randomUUID());
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        manager.activateVerified(first, user);
        manager.activateVerified(second, user);
        long version = manager.getSessionVersion(first);
        assertEquals(2, manager.playersUsing(user.getUuid()).size());
        manager.setAccessPolicy(candidate -> !candidate.getUuid().equals(user.getUuid()));
        assertTrue(manager.getLoggedInUser(first).isEmpty());
        assertThrows(CompletionException.class, () -> manager.authenticateAsync("restricted", "").join());
        assertThrows(IllegalStateException.class, () -> manager.activateVerified(UUID.randomUUID(), user));
        manager.revokeSessions(user.getUuid());
        assertTrue(manager.getSessionVersion(first) > version);
        manager.setAccessPolicy(candidate -> true);
        assertTrue(manager.getLoggedInUser(first).isEmpty());
        assertTrue(manager.getLoggedInUser(second).isEmpty());
        assertEquals(user.getUuid(), manager.authenticateAsync("restricted", "").join().orElseThrow().getUuid());
    }

    @Test
    void emptyCredentialSurvivesStorageAndAllowsAnotherPlayerToLoginWithoutPassword()
    {
        var manager = manager();
        UUID creator = UUID.randomUUID(), visitor = UUID.randomUUID();
        var user = manager.registerAsync("Shared", "shared", "", creator).join();
        assertFalse(user.hasPassword());
        assertTrue(user.verifyPassword(""));
        assertFalse(user.verifyPassword("wrong"));
        assertEquals(user.getUuid(), manager.loginAsync(visitor, "SHARED", "").join().orElseThrow().getUuid());
        assertNotEquals(creator, visitor);
        assertNotEquals(creator, user.getUuid());
        var restored = new ServerUserManager(directory.resolve("users.json"));
        assertFalse(restored.findByUsername("shared").orElseThrow().hasPassword());
        assertTrue(restored.getLoggedInUser(visitor).isEmpty());
    }

    @Test
    void registrationLimitsAndCaseInsensitiveUniquenessApplyConcurrently()
    {
        var manager = manager();
        UUID owner = UUID.randomUUID();
        manager.registerAsync("One", "one", "", owner).join();
        manager.registerAsync("Two", "two", "", owner).join();
        manager.registerAsync("Three", "three", "", owner).join();
        assertThrows(CompletionException.class, () -> manager.registerAsync("Four", "four", "", owner).join());
        assertThrows(CompletionException.class, () -> manager.registerAsync("Duplicate", "ONE", "", UUID.randomUUID()).join());
        assertEquals(3, manager.getOwnedAccounts(owner).size());
    }

    @Test
    void wrongPasswordDoesNotReplaceAnExistingSession()
    {
        var manager = manager();
        UUID player = UUID.randomUUID();
        var first = manager.registerAsync("First", "first", "", player).join();
        manager.registerAsync("Protected", "protected", "secret", player).join();
        manager.loginAsync(player, "first", "").join();
        assertTrue(manager.loginAsync(player, "protected", "wrong").join().isEmpty());
        assertEquals(first.getUuid(), manager.getLoggedInUser(player).orElseThrow().getUuid());
    }

    @Test
    void changingCredentialPreservesProfileAndBindingAndInvalidatesAllSessions()
    {
        var manager = manager();
        UUID owner = UUID.randomUUID(), visitor = UUID.randomUUID();
        var profile = new JsonObject();
        profile.addProperty("name", "Creator");
        var user = manager.registerAsync("Account", "account", "secret", owner, profile).join();
        profile.addProperty("name", "Mutated");
        manager.loginAsync(owner, "account", "secret").join();
        manager.loginAsync(visitor, "account", "secret").join();
        assertTrue(manager.changePasswordAsync("account", "secret", "").join());
        assertTrue(manager.getLoggedInUser(owner).isEmpty());
        assertTrue(manager.getLoggedInUser(visitor).isEmpty());
        var updated = manager.findByUsername("account").orElseThrow();
        assertEquals(user.getExchangeUserId(), updated.getExchangeUserId());
        assertFalse(updated.hasPassword());
        assertEquals("Creator", updated.getProfile().orElseThrow().get("name").getAsString());
        updated.getProfile().orElseThrow().addProperty("name", "Visitor");
        var loaded = new ServerUserManager(directory.resolve("users.json")).findByUsername("account").orElseThrow();
        assertEquals("Creator", loaded.getProfile().orElseThrow().get("name").getAsString());
        assertFalse(manager.changePasswordAsync("account", "secret", "new").join());
    }

    @Test
    void disconnectDuringBackgroundVerificationCannotRecreateSession() throws Exception
    {
        var manager = new ServerUserManager();
        UUID player = UUID.randomUUID();
        CountDownLatch started = new CountDownLatch(1), proceed = new CountDownLatch(1);
        var user = new ServerUser("Slow", "slow", "", UUID.randomUUID(), player)
        {
            @Override
            public boolean verifyPassword(String password)
            {
                assertTrue(Thread.currentThread().getName().startsWith("capitalmc-credentials-"));
                started.countDown();
                try
                {
                    assertTrue(proceed.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException error)
                {
                    throw new AssertionError(error);
                }
                return true;
            }
        };
        manager.register(user);
        var login = manager.loginAsync(player, "slow", "anything");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        manager.logout(player);
        proceed.countDown();
        assertTrue(login.join().isEmpty());
        assertTrue(manager.getLoggedInUser(player).isEmpty());
    }

    @Test
    void verifiedSnapshotCannotActivateAfterPasswordChanged()
    {
        var manager = manager();
        UUID player = UUID.randomUUID();
        manager.registerAsync("Account", "account", "", player).join();
        var verified = manager.authenticateAsync("account", "").join().orElseThrow();
        assertTrue(manager.changePasswordAsync("account", "", "new").join());
        assertThrows(IllegalStateException.class, () -> manager.activateVerified(player, verified));
        assertTrue(manager.getLoggedInUser(player).isEmpty());
    }

    @Test
    void logoutAndLoginToTheSameAccountStillInvalidateTheOldSessionVersion()
    {
        var manager = manager();
        UUID player = UUID.randomUUID();
        manager.registerAsync("Account", "account", "", player).join();
        manager.loginAsync(player, "account", "").join();
        long original = manager.getSessionVersion(player);
        UUID account = manager.getLoggedInUser(player).orElseThrow().getUuid();
        manager.logout(player);
        manager.loginAsync(player, "account", "").join();
        assertEquals(account, manager.getLoggedInUser(player).orElseThrow().getUuid());
        assertTrue(manager.getSessionVersion(player) > original);
    }
}
