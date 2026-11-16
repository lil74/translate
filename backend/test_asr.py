import io
import json
import tempfile
import unittest
import os
import time
from urllib.parse import urlparse
from pathlib import Path
from gateway import Config
from asr import AsrGateway, AsrError, segments, cleanup


class AsrTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.calls = []
        self.status = "RUNNING"
        self.config = Config("private-vendor-key", "a" * 32, "https://vendor.example/compatible-mode/v1")
        self.app = AsrGateway(self.config, self.transport, self.folder.name, "https://reader.example", lambda path: None)

    def tearDown(self):
        self.folder.cleanup()

    def transport(self, url, headers=None, payload=None):
        self.calls.append((url, headers, payload))
        if payload:
            return {"output": {"task_id": "upstream-123", "task_status": "PENDING"}}
        if "/tasks/" in url:
            return {"output": {"task_status": self.status, "results": [{"subtask_status": "SUCCEEDED",
                "transcription_url": "https://result.oss-cn-beijing.aliyuncs.com/example"}]}}
        return {"transcripts": [{"channel_id": 0, "sentences": [
            {"begin_time": 120, "end_time": 1400, "text": "Hello world."}]}]}

    def request(self, path="/asr/jobs", method="POST", raw=b"\x00\x00\x00\x18ftypM4A audio-test", token="a" * 32, query=""):
        captured = []
        env = dict(PATH_INFO=path, REQUEST_METHOD=method, QUERY_STRING=query,
            HTTP_AUTHORIZATION="Bearer " + token, CONTENT_TYPE="audio/mp4", CONTENT_LENGTH=str(len(raw)),
            **{"wsgi.input": io.BytesIO(raw)})
        body = b"".join(self.app.handle(env, lambda status, headers: captured.append((status, dict(headers)))))
        return int(captured[0][0][:3]), body, captured[0][1]

    def job(self):
        status, body, _ = self.request()
        self.assertEqual(202, status)
        return json.loads(body)["jobId"]

    def test_auth_before_upload_or_paid_call(self):
        self.assertEqual(401, self.request(token="wrong")[0])
        self.assertEqual([], self.calls)
        self.assertEqual([], list(Path(self.folder.name).iterdir()))

    def test_upload_is_idempotent_and_only_submits_english_timestamped_audio(self):
        job = self.job()
        status, body, _ = self.request()
        self.assertEqual(200, status)
        self.assertEqual(job, json.loads(body)["jobId"])
        self.assertEqual(1, len(self.calls))
        payload = self.calls[0][2]
        self.assertEqual(["en"], payload["parameters"]["language_hints"])
        self.assertTrue(payload["parameters"]["timestamp_alignment_enabled"])
        self.assertEqual("enable", self.calls[0][1]["X-DashScope-Async"])
        self.assertNotIn("private-vendor-key", json.dumps(payload))

    def test_audio_requires_signed_expiring_url(self):
        job = self.job()
        url = urlparse(self.calls[0][2]["input"]["file_urls"][0])
        self.assertEqual(403, self.request(url.path, "GET", query="sig=bad")[0])
        status, body, headers = self.request(url.path, "GET", token="", query=url.query)
        self.assertEqual(200, status)
        self.assertEqual("audio/mp4", headers["Content-Type"])
        self.assertIn(b"ftyp", body)

    def test_success_retains_real_timing_and_deletes_uploaded_audio(self):
        job = self.job()
        self.status = "SUCCEEDED"
        status, body, _ = self.request("/asr/jobs/" + job, "GET")
        result = json.loads(body)
        self.assertEqual(200, status)
        self.assertEqual("SUCCEEDED", result["status"])
        self.assertEqual(dict(startMs=120, endMs=1400, text="Hello world."), result["segments"][0])
        self.assertFalse((Path(self.folder.name) / (job + ".m4a")).exists())
        self.assertIsNone(self.calls[-1][1])  # Never send vendor credentials to result URLs.
        calls = len(self.calls)
        self.request("/asr/jobs/" + job, "GET")
        self.assertEqual(calls, len(self.calls))

    def test_job_survives_gateway_restart(self):
        job = self.job()
        self.app = AsrGateway(self.config, self.transport, self.folder.name, "https://reader.example", lambda path: None)
        status, body, _ = self.request("/asr/jobs/" + job, "GET")
        self.assertEqual(200, status)
        self.assertEqual("RUNNING", json.loads(body)["status"])

    def test_cancel_removes_audio_and_does_not_poll_again(self):
        job = self.job()
        self.assertEqual(200, self.request("/asr/jobs/" + job, "DELETE")[0])
        self.assertFalse((Path(self.folder.name) / (job + ".m4a")).exists())
        self.request("/asr/jobs/" + job, "GET")
        self.assertEqual(1, len(self.calls))

    def test_empty_or_invalid_timed_results_rejected(self):
        for sentence in (None, {"begin_time": -1, "end_time": 100, "text": "hello"},
                         {"begin_time": 100, "end_time": 90, "text": "hello"},
                         {"begin_time": True, "end_time": 100, "text": "hello"}):
            value = {"transcripts": [{"sentences": [] if sentence is None else [sentence]}]}
            with self.assertRaises(AsrError):
                segments(value)

    def test_bad_audio_and_path_never_call_vendor(self):
        self.assertEqual(400, self.request(raw=b"not-a-valid-audio-file")[0])
        self.assertEqual(404, self.request("/asr/jobs/../../etc/passwd", "GET")[0])
        self.assertEqual([], self.calls)

    def test_duration_validator_rejects_before_paid_submission(self):
        def reject(path):
            raise AsrError(400, "duration invalid")
        self.app.validator = reject
        self.assertEqual(400, self.request()[0])
        self.assertEqual([], self.calls)
        self.assertEqual([], list(Path(self.folder.name).glob("*.m4a")))

    def test_expired_signed_audio_unavailable_and_cleanup_removes_files(self):
        job = self.job()
        saved = self.app.load(job)
        saved["expires"] = int(time.time()) - 1
        self.app.write(job, saved)
        self.assertEqual(410, self.request("/asr/media/" + job, "GET", query="sig=wrong")[0])
        for path in Path(self.folder.name).glob("*"):
            os.utime(path, (time.time()-4000, time.time()-4000))
        cleanup(self.folder.name)
        self.assertEqual([], list(Path(self.folder.name).glob("*.m4a")))
        self.assertEqual([], list(Path(self.folder.name).glob("*.json")))
        self.assertTrue((Path(self.folder.name) / ".lock").exists())


if __name__ == "__main__":
    unittest.main()
