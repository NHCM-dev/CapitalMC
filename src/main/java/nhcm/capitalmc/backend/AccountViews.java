package nhcm.capitalmc.backend;

import com.google.gson.*;
import nhcm.capitalmc.account.ServerUser;

import java.util.UUID;

/** Explicit immutable metadata encoding; passwords are never echoed in responses. */
public class AccountViews
{
    public static JsonObject encode(ServerUser user)
    {
        var result = new JsonObject();
        result.addProperty("id", user.getUuid().toString());
        result.addProperty("owner", user.getOwnerUuid().toString());
        result.addProperty("display", user.getDisplayName());
        result.addProperty("username", user.getUsername());
        result.addProperty("credentialHash", user.getPasswordHash());
        result.addProperty("exchangeUser", user.getExchangeUserId());
        result.add("profile", user.getProfile().map(value -> (JsonElement) value).orElse(JsonNull.INSTANCE));
        return result;
    }

    public static ServerUser decode(JsonElement value)
    {
        var view = value.getAsJsonObject();
        var user = ServerUser.restore(view.get("display").getAsString(), view.get("username").getAsString(),
                                      view.get("credentialHash").getAsString(), UUID.fromString(view.get("id").getAsString()),
                                      UUID.fromString(view.get("owner").getAsString()));
        long nativeId = view.get("exchangeUser").getAsLong();
        if (nativeId != 0)
        {
            user = user.withExchangeUserId(nativeId);
        }
        return user.withProfile(view.get("profile").isJsonNull() ? null : view.getAsJsonObject("profile"));
    }
}
