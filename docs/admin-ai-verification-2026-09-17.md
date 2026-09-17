# AI录入助手验证与部署记录（2026-09-17）

## 已交付
资源管理、政策资讯、滚动广告、服务机构支持文件识别及文字/聊天补充，识别后打开新草稿的人工复核表单。未识别字段不采用原表单事实默认值；人工图片、来源链接、关联目标、排序及推荐选择在补充识别后保留。今日主推仍选择既有资源。

没有修改小程序，没有数据库迁移，没有自动保存或发布权限。

## 本地验证
- Maven package：60项，42通过、0失败/错误、18跳过。跳过项依赖独立MySQL测试库，未指向生产库执行。
- 新增AI服务8项、字段规范11项、文件解析10项；同时通过既有会话、服务器微信登录、管理员安全等回归。
- Node：AI录入18项、原资源DOM15项，以及既有admin/企业/角色字典检查全部通过；JS语法、Git diff whitespace检查通过。
- 浏览器：本地模拟接口验证桌面及390px窄屏、两个入口、模板、自动二审、留空、返回助手和必填拦截；模拟记录4次AI请求、0次内容写入，全部携带CSRF。该结果不是DeepSeek真实识别效果。
- 真实解析样本：PDF、DOCX、扫描页、XLSX、PPTX、RTF、图片；超时强杀、字符/页数/ZIP大小限制、空白及临时清理。DOCX外链/XXE探针0次网络请求。
- 可执行JAR的reader → PropertiesLauncher → worker完整链路验证通过。

## 服务器验证
- 部署根目录：`/www/wwwroot/trade exchange`。
- 仅通过宝塔重启 `jiaoyi-admin`，`jiaoyi-api`未重启。
- 新JAR SHA256：`5af07008657f6e918460fa6cfa2f482b460507b728896f12c0c49b15ed977969`。
- 旧JAR备份：`release/app-before-ai-20260917-100840.jar`。
- 管理私密配置已备份，并在末尾添加空的 `app.ai.api-key=`；权限维持640、root:springboot。
- Nginx管理代理等待调整为100秒，以覆盖受限解析与模型调用；上传限制仍6MiB。配置备份、nginx -t、reload成功。
- 实际公网HTTPS：`/api/health`、`/api/ready`、`/admin/index.html`均200，页面包含两个新入口。
- 管理员实际登录/退出均200；未登录AI状态401；登录后状态200且model为deepseek-flash、configured=false；无CSRF的生成请求403；未配Key生成请求503。
- 以服务器springboot账号、Java21执行已打包worker：TXT与PNG临时样本均通过，未调用模型，完成后清理。
- 服务器直连DeepSeek HTTPS返回401，证明出站及TLS可用，不代表密钥或模型调用已验证。

## 待用户完成
在宝塔 `.local/admin.properties` 的 `app.ai.api-key=` 后填写真实Key，重启jiaoyi-admin，再使用一份允许交给DeepSeek的材料试录入。真实识别质量、费用与供应商额度尚未验收。配置步骤见 [使用说明](admin-ai-import.md)。

真实材料的事实、资质、金额、日期及分类仍需人工复核；保存与发布继续使用原管理流程。
