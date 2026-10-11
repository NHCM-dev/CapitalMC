package nhcm.capitalmc.exchange;

import nhcm.advmatch.Exchange;
import nhcm.advmatch.command.read.ReadBalance;
import nhcm.advmatch.model.*;
import nhcm.advmatch.object.*;
import nhcm.capitalmc.account.ServerUser;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class BalanceUtil
{
    private BalanceUtil()
    {
    }

    public static CompletableFuture<Optional<BalanceView>> balance(long accountId, long assetId)
    {
        return balance(QueryUtil.exchange(), accountId, assetId);
    }

    public static CompletableFuture<Optional<BalanceView>> balance(Exchange exchange, long accountId, long assetId)
    {
        QueryUtil.positive(accountId);
        QueryUtil.positive(assetId);
        // Operation previews and checks need an explicit fresh read; account list rendering uses subscriptions.
        return exchange.submit(new ReadBalance(new ObservationQuery().withAccountId(accountId).withAssetId(assetId)))
                       .thenApply(QueryUtil::one);
    }

    public static CompletableFuture<Optional<BalanceView>> spotBalance(ServerUser user, long assetId)
    {
        return UserUtil.spotAccount(user).thenCompose(account -> balance(account.id(), assetId));
    }

    public static CompletableFuture<Optional<BalanceView>> spotBalance(long userId, long assetId)
    {
        return UserUtil.spotAccount(userId).thenCompose(account -> balance(account.id(), assetId));
    }

    public static CompletableFuture<Optional<BalanceView>> contractBalance(ServerUser user, long assetId)
    {
        return UserUtil.contractAccount(user).thenCompose(account -> balance(account.id(), assetId));
    }

    public static CompletableFuture<Long> spotAvailable(ServerUser user, long assetId)
    {
        return spotBalance(user, assetId).thenApply(value -> value.map(BalanceView::active).orElse(0L));
    }

    public static CompletableFuture<List<BalanceView>> allBalances(Account account)
    {
        return QueryUtil.liveList(new ObservationQuery().withKind(ReadKind.Balances).withAccountId(account.id()), BalanceView.class, account::balances);
    }

    public static CompletableFuture<List<BalanceView>> allSpotBalances(ServerUser user)
    {
        return UserUtil.spotAccount(user).thenCompose(BalanceUtil::allBalances);
    }

    public static CompletableFuture<List<BalanceView>> allSpotBalances(long userId)
    {
        return UserUtil.spotAccount(userId).thenCompose(BalanceUtil::allBalances);
    }

    public static CompletableFuture<List<BalanceView>> allContractBalances(ServerUser user)
    {
        return UserUtil.contractAccount(user).thenCompose(BalanceUtil::allBalances);
    }
}
