package nhcm.capitalmc.backend;

import com.google.gson.JsonObject;
import nhcm.capitalmc.account.ServerUser;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface AccountDirectory
{
    CompletableFuture<List<ServerUser>> snapshot();

    CompletableFuture<ServerUser> register(String display, String username, String password, UUID owner, JsonObject profile);

    CompletableFuture<Optional<ServerUser>> authenticate(String username, String password);

    CompletableFuture<Boolean> password(String username, String oldPassword, String newPassword);

    CompletableFuture<ServerUser> ensure(UUID account);

    CompletableFuture<Optional<ServerUser>> remove(UUID owner, UUID account);
}
