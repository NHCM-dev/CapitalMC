# 持久化

CapitalMC 的业务数据使用带校验的二进制 WAL 和状态快照。JSON 仅用于可编辑的连接/持久化配置和语言资源。Minecraft 身份、托管、绑定和审计仍由现有应用服务负责；财务状态归 AdvMatch 管理。

## 配置

停服后编辑 `capitalmc/persistence.json`，重启生效。默认值：

```json
{
  "directory": "persistence",
  "exchangeSnapshotEverySequences": 10000,
  "exchangeRetainedSnapshots": 2,
  "flushEveryCommands": 256,
  "flushIntervalMilliseconds": 10,
  "metadataSnapshotEverySequences": 128,
  "metadataSnapshotWalBytes": 4194304,
  "metadataRetainedSnapshots": 2
}
```

交易所达到命令数间隔时创建快照。应用存储达到序号间隔或 WAL 字节阈值之一时创建快照；应用序号代表一次已提交的编辑组，不是一笔交易。快照保存当前保留的状态，重启只重放快照后的 WAL。间隔越大，快照越少，但恢复工作越多。完整快照的编码和刷盘仍在交易所主处理线程或元数据 IO 线程同步完成，应在实际部署磁盘上测量延迟。

## 文件与迁移

新实例默认使用 `capitalmc/persistence/`，包含独立的 `exchange`、`users`、`services`、`shared-services` 和 `data-version` 存储，各自有 WAL、`snapshots/` 目录和二进制快照清单。原生 `archive/` 分段保留完整已提交交易历史，启动不读取它们；近期 outbox 查询跳过旧分段。历史归档占用会随交易增长。

`*.store-path` 记录各存储的相对位置，修改 `directory` 不会让已有实例转到空账本。迁移目录时停止所有后台和网关，复制完整存储，修改对应位置文件并验证恢复后再开放。已有原生 `exchange/` 在升级时保持原位置。

旧 `users.json` v1、`users.dat`、应用 JSON 文件及其**相邻 `.wal`** 会一次性导入；原应用文件保持原样。原生旧重放快照会重放一次，转换成状态快照并保留持久化历史归档。迁移后的数据不能用于降级，应恢复升级前完整且兼容的备份。

## 持久化边界与恢复

应用写入在 WAL 刷盘后确认，一组编辑共享刷盘。原生批量提交的当前序号和持久化序号可能不同，实物托管流程等待明确的持久化边界。快照依次刷 WAL、发布原生历史归档、发布完整快照和清单，再移除已覆盖的活动 WAL。快照保留数量不影响交易归档。

完整记录损坏、序号断档、缺失必要快照或清单时拒绝加载，仅允许丢弃物理上未写完的 WAL 尾部。结果未知的操作保持隔离，重启不意味着可以再次交付物品。Minecraft 世界存盘和交易所 WAL 是两个独立持久化边界，不能宣称实物托管与账本原子提交。

停服时一起备份整个 `capitalmc` 目录和相关世界，包含位置文件、清单、WAL、快照及归档。禁止两个后台同时写同一个原生账本。配置仅在启动时读取；当前不提供复制和自动故障转移。
