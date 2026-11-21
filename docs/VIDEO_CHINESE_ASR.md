# 无字幕中文视频生成中文字幕

视频学习页增加“无字幕：识别中文语音并生成字幕”。应用在本地从视频提取首条 AAC 音轨，显示费用和上传说明后提交到团队 `/asr/jobs` 网关；网关通过 Paraformer-v2 `language_hints: ["zh"]` 识别并返回时间戳。结果直接写成中文 ASR 字幕，不送入文本翻译服务。随后可以预览、修正、播放、保存项目并导出 SRT/VTT。

输入仍受 ASR 支持边界约束：10 分钟以内，提取音频不超过 20 MB，兼容 AAC 音轨；需要联网、有效服务令牌和服务端百炼 ASR 权限。视频画面不上传。暂停等待不会保证取消已提交的供应商任务或费用。

网关 `/asr/jobs` 接受可选 `X-Recognition-Language: en|zh`，缺省值保持为 `en`。服务端任务 ID 按语言和音频分别生成。Paraformer-v2 REST API 的 `language_hints` 支持 `zh`，见[阿里云录音文件识别 REST API](https://help.aliyun.com/zh/model-studio/paraformer-recorded-speech-recognition-restful-api)。

本地 Debug/Release Java 编译通过，后端 Python 语法编译通过。2026-11-19 已部署 `backend/asr.py` 到团队网关；服务重启正常，`/health` 返回 `speechRecognition: true`。尚未执行真实中文音频识别验收。
