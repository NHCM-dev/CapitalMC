# CapitalMC

Minecraft 26.3 / Fabric 服务端交易模组，Java 25。玩家无需客户端模组，通过 `/trading` 使用缓存的交易 GUI。

已接入本地 AdvMatch：独立账户注册和登录（每个 Minecraft UUID 最多创建 3 个账户）、现货与合约交易、订单簿、撤单、持仓和减仓、资产查询、充值、提款、账户划转、玩家转账、电子货币发行与锚定兑换。文本输入使用私有聊天问答，默认 30 秒超时。

账户与品种列表支持搜索、现货/合约筛选和翻页。资产以注册的物品、组件与显示名称呈现。GUI 独立调度线程每 50ms 请求一次刷新，实际页面更新和世界修改在服务器线程执行；异步查询同一页面最多有一个进行中的请求。

```powershell
.\gradlew.bat build
.\gradlew.bat runServer
```

使用 JDK 25。首次运行需要自行阅读并接受 Minecraft EULA。产物为 `build/libs/CapitalMC-0.1.0-SNAPSHOT.jar`，已嵌入 SGui 和 AdvMatch；服务器另外安装适配 26.3 的 Fabric API。当前 `libs/advmatch-1.1.0.jar` 包含 Windows amd64 原生交易引擎；其他平台需从适配后的 AdvMatch 源码构建并重新打包对应原生库。

| 命令 | 用途 |
| --- | --- |
| `/trading` / `/capital gui` | 打开交易中心 |
| `/capital` | 用户账户、余额、充值提款、划转、兑换、交易和历史命令 |
| `/testgui <page>` | 临时预览页面，未选择交易对/资产时会显示选择入口 |
| `/cmcadmin` | 查看管理员资产、交易对、货币与核对命令，要求 owner 权限 |

实际物品资产按完整 `ItemStack` 模板匹配，可注册保护 IV 附魔书等组件物品。电子资产只存在于交易所余额中，不能生成物品。

充值支持批量：丢物模式中 Q 逐个选择、Ctrl+Q 选择整组，可切换多个背包槽；容器模式中依次右键小/大箱子或潜影盒。聊天输入 `done` 汇总，再输入 `yes` 确认。预览不会移走物品，`cancel` 或超时取消；确认前整批复查。账户使用 `capitalmc/users.dat` 二进制持久化，首次启动自动迁移旧 users.json，保留原文件。

使用方法、公开 API、整数与小数转换、容器额度及交割日志见 [服务器与交易所接入](docs/server-exchange.md)。暂停窗口和聊天输入见 [GUI 暂停工具](docs/gui-input.md)。

完整构建自动运行真实 AdvMatch 金融测试与 Minecraft GameTest：覆盖原子余额事务、储备兑换、请求去重、现货/合约，以及服务器中的丢物、小/大箱子、潜影盒、实体交付、页面渲染与 GUI 暂停恢复。实际客户端的显示和网络交互还需验收。

用户及管理命令见 [命令参考](docs/commands.md)。AdvMatch 已增加原生 `BalanceTransaction`，多资产入账、跨用户转账及储备兑换全部使用原子事务。世界与交易所账本仍属于独立存储，异常交付保留证据并人工核对。

A–G 重构、完整功能、兼容迁移、异常恢复与验证边界见 [重构状态](docs/refactor-status.md)，长期开发约束见 [AGENTS.md](AGENTS.md)。元数据备份请同时保存 services.json 与 services.json.wal。
