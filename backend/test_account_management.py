import io
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from accounts import Accounts


class AccountManagementTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        root = Path(self.folder.name)
        self.db_path = str(root / "accounts.sqlite")
        self.books = str(root / "books")
        self.api = Accounts(self.db_path, self.books)

    def tearDown(self):
        self.folder.cleanup()

    def call(self, path, method="POST", data=None, token=""):
        raw = json.dumps(data).encode() if data is not None else b""
        env = {"PATH_INFO": path, "REQUEST_METHOD": method, "CONTENT_TYPE": "application/json",
               "CONTENT_LENGTH": str(len(raw)), "wsgi.input": io.BytesIO(raw),
               "HTTP_AUTHORIZATION": "Bearer " + token, "REMOTE_ADDR": "127.0.0.1"}
        status = []
        result = b"".join(self.api(env, lambda line, headers: status.append(line)))
        return int(status[0][:3]), json.loads(result)

    def test_recovery_code_and_password_change(self):
        user = {"username": "reader_1", "password": "long-password-123"}
        registered = self.call("/account/register", data=user)[1]
        token, code = registered["token"], registered["recoveryCode"]
        self.assertEqual(401, self.call("/account/recover", data={"username": user["username"], "recoveryCode": "0" * 40, "newPassword": "changed-password-1"})[0])
        self.assertEqual(200, self.call("/account/recover", data={"username": user["username"], "recoveryCode": code, "newPassword": "changed-password-1"})[0])
        self.assertEqual(401, self.call("/account/me", "GET", token=token)[0])
        self.assertEqual(401, self.call("/account/recover", data={"username": user["username"], "recoveryCode": code, "newPassword": "another-password-1"})[0])
        token = self.call("/account/login", data={"username": user["username"], "password": "changed-password-1"})[1]["token"]
        self.assertEqual(401, self.call("/account/password", data={"password": "wrong-password", "newPassword": "another-password-1"}, token=token)[0])
        next_code = self.call("/account/recovery-code", data={"password": "changed-password-1"}, token=token)[1]["recoveryCode"]
        self.assertNotEqual(code, next_code)
        self.assertEqual(200, self.call("/account/password", data={"password": "changed-password-1", "newPassword": "another-password-1"}, token=token)[0])
        self.assertEqual(401, self.call("/account/me", "GET", token=token)[0])
        self.assertEqual(200, self.call("/account/login", data={"username": user["username"], "password": "another-password-1"})[0])

    def test_export_and_account_delete(self):
        user = {"username": "reader_2", "password": "long-password-123"}
        token = self.call("/account/register", data=user)[1]["token"]
        self.assertEqual(201, self.call("/expressions", data={"text": "take off"}, token=token)[0])
        exported = self.call("/account/export", "GET", token=token)[1]
        self.assertEqual("reader_2", exported["username"])
        self.assertEqual("take off", exported["expressions"][0]["text"])
        with sqlite3.connect(self.db_path) as db:
            user_id = db.execute("SELECT id FROM users WHERE username='reader_2'").fetchone()[0]
        folder = Path(self.books) / str(user_id)
        folder.mkdir(parents=True)
        (folder / "example.txt").write_text("sample")
        self.assertEqual(401, self.call("/account/me", "DELETE", {"password": "wrong-password"}, token)[0])
        self.assertEqual(200, self.call("/account/me", "DELETE", {"password": user["password"]}, token)[0])
        self.assertFalse(folder.exists())
        self.assertEqual(401, self.call("/account/me", "GET", token=token)[0])

    def test_existing_database_migrates_and_can_generate_recovery_code(self):
        with sqlite3.connect(self.db_path) as db:
            db.execute("ALTER TABLE users DROP COLUMN recovery_hash")
        self.api = Accounts(self.db_path, self.books)
        user = {"username": "reader_3", "password": "long-password-123"}
        token = self.call("/account/register", data=user)[1]["token"]
        code = self.call("/account/recovery-code", data={"password": user["password"]}, token=token)[1]["recoveryCode"]
        self.assertEqual(40, len(code))


if __name__ == "__main__":
    unittest.main()
