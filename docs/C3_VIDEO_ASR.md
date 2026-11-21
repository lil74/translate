# C3 无字幕视频语音翻译（2026-11-16）

当前项目同时支持两条文本获取链路：画面英文走本机 OCR；没有字幕但有英文语音的视频走音轨提取 → 云端 ASR → 中文翻译。两者最终使用同一套字幕片段、双语播放、单句重播、项目保存和 SRT/VTT 导出。

## 使用

1. 配置真实翻译服务及网关访问令牌，关闭模拟验证。语音识别不会用模拟文字冒充结果。
2. 导入有英文语音的视频，点击“无字幕：识别英文语音并翻译”。
3. 确认音频会上传至团队服务器并交由百炼识别，以及可能产生的模型调用费用。
4. 依次显示提取、上传、云端识别、翻译阶段；成功后播放中文字幕或双语字幕，无需另备 SRT/VTT。

初版提取第一条 AAC 音轨，主要适配 MP4/AAC，最多 10 分钟、20 MB 音频。没有音轨、音轨为空、加密或非 AAC 会明确提示。只有背景音乐或没有有效语音时不能保证生成字幕，可改用 OCR。没有接入声纹克隆或配音输出。

新增中文语音识别入口：Paraformer-v2 使用 `language_hints: ["zh"]` 生成中文字幕，识别后不再经过翻译。网关以语言和音频共同生成幂等任务 ID，中文与英文识别任务不会互相复用；`X-Recognition-Language` 缺省时仍按英文处理以兼容旧客户端。更新网关后才支持该入口。

视频画面不上传，也不需要麦克风权限。音频提取在后台线程进行，保留相对视频的起始偏移，正确处理 AAC 编码器预滚负时间戳。语音字幕使用供应商返回的句级时间戳，不平均分配时长或伪造时间。

“暂停等待”、离开页面或切后台会停止本地等待并隔离迟到结果，**不能保证停止已经提交到供应商的运行或计费**。再次点击语音识别可获取原任务结果，语音任务 ID 和音轨偏移保存在本机私有配置中。识别已完成而翻译失败时，直接用普通“继续翻译”，无需再次 ASR。旋转保留 ViewModel，不重复提交任务。

## 接口与存储

安卓：`video/VideoAudio.java`、`SpeechClient.java`，由 `VideoViewModel.prepareSpeech/recognizeSpeech` 串接到已有字幕翻译。一次确认固定服务与令牌；厂商密钥不进入 APP。图像 OCR 仍在本机完成，不因新增 ASR 自动上传图像。

后端新增 `backend/asr.py`，共享现有 Bearer SERVICE_TOKEN：

| 接口 | 作用 |
| --- | --- |
| `POST /asr/jobs`，Content-Type `audio/mp4` | 上传纯音频，校验大小、实际音轨类型和时长后提交 Paraformer |
| `GET /asr/jobs/{jobId}` | 获取 PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED 状态及真实字幕 |
| `DELETE /asr/jobs/{jobId}` | 删除本地任务音频并标记取消，不承诺撤销供应商任务或费用 |
| `GET/HEAD /asr/media/{jobId}?sig=...` | 供百炼下载的短期签名音频；无签名、错误签名或过期均不可读 |

同一音频使用服务端 HMAC 标识，已有运行中或成功任务重复上传不再次提交供应商。失败任务由用户手动重试，没有自动重试付费提交。供应商已接受请求但连接中断、尚未取得任务 ID 等极端情况，不能承诺供应商侧严格只执行一次。

任务元数据原子写入私有 `/var/lib/bilingualreader/asr`，支持网关进程重启后继续查询，并以跨进程文件锁覆盖优雅重载期间的新旧 worker。最多同时两个任务；任务链接有效一小时。识别成功、失败或本地删除时清理音频；放弃的音频由每十分钟的清理任务按约 3700 秒文件年龄删除。结果记录到期不可访问，并在最后写入约一小时后清理。APP 提取的临时音频在正常成功或异常退出提取流程时删除；进程被系统强杀时可能暂留在应用缓存。

视频原文和译文仍保存在应用私有项目文件中。另修复 `VideoProjectStore` 跨实例读写 AtomicFile 的竞争，避免检查点读取回滚正在进行的写入。

## 部署

现有 ECS 已部署并实际验证，APP 的原 `/translate` 地址及令牌不变。安装了服务端 ffprobe，以实际容器信息验证纯音频和时长；Nginx `/asr/` 允许 20 MB 上传且关闭访问日志；systemd 使用私有状态目录、70 秒 worker 超时，并启用 ASR 清理定时器。

服务器配置项：

```dotenv
ASR_PUBLIC_URL=https://你的团队服务公网地址
ASR_MODEL=paraformer-v2
# 可选：百炼原生 API 对应地域的 /api/v1 根地址
ASR_BASE_URL=https://你的百炼地域或工作空间域名/api/v1
```

未指定 ASR_BASE_URL 时从现有 QWEN_BASE_URL 提取域名；仍需确保同一地域的 API Key 具备 ASR 模型权限。示例文件只有占位配置，不含实际凭据。不要把百炼 API Key 填到 APP。

现有主机更新使用 `deploy/qwen/install-asr.sh`，更新前备份了网关、Nginx 和 systemd 配置到各自 `.before-asr-20261116` 文件。Dockerfile/Compose 同步增加 ffprobe、ASR 模块、持久卷和独立清理服务；Compose 配置解析通过，本轮没有构建或启动 Docker 替代生产服务。

## 验证

- Debug、测试 APK、Release Java 编译与 68 项 JVM 测试通过；Lint 0 错误、108 警告，未关闭检查。
- 后端 25 项通过，其中 ASR 10 项覆盖鉴权、重复上传、签名、真实时间戳映射、恢复、取消、时长检查、过期清理和坏响应；其余 15 项为原翻译网关回归。
- Pixel_6 API 34 模拟器 7 项通过、0 跳过、24.891 秒：6 项视频功能（含无音轨拒绝）和 1 项真实云端完整链路。
- 真实链路测试使用自编英文朗读和生成的**无字幕黑色视频**，APP 实际提取 AAC、上传音频、调用 Paraformer，再自动调用 Qwen；断言所有片段来源 ASR、具有有效时间戳、provider 为 qwen-mt 且结果包含中文。没有上传用户视频或书籍。
- 云端独立识别结果见 [ASR 样本](asr-cloud-results-2026-11-16.json)。实际返回 “Hello everyone, today we are learning English.” 及另一句原文，带 40–3460 ms、4310–6740 ms 时间戳。
- `SpeechDeviceTest` 只在通过运行时私有缓存注入测试配置后调用真实服务，测试后删除缓存和新增令牌配置。凭据不在源码、测试 APK 或命令参数中；正常无配置的 CI 会明确跳过该真实付费用例。

本轮未检测到实体手机，没有安装到用户手机。真实云端样本验证不代表对所有用户音轨、口音或背景噪声的准确率验收。日志在忽略目录 `work/c3-asr-*.log`；测试素材只含自编朗读。当前代码保留在 C3，尚未提交、推送。

接口依据：[百炼 Paraformer 录音识别官方文档](https://help.aliyun.com/en/model-studio/paraformer-recorded-speech-recognition-restful-api)。
