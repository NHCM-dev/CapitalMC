package nhcm.capitalmc.backend;

import com.google.gson.JsonObject;
import nhcm.advmatch.Exchange;
import nhcm.advmatch.command.account.UpdateBalance;
import nhcm.advmatch.command.read.ReadBalance;
import nhcm.advmatch.command.persistence.Flush;
import nhcm.advmatch.model.*;
import nhcm.advmatch.remote.*;
import nhcm.capitalmc.management.ServerUserManager;
import nhcm.capitalmc.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceToolTest
{
    @TempDir Path directory;

    @Test
    void configurationRejectsInvalidValuesWithoutChangingFile() throws Exception
    {
        PersistenceSettings.load(directory);
        var before = Files.readString(directory.resolve("persistence.json"));
        assertThrows(IllegalArgumentException.class, () -> PersistenceAdministration.configure(directory, "flushIntervalMilliseconds", "0"));
        assertThrows(IllegalArgumentException.class, () -> PersistenceAdministration.configure(directory, "directory", "../outside"));
        assertThrows(IllegalArgumentException.class, () -> PersistenceAdministration.configure(directory, "unknown", "1"));
        assertEquals(before, Files.readString(directory.resolve("persistence.json")));
        PersistenceAdministration.configure(directory, "exchangeSnapshotEverySequences", "1234");
        assertEquals(1234, PersistenceSettings.load(directory).exchangeSnapshotEverySequences);
    }

    @Test
    void tamperedOrExtraBackupFilesAndUnsafeNamesAreRejected() throws Exception
    {
        Path root = directory.resolve("data");
        Files.createDirectories(root);
        Files.writeString(root.resolve("evidence.bin"), "evidence");
        Path backup = PersistenceTool.save(root, "test");
        PersistenceTool.verifyBackup(backup);
        assertThrows(IllegalArgumentException.class, () -> PersistenceTool.save(root, "../outside"));
        assertThrows(FileAlreadyExistsException.class, () -> PersistenceTool.save(root, "test"));
        Files.writeString(backup.resolve("extra"), "injected");
        assertThrows(IOException.class, () -> PersistenceTool.verifyBackup(backup));
        Files.delete(backup.resolve("extra"));
        Files.writeString(backup.resolve("evidence.bin"), "tampered");
        assertThrows(IOException.class, () -> PersistenceTool.verifyBackup(backup));
        Path target = directory.resolve("failed-restore");
        assertThrows(IOException.class, () -> PersistenceTool.load(backup, target));
        assertFalse(Files.exists(target));
    }

    @Test
    void manifestCannotReadOutsideBackupOrOverwriteExistingData() throws Exception
    {
        Path root = directory.resolve("data");
        Files.createDirectories(root);
        Files.writeString(root.resolve("evidence.bin"), "evidence");
        Path backup = PersistenceTool.save(root, "test");
        Files.writeString(backup.resolve("backup.sha256"), "0".repeat(64) + " ../outside\n");
        assertThrows(IOException.class, () -> PersistenceTool.verifyBackup(backup));
        assertThrows(FileAlreadyExistsException.class, () -> PersistenceTool.load(backup, root));
        assertEquals("evidence", Files.readString(root.resolve("evidence.bin")));
    }

    @Test
    void completeBackupLoadsExactLedgerAndMetadataIntoNewRoot() throws Exception
    {
        Path root = directory.resolve("data");
        Files.createDirectories(root);
        DataCompatibility.inspect(root);
        long account, asset;
        try (var exchange = new Exchange(ExchangeRuntime.nativeConfig(root.resolve("exchange"))))
        {
            var users = new ServerUserManager(root.resolve("users.dat"));
            users.connectExchange(exchange).join();
            var user = users.registerAsync("Recovery", "recovery", "", UUID.randomUUID()).join();
            asset = exchange.assets().create("test", "Test", 0).join().id();
            account = exchange.users().get(user.getExchangeUserId()).spotAccount().join().id();
            exchange.submit(new UpdateBalance(account, asset, 123456789012345L, 1)).join();
            var state = new ServiceState(root.resolve("services.json"));
            state.update(data -> data.addProperty("custodyEvidence", "preserved"));
            var storage = new PersistenceAdministration(exchange, root);
            var request = new JsonObject();
            request.addProperty("action", "snapshot-save");
            storage.execute(request).join();
            exchange.submit(new UpdateBalance(account, asset, 7, 2)).join();
            state.update(data -> data.addProperty("postSnapshot", "WAL tail"));
            exchange.submit(new Flush()).join();
            PersistenceTool.save(root, "ready");
        }
        Path restored = PersistenceTool.load(PersistenceTool.backups(root).resolve("ready"), directory.resolve("restored"));
        try (var exchange = new Exchange(ExchangeRuntime.nativeConfig(restored.resolve("exchange"))))
        {
            var balance = exchange.submit(new ReadBalance(new ObservationQuery().withAccountId(account).withAssetId(asset))).join().values().getFirst();
            assertEquals(123456789012352L, balance.active());
            assertTrue(exchange.audit().report(64).join().conserved());
            var users = new ServerUserManager(restored.resolve("users.dat"));
            assertTrue(users.findByUsername("recovery").isPresent());
            var metadata = new ServiceState(restored.resolve("services.json")).snapshot();
            assertEquals("preserved", metadata.get("custodyEvidence").getAsString());
            assertEquals("WAL tail", metadata.get("postSnapshot").getAsString());
        }
    }

    @Test
    void remoteStorageCommandsUseOwnerFilesAndCheckpointItsLedger() throws Exception
    {
        Path root = directory.resolve("owner");
        Files.createDirectories(root);
        DataCompatibility.inspect(root);
        try (var exchange = new Exchange(ExchangeRuntime.nativeConfig(root.resolve("exchange"))))
        {
            var users = new ServerUserManager(root.resolve("users.dat"));
            users.connectExchange(exchange).join();
            try (var coordinator = new BackendCoordinator(exchange, users, root);
                 var endpoint = new ExchangeServer(exchange, new ExchangeServer.Options("127.0.0.1", 0,
                         "storage-test-012345678901234567890123456789", coordinator.exchangeId(), false, 8, 64, 4194304)))
            {
                coordinator.expose(endpoint);
                try (var channel = new RemoteChannel(new RemoteOptions("127.0.0.1", endpoint.port(), "storage-gateway", "storage-test-012345678901234567890123456789")))
                {
                    var client = new BackendClient(channel);
                    var request = new JsonObject();
                    request.addProperty("action", "snapshot-save");
                    assertTrue(client.call(BackendClient.PERSISTENCE, request).join().getAsJsonObject().get("enabled").getAsBoolean());
                    request.addProperty("action", "snapshot-list");
                    assertFalse(client.call(BackendClient.PERSISTENCE, request).join().getAsJsonObject().getAsJsonArray("files").isEmpty());
                    request.addProperty("action", "config-set");
                    request.addProperty("field", "exchangeSnapshotEverySequences");
                    request.addProperty("value", "4567");
                    client.call(BackendClient.PERSISTENCE, request).join();
                    assertEquals(4567, PersistenceSettings.load(root).exchangeSnapshotEverySequences);
                    request.addProperty("action", "verify");
                    assertTrue(client.call(BackendClient.PERSISTENCE, request).join().getAsJsonObject().get("verified").getAsBoolean());
                }
            }
        }
    }
}
