# 医药小程序后端 · 内容管理二期

二期已增加首页内容接口、资源/资讯/机构管理、管理员网页及手动 SQL 迁移包。小程序身份识别、名片保存恢复保持一期契约。

- [微信手机号快速验证登录](docs/wechat-phone-login.md)
- [Ubuntu / 宝塔服务器迁移](docs/server-deployment.md)
- [二期部署与后台使用](docs/phase2-deployment.md)
- [SQL导入步骤与核对](migration/README.md)
- [验证记录与云端待办](docs/phase2-verification.md)
- [已确认计划及实施调整](docs/superpowers/plans/2026-09-09-backend-phase2.md)

云数据库尚未执行二期SQL，云托管尚未发布本版本；先导入、再部署两种服务模式，最后发布前端。

## 一期接口与原部署说明（保留供查阅）

基于微信云托管官方 Spring Boot 模板，提供服务检查、当前微信用户识别、名片保存和恢复。本仓库只负责后端。

## 当前范围

- Java 21、Spring Boot 3.5.16、MyBatis 3.0.5、MySQL。
- 用户身份由可信云托管入口传入，按 `(appid, openid)` 唯一登记；不需要自建密码登录，不向小程序暴露 OpenID。
- 名片写入数据库，服务重启后仍可读取。八个字段与当前小程序一致。
- 原模板计数器不对正式小程序服务开放。
- 一期未实现企业入驻、手机号授权和头像昵称授权；二期新增管理员内容发布和网页后台，见上方二期说明。

## 快速验证

需要 JDK 21+（本轮实际使用本机 JDK 25）与 Maven 3.6.3+：

```powershell
mvn --batch-mode --no-transfer-progress clean verify
```

默认测试使用临时 H2 文件数据库，启动真实 HTTP 服务并关闭、重启它，检查身份、并发登记、用户隔离、输入校验及名片恢复。H2 仅属于测试依赖，不进入部署 JAR。

如要对真实 MySQL 验证，先准备**专用测试库**，通过进程环境变量提供以下内容，再执行同一命令：

| 环境变量 | 说明 |
| --- | --- |
| BACKEND_TEST_JDBC_URL | 专用库 JDBC URL，含 `characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` |
| BACKEND_TEST_DB_USER | 测试库账号 |
| BACKEND_TEST_DB_PASSWORD | 测试库密码 |

测试会在该库创建两张表、写入带随机前缀的测试用户/名片。不会删除已有表或业务数据，禁止使用正式业务库。每次测试使用新身份，可重复运行。测试代码只模拟可信入口请求头，用于验证服务端逻辑，不等于真机微信认证已经通过。

产物：`target/springboot-wxcloudrun-1.0.jar`。

## 部署顺序

### 1. 准备业务数据库

使用 MySQL 8.x，创建专用业务数据库与服务账号。用数据库管理账号执行：

```sql
CREATE DATABASE yiyao CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

在这个库中执行 [schema-mysql.sql](src/main/resources/schema-mysql.sql)。该脚本只创建 `app_user`、`user_card`，不删除模板 `Counters`，不自动变更已有表结构。

服务账号需要两张业务表的 SELECT、INSERT、UPDATE 权限。DDL 使用独立管理账号；不要在代码中保存 root 密码。应用不会在每次启动时自动建表。

### 2. 配置云托管服务

已知目标信息仅用于定位，当前配置和连通性仍需控制台核实：

- 环境：`prod-d2gvfetsj148e4189`
- 服务：`springboot-olrl`
- 小程序 AppID：`wx2948f6a7ea6a688b`

在服务的环境变量配置中填写：

| 变量 | 值 / 含义 |
| --- | --- |
| MYSQL_ADDRESS | 实际数据库内网 host:port，必须从数据库配置取得 |
| MYSQL_DATABASE | 实际业务库名，例如 yiyao，必填 |
| MYSQL_USERNAME | 业务数据库账号 |
| MYSQL_PASSWORD | 业务账号密码，仅配置在服务端 |
| WECHAT_APPID | wx2948f6a7ea6a688b |
| WECHAT_TRUSTED_INGRESS | 初次部署保持 false；完成下一步入口核验后才设 true |
| PORT | 默认 80，须与云托管容器端口一致 |

JDBC 会统一数据库会话时区为 UTC；接口时间返回 ISO 8601，例如 `2026-09-08T03:00:00Z`。容器系统时区不影响名片时间的含义。

`.env.example` 仅供填写参考，Spring Boot 不会自动加载这个文件。不要把填写密码的文件提交到 Git 或复制进容器镜像。

### 3. 核实身份信任边界

仅通过获授权小程序的 `wx.cloud.callContainer` 调用私有接口：

1. 确认小程序已关联或获授权访问该云环境和服务。
2. 在服务设置关闭公网访问，并排查自定义域名、HTTP 网关和其他直达容器的入口。关闭服务公网开关不会自动关闭独立 HTTP 网关。
3. 移除未认证的网关入口；本轮不通过公开 HTTP 接收微信身份头。未来需要公开 API 时必须另加可验证的认证，不能直接放开现有接口。
4. 核实完成后才将 `WECHAT_TRUSTED_INGRESS=true`。

代码会校验 `X-WX-SOURCE`、`X-WX-APPID`、`X-WX-OPENID`，拒绝重复身份头和错误 AppID。**这些请求头本身不是签名；配置开关也不是防火墙。若把私有接口公开后仍启用信任，攻击者可能伪造身份。**

无需为本轮云托管身份链路配置 AppSecret。身份识别与取得手机号、头像昵称是不同能力，本轮没有获取这些微信资料。

### 4. 构建和运行

本地测试通过后，可使用本仓库 Dockerfile 构建镜像：

```powershell
docker build -t yiyao-backend:round1 .
```

Dockerfile 使用 Maven + Temurin 21 编译并运行测试，运行镜像为 Temurin 21 JRE。端口为 80；建议健康检查 `GET /api/health`，数据库就绪检查 `GET /api/ready`。

也可以在已配置环境变量的机器运行：

```powershell
java -jar target/springboot-wxcloudrun-1.0.jar
```

本轮本机没有运行 Docker daemon，因此验证了 JAR 构建与实际 HTTP 运行，未声称 Docker 镜像或云托管发布已通过。

### 5. 接入前的检查点

- `/api/health` 返回 HTTP 200：服务进程正常。
- `/api/ready` 返回 HTTP 200：数据库可以连接；它不检查表结构，建表必须完成。
- 身份未启用时私人接口返回 HTTP 503；启用后无有效微信身份返回 HTTP 401。
- 真机经 callContainer 完成 session → 保存名片 → 重新读取。
- 清除小程序本地缓存、重启服务后仍能恢复名片；切换另一个微信账号不能读取前一用户名片。
- 从所有公网地址发送无身份和伪造身份头请求，都不能进入这些私人接口。

最后三项需要云配置和真机验证。一期未修改小程序；二期由已授权前端任务同步接入，尚未向现有云服务发布。

## 接口契约

统一响应：

```json
{"code":0,"errorMsg":"","data":{}}
```

业务失败使用非 0 code，同时返回相应 HTTP 状态：400 输入格式错误，401 未识别身份，503 身份入口未启用或数据库暂不可用。数据库错误不返回 SQL、密码或名片内容。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/health | 服务存活，不要求身份 |
| GET | /api/ready | 数据库连通，不要求身份 |
| POST | /api/auth/session | 识别当前用户并自动登记，重复调用返回同一 userId |
| GET | /api/me/card | 读取当前用户名片，未保存返回空字段和 savedAt:null |
| PUT | /api/me/card | 全量保存当前用户名片，返回落库后的完整内容 |

POST session 不需要请求体，响应示例：

```json
{"code":0,"errorMsg":"","data":{"authenticated":true,"userId":"服务端用户标识","createdAt":"2026-09-08T03:00:00Z"}}
```

返回的 userId 仅用于展示或关联客户端状态，不能用它指定其他用户。后续每个私有请求均重新验证微信身份，不签发额外 JWT。GET/PUT 名片不依赖客户端先缓存 userId。

PUT 请求：

```json
{
  "values": {
    "name": "张先生",
    "phone": "13800138000",
    "company": "示例科研机构",
    "position": "技术经理",
    "address": "广州市",
    "email": "sample@example.com",
    "wechat": "sample_wechat",
    "intro": "生物医药成果转化"
  }
}
```

名片响应：

```json
{
  "code": 0,
  "errorMsg": "",
  "data": {
    "version": 1,
    "values": {
      "name": "张先生",
      "phone": "13800138000",
      "company": "示例科研机构",
      "position": "技术经理",
      "address": "广州市",
      "email": "sample@example.com",
      "wechat": "sample_wechat",
      "intro": "生物医药成果转化"
    },
    "savedAt": "2026-09-08T03:00:00Z"
  }
}
```

| 字段 | 最大字符数 |
| --- | --- |
| name | 30 |
| phone | 24 |
| company | 80 |
| position | 50 |
| address | 120 |
| email | 100 |
| wechat | 50 |
| intro | 500 |

字符数按 Unicode 码点计算，支持中文和表情。只允许字符串，首尾空白会清理；未知字段、null、数组、数字、超长内容或无效非空电话/邮箱返回 400。字段可为空。**PUT 为全量替换：省略字段将存为空字符串。** 多端同时保存时以后提交成功的保存为准；本轮不提供历史版本。

不要发送 userId、openid、appid、savedAt 或客户端草稿 version；服务端独立确定身份和保存时间。

## 后续小程序接入说明（本轮未修改）

经用户知情后，需要在小程序初始化云环境，调用上述接口，将名片读写从本地草稿迁移到服务端。例如：

```javascript
// 这是接入契约示例，不是本轮已修改的小程序源码。
wx.cloud.init({ env: 'prod-d2gvfetsj148e4189' })
const result = await wx.cloud.callContainer({
  config: { env: 'prod-d2gvfetsj148e4189' },
  path: '/api/auth/session',
  header: { 'X-WX-SERVICE': 'springboot-olrl' },
  method: 'POST'
})
if (result.statusCode !== 200 || result.data.code !== 0) {
  throw new Error(result.data.errorMsg || '身份识别失败')
}
```

保存使用 `PUT /api/me/card`、`data: { values }`，并带 `Content-Type: application/json`；读取用 GET。请勿手动填写 X-WX-OPENID / X-WX-APPID / X-WX-SOURCE。

原本地名片是否导入云端，需要在接入时明确用户选择，不能静默覆盖已有云端名片。数据库失败时前端应保留编辑内容并允许重试，只有收到成功响应才显示“已保存”。

## 依据与验证记录

- [官方 Spring Boot 模板](https://github.com/WeixinCloud/wxcloudrun-springboot)
- [小程序 callContainer 调用](https://docs.cloudbase.net/en/run/develop/access/mini)
- [云托管身份说明](https://docs.cloudbase.net/faq/knowledge/cloudrun-authentication-integration)
- [公网访问设置](https://docs.cloudbase.net/run/deploy/networking/public)
- [入口关系](https://docs.cloudbase.net/run/related)
- [官方来源头判断实现](https://github.com/WeixinCloud/wxcloudrun-wxcomponent/blob/main/middleware/wxsource.go)
- [Spring Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [MyBatis 兼容矩阵](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)

执行结果见 [第一轮验证记录](docs/round1-verification.md)。

## 后台 AI 录入助手

资源、政策、广告、服务机构支持文件导入和文字生成待复核表单。API Key仅配置在服务端，保存与发布由工作人员完成。见 [配置与使用说明](docs/admin-ai-import.md)。
