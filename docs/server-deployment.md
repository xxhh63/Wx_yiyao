# Ubuntu / 宝塔服务器部署

本方案给新小程序 `wxa64654c604b3af31` 提供后端，复用原云托管业务表、内容接口和管理后台。旧云托管模式仍可使用。数据库已按全量导出迁移到 `jiaoyi_zx`；不要再次执行清空/覆盖导入。

## 架构与目录

- HTTPS 域名：`https://xoesjan1.store`。
- 宝塔 Java 项目 `jiaoyi-api`：监听 `127.0.0.1:8082`，APP_MODE=mini，WECHAT_AUTH_MODE=server。
- 宝塔 Java 项目 `jiaoyi-admin`：监听 `127.0.0.1:8083`，APP_MODE=admin。
- 两者使用同一个构建 JAR、同一个数据库；通过现有 JDBC Session 表保存各自的登录会话。
- 实际文件根目录：`/www/wwwroot/trade exchange`。宝塔启动使用无空格链接 `/www/wwwroot/trade-exchange`，两者指向同一目录。
- JDK：`/www/server/java/jdk-21.0.2/bin/java`。
- 私密配置：项目根目录 `.local/api.properties` 与 `.local/admin.properties`，由 root/springboot 读取，不进入 Git。
- 旧代码下载目录 `jiaoyi/Wx_yiyao-master` 保留；新版源码另存 `backend`，成品位于 `release/app.jar`。

宝塔“网站 → Java 项目”负责查看日志、停止、启动。Nginx 负责 TLS 和路由：`/api/`、`/assets/` 到 8082，`/admin` 和 `/admin/` 到 8083。修改路由前备份旧域名配置，先 `nginx -t`，成功后 reload。

## 服务端配置

下面是属性名和取值，不要把真实密码写进仓库。配置文件由 Java 的 `--spring.config.additional-location=file:/.../.local/api.properties` 加载，宝塔启动命令里只放文件路径。

| 属性 | 小程序服务 | 管理服务 |
| --- | --- | --- |
| server.address | 127.0.0.1 | 127.0.0.1 |
| server.port | 8082 | 8083 |
| spring.datasource.url | jdbc:mysql://127.0.0.1:3306/jiaoyi_zx?characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true | 同左 |
| spring.datasource.username | jiaoyi_zx | jiaoyi_zx |
| spring.datasource.password | 宝塔业务库密码 | 同左 |
| app.mode | mini | admin |
| app.wechat.auth-mode | server | 不使用 |
| app.wechat.appid | wxa64654c604b3af31 | 不使用 |
| app.wechat.appsecret | 新 AppID 对应的 AppSecret | 不配置 |
| app.wechat.trusted-ingress | false | false |
| app.admin.username | 不配置 | 管理员账号 |
| app.admin.password-hash | 不配置 | BCrypt 完整哈希 |
| server.servlet.session.cookie.secure | 不使用 cookie | true |

AppSecret 从 `/root/yiyao-migration/wechat-appsecret.txt` 安全读取；不得写入前端、日志或命令行。不能在公网服务器上开启旧的 trusted-ingress 来“修复”登录；客户端伪造 X-WX 头不可信。

2 GB 服务器可先为每个 JVM 设置 `-Xms64m -Xmx192m -XX:+UseSerialGC`，根据宝塔内存监测再调整。接口只监听回环地址，防火墙无需开放 8082/8083。不要为迁移重启已停用的 Docker 和无关服务。

## 小程序接口契约

公共内容接口路径和响应保持原样，前端使用 `wx.request` 访问 HTTPS 域名。微信公众平台需为新 AppID 配置 request 合法域名。

1. `wx.login` 获取一次性 code。
2. `POST /api/auth/session`，JSON 必须为 `{"code":"微信返回的code"}`。
3. 返回 `data.authenticated/userId/createdAt/accessToken/expiresIn`；expiresIn 为 7200 秒无活动期限。后端直接与微信 code2Session 交换，openid 和 session_key 不下发。
4. `GET /api/me/card`、`PUT /api/me/card` 携带 `X-Auth-Token: accessToken`。保存体仍是 `{"values":{...}}`。
5. 401 最多重新 wx.login 并重试一次；身份变化时丢弃旧用户名片缓存，不能把旧名片自动保存给新用户。
6. `DELETE /api/auth/session` 撤销当前会话，返回 `data.authenticated=false`。没有有效会话时可以重复退出；跨 AppID/管理员会话会被拒绝。

令牌是登录凭证，不能放 URL、日志或公开配置。管理后台仍使用独立的 Secure/HttpOnly cookie 和 CSRF 校验。两种身份不能互相替代。

## Nginx 登录限流

在 http 级别定义：
```nginx
limit_req_zone $binary_remote_addr zone=jiaoyi_login:10m rate=2r/s;
```

登录精确 location 内增加：
```nginx
location = /api/auth/session {
    limit_req zone=jiaoyi_login burst=10 nodelay;
    limit_req_status 429;
    proxy_pass http://127.0.0.1:8082;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $remote_addr;
}
```

其他代理路径同样覆盖 X-Forwarded-For，不能信任客户端自带值。禁用该站点 TLS early data，避免一次性登录/写请求重放。禁止暴露源码、.local、配置文件和 SQL 备份。

## 验收与更新

- `GET /api/health`、`GET /api/ready` 验证服务及数据库；公共首页应读取已迁移内容。
- 未登录名片接口必须 401；伪造 X-WX-* 不能登录。
- `/admin/` 可显示登录页，管理员登录后能读取资源；未登录管理 API 仍被拦截。
- 真机完成登录 → 保存名片 → 退出/重新登录 → 恢复，才算真实微信登录验收。模拟微信接口的本地测试不等于真机验证。
- 后端本地 `mvn test` 包括云入口回归、管理员安全和服务器登录测试；需要独立 MySQL 的内容测试不能对生产迁移库运行。
- 更新时先构建与测试，备份上一版 JAR，替换 release/app.jar 后在宝塔依次重启两个项目，再核对 ready。失败则恢复上一版 JAR 与配置。
- 历史 app_user 按 AppID 隔离，新小程序不会自动冒用旧 AppID 用户；业务资源继续共用。
- 图片上传仍沿用现有 COS 配置；没有 COS 服务端凭据时不要宣称上传能力已迁移。

## 本次部署记录

详见 [2026-09-16 服务器验收](server-verification-2026-09-16.md)。
