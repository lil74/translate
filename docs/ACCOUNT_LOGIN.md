# 账号登录试用部署

## 2026-11-18 ECS 部署记录

账号接口已部署至项目 ECS `8.148.28.233`，使用现有 HTTPS Nginx 与 systemd 服务。账号数据库位于 `/var/lib/bilingualreader/accounts.sqlite`，属主 `readertranslate`、权限 `600`。部署前的网关、Nginx、systemd 文件备份保存在 `/opt/bilingualreader/backups/`，文件名含 `before-accounts-20261118`。

线上验证：`/health` 返回 200；注册、登录、`/account/me`、退出完整流程通过，测试账号已删除；无令牌的翻译 POST 仍返回 401。App 构建配置中的账号服务器地址为 `https://8.148.28.233`。

本阶段实现注册、登录、会话校验与退出。账号数据库与手机本地书库独立；书籍不会上传。App 的“我的 → 账号登录”直接连接构建配置中的固定 HTTPS 服务器地址，用户无需填写服务器地址。

## ECS systemd 部署

将 `backend/accounts.py` 与更新后的 `backend/gateway.py` 部署到 `/opt/bilingualreader/app`。更新 `/etc/systemd/system/bilingualreader.service`，确保服务用户 `readertranslate` 对 `/var/lib/bilingualreader` 有写权限。项目的 `StateDirectory=bilingualreader` 与 `ACCOUNT_DB_PATH=/var/lib/bilingualreader/accounts.sqlite` 配套使用。更新 Nginx 的 `/account/` 反向代理配置，完成 HTTPS 配置检查后重启服务。现有服务器可能有其他 Nginx 配置，合并 location 时应保留现有站点设置。

若使用 Docker Compose，更新镜像和 compose 配置；账号 SQLite 文件位于独立的 `account-data` volume。务必将该 volume 纳入备份，避免重建容器后丢失账号。

## 接口

- `POST /account/register`、`POST /account/login`：JSON `{"username":"reader_1","password":"至少10位密码"}`，成功返回会话令牌。
- `GET /account/me`、`POST /account/logout`：`Authorization: Bearer <token>`。

用户名为 3–32 位字母、数字或下划线；密码为 10–128 位。服务端仅保存加盐 PBKDF2 密码摘要和令牌摘要，会话有效期 30 天。App 使用 Android Keystore 加密令牌。公网部署应保持 HTTPS，并定期备份账号数据库。当前账号没有邮箱验证、找回密码或书籍云同步。

验证：`python -m unittest discover -s backend -v`，以及 `gradlew.bat :app:compileDebugJavaWithJavac`。
