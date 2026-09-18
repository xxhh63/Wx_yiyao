# 骊珠交易中心 · 自建服务器后端

当前业务使用 Ubuntu、宝塔、Spring Boot 和 MySQL。小程序通过 HTTPS 调用本服务器；微信仅提供登录、手机号授权等平台能力，不使用微信云托管或云数据库作为业务运行路径。

## 当前项目

- 小程序 AppID：`wxa64654c604b3af31`。
- HTTPS：`https://xoesjan1.store`；管理入口：`/admin/`。
- 业务数据库：服务器 MySQL `jiaoyi_zx`。
- 宝塔 Java 项目：`jiaoyi-api` 监听 `127.0.0.1:8082`，`jiaoyi-admin` 监听 `127.0.0.1:8083`。
- 两个进程使用同一份 JAR、同一个业务库，分别提供小程序 API 和管理员后台。
- 本仓库负责后端及管理网页；小程序源码由前端任务维护。

## 配置与部署

以 [Ubuntu / 宝塔部署说明](docs/server-deployment.md) 为准。小程序服务必须配置 `APP_MODE=mini`、`WECHAT_AUTH_MODE=server`、新 AppID 及仅保存在服务端的 AppSecret；`WECHAT_TRUSTED_INGRESS=false`。

管理员服务使用 `APP_MODE=admin`、独立管理员账号和 BCrypt 密码哈希。服务只监听回环地址，由 Nginx 提供 HTTPS；管理员使用 Secure/HttpOnly Cookie 和 CSRF 校验，小程序使用 `X-Auth-Token`，两种会话不能互换。

`.env.example` 是空凭据示例，不会由 Spring Boot 自动加载。实际配置保存在服务器 `.local/api.properties`、`.local/admin.properties`，不得提交 Git、写入小程序或复制到聊天和日志。已经迁移的业务库不要重复执行清空或覆盖导入；新增结构只执行对应增量 SQL。

- [微信手机号快速验证登录](docs/wechat-phone-login.md)
- [登录与单选身份标签](docs/user-identity.md)
- [AI 录入助手配置与使用](docs/admin-ai-import.md)
- [资源分类与人工编辑](docs/admin-resource-categories.md)
- [SQL 迁移文件说明](migration/README.md)
- [2026-09-16 服务器验收记录](docs/server-verification-2026-09-16.md)
- [2026-09-17 代码审查与修复](docs/review-2026-09-17.md)

## 接口与身份

统一成功响应：`{"code":0,"errorMsg":"","data":{}}`。错误同时使用相应 HTTP 状态；不向客户端返回 SQL、密码或服务端凭据。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/health | 服务存活 |
| GET | /api/ready | 数据库连通 |
| POST | /api/auth/session | 微信登录，并在首次授权时绑定手机号 |
| DELETE | /api/auth/session | 注销当前会话 |
| GET / PUT | /api/me/card | 当前用户名片读取 / 全量保存 |
| GET / PUT | /api/me/identity | 当前用户单选身份读取 / 覆盖保存 |
| GET | /api/home | 主推、广告、统计、政策 |
| GET | /api/resources、/api/catalogs/{audience} | 资源列表与分类筛选字典 |
| GET | /api/policies、/api/institutions、/api/stats | 政策、机构、统计 |

首次手机号授权后，登录请求为：

```json
{"code":"wx.login返回的code","phoneCode":"手机号组件返回的code"}
```

已绑定手机号的账号可只提交新的 `code` 续期；未绑定时返回 428。成功返回本系统 `accessToken`，后续名片请求通过 `X-Auth-Token` 携带。OpenID、微信 session_key 和微信服务端 access_token 不下发。接口、拒绝授权和凭据失效的完整行为见手机号登录文档。

名片保存体为 `{"values":{...}}`，只允许 `name`、`phone`、`company`、`position`、`address`、`email`、`wechat`、`intro` 八个文本字段。PUT 全量替换，省略字段会清空；服务端从当前会话确定用户，不能由客户端指定其他人的 userId。验证手机号与可编辑的名片联系电话分开保存。

## 内容管理与 AI

后台维护资源、政策、广告、服务机构和今日主推；数据中心按实际已发布资源计数。资源支持角色、二级分类及可选筛选属性，缺失属性不会命中具体筛选。

AI 助手使用服务端配置的 `DEEPSEEK_API_KEY` 和固定模型 `deepseek-flash`，从材料或文字生成四类待审核表单。未识别字段留空，已有人工字段应保留；保存、发布和关联项目选择均由工作人员完成。一次导入生成一份待审核表单，Excel 多项目批次审核尚未实施。

## 本地验证

需要 JDK 21+ 与 Maven。以下命令在仓库根目录运行：

```powershell
mvn --batch-mode --no-transfer-progress verify
node scripts/export-phase2.test.cjs
Get-ChildItem scripts/tests -Filter '*.test.cjs' | ForEach-Object { node $_.FullName; if ($LASTEXITCODE -ne 0) { throw "回归失败：$($_.Name)" } }
```

默认使用临时 H2 数据库与本地微信 / AI 响应替身，不调用真实手机号授权或真实 DeepSeek。内容查询和 CRUD 的真实 MySQL 测试仅在显式配置 `CONTENT_TEST_JDBC_URL/CONTENT_TEST_DB_USER/CONTENT_TEST_DB_PASSWORD` 时执行；必须使用独立测试库，严禁指向生产库。未执行的 MySQL 用例不计入通过数量。

构建产物：`target/springboot-wxcloudrun-1.0.jar`。本地构建、Git 推送、服务器部署、模拟器和真机验收是独立步骤，代码测试通过不代表线上已经更新。
