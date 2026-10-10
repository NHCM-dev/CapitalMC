package nhcm.capitalmc.exchange;

import nhcm.advmatch.Exchange;
import nhcm.advmatch.command.read.ReadUser;
import nhcm.advmatch.model.*;
import nhcm.advmatch.object.*;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.account.ServerUser;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class UserUtil
{
    private UserUtil()
    {
    }

    public static CompletableFuture<User> getUser(ServerUser user)
    {
        return CapitalMC.serverUserManager.ensureExchangeUser(user.getUuid())
                                          .thenCompose(bound -> getUser(bound.getExchangeUserId()));
    }

    public static CompletableFuture<User> getUser(long userId)
    {
        return getUser(QueryUtil.exchange(), userId);
    }

    public static CompletableFuture<User> getUser(Exchange exchange, long userId)
    {
        QueryUtil.positive(userId);
        return exchange.users().find(userId);
    }

    public static CompletableFuture<User> getLoggedInUser(UUID playerUuid)
    {
        return CapitalMC.serverUserManager.getLoggedInExchangeUser(playerUuid)
                                          .thenApply(user -> user.orElseThrow(() -> new IllegalStateException("Player is not logged in")));
    }

    public static CompletableFuture<Optional<UserView>> find(long userId)
    {
        QueryUtil.positive(userId);
        return QueryUtil.exchange().submit(new ReadUser(new ObservationQuery().withUserId(userId)))
                        .thenApply(QueryUtil::one);
    }

    public static CompletableFuture<SpotAccount> spotAccount(ServerUser user)
    {
        return getUser(user).thenCompose(User::spotAccount);
    }

    public static CompletableFuture<SpotAccount> spotAccount(long userId)
    {
        return getUser(userId).thenCompose(User::spotAccount);
    }

    public static CompletableFuture<SpotAccount> spotAccount(User user)
    {
        return user.spotAccount();
    }

    public static CompletableFuture<ContractAccount> contractAccount(ServerUser user)
    {
        return getUser(user).thenCompose(User::contractAccount);
    }

    public static CompletableFuture<ContractAccount> contractAccount(long userId)
    {
        return getUser(userId).thenCompose(User::contractAccount);
    }

    public static CompletableFuture<ContractAccount> contractAccount(User user)
    {
        return user.contractAccount();
    }

    public static CompletableFuture<List<UserView>> allUsers()
    {
        return QueryUtil.all(QueryUtil.exchange().users()::list);
    }
}
