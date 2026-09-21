# 网易云协议来源与实现范围

本模块为 EasePod 新编写的 Kotlin 实现，通过 Android/JVM JCA、OkHttp 和结构化 JSON 直接调用服务端。没有链接 Rust SDK，也没有复制 CNMPlayer 播放器本体的 AGPL 代码。协议字段和加密格式通过工作区内的 `参考文件/CNMPlayer/ncm-api-rs/` 静态研究获得；这不是官方开放 API SDK，也没有获得网易云音乐的官方支持承诺。

## 本地参考来源

参考快照的 [`Cargo.toml`](../../参考文件/CNMPlayer/ncm-api-rs/Cargo.toml) 声明 crate `ncm-api`、版本 `0.1.0`、许可证 `WTFPL`，上游地址 <https://github.com/imsyy/ncm-api-rs> 现重定向至 <https://github.com/SPlayer-Dev/ncm-api-rs>。CNMPlayer 的 [`THIRD_PARTY_NOTICES.md`](../../参考文件/CNMPlayer/THIRD_PARTY_NOTICES.md) 将该目录列为原样复制的第三方代码，声明 WTFPL v2，并附完整许可文本。

2026-09-08 已通过 GitHub API 联网核验固定上游提交 [`133b65bfe482e41ebccf018870d3fce07bf58eb3`](https://github.com/SPlayer-Dev/ncm-api-rs/commit/133b65bfe482e41ebccf018870d3fce07bf58eb3) 的 [`LICENSE`](https://github.com/SPlayer-Dev/ncm-api-rs/blob/133b65bfe482e41ebccf018870d3fce07bf58eb3/LICENSE)：内容为 WTFPL v2，Git blob SHA-1 为 `5c93f45654687732c6b9b568186deb766f78a81c`。相同原文已随本模块附于 [`licenses/WTFPL.txt`](licenses/WTFPL.txt)。该文件补充的是已核验上游提交的许可材料，没有改动本地参考快照。

本地快照仍缺失第三方说明所指向的 `ncm-api-rs/LICENSE`，且 `crypto.rs` 和 `request.rs` 的内容与上述固定提交不一致。本次进一步检查 GitHub 默认分支中这两个路径返回的全部相关提交：`40de3fc422bf72c58a74e86896223a98e096d011`、`9e393a862393345763b201655cfac7728dd4c9e4`、`c2685be88676516e87b27749c1845317936e8e44`、`df575364ed7e3813aed136ad2a4dfaaae67b60de`。这些提交的对应 Git tree 中均没有与本地两文件相同的 blob。因此，当日上游 WTFPL v2 声明已得到确认，但本地快照的精确上游版本仍未确定，第三方说明中的“原样复制”不能视为已经核实。此检查不覆盖其他分支、已删除历史或 CNMPlayer 自身的导入后修改。

| 本地参考文件 | Git blob SHA-1 | 文件 SHA-256 |
|---|---|---|
| `src/crypto.rs` | `3fd6e143c076e36c3f6d2e11bfbd8efa9b16d212` | `3f2acf36604857de98f7422af95d16eed7ec891aff1273ff09bc253439ef4300` |
| `src/request.rs` | `ed03d06e138e92ee0d7a79ca63ee6fa292ac967a` | `d41a7cfb4d99dae05589f04cca75fbe33fb5b4015cab4fea29316b9d79f4ae31` |
| `Cargo.toml` | 未记录 | `643cca913ec03da997f1245e6ed74ff9e07c62d91bdb3cd252ca372548616618` |

以上文件路径相对 `参考文件/CNMPlayer/ncm-api-rs/`；外层 `THIRD_PARTY_NOTICES.md` 的 SHA-256 为 `0334a3c6720bd7a0b338f0e5f1ecaf6bbd8ae0a1562c79ccabc1145ed32a06cd`。这些校验值用于固定实际研究材料，不表示 Rust 实现被打包进插件，也不构成对全部历史版权来源的确认。

参考仓库外层 [`LICENSE`](../../参考文件/CNMPlayer/LICENSE) 是 AGPL v3，不能代替子项目自身的许可核验。EasePod 原创 Kotlin 实现仍按根目录 [`LICENSES.md`](../../LICENSES.md) 和项目 [`LICENSE`](../LICENSE) 的 AGPL-3.0-only 声明处理；此记录不改授第三方代码的许可。

| 本模块行为 | 本地参考文件（相对 `参考文件/CNMPlayer/ncm-api-rs/src/`） |
|---|---|
| WEAPI 双层 AES-CBC、反转随机密钥的无填充 RSA；EAPI MD5 签名和 AES-ECB | `crypto.rs` |
| `/api/` 路径签名、WEAPI/EAPI URL、表单编码、请求 header、会话 cookie | `request.rs`、`util/config.rs`、`util/cookie.rs` |
| 二维码登录与会话状态 | `api/login_qr_key.rs`、`api/login_qr_check.rs`、`api/login_status.rs` |
| 公开推荐歌单、我的歌单、喜欢列表、每日推荐 | `api/personalized.rs`、`api/user_playlist.rs`、`api/likelist.rs`、`api/recommend_songs.rs` |
| 主界面的收藏专辑、收藏歌手 | `api/album_sublist.rs`、`api/artist_sublist.rs` |
| 歌曲、专辑、歌手、歌单搜索 | `api/cloudsearch.rs` |
| 歌单完整 trackIds 和按页批量歌曲详情 | `api/playlist_detail.rs`、`api/playlist_track_all.rs`、`api/song_detail.rs` |
| 专辑详情、歌手歌曲列表 | `api/album.rs`、`api/artist_songs.rs` |
| 播放地址、网易云音质参数 | `api/song_url_v1.rs` |
| 原文和翻译歌词 | `api/lyric.rs` |

## 适配约束

- 加密使用 JCA 重新实现；HTTP 使用 OkHttp。原始业务 `code` 必须保留，尤其二维码状态 800..803 不能归并成成功。cookie 在插件持有的账号会话内管理，不传到主程序的媒体请求。
- 页游标绑定操作、账号作用域、用户 ID、父目录、搜索条件、类型和页大小；每页最多 50 项。歌单先读取完整 `trackIds`，再批量获取当前页歌曲，保持原顺序并标记缺失项。为避免返回不需要的嵌入歌曲，详情请求设置 `n=0`；此参数行为仍需在线联调确认。
- 契约使用 `playlist:ID`、`album:ID`、`artist:ID`、`category:*` 目录标识和纯数字歌曲标识。现有契约只允许四种条目类型，因此 `category:*` 入口使用 `PLAYLIST` 类型。
- `catalog.home` 按[通用主界面目录](../plugin-contract/HOME-CATALOG.md)支持登录后的主音乐来源：Cover Flow / 歌单为用户歌单，并插入每日推荐、私人雷达、歌曲漫游三个只读虚拟歌单；歌曲 / 喜欢为喜欢列表。每日推荐取推荐歌曲首个专辑封面，私人雷达取推荐卡封面，歌曲漫游取首歌专辑封面。收藏专辑、歌手分别读取 `/api/album/sublist` 与 `/api/artist/sublist` 的 `data`、`hasMore`（缺少 `hasMore` 时使用明确的 `count`；两者均缺失视为响应错误），使用 WEAPI 与 `limit` / `offset` / `total`；未支持的流派目录返回 `Unsupported`。公开根目录继续返回推荐歌单。
- `standard/high/lossless/hires` 请求分别映射 `standard/exhigh/lossless/hires`。输出按服务端实际等级和格式报告音质；服务端提供试听时明确设置 `isPreview`。缺少授权或歌曲不可用时返回错误，不构造下载地址或替代来源。
- 媒体仅接受代码列出的精确网易 CDN 主机。已知 CDN 的 `http` 地址可升级为 `https`；不发出明文 HTTP 请求，拒绝任意外域、用户信息和非 443 端口。未知 CDN 主机需要检查后显式增加。
- 默认 `NO_STORE`，不声明离线下载授权或完整内容 SHA-256。若上游给出格式正确的 MD5，使用 MD5 和实际音质作为媒体表示版本；这不是额外的完整性承诺或缓存授权。合法可播放地址不等于获得离线保存许可。

本地夹具覆盖端点参数、目录映射、歌曲分页、账号隔离游标、音质降级、试听、媒体地址限制、歌词翻译和业务错误。夹具测试不证明真实账号、歌曲或当前服务端可用；服务端变化、账号权益、地区和 CDN 调度仍可能影响实际结果。
