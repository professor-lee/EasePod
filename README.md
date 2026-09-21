# EasePod Android

EasePod 的正式 Android 工程。最低 Android 14（API 34），目标 API 35；主要使用 Kotlin、Jetpack Compose、Media3、Room、Proto DataStore。界面与轮盘规则采用 [已冻结基线](../docs/easepod-architecture/frozen-ui-baseline.md)，迭代进度见 [DEVELOPMENT.md](DEVELOPMENT.md)，构建和设备验收索引见 [verification/README.md](verification/README.md)。网易云服务插件位于 `netease-plugin/`，作为独立 APK 随交付包分发；协议来源和能力边界见 [模块说明](netease-plugin/PROTOCOL_SOURCES.md)。

## 首次运行

主 APK 是可独立使用的本地播放器。初次运行不写入示例歌曲，不自动播放；本地歌曲、专辑和离线列表按真实数据呈现空状态。网易云插件可从交付包中选择安装，未安装时首次启动不会发起网易云请求。内置 Local Library 与银色、黑色、OLED 黑主题无需安装其他 APK。

在“设置 > 本地与存储 > 本地音乐文件夹”通过 Android 系统选择器授予一个文件夹的只读访问权限。扫描范围是根目录单曲与一级子目录专辑，不继续递归；支持 MP3、WAV、FLAC、OGG，专辑目录内的 `cover.png`、`cover.jpg`、`cover.jpeg`、`cover.gif` 可作为封面。更换目录或重新扫描时，成功发布新索引后再替换现有库。

播放、队列、本地搜索与歌单、歌词、睡眠定时、缓存管理、加密备份与恢复由宿主提供。云端收藏与歌单编辑、账号、网络音质和离线下载根据已批准插件的实际能力显示；示例服务不支持的能力不会伪造成功结果。

“防误触”默认关闭，打开后拦截 LCD 的直接触摸，保留轮盘、键盘、无障碍和媒体控制；文本输入例外仅存在于当前表单会话。“锁屏覆盖”默认关闭，开启后使用 `setShowWhenLocked` 复用完整播放器。它受 Android 后台启动与 ROM 窗口策略限制，不保证每次开屏都自动拉起；无法显示时仍使用标准 MediaSession 控件。敏感操作需要系统解锁并重新确认。

锁屏覆盖显示时，从底部系统导航区域上方向上滑动可直接进入系统解锁流程，PIN 等认证方式由设备设置决定；防误触不拦截该手势。“设置 > 全屏显示”默认关闭，开启后隐藏顶部系统状态栏，保留底部导航和播放器 LCD 状态栏；关闭即时恢复，偏好随重启和备份保存。

## 构建

安装 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0，设置 `JAVA_HOME`，并设置 `ANDROID_HOME` 或使用未纳入版本控制的 `local.properties` 指定 `sdk.dir`。以下命令在 `project/` 中运行：

```sh
./gradlew :app:assembleDebug :netease-plugin:assembleDebug
./gradlew :core:test :data:testDebugUnitTest :playback:testDebugUnitTest :plugins:testDebugUnitTest :netease-plugin:testDebugUnitTest :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 网易云服务按需安装（可跳过）
adb install -r netease-plugin/build/outputs/apk/debug/netease-plugin-debug.apk
```

Gradle Wrapper 固定 8.9，并校验分发包的 SHA-256。主应用包名为 `app.easepod`，版本为 `0.1.0`。发布构建使用 `./gradlew :app:assembleRelease`；生成的未签名 APK 不能直接安装，签名由发行者提供，仓库不保存签名私钥。设备测试需要已授权 ADB 的 Android 14+ 设备，命令与夹具条件见验证索引。

## 模块

| 模块 | 职责 |
|---|---|
| `app` | Android 窗口、冻结路由、Compose LCD 与轮盘、系统流程 |
| `core` | 音乐实体、队列、设置接口、轮盘几何 |
| `data` | 单 SAF 根目录、Room 索引与歌单、设置、加密备份 |
| `playback` | Media3 前台服务、真实队列、音频输出、流缓存与离线下载 |
| `plugin-contract` | 有界 Parcelable 与 AIDL 协议、插件服务基类 |
| `plugins` | 内置服务注册、独立 APK 发现、双向信任、连接管理、主题导入与回退 |
| `netease-plugin` | 独立 APK 形式分发的网易云服务、扫码账号、目录与搜索、播放地址、歌词与喜欢状态 |
| `sample-plugin` | 独立安装的契约示例服务，不随播放器预装 |

## 插件与恢复

“设置 > 插件 > 安装插件 > 从文件安装”接收独立 APK 与 `.ep-theme` 主题包。独立 APK 插件通过单独进程的 AIDL 服务接入；安装器返回后重新核对包名、版本、插件标识与证书，批准证书并启用后才连接。服务端同时核验并批准 EasePod 的签名身份，包名本身不构成信任。

网易云音乐 APK 安装后从“音乐 > 音乐服务 > 网易云音乐”进入。它没有桌面启动图标，由宿主通过显式授权 Intent 打开授权页，并通过 `MUSIC_PLUGIN` 服务 Intent 连接。它遵循独立插件的签名核对、批准、启用、停用和卸载流程；未安装时宿主仍可正常使用本地库。账号页提供扫码登录，凭据保存在本机 Android Keystore 加密文件中，不进入备份。歌曲权益与实际音质由服务端决定；当前支持喜欢状态，不声明离线下载和云端歌单编辑能力。

登录成功后，该账号自动成为当前音乐来源。正常音乐菜单的 Cover Flow、歌单、歌曲、专辑、艺人、搜索与主菜单随机播放直接读取该账号，无需再次进入插件页。网易云 Cover Flow 展示账号歌单，歌曲为喜欢的音乐，专辑和艺人为收藏列表，并提供每日推荐；个人流派目录暂不支持。通过“音乐 > 音乐来源”切回本地或其他已选账号，选择随重启保存；退出账号、账号失效、停用插件或安全模式会回到本地。旧版已有选中云账号会自动迁移，明确选过本地后不会重新自动切换。

后续插件通过 [主目录契约](plugin-contract/HOME-CATALOG.md) 接入相同入口；未声明 `catalog.home` 的旧插件使用原浏览根目录，不要求采用网易云专用端点。主音乐来源验收见 [验证记录](verification/music-source.md)。

独立 [sample-plugin](sample-plugin/README.md) 使用三个公共 SoundHelix 样例验证浏览、搜索、详情与 HTTPS 播放，不随宿主安装。它可在手机插件详情或系统设置中独立卸载，卸载后可从交付包重新安装。主题包格式和回退规则见 [THEME-PACKAGE.md](plugins/THEME-PACKAGE.md)，可选音质协商见 [QUALITY.md](plugin-contract/QUALITY.md)。远程签名目录的校验组件已提供，但本工程未配置在线目录地址或官方发布服务；当前安装入口使用本地文件。

通用云端收藏与歌单写入协议见 [CLOUD-LIBRARY.md](plugin-contract/CLOUD-LIBRARY.md)。写操作需要有效私有账号、联网和明确确认；超时保留待确认收据，先读回远端状态。应用不会排队发送离线云操作，也不会在重连后重放写入。示例服务未声明云端写入能力，这些入口只在实际支持的服务上显示。

安全模式暂停云端音乐服务并使用内置银色主题，保留本地库与宿主播放。除手动开启外，连续两次确认在外部插件或主题启动阶段发生的宿主崩溃会使下次启动持久化开启安全模式。系统回收、强制停止与插件自身进程死亡不作为宿主启动崩溃累计。首屏与启动资源稳定后清除计数；纯后台启动也会结束观察窗口，避免把后续后台崩溃误计为启动失败。

## 许可

EasePod 原创代码采用 AGPL-3.0-only，全文见 [LICENSE](LICENSE)。发行时同时提供对应源代码、构建材料和第三方许可；应用“关于”包含许可、依赖清单和参考致谢。仓库未预设对外源码托管地址，发行者需提供实际对应源码获取方式。正式应用不包含参考项目 Classipod 的代码、字体或位图。
