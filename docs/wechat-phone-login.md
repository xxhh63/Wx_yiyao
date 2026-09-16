# 微信手机号快速验证登录

适用：自建服务器、新小程序 `wxa64654c604b3af31`；原 CloudBase 身份入口保留旧行为。

## 登录行为

前端使用微信原生 `button open-type="getPhoneNumber"`。用户点击并同意后，将组件返回的 phoneCode 与本次 wx.login 的 code 一同交给后端，两者不能混用。

```http
POST /api/auth/session
Content-Type: application/json

{"code":"wx.login 返回的 code","phoneCode":"手机号组件返回的 code"}
```

后端先用 code2Session 确认 OpenID，再以服务器 access_token 调用 getuserphonenumber。手机号请求同时携带该 OpenID，让微信校验手机号授权码与用户的绑定关系。号码、国家区号和返回水印 AppID 均校验后才保存并签发本系统会话。

成功响应仍在 data 下返回 authenticated、userId、createdAt、accessToken、expiresIn=7200，并增加：

- phoneVerified：true。
- phoneNumber：微信确认的真实手机号，仅返回给当前本人。
- countryCode：国家/地区区号。

页面应脱敏显示，不能记录手机号或凭据到日志、公开配置、本机长期草稿。微信 API access_token 与本系统 accessToken 是不同凭据，前者始终只留在服务端内存。

## 续期、取消和失败

- 用户拒绝授权或取消时，前端不调用登录接口，不显示登录成功。
- 服务端已经保存手机号绑定的账号可通过 `{"code":"新登录code"}` 续期或重新识别；不重复消费手机号授权码。
- 未绑定账号仅提交 code 返回 HTTP 428，需要重新点击手机号授权按钮。升级前尚未绑定手机号的旧会话访问名片也返回 428；退出接口仍可调用。
- 手机号授权码失效或已使用返回 HTTP 400。不能自动重试该授权码，也不能降级为无手机号登录。
- 微信调用失败、号码/水印不符合约定返回 HTTP 503；不新建账号、不绑定号码、不替换原会话。
- 只有明确的微信 API token 无效/过期错误才刷新 token 并重试一次。网络超时或手机号 code 错误不重放。
- 登录成功后轮换当前会话；同一 OpenID 继续使用原账号和名片。相同手机号不合并不同 OpenID 的账号。

## 数据库

新增 `app_user_phone`，字段 user_id、phone_number、country_code、verified_at，与 app_user 一对一关联。
微信验证手机号独立保存，不能用可编辑的 `user_card.phone` 充当验证结果；不会自动覆盖名片联系电话。

已有库在部署 JAR 前执行 [006_wechat_phone.sql](../migration/006_wechat_phone.sql)；该 SQL 只新增表，可重复执行，不清空原表。新建库的 schema-mysql.sql 已包括此表。
服务器当前业务库为 jiaoyi_zx。无需新增 AppSecret 或调整已验证的域名。

## 验证与部署

本地集成测试使用真实 Spring HTTP 服务及临时 H2 数据库，只有外部微信响应使用测试替身：
- 未授权拦截；微信账号与手机号 code 绑定检查；一次性消费。
- 数据库重启后保留绑定；名片联系电话独立；旧会话的绑定缺失检查。
- 同号不同用户隔离、跨 AppID/管理员会话隔离、注销及会话轮换。
- 手机号服务失败不落库、不泄露号码或凭据；token 缓存与单次刷新。

部署时先备份旧 JAR，再执行增量 SQL，核验表结构，替换 JAR 并通过宝塔依次重启 jiaoyi-api 与 jiaoyi-admin。前端改动由“云开发路线”任务维护。
原表和新表在回退时均保留，只回退 JAR，避免删除已授权手机号记录。

服务器 stable_token 预检已成功，说明 AppSecret 及当前服务器调用入口可用。这不替代真实手机授权验收。真实手机号必须由用户在微信授权弹窗中主动同意；自动化测试不得代替用户同意。

## 参考与复用

核对了 [WxJava 的手机号服务接口](https://github.com/binarywang/WxJava/blob/develop/weixin-java-miniapp/src/main/java/cn/binarywang/wx/miniapp/api/WxMaUserService.java)及其 [实现](https://github.com/binarywang/WxJava/blob/develop/weixin-java-miniapp/src/main/java/cn/binarywang/wx/miniapp/api/impl/WxMaUserServiceImpl.java)。
当前只新增两种微信 API 请求，继续使用现有 Java HttpClient、Jackson、MyBatis 和 Spring Session；没有新增 SDK、JWT 或 Redis。
微信官方文档页面本次抓取不可用，服务端调用凭据已另外做实际预检；完整手机号授权仍待用户真机操作。

## 2026-09-16 部署结果

- 后端功能提交 `5cd233e` 已推送 GitHub master，并部署到服务器；源码与 JAR 均已同步。服务器拉取 GitHub 网络阻塞时改用已推送提交的 Git bundle 快进，同一提交无额外源码改动。
- 业务库 `jiaoyi_zx` 已执行 006：新增表的四个字段及外键核对通过；原 app_user 2 行、user_card 0 行未变化，手机号绑定初始为 0 行。
- 新 JAR SHA256：`fd7bfefb6247a9db70223182d0fe0bb2c382a7490387bd416c403a84d9874253`。
- 回退 JAR：`/www/wwwroot/trade exchange/release/app-before-phone-23ce8f9.jar`。通过宝塔重启 jiaoyi-api、jiaoyi-admin 后，两个服务 ready 均 200。
- 本地 Maven package：31 项发现，13 项通过，18 项独立 MySQL 测试跳过，0 失败/错误。本轮没有以生产库运行 CRUD 测试。
- 正式 HTTPS：health、ready、home、后台登录页面均 200；手机号空授权码输入 400；匿名及管理员 cookie 读取小程序名片均 401；私密配置路径 404。
- 管理后台登录、资源/主推/广告/政策只读接口、退出均 200，cookie 保持 Secure。
- 服务器验证记录：`/root/yiyao-migration/phone-login-verification.json`。
- 尚未代替用户执行真实手机号授权；完整手机号绑定、真机展示及再次登录仍需要用户主动点击微信授权弹窗后验收。
