# translation · C 负责

真实服务适配器、批次与 ID 校验、Coordinator 实现。通过 TranslationStore 落库。

M1 已添加 `LibreTranslateService`（真实 HTTP 适配器）、`TranslationValidation`（请求与响应校验），Debug 提供 `FakeTranslationService`。

复现、EPUB 样本和验证边界见 `docs/C_PHASE1.md`。当前尚未接入 AppGraphFactory；原有 Debug 页面仍使用 DemoRepository。Coordinator / Worker 属于后续阶段，未实现。
