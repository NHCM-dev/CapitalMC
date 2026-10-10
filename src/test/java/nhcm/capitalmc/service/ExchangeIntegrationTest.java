package nhcm.capitalmc.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import nhcm.advmatch.*;
import nhcm.advmatch.command.account.*;
import nhcm.advmatch.command.persistence.Flush;
import nhcm.advmatch.command.read.ReadBalance;
import nhcm.advmatch.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real JNI engine, isolated ledgers: confirms raw math, idempotency and physical custody prerequisites.
 */
class ExchangeIntegrationTest
{
    @TempDir
    Path directory;

    private Exchange exchange()
    {
        return new Exchange(new ExchangeConfig().withPersistence(new PersistenceConfig().withEnabled(true).withDirectory(directory.toString())));
    }

    private long active(Exchange exchange, long account, long asset)
    {
        var result = exchange.submit(new ReadBalance(new ObservationQuery().withAccountId(account).withAssetId(asset))).join();
        return result.values().isEmpty() ? 0 : result.values().getFirst().active();
    }

    @Test
    void atomicBasketCannotPartiallyCreditWhenAnotherAssetOverflows()
    {
        try (var e = exchange())
        {
            var a = e.assets().create("A", "a", 0).join();
            var b = e.assets().create("B", "b", 0).join();
            var account = e.users().create().join().spotAccount().join();
            account.updateBalance(b, Long.MAX_VALUE).join();
            var result = e.submit(new BalanceTransaction(List.of(new BalanceDelta(account.id(), a.id(), 64),
                                                                 new BalanceDelta(account.id(), b.id(), 1)), false, 7101)).join();
            assertFalse(result.result().accepted());
            assertEquals(0, active(e, account.id(), a.id()));
            assertEquals(Long.MAX_VALUE, active(e, account.id(), b.id()));
            assertTrue(e.submit(new BalanceTransaction(List.of(new BalanceDelta(account.id(), a.id(), 64),
                                                               new BalanceDelta(account.id(), b.id(), -1)), false, 7102)).join().result().accepted());
            assertEquals(64, active(e, account.id(), a.id()));
        }
    }

    @Test
    void reserveExchangeIsAllOrNothingAndConservesEachAsset()
    {
        try (var e = exchange())
        {
            var item = e.assets().create("Item", "item", 0).join();
            var cash = e.assets().create("Cash", "cash", 2).join();
            var bank = e.users().create().join().spotAccount().join();
            var player = e.users().create().join().spotAccount().join();
            player.updateBalance(item, 5).join();
            bank.updateBalance(cash, 499).join();
            var legs = List.of(new BalanceDelta(player.id(), item.id(), -5), new BalanceDelta(bank.id(), item.id(), 5),
                               new BalanceDelta(bank.id(), cash.id(), -500), new BalanceDelta(player.id(), cash.id(), 500));
            assertFalse(e.submit(new BalanceTransaction(legs, true, 7201)).join().result().accepted());
            assertEquals(5, active(e, player.id(), item.id()));
            assertEquals(0, active(e, bank.id(), item.id()));
            bank.updateBalance(cash, 1).join();
            var command = new BalanceTransaction(legs, true, 7202);
            assertTrue(e.submit(command).join().result().accepted());
            assertTrue(e.submit(command).join().duplicate());
            assertEquals(5, active(e, bank.id(), item.id()));
            assertEquals(500, active(e, player.id(), cash.id()));
            assertFalse(e.submit(new BalanceTransaction(List.of(new BalanceDelta(player.id(), cash.id(), 1)), true)).join().result().accepted());
        }
    }

    @Test
    void atomicCommandReplayRestoresWholeBasketAndDuplicateIdentity()
    {
        long account, a, b;
        BalanceTransaction command;
        try (var e = exchange())
        {
            a = e.assets().create("A", "a", 0).join().id();
            b = e.assets().create("B", "b", 0).join().id();
            account = e.users().create().join().spotAccount().join().id();
            command = new BalanceTransaction(List.of(new BalanceDelta(account, a, 10), new BalanceDelta(account, b, 20)), false, 7301);
            assertTrue(e.submit(command).join().result().accepted());
            e.submit(new Flush()).join();
        }
        try (var e = exchange())
        {
            assertEquals(10, active(e, account, a));
            assertEquals(20, active(e, account, b));
            assertTrue(e.submit(command).join().duplicate());
            assertEquals(10, active(e, account, a));
        }
    }

    @Test
    void duplicateLegsAndDeletedAccountsRejectBeforeAnyBalanceChange()
    {
        try (var e = exchange())
        {
            var asset = e.assets().create("A", "a", 0).join();
            var user = e.users().create().join();
            var account = user.spotAccount().join();
            var duplicate = List.of(new BalanceDelta(account.id(), asset.id(), 5), new BalanceDelta(account.id(), asset.id(), -5));
            assertFalse(e.submit(new BalanceTransaction(duplicate, true)).join().result().accepted());
            user.remove().join();
            assertFalse(e.submit(new BalanceTransaction(List.of(new BalanceDelta(account.id(), asset.id(), 5)), false)).join().result().accepted());
        }
    }

    @Test
    void mintBurnAuditAndStableOperationIdentitySurviveRestartWithoutDoubleMutation()
    {
        try (var e = exchange())
        {
            var asset = e.assets().create("Cash", "cash", 2).join();
            var bank = e.users().create().join().spotAccount().join();
            Path metadata = directory.resolve("metadata.json");
            var state = new ServiceState(metadata);
            var service = new SettlementService(e, state);
            String mintId = UUID.randomUUID().toString();
            var minted = service.adjustmentWithRequest(bank.id(), asset.id(), 10000, "MINT", "admin", "Initial reserves", mintId).join();
            var restarted = new SettlementService(e, new ServiceState(metadata));
            var repeated = restarted.adjustmentWithRequest(bank.id(), asset.id(), 10000, "MINT", "admin", "Initial reserves", mintId).join();
            assertEquals(minted.requestId(), repeated.requestId());
            assertEquals(10000, active(e, bank.id(), asset.id()));
            assertThrows(CompletionException.class, () -> restarted.adjustment(bank.id(), asset.id(), 20000,
                                                                               "MINT", "admin", "Initial reserves", mintId).join());
            service.adjustment(bank.id(), asset.id(), -2500, "BURN", "admin", "Retire reserves", UUID.randomUUID().toString()).join();
            assertEquals(7500, active(e, bank.id(), asset.id()));
            var records = new ServiceState(metadata).snapshot().getAsJsonObject("commands");
            assertEquals(2, records.size());
            var mint = records.getAsJsonObject(Long.toString(minted.requestId()));
            assertEquals("MINT", mint.get("type").getAsString());
            assertEquals("admin", mint.get("actor").getAsString());
            assertEquals("Initial reserves", mint.get("reason").getAsString());
            assertEquals("DURABLE", mint.get("status").getAsString());
        }
    }

    @Test
    void rejectedBurnCannotDestroyReservedOrUnavailableMoneyAndIsAuditable()
    {
        try (var e = exchange())
        {
            var asset = e.assets().create("Cash", "cash", 0).join();
            var bank = e.users().create().join().spotAccount().join();
            bank.updateBalance(asset, 5).join();
            var state = new ServiceState(directory.resolve("metadata.json"));
            var service = new SettlementService(e, state);
            String id = UUID.randomUUID().toString();
            assertThrows(CompletionException.class, () -> service.adjustment(bank.id(), asset.id(), -6, "BURN", "admin", "Retire", id).join());
            assertThrows(CompletionException.class, () -> service.adjustment(bank.id(), asset.id(), -6, "BURN", "admin", "Retire", id).join());
            assertEquals(5, active(e, bank.id(), asset.id()));
            assertEquals(1, state.snapshot().getAsJsonObject("commands").size());
            assertEquals("REJECTED", state.snapshot().getAsJsonObject("commands").entrySet().iterator().next().getValue().getAsJsonObject().get("status").getAsString());
        }
    }

    @Test
    void decimalPricesSettleCorrectlyAndBookLevelsUseTheSameUnits()
    {
        try (var e = exchange())
        {
            var base = e.assets().create("Base", "base", 2).join();
            var quote = e.assets().create("Quote", "quote", 4).join();
            var symbol = e.symbols().create("base/quote", SymbolType.Spot, base, quote).join();
            e.books().addSpot(symbol, OrderBookImplementation.Radix).join();
            var buyer = e.users().create().join();
            var seller = e.users().create().join();
            var buyAccount = buyer.spotAccount().join();
            var sellAccount = seller.spotAccount().join();
            buyAccount.updateBalance(quote, 100000).join();
            sellAccount.updateBalance(base, 150).join();
            long price = DecimalUnits.parse("2.50", DecimalUnits.priceScale(2, 4)), quantity = DecimalUnits.parse("1.50", 2);
            var ask = symbol.book().place(seller, OrderSide.Ask, OrderType.Limit, TimeInForce.GTC, price, quantity, 1).join();
            var level = symbol.book().depth(OrderSide.Ask, 5).join().values().getFirst();
            assertEquals(price, level.price());
            assertEquals(quantity, level.quantity());
            symbol.book().place(buyer, OrderSide.Bid, OrderType.Limit, TimeInForce.IOC, level.price(), quantity, 2).join();
            assertEquals(150, active(e, buyAccount.id(), base.id()));
            assertEquals(62500, active(e, buyAccount.id(), quote.id()));
            assertEquals(37500, active(e, sellAccount.id(), quote.id()));
            assertEquals(OrderStatus.Filled, ask.snapshot().join().filling().status());
        }
    }

    @Test
    void duplicateRequestsDoNotMintOrTransferTwiceAndFlushIsDurable()
    {
        try (var e = exchange())
        {
            var asset = e.assets().create("Currency", "cash", 2).join();
            var issuer = e.users().create().join().spotAccount().join();
            var recipient = e.users().get(1).contractAccount().join();
            var mint = new UpdateBalance(issuer.id(), asset.id(), 10000, 90001);
            assertTrue(e.submit(mint).join().result().accepted());
            assertTrue(e.submit(mint).join().duplicate());
            var transfer = new Transfer(issuer.id(), recipient.id(), asset.id(), 2500, 90002);
            var receipt = e.submit(transfer).join();
            assertTrue(e.submit(transfer).join().duplicate());
            assertEquals(7500, active(e, issuer.id(), asset.id()));
            assertEquals(2500, active(e, recipient.id(), asset.id()));
            var flushed = e.submit(new Flush()).join();
            assertFalse(flushed.faulted());
            assertTrue(flushed.durableThrough() >= receipt.sequence());
        }
    }

    @Test
    void insufficientWithdrawalLeavesBalancesUntouchedAndSpotContractTransferWorks()
    {
        try (var e = exchange())
        {
            var item = e.assets().create("Diamond", "mc_diamond", 0).join();
            var user = e.users().create().join();
            var spot = user.spotAccount().join();
            var contract = user.contractAccount().join();
            spot.updateBalance(item, 64).join();
            var reject = e.submit(new UpdateBalance(spot.id(), item.id(), -65, 1001)).join();
            assertFalse(reject.result().accepted());
            assertEquals(64, active(e, spot.id(), item.id()));
            assertTrue(e.submit(new Transfer(spot.id(), contract.id(), item.id(), 16, 1002)).join().result().accepted());
            assertEquals(48, active(e, spot.id(), item.id()));
            assertEquals(16, active(e, contract.id(), item.id()));
        }
    }

    @Test
    void atomicCrossUserTransferConservesMoneyAndRejectsInsufficientFunds()
    {
        try (var e = exchange())
        {
            var asset = e.assets().create("Currency", "cash", 2).join();
            var source = e.users().create().join().spotAccount().join();
            var target = e.users().create().join().spotAccount().join();
            source.updateBalance(asset, 10000).join();
            var state = new ServiceState(directory.resolve("bridge.json"));
            var bridge = new SettlementService(e, state);
            assertTrue(bridge.transfer(source.id(), target.id(), asset.id(), 2500).join().result().accepted());
            assertEquals(7500, active(e, source.id(), asset.id()));
            assertEquals(2500, active(e, target.id(), asset.id()));
            assertThrows(CompletionException.class, () -> bridge.transfer(source.id(), target.id(), asset.id(), 7501).join());
            assertEquals(7500, active(e, source.id(), asset.id()));
            assertEquals(2500, active(e, target.id(), asset.id()));
            assertFalse(bridge.unresolvedTransfer(source.id()));
            assertFalse(bridge.unresolvedTransfer(target.id()));
        }
    }

    @Test
    void recipientOverflowRejectsAtomicTransferBeforeDebitingSender()
    {
        try (var e = exchange())
        {
            var asset = e.assets().create("Currency", "cash", 0).join();
            var source = e.users().create().join().spotAccount().join();
            var target = e.users().create().join().spotAccount().join();
            source.updateBalance(asset, 10).join();
            target.updateBalance(asset, Long.MAX_VALUE).join();
            var bridge = new SettlementService(e, new ServiceState(directory.resolve("bridge.json")));
            assertThrows(CompletionException.class, () -> bridge.transfer(source.id(), target.id(), asset.id(), 1).join());
            assertEquals(10, active(e, source.id(), asset.id()));
            assertEquals(Long.MAX_VALUE, active(e, target.id(), asset.id()));
            assertFalse(bridge.unresolvedTransfer(source.id()));
        }
    }

    @Test
    void unresolvedTransferSurvivesRestartAndCannotBeRepeated()
    {
        try (var e = exchange())
        {
            var asset = e.assets().create("Currency", "cash", 0).join();
            var source = e.users().create().join().spotAccount().join();
            var target = e.users().create().join().spotAccount().join();
            source.updateBalance(asset, 10).join();
            var state = new ServiceState(directory.resolve("bridge.json"));
            state.update(root -> {
                var entries = new JsonObject();
                var entry = new JsonObject();
                entry.addProperty("source", source.id());
                entry.addProperty("target", target.id());
                entry.addProperty("phase", "DEBITED");
                entries.add(UUID.randomUUID().toString(), entry);
                root.add("crossTransfers", entries);
            });
            var bridge = new SettlementService(e, new ServiceState(directory.resolve("bridge.json")));
            assertTrue(bridge.unresolvedTransfer(source.id()));
            assertThrows(CompletionException.class, () -> bridge.transfer(source.id(), target.id(), asset.id(), 1).join());
            assertEquals(10, active(e, source.id(), asset.id()));
        }
    }

    @Test
    void contractOrdersCreatePositionsAndReduceOnlyOrdersCloseThem()
    {
        try (var e = exchange())
        {
            long now = System.currentTimeMillis();
            var base = e.assets().create("Diamond", "diamond", 0).join();
            var quote = e.assets().create("Cash", "cash", 2).join();
            var symbol = e.symbols().create("D/C PERP", SymbolType.Perpetual, base, quote).join();
            var spec = new ContractSpec().withDefaultLeverage(1).withMaxLeverage(10).withTakerFeeRate(0).withMakerFeeRate(0).withFirstFundingTimestamp(now + 28800000);
            e.books().addContract(symbol, spec, 250, now, OrderBookImplementation.Radix).join();
            var buyer = e.users().create().join();
            var seller = e.users().create().join();
            buyer.contractAccount().join().updateBalance(quote, 100000).join();
            seller.contractAccount().join().updateBalance(quote, 100000).join();
            var options = new ContractOrderOptions().withLeverage(10).withMarginMode(MarginMode.Isolated);
            assertDoesNotThrow(() -> symbol.book().placeContract(seller, OrderSide.Ask, OrderType.Limit, TimeInForce.GTC, 250, 10, now + 1, options).join(), "open short limit");
            assertDoesNotThrow(() -> symbol.book().placeContract(buyer, OrderSide.Bid, OrderType.Market, TimeInForce.IOC, 0, 10, now + 2, options.withPriceLimit(275)).join(), "open long market");
            var longPosition = buyer.positions(new ObservationQuery()).join().values().getFirst();
            var shortPosition = seller.positions(new ObservationQuery()).join().values().getFirst();
            assertEquals(10, longPosition.size());
            assertEquals(PositionSide.Long, longPosition.side());
            assertEquals(250, longPosition.averageEntryPrice());
            var state = new ServiceState(directory.resolve("margin.json"));
            var settlements = new SettlementService(e, state);
            assertThrows(CompletionException.class, () -> settlements.addMargin(seller.id(), longPosition.positionId(), 100).join());
            assertTrue(state.section("commands").isEmpty(), "Unauthorized margin addition created an intent");
            var marginReceipt = settlements.addMargin(buyer.id(), longPosition.positionId(), 100).join();
            assertEquals(longPosition.initialMargin() + 100, e.positions().find(longPosition.positionId()).join().snapshot().join().initialMargin());
            var marginEntry = state.section("commands").getAsJsonObject("commands").entrySet().iterator().next();
            assertEquals("DURABLE", marginEntry.getValue().getAsJsonObject().get("status").getAsString());
            assertEquals("ADD_MARGIN", marginEntry.getValue().getAsJsonObject().get("type").getAsString());
            assertTrue(e.submit(new Flush()).join().durableThrough() >= marginReceipt.sequence());
            symbol.book().placeContract(seller, OrderSide.Bid, OrderType.Limit, TimeInForce.GTC, 250, 10, now + 3, options.withPositionId(shortPosition.positionId()).withReduceOnly(true)).join();
            symbol.book().placeContract(buyer, OrderSide.Ask, OrderType.Market, TimeInForce.IOC, 0, 10, now + 4, options.withPositionId(longPosition.positionId()).withReduceOnly(true).withPriceLimit(225)).join();
            assertTrue(e.positions().find(longPosition.positionId()).join().snapshot().join().closed());
            assertTrue(e.positions().find(shortPosition.positionId()).join().snapshot().join().closed());
        }
    }

    @Test
    void atomicCreditAccountsForReservedBalancesAndCannotReleaseAnOrderReservation()
    {
        try (var e = exchange())
        {
            var base = e.assets().create("Base", "base", 0).join();
            var quote = e.assets().create("Quote", "quote", 0).join();
            var user = e.users().create().join();
            var account = user.spotAccount().join();
            account.updateBalance(base, Long.MAX_VALUE - 5).join();
            var market = e.symbols().create("B/Q", SymbolType.Spot, base, quote).join();
            e.books().addSpot(market, OrderBookImplementation.Radix).join();
            market.book().place(user, OrderSide.Ask, OrderType.Limit, TimeInForce.GTC, 1, 5, 1).join();
            var before = e.submit(new ReadBalance(new ObservationQuery().withAccountId(account.id()).withAssetId(base.id()))).join().values().getFirst();
            assertEquals(5, before.reserved());
            var result = e.submit(new BalanceTransaction(List.of(new BalanceDelta(account.id(), quote.id(), 1), new BalanceDelta(account.id(), base.id(), 6)), false)).join();
            assertFalse(result.result().accepted());
            assertEquals(0, active(e, account.id(), quote.id()));
            assertEquals(before.active(), active(e, account.id(), base.id()));
            assertFalse(e.submit(new BalanceTransaction(List.of(new BalanceDelta(account.id(), base.id(), -before.active() - 1)), false)).join().result().accepted());
            var after = e.submit(new ReadBalance(new ObservationQuery().withAccountId(account.id()).withAssetId(base.id()))).join().values().getFirst();
            assertEquals(before, after);
        }
    }

    @Test
    void stableAdjustmentIdCannotBeReusedForAnotherAccountOrAsset()
    {
        try (var e = exchange())
        {
            var first = e.assets().create("First", "first", 0).join();
            var second = e.assets().create("Second", "second", 0).join();
            var a = e.users().create().join().spotAccount().join();
            var b = e.users().create().join().spotAccount().join();
            var service = new SettlementService(e, new ServiceState(directory.resolve("metadata.json")));
            var operation = UUID.randomUUID().toString();
            service.adjustment(a.id(), first.id(), 10, "MINT", "admin", "Test issuance", operation).join();
            assertThrows(CompletionException.class, () -> service.adjustment(b.id(), first.id(), 10, "MINT", "admin", "Test issuance", operation).join());
            assertThrows(CompletionException.class, () -> service.adjustment(a.id(), second.id(), 10, "MINT", "admin", "Test issuance", operation).join());
            assertEquals(10, active(e, a.id(), first.id()));
            assertEquals(0, active(e, b.id(), first.id()));
            assertEquals(0, active(e, a.id(), second.id()));
        }
    }

    @Test
    void commandReviewPreservesEvidenceAndUnblocksOnlyExplicitlyResolvedTransfers()
    {
        try (var e = exchange())
        {
            var asset = e.assets().create("Cash", "cash", 0).join();
            var source = e.users().create().join().spotAccount().join();
            var target = e.users().create().join().spotAccount().join();
            source.updateBalance(asset, 10).join();
            var state = new ServiceState(directory.resolve("review.json"));
            state.update(root -> {
                var commands = new JsonObject();
                var entry = new JsonObject();
                entry.addProperty("status", "PENDING");
                entry.addProperty("type", "TRANSFER");
                entry.add("changes", new Gson().toJsonTree(List.of(new BalanceDelta(source.id(), asset.id(), -5), new BalanceDelta(target.id(), asset.id(), 5))));
                commands.add("51", entry);
                root.add("commands", commands);
            });
            var service = new SettlementService(e, state);
            assertTrue(service.unresolvedTransfer(source.id()));
            assertThrows(CompletionException.class, () -> service.reconcileCommand(51, "owner", "").join());
            service.reconcileCommand(51, "owner", "Inspected original ledger and both balances; no command was submitted").join();
            assertFalse(service.unresolvedTransfer(source.id()));
            assertFalse(service.unresolvedTransfer(target.id()));
            var reviewed = new ServiceState(directory.resolve("review.json")).section("commands").getAsJsonObject("commands").getAsJsonObject("51");
            assertEquals("PENDING", reviewed.get("previousStatus").getAsString());
            assertEquals("owner", reviewed.get("reviewer").getAsString());
            assertEquals(10, active(e, source.id(), asset.id()));
            assertEquals(0, active(e, target.id(), asset.id()));
            assertThrows(CompletionException.class, () -> service.reconcileCommand(51, "other", "Overwrite").join());
            service.transfer(source.id(), target.id(), asset.id(), 1).join();
            assertEquals(9, active(e, source.id(), asset.id()));
            var durable = state.section("commands").getAsJsonObject("commands").entrySet().stream().filter(row -> !row.getKey().equals("51")).findFirst().orElseThrow();
            assertThrows(CompletionException.class, () -> service.reconcileCommand(Long.parseLong(durable.getKey()), "owner", "Overwrite completed transfer").join());
        }
    }

}
