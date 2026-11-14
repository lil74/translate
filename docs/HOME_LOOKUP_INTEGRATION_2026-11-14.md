# 首页查词与中英互译对接验收

日期：2026-11-14。按用户提供的 `C_HOME_LOOKUP.md` 对接。先将 C2 快进到远端 main `6be51bd`，再恢复未提交的选词语境、浮窗定位和英语发音修改，解决三个文件的冲突。原选词修改的 stash 备份仍保留；本轮没有提交或推送。

## 最终行为

- 首页复用已有查询入口与 `AppContainer.selectionBinding()`，继续使用 `TranslationService` 和单项 `TranslationBatchRequest`；没有新增 HTTP 路由、Room 表、章节任务或缓存类型。
- 自动判断包含汉字时优先中译英，否则拉丁字母英译中；支持手动覆盖。数字或符号在自动模式下提示选择方向。
- 输入支持换行，原文、空白及 SHA-256 保持一致，按 Unicode 码点限制 4000 字符，不截断。每次提交和重试创建新的临时任务与请求标识。
- 恢复文档要求的仅内存策略，删除持久化历史实现；进入查询页会清除旧版本 `lookup_history`。输入、方向和结果关闭 View 自动状态保存，通过保留的 ViewModel 支持旋转；不写书籍译文和阅读进度。
- 在线发送前展示方向和地址并确认。编辑输入或方向会清除结果、取消请求并关闭未确认对话框；离开或后台取消，迟到响应按请求代次隔离。取消或旋转不会自动重发。
- 每次查询固定服务、模拟标志及地址快照；服务变更对下一次查询生效。页面内新增“翻译服务”按钮；模拟结果明确标识“非真实翻译”。
- HTTP 适配器兼容 `en → zh-CN` 与 `zh-CN → en`，在供应商边界把 `zh-CN` 映射成 `zh`；同时保留选词 context、真实 provider 与模型字段校验。阅读页选词和章节仍英译中。

## 网关部署与真实样本

已备份并更新 ECS `/opt/bilingualreader/app/gateway.py`，重载现有服务。备份路径为 `/opt/bilingualreader/app/gateway.before-home-20261114.py`。地址仍是 `https://8.148.28.233/translate`，认证及手机配置无需变更，供应商密钥仍只在服务器。

网关现在根据两个方向设置 Qwen-MT 的 `source_lang`、`target_lang` 和翻译指令；反向查询不会沿用“译成中文”的指令，术语表在反向时交换 source/target。语境选词仍限定英译中。

通过系统受信任 HTTPS 验证了 4 个自编样本，实际模型均为 `qwen-mt-plus`：

| 输入 | 方向 | 实际返回 |
| --- | --- | --- |
| hello | 英译中 | 你好 |
| 你好 | 中译英 | Hello |
| 今天下午我们去图书馆读书。 | 中译英 | This afternoon, we went to the library to read. |
| bank，语境 She sat on the river bank. | 语境英译中 | 河岸 |

原始非敏感结果见 [云端样本 JSON](home-lookup-cloud-results-2026-11-14.json)。这些样本证明两个方向和语境链路实际可用，不代表完整语料的翻译质量验收；没有发送用户 EPUB 内容。

## 本轮检查结果

- Debug APK、测试 APK、Release Java 编译通过。
- JVM 53 项全部通过：章节 13、网关 HTTP 5、首页查询 8、语境 6、浮窗定位 6、选词服务 10、阅读进度 5。
- 后端 13 项全部通过，覆盖双向映射、原文保留、反向术语、拒绝非法方向、认证与错误脱敏，以及语境回归。
- Pixel_6 API 34 模拟器 12 项全部通过，0 跳过，19.518 秒：首页查询 5、原选词 5、原生选区浮窗 1、英语 TTS 1。新增首页用例验证迟到响应隔离、服务快照与新请求 ID、旧确认快照失效、后台取消、旋转保留手动方向与无历史写入。
- Lint：0 错误、87 警告，未禁用检查。`git diff --check` 通过。

本轮 ADB 未检测到实体手机，没有安装到用户手机。模拟器测试采用受控服务，真实百炼联调由本机通过 HTTPS 完成，不能表述为“真机在线验收通过”。发音用例依据 TTS 开始/完成回调验证，没有主观听音确认。

本地忽略日志：`work/home-lookup-final-layout-build.log`、`work/home-lookup-device-final-results.log`、`work/home-lookup-integration-final-build.log`。测试 APK 和生产密钥均未加入 Git。
