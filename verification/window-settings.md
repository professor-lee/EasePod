# 2026-09-08 锁屏手势、全屏显示与集成回归

设备：HiBreak，Android 14 / API 34，824 × 1648。工具链：JDK 17、SDK 35、Gradle 8.9。本轮使用本机已解压的 Gradle 8.9 分发版，版本与 Gradle Wrapper 固定的 8.9 一致。

## 实现

- 锁屏覆盖开启且设备锁定时，从系统导航区域上方 72dp 内起手，上滑至少 72dp 后抬手，直接调用系统 Keyguard 解锁。防误触开启时仍有效，横滑、下滑、折返、多指、取消、短滑和窗口失焦均不会请求解锁。
- 系统负责 PIN、密码等认证；普通上滑清除待续接的敏感操作，原敏感操作入口仍需系统解锁后再次确认。
- “设置 > 全屏显示”默认关闭，开启隐藏顶部系统状态栏，保留底部导航及 LCD 状态栏。窗口重新获得焦点和 Activity 重建后重新应用偏好，旧 Proto/备份缺少新字段时默认关闭。
- 修复现有云歌单结果的跨模块可空属性编译错误，补齐已有示例服务设备测试的 Media3 注解依赖。

## 已取得证据

- 单元测试 125/125：core 2、data 46、playback 10、plugins 47、app 20。固定 XML 保存在 `window-settings-unit/`，覆盖设置/备份兼容以及现有迁移、云契约与播放规则。
- Debug 主 APK、设备测试 APK 和 `:app:lintDebug` 构建通过。Lint 报告为 `No issues found.`，构建日志见 `window-settings-build-final.txt`。
- 首批设备回归 26 项中 25 项通过，唯一失败是全屏测试的附加系统截图返回 null，未执行到全屏断言。已改用本项目现有 Compose 截图接口，后续回归单独记录。
- 首批通过包括锁屏手势 9 项、普通解锁不续接敏感操作 1 项、DeviceShell 7 项、页面恢复 4 项、集成 4 项；全部无跳过。集成覆盖加密备份往返、主题安装/预览/应用/回滚/卸载和 Activity 会话恢复。
- 通过 ADB 截图独立核实全屏开关效果：`device/fullscreen-system-off.png` 显示顶部系统栏，`device/fullscreen-system-on.png` 隐藏顶部系统栏且 LCD 和轮盘完整。手工切换后已恢复关闭。

## 复现

```sh
./gradlew :core:test :data:testDebugUnitTest :playback:testDebugUnitTest :plugins:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class app.easepod.ui.LockScreenSwipeDeviceTest,app.easepod.ui.WindowSettingsDeviceTest,app.easepod.ui.DeviceShellTest,app.easepod.ui.PageRecoveryDeviceTest,app.easepod.ui.IntegrationDeviceTest app.easepod.test/androidx.test.runner.AndroidJUnitRunner
```

首批日志为 `window-settings-device.txt`。手势自动化使用独立 Compose 宿主，解锁门控测试替换系统查询并记录 Unlock 事件，不等同于真实 PIN 认证流程。下文单独记录本机系统 PIN 入口；不同 ROM 的锁屏覆盖、蓝牙硬件和连续崩溃恢复不能以这些自动化结果替代。

## 后续设备结果

- `window-settings-device-final.txt`：窗口设置 2 项和本地库 7 项全部通过，23.080 秒。全屏切换、底部导航保持、Activity 重建和旧敏感动作清理均通过；应用设备测试去重共 33 项通过。
- `window-settings-playback-device.txt`：播放 11 项与来源缓存 3 项全部通过，7.342 秒。
- `window-settings-plugin-device.txt`：独立插件跨进程 Binder 12/12，8.339 秒。
- 修正前的 `window-settings-download-device.txt`：12 项中 1 项失败，完成文件授权检查用例预期 COMPLETE、实际 FAILED。`window-settings-download-repro.txt` 单项 1/1，`window-settings-download-recheck.txt` 全量 12/12（4.372 秒）。旧数据库关闭崩溃未重现；后续修正与受控回归见下文，绿色重跑本身不作为修复证据。
- `window-settings-https-device.txt`：公共 HTTPS 示例服务 1/1，56.524 秒，覆盖实际流媒体播放、拖动进度、停用来源及重新启用不自动恢复播放。加上上述六组应用回归，应用设备测试去重共 34 项通过。
- `window-settings-release.txt`：Debug 测试 APK、播放测试 APK、独立示例 APK/测试 APK 及 R8 Release 构建成功，10 分 17 秒。下载竞态修正后的最终构建另行记录。

## 真机系统 PIN 入口

在 HiBreak 上保留防误触开启、全屏显示关闭，通过应用设置开启锁屏覆盖。熄屏再唤醒后，`dumpsys window policy` 确认 `showing=true`、`inputRestricted=true`、`secure=true`、`occluded=true`，应用 LCD 显示锁定图标，见 `device/lock-overlay-before-swipe.png`。

执行 `adb shell input swipe 412 1560 412 1050 500` 后，没有应用内中间对话框，系统 UI 树直接出现 `com.android.systemui:id/keyguard_pin_view` 和 `com.android.systemui:id/pinEntry`（`password=true`、描述“PIN 码区域”），数字键盘属于 `com.android.systemui`。原始 UI 树在 `device/lock-overlay-pin.xml`。系统认证窗口的 ADB 截图为全黑，不能当作可视画面证据。

按系统返回取消认证后回到仍处于锁定状态的播放器；没有输入、提交或修改 PIN。随后通过轮盘关闭锁屏覆盖，Proto 设置读回确认锁屏覆盖与全屏显示均关闭，防误触仍开启；系统恢复 `occluded=false`，设备保持锁定。此项验证覆盖“上滑进入系统 PIN 页”和取消返回，成功认证后的完整流程仍需单独验收。

## 下载竞态修正与最终构建

观察器收到旧 ENQUEUED/BLOCKED 快照时，可能把已经 DOWNLOADING 的同一请求退回排队，随后传输因状态不符被取消。现于状态锁内重新查询 WorkManager 的当前记录：RUNNING 时保留传输状态，真实重新排队时继续更新等待状态。原首轮日志不足以确定精确事件顺序，修正针对代码中确认的竞态。

新增回归用真实 WorkManager Worker 开始下载，在 DataSource 内暂停传输，再向生产观察处理函数送入先前 ENQUEUED 快照，确认仍为 DOWNLOADING；放行后完成且可离线读取。

- `window-settings-download-race-device.txt`：新回归单项 1/1，0.291 秒。
- `window-settings-download-fixed-suite.txt`：修正后下载全量 13/13，4.799 秒，包含原失败项以及暂停/恢复、约束更改、旧请求隔离和关闭后的晚到事件。
- `window-settings-download-fix-build.txt`：播放单测 10/10 与更新的设备测试 APK 构建通过，15 秒；固定单测 XML 已更新，本轮单测仍合计 125/125。
- `window-settings-delivery-build.txt`：包含下载修正的 Debug、Debug 测试 APK、Debug Lint、R8 Release 全部构建成功，3 分 11 秒；Lint 为 `No issues found.`。Debug APK 签名校验通过并已覆盖安装至 HiBreak。

本轮分批通过的设备用例去重合计 73 项：应用 34、播放/缓存 14、下载 13、独立插件 12。它们来自上述不同批次，不是一条命令的整体执行结果。交付脚本 `scripts/package-delivery.sh` 汇总 APK、源码和校验文件到 `dist/`；Release 为未签名包，可直接安装的是 Debug 包。
