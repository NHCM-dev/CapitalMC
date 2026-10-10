# CapitalMC

[English](README.md) · 简体中文

基于 **AdvMatch 1.2.0** 的 Minecraft 服务端交易所。通过物品栏界面交易实物与电子资产，玩家无需安装客户端模组。

## 功能

- 独立交易账户，每个 Minecraft UUID 最多创建三个账户。
- 现货、交割合约和永续合约，支持限价 / 市价、止盈止损、OCO、仓位详情和保证金管理。
- 实时订单簿、搜索、价格统计与可配置盘口深度。
- 批量物品充值、提款、转账、账户划转和锚定兑换。
- 管理员配置资产、交易对、供应量、手续费、风控及异常核对。
- 中英文界面、小数输入与私有聊天问答。

## 安装与构建

需要 **Minecraft 26.3**、**Fabric Loader 0.19.5+**、**适配 26.3 的 Fabric API** 和 **JDK 25**。

```powershell
.\gradlew.bat build
```

将 `build/libs/CapitalMC-0.2.0-SNAPSHOT.jar` 和 Fabric API 放入服务器的 `mods` 目录。SGui 与 AdvMatch 已内置。当前 AdvMatch 包支持 **Windows amd64**；其他平台需要构建对应原生 SDK。

开发时使用 `.\gradlew.bat runServer`，首次启动前阅读并接受 Minecraft EULA。完整构建会执行单元测试与 Minecraft GameTest。

## 开始使用

1. 输入 `/trade gui`，注册或登录交易账户。
2. 充值已注册的资产，选择交易对及现货或合约类型。
3. 设置订单参数，核对预览，在私有聊天中确认。

管理员通过 `/cmcadmin gui` 打开管理页，需要 owner 权限。新服务器应先注册资产、配置交易对，再开放交易。

[中文使用说明](documentation/guide.zh-CN.md) · [English guide](documentation/guide.en.md)

[完整命令参考](documentation/commands.zh-CN.md)

## 截图

| 合约交易 | 交易对列表 |
| --- | --- |
| ![价格、数量与保证金的下单预览](documentation/screenshots/contract-trading.png) | ![交易对信息及合约筛选](documentation/screenshots/trading-symbols.png) |

| 账户余额 | 登录与账户 |
| --- | --- |
| ![可用、冻结与总余额](documentation/screenshots/account-balances.png) | ![注册及选择交易账户](documentation/screenshots/login-and-accounts.png) |

| 管理页面 | 交易所状态 |
| --- | --- |
| ![资产与交易规则管理](documentation/screenshots/administration.png) | ![持久化及交易所状态](documentation/screenshots/exchange-status.png) |

## 数据与备份

AdvMatch 管理金融账本，CapitalMC 管理 Minecraft 身份和实物。停服后一起备份世界、交易所持久化目录及整个 `capitalmc` 目录。`services.json` 必须与 `services.json.wal` 一起保存。交付结果不确定时应人工核对，不自动重试。
