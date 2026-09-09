# 后端二期：部署与后台使用

日期：2026-09-09。代码与 SQL 在本地完成并验证；云库尚未导入、服务尚未发布。先执行 SQL，再发布 Java 服务，最后切换小程序内容接口。

## 1. 先导入数据库

现有 `yiyao.app_user`、`user_card` 保留，不参与首页统计。不要删除这两张表。

下载二期 SQL 压缩包，解压后按照 [迁移说明](../migration/README.md) 的顺序，在 Navicat 中选中 **yiyao** 执行：

1. `000_preflight.sql`：只读检查库名、版本、现有表。
2. `001_schema.sql`：创建内容表、后台会话及限流表。首次完整执行；已有会话表时按说明跳过标准 Session 段。
3. `002_seed.sql`：先保留 `@phase2_dry_run=1` 执行整个文件，看到 DRY_RUN_READY 后改为 0 再执行整个文件。
4. `003_verify.sql`：核对 IMPORTED / COMPLETED 及条数。

同一清单再次导入会跳过，保护后台后续修改。SQL 失败时在原查询连接执行 ROLLBACK，不能手工补 COMMIT。云数据库外网此前强制 SSL 连接失败，本包支持用户在受控连接手动执行。

初始内容为 **93条资源、119条专区展示关联、4条主推、5条广告、6篇资讯、8家机构**。资源去重后首页数字：

| 技术需求 | 科技成果 | 行业专家 | 技术经理人 | 高校院所 | 技术专利 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 13 | 29 | 4 | 0 | 0 | 12 |

仅统计已发布且业务状态为 open 的资源；跨专区展示同一资源不会重复计数。技术经理人和高校当前没有独立档案来源，因此为0；名片或机构服务卡不能自动冒充这些档案。源示例内容已经作为正式内容进入发布流程，同时保留 legacy_sample 来源说明。

## 2. 同一份代码部署两个服务

保持小程序服务 `springboot-olrl` 的私有调用边界；单独创建一个网页管理服务，例如 `yiyao-admin`。两者连接同一个 yiyao 库，使用同一份 JAR/镜像。

| 环境变量 | 小程序服务 | 网页管理服务 |
| --- | --- | --- |
| APP_MODE | mini | admin |
| MYSQL_ADDRESS | 控制台确认的数据库内网 host:port | 同左 |
| MYSQL_DATABASE | yiyao | yiyao |
| MYSQL_USERNAME / MYSQL_PASSWORD | 服务端业务账号与密码 | 服务端业务账号与密码 |
| WECHAT_APPID | wx2948f6a7ea6a688b | 可留空 |
| WECHAT_TRUSTED_INGRESS | 入口核验后才设 true | false |
| PORT | 80，与容器端口一致 | 80，与容器端口一致 |
| ADMIN_USERNAME | 留空 | 自定义管理员账号 |
| ADMIN_PASSWORD_HASH | 留空 | 第3步生成的 BCrypt 完整字符串 |
| SERVER_SERVLET_SESSION_COOKIE_SECURE | 不用配置 | 保持默认 true |

管理服务通过 HTTPS 公开 `/admin/`，小程序服务保持公网及其他未认证入口关闭。APP_MODE=admin 不注册微信身份/名片接口，伪造微信头也不能读取私人资料；APP_MODE=mini 禁用后台。不要把两个模式混成一个公网微信身份服务。

运行期不自动建表。内容与媒体表需要 SELECT/INSERT/UPDATE/DELETE，后台还需要会话表与 admin_login_attempt 的对应权限；一期小程序用户表需要 SELECT/INSERT/UPDATE。DDL 由迁移账号执行。建议分别配置最小权限业务账号，避免容器持有 root。

构建（JDK21+）：

~~~powershell
mvn --batch-mode --no-transfer-progress clean verify
java -jar target/springboot-wxcloudrun-1.0.jar
~~~

现有 Dockerfile 可构建同一产物，端口80。此次已验证实际 JAR，未执行 Docker daemon 构建或云托管发布。

## 3. 设置后台账号

在后端仓库 PowerShell7 终端运行：

~~~powershell
pwsh -File ./scripts/New-AdminPasswordHash.ps1
~~~

需 JDK21+，先完成 Maven package；可用 `-Java <java.exe绝对路径>` 和 `-Jar <jar路径>` 指定。脚本隐藏密码输入，经标准输入传给 BCrypt，不把原密码放到命令参数或文件中。密码至少12字符、最多72个UTF-8字节。

把输出的整段 BCrypt 字符串填到云托管管理服务的 `ADMIN_PASSWORD_HASH`；`ADMIN_USERNAME` 填你自己的账号。没有默认生产账号和密码。不要把实际值填写到仓库的 .env.example 或发到聊天中。

打开管理服务 HTTPS 域名下的 `/admin/`，使用原密码登录。会话存 MySQL、30分钟无操作过期；所有修改验证 CSRF、登录后刷新会话；五次失败后锁定15分钟。当前单管理员配置，修改账号/密码后需要使旧会话失效：在后台维护窗口清除管理员会话或等待会话过期后更换服务配置。

本机 HTTP 临时验收可设置 Secure cookie=false；云端保持 true。本地测试账户不会进入部署包。

## 4. 如何修改首页内容

- **今日主推**：先在“资源管理”维护内容与展示图，再在“首页主推”关联资源、排序和发布。主推是资源引用，不复制第二份资源；资源下架或结束后首页自动隐藏该推荐。
- **移动广告**：编辑上标、标题、副标题、图片、按钮文案、详情和排序；动作可选自身详情、资源、资讯或不跳转。关联目标未发布或已结束时不能上架，后续目标失效时首页隐藏该广告。
- **政策资讯**：编辑分类、日期、来源、摘要和分节正文；勾选“推荐到首页”。首页最多显示3篇推荐，完整列表可搜索、分类、分页。官方/转载必须填写可核对的 HTTPS 原文链接。
- **资源/机构**：维护列表和详情。资源勾选对应专区后才进入该专区列表，类型与分类选项由同一份字典校验；跨类别的筛选参数会被拒绝。
- **数据概览**：展示数据库统计，不允许填写虚拟基数。
- **操作记录**：查询创建、修改、发布、下架与迁移记录。

新建默认草稿，保存后到列表点击“发布”。已发布内容保存后立即更新；需要准备新版本时先下架再编辑。同一条内容被其他窗口修改会返回409并保留当前输入，手动重新载入后再处理。正文是结构化纯文本，无任意HTML执行。

## 5. 配置图片上传

管理服务额外配置：

| 变量 | 内容 |
| --- | --- |
| COS_SECRET_ID / COS_SECRET_KEY | 仅服务端配置、可写指定内容前缀的COS凭据 |
| COS_SESSION_TOKEN | 使用临时凭据时填写，并在到期前更新 |
| COS_BUCKET | 完整桶名，包含账号数字后缀 |
| COS_REGION | 桶实际地域，如 ap-shanghai |
| COS_PUBLIC_BASE_URL | 可选，已配置的HTTPS内容域名；不填使用标准COS域名 |

上传只接收 PNG/JPEG、5MB以内，服务端检查真实格式、尺寸和像素。写入随机新对象，不覆盖已有图片；数据库登记失败时清理本次新对象。当前生成的是公开可读的内容图片URL，因此使用**专门的公开内容桶/前缀，不上传私人证件或名片附件**。配置对应上传和ACL权限。

小程序需将实际图片HTTPS域名纳入平台允许的配置并真机验证。缺少COS配置时上传返回503和明确提示，仍可保存已有HTTPS图片地址。

原来的3张机构示意PNG作为内置素材保留，SQL只迁移图片引用，未上传COS。后续管理员可逐个替换为自己的真实图片。此次未进行真实云存储上传。

## 6. 接口与联调检查点

统一响应 `{code,errorMsg,data}`。内容只读不要求用户登录，仍经现有 callContainer 私有服务调用；用户识别和名片接口保留一期契约。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | /api/home | 主推、广告、推荐资讯、六项统计 |
| GET | /api/stats | 统计 |
| GET | /api/catalogs/{audience} | pool/investor/enterprise/scientist/manager 分类及筛选 |
| GET | /api/resources?audience=...&category=... | 搜索、组合筛选、分页 |
| GET | /api/resources/{id}?audience=... | 详情，audience可省略，旧收藏ID按专区解析 |
| GET | /api/policies、/api/institutions | 分页列表 |
| GET | /api/policies/{id}、/api/institutions/{id}、/api/promos/{id} | 详情 |
| GET/POST/DELETE | /admin/api/session | 会话/登录/退出 |
| GET/POST/PUT | /admin/api/{collection}[/{id}] | featured/promos/policies/resources/institutions 管理 |
| PUT | /admin/api/{collection}/{id}/publication | 发布/下架，带 version |
| GET | /admin/api/stats、/admin/api/audit | 统计、审计 |
| POST/GET | /admin/api/media | multipart file上传、图片列表 |

分页从1开始，默认20条、上限50。首页主推和广告每类最多读取排序靠前的100条，超过此运营规模再调整加载策略。排序仅 default/newest；筛选键和值以对应字典为准。写入JSON上限256KB。400输入错误、401未登录、403无权限/CSRF错误、404下架或不存在、409版本冲突、413请求过大、429登录限流、503数据库或存储未配置/不可用。

云部署检查顺序：health200 → ready200 → callContainer读取home → 后台登录并修改广告 → 小程序刷新后看到变化 → 发布/下架资讯 → 页面同步 → 真机头像名片回归。失败页面显示错误和重试，不回退写死的业务数据。ready只检查连接，不代替迁移核验。

## 7. 代码取舍与验证边界

内容采用五类业务表，公共控制列加经过校验的JSON载荷，专区展示用独立关联表；复用现有Spring JDBC进行内容读写，原MyBatis名片代码不改。没有引入额外脚手架、Redis或富文本编辑器。

安全和会话复用 [Spring Security samples](https://github.com/spring-projects/spring-security-samples)、[Spring Session JDBC](https://docs.spring.io/spring-session/reference/guides/boot-jdbc.html)；图片用 [腾讯云官方 COS Java SDK](https://github.com/tencentyun/cos-java-sdk-v5)，仅基本上传能力，不引入未使用的KMS SDK。

已完成的检查与尚待云端验证项见 [二期验证记录](phase2-verification.md)。此版本支持单管理员内容运营；没有新增企业自主发布审核、手机号授权登录或机构入驻流程。
