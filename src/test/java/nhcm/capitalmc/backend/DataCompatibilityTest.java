package nhcm.capitalmc.backend;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DataCompatibilityTest
{
    @TempDir Path directory;

    @Test
    void freshDataRecordsVersionAndReopeningDoesNotWarn() throws Exception
    {
        assertTrue(DataCompatibility.inspect(directory).warnings().isEmpty());
        assertTrue(Files.readString(directory.resolve("data-version.json")).contains(DataCompatibility.version()));
        assertTrue(DataCompatibility.inspect(directory).warnings().isEmpty());
    }

    @Test
    void oldAndUnversionedDataWarnWithoutRewritingEvidence() throws Exception
    {
        Path marker = directory.resolve("data-version.json");
        String old = "{\"schema\":1,\"capitalmcVersion\":\"old-release\"}";
        Files.writeString(marker, old);
        assertEquals("old-release", DataCompatibility.inspect(directory).warnings().getFirst().stored());
        assertEquals(old, Files.readString(marker));
        Files.delete(marker);
        Files.writeString(directory.resolve("services.json"), "{\"original\":true}");
        assertEquals("unknown", DataCompatibility.inspect(directory).warnings().getFirst().stored());
        assertFalse(Files.exists(marker));
        assertEquals("{\"original\":true}", Files.readString(directory.resolve("services.json")));
    }

    @Test
    void remoteVersionDifferencesAreRetainedAndDeduplicated() throws Exception
    {
        var status = DataCompatibility.inspect(directory);
        var info = new JsonObject();
        info.addProperty("capitalmcVersion", "other-release");
        status.remote(info);
        status.remote(info);
        assertEquals(1, status.warnings().size());
        assertEquals("REMOTE", status.warnings().getFirst().source());
    }
}
