import io
import json
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

from accounts import Accounts


class AccountTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.api = Accounts(str(Path(self.folder.name) / "accounts.sqlite"))

    def tearDown(self):
        self.folder.cleanup()

    def call(self, path, value=None, token="", ip="127.0.0.1"):
        raw = json.dumps(value).encode() if value is not None else b""
        env = {"PATH_INFO": path, "REQUEST_METHOD": "GET" if path.endswith("/me") else "POST",
               "CONTENT_TYPE": "application/json", "CONTENT_LENGTH": str(len(raw)),
               "wsgi.input": io.BytesIO(raw), "HTTP_AUTHORIZATION": "Bearer " + token,
               "REMOTE_ADDR": ip}
        status = []
        result = self.api(env, lambda line, headers: status.append(line))
        return int(status[0][:3]), json.loads(b"".join(result))

    def test_register_login_me_logout_and_restart(self):
        user = {"username": "reader_1", "password": "long-password-123"}
        status, registered = self.call("/account/register", user)
        self.assertEqual(201, status)
        self.assertEqual("reader_1", registered["username"])
        self.assertEqual(409, self.call("/account/register", user)[0])
        self.api = Accounts(str(Path(self.folder.name) / "accounts.sqlite"))
        self.assertEqual(401, self.call("/account/login", {**user, "password": "wrong-password"})[0])
        status, logged = self.call("/account/login", user)
        self.assertEqual(200, status)
        token = logged["token"]
        self.assertEqual("reader_1", self.call("/account/me", token=token)[1]["username"])
        self.assertEqual(200, self.call("/account/logout", token=token)[0])
        self.assertEqual(401, self.call("/account/me", token=token)[0])

    def test_invalid_input_and_rate_limit(self):
        self.assertEqual(400, self.call("/account/register", {"username": "x", "password": "short"})[0])
        user = {"username": "reader_2", "password": "long-password-123"}
        for _ in range(20):
            self.assertEqual(401, self.call("/account/login", user)[0])
        self.assertEqual(429, self.call("/account/register", user)[0])
        self.assertEqual(201, self.call("/account/register", user, ip="other-client")[0])

    def test_gateway_routes_accounts_without_translation_credentials(self):
        import accounts
        import gateway
        previous = accounts._accounts
        try:
            accounts._accounts = self.api
            with patch.object(gateway, "_gateway", None), patch.dict("os.environ", {"DASHSCOPE_API_KEY": "", "SERVICE_TOKEN": ""}):
                raw = b'{"username":"reader_3","password":"long-password-123"}'
                env = {"PATH_INFO": "/account/register", "REQUEST_METHOD": "POST",
                       "CONTENT_TYPE": "application/json", "CONTENT_LENGTH": str(len(raw)),
                       "wsgi.input": io.BytesIO(raw), "REMOTE_ADDR": "other-client"}
                status = []
                result = gateway.application(env, lambda line, headers: status.append(line))
                self.assertEqual(201, int(status[0][:3]))
                self.assertIn(b"token", b"".join(result))
        finally:
            accounts._accounts = previous


if __name__ == "__main__":
    unittest.main()
