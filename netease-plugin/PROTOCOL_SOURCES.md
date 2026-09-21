# 网易云协议来源与实现范围

本模块为 EasePod 新编写的 Kotlin 实现，通过 Android/JVM JCA、OkHttp 和结构化 JSON 直接调用服务端。没有链接 Rust SDK，也没有复制任何播放器本体的 AGPL 代码。协议字段与加密格式通过对上游开源实现的静态研究获得；这不是官方开放 API SDK，也没有获得网易云音乐的官方支持承诺。

## 协议来源与许可核验

协议研究的依据是上游开源项目 [`ncm-api-rs`](https://github.com/SPlayer-Dev/ncm-api-rs)（原地址 <https://github.com/imsyy/ncm-api-rs> 现重定向至该仓库），其 `Cargo.toml` 声明 crate `ncm-api`、版本 `0.1.0`、许可证 `WTFPL`。

本模块不包含该项目或其他上游项目的任何代码、二进制或源码快照，仅依据公开的协议描述用 Kotlin 重新实现。许可方面已做如下核验：

- 2026-09-08 通过 GitHub API 联网核验固定提交 [`133b65bfe482e41ebccf018870d3fce07bf58eb3`](https://github.com/SPlayer-Dev/ncm-api-rs/commit/133b65bfe482e41ebccf018870d3fce07bf58eb3) 的 [`LICENSE`](https://github.com/SPlayer-Dev/ncm-api-rs/blob/133b65bfe482e41ebccf018870d3fce07bf58eb3/LICENSE)：内容为 WTFPL v2，Git blob SHA-1 为 `5c93f45654687732c6b9b568186deb766f78a81c`。
- 该许可原文已随本模块附于 [`licenses/WTFPL.txt`](licenses/WTFPL.txt)，可直接查阅，无需访问上游。

核验边界：上述结论只针对该固定提交的许可声明，不构成对上游全部历史版权来源的确认。本模块实现与任何单一上游提交都不构成逐行对应关系；上游是活跃演进的项目，文件内容可能已经变化。此记录不改授第三方代码的许可，EasePod 原创 Kotlin 实现按项目 [`LICENSE`](../LICENSE) 的 AGPL-3.0-only 声明处理。

## 实现对照

下表给出本模块行为与上游项目内对应文件的对照，便于核对协议来源。文件名相对上游仓库根目录，`src/` 下的路径为该项目的模块布局。

| 本模块行为 | 上游对应文件 |
|---|---|
| WEAPI 双层 AES-CBC、反转随机密钥的无填充 RSA；EAPI MD5 签名和 AES-ECB | `src/crypto.rs` |
| `/api/` 路径签名、WEAPI/EAPI URL、表单编码、请求 header、会话 cookie | `src/request.rs`、`src/util/config.rs`、`src/util/cookie.rs` |
| 二维码登录与会话状态 | `src/api/login_qr_key.rs`、`src/api/login_qr_check.rs`、`src/api/login_status.rs` |
| 公开推荐歌单、我的歌单、喜欢列表、每日推荐 | `src/api/personalized.rs`、`src/api/user_playlist.rs`、`src/api/likelist.rs`、`src/api/recommend_songs.rs` |
| 主界面的收藏专辑、收藏歌手 | `src/api/album_sublist.rs`、`src/api/artist_sublist.rs` |
| 歌曲、专辑、歌手、歌单搜索 | `src/api/cloudsearch.rs` |
| 歌单完整 trackIds 和按页批量歌曲详情 | `src/api/playlist_detail.rs`、`src/api/playlist_track_all.rs`、`src/api/song_detail.rs` |
| 专辑详情、歌手歌曲列表 | `src/api/album.rs`、`src/api/artist_songs.rs` |
| 播放地址、网易云音质参数 | `src/api/song_url_v1.rs` |
| 原文和翻译歌词 | `src/api/lyric.rs` |

## 适配约束

- 加密使用 JCA 重新实现；HTTP 使用 OkHttp。原始业务 `code` 必须保留，尤其二维码状态 800..803 不能归并成成功。cookie 在插件持有的账号会话内管理，不传到主程序的媒体请求。
- 页游标绑定操作、账号作用域、用户 ID、父目录、搜索条件、类型和页大小；每页最多 50 项。歌单先读取完整 `trackIds`，再批量获取当前页歌曲，保持原顺序并标记缺失项。为避免返回不需要的嵌入歌曲，详情请求设置 `n=0`；此参数行为仍需在线联调确认。
- 契约使用 `playlist:ID`、`album:ID`、`artist:ID`、`category:*` 目录标识和纯数字歌曲标识。现有契约只允许四种条目类型，因此 `category:*` 入口使用 `PLAYLIST` 类型。
- `catalog.home` 按[通用主界面目录](../plugin-contract/HOME-CATALOG.md)支持登录后的主音乐来源：Cover Flow / 歌单为用户歌单，并插入每日推荐、私人雷达、歌曲漫游三个只读虚拟歌单；歌曲 / 喜欢为喜欢列表。每日推荐取推荐歌曲首个专辑封面，私人雷达取推荐卡封面，歌曲漫游取首歌专辑封面。收藏专辑、歌手分别读取 `/api/album/sublist` 与 `/api/artist/sublist` 的 `data`、`hasMore`（缺少 `hasMore` 时使用明确的 `count`；两者均缺失视为响应错误），使用 WEAPI 与 `limit` / `offset` / `total`；未支持的流派目录返回 `Unsupported`。公开根目录继续返回推荐歌单。
- `standard/high/lossless/hires` 请求分别映射 `standard/exhigh/lossless/hires`。输出按服务端实际等级和格式报告音质；服务端提供试听时明确设置 `isPreview`。缺少授权或歌曲不可用时返回错误，不构造下载地址或替代来源。
- 媒体仅接受代码列出的精确网易 CDN 主机。已知 CDN 的 `http` 地址可升级为 `https`；不发出明文 HTTP 请求，拒绝任意外域、用户信息和非 443 端口。未知 CDN 主机需要检查后显式增加。
- 默认 `NO_STORE`，不声明离线下载授权或完整内容 SHA-256。若上游给出格式正确的 MD5，使用 MD5 和实际音质作为媒体表示版本；这不是额外的完整性承诺或缓存授权。合法可播放地址不等于获得离线保存许可。

本地夹具覆盖端点参数、目录映射、歌曲分页、账号隔离游标、音质降级、试听、媒体地址限制、歌词翻译和业务错误。夹具测试不证明真实账号、歌曲或当前服务端可用；服务端变化、账号权益、地区和 CDN 调度仍可能影响实际结果。

## 免责声明与使用风险

- 本项目与网易云音乐及其运营方无任何关联，未获得官方授权或支持；本模块调用的服务端接口不是官方开放 API，其可用性与行为由服务端决定，可能随时变化或被限制。
- 协议字段与加密格式通过研究第三方开源实现静态分析获得（见上文“协议来源与许可核验”）。`NeteaseCrypto.kt` 中的 `EAPI_KEY`、`PRESET_KEY` 与 RSA 公钥是公开的协议常量，仅用于构造请求签名，不是账号凭据，也不是私钥。
- 使用本模块连接网易云音乐服务可能违反其服务条款，并可能导致账号被限制或封禁；由此产生的账号与数据风险由使用者自行承担。
- 本模块不绕过 DRM、不破解付费内容、不托管任何未经授权的 API 服务；不声明离线下载授权，试听范围、账号权益与实际音质均由服务端决定。
- 本模块不提供任何音乐内容，只在本机客户端转发请求；所有音乐内容及其权利归各自权利人所有。
- 登录凭据（Cookie）仅保存在本机 Android Keystore 加密的账号文件中（AES-GCM，位于 `noBackupFilesDir`），不参与系统备份，不上传任何服务器。
- 建议仅在个人自有账号上以只读方式使用本模块；不用于商业分发或对外提供服务。
- 本文档及代码中出现的产品名称与商标归其各自权利人所有。
