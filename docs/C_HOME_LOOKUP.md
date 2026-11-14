# 给 C：首页查词 / 中英互译接入说明

更新：2026-11-13。首页上方新增独立入口，支持普通中英互译。此文可直接发给 C。

## 已实现的体验

首页点击“查词 / 中英互译”，输入单词、短语或句子，点击查询。方向可以选择自动、英译中或中译英；自动模式包含汉字时优先中译英，否则检测到拉丁字母时英译中。混合文本可以手动覆盖。只有数字或符号时自动模式提示手动选择，不把它宣传为可靠的语言识别。

结果支持选择文字和复制，包含加载、取消、错误提示、手动重试。编辑输入或切换方向时会取消旧请求并清空旧结果；迟到响应不能覆盖新查询。旋转屏幕使用保留的 ViewModel，不自动重复查询。离开页面或切到后台会取消进行中的查询，返回后可以手动重试。

Debug 的模拟服务仍返回带有“模拟译文，非真实翻译”的占位结果，只证明流程可用。在线请求显示所用方向与发送地址，用户确认后才提交。本功能不提供音标、词性、多个词义或词典例句。

## C 不需要新增接口

复用 `AppContainer.selectionBinding()` 提供的 `TranslationService`，以及现有 `TranslationBatchRequest / TranslationBatchResult`。每次点击查询捕获一次服务与模拟标志快照；服务配置变更对下一次查询生效，已确认的请求继续使用捕获的服务。不会沿用整章协调器发请求，也不新增 Room 表、Worker、任务缓存或 HTTP 路由。

每次请求只有一项：

| 字段 | 规则 |
| --- | --- |
| sourceLanguage / targetLanguage | 英译中 `en / zh-CN`；中译英 `zh-CN / en` |
| taskId、paragraphId | 本次临时生成的同一个 `lookup:<UUID>`，数据库中不存在 |
| requestId | 另一独立 UUID；重试生成新标识 |
| text | 用户输入原文，保留空白、标点与换行，不在后台加入书籍内容 |
| sourceHash | 输入原文 UTF-8 SHA-256 |
| 长度 | 单次最多 4000 个 Unicode 码点，含空白与标点；超限提示缩短，不截断 |

返回 ID、hash 必须与请求一致；provider 非空，成功译文非空。响应通过已有校验器，错误消息沿用 AppException 的 userMessage。此页面无自动重试，失败后由用户主动点击。

## 本次对 C 现有代码的必要修改

1. `translation/TranslationValidation.java`：允许 `en → zh-CN` 和 `zh-CN → en`，继续拒绝其他语种与同语种请求。批次大小、哈希、ID、响应条目与空译文的校验保持有效。
2. `translation/LibreTranslateService.java`：去掉 HTTP payload 中固定的 en/zh，按请求的 sourceLanguage、targetLanguage 发送；共享契约中的 `zh-CN` 在供应商边界映射为 `zh`。
3. `IsolatedSelectionTranslationService` 无需改动，仍对每个查询创建独立适配器，首页和选词查询各自提交自己的临时 ID。

**整章翻译和阅读页选词仍请求 en → zh-CN。** 本轮不扩展整章语言支持，不修改整章缓存规则，也不会把首页结果写入书籍译文。

例如查询“你好”，供应商收到的请求体是：

```json
{"q":"你好","source":"zh","target":"en","format":"text"}
```

`/translate` 返回的 translatedText 按既有适配器绑定回原 ID/hash。APK 不包含供应商长期密钥。

## C 需要核对的部署事项

现有团队服务需要实际提供 **en → zh 和 zh → en 两个方向** 的模型。本地 Compose 配置已经声明 `LT_LOAD_ONLY=en,zh`，但不能据此保证某台服务器已经安装并可用；请分别用 hello 和你好做在线验收。服务地址仍通过菜单“翻译服务”配置，网络访问、认证要求与部署步骤参照 [真实服务接入说明](REAL_TRANSLATION_SETUP.md)。若另接供应商，应在适配器中映射语种，不把供应商的 zh 写进共享契约。

在线服务的地址、模型状态和译文质量需在实际设备上验证。本轮本地 HTTP 测试只验证应用发送方向和响应绑定，不代表供应商翻译质量通过。

## 新代码在哪里

| 文件 | 职责 |
| --- | --- |
| `ui/lookup/LookupFragment.java` | 输入、方向选择、在线发送确认、结果与复制 |
| `viewmodel/LookupViewModel.java` | 服务快照、后台执行、取消与迟到响应隔离 |
| `translation/LookupQuery.java` | 自动方向规则、临时请求组装、响应验证 |
| `res/layout/fragment_lookup.xml` | 查询页布局 |
| `ui/bookshelf/BookshelfFragment.java`、`res/layout/fragment_bookshelf.xml` | 首页入口 |
| `ui/MainActivity.java`、`viewmodel/ViewModelFactory.java` | 导航与 ViewModel 接线 |
| `src/test/.../translation/LookupQueryTest.java` | 双向请求、自动判断、长度、哈希与本地 HTTP 回归 |

Java 路径以 `app/src/main/java/com/example/bilingualreader/` 为根。

输入、结果只在页面 ViewModel 内存中保留，不创建历史记录、不写阅读位置。用户主动复制时写入系统剪贴板。取消使用线程中断与请求代次隔离；已经发出的 HTTP 请求不保证立即断开，原适配器超时限制继续生效。

## 联调验收

1. 模拟模式：空输入、中文、英文、混合文本、手动方向、超长输入、加载、结果模拟标志、复制。
2. 在线模式：分别查询 hello 与你好，确认提示中的方向、服务器收到的 source/target 和结果语言。
3. 确认对话框点取消，服务器应无新增请求；编辑内容不会自动查询。
4. 断网、服务 4xx/5xx、超时、空结果、错 ID/hash 显示错误，重试不会复用旧请求 ID。
5. 请求中修改输入、取消、离开页面和旋转屏幕，旧响应不应覆盖新输入；旋转不重复发请求。
6. 在查询页切换“翻译服务”，下一次查询使用新配置；再次打开阅读页确认英译中和整章任务仍正常。

## 本轮验证记录

2026-11-13：Debug、Release、测试 APK 构建通过。JUnit 共 31 项通过，包含 8 项新增首页互译测试与 23 项原有章节/选词回归测试；0 失败、0 错误。Lint 0 错误、68 警告，未禁用检查。`git diff --check` 通过。

新增测试包含自动方向与手动覆盖、输入和 Unicode 长度边界、临时 ID 与原文 hash、双向语种白名单、无效响应、独立适配器反向查询，以及本机 HTTP 服务接收到的两个方向的 payload。测试服务不具备翻译模型，响应为受控测试文本。

本轮未运行手机触屏验收，也未连接真实在线供应商验证译文质量。上述联调步骤仍需在设备和团队服务上执行。本地构建日志为忽略文件 `work/home-lookup-build.log`。


## 2026-11-14 对接更新

已按本文件恢复自动/手动方向、多行输入与仅内存保留策略；升级时清除先前版本的 `lookup_history`，不再写入查询历史。查询页可直接打开“翻译服务”。共享 HTTP 适配器同时保留选词 context、真实 provider/model 字段及双向语言映射。现有 Qwen 网关已扩展双向翻译，未新增接口、数据库表或章节缓存类型。

本轮实际构建、回归与云端样本记录见 [首页查询对接验收](HOME_LOOKUP_INTEGRATION_2026-11-14.md)；上面的 2026-11-13 记录为原文历史记录。
