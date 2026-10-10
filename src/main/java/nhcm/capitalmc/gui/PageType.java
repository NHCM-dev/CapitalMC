package nhcm.capitalmc.gui;

public enum PageType
{
    HOME("gui.capitalmc.home"),
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
    ORDER_HISTORY("ui.order_history"),
    POSITION_HISTORY("ui.position_history"),
    TRADES("ui.trades"),
    ADMIN("ui.admin"),
    SYMBOL_INFO("ui.symbol_info"),
    ORDER_BOOK("ui.book.professional"),
    BOOK_SETTINGS("ui.book.settings");

    private final String titleKey;

    PageType(String titleKey)
    {
        this.titleKey = titleKey;
    }

    public String getTitleKey()
    {
        return titleKey;
    }
}
