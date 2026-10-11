package nhcm.capitalmc.backend;

import com.google.gson.*;
import nhcm.advmatch.Exchange;
import nhcm.advmatch.command.persistence.*;
import nhcm.capitalmc.service.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Persistence operations run on the owner, including calls from remote gateways. */
public class PersistenceAdministration
{
    public static final List<String> FIELDS = List.of("directory", "exchangeSnapshotEverySequences", "exchangeRetainedSnapshots",
            "flushEveryCommands", "flushIntervalMilliseconds", "metadataSnapshotEverySequences", "metadataSnapshotWalBytes", "metadataRetainedSnapshots");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Exchange exchange;
    private final Path root;

    public PersistenceAdministration(Exchange exchange, Path root)
    {
        this.exchange = exchange;
        this.root = root.toAbsolutePath().normalize();
    }

    public CompletableFuture<JsonObject> execute(JsonObject request)
    {
        String action = request.get("action").getAsString();
        return switch (action)
        {
            case "status" -> exchange.submit(new ReadPersistenceStatus()).thenApply(status -> JSON.toJsonTree(status).getAsJsonObject());
            case "flush" -> exchange.submit(new Flush()).thenApply(status -> JSON.toJsonTree(status).getAsJsonObject());
            case "snapshot-save" -> exchange.submit(new Checkpoint()).thenCompose(status -> ApplicationExecutors.io(() -> {
                try
                {
                    checkpointMetadata(root);
                    return JSON.toJsonTree(status).getAsJsonObject();
                }
                catch (IOException error)
                {
                    throw new UncheckedIOException(error);
                }
            }));
            case "snapshot-list", "wal-list", "backup-list", "config-show" -> ApplicationExecutors.io(() -> read(action));
            case "config-set" -> ApplicationExecutors.io(() -> {
                try
                {
                    configure(root, request.get("field").getAsString(), request.get("value").getAsString());
                    return read("config-show");
                }
                catch (IOException error)
                {
                    throw new UncheckedIOException(error);
                }
            });
            case "verify" -> exchange.audit().report(64).thenCompose(audit -> ApplicationExecutors.io(() -> {
                try
                {
                    verifyMetadata(root);
                    if (!audit.conserved() || !audit.consistent() || audit.issueCount() != 0)
                        throw new IllegalStateException("cli.persistence.audit_failed");
                    var result = new JsonObject();
                    result.addProperty("verified", true);
                    return result;
                }
                catch (IOException error)
                {
                    throw new UncheckedIOException(error);
                }
            }));
            default -> CompletableFuture.failedFuture(new IllegalArgumentException("cli.help.unknown"));
        };
    }

    private JsonObject read(String action)
    {
        try
        {
            var result = new JsonObject();
            if (action.equals("config-show"))
            {
                result.add("settings", JSON.toJsonTree(PersistenceSettings.load(root)));
                return result;
            }
            var names = new JsonArray();
            if (action.equals("backup-list"))
            {
                Path backups = PersistenceTool.backups(root);
                if (Files.isDirectory(backups))
                {
                    try (var files = Files.list(backups))
                    {
                        files.filter(path -> Files.isRegularFile(path.resolve("backup.sha256"))).sorted().limit(1000)
                             .forEach(path -> names.add(path.getFileName().toString()));
                    }
                }
            }
            else
            {
                try (var files = Files.walk(root))
                {
                    for (Path file : files.filter(Files::isRegularFile).filter(path -> action.equals("snapshot-list")
                            ? path.getFileName().toString().matches("snapshot-[0-9]+\\.bin")
                            : path.getFileName().toString().endsWith(".wal")).sorted().limit(1000).toList())
                    {
                        var row = new JsonObject();
                        row.addProperty("path", root.relativize(file).toString());
                        row.addProperty("bytes", Files.size(file));
                        names.add(row);
                    }
                }
            }
            result.add("files", names);
            return result;
        }
        catch (IOException error)
        {
            throw new UncheckedIOException(error);
        }
    }

    public static void verifyMetadata(Path root) throws IOException
    {
        var settings = PersistenceSettings.load(root);
        for (Path pin : pins(root))
        {
            Path directory = pinned(root, pin);
            if (pin.getFileName().toString().equals("exchange.store-path")) continue;
            var store = new BinaryStateStore(directory, settings.metadataOptions());
            if (!store.initialized() && pin.getFileName().toString().equals("data-version.store-path")) continue;
            if (!store.initialized()) throw new IOException("Missing initialized store: " + pin.getFileName());
        }
    }

    public static void checkpointMetadata(Path root) throws IOException
    {
        var settings = PersistenceSettings.load(root);
        for (Path pin : pins(root))
        {
            if (pin.getFileName().toString().equals("exchange.store-path")) continue;
            boolean initialized = BinaryStateStore.checkpointStored(pinned(root, pin), settings.metadataOptions());
            if (!initialized && !pin.getFileName().toString().equals("data-version.store-path"))
                throw new IOException("Missing initialized store: " + pin.getFileName());
        }
    }

    private static List<Path> pins(Path root) throws IOException
    {
        try (var files = Files.list(root))
        {
            return files.filter(path -> path.getFileName().toString().endsWith(".store-path")).toList();
        }
    }

    public static Path pinned(Path root, Path pin) throws IOException
    {
        root = root.toAbsolutePath().normalize();
        String value = Files.readString(pin).strip();
        Path selected = root.resolve(value).normalize();
        if (value.isBlank() || Path.of(value).isAbsolute() || !selected.startsWith(root) || selected.equals(root)
            || !selected.toRealPath().startsWith(root.toRealPath()))
            throw new IOException("Invalid store pin: " + pin.getFileName());
        return selected;
    }

    public static void configure(Path root, String field, String value) throws IOException
    {
        if (!FIELDS.contains(field)) throw new IllegalArgumentException("cli.help.unknown");
        var settings = PersistenceSettings.load(root);
        try
        {
            var member = PersistenceSettings.class.getField(field);
            if (member.getType() == String.class) member.set(settings, value);
            else if (member.getType() == int.class) member.setInt(settings, Math.toIntExact(Long.parseLong(value)));
            else member.setLong(settings, Long.parseLong(value));
        }
        catch (ReflectiveOperationException error)
        {
            throw new IllegalStateException(error);
        }
        settings.validate();
        Path temporary = Files.createTempFile(root, "persistence-config-", ".tmp");
        try
        {
            Files.writeString(temporary, JSON.toJson(settings));
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
            {
                channel.force(true);
            }
            Files.move(temporary, root.resolve("persistence.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }
    }
}
