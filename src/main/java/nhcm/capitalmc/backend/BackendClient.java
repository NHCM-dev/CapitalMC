package nhcm.capitalmc.backend;

import com.google.gson.*;
import nhcm.advmatch.remote.RemoteChannel;
import nhcm.advmatch.command.Command;
import nhcm.advmatch.model.CommandReceipt;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.Base64;

public class BackendClient implements SharedMetadata
{
    public interface Rpc
    {
        CompletableFuture<JsonElement> call(int operation, JsonObject request);
    }

    public static final int INFO = 100, ACCOUNTS = 101, REGISTER = 102, AUTHENTICATE = 103,
                            PASSWORD = 104, ENSURE = 105, REMOVE = 106, METADATA = 110, COMMIT = 111,
                            ACQUIRE = 112, RELEASE = 113, REQUEST_RANGE = 114, CUSTODY = 116;
    private final Rpc rpc;
    private long nextRequest, endRequest;
    private String metadataRevision = "";
    private JsonObject metadataImage = new JsonObject();

    public BackendClient(Rpc rpc)
    {
        this.rpc = rpc;
    }

    public BackendClient(RemoteChannel channel)
    {
        this((operation, request) -> channel.extension(operation, request.toString().getBytes(StandardCharsets.UTF_8))
                                           .thenApply(bytes -> JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))));
    }

    public CompletableFuture<JsonElement> call(int operation, JsonObject request)
    {
        return rpc.call(operation, request);
    }

    public CompletableFuture<CommandReceipt> custody(Command<CommandReceipt> command, JsonObject bindings)
    {
        var request = new JsonObject();
        request.addProperty("packet", Base64.getEncoder().encodeToString(command.encode()));
        request.add("bindings", bindings.deepCopy());
        return call(CUSTODY, request).thenApply(response -> command.decode(Base64.getDecoder().decode(response.getAsString())));
    }

    @Override
    public CompletableFuture<JsonObject> read()
    {
        var request = new JsonObject();
        synchronized (this)
        {
            request.addProperty("known", metadataRevision);
        }
        return call(METADATA, request).thenApply(value -> {
            synchronized (this)
            {
                var response = value.getAsJsonObject();
                if (!response.get("image").isJsonNull())
                {
                    metadataImage = response.getAsJsonObject("image").deepCopy();
                    metadataRevision = response.get("revision").getAsString();
                }
                return metadataImage.deepCopy();
            }
        });
    }

    @Override
    public CompletableFuture<JsonObject> commit(JsonObject expected, JsonObject next)
    {
        var request = new JsonObject();
        request.add("expected", expected);
        request.add("next", next);
        return call(COMMIT, request).thenApply(JsonElement::getAsJsonObject);
    }

    /** Called only on an application IO thread, before journalling a mutation. */
    public synchronized long nextRequestId()
    {
        if (nextRequest == endRequest)
        {
            var request = new JsonObject();
            request.addProperty("count", 4096);
            var range = call(REQUEST_RANGE, request).join().getAsJsonObject();
            nextRequest = range.get("first").getAsLong();
            endRequest = range.get("end").getAsLong();
        }
        return nextRequest++;
    }

    public <T> CompletableFuture<T> exclusive(String namespace, Supplier<CompletableFuture<T>> work)
    {
        var request = new JsonObject();
        request.addProperty("name", namespace);
        return call(ACQUIRE, request).thenCompose(lease -> {
            CompletableFuture<T> operation;
            try
            {
                operation = work.get();
            }
            catch (Throwable error)
            {
                operation = CompletableFuture.failedFuture(error);
            }
            return operation.handle((value, failure) -> {
                var release = new JsonObject();
                release.addProperty("name", namespace);
                release.addProperty("lease", lease.getAsString());
                return call(RELEASE, release).handle((unused, releaseFailure) -> {
                    if (failure != null)
                    {
                        throw new CompletionException(failure);
                    }
                    if (releaseFailure != null)
                    {
                        throw new CompletionException(releaseFailure);
                    }
                    return value;
                });
            }).thenCompose(future -> future);
        });
    }
}
