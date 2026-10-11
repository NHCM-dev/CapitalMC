# CapitalMC

English · [简体中文](README.zh-CN.md)

A server-side Minecraft exchange powered by **AdvMatch 2.0.0**. Trade items and electronic assets through an inventory GUI. Players do not need a client mod.

## Features

- Separate trading accounts, with up to three accounts per Minecraft UUID.
- Spot, futures and perpetual trading; limit/market orders, take-profit/stop-loss, OCO, position details and margin controls.
- Live order books, search, price statistics and configurable depth.
- Batch item deposits, withdrawals, transfers and pegged currency exchange.
- Administrator tools for assets, markets, supply, fees, risk limits and recovery.
- English and Chinese UI, decimal input and private chat prompts.
- Shared exchange across Minecraft servers, LOCAL/REMOTE modes and a standalone backend; coalesced data subscriptions.

## Install and build

Requires **Minecraft 26.3**, **Fabric Loader 0.19.5+**, **Fabric API for 26.3** and **JDK 25**.

```powershell
.\gradlew.bat build
```

Place `build/libs/CapitalMC-0.5.1-SNAPSHOT.jar` and Fabric API in the server's `mods` folder. SGui and AdvMatch are bundled. The included AdvMatch library supports **Windows amd64 and Linux amd64 (glibc)**; other platforms need a matching native SDK build. The current Linux bundle requires glibc 2.38+; see the AdvMatch build documentation.

For development, run `.\gradlew.bat runServer` and accept the Minecraft EULA before starting the server. The build runs unit tests and Minecraft GameTests.

## Get started

For multiple servers, follow [shared exchange setup](documentation/cross-server.en.md). The standalone owner is `CapitalMC-0.5.1-SNAPSHOT-exchange-server.jar`; Minecraft gateways use `config/capitalmc.json`.

1. Run `/trade gui`, then register or log in to a trading account.
2. Deposit a registered asset, select a market and choose spot or contract trading.
3. Set the order details, review the summary and confirm in private chat.

Use `/cmcadmin gui` for administration; owner permission is required. A new server needs registered assets and configured markets before players can trade.

Open **My orders and positions** from My Account, or use the order/position buttons on a trading page. Browse all markets within spot, perpetual or futures trading, including order history and take-profit/stop-loss orders. Spot trading has no positions.

[Player and administrator guide](documentation/guide.en.md) · [中文使用说明](documentation/guide.zh-CN.md)

[Command reference](documentation/commands.en.md)

[Capacity, measurements and recovery](documentation/performance.en.md)

## Screenshots

| Contract trading | Trading symbols |
| --- | --- |
| ![Order review with price, quantity and margin](documentation/screenshots/contract-trading.png) | ![Market list and selected contract filter](documentation/screenshots/trading-symbols.png) |

| Account balances | Login and accounts |
| --- | --- |
| ![Available, reserved and total balances](documentation/screenshots/account-balances.png) | ![Account registration and selection](documentation/screenshots/login-and-accounts.png) |

| Administration | Exchange status |
| --- | --- |
| ![Asset and market configuration tools](documentation/screenshots/administration.png) | ![Persistence and exchange status](documentation/screenshots/exchange-status.png) |

## Data and backups

AdvMatch owns the financial ledger; CapitalMC manages Minecraft identities and physical custody. Business data uses binary WAL and state snapshots. Back up the entire `capitalmc` directory and worlds while stopped. Legacy JSON imports require the original file and its adjacent WAL. Uncertain deliveries require review.

[Persistence configuration and recovery](documentation/persistence.en.md)
