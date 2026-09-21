# 插件安装与删除验收

记录日期：2026-09-09。

- SoundHelix Sample（`app.easepod.sampleplugin`）是独立 APK。真机执行 `adb uninstall app.easepod.sampleplugin` 返回 `Success`，包从插件列表消失；重新安装 `easepod-sample-plugin-0.1.0-debug.apk` 返回 `Success`，`SampleMusicService` 重新被发现。插件详情也提供“卸载插件”，卸载后宿主清理该插件的连接、账号和来源选择。
- 网易云音乐服务现在作为独立 APK `easepod-netease-plugin-0.1.0-debug.apk`（包名 `app.easepod.netease`）分发。它使用与 SoundHelix Sample 相同的跨进程 `MUSIC_PLUGIN` 契约，安装主 APK 后可选择安装；安装后在插件管理中核对签名、批准并启用。
- 插件 APK 不声明 `MAIN`/`LAUNCHER`，安装后不会在手机桌面新增图标；宿主通过显式 `APPROVE_HOST` Intent 打开授权页。
- 真机验收命令：
  ```sh
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  adb install -r netease-plugin/build/outputs/apk/debug/netease-plugin-debug.apk
  adb shell pm path app.easepod.netease
  adb uninstall app.easepod.netease
  ```
  卸载返回 `Success` 后，宿主插件列表应移除网易云服务并清理该插件的连接、账号和来源选择；重新安装同一 APK 后，插件会重新发现，需再次核对签名并启用。卸载主应用不会自动安装该插件，是否安装由用户决定。

对应代码和设备断言位于 `plugins/PluginManager`、`AccountAuthDeviceTest` 及 `verification/music-source-virtual-device.txt`。
