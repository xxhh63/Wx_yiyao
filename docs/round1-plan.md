# 后端第一轮实施与验收

范围：只修改本 Spring Boot 仓库。小程序只读核对字段，不改小程序、不推送远程、不操作现有云服务或真实用户数据。

设计：沿用 Spring Boot + MyBatis + MySQL。云托管私有入口提供微信身份，服务端校验来源和 AppID，用户以 (appid, openid) 唯一识别；名片从数据库恢复。没有 AppSecret、JWT、模拟登录或客户端 userId 授权。

接口：GET /api/health（存活）、GET /api/ready（数据库连通）、POST /api/auth/session（识别并登记用户）、GET /api/me/card、PUT /api/me/card。名片响应保持 version / values / savedAt 结构；PUT 全量替换八个字段。

- [x] 升级旧模板构建基础，先编写失败的 HTTP 集成检查。
- [x] 实现默认拒绝的微信身份校验、用户唯一约束、名片保存读取、输入校验和统一错误响应。
- [x] 用独立本地数据库检查：重启恢复、第二用户隔离、并发首次请求、非法输入、伪造用户字段、缺失身份、未启用可信入口。
- [x] 编译可部署 JAR，补充 MySQL 建表、环境变量、云托管部署和小程序接入契约。
- [x] 复核只修改后端并准备本地 Git 提交；记录本地验证与云端真机验证的边界。

入口要求：必须先关闭服务公网、移除未认证 HTTP 网关和其他可直达容器的入口，再显式启用 WECHAT_TRUSTED_INGRESS。这个变量是部署确认开关，不是密码或网络防火墙。未核实云端配置前不得打开。
