"""Small authenticated Qwen-MT gateway; vendor credentials never reach Android."""
import hmac
import json
import os
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from urllib.parse import urlparse


class ServiceError(Exception):
    def __init__(self, status, message, retry_after=None):
        self.status, self.message, self.retry_after = status, message, retry_after


@dataclass(frozen=True)
class Config:
    api_key: str
    service_token: str
    base_url: str
    model: str = "qwen-mt-plus"
    domains: str = ("Translate English fiction into natural Simplified Chinese. Preserve the "
                    "meaning, narrative voice, dialogue tone and proper names. Do not omit, "
                    "summarize, add commentary or invent details.")
    terms: tuple = ()

    @classmethod
    def from_env(cls):
        terms = json.loads(os.getenv("TRANSLATION_TERMS_JSON", "[]"))
        base = os.getenv("QWEN_BASE_URL", "").rstrip("/")
        parsed = urlparse(base)
        key, token = os.getenv("DASHSCOPE_API_KEY", ""), os.getenv("SERVICE_TOKEN", "")
        if not key or len(token) < 24 or parsed.scheme != "https" or not parsed.hostname:
            raise ValueError("Configure vendor key, HTTPS base URL and service token (24+ characters)")
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("Invalid upstream URL")
        model = os.getenv("QWEN_MODEL", "qwen-mt-plus")
        if not model.startswith("qwen-mt-"):
            raise ValueError("Use an enabled Qwen-MT translation model")
        if not isinstance(terms, list) or len(terms) > 100 or any(
            not isinstance(t, dict) or set(t) != {"source", "target"}
            or any(not isinstance(v, str) or not v or len(v) > 128 for v in t.values())
            for t in terms
        ):
            raise ValueError("Invalid glossary")
        return cls(key, token, base, model, os.getenv("TRANSLATION_DOMAINS", cls.domains), tuple(terms))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward vendor credentials to a redirect target.


def call_qwen(config, payload):
    request = urllib.request.Request(
        config.base_url + "/chat/completions", data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + config.api_key},
        method="POST",
    )
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=24) as response:
            raw = response.read(65537)
        if len(raw) > 65536:
            raise ServiceError(502, "翻译服务返回内容过大")
        result = json.loads(raw)
        choice = result["choices"][0]
        text = choice["message"]["content"]
        if choice.get("finish_reason") == "length":
            raise ServiceError(502, "翻译结果被截断，请缩小文本范围")
        if not isinstance(text, str) or not text.strip():
            raise ServiceError(502, "翻译服务未返回有效译文")
        return text
    except urllib.error.HTTPError as error:
        if error.code == 429:
            raise ServiceError(429, "翻译服务限流，请稍后重试", 30) from None
        # Do not expose upstream bodies, keys, URLs or raw exceptions.
        if error.code in (401, 403):
            raise ServiceError(502, "服务器的百炼凭据或模型权限不可用，请联系维护者") from None
        raise ServiceError(502, "上游翻译服务请求失败") from None
    except (TimeoutError, urllib.error.URLError):
        raise ServiceError(503, "上游翻译服务暂时无法连接", 10) from None
    except (ValueError, KeyError, IndexError, TypeError):
        raise ServiceError(502, "翻译服务响应格式不正确") from None


class Gateway:
    def __init__(self, config, transport=call_qwen):
        self.config, self.transport = config, transport
        self.slots = threading.BoundedSemaphore(2)
        self.rate_lock = threading.Lock()
        self.calls = []

    def _translate(self, value):
        if not isinstance(value, dict) or set(value) - {"q", "source", "target", "format"}:
            raise ServiceError(400, "翻译请求格式不正确")
        text = value.get("q")
        if not isinstance(text, str) or not text.strip() or len(text) > 4000:
            raise ServiceError(400, "单次原文应为 1–4000 个字符")
        if value.get("source") != "en" or value.get("target") not in ("zh", "zh-CN", "zh-Hans"):
            raise ServiceError(400, "当前仅支持英语翻译为简体中文")
        if value.get("format", "text") != "text":
            raise ServiceError(400, "当前仅支持纯文本")
        now = time.monotonic()
        with self.rate_lock:
            self.calls = [t for t in self.calls if now - t < 60]
            if len(self.calls) >= 120:
                raise ServiceError(429, "请求过于频繁，请稍后重试", 30)
            self.calls.append(now)
        if not self.slots.acquire(blocking=False):
            raise ServiceError(429, "翻译服务正在处理其他请求", 3)
        try:
            options = {"source_lang": "English", "target_lang": "Chinese",
                       "domains": self.config.domains}
            if self.config.terms:
                options["terms"] = list(self.config.terms)
            # Qwen-MT only supports one user message; no system role or temperature override.
            payload = {"model": self.config.model, "messages": [{"role": "user", "content": text}],
                       "translation_options": options, "stream": False}
            translated = self.transport(self.config, payload)
            if not isinstance(translated, str) or not translated.strip():
                raise ServiceError(502, "翻译服务未返回有效译文")
            return {"translatedText": translated, "provider": "qwen-mt", "engineVersion": self.config.model}
        finally:
            self.slots.release()

    def __call__(self, environ, start_response):
        try:
            path, method = environ.get("PATH_INFO"), environ.get("REQUEST_METHOD")
            if path == "/health" and method == "GET":
                return self._reply(start_response, 200, {"status": "ok", "model": self.config.model})
            if path != "/translate":
                raise ServiceError(404, "接口不存在")
            if method != "POST":
                raise ServiceError(405, "请使用 POST 请求")
            supplied = environ.get("HTTP_AUTHORIZATION", "")
            if not hmac.compare_digest(supplied.encode(), ("Bearer " + self.config.service_token).encode()):
                raise ServiceError(401, "翻译服务访问令牌无效")
            if environ.get("CONTENT_TYPE", "").split(";")[0].strip().lower() != "application/json":
                raise ServiceError(415, "请使用 JSON 请求")
            try:
                length = int(environ.get("CONTENT_LENGTH", "0"))
            except ValueError:
                raise ServiceError(400, "无效请求长度") from None
            if length < 1 or length > 65536:
                raise ServiceError(413, "翻译请求大小不符合限制")
            try:
                value = json.loads(environ["wsgi.input"].read(length))
            except (ValueError, UnicodeError):
                raise ServiceError(400, "无效 JSON 请求") from None
            return self._reply(start_response, 200, self._translate(value))
        except ServiceError as error:
            return self._reply(start_response, error.status, {"error": error.message}, error.retry_after)
        except Exception:
            return self._reply(start_response, 500, {"error": "翻译网关内部错误"})

    @staticmethod
    def _reply(start, status, value, retry=None):
        from http import HTTPStatus
        raw = json.dumps(value, ensure_ascii=False).encode("utf-8")
        headers = [("Content-Type", "application/json; charset=utf-8"),
                   ("Content-Length", str(len(raw))), ("Cache-Control", "no-store")]
        if retry is not None:
            headers.append(("Retry-After", str(retry)))
        start(f"{status} {HTTPStatus(status).phrase}", headers)
        return [raw]


_gateway = None


def application(environ, start_response):
    global _gateway
    if _gateway is None:
        try:
            _gateway = Gateway(Config.from_env())
        except (ValueError, TypeError):
            return Gateway._reply(start_response, 503, {"error": "服务器翻译服务尚未配置"})
    return _gateway(environ, start_response)
