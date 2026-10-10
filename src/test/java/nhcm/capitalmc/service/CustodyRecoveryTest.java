package nhcm.capitalmc.service;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CustodyRecoveryTest
{
    private JsonObject operation(String phase)
    {
        var op = new JsonObject();
        op.addProperty("type", "WITHDRAW_ITEMS");
        op.addProperty("phase", phase);
        op.addProperty("debitRequest", 99);
        return op;
    }

    @Test
    void interruptedPhasesAreQuarantinedWithoutChangingTerminalResults()
    {
        var root = new JsonObject();
        var records = new JsonObject();
        root.add("itemOperations", records);
        for (var phase : ItemBankService.Phase.values())
            records.add(phase.name(), operation(phase.name()));
        ItemBankService.recoverMetadata(root);
        for (var phase : ItemBankService.Phase.values())
        {
            var op = records.getAsJsonObject(phase.name());
            assertEquals(phase.terminal() ? phase.name() : "IN_DOUBT", op.get("phase").getAsString());
            if (!phase.terminal() && phase != ItemBankService.Phase.IN_DOUBT)
            {
                assertEquals(phase.name(), op.get("failurePhase").getAsString());
            }
        }
        var snapshot = root.deepCopy();
        ItemBankService.recoverMetadata(root);
        assertEquals(snapshot, root);
    }

    @Test
    void refundRequiresConfirmedDebitBeforeAnyMaterialization()
    {
        assertTrue(ItemBankService.canRefund(operation("DEBITED")));
        for (String phase : new String[]{"EXCHANGE_DEBITTING", "DELIVERY_INTENT", "REFUND_INTENT", "COMPLETE", "REVERSED", "RECONCILED", "REJECTED"})
            assertFalse(ItemBankService.canRefund(operation(phase)));
        var crash = operation("IN_DOUBT");
        crash.addProperty("failurePhase", "DEBITED");
        assertTrue(ItemBankService.canRefund(crash));
        crash.addProperty("failurePhase", "DELIVERY_INTENT");
        assertFalse(ItemBankService.canRefund(crash));
        var missing = operation("DEBITED");
        missing.remove("debitRequest");
        assertFalse(ItemBankService.canRefund(missing));
    }
}
