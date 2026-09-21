<h1 align="center"><img src="assets/logo.svg" width="96" height="96" alt="EasePod"></h1>

<p align="center">
	<a href="README.md">English</a>
	&nbsp;&nbsp;&nbsp;|&nbsp;&nbsp;&nbsp;
	<a href="README_zh.md">简体中文</a>
</p>

<p align="center" style="color:gray;">
	面向 Android 的本地优先音乐播放器，采用经典 iPod 式圆盘与 LCD 界面。
</p>

<p align="center">
    <img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
    <img src="https://img.shields.io/badge/Android-14%2B%20(API%2034)-3DDC84?logo=android&logoColor=white" alt="Android 14+">
    <img src="https://img.shields.io/badge/License-AGPL--3.0-blue?logo=opensourceinitiative&logoColor=white" alt="License">
    <img src="https://img.shields.io/github/stars/professor-lee/EasePod?style=flat&label=Stars&color=FFC700&logo=github&logoColor=white" alt="Stars">
    <img src="https://img.shields.io/github/forks/professor-lee/EasePod?style=flat&label=Forks&color=60adff&logo=git-fork&logoColor=white" alt="Forks">
    <img src="https://img.shields.io/github/last-commit/professor-lee/EasePod?color=rebeccapurple&logo=git&logoColor=white" alt="Last Commit">
    <img src="https://img.shields.io/github/languages/code-size/professor-lee/EasePod?style=flat&color=blueviolet" alt="Code Size">
</p>

## 项目简介

EasePod 是一个围绕实体操作手感构建的 Android 音乐播放器：上半区为 LCD，下半区为居中圆盘，按经典 iPod 的方式导航。
最低运行版本为 **Android 14（API 34）**，使用 Kotlin、Jetpack Compose、Media3、Room 与 Proto DataStore 实现。

主 APK 本身就是一个**完整可用的本地播放器**。它不内置示例歌曲，首次运行不自动播放；在你授予音乐文件夹之前，
本地歌曲、专辑与歌单都按真实数据呈现空状态。云端音乐服务是**可选安装的独立 APK 插件**，通过有界 AIDL 契约接入，
因此一个插件都不装时宿主仍然完整可用。

界面与交互规则是冻结基线，相关约束记录在仓库内：主菜单结构、圆盘语义、防误触、锁屏解锁门控与 Cover Flow 动画
都属于固定设计，而不是可自由发挥的空间。冻结条目与迭代进度见 [DEVELOPMENT.md](DEVELOPMENT.md)。

## 主要功能

- **经典圆盘导航**：`MENU` 返回，中心确认，左右切换曲目，下键播放/暂停，旋转圆盘移动焦点
- **Cover Flow**：双向平移、缩放与倾斜约 220 ms，首尾循环；要求减少动态效果时立即落位
- **基于 SAF 的本地库**：选择一个根文件夹，根目录音频为单曲、一级子目录为专辑，不继续递归；支持
  `mp3`、`wav`、`flac`、`ogg`，专辑封面支持 `cover.png` / `cover.jpg` / `cover.jpeg` / `cover.gif`
- **歌单与队列**：歌单创建与追加为单事务写入，支持队列编辑、随机/重复状态记忆，以及进程重启后的播放队列恢复
- **歌词**：读取 MP3 内嵌 ID3 `USLT`/`SYLT` 与 Vorbis 注释，并提供页面歌词层
- **睡眠定时**：关闭 / 15 / 30 / 60 分钟后停止
- **流媒体缓存**：缓存上限可调，并支持按账号清理
- **离线下载**：基于 WorkManager，含 SHA-256 与长度校验、授权到期检查与续传
- **加密备份与恢复**：Argon2id（64 MiB、t=3、p=1）加 AES-256-GCM，容器格式为 `EPBK`，导入时严格校验 JSON；
  账号凭据不进入备份
- **主题**：内置银色、黑色、OLED 黑，支持导入 `.ep-theme` 主题包、随包离线主题资产，以及回退到上一版本
- **插件平台**：发现并连接独立安装的 APK，契约使用有界 Parcelable，双向显式批准证书，支持启用/停用/卸载
- **可选的网易云音乐插件**：扫码登录、账号目录、搜索、播放地址、歌词与喜欢状态；每日推荐、私人雷达与歌曲漫游
  作为只读虚拟歌单出现
- **音乐来源切换**：本地库与已登录账号之间切换，选择随重启保留
- **防误触**：拦截 LCD 的直接触摸，保留圆盘、键盘、无障碍服务与媒体控制，仅在当前文本输入会话内例外
- **锁屏覆盖**：使用 `setShowWhenLocked`，敏感操作先经系统解锁再确认；另有仅隐藏顶部系统状态栏的全屏显示模式
- **安全模式**：外部插件或主题加载阶段连续两次确认启动崩溃后，暂停云端音乐服务并使用内置银色主题，本地库继续可用
- **脱敏诊断**：支持预览与导出

## 说明

- 首次运行不会添加曲目或自动播放。需要先在 `设置 > 本地与存储 > 本地音乐文件夹` 通过系统选择器授予目录权限
- 本地扫描只读取根目录与一级子目录，不会继续向下遍历
- 网易云插件没有桌面启动图标。宿主通过显式 Intent 打开其授权页，并通过 `MUSIC_PLUGIN` 服务 Intent 连接
- 单曲权益与实际输出音质由服务端决定，不由本应用决定。请求音质只是偏好，界面显示的实际音质始终是服务端返回值
- 网易云插件不声明离线下载与云端歌单写入能力。不支持能力的入口直接不显示，不会伪造成功结果
- 锁屏覆盖受 Android 后台启动限制与 ROM 窗口策略影响，不保证每次开屏都能自动显示
- 未提供密钥时 release 构建产物为未签名包，见[发布构建与签名](#发布构建与签名)
- 离线插件市场位于宿主 APK 内，安装走系统安装器；不会自动安装或静默启用任何插件

## 技术栈

- Kotlin 2.0.21，Java 17 目标版本
- 界面：Jetpack Compose（BOM 2024.12.01）与 Material 3、`material-icons-extended`
- 播放：Media3 1.5.1（`media3-exoplayer`、`media3-session`、`media3-datasource-okhttp`、`media3-common`）
- 存储：Room 2.6.1（经 KSP）、Proto DataStore（`protobuf-javalite` 4.29.2）、流媒体使用 `SimpleCache`
- 后台任务：WorkManager（`work-runtime-ktx`）承载离线下载
- 网络：OkHttp 4.12.0 与 Okio
- 图片与条码：Coil 2.7.0、ZXing 3.5.3（扫码登录）
- 加密：Bouncy Castle（Argon2id）与 JCA AES-GCM，账号凭据使用 Android Keystore
- 进程间通信：`:plugin-contract` 中的 AIDL 与 `Parcelable` 信封
- 构建：Android Gradle Plugin 8.7.3、Gradle Wrapper 8.9（分发包 SHA-256 固定）、版本目录
  `gradle/libs.versions.toml`

## 开发与运行

### 环境要求

- JDK 17
- Android SDK Platform 35 与 Build Tools 35.0.0
- 设置 `ANDROID_HOME`，或在未纳入版本控制的 `local.properties` 中设置 `sdk.dir`
- 运行设备测试需要已授权 ADB 的 Android 14+ 设备

Gradle 8.9 由 Wrapper 固定并校验分发包校验和，无需另行安装 Gradle。

### 构建

```sh
./gradlew :app:assembleDebug :netease-plugin:assembleDebug
```

如需同时构建独立示例服务，再加上 `:sample-plugin:assembleDebug`。

### 测试

```sh
./gradlew :core:test :data:testDebugUnitTest :playback:testDebugUnitTest \
  :plugins:testDebugUnitTest :netease-plugin:testDebugUnitTest :app:testDebugUnitTest
```

设备端 instrumentation 需要连接设备，按类分别运行，让每组测试拥有独立进程：

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.easepod.ui.DeviceShellTest
./gradlew :playback:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.easepod.playback.PlaybackDeviceTest
./gradlew :playback:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.easepod.playback.DownloadCoordinatorDeviceTest
```

本地库设备测试使用 `verification/media` 中的静音夹具：

```sh
adb push verification/media /sdcard/Download/EasePod-QA
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.easepod.ui.LocalLibraryDeviceTest
```

用系统选择器授予该 `EasePod-QA` 目录后，预期得到 1 首根目录单曲与 2 张一级目录专辑（共 3 首静音 WAV/FLAC/OGG）。
不要用个人音乐目录代替夹具。

### 安装

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 可选云端服务，按需安装
adb install -r netease-plugin/build/outputs/apk/debug/netease-plugin-debug.apk
# 可选独立示例服务
adb install -r sample-plugin/build/outputs/apk/debug/sample-plugin-debug.apk
```

### 发布构建与签名

```sh
./gradlew :app:assembleRelease
```

未提供密钥时产物为 `app/build/outputs/apk/release/app-release-unsigned.apk`，不能直接安装。要产出可签名构建，
复制仓库内模板并填写：

```sh
cp keystore.properties.example keystore.properties
```

```properties
storeFile=/absolute/path/to/release.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

`keystore.properties` 已被 gitignore，禁止提交。**本仓库不保存任何签名私钥**，发行签名由实际发行者提供。

### 打包交付

```sh
./scripts/package-delivery.sh
```

该脚本把 APK、许可证与可复现的源码归档收集到 `dist/`，并生成 `SHA256SUMS`。在 `dist/` 内执行
`sha256sum -c SHA256SUMS` 核对。`dist/` 不纳入版本控制。

## 模块

| 模块 | 职责 |
|---|---|
| `app` | Android 窗口、冻结路由、Compose LCD 与圆盘、系统流程 |
| `core` | 音乐实体、队列、设置接口、圆盘几何（纯 Kotlin/JVM） |
| `data` | 单 SAF 根目录、Room 索引与歌单、设置、加密备份 |
| `playback` | Media3 前台服务、播放队列、音频输出、流缓存、离线下载 |
| `plugin-contract` | 有界 Parcelable、AIDL 协议、插件服务基类 |
| `plugins` | 内置服务注册、独立 APK 发现、双向信任、连接管理、主题导入 |
| `netease-plugin` | 以独立 APK 分发的可选网易云音乐服务 |
| `sample-plugin` | 独立安装的契约示例服务，不随宿主预装 |

## 插件平台

外部服务是独立安装的 APK，通过 AIDL 接入。安装器返回后，宿主会重新核对包名、版本、插件标识与证书，
之后才提供批准入口；插件侧同时核验并批准 EasePod 的签名身份，因此包名本身不构成信任。未显式批准证书并启用之前，
宿主不会连接该插件。

插件会声明自身能力，服务不支持的能力入口不会显示。云端写操作额外要求有效私有账号、联网与明确确认；
写入超时只保留待确认收据并重新读取远端状态，不会假定成功。离线云操作不会被排队，也不会在重连后重放。

内置的示例服务是契约参考实现：

- [sample-plugin/README.md](sample-plugin/README.md) — 构建、安装与双向批准步骤
- [plugin-contract/HOME-CATALOG.md](plugin-contract/HOME-CATALOG.md) — 主菜单目录契约
- [plugin-contract/MEDIA-SOURCES.md](plugin-contract/MEDIA-SOURCES.md) — 媒体与封面解析规则
- [plugin-contract/QUALITY.md](plugin-contract/QUALITY.md) — 可选音质协商
- [plugin-contract/CLOUD-LIBRARY.md](plugin-contract/CLOUD-LIBRARY.md) — 云端收藏与歌单写入
- [plugins/THEME-PACKAGE.md](plugins/THEME-PACKAGE.md) — `.ep-theme` 主题包格式与回退规则
- [netease-plugin/PROTOCOL_SOURCES.md](netease-plugin/PROTOCOL_SOURCES.md) — 网易云插件的协议来源、实现范围，
  以及免责声明与条款风险声明

本工程未配置在线插件目录或官方发布服务。插件市场随宿主 APK 提供，安装入口使用本地文件。

## 设置

设置树由冻结基线确定：

- `音乐`：Cover Flow、歌单、艺人、专辑、歌曲、流派、搜索、最近播放，以及音乐来源
- `设置 > 主题`：内置主题、导入主题，以及回退到上一版本
- `设置 > 轮盘`：旋转灵敏度、触觉反馈、点击音
- `设置 > 播放设置`：随机播放、重复、音量、网络音质偏好、实际音质、音频输出
- `设置 > 睡眠定时`：关闭 / 15 / 30 / 60 分钟后停止
- `设置 > 锁屏覆盖`、`全屏显示`、`防误触`：默认均为关闭
- `设置 > 插件`：内置服务、已安装插件、安装入口与安全模式
- `设置 > 本地与存储`：本地音乐文件夹、重新扫描、目录授权状态、缓存上限、清除缓存，以及备份导出/导入
- `设置 > 离线音乐`：仅 Wi-Fi 下载与下载列表
- `设置 > 关于`：开源许可、第三方许可、参考致谢、隐私声明与脱敏诊断

应用内隐私声明原文为：音乐目录仅在你授权的范围读取；账号凭据由所属音乐服务插件保存；无默认遥测，
不上传本地音乐或搜索记录；备份使用口令加密，不包含账号凭据。

## 操作方式

圆盘：

- 旋转：移动焦点
- 中心：确认；在播放页按 Normal → Seek → Volume → Lyrics 循环
- `MENU`：返回
- 左 / 右：上一首 / 下一首
- 下：播放 / 暂停

键盘与媒体控制沿用同一路由。敏感操作（安装插件、导出数据、移除文件夹、云端写入）需要先通过系统解锁，
再由用户明确确认。

## 验证记录

本仓库把验收证据纳入版本控制，而不是只声明结论：

- [verification/README.md](verification/README.md) — 环境、复现命令与覆盖边界
- [data/VERIFICATION.md](data/VERIFICATION.md)、[playback/VERIFICATION.md](playback/VERIFICATION.md)、
  [plugins/VERIFICATION.md](plugins/VERIFICATION.md) — 各模块验收记录
- [verification/netease.md](verification/netease.md)、[verification/music-source.md](verification/music-source.md)、
  [verification/window-settings.md](verification/window-settings.md)、
  [verification/plugin-install-delete.md](verification/plugin-install-delete.md) — 功能级验收记录
- 固定单测结果 XML 与原始构建/设备日志与上述文档放在一起，其中包括修复前的失败记录

设备截图与 ADB 转储体积大且随设备变化，仅保留在本地工作目录，不随仓库分发。

## 项目状态与已知限制

- **国际化尚未完成。** 目前全部用户可见文案都是 Kotlin 中的中文硬编码，没有 `res/values/strings.xml`，
  也没有任何语言变体。补齐字符串资源与至少一种翻译语言，是让非中文用户可用之前最大的待办项
- 未提供 `keystore.properties` 时 release 产物为未签名包
- 设备验收尚不完整。蓝牙播放、耳机移除、音频焦点中断、电池限制、进程终止、设备重启，以及不同 ROM 的
  锁屏行为仍需按实际设备逐项覆盖
- 网易云插件的真实云端写入、完整曲目权益与各档音质**尚未**验收；已验证的是真实账号上的只读链路
- 真实网络断开/恢复下的下载行为，以及设备电池/网络限制下的 WorkManager 重排队，仍需设备验证
- 本仓库尚未配置 CI、在线插件目录或发布流水线
- 各阶段状态与当前验收队列见 [DEVELOPMENT.md](DEVELOPMENT.md)

## 相关项目

EasePod 是独立实现，界面设计参考了以下项目：

- [Classipod](https://github.com/adeeteya/Classipod) — iPod 式界面结构与圆盘交互参考
  （BSD-4-Clause；未使用其代码、字体或位图）
- [CNMPlayer](https://github.com/professor-lee/CNMPlayer) — 云服务适配流程的实现参考
- [SoundHelix](https://www.soundhelix.com/) — 仅由独立示例插件使用的公共示例音频

应用内致谢随包提供：`app/src/main/assets/THIRD-PARTY.txt`，许可全文在
`app/src/main/assets/licenses/`。

## 许可

EasePod 原创代码与资源采用 [AGPL-3.0-only](LICENSE)。全文同时打包进 APK 的
`app/src/main/assets/LICENSE`。

第三方组件与许可全文见
[app/src/main/assets/THIRD-PARTY.txt](app/src/main/assets/THIRD-PARTY.txt)；运行时依赖清单在构建时由
`releaseRuntimeClasspath` 生成到 `DEPENDENCIES.txt`。网易云插件另外保留了其核验到的上游许可全文：
[netease-plugin/licenses/WTFPL.txt](netease-plugin/licenses/WTFPL.txt)。

分发受 AGPL 覆盖的构建时，需同时提供对应源代码、构建材料与第三方声明，并说明获取对应源码的方式。
本仓库不预设对外源码托管地址，该地址由发行者提供。交付包结构见 [DELIVERY.md](DELIVERY.md)。

网易云插件为非官方实现，与网易云音乐无任何关联；使用它可能违反服务条款并带来账号限制风险。
安装前请阅读 [netease-plugin/PROTOCOL_SOURCES.md](netease-plugin/PROTOCOL_SOURCES.md) 中的免责声明。

---
## Star History

[![Star History Chart](https://api.star-history.com/image?repos=professor-lee/EasePod&type=date&legend=top-left)](https://www.star-history.com/?repos=professor-lee%2FEasePod&type=date&legend=top-left)
