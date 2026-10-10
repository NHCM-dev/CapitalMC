# Command reference

[English](commands.en.md) · [简体中文](commands.zh-CN.md)

Use `/trade help` or `/cmcadmin help`, followed by a topic and optional page number. Help comes from the actual command tree. Press Tab for IDs, assets, users, modes, configuration fields and options.

`<...>` is required; `[...]` is optional. Quote names with spaces. Monetary input uses exact displayed units and supports decimals. Administrative changes require a trailing non-empty reason.

## Players

`/trade` is the main entry. `/account`, `/wallet`, `/order`, `/orders`, `/position`, `/positions` and `/conditional` are independent roots; the same groups work under `/trade`. `/user` is also an alias. All operations use the current trading account. Passwords are entered privately, never in command arguments.

| Syntax | Purpose |
| --- | --- |
| `/trade help [topic] [page]` | Browse help |
| `/trade gui [page]` | Open a GUI page |
| `/trade open <symbol>` | Open a market trading page |
| `/account create` | Register through private chat |
| `/account login <username>` | Log in with private password input |
| `/account switch <username>` | Switch trading account |
| `/account logout` | Log out |
| `/account info` | Account details |
| `/account password` | Change password privately |
| `/wallet balance [spot / contract] [asset]` | Available and reserved balances |
| `/wallet deposit <drop / container>` | Batch item or container deposit |
| `/wallet withdraw <asset> <amount> <output>` | Withdraw after review |
| `/wallet transfer <username> <asset> <amount>` | Send from spot account |
| `/wallet move <spot / contract> <asset> <amount>` | Move from selected account to the other |
| `/wallet peg <buy / sell> <asset> <amount>` | Buy or sell the base asset in a peg |
| `/trade markets` | List markets |
| `/trade market <symbol>` | Market information |
| `/order limit <symbol> <direction> <price> <quantity> [tif] [leverage] [margin]` | Limit order |
| `/order market <symbol> <direction> <quantity> [protection] [leverage] [margin]` | Market IOC order |
| `/order market-fok <symbol> <direction> <quantity> [protection] [leverage] [margin]` | Market FOK: all or none |
| `/order cancel <order>` | Cancel an execution order |
| `/order cancel-all` | Cancel open execution orders |
| `/orders [open / next / history [next]]` | Execution orders and history |
| `/positions [open / next / history [next]]` | Positions and history |
| `/trade trades [next]` | Executions |

Directions: `buy`, `sell`, `open-long`, `open-short` (contract only). Limit defaults: `GTC`, leverage `1`, `isolated`. Limit TIF: `GTC`, `IOC`, `FOK`, `PostOnly`. Market defaults: `IOC`, `auto` protection; `market-fok` requires full immediate execution. Contract margin: `isolated` or `cross`. Spot uses `auto`; use a limit order to cap a spot price. Optional arguments follow the listed order.

Withdrawal output: `items`, `small_chest`, `large_chest`, `shulker`. Finish a deposit selection with `done`, then confirm `yes`. `cancel` or empty input returns.

## Positions

Click a position in the list to open its detail page. It offers full/partial market close, full/partial limit close, add/reduce margin, and take-profit/stop-loss. Market close never asks for a price; it uses the native protection band. Limit close asks for the execution price. All close tickets are bound to the position and reduce-only. A market IOC may fill only partly; check the remaining position. A limit GTC can remain open.

| Syntax | Purpose |
| --- | --- |
| `/position` | Open the position list |
| `/position view <position>` | Open position details |
| `/position close market <position> [quantity]` | IOC close; omit quantity or use all for full close |
| `/position close limit <position> <price> [quantity]` | GTC close; omit quantity or use all for full close |
| `/position margin add <position> <amount>` | Add isolated margin |
| `/position margin remove <position> <amount>` | Release isolated margin subject to native risk |
| `/position protect <kind> market <position> <trigger> [quantity] [source] [oco]` | Position-bound market protection |
| `/position protect <kind> limit <position> <trigger> <price> [quantity] [source] [oco]` | Position-bound limit protection |

`quantity` is a displayed amount, or `all`; omitted means all. Full close uses the remaining size when submitted. Margin amounts are positive quote-asset amounts. Reduction is isolated-only and must preserve native initial/maintenance margin and liquidation safety. A rejection leaves balances unchanged.

## Take-profit / stop-loss

GUI: open a market → stop-loss / take-profit → create order. This ticket page supports spot buy/sell and contract opening orders. For closing protection, use Position details: set trigger source, market/limit execution, full/partial quantity, then stop-loss, take-profit, or an OCO pair. Prices are distinct: **trigger price** activates the order; **limit execution price** controls its resting order; **market protection price** bounds execution.

| Syntax | Purpose |
| --- | --- |
| `/conditional market <symbol> <direction> <kind> <source> <trigger> <quantity> [protection] [tif] [oco] [leverage] [margin]` | Market conditional order |
| `/conditional limit <symbol> <direction> <kind> <source> <trigger> <quantity> <price> [tif] [oco] [leverage] [margin]` | Limit conditional order |
| `/conditional [list [next]]` | Waiting conditional orders |
| `/conditional history [next]` | Waiting, activated, rejected and cancelled orders |
| `/conditional view <conditional>` | Trigger, execution and OCO details |
| `/conditional modify <conditional> <trigger> <quantity> <price>` | Modify a waiting order; market price is 0 |
| `/conditional cancel <conditional>` | Cancel a waiting order |

- `kind`: `stop-loss` or `take-profit`. `source`: `last`, `mark`, `index`; spot requires `last`. Contract position protection defaults to `mark`.
- Buy stop-loss and sell take-profit trigger at or above the trigger price. Sell stop-loss and buy take-profit trigger at or below it. Already reached thresholds may activate immediately. Missing or stale price sources cannot provide dependable protection.
- A waiting condition reserves no funds. Activation rechecks funds, margin, position, limits and the order book. `Activated` means an execution order was accepted; it does not mean fully filled. Check the execution order ID, position remainder and rejection reason.
- Position protection is **reduce-only and position-bound**. `all` records the remaining size when submitted; it does not follow later increases. General contract conditional tickets open positions; they are not closing protection.
- Automatic market protection is calculated from the trigger level, aligned to the price tick. The native current price band still applies when activated. A price gap, unavailable liquidity or insufficient funds can leave an IOC partly/unfilled, or cause rejection.
- `oco`: ID of another waiting condition belonging to your account and market; `0` means independent. Successfully activated orders cancel their linked sibling, including partial executions. Rejected activation leaves the sibling independent. Manually cancelling one also leaves its sibling independent.
- The position GUI OCO pair uses two durable submissions. If the second fails, the first may remain active or already have activated; its ID is shown for review. Do not assume atomic pair creation.
- Waiting orders can be modified or cancelled. Modification keeps source, kind, TIF, OCO and market protection fixed; enter `0` for a market execution price. To change fixed fields, cancel and create a new ticket. Activated execution orders use `/order cancel`, not conditional cancellation.
- AdvMatch persists waiting orders and links in its own WAL. CapitalMC records request IDs and application audit separately. Unknown outcomes stay in review; do not resubmit blindly. Minecraft saves are a third persistence boundary.

### Examples

```text
/position close market 42
/position close market 42 2
/position close limit 42 120
/position margin remove 42 10
/position protect take-profit market 42 120 all mark
/position protect stop-loss market 42 90 all mark 17
/conditional limit 1 sell take-profit last 120 2 119 GTC
/conditional market 2 open-long stop-loss mark 110 1 auto IOC 0 2 isolated
/conditional view 17
/conditional modify 17 91 2 0
```

Use actual IDs from `/trade markets`, `/positions` and `/conditional`. The OCO example assumes condition `17` is your waiting take-profit on the same market.

## Administration

/cmcadmin requires owner permission. Console can query and configure; item registration, binding, icons and GUI require a player.

### Asset registration, units and supply

| Syntax | Action |
| --- | --- |
| `/cmcadmin asset add-item <name> <display> <reason>` | Register |
| `/cmcadmin asset add-virtual <name> <display> <scale> <reason>` | Electronic |
| `/cmcadmin asset bind <asset> <reason>` | Bind |
| `/cmcadmin asset burn <asset> <amount> <reason>` | Burn |
| `/cmcadmin asset disable <asset> <reason>` | Asset disable |
| `/cmcadmin asset enable <asset> <reason>` | Asset enable |
| `/cmcadmin asset mint <asset> <amount> <reason>` | Mint |
| `/cmcadmin asset rename <asset> <display> <reason>` | Display |
| `/cmcadmin asset unbind <asset> <reason>` | Unbind |
| `/cmcadmin asset units <asset> <unitSize> <minimumDeposit> <minimumWithdrawal> <reason>` | Asset units |
| `/cmcadmin asset info <asset>` | Query / search |
| `/cmcadmin asset list` | Query / search |
| `/cmcadmin asset search <search>` | Query / search |
| `/cmcadmin asset supply` | Query / search |

### Market configuration and information

| Syntax | Action |
| --- | --- |
| `/cmcadmin market add future <name> <base> <quote> <price> <expiry> <reason>` | Symbol |
| `/cmcadmin market add perpetual <name> <base> <quote> <price> <reason>` | Symbol |
| `/cmcadmin market add spot <name> <base> <quote> <reason>` | Symbol |
| `/cmcadmin market deliver <symbol> <price> <reason>` | Deliver |
| `/cmcadmin market expire <symbol> <reason>` | Expire |
| `/cmcadmin market fees <symbol> <makerPercent> <takerPercent> <reason>` | Fees |
| `/cmcadmin market funding <symbol> <percent> <reason>` | Funding percent |
| `/cmcadmin market halt <symbol> <reason>` | Halt |
| `/cmcadmin market icon <symbol> <reason>` | Icon |
| `/cmcadmin market lot <symbol> <baseAmount> <minimumLots> <lotStep> <reason>` | Lot |
| `/cmcadmin market mark <symbol> <price> <reason>` | Mark |
| `/cmcadmin market minimum-notional <symbol> <amount> <reason>` | Min notional |
| `/cmcadmin market minimum-quantity <symbol> <quantity> <reason>` | Min quantity |
| `/cmcadmin market quantity-step <symbol> <quantity> <reason>` | Quantity step |
| `/cmcadmin market reference set <symbol> <field> <value> <reason>` | Reference |
| `/cmcadmin market reference source-add <symbol> <sourceType> <sourceId> <weight> <numerator> <denominator> <reason>` | Add / replace a reference source |
| `/cmcadmin market reference source-remove <symbol> <sourceType> <sourceId> <reason>` | Remove a reference source |
| `/cmcadmin market resume <symbol> <reason>` | Resume |
| `/cmcadmin market rules set <symbol> <field> <value> <reason>` | Rules |
| `/cmcadmin market schedule set <symbol> <field> <value> <reason>` | Schedule |
| `/cmcadmin market tick <symbol> <price> <reason>` | Price tick |
| `/cmcadmin market info <symbol>` | Query / search |
| `/cmcadmin market list` | Query / search |
| `/cmcadmin market reference show <symbol>` | Query / search |
| `/cmcadmin market rules show <symbol>` | Query / search |
| `/cmcadmin market schedule show <symbol>` | Query / search |
| `/cmcadmin market search <search>` | Query / search |

### Trading users and investigations

| Syntax | Action |
| --- | --- |
| `/cmcadmin user ban <username> <reason>` | Ban trading account |
| `/cmcadmin user unban <username> <reason>` | Unban trading account |
| `/cmcadmin user info <username>` | Query / search |
| `/cmcadmin user list` | Query / search |
| `/cmcadmin user orders <username> <page>` | Query / search |
| `/cmcadmin user positions <username> <page>` | Query / search |
| `/cmcadmin user search <search>` | Query / search |
| `/cmcadmin user trades <username> <page>` | Query / search |

### Available and reserved balances

| Syntax | Action |
| --- | --- |
| `/cmcadmin balance add contract <username> <asset> <amount> <reason>` | Increase balance |
| `/cmcadmin balance add spot <username> <asset> <amount> <reason>` | Increase balance |
| `/cmcadmin balance remove contract <username> <asset> <amount> <reason>` | Decrease balance |
| `/cmcadmin balance remove spot <username> <asset> <amount> <reason>` | Decrease balance |
| `/cmcadmin balance show contract <username>` | Query / search |
| `/cmcadmin balance show spot <username>` | Query / search |

### Pegged exchange and reserves

| Syntax | Action |
| --- | --- |
| `/cmcadmin peg configure <base> <quote> <baseAmount> <quoteAmount> <percent> <minimum> <maximum> <buyEnabled> <sellEnabled> <enabled> <reason>` | Peg human |
| `/cmcadmin peg issue <username> <asset> <amount> <reason>` | Issue |
| `/cmcadmin peg reserve-in <username> <asset> <amount> <reason>` | Reserve in |
| `/cmcadmin peg reserve-out <username> <asset> <amount> <reason>` | Reserve out |

### Operation records and recovery

| Syntax | Action |
| --- | --- |
| `/cmcadmin journal refund <operation> <reason>` | Refund |
| `/cmcadmin journal review commands <operation> <reason>` | Review commands |
| `/cmcadmin journal review items <operation> <reason>` | Review items |
| `/cmcadmin journal review pegs <operation> <reason>` | Review pegs |
| `/cmcadmin journal review transfers <operation> <reason>` | Review transfers |
| `/cmcadmin journal list admin <page>` | Query / search |
| `/cmcadmin journal list commands <page>` | Query / search |
| `/cmcadmin journal list items <page>` | Query / search |
| `/cmcadmin journal list pegs <page>` | Query / search |
| `/cmcadmin journal list transfers <page>` | Query / search |
| `/cmcadmin journal search admin <search> <page>` | Query / search |
| `/cmcadmin journal search commands <search> <page>` | Query / search |
| `/cmcadmin journal search items <search> <page>` | Query / search |
| `/cmcadmin journal search pegs <search> <page>` | Query / search |
| `/cmcadmin journal search transfers <search> <page>` | Query / search |

### Exchange status and maintenance

| Syntax | Action |
| --- | --- |
| `/cmcadmin exchange checkpoint  <reason>` | Checkpoint |
| `/cmcadmin exchange halt  <reason>` | Halt |
| `/cmcadmin exchange resume  <reason>` | Resume |
| `/cmcadmin exchange audit` | Query / search |
| `/cmcadmin exchange status` | Query / search |

- `/cmcadmin order <order>`: inspect an individual record.
- `/cmcadmin position <position>`: inspect an individual record.

- `/cmcadmin gui`: open administration.

## Parameters and rules

- `asset`, `base`, `quote`: registered asset name, ID or uniquely matching display name. `symbol`, `order`, `position`: exchange IDs. `username`: trading login name, not Minecraft name.
- `display`: quoted display name; item registration / binding uses the held item. `scale`: native decimal precision. `unitSize`: actual units represented by one displayed unit.
- `expiry`: Unix timestamp in milliseconds. `percent`, maker/taker fees: human percentages (`0.05` means 0.05%).
- `market lot`: base amount per lot/contract, then minimum lots and lot step. Minimum quantities and steps must satisfy native rules.
- `peg configure`: base/quote, base amount, quote amount, fee percentage, minimum/maximum base amount, buy enabled, sell enabled, enabled. Amount input is base quantity. `peg issue` moves issuer funds to a user; `asset mint` creates electronic supply in the issuer account.
- `market rules/schedule/reference set`: one scalar field per command. Tab includes nested risk/fee paths, booleans and enum values. These advanced fields use native integer units (rates in millionths, timestamps/durations in milliseconds); use `tick`, `fees`, `minimum-quantity`, `minimum-notional`, `funding` and `lot` for displayed-unit inputs. Source add/remove manages the source list without JSON. A source has type `spot` or `external`, ID, positive weight and numerator/denominator for unit conversion.
- `balance add/remove`: audited creation/removal of available balance, subject to native withdrawal risk checks. Reserved funds are not silently released. This differs from transferring issuer reserves.
- `user ban/unban`: persist an application access restriction; ban revokes all sessions and cancels pending item selection/chat. Balances, positions and accepted orders remain in AdvMatch. Existing orders can still execute, and native risk/funding/settlement continue. Unban does not restore sessions.
- Journal pages contain 20 entries. Review requires an operation ID and evidence as the reason; unknown outcomes are not automatically retried. Rule changes generally require no open exposure, except a change limited to the tick-aware protection flag.

## Examples

```text
/cmcadmin asset add-item diamond "Diamond" Register held diamond
/cmcadmin asset add-virtual usd "Server Dollar" 2 Create currency
/cmcadmin market add spot DIA_USD diamond usd Open spot market
/cmcadmin market add perpetual DIA_PERP diamond usd 1 Open contract market
/cmcadmin balance add spot alice usd 100.50 Test funding
/cmcadmin market fees 1 0.01 0.05 Configure fees
/cmcadmin market rules set 1 selfTradePrevention CancelBoth Prevent self trades
/cmcadmin user trades alice 1
/cmcadmin user ban alice Trading restriction
/trade account login alice
/trade order limit 1 buy 1.25 10
/trade order market 2 open-long 3 auto 5 cross
/position close market 1 1
```
