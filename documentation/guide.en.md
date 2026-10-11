# CapitalMC guide

[README](../README.md) · [简体中文](guide.zh-CN.md)

[Full command reference](commands.en.md)

## Players

| Command | Purpose |
| --- | --- |
| `/trade gui` | Open the exchange |
| `/trade` | Account, balance, deposit, withdrawal and trading commands |
| `/cmcadmin gui` | Open administration (owner permission) |
| `/testgui <page>` | Preview a page during development |

Before login, the home page shows only the login/registration entry. Trading pages become available after login; logging out clears protected navigation.

Chat prompts are private. Follow the displayed example; use `cancel` to return. Most prompts expire after 30 seconds. Passwords are never echoed.

**Trading:** select a market and direction. Contracts offer open long, open short, close long and close short. Click a price level to set a limit price. Market orders use a protection boundary and need an eligible counterparty. The 25% / 50% / 100% buttons estimate capacity from funds, fees, liquidity and rules; the exchange validates the final order.

**Order book:** left-click the comparator to open the full book; right-click it to adjust depth and display settings. White panes indicate empty levels. Highlighted options identify the current selection.

**Deposits:** start drop selection, then use Q for one item or Ctrl+Q for a stack across inventory slots. For container deposits, right-click eligible chests or shulker boxes. Enter `done`, review the batch and confirm with `yes`. Selection does not remove items; the entire batch is checked again before custody.

**Transfers and exchange:** choose the asset and account direction on the transfer page. Pegged exchange shows what you pay and receive before confirmation. Withdrawals deliver physical assets; electronic assets remain ledger balances.

## Administrators

New instances start without assets or markets. Configure them through the administrator GUI or commands.

1. Register physical assets from held items, or create electronic assets.
2. Set display units and minimum deposit/withdrawal amounts. Item matching includes components and damage.
3. Create markets and configure price ticks, quantity steps, minimum orders and contract specifications.
4. Supply pegged exchange reserves and configure reference prices for contracts.

Configuration uses guided chat questions and a review step. Deposits, withdrawals and exchange amounts accept exact decimals where the configured units allow them.

Price ticks align prices; they do not force the next trade to rise. The contract protection band supports a one-tick minimum, controlled by trading rules. Without a valid index source, the mark can remain at its initial value; configure a matching spot market or external source. Order acceptance does not guarantee a fill.

## Persistence

Account and application metadata use binary WAL and snapshots; see [persistence](persistence.en.md) for store paths and recovery. Financial state belongs to AdvMatch, whose persistence directory is configured separately. World saves and exchange records are separate durability boundaries.

Stop the server for a coordinated backup. Preserve custody and recovery evidence; do not repeat an uncertain deposit, withdrawal or delivery. Keep the SDK and native libraries matched.

## Position protection

Click a position to open its detail page. Market full close requires confirmation only; partial market close asks for quantity. Limit close asks for a price. Add/reduce margin is isolated-only. Take-profit/stop-loss supports last, mark and index triggers, market/limit execution and OCO links. Check the execution status after a trigger; fills depend on funds and liquidity.

## Trading settings

Open **Exchange settings** from administration, or `/cmcadmin market settings <symbol>`. Select a pair and configure order ticks, minimums, fees, exposure limits, self-trade handling, contract execution protection, reference prices and funding schedules. Values show units and effects; changes require an audit reason and confirmation and survive restarts. Exchange-wide halt/resume and checkpoints have their own tab.

Protection accepts a percentage below 100; zero disables the execution band. Turning it back on uses 10%, or enter another percentage. Explicit market/TP/SL price caps, collateral checks and bounded liquidation prices remain active. The one-tick option only widens nonzero bands.

Deposits can consume empty chests or shulker boxes held in the main hand to add container credits. Credits fund container withdrawals and cannot be redeemed as containers. Experience deposits consume points, crediting 1 Exp per point to the spot account (`mc_exp`, unit size 1). The asset is registered at startup and cannot be withdrawn as experience or items.

These deposits share custody records: persist intent, revalidate and remove containers/experience, save Minecraft data, then credit the account or container allowance. Minecraft saves and the exchange ledger are separate stores; interrupted records are quarantined for review after restart and never retried automatically.

Exp is a special asset whose supply grows only through experience deposits. Item binding, issuance, burning, administrative balance adjustments and pegged exchange are disabled; its unit size is fixed at 1. Trading and account transfers remain available. Asset details show net external credits, ledger totals and circulating amounts excluding issuer holdings; unbanked game items and experience are outside these statistics.

## Trading records and delivery dates

Orders, positions, conditional orders and trade history are grouped by trading type across all pairs. Records show UTC creation/update times; orders also show fill and completion times. Newest records appear at the top left. Switching record tabs keeps the original trading/account page as the return destination. Reopening a list starts from its newest page.

Closed positions show realized PnL, funding and paid fees. Unfilled orders that were cancelled do not count as historical positions. The engine clears live size and entry price at full close; these live fields are not presented as historical execution values. Closing orders remain in order history.

For a future, open **Contract information → Delivery date** or **Exchange settings → Schedule** to set the actual delivery date. Administrators can enter `2027-01-31`, `2027-01-31 12:00:00` (UTC), ISO time with an offset, or Unix milliseconds. Dates must be in the future. The contract information page displays the configured date and contract status.
