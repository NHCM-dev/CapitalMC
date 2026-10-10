package nhcm.capitalmc.exchange;

import nhcm.advmatch.command.read.ReadAsset;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.service.DecimalUnits;
import nhcm.capitalmc.service.GameServices;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class AssetUtil
{
    private AssetUtil()
    {
    }

    public static DecimalUnits.Unit units(long id, int nativeScale)
    {
        return GameServices.get().assets.units(id, nativeScale);
    }

    public static CompletableFuture<Optional<AssetView>> find(long id)
    {
        QueryUtil.positive(id);
        return QueryUtil.exchange().submit(new ReadAsset(new ObservationQuery().withAssetId(id))).thenApply(QueryUtil::one);
    }

    public static CompletableFuture<List<AssetView>> allAssets()
    {
        return QueryUtil.shared("assets", 1_000_000_000L, AssetUtil::allAssetsFresh);
    }

    /**
     * Metadata loading and registration must not reuse a pre-registration view cache.
     */
    public static CompletableFuture<List<AssetView>> allAssetsFresh()
    {
        return QueryUtil.all(QueryUtil.exchange().assets()::list);
    }

    public static BigDecimal amount(long raw, AssetView asset)
    {
        return BigDecimal.valueOf(raw, asset.scale());
    }

    public static long rawAmount(BigDecimal amount, AssetView asset)
    {
        return amount.movePointRight(asset.scale()).longValueExact();
    }
}
