package nhcm.capitalmc.exchange;

import net.minecraft.server.level.ServerPlayer;
import nhcm.advmatch.BusinessException;
import nhcm.advmatch.model.*;
import nhcm.capitalmc.i18n.I18n;
import nhcm.capitalmc.service.CurrencyService;
import nhcm.capitalmc.service.GameServices;

/**
 * Presentation of native results; state codes are scoped to their command family.
 */
public class ExchangeMessages
{
    private ExchangeMessages()
    {
    }

    public static String label(ServerPlayer player, Enum<?> value)
    {
        return I18n.format(language(player), "ui.enum." + value.getDeclaringClass().getSimpleName() + "." + value.name());
    }

    private static String language(ServerPlayer player)
    {
        return player == null ? null : player.clientInformation().language();
    }

    public static String action(ServerPlayer player, String action)
    {
        return I18n.format(language(player), "ui.admin.action." + action);
    }

    public static String error(ServerPlayer player, Throwable error)
    {
        return error(language(player), error);
    }

    public static String error(String language, Throwable error)
    {
        while (error.getCause() != null && !(error instanceof BusinessException))
            error = error.getCause();
        if (error instanceof CurrencyService.FundsException funds)
        {
            // Only the signed-in account's own funds may appear in public error details.
            if (!"ui.peg.user_funds".equals(funds.getMessage()))
            {
                return I18n.format(language, funds.getMessage());
            }
            var asset = GameServices.get().assets.find(funds.assetId).orElseThrow();
            return I18n.format(language, funds.getMessage() + "_details", asset.displayName(), asset.format(funds.required), asset.format(funds.available));
        }
        if (error instanceof BusinessException business)
        {
            if (business.page() != null)
            {
                return I18n.format(language, "ui.exchange.read", I18n.format(language, "ui.exchange.reason." + business.page().state().name()));
            }
            var result = business.receipt().result();
            String reason;
            try
            {
                reason = switch (result.type())
                {
                    case Place -> PlaceOrderState.fromCode(result.state()).name();
                    case Modify -> ModifyOrderState.fromCode(result.state()).name();
                    case Cancel -> CancelOrderState.fromCode(result.state()).name();
                    case Balance -> BalanceOpResult.fromCode(result.state()).name();
                    case Contract -> ContractState.fromCode(result.state()).name();
                    case Conditional -> ConditionalOrderState.fromCode(result.state()).name();
                    case TradingRule -> TradingRuleState.fromCode(result.state()).name();
                    case TradingControl -> TradingControlState.fromCode(result.state()).name();
                    case Management -> ManagementState.fromCode(result.state()).name();
                    case Archive -> "Unknown";
                };
            } catch (IllegalArgumentException unknown)
            {
                reason = "Unknown";
            }
            return I18n.format(language, "ui.exchange.rejected",
                               I18n.format(language, "ui.exchange.operation." + result.type().name()),
                               I18n.format(language, "ui.exchange.reason." + reason), result.state());
        }
        return I18n.format(language, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
    }
}
