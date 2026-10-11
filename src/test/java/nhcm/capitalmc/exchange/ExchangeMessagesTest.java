package nhcm.capitalmc.exchange;

import nhcm.advmatch.BusinessException;
import nhcm.advmatch.model.*;
import nhcm.advmatch.remote.RemoteFailure;
import nhcm.capitalmc.i18n.I18n;
import nhcm.capitalmc.service.CurrencyService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class ExchangeMessagesTest
{
    private static BusinessException rejected(CommandResultType type, int state)
    {
        return new BusinessException(new CommandReceipt(1, 1, false, new CommandResult(type, state, false, 0, 0,
                                                                                       OrderStatus.Rejected, 0, 0, 0, 0, 0, 0, 0)));
    }

    @Test
    void sameNumericStateUsesItsOwnCommandFamilyAndUnwrapsAsyncFailure()
    {
        assertTrue(ExchangeMessages.error("zh_cn", new CompletionException(rejected(CommandResultType.Place, 4))).contains("余额"));
        assertTrue(ExchangeMessages.error("zh_cn", rejected(CommandResultType.Cancel, 4)).contains("已撤销"));
        assertTrue(ExchangeMessages.error("en_us", rejected(CommandResultType.Balance, 4)).contains("Account"));
        assertTrue(ExchangeMessages.error("zh_cn", rejected(CommandResultType.Place, 999)).contains("未提供具体原因"));
        assertTrue(ExchangeMessages.error("zh_cn", rejected(CommandResultType.Place, 999)).contains("999"));
    }

    @Test
    void issuerFundsFailuresDoNotDiscloseBalancesEvenThroughLegacyExceptions()
    {
        var legacy = new CurrencyService.FundsException("ui.peg.reserve_funds", 987654, 123456, 654321);
        for (String language : new String[]{"en_us", "zh_cn"})
        {
            String expected = I18n.format(language, "ui.peg.reserve_funds");
            assertEquals(expected, ExchangeMessages.error(language, new CompletionException(legacy)));
            assertEquals(expected, ExchangeMessages.error(language, new CompletionException(new IllegalStateException("ui.peg.reserve_funds"))));
            assertEquals(expected, I18n.format(language, "ui.peg.reserve_funds_details", "Private Asset", 123456, 654321));
            assertFalse(expected.contains("123456"));
            assertFalse(expected.contains("654321"));
        }
    }

    @Test
    void remoteUncertaintyAndPublicKeysSurviveWrappedCauses()
    {
        for (String language : new String[]{"en_us", "zh_cn"})
        {
            for (boolean sent : new boolean[]{false, true})
            {
                var failure = new RemoteFailure("Disconnected", sent, new IllegalStateException("socket closed"));
                String key = sent ? "ui.exchange.request_uncertain" : "ui.exchange.connection_stale";
                assertEquals(I18n.format(language, key), ExchangeMessages.error(language, new CompletionException(failure)));
            }
            var failure = new IllegalStateException("ui.custody.binding_changed", new IllegalStateException("Private backend detail"));
            assertEquals(I18n.format(language, "ui.custody.binding_changed"), ExchangeMessages.error(language, new CompletionException(failure)));
        }
    }

    @Test
    void everyNativeRejectionAndDisplayedEnumHasBothTranslations() throws Exception
    {
        Class<?>[] families = {PlaceOrderState.class, ModifyOrderState.class, CancelOrderState.class, BalanceOpResult.class,
                ContractState.class, ConditionalOrderState.class, TradingRuleState.class, TradingControlState.class, ManagementState.class, ReadState.class};
        for (String language : new String[]{"en_us", "zh_cn"})
        {
            for (Class<?> family : families)
                for (Object value : family.getEnumConstants())
                {
                    String key = "ui.exchange.reason." + ((Enum<?>) value).name();
                    assertNotEquals(key, I18n.format(language, key), family.getSimpleName());
                }
            for (Class<?> family : new Class<?>[]{OrderSide.class, OrderType.class, TimeInForce.class, MarginMode.class,
                    AccountType.class, SymbolType.class, PositionSide.class, OrderStatus.class, ContractStatus.class})
                for (Object value : family.getEnumConstants())
                {
                    String key = "ui.enum." + family.getSimpleName() + "." + ((Enum<?>) value).name();
                    assertNotEquals(key, I18n.format(language, key));
                }
        }
    }
}
