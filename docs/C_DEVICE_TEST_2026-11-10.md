# C 统一代码真机测试（2026-11-10）

本次使用本地 C1 工作区（包含尚未提交的选词接口统一修改），重新构建并覆盖安装 Debug 应用与测试包。用户已允许本次生成测试 APK；未构建 Release 包，未提交或推送代码。

## 设备与结果

- 设备型号：24115RA8EC；Android 16 / API 36；USB ADB。
- `TranslationPersistenceTest`：9 项通过，0 失败，仪器报告耗时 0.904 秒。
- `SelectionTranslationDeviceTest`：5 项通过，0 失败，仪器报告耗时 87.189 秒（包括界面启动等待）。
- 合计：14 项通过，0 失败。
- 应用与测试包均使用 `adb install -r` 安装，没有卸载或清空应用书库。

## 覆盖范围

章节与持久化的 9 项：空章无网络完成；成功译文缓存及幂等计数；持久化重试预算与服务配置快照；导入合成 EPUB 后由真实 WorkManager 执行模拟翻译及缓存复用；原文哈希错误的事务回滚；取消时拒绝迟到写入及重新开始；数据库 v1→v2 迁移保留书籍、进度、任务和译文；不同 Repository 实例重复启动去重；删除书籍后清理及拒绝迟到写入。

选词的 5 项：

1. 非模拟弹窗在点击确认前不调用服务（含 Activity 重建后），点击后仅发送所选文字，保留空白、换行、标点及 emoji，校验临时 ID 与 SHA-256。
2. 模拟弹窗完成后重建 Activity，保留结果与原服务模式；配置绑定变化不会让已有弹窗重新发送。
3. 取消旧选择后，即使旧服务忽略中断并迟到返回，新选择最终显示正确译文。
4. 失败显示服务消息，显式重试使用新的任务和请求 ID。
5. 接受 4000 个 emoji 码点且不截断；拒绝 4001 个码点，不增加服务调用。

选词界面用真实 MainActivity、BottomSheet 和 ActivityScenario 重建；服务使用可控本地测试实现。测试过程中界面启动等待，需要通过 ADB 使用相同 MAIN/LAUNCHER Intent 将应用带到前台后才继续。此启动条件需保留在复跑说明中，不能把本次结果视为无人干预的 UI 回归。

## 复跑

JDK 使用 `D:\AndroidStudio\jbr`。构建 `:app:assembleDebug :app:assembleDebugAndroidTest`，覆盖安装两个 Debug APK，然后执行：

```powershell
adb shell am instrument -w -r -e class com.example.bilingualreader.translation.TranslationPersistenceTest,com.example.bilingualreader.translation.SelectionTranslationDeviceTest com.example.bilingualreader.debug.test/androidx.test.runner.AndroidJUnitRunner
```

若界面启动停滞，使用另一个终端：

```powershell
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10008000 -n com.example.bilingualreader.debug/com.example.bilingualreader.ui.MainActivity
```

本次原始 TestRunner 日志保存在忽略目录 `work/device-persistence-2026-11-10.log`、`work/device-selection-2026-11-10.log`。机器可读摘要见 [device-test-results-2026-11-10.json](device-test-results-2026-11-10.json)。

## 未覆盖

没有真实翻译服务地址，本次未向外部服务发送文字；联网确认使用本地测试服务，不能证明 HTTP 联通、断网恢复或真实译文质量。没有验证手指长按及拖动系统选择手柄的整条交互，也没有用完整商业 EPUB 跑全书翻译。Activity 重建验证不能替代所有机型的物理旋转、后台杀进程及系统省电测试。历史 JVM 和其他日期验收结果不计入本次 14 项真机结果。

本次新增的是 Android 集成测试与报告，未因测试发现需要修改的生产代码问题。
