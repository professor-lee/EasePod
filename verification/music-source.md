# 主音乐来源验收

记录日期：2026-09-09。登录并验证云账号后，账号会持久成为主音乐来源；音乐菜单中的 Cover Flow、歌单、歌曲、专辑、艺人、流派和搜索直接使用该账号。音乐来源页可切回本地，停用、退出或账号过期时回落本地。

网易云的每日推荐、私人雷达、歌曲漫游以只读虚拟歌单加入 Cover Flow 和歌单开头。它们打开后仍通过统一 `Browse` 目录契约加载歌曲，主菜单不再单独列出每日推荐。

## 已执行证据

- `verification/music-source-virtual-unit-rerun.txt`：app 与 netease-plugin 单测任务通过；网易云目录分页、账号绑定和虚拟歌单分页断言已更新。
- `verification/music-source-device-final.txt`：来源切换、账号隔离、主目录、搜索、封面返回与失败重试设备回归通过（该批次为虚拟歌单改动前的既有主来源批次）。
- `verification/music-source-live-cover-final.txt`：最新虚拟歌单与封面逻辑真机只读回归 1/1 通过；不执行云端写入。
- `verification/music-source-pagination-build.txt`：最新分页恢复修正后的 Debug、测试 APK、R8 Release 与 app lint 构建通过。

虚拟歌单封面遵循参考 `CNMPlayer`：每日推荐取推荐歌曲首个专辑封面，私人雷达取推荐卡封面，歌曲漫游取首歌封面并可随最后播放歌曲更新。歌曲漫游按 UTC 日期只刷新一次，同一分页会话复用快照；刷新批次按歌曲 ID 去重，播放到末尾或分页超出当前列表时追加新批次，网络失败保留已有列表。虚拟歌单接口只读；网易云流派目录仍返回 `Unsupported`。尚未覆盖云端写入、所有播放质量档位、完整权益账号以及跨设备登录行为。
