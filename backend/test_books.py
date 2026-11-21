import io
import json
import tempfile
import unittest
from pathlib import Path
from urllib.parse import quote

from accounts import Accounts, MAX_BOOK


class CloudBooksTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        root = Path(self.folder.name)
        self.api = Accounts(str(root / "accounts.sqlite"), str(root / "books"))

    def tearDown(self):
        self.folder.cleanup()

    def call(self, path, method="GET", body=b"", token="", name="", length=None):
        env = {"PATH_INFO": path, "REQUEST_METHOD": method,
               "CONTENT_TYPE": "application/octet-stream",
               "CONTENT_LENGTH": str(len(body) if length is None else length),
               "HTTP_AUTHORIZATION": "Bearer " + token,
               "HTTP_X_BOOK_NAME": quote(name), "wsgi.input": io.BytesIO(body),
               "REMOTE_ADDR": "127.0.0.1"}
        if path.startswith("/account/") and body:
            env["CONTENT_TYPE"] = "application/json"
        status = []
        result = self.api(env, lambda line, headers: status.append(line))
        return int(status[0][:3]), b"".join(result)

    def register(self, name):
        payload = json.dumps({"username": name, "password": "long-password-123"}).encode()
        status, body = self.call("/account/register", "POST", payload)
        self.assertEqual(201, status)
        return json.loads(body)["token"]

    def test_upload_list_restore_delete_and_user_isolation(self):
        alice = self.register("alice_reader")
        bob = self.register("bob_reader")
        content = b"Chapter One\nHello, world.\n"
        self.assertEqual(401, self.call("/books")[0])
        status, body = self.call("/books", "POST", content, alice, "Novel.txt")
        self.assertEqual(201, status)
        book_id = json.loads(body)["id"]
        self.assertEqual(200, self.call("/books", "POST", content, alice, "Novel.txt")[0])
        listed = json.loads(self.call("/books", token=alice)[1])["books"]
        self.assertEqual([(book_id, "Novel.txt")], [(b["id"], b["name"]) for b in listed])
        self.assertEqual([], json.loads(self.call("/books", token=bob)[1])["books"])
        self.assertEqual((200, content), self.call("/books/" + book_id, token=alice))
        self.assertEqual(404, self.call("/books/" + book_id, token=bob)[0])
        self.assertEqual(404, self.call("/books/" + book_id, "DELETE", token=bob)[0])
        self.assertEqual(200, self.call("/books/" + book_id, "DELETE", token=alice)[0])
        self.assertEqual(404, self.call("/books/" + book_id, token=alice)[0])

    def test_rejects_bad_name_length_and_short_upload(self):
        token = self.register("test_reader")
        self.assertEqual(400, self.call("/books", "POST", b"abc", token, "../bad.txt")[0])
        self.assertEqual(400, self.call("/books", "POST", b"abc", token, "book.pdf")[0])
        self.assertEqual(413, self.call("/books", "POST", b"abc", token, "book.txt", MAX_BOOK + 1)[0])
        self.assertEqual(400, self.call("/books", "POST", b"abc", token, "book.txt", 5)[0])
        self.assertEqual([], json.loads(self.call("/books", token=token)[1])["books"])


if __name__ == "__main__":
    unittest.main()
