# CapitalMC

[English](README.md) · 简体中文

基于 **AdvMatch 2.0.1** 的 Minecraft 服务端交易所。通过物品栏界面交易实物与电子资产，玩家无需安装客户端模组。

## 功能

- 独立交易账户，每个 Minecraft UUID 最多创建三个账户。
- 现货、交割合约和永续合约，支持限价 / 市价、止盈止损、OCO、仓位详情和保证金管理。
- 实时订单簿、搜索、价格统计与可配置盘口深度。
- 批量物品充值、提款、转账、账户划转和锚定兑换。
- 管理员配置资产、交易对、供应量、手续费、风控及异常核对。
- 中英文界面、小数输入与私有聊天问答。
- 跨服共享交易所，支持 LOCAL / REMOTE、独立后台和合并数据订阅。

## 安装与构建

需要 **Minecraft 26.3**、**Fabric Loader 0.19.5+**、**适配 26.3 的 Fabric API** 和 **JDK 25**。

```powershell
.\gradlew.bat build
```

将 `build/libs/CapitalMC-0.5.2-SNAPSHOT.jar` 和 Fabric API 放入服务器的 `mods` 目录。SGui 与 AdvMatch 已内置。当前 AdvMatch 包支持 **Windows amd64 和 Linux amd64（glibc）**；其他平台需要构建对应原生 SDK。当前 Linux 产物要求 glibc 2.38+，参见 AdvMatch 构建说明。

开发时使用 `.\gradlew.bat runServer`，首次启动前阅读并接受 Minecraft EULA。完整构建会执行单元测试与 Minecraft GameTest。

## 开始使用

多服部署参见[跨服配置说明](documentation/cross-server.zh-CN.md)。独立后台使用 `CapitalMC-0.5.2-SNAPSHOT-exchange-server.jar`，Minecraft 连接配置位于 `config/capitalmc.json`。

1. 输入 `/trade gui`，注册或登录交易账户。
2. 充值已注册的资产，选择交易对及现货或合约类型。
3. 设置订单参数，核对预览，在私有聊天中确认。

管理员通过 `/cmcadmin gui` 打开管理页，需要 owner 权限。新服务器应先注册资产、配置交易对，再开放交易。

从“我的账户”的“我的订单与持仓”，或交易页的订单、持仓按钮进入。按现货、永续、交割类型查看该类型所有交易对的记录，包括历史订单和止盈止损单；现货不显示持仓。

[中文使用说明](documentation/guide.zh-CN.md) · [English guide](documentation/guide.en.md)

[完整命令参考](documentation/commands.zh-CN.md)

[容量、实测与恢复](documentation/performance.zh-CN.md)

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

AdvMatch 管理财务账本，CapitalMC 管理 Minecraft 身份和实物托管。业务数据使用二进制 WAL 和状态快照；停服时备份整个 `capitalmc` 目录和世界。旧 JSON 导入需要原文件及其相邻 WAL。结果未知的交付须人工核对。

[持久化配置与恢复](documentation/persistence.zh-CN.md)
