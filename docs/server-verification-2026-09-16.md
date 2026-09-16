# 2026-09-16 服务器迁移验收记录

## 已完成

- 新 AppID：`wxa64654c604b3af31`。后端服务器登录实现提交：`7a6efbb`，已推送 GitHub master。
- 服务器：Ubuntu，Java 21.0.2，宝塔管理；实际根目录 `/www/wwwroot/trade exchange`，启动链接 `/www/wwwroot/trade-exchange`。
- 新版源码：`backend` 子目录；原下载的 `jiaoyi/Wx_yiyao-master` 保留。
- 宝塔 Java 项目：`jiaoyi-api`（127.0.0.1:8082）和 `jiaoyi-admin`（127.0.0.1:8083）。已通过宝塔原生接口逐一重启并验证 ready。
- 域名 `xoesjan1.store` 已正式登记到 `jiaoyi-admin`。根路径跳转后台；/api/ 和 /assets/ 转发小程序服务。
- 新站点独立使用证书目录 `/www/server/panel/vhost/cert/jiaoyi-admin`，由现有有效证书复制。后续证书续期必须对这个新项目维护，不能只更新旧站点证书。
- 数据库 `jiaoyi_zx`：原 14 张表、244 行全量导入时核对一致；资源 93 条。之后后台登录会产生正常会话记录，不能再要求全库总行数固定为 244。
- 私密配置 .local/api.properties、.local/admin.properties 权限 0640（root/springboot），不进 Git；AppSecret 只在服务器。
- 管理员初始凭据保存在 `/root/yiyao-migration/admin-initial-credentials.txt`，权限 0600，可由服务器管理员通过宝塔文件查看。
- 旧项目保留；sillytavern、st-manager、reception-ai-demo、supervisord、docker、containerd 仍为 inactive/disabled。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| 本地默认测试 | 29 项发现，11 项执行通过，18 项需要独立 MySQL 而跳过；无失败 |
| Maven 可执行 JAR 构建 | 成功 |
| /api/health、/api/ready、/api/home | 正式 HTTPS 均 200；服务器外也验证 ready 200 |
| /admin/ | 200，展示登录页 |
| 管理员登录、资源/主推/广告/政策读取、退出 | 正式 HTTPS 验证通过，Secure cookie |
| 未登录读取名片、管理员 cookie 读取小程序名片 | 401 |
| /.local/api.properties | 404 |
| 宝塔逐项重启 | 两个项目重启后 ready 均 200 |
| 小程序开发者工具真实网络 | urlCheck=true；健康、首页和真实 wx.login/code 登录均通过 |
| 开发者工具名片与注销 | GET/PUT/DELETE 200；重登同一用户后内容一致；旧凭证读取名片 401 |
| 真机登录、名片保存恢复 | 待验收；不得用本地模拟测试替代 |

部署 JAR SHA256：
`93550275d0dbbea348a00f785f320c10f6deff57fff99b4c184e1085008531b4`

服务器核对记录：
`/root/yiyao-migration/server-verification.json`。
数据库导入记录：
`/root/yiyao-migration/database-20260916-1010/verification.json`。

## 后续联调结果与下一步

用户已完成新 AppID 的 request 合法域名配置。“云开发路线”重开项目后保持 urlCheck=true，真实首页和健康接口请求已通过。

实际前端 wx.login → POST /api/auth/session 返回 HTTP 200、code=0、authenticated=true。名片 GET/PUT 成功；退出 DELETE 成功后，旧凭证 GET 返回 401；重新登录后仍为同一用户，名片内容与保存值一致。

测试前该用户没有名片记录。验收后前端已还原空字段并退出，后端在新 AppID、唯一用户、全部字段为空和本次时间范围的严格条件下，删除唯一一条测试新增空白名片行，恢复原来的无记录状态。没有保留测试用个人资料。

下一步由用户手机扫码预览：进入“我的”登录，保存自己的名片，退出后重新登录确认恢复。开发者工具实连已经通过，手机真机尚未验收；没有上传体验版或发布正式版。

图片上传仍需管理服务的 COS 配置；本轮未取得或迁移 COS 密钥，已有内容展示与编辑接口已可用，图片上传不能宣称已完成。
