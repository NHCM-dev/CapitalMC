# 跨服交易所

[English](cross-server.en.md) · [README](../README.zh-CN.md)

多个 Minecraft 服务器连接**同一个** AdvMatch 账本。交易账户、余额、挂单、仓位、资产、物品绑定和交易规则共享；世界、背包、登录会话、实物托管记录各自保留。

## 独立后台

需要 Java 25；支持 Windows amd64 和 Linux amd64（glibc 2.38+）。后台不需要 Minecraft 或 Fabric。

```text
java --enable-native-access=ALL-UNNAMED -jar CapitalMC-0.3.0-SNAPSHOT-exchange-server.jar ./capitalmc
```

首次启动生成 `capitalmc/connection.json` 和随机密钥 `token`，默认监听 `127.0.0.1:25570`。独立后台始终在 LOCAL 模式监听。按 Ctrl+C 停止后修改配置。密钥只交给受信任的 Minecraft 服务器；连接方可以调用交易所管理接口，玩家客户端不应直接连接。

各 Minecraft 服务器的 `config/capitalmc.json` 设置为：

```json
{
  "mode": "REMOTE",
  "host": "127.0.0.1",
  "port": 25570,
  "token": "COPY_THE_OWNER_TOKEN_FROM_CONNECTION_JSON"
}
```

将示例密钥替换为后台配置中的实际密钥。其余字段自动补默认值，包括随机 `instanceId`。每台服务器的实例 ID 必须不同，之后保持不变。首次成功连接会保存 `expectedExchangeId`，防止误接另一个账本。不要直接克隆另一台服务器的整份配置或托管数据目录。

先启动后台，再启动 Minecraft 服务器。在任意 Minecraft 服务器使用原来的管理员 GUI 或命令配置资产与交易对。玩家照常使用 `/trade` 和界面；账户凭据、余额、订单和仓位跨服共享。各服应使用兼容的版本及物品组件注册表。

## Minecraft 服务器作为后台

默认 `mode: LOCAL`，保留原有的内置交易所。在该服 `config/capitalmc.json` 开启 `listen: true`，其他 REMOTE 服务器即可用它的地址、端口、密钥连接。该服关闭后，所有连接方暂停交易。希望后台不依赖 Minecraft 运行时，使用独立后台产物。

## 配置

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `mode` | `LOCAL` | 持有交易所；`REMOTE` 连接后台 |
| `listen` | `false` | 内置 LOCAL 是否开放连接 |
| `bind` / `host` | `127.0.0.1` | 监听地址 / 后台地址 |
| `port` | `25570` | 后台 TCP 端口 |
| `instanceId` | 随机 UUID | 各服唯一且固定的实例标识 |
| `token` | 随机 64 字符密钥 | 身份验证 |
| `expectedExchangeId` | 首次连接后保存 | 固定连接的交易所身份 |
| `tls` | `false` | 加密连接 |
| `keyStore` / `keyStorePassword` | 空 | 后台 PKCS12 证书与密码 |
| `allowPlaintextNetwork` | `false` | 明确允许可信内网明文连接 |
| `maxConnections` | `64` | 后台连接数上限，最大 256 |
| `maxOutstanding` | `256` | 每个实例等待结果的请求数上限，最大 4096 |
| `requestTimeoutMillis` | `30000` | 响应超时；已发送请求的结果可能未知 |
| `maxFrameBytes` | `4194304` | 单个数据包上限，最大 16 MiB |

同机使用回环地址即可。非回环地址默认要求 TLS，或双方显式允许可信内网明文连接。使用 TLS 时双方设置 `tls: true`，后台配置 PKCS12 `keyStore`；相对路径以配置文件目录为基准。证书需要覆盖客户端填写的后台主机名。客户端使用 Java 默认信任库；自签 CA 可通过 `-Djavax.net.ssl.trustStore=绝对路径/trust.p12` 和相应 Java 信任库密码参数指定。后台端口应只允许 Minecraft 服务器访问。

## 数据更新与断线

账户余额列表、交易对列表、盘口和保留的成交统计使用共享订阅：相同查询共用后台数据源，连续更新合并，闲置订阅会释放。界面普通刷新间隔为 250 毫秒；充值提款等操作前的余额检查仍使用即时查询。数据超过订阅快照上限时，完整列表继续使用游标分页。

断线后实时数据标记为过期，恢复连接后自动恢复订阅。**下单、转账和其他修改不会自动重发。** 响应丢失可能意味着后台已经执行，应检查发起服务器的操作记录，通过现有核对功能处理。提款只在发起服务器交付实物；箱子、潜影盒额度及未完成托管也留在该实例。

实物充值、提款会保留确认时使用的物品绑定与单位配置，后台在接受记账请求前核对这些证据。绑定已变更、资产已停用或证据不完整时拒绝记账；已经托管的物品继续保留在原服务器，进入核对流程。旧版未完成操作缺少绑定证据时也需要人工核对，不能自动重新入账。

后台目前是单点，不包含复制或自动故障切换。订阅提供合并后的状态，不是用于记账的持久事件流。实际承载量取决于行情活跃度、订阅量、持久化和 Minecraft 实例，需要按实际场景压测。

## 已有数据迁移与备份

迁移已有 LOCAL 交易所时，先停止所有服务器并备份。将原有**完整** `capitalmc` 目录复制到独立后台，保留原生和账户 ID 的对应关系，包括 `users.dat`（或旧 `users.json`）、`exchange/`、`exchange.id`，以及所有元数据文件和相邻 `.wal`。后台首次创建 `shared-services.json` 时会从 `services.json` 导入全局配置。

原 Minecraft 服务器切换 REMOTE 前，将它的 `users.dat` / `users.json` 和 `exchange/` 移至活动目录之外归档。该服原来的 `services.json` **及** `services.json.wal` 必须保留，它们包含本服实物托管与恢复证据。REMOTE 发现仍有本地账户注册表或交易所目录时会拒绝启动，避免将旧 ID 误接到其他账本。若有未核对的实物操作，应先完成核对再开放充值提款。

备份应在停服后覆盖后台完整目录，以及每个 Minecraft 实例的世界和完整 `capitalmc` 目录。原生 WAL、共享配置、账户文件、本地操作日志、Minecraft 世界保存是不同持久化边界，不能保证跨存储原子性。全局管理操作使用独占配置租约和比较更新；中断的配置操作可能需要管理员核对。

Minecraft 产物为 `build/libs/CapitalMC-0.3.0-SNAPSHOT.jar`，独立后台为 `build/libs/CapitalMC-0.3.0-SNAPSHOT-exchange-server.jar`。`./gradlew.bat build` 同时构建两者。
