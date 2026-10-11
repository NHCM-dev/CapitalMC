package nhcm.capitalmc.management;

import com.google.gson.*;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.service.PersistenceSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class UserStorageTest
{
    @TempDir
    Path directory;

    @Test
    void legacyAccountsMigrateOnceKeepingHashIdentityBindingAndProfile() throws Exception
    {
        var user = new ServerUser("Legacy", "legacy", "secret", UUID.randomUUID(), UUID.randomUUID()).withExchangeUserId(42);
        var data = new JsonObject();
        data.addProperty("uuid", user.getUuid().toString());
        data.addProperty("ownerUuid", user.getOwnerUuid().toString());
        data.addProperty("displayName", user.getDisplayName());
        data.addProperty("username", user.getUsername());
        data.addProperty("passwordHash", user.getPasswordHash());
        data.addProperty("exchangeUserId", 42);
        var profile = new JsonObject();
        profile.addProperty("name", "Creator");
        data.add("profile", profile);
        var root = new JsonObject();
        root.addProperty("version", 1);
        var users = new JsonArray();
        users.add(data);
        root.add("users", users);
        Path legacy = directory.resolve("users.json");
        String original = root.toString();
        Files.writeString(legacy, original);
        var manager = new ServerUserManager(directory.resolve("users.dat"));
        var migrated = manager.findByUsername("legacy").orElseThrow();
        assertEquals(user.getUuid(), migrated.getUuid());
        assertEquals(user.getPasswordHash(), migrated.getPasswordHash());
        assertEquals(42, migrated.getExchangeUserId());
        assertTrue(migrated.verifyPassword("secret"));
        assertEquals("Creator", migrated.getProfile().orElseThrow().get("name").getAsString());
        assertEquals(original, Files.readString(legacy));
        assertTrue(Files.exists(PersistenceSettings.storeDirectory(directory.resolve("users.dat")).resolve("journal.wal")));
        Files.writeString(legacy, "not JSON");
        assertEquals(user.getUuid(), new ServerUserManager(legacy).findByUsername("legacy").orElseThrow().getUuid());
    }

    @Test
    void corruptedOrTruncatedBinaryFailsWithoutFallingBackToLegacy() throws Exception
    {
        Path file = directory.resolve("users.dat");
        var user = new ServerUser("One", "one", "", UUID.randomUUID(), UUID.randomUUID());
        UserStorage.write(file, List.of(user));
        Path stored = PersistenceSettings.storeDirectory(file).resolve("snapshot.head");
        byte[] valid = Files.readAllBytes(stored);
        byte[] corrupt = valid.clone();
        corrupt[corrupt.length - 1] ^= 1;
        Files.write(stored, corrupt);
        assertThrows(IOException.class, () -> UserStorage.read(file));
        Files.writeString(directory.resolve("users.json"), "{\"version\":1,\"users\":[]}");
        assertThrows(UncheckedIOException.class, () -> new ServerUserManager(file));
        Files.write(stored, Arrays.copyOf(valid, valid.length - 1));
        assertThrows(IOException.class, () -> UserStorage.read(file));
    }

    @Test
    void missingAccountManifestAndWalCannotResetTheRegistry() throws Exception
    {
        Path file = directory.resolve("users.dat");
        var user = new ServerUser("One", "one", "", UUID.randomUUID(), UUID.randomUUID());
        UserStorage.write(file, List.of(user));
        Path stored = PersistenceSettings.storeDirectory(file);
        Files.delete(stored.resolve("snapshot.head"));
        Files.delete(stored.resolve("journal.wal"));
        Files.writeString(directory.resolve("users.json"), "{\"version\":1,\"users\":[]}");
        assertThrows(UncheckedIOException.class, () -> new ServerUserManager(file));
    }

    @Test
    void failedWriteDoesNotChangePublishedAccountRegistry() throws Exception
    {
        Path file = directory.resolve("users.dat");
        var manager = new ServerUserManager(file);
        Files.createDirectory(file);
        var user = new ServerUser("One", "one", "", UUID.randomUUID(), UUID.randomUUID());
        assertThrows(UncheckedIOException.class, () -> manager.register(user));
        assertTrue(manager.findByUsername("one").isEmpty());
    }
}
