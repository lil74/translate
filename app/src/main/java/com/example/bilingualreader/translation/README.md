# translation · C 负责

真实服务适配器、批次与 ID 校验、Coordinator 实现。通过 TranslationStore 落库。

已实现 `LibreTranslateService`、`TranslationValidation`、`TranslationBatcher`、`ChapterTranslationRunner`、`WorkTranslationCoordinator`，Debug 提供明确标注的 `FakeTranslationService`。

两个 AppGraphFactory 已接入正式 Coordinator；服务、任务、取消与启动恢复通过现有 Room 仓库协作。用户可在菜单配置服务；Release 不包含 Fake。复现、配置和测试边界见 `docs/C_IMPLEMENTATION.md`。
