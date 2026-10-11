package nhcm.capitalmc.backend;

import com.google.gson.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import nhcm.capitalmc.service.BinaryStateStore;
import nhcm.capitalmc.service.PersistenceSettings;

/** Deployment provenance; separate from native WAL and compatible account file schemas. */
public class DataCompatibility
{
    public record Warning(String source, String stored, String running) {}

    private final List<Warning> warnings = new ArrayList<>();

    public static String version()
    {
        var properties = new Properties();
        try (var input = DataCompatibility.class.getResourceAsStream("/capitalmc-build.properties"))
        {
            if (input != null) properties.load(input);
            return properties.getProperty("version", "development");
        }
        catch (IOException error)
        {
            throw new IllegalStateException("Cannot read CapitalMC build version", error);
        }
    }

    public static DataCompatibility inspect(Path directory) throws IOException
    {
        var status = new DataCompatibility();
        Path marker = directory.resolve("data-version.json");
        var store = new BinaryStateStore(PersistenceSettings.storeDirectory(marker), PersistenceSettings.load(directory).metadataOptions());
        if (!store.initialized() && Files.exists(marker))
        {
            store.importState(JsonParser.parseString(Files.readString(marker)).getAsJsonObject(), 0);
        }
        if (store.initialized())
        {
            var data = store.snapshot();
            String stored = data.get("capitalmcVersion").getAsString();
            if (!stored.equals(version())) status.warn(directory.toString(), stored, version());
            if (data.get("schema").getAsInt() != 1) status.warn(directory.toString(), "schema " + data.get("schema"), "schema 1");
            return status;
        }
        boolean existing = Files.exists(directory.resolve("services.json")) || Files.exists(directory.resolve("users.dat"))
                           || Files.exists(directory.resolve("users.json")) || Files.exists(directory.resolve("exchange"))
                           || Files.exists(directory.resolve("shared-services.json"))
                           || Files.exists(directory.resolve("users.store-path")) || Files.exists(directory.resolve("exchange.store-path"))
                           || Files.exists(directory.resolve("services.store-path")) || Files.exists(directory.resolve("shared-services.store-path"));
        if (existing)
        {
            status.warn(directory.toString(), "unknown", version());
            return status; // Do not falsely stamp old or unknown data as belonging to this build.
        }
        Files.createDirectories(directory);
        var data = new JsonObject();
        data.addProperty("schema", 1);
        data.addProperty("capitalmcVersion", version());
        store.importState(data, 0);
        return status;
    }

    public synchronized void warn(String source, String stored, String running)
    {
        var warning = new Warning(source, stored, running);
        if (warnings.contains(warning)) return;
        warnings.add(warning);
        System.getLogger("CapitalMC").log(System.Logger.Level.WARNING,
                "CapitalMC data/version warning: " + source + " recorded " + stored + ", running " + running
                + ". Back up and review compatibility before changing data. Existing financial records have not been repaired or reset.");
    }

    public synchronized List<Warning> warnings()
    {
        return List.copyOf(warnings);
    }

    public void remote(JsonObject info)
    {
        String owner = info.has("capitalmcVersion") ? info.get("capitalmcVersion").getAsString() : "unknown";
        if (!owner.equals(version())) warn("REMOTE", owner, version());
        if (info.has("versionWarnings"))
        {
            for (var value : info.getAsJsonArray("versionWarnings"))
            {
                var warning = value.getAsJsonObject();
                warn("REMOTE / " + warning.get("source").getAsString(), warning.get("stored").getAsString(), warning.get("running").getAsString());
            }
        }
    }
}
