# 视频内嵌软字幕提取

视频页新增“提取视频软字幕”。Android `MediaExtractor` 在手机本地查找并读取 MP4/3GPP 等容器中设备可识别的 timed-text 轨道，提取正文和样本时间戳，再进入现有字幕预览、修正、翻译、双语播放及 SRT/VTT 导出流程。视频文件不上传。识别不到兼容轨道时，页面会提示改用 OCR、ASR 或外部 SRT/VTT。

当前按 Android 提供的轨道 MIME 处理 tx3g/3GPP timed text、MP4 WebVTT、VTT、SubRip 和 TTML 样本；具体可读格式受 Android 版本及视频封装影响。字幕时间以容器样本时间为准，样本没有明确结束时间时以字幕清空/变更时刻结束，最后一条暂用 3 秒。复杂 TTML 样式、区域定位、样本内逐 cue 时长不在当前版本支持范围，应预览检查时间轴。每个视频最多提取 5000 条，每条 40000 字符。

编译验证：Debug 与 Release Java 编译通过。尚未在含真实软字幕的视频文件上验证各容器格式。
