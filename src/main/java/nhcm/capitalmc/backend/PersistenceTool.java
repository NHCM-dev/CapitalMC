package nhcm.capitalmc.backend;

import com.google.gson.JsonObject;
import nhcm.advmatch.Exchange;
import nhcm.advmatch.command.persistence.Checkpoint;
import nhcm.advmatch.command.persistence.Flush;
import nhcm.advmatch.command.persistence.ReadPersistenceStatus;
import nhcm.capitalmc.service.ApplicationExecutors;

import java.io.IOException;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.*;

/** Offline full-store backups. Loading creates a separate, previously absent data root. */
public class PersistenceTool
{
    public static void main(String[] args) throws Exception
    {
        if (args.length < 2 || args[0].equals("help"))
        {
            System.out.println("java -jar CapitalMC-exchange-server.jar --storage <action> <data-root> [arguments]");
            System.out.println("Actions: status, flush, snapshot-save, snapshot-list, wal-list, verify, config-show,");
            System.out.println("         config-set <field> <value>, backup-save <name>, wal-save <name>, backup-list, load <name> <new-data-root>");
            System.out.println("Save/load/verify require the owner to be stopped. A backup includes snapshots, WAL, accounts and metadata.");
            System.out.println("Load never overwrites an existing directory. Stop all gateways and restore their world/custody state consistently before activation.");
            return;
        }
        String action = args[0];
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("Data root does not exist");
        try
        {
            if (action.equals("backup-list"))
            {
                if (args.length != 2) throw new IllegalArgumentException("Unexpected arguments");
                Path directory = backups(root);
                if (Files.isDirectory(directory))
                {
                    try (var files = Files.list(directory))
                    {
                        files.filter(path -> Files.isRegularFile(path.resolve("backup.sha256"))).sorted()
                             .forEach(path -> System.out.println(path.getFileName()));
                    }
                }
                return;
            }
            // Native exclusive writer lock rejects a live owner before any backup, configuration or load.
            PersistenceAdministration.pinned(root, root.resolve("exchange.store-path"));
            try (var exchange = new Exchange(ExchangeRuntime.nativeConfig(root.resolve("exchange"))))
            {
                PersistenceAdministration.verifyMetadata(root);
                var storage = new PersistenceAdministration(exchange, root);
                if (action.equals("backup-save") || action.equals("wal-save"))
                {
                    if (args.length != 3) throw new IllegalArgumentException("backup-save requires a name");
                    verify(exchange);
                    if (action.equals("backup-save"))
                    {
                        exchange.submit(new Checkpoint()).join();
                        PersistenceAdministration.checkpointMetadata(root);
                    }
                    else
                    {
                        exchange.submit(new Flush()).join();
                    }
                    System.out.println(save(root, args[2]));
                }
                else if (action.equals("load"))
                {
                    if (args.length != 4) throw new IllegalArgumentException("load requires a backup name and a new data root");
                    Path target = Path.of(args[3]).toAbsolutePath().normalize();
                    if (target.startsWith(root)) throw new IOException("Restore target must be outside the active data root");
                    Path source = backups(root).resolve(name(args[2]));
                    System.out.println(load(source, target));
                    System.out.println("Prepared only; no running exchange was replaced. Activate after world/custody recovery.");
                }
                else
                {
                    if (!Set.of("status", "flush", "snapshot-save", "snapshot-list", "wal-list", "verify", "config-show", "config-set").contains(action))
                        throw new IllegalArgumentException("Unknown storage action");
                    if (args.length != (action.equals("config-set") ? 4 : 2)) throw new IllegalArgumentException("Unexpected arguments");
                    var request = new JsonObject();
                    request.addProperty("action", action);
                    if (action.equals("config-set"))
                    {
                        request.addProperty("field", args[2]);
                        request.addProperty("value", args[3]);
                    }
                    System.out.println(storage.execute(request).join());
                }
            }
        }
        finally
        {
            ApplicationExecutors.shutdown();
        }
    }

    public static Path backups(Path root)
    {
        root = root.toAbsolutePath().normalize();
        return root.resolveSibling(root.getFileName() + "-backups");
    }

    private static String name(String value)
    {
        if (!value.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid backup name");
        return value;
    }

    private static void verify(Exchange exchange)
    {
        var audit = exchange.audit().report(64).join();
        if (!audit.conserved() || !audit.consistent() || audit.issueCount() != 0)
            throw new IllegalStateException("Exchange audit failed");
        var status = exchange.submit(new ReadPersistenceStatus()).join();
        if (!status.enabled() || status.faulted()) throw new IllegalStateException("Persistence is disabled or faulted");
    }

    public static Path save(Path root, String name) throws IOException
    {
        Path parent = backups(root);
        Files.createDirectories(parent);
        Path destination = parent.resolve(name(name));
        if (Files.exists(destination)) throw new FileAlreadyExistsException(destination.toString());
        Path staging = Files.createTempDirectory(parent, ".pending-");
        copy(root, staging);
        var manifest = new StringBuilder();
        for (Path file : files(staging))
        {
            manifest.append(digest(file)).append(" ").append(staging.relativize(file).toString().replace('\\', '/')).append("\n");
        }
        Files.writeString(staging.resolve("backup.sha256"), manifest.toString(), StandardOpenOption.CREATE_NEW);
        try (var channel = FileChannel.open(staging.resolve("backup.sha256"), StandardOpenOption.WRITE))
        {
            channel.force(true);
        }
        forceDirectories(staging);
        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(parent);
        return destination;
    }

    public static Path load(Path source, Path destination) throws IOException
    {
        if (Files.exists(destination)) throw new FileAlreadyExistsException(destination.toString());
        verifyBackup(source);
        Path parent = destination.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path staging = Files.createTempDirectory(parent, ".restore-pending-");
        copy(source, staging);
        Files.delete(staging.resolve("backup.sha256"));
        // Validate replay in isolation. Failure leaves the old root untouched and never publishes the target.
        PersistenceAdministration.pinned(staging, staging.resolve("exchange.store-path"));
        PersistenceAdministration.verifyMetadata(staging);
        try (var exchange = new Exchange(ExchangeRuntime.nativeConfig(staging.resolve("exchange"))))
        {
            verify(exchange);
            exchange.submit(new Flush()).join();
        }
        forceDirectories(staging);
        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory(parent);
        return destination;
    }

    public static void verifyBackup(Path root) throws IOException
    {
        root = root.toAbsolutePath().normalize();
        var expected = new HashSet<Path>();
        for (String line : Files.readAllLines(root.resolve("backup.sha256")))
        {
            if (line.length() < 66 || line.charAt(64) != ' ') throw new IOException("Invalid backup manifest");
            Path file = root.resolve(line.substring(65)).normalize();
            if (!file.startsWith(root) || file.equals(root) || !file.toRealPath().startsWith(root.toRealPath())
                || !expected.add(file) || !digest(file).equals(line.substring(0, 64)))
                throw new IOException("Backup checksum/path mismatch");
        }
        var actual = new HashSet<>(files(root));
        actual.remove(root.resolve("backup.sha256"));
        if (!actual.equals(expected)) throw new IOException("Backup file set mismatch");
    }

    private static List<Path> files(Path root) throws IOException
    {
        try (var paths = Files.walk(root))
        {
            return paths.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static void copy(Path source, Path destination) throws IOException
    {
        source = source.toAbsolutePath().normalize();
        destination = destination.toAbsolutePath().normalize();
        if (destination.startsWith(source)) throw new IOException("Backup target must be outside the source root");
        try (var paths = Files.walk(source))
        {
            for (Path path : paths.toList())
            {
                if (Files.isSymbolicLink(path)) throw new IOException("Backup refuses symbolic links");
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else
                {
                    String relative = source.relativize(path).toString();
                    if (relative.indexOf('\n') >= 0 || relative.indexOf('\r') >= 0) throw new IOException("Invalid backup filename");
                    if (path.getFileName().toString().equals("writer.lock")) continue;
                    Files.copy(path, target);
                    try (var channel = FileChannel.open(target, StandardOpenOption.WRITE))
                    {
                        channel.force(true);
                    }
                }
            }
        }
    }

    private static String digest(Path file) throws IOException
    {
        try
        {
            var hash = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file))
            {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) >= 0) hash.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(hash.digest());
        }
        catch (java.security.NoSuchAlgorithmException error)
        {
            throw new IllegalStateException(error);
        }
    }

    private static void forceDirectory(Path directory) throws IOException
    {
        if (System.getProperty("os.name").startsWith("Windows")) return;
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ))
        {
            channel.force(true);
        }
    }

    private static void forceDirectories(Path root) throws IOException
    {
        if (System.getProperty("os.name").startsWith("Windows")) return;
        try (var paths = Files.walk(root))
        {
            for (Path directory : paths.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList())
            {
                forceDirectory(directory);
            }
        }
    }
}
