# DeepSeek 后台录入助手

## 使用方法
后台的资源管理、政策资讯、滚动广告、服务机构列表增加 **导入文件** 和 **AI录入助手** 两个入口。今日主推仍从已有资源选择。

1. 选择要录入的栏目；资源可以先指定一级角色、二级分类。
2. 上传文件，或点击文字模板填写材料。选中的材料会送往 DeepSeek；仅上传允许交给该服务处理的材料。
3. 点击生成；自动打开新的待复核表单。没有识别到的信息留空，缺失或异常处会提示。
4. 工作人员核对标题、正文、主体、金额、日期、分类等；图片与跳转关联由人工维护。
5. 可返回助手补充信息，再生成表单。未提及的人工字段会保留；需要清空时直接在表单修改。
6. 点击保存草稿，再沿用列表中的发布操作。助手不会自动保存、覆盖已发布内容或发布。

对话只处理当前一张表单。模板示例不作为事实。切换栏目或退出会提示丢弃；刷新页面后未保存草稿不保留。取消只停止界面等待，服务器/供应商已开始的请求可能仍会完成并产生费用；系统不会自动重试。

## 宝塔中填写 API Key
本项目服务器使用私密属性文件。宝塔“文件”打开：

`/www/wwwroot/trade exchange/.local/admin.properties`

在文件末尾添加（等号后填你自己的密钥，不加引号）：

```properties
# DeepSeek：在这里手动填写 API Key，不能提交 Git
app.ai.api-key=
```

保存后到宝塔“网站 → Java项目”重启 **jiaoyi-admin**。无需重启小程序接口服务。重新进入管理后台，助手将检查配置状态。空密钥只关闭助手，其他管理功能照常使用。

若使用环境变量部署，设置 `DEEPSEEK_API_KEY`；不要同时配置两个不同值。模型固定为 `deepseek-flash`，接口固定为 `https://api.deepseek.com/chat/completions`。密钥不下发网页、不写入应用日志或源码。

## 支持材料与限制
- PDF、DOC、DOCX、TXT、MD、CSV、RTF、XLS、XLSX、PPT、PPTX、JPG、JPEG、PNG。
- 每次一个文件，最多5MiB；文字最多60,000字符。
- PDF最多前20页；图片/扫描页最多6张。达到限制会提示，工作人员可拆分后继续。
- Office提取文字及表格单元格；Word/PowerPoint的可读取JPG、PNG内嵌图片也在6张限额内送交识别。图表、组合图形、其他图片编码不保证识别，需要时转成PDF或单独图片。不执行宏、不访问外链、不递归解析附件。
- 加密、损坏、压缩异常或无法解析的材料会提示转换格式。可解析但无文字/图像时打开空表单供人工填写。
- 解析子进程堆限制96MiB、超时25秒。模型请求最长约50秒、返回体最多128KiB；服务器每次处理一份材料，每分钟最多6次。
- 这是单机后台的限额；以后部署多副本时再改成共享限流。

## 规范与边界
固定提示词在 `src/main/resources/admin-ai-system.txt`。请求明确设置：
```json
{
  "model": "deepseek-flash",
  "thinking": {"type": "disabled"},
  "response_format": {"type": "json_object"},
  "stream": false,
  "max_tokens": 6144,
  "temperature": 0
}
```

只返回四类表单字段。服务端检查白名单、当前分类枚举、日期、金额、长度；非法部分留空，并保留合法部分。模型不能提供ID、版本、发布状态、排序、推荐、图片URL或关联目标。上传材料中的命令/提示词被视为不可信数据。助手没有数据库写入、搜索或工具调用能力。

提示词和类型校验不能保证事实准确，必须人工二审。未知必填字段需补齐后才能保存。旧保存接口的业务校验和管理员权限/CSRF继续生效。临时文件在完成或失败后清理，不新增数据库表，不保存完整文件或对话。

## 接口
- `GET /admin/api/ai/status`：仅管理员；返回配置是否存在、固定模型和格式限制。
- `POST /admin/api/ai/draft`：管理员会话及CSRF必需；JSON包含 collection/text/context/previousDraft。
- 同一路径 multipart：file为文件，request为上述JSON字符串。
- 返回 draft/missingFields/warnings/source，仍是未保存建议；不调用既有CRUD保存接口。

## 验证说明
本地用真实样本验证文档解析，用本地HTTP替身验证请求协议、稀疏字段、错误边界；替身不是 DeepSeek 实际效果验证。真实模型识别需用户填写密钥后，选一份非敏感材料完成“生成 → 人工核对 → 保存草稿 → 预览”的试录入。确认后再发布。

不需要SQL迁移；更新JAR并重启管理服务即可获得入口。部署记录和测试结果随本次交付补充。

官方资料：[Chat API](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/)、[图像输入](https://api-docs.deepseek.com/guides/vision/)、[PDFBox](https://pdfbox.apache.org/)、[Apache POI](https://poi.apache.org/)。

当前实现与服务器检查结果见 [2026-09-17验证记录](admin-ai-verification-2026-09-17.md)。
