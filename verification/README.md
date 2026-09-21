# EasePod 验证索引

记录日期：2026-09-08。正式开发状态以 [DEVELOPMENT.md](../DEVELOPMENT.md) 为准；这里集中说明证据位置、复现条件和覆盖边界。后续筛选测试可能覆盖 Gradle 默认报告，应同时保留模块的固定日期报告。

最新增补见 [主音乐来源验收](music-source.md) 与 [网易云独立插件验收](netease.md)：主界面使用已验证账号，网易云每日推荐、私人雷达、歌曲漫游已并入 Cover Flow/歌单。真实账号只读目录、搜索与来源恢复结果按批次记录；真实云端写入、完整播放权益和所有质量档位仍未宣称验收。 插件安装、删除与可选网易云 APK 边界见 [插件安装与删除验收](plugin-install-delete.md)。

此前 [锁屏手势与全屏显示验收](window-settings.md) 记录单测 125/125、Debug Lint 无问题、应用设备回归去重 34 项通过（含公共 HTTPS 1 项）。播放与缓存 14/14、下载竞态修正后 13/13、Binder 12/12，分批设备用例去重共 73 项；真机上滑进入系统 PIN 页及取消返回通过。该批次 Debug/R8 Release 构建成功，Debug 已安装。原下载首轮失败与修正证据均保留。历史批次数量不与网易云批次累加，也不代表最新补丁整体完成验收。

## 环境与入口

- JDK 17、Gradle Wrapper 8.9、SDK Platform 35。
- 本轮实际设备为 HiBreak，Android 14 / API 34，屏幕 824 × 1648。截图不是 HTML 预览。
- 主 APK：`app/build/outputs/apk/debug/app-debug.apk`；独立网易云插件 APK：`netease-plugin/build/outputs/apk/debug/netease-plugin-debug.apk`；独立示例 APK：`sample-plugin/build/outputs/apk/debug/sample-plugin-debug.apk`。
- 单测 HTML 报告：各模块的 `build/reports/tests/`；设备报告：各 Android 模块的 `build/reports/androidTests/connected/debug/`。
- [home.png](device/home.png) 与 [coverflow.png](device/coverflow.png) 记录真实 SAF 授权和三首曲目索引后的首批设备画面。

## 已完成设备批次

最终增补前的本轮 Debug 构建已取得以下结果，不能替代启动恢复等最新补丁的最终回归：

| 测试 | 结果 | 用时 |
|---|---|---|
| `LocalLibraryDeviceTest` | 5 / 5 通过 | 17.265 秒 |
| `DeviceShellTest` | 6 / 6 通过 | 9.821 秒 |
| Cover Flow 动画单例重跑 | 1 / 1 通过 | 2.253 秒 |

[设备截图目录](device/verification/) 保存 43 张主路由、三种内置主题、本地播放/歌词/队列/歌单及 Cover Flow 画面。设备原来的 `animator_duration_scale=0` 会关闭动画；动画单例运行时临时设为 `1`，完成后已恢复为 `0`。[顺时针中间帧](device/verification/local-coverflow-clockwise-mid.png) 与 [逆时针中间帧](device/verification/local-coverflow-counterclockwise-mid.png) 经画面检查和像素差验证发生移动，没有把静止画面算作动画通过。

## 复现命令

在 `project/` 中执行，`JAVA_HOME` 与 Android SDK 路径需按本机配置。对同一工作目录顺序构建，避免并发 Gradle 编译竞争输出目录。

```sh
./gradlew :app:assembleDebug :netease-plugin:assembleDebug :sample-plugin:assembleDebug :app:assembleRelease
./gradlew :core:test :data:testDebugUnitTest :playback:testDebugUnitTest :plugins:testDebugUnitTest :netease-plugin:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.easepod.ui.DeviceShellTest
./gradlew :sample-plugin:connectedDebugAndroidTest

# 安装主应用后可按需安装网易云插件；卸载插件不会卸载宿主
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r netease-plugin/build/outputs/apk/debug/netease-plugin-debug.apk
adb shell pm uninstall app.easepod.netease
```

播放控制器与下载协调器使用不同 WorkManager 初始化配置，分别运行，让每组 instrumentation 拥有新的进程：

```sh
./gradlew :playback:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.easepod.playback.PlaybackDeviceTest
./gradlew :playback:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.easepod.playback.DownloadCoordinatorDeviceTest
```

完整本地库流程使用这里的 `media/` 夹具。将夹具放入设备中独立的 `EasePod-QA` 目录，在 EasePod 系统文件选择器中手动授予该目录，再运行：

```sh
adb push verification/media /sdcard/Download/EasePod-QA
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.easepod.ui.LocalLibraryDeviceTest
```

预期是一个根单曲、两个一级专辑，共三首 WAV / FLAC / OGG 静音曲目；Album-A 有时间轴歌词，两个专辑分别使用 PNG / JPEG 封面。首次运行 `adb push` 后先检查目录结构，避免已有目录导致额外嵌套。测试只接受明确命名的测试库，不应以个人音乐目录代替夹具。

## 证据与范围

| 模块 | 覆盖内容 | 证据入口 |
|---|---|---|
| data | SAF 扫描发布、根目录与一级专辑、持久授权、歌单事务、内嵌/旁挂歌词、备份验证、设置兼容 | [data/VERIFICATION.md](../data/VERIFICATION.md)，`data/build/test-results/` |
| playback | Media3 解码与进度、真实队列编辑、进程检查点、MediaSession 服务、账号与来源门控、下载持久化与校验 | [playback/VERIFICATION.md](../playback/VERIFICATION.md)，`playback/verification/` |
| plugins | 主题归档与资源验证、签名索引、能力协商、真实跨进程 Binder、取消/代次/信任撤销/进程死亡 | [plugins/VERIFICATION.md](../plugins/VERIFICATION.md)，`plugins/build/test-results/` |
| netease-plugin | 协议与账号夹具、公共网络目录/搜索/播放/歌词、二维码布局、账号选择与真实 Keystore；真实登录未通过 | [netease.md](netease.md)，`netease-unit/`，`netease-*.txt` |
| app | 固定菜单、防误触与无障碍、输入会话、解锁续接、主题预览、Cover Flow、本地库播放与歌单、启动崩溃判断 | `app/build/test-results/`，`app/build/outputs/androidTest-results/` |

模块文档中标注的数量属于各自记录批次，不能与后来新增测试混为一份最终结果。本轮新增启动恢复和主题延迟初始化的最终执行结果由统一回归写入开发进度文档；未执行的测试源代码不能作为通过证据。

## 验证边界

设备自动化的下载夹具使用真实 Room / WorkManager 与可控 DataSource，验证持久化、取消、续传、内容校验和授权竞争；它本身不验证公共互联网的 TLS、HTTP Range、重定向或真实账号服务。跨进程示例插件的 Binder 通过也不等于外网流媒体播放通过，需独立记录。

当前设备覆盖不等于所有 Android ROM、蓝牙耳机、实体音频路由、硬件媒体键和电池限制策略均已验收。锁屏覆盖遵守 `setShowWhenLocked` 和后台启动限制；不承诺从每次熄屏或后台状态强制显示整个应用。

自动安全模式只依据宿主未捕获崩溃记录或 Android 的 `CRASH` / `CRASH_NATIVE` 退出原因，并绑定前次启动进程与时间窗口。单测覆盖计数及排除规则；模拟连续崩溃、系统回收与 Activity 重建的全流程设备验收应独立登记。发行 APK 的签名、对外源码地址和在线插件目录属于发行配置，本仓库不伪造这些材料。
