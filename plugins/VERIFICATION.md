# 插件验证记录

2026-09-08，HiBreak Android 14 / API 34，ADB 序列 B651…0383。

独立安装 `sample-plugin-debug.apk` 与测试 APK，通过 `adb shell am instrument -w -r app.easepod.sampleplugin.test/androidx.test.runner.AndroidJUnitRunner` 执行。结果为 `OK (12 tests)`，耗时 8.258 秒。测试断言 Binder 为跨进程代理。

上面的 12 项是 2026-09-08 的历史批次；本次新增的 Manifest 无桌面入口断言尚未在设备上执行，不计入该结果。

已通过：

- 标准浏览、搜索与播放源模型。
- 分页位置和查询绑定。
- 不兼容协议主版本与伪造宿主身份拒绝。
- 页长和 deadline 上限。
- 并发上限及完成请求去重。
- 取消后抑制延迟回调，新连接使旧代次失效。
- 撤销信任立即阻断现有连接与在途响应。
- 解绑使之前的连接失效。
- 插件进程终止不结束调用方进程。
- 账号与歌词响应有界，示例服务不伪造登录成功。

此组测试调用真实独立进程，不代表已完成宿主 PluginManager UI 启用/审批流程、HTTPS 音频完整播放或联网下载验收。宿主目录映射修复与主题包、签名索引测试需以最新统一 Gradle 结果为准。
