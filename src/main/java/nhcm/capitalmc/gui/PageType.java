package nhcm.capitalmc.gui;

public enum PageType
{
    HOME("gui.capitalmc.home"),
    ABOUT("ui.about"),
    LIST_SYMBOLS("gui.capitalmc.symbols"),
    MY_ACCOUNT("gui.capitalmc.account"),
    DEPOSIT_WITHDRAW("gui.capitalmc.deposit_withdraw"),
    TRADE_SPOT("gui.capitalmc.trade.spot"),
    TRADE_CONTRACT("ui.trade.contract"),
    ACCESS("ui.access"),
    ASSET("ui.asset"),
    TRANSFER("ui.transfer"),
    PEG("ui.peg"),
    ORDERS("ui.orders"),
    POSITIONS("ui.positions"),
    POSITION("ui.position.page"),
    CONDITIONALS("ui.conditional.page"),
    CONDITIONAL_TICKET("ui.conditional.ticket"),
    ORDER_HISTORY("ui.order_history"),
    POSITION_HISTORY("ui.position_history"),
    TRADES("ui.trades"),
    ADMIN("ui.admin"),
    TRADING_SETTINGS("ui.settings.title"),
    SYMBOL_INFO("ui.symbol_info"),
    ORDER_BOOK("ui.book.professional"),
    BOOK_SETTINGS("ui.book.settings");

    private final String titleKey;

    PageType(String titleKey)
    {
        this.titleKey = titleKey;
    }

    public boolean isActivityList()
    {
        return this == ORDERS || this == POSITIONS || this == ORDER_HISTORY || this == POSITION_HISTORY
               || this == CONDITIONALS || this == TRADES;
    }

    public String getTitleKey()
    {
        return titleKey;
    }
}
