package nhcm.capitalmc.backend;

import com.google.gson.*;
import nhcm.capitalmc.account.ServerUser;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class RemoteAccountDirectory implements AccountDirectory
{
    private final BackendClient client;
    private String revision = "";
    private List<ServerUser> image = List.of();

    public RemoteAccountDirectory(BackendClient client)
    {
        this.client = client;
    }

    private static JsonObject request(String... pairs)
    {
        var request = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2)
        {
            request.addProperty(pairs[i], pairs[i + 1]);
        }
        return request;
    }

    @Override
    public CompletableFuture<List<ServerUser>> snapshot()
    {
        var request = new JsonObject();
        synchronized (this)
        {
            request.addProperty("known", revision);
        }
        return client.call(BackendClient.ACCOUNTS, request).thenApply(value -> {
            synchronized (this)
            {
                var response = value.getAsJsonObject();
                if (!response.get("image").isJsonNull())
                {
                    var result = new ArrayList<ServerUser>();
                    response.getAsJsonArray("image").forEach(row -> result.add(AccountViews.decode(row)));
                    image = List.copyOf(result);
                    revision = response.get("revision").getAsString();
                }
                return image;
            }
        });
    }

    @Override
    public CompletableFuture<ServerUser> register(String display, String username, String password, UUID owner, JsonObject profile)
    {
        var request = request("display", display, "username", username, "password", password, "owner", owner.toString());
        request.add("profile", profile == null ? JsonNull.INSTANCE : profile);
        return client.call(BackendClient.REGISTER, request).thenApply(AccountViews::decode);
    }

    @Override
    public CompletableFuture<Optional<ServerUser>> authenticate(String username, String password)
    {
        return client.call(BackendClient.AUTHENTICATE, request("username", username, "password", password))
                     .thenApply(value -> value.isJsonNull() ? Optional.empty() : Optional.of(AccountViews.decode(value)));
    }

    @Override
    public CompletableFuture<Boolean> password(String username, String oldPassword, String newPassword)
    {
        return client.call(BackendClient.PASSWORD, request("username", username, "old", oldPassword, "new", newPassword))
                     .thenApply(JsonElement::getAsBoolean);
    }

    @Override
    public CompletableFuture<ServerUser> ensure(UUID account)
    {
        return client.call(BackendClient.ENSURE, request("account", account.toString())).thenApply(AccountViews::decode);
    }

    @Override
    public CompletableFuture<Optional<ServerUser>> remove(UUID owner, UUID account)
    {
        return client.call(BackendClient.REMOVE, request("owner", owner.toString(), "account", account.toString()))
                     .thenApply(value -> value.isJsonNull() ? Optional.empty() : Optional.of(AccountViews.decode(value)));
    }
}
