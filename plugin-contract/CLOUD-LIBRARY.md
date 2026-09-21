# 通用云端音乐库协议

本文记录正式工程对冻结 `SetFavorite` / `EditPlaylist` 用例的实现约定，不包含网易云端点。协议仍为主版本 1；新增能力通过握手协商，新增 Parcelable 字段位于记录尾部，旧版请求和响应可继续解码。`sample-plugin` 未声明以下能力。

## 能力和读取

| 能力 | 操作 | 请求与响应 |
|---|---|---|
| `library.favorite` | `GetFavorite` | `remoteId` 为歌曲 ID；响应 `favoriteState` 必须是明确的布尔值 |
| `library.favorite` | `SetFavorite` | 歌曲 ID 与 `LibraryMutation(action=FAVORITE, desiredFavorite, mutationId)` |
| `library.playlist.write` | `ListEditablePlaylists` | 账号内可编辑歌单分页；响应 `playlists` 与 `nextCursor` |
| `library.playlist.write` | `GetPlaylistInfo` | 歌单 ID；响应单个 `CloudPlaylist`，包含所有者、当前 revision、允许操作 |
| `library.playlist.write` | `EditPlaylist` | 歌单 ID、预期 revision 和封闭的修改命令 |
| `library.playlist.create` | `EditPlaylist(action=CREATE)` | 额外允许创建；没有既有歌单 ID 和预期 revision |
| `library.mutation.read` | `GetMutation` | 同账号、同 `mutationId` 的只读结果核对；请求 action 为 `READ` |

所有操作要求明确的私有账号。宿主的 `accountScope` 经 `PluginManager.execute` 映射成插件的 `pluginAccountId`，不把宿主 UUID 当作服务账号。`public` 与 `local` 不能进行这些云操作。读取仍使用已批准的插件连接、当前连接代次、最多 2 个插件在途请求 / 全局 4 个请求、10 秒超时和 128 KiB / 50 项上限。

`GetPlaylistInfo.allowedActions` 只可包含 `ADD`、`REMOVE`、`MOVE`、`RENAME`、`DELETE`。空列表表示当前账号只读；所有权由服务验证，不根据显示名猜测。`revision` 为不透明的远端版本。歌单曲目从既有 `GetDetails` / `Browse` 读取，`CatalogItem.playlistEntryId` 表示歌单中的位置身份，同一歌曲出现两次必须给两个不同 ID。没有条目 ID 时宿主仍能显示与播放，但隐藏按位置修改入口。

## 修改命令

| action | 必须字段 | 语义 |
|---|---|---|
| `FAVORITE` | 曲目 `remoteId`、`desiredFavorite` | 设置成明确目标状态，不使用 toggle |
| `CREATE` | `title` | 新建歌单；可带初始 `trackId` |
| `ADD` | 歌单 ID、`expectedRevision`、`trackId` | 添加一个曲目位置，可与既有曲目重复 |
| `REMOVE` | 歌单 ID、`expectedRevision`、`entryId` | 只删除指定位置 |
| `MOVE` | 歌单 ID、`expectedRevision`、`entryId` | 移到 `beforeEntryId` 前；null 表示移到末尾 |
| `RENAME` | 歌单 ID、`expectedRevision`、`title` | 修改名称，保持实体 ID |
| `DELETE` | 歌单 ID、`expectedRevision` | 删除歌单，不删除原始音乐 |

名称为 1 至 60 个 Unicode 码点；标识与 revision 最多 4 KiB。宿主提交前重新读取权限与 revision。插件仍必须在执行时实施并发版本检查，不能把宿主预读视为原子锁。上游不支持某项操作时不得在 `allowedActions` 声明该项。

每次明确确认分配新的随机 `mutationId`。同一请求不能换用另一账号执行。`CREATE` 携带初始曲目时，只有创建与添加都确定完成才可返回 `APPLIED`；无法支持该语义应在写入前拒绝，发生部分完成或无法核对时返回 `UNKNOWN`，不能静默遗漏初始曲目。

`MutationResult` 状态为 `APPLIED`、`REJECTED`、`CONFLICT`、`UNKNOWN`。必须回显相同 `mutationId`；歌单 `APPLIED` 还必须给出歌单 ID 与新 revision，删除则给出可识别本次删除的最终版本 / tombstone revision。伪成功、错误身份、缺少版本或畸形响应均保留为未知。拒绝和冲突表示本次修改未应用。

## 未知结果与恢复

宿主先持久化最多 256 项的待确认收据，再向插件发送一次写入。收据只有插件 ID、宿主账号作用域、操作类型、目标 ID、标题、mutationId 和收藏目标状态，不保存可重放的歌单命令、播放 URL 或凭据。收据不进入备份，不由 WorkManager 消费；应用重启不会发送任何写入。

写入超时、插件断连、发送后退出账号、响应损坏或取消后进程重启均可能留下未知结果。支持 `library.mutation.read` 时，宿主优先调用 `GetMutation`。插件必须依据远端读回或已获得的权威结果核对，不能把自己的乐观缓存当作成功。该读取不得重发写入，也不得为未找到的 receipt 虚构 `REJECTED`。

无 `GetMutation` 时，收藏可通过 `GetFavorite` 核实目标状态已达到；读到不同状态仍保留未知。云歌单写入保持待确认，用户可在“音乐服务 > 待确认操作”刷新核对。相同账号、目标及操作类型存在未确认收据时禁止再次写入，防止重复创建或添加。宿主不声称无读回能力的服务可以自动消除这种不确定性。

云菜单沿用冻结的 LCD 列表与确认弹窗，防误触时仍通过轮盘操作。写入确认只在当前页面代次有效；在等待插件连接或权限读取期间锁定、离开页面、启用安全模式，都使尚未发送的写入失效。已经发送的写入只核对结果，不根据取消行为推断上游未执行。

## 验证

`CloudLibraryTest` 验证离线拒绝、账号门控、收据先于发送、超时读回、不重放、重启恢复、未知身份、并发版本、所有权与锁定失效；`CloudWireContractTest` 验证新旧帧兼容、重复条目位置和持久化收据。`CloudMenuPolicyTest` 检查入口门控，`CloudWriteDeviceTest` 通过受控服务测试 LCD 防误触、明确确认、锁定失效和结果刷新。

这些是宿主契约与交互验证。真实服务的上游幂等性、并发版本及读回行为由具体适配器独立验收，不因宿主测试通过而自动宣称支持。
