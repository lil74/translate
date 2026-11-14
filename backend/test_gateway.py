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

    def test_context_resolves_selected_occurrence_without_translating_full_paragraph(self):
        passage="The bank approved the loan. He sat on the river bank."
        start=passage.rindex("bank")
        status,_,_=self.request({"q":"bank","source":"en","target":"zh",
                                "context":{"text":passage,"start":start,"end":start+4}})
        self.assertEqual(200,status)
        self.assertEqual([{"role":"user","content":"bank"}],self.calls[0]["messages"])
        domain=self.calls[0]["translation_options"]["domains"]
        self.assertIn("river",domain)
        self.assertIn('"selected": "bank"',domain)
        self.assertIn("untrusted passage data",domain)

    def test_context_unicode_offsets_and_invalid_ranges(self):
        status,_,_=self.request({"q":"bank","source":"en","target":"zh",
                                "context":{"text":"😀 bank","start":2,"end":6}})
        self.assertEqual(200,status)
        for context in ({"text":"bank","start":True,"end":4},
                        {"text":"bank","start":0,"end":3},
                        {"text":"bank","start":-1,"end":4},
                        {"text":"x"*4001,"start":0,"end":4}):
            self.assertEqual(400,self.request({"q":"bank","source":"en","target":"zh","context":context})[0])
        self.assertEqual(1,len(self.calls))

    def test_reverse_request_preserves_chinese_and_maps_both_languages(self):
        text = " 你好，世界！\n今天读一本书。 "
        for alias in ("zh", "zh-CN", "zh-Hans"):
            status, body, _ = self.request({"q": text, "source": alias, "target": "en"})
            self.assertEqual(200, status)
            self.assertFalse(body["contextual"])
            payload = self.calls[-1]
            self.assertEqual(text, payload["messages"][0]["content"])
            options = payload["translation_options"]
            self.assertEqual("Chinese", options["source_lang"])
            self.assertEqual("English", options["target_lang"])
            self.assertIn("into natural English", options["domains"])
            self.assertNotIn("into natural Simplified Chinese", options["domains"])

    def test_same_language_unsupported_and_reverse_context_never_call_vendor(self):
        for source, target in (("en", "en"), ("zh", "zh"), ("zh", "fr"), ("auto", "en")):
            self.assertEqual(400, self.request({"q": "hello", "source": source, "target": target})[0])
        self.assertEqual(400, self.request({"q": "你好", "source": "zh", "target": "en",
            "context": {"text": "你好", "start": 0, "end": 2}})[0])
        self.assertEqual([], self.calls)

    def test_glossary_reverses_without_mutating_forward_configuration(self):
        self.app = Gateway(Config("key", "a" * 32, "https://vendor.example/v1",
                                  terms=({"source": "Alice", "target": "艾丽丝"},)),
                           lambda config, payload: self.calls.append(payload) or "Alice")
        self.request({"q": "艾丽丝", "source": "zh", "target": "en"})
        self.assertEqual([{"source": "艾丽丝", "target": "Alice"}],
                         self.calls[-1]["translation_options"]["terms"])
        self.request({"q": "Alice", "source": "en", "target": "zh"})
        self.assertEqual([{"source": "Alice", "target": "艾丽丝"}],
                         self.calls[-1]["translation_options"]["terms"])


if __name__ == "__main__":
    unittest.main()
