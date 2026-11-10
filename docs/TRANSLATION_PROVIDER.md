# 翻译服务配置与运行边界

更新：2026-11-07。当前使用 LibreTranslate 适配器，面向团队自建、不需要供应商长期密钥的实例。章节任务、Worker 和页面已接入；尚无团队服务地址，不代表在线引擎可用性或质量已经验收。完整交付见 [C_IMPLEMENTATION.md](C_IMPLEMENTATION.md)。

## 官方依据

- [API 使用与认证](https://docs.libretranslate.com/guides/api_usage/)：`POST /translate`，请求 `q/source/target/format`，返回 `translatedText`。启用密钥的实例需要 `api_key`；自建实例可不要求密钥。
- [支持语言](https://docs.libretranslate.com/guides/supported_languages/)：英语 `en`、中文 `zh`、繁体中文 `zt`；应用内部继续使用契约的 `en/zh-CN`，适配器将 `zh-CN` 映射为 `zh`。
- [自建安装](https://docs.libretranslate.com/guides/installation/)：根据官方说明部署，安装英文到中文模型，并用实例的 `/languages` 确认实际可用语种。

## 当前实现

`new LibreTranslateService("https://团队域名/translate")` 实现既有 TranslationService。通过构造方法传完整 URL，不在应用里写死公网服务地址。只支持无需 API Key 的自建实例，不接受或保存长期密钥。团队付费服务应另加服务端代理适配器，当前代码不声称已经实现代理。

适配器选择逐段串行请求，因为供应商响应没有项目的段落 ID；使用当前输入的 paragraphId/sourceHash 生成 TranslationOutput，绝不猜测网络列表顺序。结果经过 TranslationValidation 检查，由 Runner 通过现有 TranslationStore 写入 Room。适配器不直接写数据库。

- 应用批次上限为 8 段 / 4000 Unicode 码点，不是供应商官方限额。
- 连接超时 10 秒、读取超时 30 秒；响应最大 1 MiB；拒绝重定向。
- HTTP 401/403 → AUTH_FAILED；429 → RATE_LIMITED；5xx → NETWORK_ERROR；其他非 200 → TRANSLATION_FAILED。供应商尚未给出稳定的额度错误结构，因此未把所有 403 或 400 猜成 QUOTA_EXCEEDED。
- 超时 → TIMEOUT；连接失败 → NETWORK_ERROR；空译文、非字符串或无效 JSON → INVALID_RESPONSE。
- Runner 对暂时错误退避，每个输入最多 3 次持久化尝试；HTTP 429 / 503 的 Retry-After 支持秒数和 HTTP 日期。等待期间不会消耗次数。鉴权 / 额度问题终止任务，成功缓存保留。
- Release 仅 HTTPS；Debug 与 JVM 测试额外允许 localhost/127.0.0.1 HTTP，未放开 Android 全局明文网络。停止 Worker 会断开当前连接。

## 上线前需确认

自建需要模型与计算资源；实际单请求上限、并发、费用、限流由部署者配置并记录。当前没有实际实例，不能给出已经验证的吞吐量或费用。不要把免费公开镜像当成稳定服务。若转用托管实例，应重新验证其费用、限额与认证并通过代理保存付费密钥。

已在 App 菜单增加“翻译服务”设置；首次向新地址在线翻译时会显示发送原文的确认，用户主动确认后才创建任务。任务保存配置快照，恢复时不会擅自切换服务。日志仅保留错误码，不打印正文、响应体或密钥。本次未将该 EPUB 发送到公网服务。

## 验证记录

已通过本地参考译文的 ID 对应验证和本机 HTTP 协议桩测试。协议桩返回预置参考文本，不是 LibreTranslate 引擎，不可作为翻译质量验收。

提供可用的团队实例后，可在 App 配置地址并打开未翻译章节，或用 `tools/verify_translation.py --endpoint` 复用第一阶段样本验证实际返回。需要人工检查语义、专名和简体中文输出；默认模拟结果不能替代此项。
