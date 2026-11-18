# 云端书库

## 2026-11-18 ECS 部署记录

云端书库接口已部署到项目 ECS `8.148.28.233`。服务器磁盘检查显示系统盘 40 GiB、部署时剩余约 32 GiB。部署前账号数据库备份为 `/opt/bilingualreader/backups/accounts.before-cloud-20261118.sqlite`；账号服务、网关、Nginx 和 systemd 配置也已备份于同目录，文件名含 `before-cloud-20261118`。

线上验证：两个临时账号完成上传、列表、下载、跨账号拒绝访问、删除全流程；临时账号及文件已清理。`/health` 返回 200，未带翻译令牌的 `/translate` POST 返回 401。Android 编译通过；本次没有连接中的设备，因此尚未验证手机端交互。

在“我的书架”顶部可点击“上传书籍”选择本地 EPUB/TXT；书籍卡片下会显示“☁ 已上传到云端”或“仅在本机”。也可从“我的 → 云端书库”上传；该页面会列出已上传的书籍，点击可下载并导入本机书架，长按可只删除云端副本。本机删除与云端删除互不影响。

云端只保存原始书籍文件及文件名、大小、上传时间。阅读位置、译文、目录自定义规则、书签与单词收藏仍仅保存在本机。恢复时使用现有 TXT/EPUB 导入流程；同一文件已在本机时不会重复导入。

## 服务端

`GET /books` 列出当前账号书籍；`POST /books` 上传，使用 `application/octet-stream` 请求体和 UTF-8 百分号编码的 `X-Book-Name`；`GET /books/{sha256}` 下载；`DELETE /books/{sha256}` 删除。所有请求都必须携带账号登录返回的 Bearer 令牌。用户只能访问自己的书籍；文件存于 `/var/lib/bilingualreader/books/<user_id>/`，元数据位于账号 SQLite 数据库。

当前试用限制：单书 50 MiB，每账号最多 100 本、合计 500 MiB。部署时 Nginx `/books` 上传入口允许 51 MiB，请备份账号数据库和书籍目录。保留 HTTPS 与账号接口的访问控制。

验证：`python -m unittest discover -s backend -q` 与 `gradlew.bat :app:compileDebugJavaWithJavac`。
