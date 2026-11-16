"""Small authenticated file-ASR gateway. Vendor jobs survive worker restarts."""
import hashlib
import hmac
import json
import os
import re
import threading
import subprocess
import time
import urllib.error
import urllib.request
from contextlib import contextmanager
from pathlib import Path
from urllib.parse import urlparse, parse_qs

LOCK = threading.Lock()
MAX_AUDIO = 20 * 1024 * 1024


class AsrError(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


def fetch_json(url, headers=None, payload=None):
    from gateway import NoRedirect
    request = urllib.request.Request(url, headers=headers or {},
        data=None if payload is None else json.dumps(payload).encode())
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=25) as response:
            raw = response.read(2 * 1024 * 1024 + 1)
        if len(raw) > 2 * 1024 * 1024:
            raise AsrError(502, "语音识别结果过大")
        return json.loads(raw)
    except urllib.error.HTTPError as error:
        if error.code in (401, 403):
            raise AsrError(502, "服务器未开通语音识别模型或百炼凭据无权限") from None
        if error.code == 429:
            raise AsrError(429, "语音识别限流，请稍后重试") from None
        raise AsrError(502, "百炼语音识别请求失败，请检查模型和地域配置") from None
    except (ValueError, urllib.error.URLError, TimeoutError):
        raise AsrError(503, "语音识别服务暂时不可用，请稍后继续") from None


def segments(result):
    cues = []
    for transcript in result.get("transcripts", []):
        if transcript.get("channel_id", 0) != 0:
            continue
        for sentence in transcript.get("sentences", []):
            start, end, text = sentence.get("begin_time"), sentence.get("end_time"), sentence.get("text")
            if (type(start) is not int or type(end) is not int or start < 0 or end <= start
                    or end > 610000 or not isinstance(text, str) or not text.strip()
                    or len(text) > 40000):
                raise AsrError(502, "语音识别返回的字幕时间轴或文本无效")
            cues.append(dict(startMs=start, endMs=end, text=text))
            if len(cues) > 5000:
                raise AsrError(502, "识别字幕超过 5000 条，请分段处理")
    if not cues:
        raise AsrError(422, "未识别到有效语音，请检查视频音轨或改用画面识别")
    return sorted(cues, key=lambda cue: cue["startMs"])


def validate_audio(path):
    try:
        result = subprocess.run(["ffprobe", "-v", "error", "-show_entries",
            "format=duration:stream=codec_type", "-of", "json", str(path)],
            capture_output=True, timeout=15, check=True)
        value = json.loads(result.stdout)
        duration = float(value["format"]["duration"])
        streams = value.get("streams", [])
        if not 0 < duration <= 601 or not streams or any(s.get("codec_type") != "audio" for s in streams):
            raise ValueError()
    except (subprocess.SubprocessError, ValueError, KeyError):
        raise AsrError(400, "请上传不超过 10 分钟的有效纯音频 M4A 文件") from None
    except FileNotFoundError:
        raise AsrError(503, "服务器缺少音频检查工具，请联系维护者") from None


class AsrGateway:
    def __init__(self, config, transport=fetch_json, root=None, public_url=None, validator=validate_audio):
        self.config, self.transport = config, transport
        self.root = Path(root or os.getenv("ASR_STATE_DIR", "/var/lib/bilingualreader/asr"))
        self.public = (public_url or os.getenv("ASR_PUBLIC_URL", "")).rstrip("/")
        origin = urlparse(config.base_url)
        self.base = os.getenv("ASR_BASE_URL", f"https://{origin.netloc}/api/v1").rstrip("/")
        self.model = os.getenv("ASR_MODEL", "paraformer-v2")
        self.validator = validator

    @contextmanager
    def locked(self):
        # Thread lock plus cross-worker file lock, including Gunicorn's graceful reload overlap.
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        with LOCK, (self.root / ".lock").open("a") as lock:
            try:
                import fcntl
            except ImportError:  # Local Windows unit tests; production runs on Linux.
                fcntl = None
            if fcntl:
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                if fcntl:
                    fcntl.flock(lock.fileno(), fcntl.LOCK_UN)

    def write(self, job_id, value):
        temporary = self.root / (job_id + ".tmp")
        temporary.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        temporary.replace(self.root / (job_id + ".json"))

    def load(self, job_id):
        if not re.fullmatch(r"[a-f0-9]{32}", job_id):
            raise AsrError(404, "语音任务不存在")
        try:
            job = json.loads((self.root / (job_id + ".json")).read_text(encoding="utf-8"))
        except FileNotFoundError:
            raise AsrError(404, "语音任务不存在或已过期") from None
        if job["expires"] <= time.time():
            raise AsrError(410, "语音任务已过期，请重新开始")
        return job

    def signature(self, job_id, expires):
        return hmac.new(self.config.service_token.encode(), f"asr:{job_id}:{expires}".encode(), hashlib.sha256).hexdigest()

    def vendor(self, path, payload=None):
        headers = {"Authorization": "Bearer " + self.config.api_key, "Content-Type": "application/json"}
        if payload is not None:
            headers["X-DashScope-Async"] = "enable"
        return self.transport(self.base + path, headers, payload)

    def handle(self, env, reply):
        try:
            path, method = env.get("PATH_INFO", ""), env.get("REQUEST_METHOD")
            if path.startswith("/asr/media/"):
                if method not in ("GET", "HEAD"):
                    raise AsrError(405, "请使用 GET 或 HEAD")
                job_id = path.removeprefix("/asr/media/")
                job = self.load(job_id)
                supplied = parse_qs(env.get("QUERY_STRING", "")).get("sig", [""])[0]
                if not hmac.compare_digest(supplied, self.signature(job_id, job["expires"])):
                    raise AsrError(403, "音频访问链接无效")
                audio = self.root / (job_id + ".m4a")
                if not audio.is_file():
                    raise AsrError(410, "音频已清理")
                raw = audio.read_bytes()
                reply("200 OK", [("Content-Type", "audio/mp4"), ("Content-Length", str(len(raw))), ("Cache-Control", "no-store")])
                return [] if method == "HEAD" else [raw]
            supplied = env.get("HTTP_AUTHORIZATION", "")
            if not hmac.compare_digest(supplied.encode(), ("Bearer " + self.config.service_token).encode()):
                raise AsrError(401, "服务访问令牌无效")
            if path == "/asr/jobs" and method == "POST":
                return self.submit(env, reply)
            if not path.startswith("/asr/jobs/"):
                raise AsrError(404, "接口不存在")
            job_id = path.removeprefix("/asr/jobs/")
            with self.locked():
                job = self.load(job_id)
                if method == "DELETE":
                    job["status"] = "CANCELLED"
                    (self.root / (job_id + ".m4a")).unlink(missing_ok=True)
                    self.write(job_id, job)
                elif method == "GET":
                    if job["status"] in ("PENDING", "RUNNING"):
                        self.poll(job_id, job)
                else:
                    raise AsrError(405, "请使用 GET 或 DELETE")
            body = {key: job[key] for key in ("status", "segments", "error", "model") if key in job}
            body["jobId"] = job_id
            return self.json(reply, 200, body)
        except AsrError as error:
            return self.json(reply, error.status, {"error": error.message})
        except Exception:
            return self.json(reply, 500, {"error": "语音任务处理失败，请稍后继续"})

    def submit(self, env, reply):
        if not self.public.startswith("https://"):
            raise AsrError(503, "服务器尚未配置语音识别公网地址")
        if env.get("CONTENT_TYPE", "").split(";")[0] != "audio/mp4":
            raise AsrError(415, "请上传 M4A 音频")
        try:
            length = int(env.get("CONTENT_LENGTH", "0"))
        except ValueError:
            raise AsrError(400, "无效音频长度") from None
        if not 16 <= length <= MAX_AUDIO:
            raise AsrError(413, "音频大小应为 16 字节至 20 MB")
        raw = env["wsgi.input"].read(length)
        if len(raw) != length or raw[4:8] != b"ftyp":
            raise AsrError(400, "音频文件格式或长度无效")
        job_id = hmac.new(self.config.service_token.encode(), raw, hashlib.sha256).hexdigest()[:32]
        with self.locked():
            self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
            try:
                old = self.load(job_id)
                if old["status"] in ("PENDING", "RUNNING", "SUCCEEDED"):
                    return self.json(reply, 200, dict(jobId=job_id, status=old["status"]))
            except AsrError:
                pass
            active = 0
            for path in self.root.glob("*.json"):
                value = json.loads(path.read_text(encoding="utf-8"))
                if value["expires"] > time.time() and value["status"] in ("PENDING", "RUNNING"):
                    active += 1
            if active >= 2:
                raise AsrError(429, "语音任务繁忙，请稍后继续")
            expires = int(time.time()) + 3600
            audio = self.root / (job_id + ".m4a")
            audio.write_bytes(raw)
            try:
                self.validator(audio)
            except Exception:
                audio.unlink(missing_ok=True)
                raise
            url = f"{self.public}/asr/media/{job_id}?sig={self.signature(job_id, expires)}"
            job = dict(status="PENDING", expires=expires, model=self.model)
            # Publish metadata first so the vendor may immediately fetch the signed URL.
            self.write(job_id, job)
            try:
                result = self.vendor("/services/audio/asr/transcription", {
                    "model": self.model, "input": {"file_urls": [url]},
                    "parameters": {"channel_id": [0], "language_hints": ["en"], "timestamp_alignment_enabled": True}})
                upstream = result["output"]["task_id"]
                if not isinstance(upstream, str) or not re.fullmatch(r"[A-Za-z0-9-]{1,100}", upstream):
                    raise AsrError(502, "语音识别任务标识无效")
                job["upstream"] = upstream
                self.write(job_id, job)
            except Exception:
                audio.unlink(missing_ok=True)
                job["status"] = "FAILED"
                self.write(job_id, job)
                raise
        return self.json(reply, 202, dict(jobId=job_id, status="PENDING"))

    def poll(self, job_id, job):
        if "upstream" not in job:
            raise AsrError(503, "语音任务正在初始化，请稍后继续")
        output = self.vendor("/tasks/" + job["upstream"])["output"]
        status = output.get("task_status")
        if status in ("PENDING", "RUNNING"):
            job["status"] = status
        elif status == "SUCCEEDED":
            results = output.get("results", [])
            if len(results) != 1 or results[0].get("subtask_status") != "SUCCEEDED":
                job.update(status="FAILED", error="音频识别失败，请检查音轨格式或服务模型")
            else:
                url = results[0].get("transcription_url", "")
                parsed = urlparse(url)
                if parsed.scheme != "https" or not (parsed.hostname or "").endswith(".aliyuncs.com") or parsed.username or parsed.password:
                    raise AsrError(502, "语音识别结果地址无效")
                try:
                    job.update(status="SUCCEEDED", segments=segments(self.transport(url)))
                except AsrError as error:
                    job.update(status="FAILED", error=error.message)
        else:
            job.update(status="FAILED", error="语音识别任务失败或已取消，请重新开始")
        if job["status"] in ("SUCCEEDED", "FAILED"):
            (self.root / (job_id + ".m4a")).unlink(missing_ok=True)
        self.write(job_id, job)

    @staticmethod
    def json(reply, status, body):
        from gateway import Gateway
        return Gateway._reply(reply, status, body)


def cleanup(root=None):
    root = Path(root or os.getenv("ASR_STATE_DIR", "/var/lib/bilingualreader/asr"))
    for path in root.glob("*"):
        if path.suffix in (".m4a", ".json", ".tmp") and path.is_file() and time.time() - path.stat().st_mtime > 3700:
            path.unlink(missing_ok=True)


if __name__ == "__main__":
    cleanup()
