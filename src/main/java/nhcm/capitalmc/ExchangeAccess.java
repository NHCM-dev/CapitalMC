package nhcm.capitalmc;

import nhcm.advmatch.Exchange;

public interface ExchangeAccess
{
    default Exchange exchange()
    {
        return CapitalMC.exchange;
    }
}
