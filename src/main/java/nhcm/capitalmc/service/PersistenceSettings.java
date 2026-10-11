package nhcm.capitalmc.service;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** User-editable configuration, separate from business data and snapshot formats. */
public class PersistenceSettings
{
    public String directory = "persistence";
    public long exchangeSnapshotEverySequences = 10000;
    public int exchangeRetainedSnapshots = 2;
    public int flushEveryCommands = 256;
    public long flushIntervalMilliseconds = 10;
    public long metadataSnapshotEverySequences = 128;
    public long metadataSnapshotWalBytes = 4 * 1024 * 1024;
    public int metadataRetainedSnapshots = 2;

    public static PersistenceSettings load(Path root) throws IOException
    {
        Path file = root.resolve("persistence.json");
        var json = new GsonBuilder().setPrettyPrinting().create();
        var settings = Files.exists(file) ? json.fromJson(Files.readString(file), PersistenceSettings.class) : new PersistenceSettings();
        settings.validate();
        if (!Files.exists(file))
        {
            Files.createDirectories(root);
            Files.writeString(file, json.toJson(settings), StandardOpenOption.CREATE_NEW);
        }
        return settings;
    }

    public void validate()
    {
        if (directory == null || directory.isBlank() || Path.of(directory).getRoot() != null ||
            Path.of(directory).normalize().startsWith("..") || exchangeSnapshotEverySequences < 1 ||
            exchangeSnapshotEverySequences > 100000000 || exchangeRetainedSnapshots < 1 || exchangeRetainedSnapshots > 64 ||
            flushEveryCommands < 1 || flushIntervalMilliseconds < 1 || flushIntervalMilliseconds > 60000)
            throw new IllegalArgumentException("Invalid persistence configuration");
        metadataOptions();
    }

    public BinaryStateStore.Options metadataOptions()
    {
        return new BinaryStateStore.Options(metadataSnapshotEverySequences, metadataSnapshotWalBytes, metadataRetainedSnapshots);
    }

    public static Path storeDirectory(Path legacyFile) throws IOException
    {
        Path root = legacyFile.toAbsolutePath().getParent();
        var settings = load(root);
        String name = legacyFile.getFileName().toString().replaceFirst("\\.(json|dat)$", "");
        // Pin the location so editing the directory setting cannot silently select an empty ledger.
        Path pin = root.resolve(name + ".store-path");
        String selected =
            Files.isDirectory(legacyFile) && Files.exists(legacyFile.resolve("journal.wal")) ? name : settings.directory + "/" + name;
        if (Files.exists(pin))
        {
            selected = Files.readString(pin).strip();
        }
        else
        {
            Path temporary = Files.createTempFile(root, name + "-path-", ".tmp");
            try
            {
                Files.writeString(temporary, selected);
                try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
                {
                    channel.force(true);
                }
                Files.move(temporary, pin, StandardCopyOption.ATOMIC_MOVE);
                if (!System.getProperty("os.name").startsWith("Windows"))
                {
                    try (var channel = FileChannel.open(root, StandardOpenOption.READ))
                    {
                        channel.force(true);
                    }
                }
            }
            finally
            {
                Files.deleteIfExists(temporary);
            }
        }
        Path result = root.resolve(selected).normalize();
        if (!result.startsWith(root) || result.equals(root))
        {
            throw new IOException("Invalid persisted store location");
        }
        return result;
    }
}
