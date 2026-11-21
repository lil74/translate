import io
import json
import tempfile
import unittest
from pathlib import Path

from accounts import Accounts


class ExpressionsTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.api = Accounts(str(Path(self.folder.name) / "accounts.sqlite"))

    def tearDown(self):
        self.folder.cleanup()

    def call(self, path, method="GET", value=None, token=""):
        body = json.dumps(value).encode() if value is not None else b""
        env = {"PATH_INFO": path, "REQUEST_METHOD": method,
               "CONTENT_TYPE": "application/json", "CONTENT_LENGTH": str(len(body)),
               "wsgi.input": io.BytesIO(body), "HTTP_AUTHORIZATION": "Bearer " + token,
               "REMOTE_ADDR": "127.0.0.1"}
        status = []
        raw = b"".join(self.api(env, lambda line, headers: status.append(line)))
        return int(status[0][:3]), json.loads(raw)

    def register(self, username):
        return self.call("/account/register", "POST", {"username": username, "password": "long-password-123"})[1]["token"]

    def test_cloud_collections_are_account_scoped(self):
        alice = self.register("alice_reader")
        bob = self.register("bob_reader")
        self.assertEqual(401, self.call("/expressions")[0])
        status, saved = self.call("/expressions", "POST", {"text": "take off"}, alice)
        self.assertEqual(201, status)
        expression_id = saved["id"]
        self.assertEqual(200, self.call("/expressions", "POST", {"text": "take off"}, alice)[0])
        self.assertEqual(["take off"], [r["text"] for r in self.call("/expressions", token=alice)[1]["expressions"]])
        second_session = self.call("/account/login", "POST", {"username": "alice_reader", "password": "long-password-123"})[1]["token"]
        self.assertEqual(["take off"], [r["text"] for r in self.call("/expressions", token=second_session)[1]["expressions"]])
        self.assertEqual([], self.call("/expressions", token=bob)[1]["expressions"])
        self.assertEqual(404, self.call("/expressions/" + expression_id, "DELETE", token=bob)[0])
        self.assertEqual(200, self.call("/expressions/" + expression_id, "DELETE", token=alice)[0])
        self.assertEqual([], self.call("/expressions", token=alice)[1]["expressions"])

    def test_rejects_invalid_text(self):
        token = self.register("test_reader")
        self.assertEqual(400, self.call("/expressions", "POST", {"text": " "}, token)[0])
        self.assertEqual(400, self.call("/expressions", "POST", {"text": "a" * 401}, token)[0])
        self.assertEqual(400, self.call("/expressions", "POST", {"text": None}, token)[0])


if __name__ == "__main__":
    unittest.main()
