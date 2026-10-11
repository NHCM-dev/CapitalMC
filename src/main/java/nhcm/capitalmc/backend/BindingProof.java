package nhcm.capitalmc.backend;

import com.google.gson.JsonObject;

/** Financially meaningful custody configuration, represented without Minecraft objects. */
public class BindingProof
{
    public static JsonObject asset(JsonObject root, long id)
    {
        JsonObject entry = null;
        if (root.has("assets"))
        {
            for (var value : root.getAsJsonArray("assets"))
            {
                if (value.getAsJsonObject().get("id").getAsLong() == id) entry = value.getAsJsonObject();
            }
        }
        if (entry == null) throw new IllegalStateException("ui.custody.binding_changed");
        var proof = new JsonObject();
        proof.add("item", entry.get("item").deepCopy());
        proof.add("electronic", entry.get("electronic"));
        proof.addProperty("enabled", !entry.has("enabled") || entry.get("enabled").getAsBoolean());
        var units = root.has("assetUnits") ? root.getAsJsonObject("assetUnits").getAsJsonObject(Long.toString(id)) : null;
        if (units == null)
        {
            units = new JsonObject();
            units.addProperty("size", "1");
            units.addProperty("minimumDeposit", 1);
            units.addProperty("minimumWithdrawal", 1);
        }
        proof.add("units", units.deepCopy());
        return proof;
    }
}
