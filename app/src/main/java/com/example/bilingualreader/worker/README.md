# worker · C 负责

Java Worker，只接收 taskId；网络约束、唯一工作、取消和启动恢复。

`TranslationWorker` 已实现：只接收 taskId，从 Room 读取任务与服务快照，调用翻译核心；网络约束由 Coordinator 设置，尝试次数与 Retry-After 等待时间持久化到同一个数据库。执行停止后拒绝迟到写入，应用启动修复未调度的活跃任务。见 `docs/C_IMPLEMENTATION.md`。
