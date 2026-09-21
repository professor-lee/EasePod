# 2026-09-08 网易云音乐独立插件集成验收

设备：HiBreak，Android 14 / API 34，824 × 1648。工具链：JDK 17、SDK 35、Gradle 8.9。网易云服务以独立插件 APK 随交付包分发，用户可选择安装；未安装时宿主不在启动时发起网易云请求。

更新至 2026-09-09：HTTP Cookie 修正后的真实扫码登录与登录后只读账号测试已经通过。下面先记录修正后的独立批次，再保留此前扫码失败与旧测试快照；不同批次的通过数不累加。登录云账号后替换正常主界面列表的新增功能另见[主音乐来源验收](music-source.md)，该后续功能的结论不由本页历史结果代替。

## HTTP Cookie 修正后批次

修正针对 EAPI 实际 HTTP Cookie 与加密请求体内 `header` 不一致的问题：实际发出的 Cookie 需包含相同 EAPI 元数据，同时保持账号、域名、路径和响应 Cookie 存储边界。修正前的真实 TLS / HTTP 栈夹具为 3 项中 2 项失败，断言分别发现缺少 `__csrf` 和 `os`，见[红灯 XML](netease-http-cookie-red-20260908.xml)与[红灯日志](netease-http-cookie-red-20260908.log)。这证明了请求不一致，不能单凭夹具断言推断网易云风控的全部判定原因。

修正后的固定 XML 保存在 [netease-http-cookie-green-20260908/](netease-http-cookie-green-20260908/)，7 个测试类合计 **61/61 通过**，失败、错误和跳过均为 0：[构建日志](netease-http-cookie-green-20260908.log)为 `BUILD SUCCESSFUL in 30s`。

| 测试类 | 通过数 | 范围 |
|---|---|---|
| `NeteaseAccountsTest` | 16 / 16 | 账号验证、取消、会话与凭据存储夹具 |
| `NeteaseCatalogTest` | 17 / 17 | 既有目录、搜索、分页、音质映射与歌词夹具 |
| `NeteaseHttpTest` | 8 / 8 | 请求参数、协议和响应处理 |
| `NeteaseHttpNetworkTest` | 3 / 3 | 本地受控 HTTPS 完整网络栈、EAPI Cookie/header 一致、账号隔离与响应 Cookie、WEAPI 回归 |
| `NeteaseFavoritesTest` | 11 / 11 | 喜欢状态与修改收据夹具，包含受控写入 |
| `NeteaseCryptoTest` | 3 / 3 | 加密向量 |
| `NeteaseCookiesTest` | 3 / 3 | Cookie 规则 |

`NeteaseHttpNetworkTest` 使用本地 TLS 服务端，并非网易云公网。以上 61 项不包含后续主音乐来源目录新增测试，也不能把受控写入视为真实账号写入验收。

修正后的二维码截图解码通过，证据[仅保存脱敏结果](netease-cookie-qr-decode.txt)，不保存登录参数或凭据。随后真实扫码成功，已登录账号能够重新通过服务端验证，并完成以下手机网络只读测试：

| 测试 | 结果 | 用时 | 证据 |
|---|---|---|---|
| `NeteasePublicDeviceTest` 公共批次 | 2 / 2 | 11.671 秒 | [netease-cookie-public-device.txt](netease-cookie-public-device.txt) |
| `liveSignedInAccountCatalogFavoriteGetterResolutionAndLyricsAreReadOnly` | 1 / 1 | 13.297 秒 | [netease-cookie-account-device.txt](netease-cookie-account-device.txt) |

公共批次再次验证宿主私有服务以及公网目录、搜索、播放地址、歌词和 Media3 静音解码、进度、暂停。私有账号批次重新读取账号的 `SignedIn` 状态，使用独立私有作用域读取我的歌单、喜欢歌曲、每日推荐及可用后续页，并核对 `GetFavorite`。有歌单时继续读取歌单歌曲；集合允许为空，不能从测试通过推断个人库的条目数量。测试没有调用真实收藏或歌单修改。

私有测试仅在候选歌曲可获播放授权时进一步检查解析结果与歌词；该批日志未逐项记录此条件分支，不能据此宣称登录后实际音频播放、全部权益或四档音质都已验收。真实账号写入仍未验收。

Cookie 修正后的构建记录：Debug 主 APK 与测试 APK [构建通过](netease-cookie-debug-build.txt)，用时 16 秒；R8 Release、示例插件 Debug、应用与网易云模块 Debug Lint [构建通过](netease-cookie-delivery-build.txt)，用时 5 分 31 秒；[随后复核](netease-cookie-delivery-recheck.txt)同组任务通过，用时 10 秒，其中多数任务为 `UP-TO-DATE`。这些日志证明该批任务成功，不代替后续主音乐来源修改的构建、Lint 或交付包校验。

## Cookie 修正前固定单测快照

原始 XML 保存在 [netease-unit/](netease-unit/)。这些报告来自分批执行，共 130/130 通过，失败、错误和跳过均为 0；不与早期锁屏增补的 125 项相加。

| 模块 | 通过数 | 覆盖 |
|---|---|---|
| `netease-plugin` | 58 / 58 | 账号状态与取消 16、目录/搜索/分页/音质/歌词 17、HTTP 8、喜欢状态与写入收据 11、加密向量 3、Cookie 规则 3 |
| `plugins` | 52 / 52 | 内置注册与宿主信任、通用契约、云写入、主题、签名目录与安装收据 |
| `app` | 20 / 20 | 启动崩溃策略、导出恢复、云菜单能力门控与歌词 |

账号、喜欢状态和 HTTP 单测使用本地夹具；通过不表示真实账号登录或云端写入已验收。此 HTTP 快照不包含上述 Cookie 修正回归。

## Cookie 修正前设备批次

| 测试 | 结果 | 用时 | 证据 |
|---|---|---|---|
| `NeteasePublicDeviceTest` | 2 / 2 | 7.615 秒 | [netease-public-device.txt](netease-public-device.txt) |
| `AccountAuthDeviceTest` 最终批次 | 6 / 6 | 10.588 秒 | [netease-account-auth-device-final.txt](netease-account-auth-device-final.txt) |
| `SelectedAccountsStoreDeviceTest` | 5 / 5 | 0.040 秒 | [netease-selected-accounts-device.txt](netease-selected-accounts-device.txt) |
| `EncryptedNeteaseAccountStoreDeviceTest` | 4 / 4 | 0.268 秒 | [netease-keystore-device.txt](netease-keystore-device.txt) |

以上四组共 17 项。账号 UI 早期批次 [netease-account-auth-device.txt](netease-account-auth-device.txt) 为 4/4（8.774 秒），已包含在后续 6 项中，不重复计数。

- 公共真机测试验证服务未导出且属于宿主 UID，并通过实际手机网络完成推荐歌单、歌单歌曲、歌曲搜索、HTTPS 播放地址和有界歌词读取；有后续歌词页时继续读取。Media3 以静音方式真实解码播放、推进进度并暂停。返回媒体不携带账号凭据，缓存策略为 `NO_STORE`。该批次使用公开账号作用域。
- 账号 UI 使用受控 `AccountSource`，覆盖等待扫码、等待确认、验证后选中账号、手动选择与失效清理、离开页面取消会话，以及内置服务不显示外部信任或卸载命令。两项二维码布局测试从屏幕截图解码，并检查 LCD 与菜单边界，包含大字号和全屏显示场景。
- 选择账号存储测试覆盖宿主作用域与插件绑定、重建恢复、失效选择清理、保留作用域/损坏偏好拒绝，以及退出其他账号不改变当前选择。
- Keystore 测试使用真实 Android Keystore，覆盖跨存储实例往返及 Cookie 属性保持、密文篡改认证失败、密钥缺失时拒绝静默覆盖，以及退出全部账号时删除密文和原子备份文件。

## 修正前二维码与登录失败记录

二维码裁切调整后的 Debug 主 APK 与设备测试 APK 构建通过，见 [netease-qr-build.txt](netease-qr-build.txt)，用时 4 分 19 秒。真实设备二维码截图已成功解码，脱敏结果见 [netease-real-qr-decode.txt](netease-real-qr-decode.txt)。此记录不保存二维码内容、登录参数或账号凭据。

该旧批次实际扫码由另一手机上的网易云 App 完成，提示“设备环境异常，禁止登录”。当时只确认这一失败现象，未确定根因；二维码成功生成、截图解码或受控账号 UI 测试都不代表真实登录成功。随后 Cookie 修正批次已经完成真实扫码与私有只读验证，见本页开头；本段保留旧失败现象，不再表示当前登录状态。

当前生命周期行为是在 EasePod 退出前台时取消登录会话，因此在同一手机切换到网易云 App 扫码会受到此限制。本次使用另一手机扫码，该限制不是此次失败原因；此处仅记录覆盖边界。

## 修正前构建与 Lint

[netease-delivery-build.txt](netease-delivery-build.txt) 记录该批次 R8 Release、应用和网易云模块 Debug Lint 任务成功，用时 12 分 33 秒。应用报告 [netease-app-lint.txt](netease-app-lint.txt) 为 `No issues found.`；模块报告 [netease-plugin-lint.txt](netease-plugin-lint.txt) 为 0 errors、1 warning，警告是网易云协议加密使用 `AES/ECB/PKCS5Padding` 的 `GetInstance` 提示。

上述构建和 Lint 均在 HTTP Cookie 修正前完成，不作为修改后的 Release 或 Lint 证据。修正后批次已在本页开头独立列出；构建日志也不能替代交付包重新生成与校验的证据。

## 复现入口

在 `project/` 中顺序执行，先按本机配置 JDK 与 SDK，并连接已授权的 Android 14+ 设备。以下命令是复现入口，不表示已对后续修改重新执行。

```sh
./gradlew :netease-plugin:testDebugUnitTest :plugins:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :netease-plugin:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r netease-plugin/build/outputs/apk/debug/netease-plugin-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e neteaseLive true -e class app.easepod.ui.NeteasePublicDeviceTest#installedServiceIsAnExternalExportedPlugin,app.easepod.ui.NeteasePublicDeviceTest#livePublicCatalogSearchResolutionLyricsAndMedia3Playback app.easepod.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e neteaseAccountLive true -e class app.easepod.ui.NeteasePublicDeviceTest#liveSignedInAccountCatalogFavoriteGetterResolutionAndLyricsAreReadOnly app.easepod.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class app.easepod.ui.AccountAuthDeviceTest,app.easepod.ui.SelectedAccountsStoreDeviceTest app.easepod.test/androidx.test.runner.AndroidJUnitRunner
./gradlew :netease-plugin:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.easepod.netease.EncryptedNeteaseAccountStoreDeviceTest
```

公共网络测试必须显式传入 `neteaseLive=true`，否则对应方法会直接返回，不能据绿色结果认定完成联网验收。私有账号测试需要真实扫码验证成功及独立参数 `neteaseAccountLive=true`，缺少该参数同样会直接返回；本记录的公共 2 项不包含该测试。

## 待验收

- 继续核验真实账号权益、各音质实际授权与登录后音频播放；真实账号收藏与歌单写入仍未验收。
- 主音乐来源自动切换、正常菜单接入及服务停用/恢复的后续验收，独立记录在 [music-source.md](music-source.md)。
- 后续代码变更须重新构建并核对 APK、源码及 SHA-256 交付材料，不能沿用 Cookie 修正批次的构建结论。

公开歌曲当前可用不保证全部曲目、账号、地区或 CDN 调度下均可用。二维码布局、受控账号流程与 Keystore 通过不替代真实服务端登录验收；本页真实登录通过依据的是 Cookie 修正后的实际扫码及独立私有账号批次。
