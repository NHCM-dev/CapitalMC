package nhcm.capitalmc.backend;

import com.google.gson.JsonObject;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface SharedMetadata
{
    Set<String> FIELDS = Set.of("assets", "assetUnits", "symbolIcons", "symbolLots", "issuerUser", "pegs",
                                "userRestrictions", "tickBandMigrated");

    CompletableFuture<JsonObject> read();

    CompletableFuture<JsonObject> commit(JsonObject expected, JsonObject next);

    static JsonObject select(JsonObject root)
    {
        var result = new JsonObject();
        for (String field : FIELDS)
        {
            if (root.has(field))
            {
                result.add(field, root.get(field).deepCopy());
            }
        }
        return result;
    }
}
