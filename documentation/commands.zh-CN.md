# 命令参考

[English](commands.en.md) · [简体中文](commands.zh-CN.md)

使用 `/trade help` 或 `/cmcadmin help`，再填分组和可选页码。帮助根据实际命令树生成。按 Tab 补全 ID、资产、用户、模式、配置字段与选项。

`<...>` 必填，`[...]` 可选。名称含空格时用双引号。金额按显示单位精确换算，支持小数。管理变更末尾必须填写非空原因。

## 玩家

`/trade` 是主入口。`/account`、`/wallet`、`/order`、`/orders`、`/position`、`/positions`、`/conditional` 是独立主命令，同名分组也能放在 `/trade` 后使用。`/user` 是别名。所有操作使用当前登录交易账户，密码通过私有聊天填写，不写进命令。

| 语法 | 用途 |
| --- | --- |
| `/trade help [topic] [page]` | 分组分页帮助 |
| `/trade about [gui]` | 查看项目简介，或打开关于页面 |
| `/trade gui [page]` | 打开指定页面 |
| `/trade open <symbol>` | 打开交易对页面 |
| `/account create` | 私有聊天注册 |
| `/account login <username>` | 登录，私有聊天输入密码 |
| `/account switch <username>` | 切换交易账户 |
| `/account logout` | 退出登录 |
| `/account info` | 账户信息 |
| `/account password` | 私有聊天修改密码 |
| `/wallet balance [spot / contract] [asset]` | 可用与冻结余额 |
| `/wallet deposit <drop / container>` | 批量丢物或容器充值 |
| `/wallet withdraw <asset> <amount> <output>` | 核对后提款 |
| `/wallet transfer <username> <asset> <amount>` | 现货账户转账 |
| `/wallet move <spot / contract> <asset> <amount>` | 从指定账户类型划转到另一类型 |
| `/wallet peg <buy / sell> <asset> <amount>` | 按基础资产数量锚定买入或卖出 |
| `/trade markets` | 查看交易对 |
| `/trade market <symbol>` | 交易对信息 |
| `/order limit <symbol> <direction> <price> <quantity> [tif] [leverage] [margin]` | 限价下单 |
| `/order market <symbol> <direction> <quantity> [protection] [leverage] [margin]` | 市价 IOC 下单 |
| `/order market-fok <symbol> <direction> <quantity> [protection] [leverage] [margin]` | 市价 FOK：全部成交或不成交 |
| `/order cancel <order>` | 撤销委托单 |
| `/order cancel-all` | 撤销所有未完成委托 |
| `/orders [open / next / history [next]]` | 委托单及历史 |
| `/positions [open / next / history [next]]` | 仓位及历史 |
| `/trade trades [next]` | 成交记录 |

方向：`buy` 买入、`sell` 卖出、`open-long` 开多、`open-short` 开空（后两项仅合约）。限价默认 `GTC`、杠杆 `1`、`isolated`；有效期可选 `GTC`、`IOC`、`FOK`、`PostOnly`。市价默认 `IOC` 和 `auto` 保护；`market-fok` 要求立即全部成交。合约保证金模式为 `isolated` 逐仓或 `cross` 全仓。现货使用 `auto`，限制现货成交价格请用限价单。可选参数按表中顺序填写。

提款输出：`items` 物品、`small_chest` 小箱子、`large_chest` 大箱子、`shulker` 潜影盒。充值选择结束输入 `done`，核对后输入 `yes`；`cancel` 或空输入返回。

## 仓位

点击仓位列表中的仓位进入详情页，集中操作市价全平、市价部分平仓、限价全平、限价部分平仓、追加 / 减少保证金和止盈止损。市价平仓不询问价格，自动使用交易所价格保护；限价平仓才填写委托价格。所有平仓单绑定仓位且只减仓。市价 IOC 可能仅部分成交，请检查剩余仓位；限价 GTC 可以继续挂单。

| 语法 | 用途 |
| --- | --- |
| `/position` | 打开仓位列表 |
| `/position view <position>` | 打开仓位详情 |
| `/position close market <position> [quantity]` | IOC 市价平仓；省略数量或填 all 全平 |
| `/position close limit <position> <price> [quantity]` | GTC 限价平仓；省略数量或填 all 全平 |
| `/position margin add <position> <amount>` | 追加逐仓保证金 |
| `/position margin remove <position> <amount>` | 减少逐仓保证金，受原生风控限制 |
| `/position protect <kind> market <position> <trigger> [quantity] [source] [oco]` | 绑定仓位的市价止盈止损 |
| `/position protect <kind> limit <position> <trigger> <price> [quantity] [source] [oco]` | 绑定仓位的限价止盈止损 |

`quantity` 填显示数量或 `all`；省略就是全平。全平使用提交时的剩余仓位数量。保证金填正数，单位为报价资产。减少保证金仅限逐仓，必须满足原生初始 / 维持保证金及强平安全检查；拒绝时余额不变。

## 止盈止损

GUI：选择交易对 → 止盈止损 → 创建条件单。本页支持现货买卖和合约开仓。保护已有仓位请进仓位详情：选择触发依据、市价 / 限价执行、全仓 / 部分数量，再设置止盈、止损或 OCO 联动。三个价格各有含义：**触发价**决定什么时候激活；**限价委托价**决定挂单价格；**市价保护价**限制执行价格。

| 语法 | 用途 |
| --- | --- |
| `/conditional market <symbol> <direction> <kind> <source> <trigger> <quantity> [protection] [tif] [oco] [leverage] [margin]` | 市价条件单 |
| `/conditional limit <symbol> <direction> <kind> <source> <trigger> <quantity> <price> [tif] [oco] [leverage] [margin]` | 限价条件单 |
| `/conditional [list [next]]` | 等待中的条件单 |
| `/conditional history [next]` | 等待、触发、拒绝和撤销的条件单 |
| `/conditional view <conditional>` | 触发、委托与 OCO 详情 |
| `/conditional modify <conditional> <trigger> <quantity> <price>` | 修改等待条件单；市价单委托价格填 0 |
| `/conditional cancel <conditional>` | 撤销等待中的条件单 |

- `kind`：`stop-loss` 止损或 `take-profit` 止盈。`source`：`last` 成交价、`mark` 标记价、`index` 指数价；现货只能使用 `last`。仓位保护默认 `mark`。
- 买入止损、卖出止盈在价格达到或高于触发价时激活；卖出止损、买入止盈在达到或低于时激活。已满足条件可能立即激活。缺失或停滞的价格来源不能提供可靠保护。
- 等待条件单不预留资金。触发时重新检查余额、保证金、仓位、规则及盘口。`已触发` 表示委托受理，不代表全部成交；请查看委托 ID、剩余仓位和拒绝原因。
- 仓位保护**绑定仓位且仅减仓**。`all` 记录提交时的剩余数量，不会自动随加仓增长。普通合约条件单用于开仓，不能代替已有仓位的只减仓保护。
- 自动市价保护根据触发价估算并对齐价格步长；激活时仍受交易所当时的价格保护带限制。跳空、缺少流动性或资金不足可能造成部分成交、未成交或拒绝。
- `oco`：当前账户、同一交易对的另一条等待条件单 ID；`0` 表示独立。委托成功激活后撤销另一单，即使只部分成交；激活被拒绝时另一单保留为独立单。手动撤销一条时，另一条也会独立保留。
- 仓位 GUI 的 OCO 成对设置分两次持久化提交。第二条失败时，第一条可能仍有效或已触发；界面显示第一条 ID 供核查，不把成对创建视为原子操作。
- 等待中的条件单可修改或撤销。修改保留触发来源、类型、有效期、OCO 和市价保护价；市价委托价格填 `0`。更改固定字段请撤销后重新创建。已触发委托用 `/order cancel` 撤销。
- 等待条件单和关联由 AdvMatch WAL 持久化；CapitalMC 单独记录请求 ID 与应用审计。未知结果保留待核查，不盲目重新提交。Minecraft 世界存档是另一持久化边界。

### 示例

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

实际 ID 从 `/trade markets`、`/positions` 和 `/conditional` 查询。OCO 示例假设 `17` 是自己在同一交易对上的等待止盈单。

## 管理命令

/cmcadmin 需要 owner 权限。控制台可查询及配置；实物注册、绑定、图标和 GUI 需要玩家。

### 资产注册、单位及供应量

| 语法 | 操作 |
| --- | --- |
| `/cmcadmin asset add-item <name> <display> <reason>` | 注册实物资产 |
| `/cmcadmin asset add-virtual <name> <display> <scale> <reason>` | 注册电子资产 |
| `/cmcadmin asset bind <asset> <reason>` | 绑定物品 |
| `/cmcadmin asset burn <asset> <amount> <reason>` | 销毁货币 |
| `/cmcadmin asset disable <asset> <reason>` | 停用资产 |
| `/cmcadmin asset enable <asset> <reason>` | 启用资产 |
| `/cmcadmin asset mint <asset> <amount> <reason>` | 铸造货币 |
| `/cmcadmin asset rename <asset> <display> <reason>` | 修改显示名 |
| `/cmcadmin asset unbind <asset> <reason>` | 解除物品绑定 |
| `/cmcadmin asset units <asset> <unitSize> <minimumDeposit> <minimumWithdrawal> <reason>` | 修改资产单位与存提款下限 |
| `/cmcadmin asset info <asset>` | 查看 / 搜索 |
| `/cmcadmin asset list` | 查看 / 搜索 |
| `/cmcadmin asset search <search>` | 查看 / 搜索 |
| `/cmcadmin asset supply` | 查看 / 搜索 |

### 交易对配置及信息

| 语法 | 操作 |
| --- | --- |
| `/cmcadmin market add future <name> <base> <quote> <price> <expiry> <reason>` | 创建交易对 |
| `/cmcadmin market add perpetual <name> <base> <quote> <price> <reason>` | 创建交易对 |
| `/cmcadmin market add spot <name> <base> <quote> <reason>` | 创建交易对 |
| `/cmcadmin market deliver <symbol> <price> <reason>` | 合约交割 |
| `/cmcadmin market expiry <symbol> <expiry> <reason>` | 设置交割日期 |
| `/cmcadmin market expire <symbol> <reason>` | 合约到期 |
| `/cmcadmin market fees <symbol> <makerPercent> <takerPercent> <reason>` | 修改手续费 |
| `/cmcadmin market funding <symbol> <percent> <reason>` | 修改资金费率 |
| `/cmcadmin market halt <symbol> <reason>` | 暂停交易 |
| `/cmcadmin market icon <symbol> <reason>` | 修改图标 |
| `/cmcadmin market lot <symbol> <baseAmount> <minimumLots> <lotStep> <reason>` | 修改手数/张数 |
| `/cmcadmin market settings <symbol>` | 打开交易设置页 |
| `/cmcadmin market protection show <symbol>` | 查询成交价格保护 |
| `/cmcadmin market protection set <symbol> <percent> <reason>` | 百分比保护；0 关闭，必须小于 100 |
| `/cmcadmin market mark <symbol> <price> <reason>` | 更新标记价 |
| `/cmcadmin market minimum-notional <symbol> <amount> <reason>` | 修改最小交易金额 |
| `/cmcadmin market minimum-quantity <symbol> <quantity> <reason>` | 修改最小交易数量 |
| `/cmcadmin market quantity-step <symbol> <quantity> <reason>` | 修改数量步长 |
| `/cmcadmin market reference set <symbol> <field> <value> <reason>` | 更新参考价格 |
| `/cmcadmin market reference source-add <symbol> <sourceType> <sourceId> <weight> <numerator> <denominator> <reason>` | 添加或替换参考来源 |
| `/cmcadmin market reference source-remove <symbol> <sourceType> <sourceId> <reason>` | 移除参考来源 |
| `/cmcadmin market resume <symbol> <reason>` | 恢复交易 |
| `/cmcadmin market rules set <symbol> <field> <value> <reason>` | 修改交易规则 |
| `/cmcadmin market schedule set <symbol> <field> <value> <reason>` | 修改交易时段 |
| `/cmcadmin market tick <symbol> <price> <reason>` | 修改价格步长 |
| `/cmcadmin market info <symbol>` | 查看 / 搜索 |
| `/cmcadmin market list` | 查看 / 搜索 |
| `/cmcadmin market reference show <symbol>` | 查看 / 搜索 |
| `/cmcadmin market rules show <symbol>` | 查看 / 搜索 |
| `/cmcadmin market schedule show <symbol>` | 查看 / 搜索 |
| `/cmcadmin market search <search>` | 查看 / 搜索 |

### 交易用户及数据查询

| 语法 | 操作 |
| --- | --- |
| `/cmcadmin user ban <username> <reason>` | 封禁交易账户 |
| `/cmcadmin user unban <username> <reason>` | 解封交易账户 |
| `/cmcadmin user info <username>` | 查看 / 搜索 |
| `/cmcadmin user list` | 查看 / 搜索 |
| `/cmcadmin user orders <username> <page>` | 查看 / 搜索 |
| `/cmcadmin user positions <username> <page>` | 查看 / 搜索 |
| `/cmcadmin user search <search>` | 查看 / 搜索 |
| `/cmcadmin user trades <username> <page>` | 查看 / 搜索 |

### 可用及冻结余额

| 语法 | 操作 |
| --- | --- |
| `/cmcadmin balance add contract <username> <asset> <amount> <reason>` | 增加余额 |
| `/cmcadmin balance add spot <username> <asset> <amount> <reason>` | 增加余额 |
| `/cmcadmin balance remove contract <username> <asset> <amount> <reason>` | 减少余额 |
| `/cmcadmin balance remove spot <username> <asset> <amount> <reason>` | 减少余额 |
| `/cmcadmin balance show contract <username>` | 查看 / 搜索 |
| `/cmcadmin balance show spot <username>` | 查看 / 搜索 |

### 锚定兑换及储备

| 语法 | 操作 |
| --- | --- |
| `/cmcadmin peg configure <base> <quote> <baseAmount> <quoteAmount> <percent> <minimum> <maximum> <buyEnabled> <sellEnabled> <enabled> <reason>` | 配置兑换 |
| `/cmcadmin peg issue <username> <asset> <amount> <reason>` | 发行货币 |
| `/cmcadmin peg reserve-in <username> <asset> <amount> <reason>` | 补充兑换储备 |
| `/cmcadmin peg reserve-out <username> <asset> <amount> <reason>` | 返还兑换储备 |

### 操作记录及异常核对

| 语法 | 操作 |
| --- | --- |
| `/cmcadmin journal refund <operation> <reason>` | 退还扣款 |
| `/cmcadmin journal review commands <operation> <reason>` | 核对异常命令 |
| `/cmcadmin journal review items <operation> <reason>` | 核对实物操作 |
| `/cmcadmin journal review pegs <operation> <reason>` | 核对兑换操作 |
| `/cmcadmin journal review transfers <operation> <reason>` | 核对划转操作 |
| `/cmcadmin journal list admin <page>` | 查看 / 搜索 |
| `/cmcadmin journal list commands <page>` | 查看 / 搜索 |
| `/cmcadmin journal list items <page>` | 查看 / 搜索 |
| `/cmcadmin journal list pegs <page>` | 查看 / 搜索 |
| `/cmcadmin journal list transfers <page>` | 查看 / 搜索 |
| `/cmcadmin journal search admin <search> <page>` | 查看 / 搜索 |
| `/cmcadmin journal search commands <search> <page>` | 查看 / 搜索 |
| `/cmcadmin journal search items <search> <page>` | 查看 / 搜索 |
| `/cmcadmin journal search pegs <search> <page>` | 查看 / 搜索 |
| `/cmcadmin journal search transfers <search> <page>` | 查看 / 搜索 |

### 交易所状态及维护

| 语法 | 操作 |
| --- | --- |
| `/cmcadmin exchange checkpoint  <reason>` | 保存持久化检查点 |
| `/cmcadmin exchange halt  <reason>` | 暂停交易 |
| `/cmcadmin exchange resume  <reason>` | 恢复交易 |
| `/cmcadmin exchange audit` | 查看 / 搜索 |
| `/cmcadmin exchange status` | 查看 / 搜索 |

- `/cmcadmin order <order>`: 查询指定记录。
- `/cmcadmin position <position>`: 查询指定记录。

- `/cmcadmin gui`: 打开管理页。

## 参数与规则

- `asset`、`base`、`quote`：资产注册名、ID 或唯一匹配的显示名。`symbol`、`order`、`position`：交易所 ID。`username`：交易账户登录名，不是 Minecraft 名称。
- `display`：带空格时用双引号；实物注册和绑定使用手持物品。`scale` 是原生小数精度，`unitSize` 是一个显示单位代表的实际单位数量。
- `expiry`：UTC 日期（`2027-01-31`）、加引号的 UTC 日期时间（`"2027-01-31 12:00:00"`）、带时区的 ISO 时间或 Unix 毫秒。`percent`、maker/taker 手续费填百分数，`0.05` 表示 0.05%。
- `market lot`：每手 / 每张对应基础资产数量，再填最小手数与手数步长。最小数量和步长须符合原生规则。
- `peg configure`：基础 / 报价资产、两边数量、手续费百分比、最小 / 最大基础数量、买入开关、卖出开关、启用开关。兑换输入是基础资产数量。`peg issue` 从发行账户向用户划转，`asset mint` 在发行账户创建电子资产供应量。
- `market rules/schedule/reference set` 每次设置一个标量字段。Tab 包含嵌套风控 / 手续费路径、布尔和枚举选项。高级字段使用原生整数单位，费率为百万分之一，时间戳 / 时长为毫秒；显示单位输入请用 `tick`、`fees`、`minimum-quantity`、`minimum-notional`、`funding`、`lot`。来源列表用 source-add/remove 管理，无需 JSON。来源包含 spot 或 external 类型、ID、正权重及单位换算分子 / 分母。
- `balance add/remove` 审计增加 / 减少可用余额，仍受原生提款风控限制，不偷偷释放冻结资金；不同于划转发行储备。
- `user ban/unban` 持久化应用访问限制。封禁清理所有登录会话，并取消待完成的物品选择 / 聊天。余额、持仓及已接受订单保留在 AdvMatch；已有挂单仍可能成交，原生风控、资金费和交割继续执行。解封不自动恢复会话。
- 日志每页 20 条。核对需要操作 ID，原因填写核对证据；未知结果不自动重试。多数规则修改要求没有挂单或持仓，仅切换最小一档保护带是例外。

## 示例

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
