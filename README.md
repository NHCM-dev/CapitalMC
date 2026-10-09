# CapitalMC

Minecraft **26.3** 的 Fabric 服务端模组开发骨架。通过 SGui 与命令接入服务器内置的电子交易系统，为现货、合约、账户与持仓提供游戏内入口。玩家无需安装客户端模组。

## 项目标识

- 名称：`CapitalMC`
- Java 包与 Maven group：`nhcm.capitalmc`
- Fabric mod ID：`nhcm_capitalmc`（Fabric 不允许 mod ID 包含 `.`）
- Java 25、Gradle Wrapper 9.7.1、Loom 1.18.3
- Fabric Loader 0.19.5、Fabric API 0.161.0+26.3、SGui 2.2.1+26.3

26.3 使用未混淆的 Minecraft 类名，不需要 Yarn mappings 或旧版 remap 配置。

## 开发与构建

安装 JDK 25，并将 IDE 的 Gradle JVM / `JAVA_HOME` 指向它。在项目根目录执行：

```powershell
.\gradlew.bat build
.\gradlew.bat runServer
```

Linux / macOS 使用 `./gradlew`。首次启动开发服务器后，阅读并接受 Minecraft EULA，再将 `run/eula.txt` 中的 `eula` 设置为 `true`。开发世界与服务器配置保存在被忽略的 `run/` 目录。

构建产物在 `build/libs/CapitalMC-0.1.0-SNAPSHOT.jar`；`-sources.jar` 是源码包。生产服务器使用 Minecraft 26.3、Java 25、Fabric Loader 0.19.5 或更新兼容版本，并另行安装适配 26.3 的 Fabric API。SGui 已嵌入模组 JAR，无需另外安装。

## 游戏命令

| 命令 | 功能 |
| --- | --- |
| `/capitalmc` | 打开交易中心 |
| `/capitalmc spot` | 打开现货市场入口 |
| `/capitalmc contract` | 打开合约市场入口 |
| `/capitalmc account` | 查询账户与持仓摘要 |
| `/capitalmc status` | 查询后端接入状态，支持服务器控制台 |

玩家命令默认无需 OP。当前页面是入口骨架，没有下单、撮合、保证金、清算或虚构余额。

## 接入交易系统

`nhcm.capitalmc.trading.TradingService` 是命令与 GUI 共用的后端接口。在服务器初始化期间调用 `CapitalMC.setTradingService(...)` 安装实现。默认实现 `UnavailableTradingService` 明确显示“尚未接入”。

接口目前提供状态、市场摘要、账户摘要；具体产品、订单、现货结算与合约规则应随服务器内置系统的接口定义扩展。资金与数量建议使用 `BigDecimal` 或固定精度整数；后端负责身份校验、订单幂等、余额与持仓一致性。

摘要接口在服务器线程调用，不能执行阻塞 I/O。后续接入数据库或异步服务时，应先获取异步结果，再通过服务器执行器回到主线程更新 GUI；避免在点击回调里等待网络或数据库。

## 衍生品开发者 API

资金费率、指数价、标记价及期货交割已提供公开接口与默认执行引擎。支持对应现货绑定、加权行情篮子、自定义数据源/算法、参数与未来结算时间调整。已注册合约由服务器每 20 tick 自动更新。

接入方法、默认公式、结算语义及测试说明见 [衍生品接口文档](docs/derivatives-api.md)。默认结算仅记录内存事件；余额划转与持仓交割由服务器账本处理器接入。

## 上游资料

- [Fabric 26.3 开发说明](https://fabricmc.net/2026/09/15/263.html)
- [Fabric 官方示例项目](https://github.com/FabricMC/fabric-example-mod/tree/26.3)
- [SGui](https://github.com/Patbox/sgui)
- [SGui Maven 版本列表](https://maven.nucleoid.xyz/eu/pb4/sgui/maven-metadata.xml)

本项目尚未指定分发许可证；发布前可按项目需求补充。
