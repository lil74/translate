import io
import json
import os
import unittest
from unittest.mock import patch
from gateway import Config, Gateway, ServiceError


class GatewayTest(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.config = Config("vendor-secret", "a" * 32, "https://vendor.example/v1")
        def translate(config, payload):
            self.calls.append(payload)
            return "我喜欢阅读。"
        self.app = Gateway(self.config, translate)

    def request(self, value=None, token="a" * 32, path="/translate", raw=None):
        if raw is None:
            raw = json.dumps(value or {"q": "I enjoy reading.", "source": "en", "target": "zh"}).encode()
        captured = []
        env = {"PATH_INFO": path, "REQUEST_METHOD": "GET" if path == "/health" else "POST",
               "HTTP_AUTHORIZATION": "Bearer " + token, "CONTENT_TYPE": "application/json",
               "CONTENT_LENGTH": str(len(raw)), "wsgi.input": io.BytesIO(raw)}
        body = b"".join(self.app(env, lambda status, headers: captured.append((status, dict(headers)))))
        return int(captured[0][0][:3]), json.loads(body), captured[0][1]

    def test_real_provider_request_preserves_input_and_uses_translation_options(self):
        text = "  Hello, Alice!\nDon't leave.  "
        status, body, _ = self.request({"q": text, "source": "en", "target": "zh-CN"})
        self.assertEqual(200, status)
        self.assertEqual("qwen-mt", body["provider"])
        self.assertEqual("qwen-mt-plus", body["engineVersion"])
        self.assertEqual([{"role": "user", "content": text}], self.calls[0]["messages"])
        self.assertEqual("Chinese", self.calls[0]["translation_options"]["target_lang"])

    def test_unauthorized_never_calls_vendor(self):
        self.assertEqual(401, self.request(token="wrong")[0])
        self.assertEqual([], self.calls)

    def test_limits_and_languages_rejected_without_paid_calls(self):
        for value in ({"q": "x" * 4001, "source": "en", "target": "zh"},
                      {"q": " ", "source": "en", "target": "zh"},
                      {"q": "Hi", "source": "fr", "target": "zh"},
                      {"q": "Hi", "source": "en", "target": "zh", "api_key": "injected"}):
            self.assertEqual(400, self.request(value)[0])
        self.assertEqual([], self.calls)

    def test_errors_do_not_leak_credentials_or_book_text(self):
        def broken(config, payload):
            raise RuntimeError("vendor-secret and private book")
        self.app = Gateway(self.config, broken)
        status, body, _ = self.request()
        self.assertEqual(500, status)
        self.assertNotIn("secret", json.dumps(body))
        self.assertNotIn("private", json.dumps(body))

    def test_upstream_limits_have_retry_after(self):
        def limited(config, payload):
            raise ServiceError(429, "限流", 30)
        self.app = Gateway(self.config, limited)
        status, _, headers = self.request()
        self.assertEqual(429, status)
        self.assertEqual("30", headers["Retry-After"])

    def test_missing_credentials_fail_configuration(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(ValueError):
                Config.from_env()

    def test_health_never_calls_vendor(self):
        self.assertEqual(200, self.request(path="/health", token="")[0])
        self.assertEqual([], self.calls)

    def test_invalid_json_does_not_call_vendor(self):
        self.assertEqual(400, self.request(raw=b"{")[0])
        self.assertEqual([], self.calls)


if __name__ == "__main__":
    unittest.main()
