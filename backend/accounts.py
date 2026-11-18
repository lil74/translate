"""Small SQLite-backed account API, independent from the translation service token."""
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import tempfile
import time
from contextlib import closing
from http import HTTPStatus
from urllib.parse import unquote


MAX_BODY = 4096
SESSION_SECONDS = 30 * 24 * 3600
USERNAME = re.compile(r"^[A-Za-z0-9_]{3,32}$")
MAX_BOOK = 50 * 1024 * 1024
MAX_USER_BOOKS = 100
MAX_USER_BYTES = 500 * 1024 * 1024
MAX_EXPRESSIONS = 500


def reply(start, status, value):
    body = json.dumps(value, ensure_ascii=False).encode("utf-8")
    start(f"{status} {HTTPStatus(status).phrase}", [
        ("Content-Type", "application/json; charset=utf-8"),
        ("Content-Length", str(len(body))),
        ("Cache-Control", "no-store"),
    ])
    return [body]


class Accounts:
    def __init__(self, path, book_dir=None):
        self.path = path
        self.book_dir = book_dir or os.getenv("BOOK_STORAGE_PATH", "/var/lib/bilingualreader/books")
        with closing(self._connect()) as db, db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY, username TEXT NOT NULL UNIQUE,
                    salt BLOB NOT NULL, password_hash BLOB NOT NULL, created_at INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS sessions (
                    token_hash BLOB PRIMARY KEY, user_id INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE);
                CREATE TABLE IF NOT EXISTS attempts (
                    id INTEGER PRIMARY KEY, client TEXT NOT NULL, at INTEGER NOT NULL);
                CREATE INDEX IF NOT EXISTS attempts_client_at ON attempts(client,at);
                CREATE TABLE IF NOT EXISTS books (
                    user_id INTEGER NOT NULL, book_id TEXT NOT NULL, name TEXT NOT NULL,
                    size INTEGER NOT NULL, created_at INTEGER NOT NULL,
                    PRIMARY KEY(user_id,book_id),
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE);
                CREATE TABLE IF NOT EXISTS expressions (
                    user_id INTEGER NOT NULL, expression_id TEXT NOT NULL,
                    text TEXT NOT NULL, created_at INTEGER NOT NULL,
                    PRIMARY KEY(user_id,expression_id),
                    FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE);
            """)

    def _connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.execute("PRAGMA foreign_keys=ON")
        return db

    @staticmethod
    def _hash(password, salt):
        return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 310000)

    def _user(self, env):
        header = env.get("HTTP_AUTHORIZATION", "")
        token = header[7:] if header.startswith("Bearer ") else ""
        if not re.fullmatch(r"[0-9a-f]{64}", token):
            return None, None
        digest = hashlib.sha256(token.encode("ascii")).digest()
        with closing(self._connect()) as db:
            row = db.execute("SELECT users.id, users.username FROM sessions JOIN users ON users.id=sessions.user_id WHERE token_hash=? AND expires_at>?", (digest, int(time.time()))).fetchone()
        return row, digest

    def _books(self, env, start):
        user, _ = self._user(env)
        if user is None:
            return reply(start, 401, {"error": "请先登录"})
        path, method = env.get("PATH_INFO", ""), env.get("REQUEST_METHOD", "")
        if path == "/books" and method == "GET":
            with closing(self._connect()) as db:
                rows = db.execute("SELECT book_id,name,size,created_at FROM books WHERE user_id=? ORDER BY created_at DESC,book_id", (user[0],)).fetchall()
            return reply(start, 200, {"books": [{"id": r[0], "name": r[1], "size": r[2], "createdAt": r[3]} for r in rows]})
        if path == "/books" and method == "POST":
            try:
                length = int(env.get("CONTENT_LENGTH", "0"))
            except ValueError:
                return reply(start, 400, {"error": "文件长度无效"})
            if length < 1 or length > MAX_BOOK:
                return reply(start, 413, {"error": "书籍文件不能超过 50 MiB"})
            name = env.get("HTTP_X_BOOK_NAME", "")
            try:
                name = unquote(name, encoding="utf-8", errors="strict")
            except (UnicodeError, ValueError):
                return reply(start, 400, {"error": "文件名编码无效"})
            name = name.strip()
            if (not name or len(name) > 180 or "/" in name or "\\" in name
                    or any(ord(c) < 32 for c in name) or not name.lower().endswith((".epub", ".txt"))):
                return reply(start, 400, {"error": "仅支持 EPUB/TXT 文件名"})
            with closing(self._connect()) as db:
                used = db.execute("SELECT COUNT(*),COALESCE(SUM(size),0) FROM books WHERE user_id=?", (user[0],)).fetchone()
            if used[0] >= MAX_USER_BOOKS or used[1] + length > MAX_USER_BYTES:
                return reply(start, 413, {"error": "云端书库已达到当前试用容量上限"})
            folder = os.path.join(self.book_dir, str(user[0]))
            os.makedirs(folder, mode=0o700, exist_ok=True)
            temporary = None
            try:
                digest = hashlib.sha256()
                with tempfile.NamedTemporaryFile(dir=folder, prefix="upload-", delete=False) as file:
                    temporary = file.name
                    remaining = length
                    while remaining:
                        chunk = env["wsgi.input"].read(min(65536, remaining))
                        if not chunk:
                            return reply(start, 400, {"error": "文件未传输完整"})
                        file.write(chunk)
                        digest.update(chunk)
                        remaining -= len(chunk)
                    file.flush()
                    os.fsync(file.fileno())
                book_id = digest.hexdigest()
                extension = ".epub" if name.lower().endswith(".epub") else ".txt"
                target = os.path.join(folder, book_id + extension)
                with closing(self._connect()) as db, db:
                    existing = db.execute("SELECT name FROM books WHERE user_id=? AND book_id=?", (user[0], book_id)).fetchone()
                    if existing:
                        return reply(start, 200, {"id": book_id, "name": existing[0], "alreadyExists": True})
                    os.replace(temporary, target)
                    temporary = None
                    db.execute("INSERT INTO books(user_id,book_id,name,size,created_at) VALUES(?,?,?,?,?)",
                               (user[0], book_id, name, length, int(time.time())))
                return reply(start, 201, {"id": book_id, "name": name, "alreadyExists": False})
            finally:
                if temporary and os.path.exists(temporary):
                    os.unlink(temporary)
        match = re.fullmatch(r"/books/([0-9a-f]{64})", path)
        if match:
            book_id = match.group(1)
            with closing(self._connect()) as db:
                row = db.execute("SELECT name,size FROM books WHERE user_id=? AND book_id=?", (user[0], book_id)).fetchone()
            if row is None:
                return reply(start, 404, {"error": "云端没有这本书"})
            folder = os.path.join(self.book_dir, str(user[0]))
            extension = ".epub" if row[0].lower().endswith(".epub") else ".txt"
            target = os.path.join(folder, book_id + extension)
            if method == "GET":
                file = open(target, "rb")
                start("200 OK", [("Content-Type", "application/octet-stream"),
                                  ("Content-Length", str(row[1])), ("Cache-Control", "no-store")])
                def chunks():
                    with file:
                        while True:
                            chunk = file.read(65536)
                            if not chunk:
                                break
                            yield chunk
                return chunks()
            if method == "DELETE":
                with closing(self._connect()) as db, db:
                    db.execute("DELETE FROM books WHERE user_id=? AND book_id=?", (user[0], book_id))
                try:
                    os.unlink(target)
                except FileNotFoundError:
                    pass
                return reply(start, 200, {"deleted": True})
        return reply(start, 405 if path == "/books" or match else 404, {"error": "书籍接口不存在"})

    def _expressions(self, env, start):
        user, _ = self._user(env)
        if user is None:
            return reply(start, 401, {"error": "请先登录"})
        path, method = env.get("PATH_INFO", ""), env.get("REQUEST_METHOD", "")
        if path == "/expressions" and method == "GET":
            with closing(self._connect()) as db:
                rows = db.execute("SELECT expression_id,text,created_at FROM expressions WHERE user_id=? ORDER BY created_at DESC,expression_id", (user[0],)).fetchall()
            return reply(start, 200, {"expressions": [{"id": r[0], "text": r[1], "createdAt": r[2]} for r in rows]})
        if path == "/expressions" and method == "POST":
            if env.get("CONTENT_TYPE", "").split(";")[0].lower() != "application/json":
                return reply(start, 415, {"error": "请使用 JSON 请求"})
            try:
                length = int(env.get("CONTENT_LENGTH", "0"))
                if length < 1 or length > MAX_BODY:
                    return reply(start, 413, {"error": "收藏内容过长"})
                value = json.loads(env["wsgi.input"].read(length))
                text = value.get("text")
            except (ValueError, TypeError, AttributeError, UnicodeError, KeyError):
                return reply(start, 400, {"error": "收藏内容无效"})
            if (not isinstance(text, str) or not 1 <= len(text.strip()) <= 400
                    or "\0" in text):
                return reply(start, 400, {"error": "收藏内容需为 1–400 字符"})
            text = text.strip()
            expression_id = hashlib.sha256(text.encode("utf-8")).hexdigest()
            with closing(self._connect()) as db, db:
                existing = db.execute("SELECT 1 FROM expressions WHERE user_id=? AND expression_id=?", (user[0], expression_id)).fetchone()
                if existing:
                    return reply(start, 200, {"id": expression_id, "alreadyExists": True})
                count = db.execute("SELECT COUNT(*) FROM expressions WHERE user_id=?", (user[0],)).fetchone()[0]
                if count >= MAX_EXPRESSIONS:
                    return reply(start, 413, {"error": "账号收藏已达到 500 条上限"})
                db.execute("INSERT INTO expressions(user_id,expression_id,text,created_at) VALUES(?,?,?,?)",
                           (user[0], expression_id, text, int(time.time())))
            return reply(start, 201, {"id": expression_id, "alreadyExists": False})
        match = re.fullmatch(r"/expressions/([0-9a-f]{64})", path)
        if match and method == "DELETE":
            with closing(self._connect()) as db, db:
                deleted = db.execute("DELETE FROM expressions WHERE user_id=? AND expression_id=?", (user[0], match.group(1))).rowcount
            return reply(start, 200 if deleted else 404, {"deleted": bool(deleted)} if deleted else {"error": "收藏不存在"})
        return reply(start, 405 if path == "/expressions" or match else 404, {"error": "收藏接口不存在"})

    def __call__(self, env, start):
        path, method = env.get("PATH_INFO"), env.get("REQUEST_METHOD")
        if path == "/books" or (path or "").startswith("/books/"):
            try:
                return self._books(env, start)
            except (OSError, sqlite3.Error):
                return reply(start, 500, {"error": "云端书库暂时不可用"})
        if path == "/expressions" or (path or "").startswith("/expressions/"):
            try:
                return self._expressions(env, start)
            except (OSError, sqlite3.Error):
                return reply(start, 500, {"error": "云端收藏暂时不可用"})
        if path not in ("/account/register", "/account/login", "/account/me", "/account/logout"):
            return reply(start, 404, {"error": "接口不存在"})
        if method != ("GET" if path.endswith("/me") else "POST"):
            return reply(start, 405, {"error": "请求方法不正确"})
        try:
            if path in ("/account/me", "/account/logout"):
                user, digest = self._user(env)
                if user is None:
                    return reply(start, 401, {"error": "请重新登录"})
                with closing(self._connect()) as db, db:
                    if path.endswith("/logout"):
                        db.execute("DELETE FROM sessions WHERE token_hash=?", (digest,))
                        return reply(start, 200, {"ok": True})
                return reply(start, 200, {"username": user[1]})
            if env.get("CONTENT_TYPE", "").split(";")[0].lower() != "application/json":
                return reply(start, 415, {"error": "请使用 JSON 请求"})
            length = int(env.get("CONTENT_LENGTH", "0"))
            if length < 1 or length > MAX_BODY:
                return reply(start, 413, {"error": "请求大小无效"})
            value = json.loads(env["wsgi.input"].read(length))
            username, password = value.get("username"), value.get("password")
            if not isinstance(username, str) or not USERNAME.fullmatch(username) or not isinstance(password, str) or not 10 <= len(password) <= 128:
                return reply(start, 400, {"error": "用户名需为 3–32 位字母、数字或下划线；密码需为 10–128 位"})
            with closing(self._connect()) as db, db:
                client = env.get("HTTP_X_REAL_IP") or env.get("REMOTE_ADDR", "unknown")
                client = client[:64]
                now = int(time.time())
                db.execute("DELETE FROM attempts WHERE at<?", (now - 900,))
                count = db.execute("SELECT COUNT(*) FROM attempts WHERE client=? AND at>=?", (client, now - 900)).fetchone()[0]
                if count >= 20:
                    return reply(start, 429, {"error": "尝试次数过多，请稍后再试"})
                db.execute("INSERT INTO attempts(client,at) VALUES(?,?)", (client, now))
                if path.endswith("/register"):
                    salt = secrets.token_bytes(16)
                    try:
                        db.execute("INSERT INTO users(username,salt,password_hash,created_at) VALUES(?,?,?,?)",
                                   (username, salt, self._hash(password, salt), int(time.time())))
                    except sqlite3.IntegrityError:
                        return reply(start, 409, {"error": "用户名已存在"})
                else:
                    row = db.execute("SELECT id,salt,password_hash FROM users WHERE username=?", (username,)).fetchone()
                    salt = row[1] if row else b"\0" * 16
                    valid = hmac.compare_digest(self._hash(password, salt), row[2] if row else b"\0" * 32)
                    if not valid:
                        return reply(start, 401, {"error": "用户名或密码错误"})
                user_id = db.execute("SELECT id FROM users WHERE username=?", (username,)).fetchone()[0]
                token = secrets.token_hex(32)
                db.execute("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)",
                           (hashlib.sha256(token.encode("ascii")).digest(), user_id, int(time.time()) + SESSION_SECONDS))
            return reply(start, 200 if path.endswith("/login") else 201,
                         {"username": username, "token": token, "expiresIn": SESSION_SECONDS})
        except (ValueError, TypeError, KeyError, AttributeError, UnicodeError):
            return reply(start, 400, {"error": "请求内容无效"})
        except Exception:
            return reply(start, 500, {"error": "账号服务暂时不可用"})


_accounts = None


def application(env, start):
    global _accounts
    if _accounts is None:
        path = os.getenv("ACCOUNT_DB_PATH", "/var/lib/bilingualreader/accounts.sqlite")
        try:
            _accounts = Accounts(path)
        except (OSError, sqlite3.Error):
            return reply(start, 503, {"error": "账号服务尚未配置"})
    return _accounts(env, start)
