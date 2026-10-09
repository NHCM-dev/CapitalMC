package nhcm.capitalmc.gui;

public enum PageType
{
    HOME("gui.capitalmc.home"),
    LIST_SYMBOLS("gui.capitalmc.symbols"),
    MY_ACCOUNT("gui.capitalmc.account"),
    TRADE_SPOT("gui.capitalmc.trade.spot");

    private final String titleKey;

    PageType(String titleKey) {
        this.titleKey = titleKey;
    }

    public String getTitleKey() {
        return titleKey;
    }
}
