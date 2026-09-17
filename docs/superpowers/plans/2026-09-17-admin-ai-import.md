# 后台 AI 导入助手实施计划

**目标：** 管理员将 PDF、Word 等材料或文字交给 DeepSeek，生成资源、政策、广告、服务机构的待复核表单。人工保存、人工发布，今日主推仍关联既有资源。

**架构：** 复用 Spring Boot 管理员会话、CSRF、分类字典、现有编辑器和保存接口。文件在有内存/时间限制的 Java 子进程提取；服务端固定调用 DeepSeek Flash，关闭思考，随后按字段白名单及分类枚举校验。无新表、无自动发布、无小程序修改。

**技术选择：** JDK HttpClient/Jackson；Apache PDFBox 3.0.8 与 POI 5.5.1。它们分别维护 PDF/Office 解析；未选 Tika 全家桶、Agent 框架或独立 OCR 服务。扫描页按限额转图交给模型。官方参考：https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/ 、https://api-docs.deepseek.com/guides/vision/ 、https://pdfbox.apache.org/ 、https://poi.apache.org/ 。

## 实施与验收
- [x] 文件解析：5MiB 文件、60,000 字文本、20页PDF/最多6张视觉页，损坏/加密/超限明确提示、临时文件清理、子进程超时和堆限制。
- [x] 字段提取：四类白名单、使用现有分类，缺失留空，非法枚举剔除，金额/日期校验。
- [x] 模型接口：API Key 仅服务端；不记录材料/密钥；关闭思考、JSON输出、限制输出/耗时/请求频率；失败不自动重试。
- [x] 管理界面：文件/文字入口、结构化例子、补充修改、自动打开待复核表单、保留人工内容，保存发布仍人工执行。
- [x] 验证：解析真实文件样本、模型协议本地替身、认证/CSRF/mini隔离、现有Node编辑器回归、打包后worker启动。
- [ ] 交付：配置文档、API Key 明确位置、Git本地提交和master推送；真实 DeepSeek 调用等待用户自行配置 Key。

## 明确边界
AI只建议字段。提示词不能保证事实正确，分类/类型校验也不能替代人工审核。默认不长期保存导入文件或对话；模型不获取数据库、浏览器或发布工具。上传内容会发送到 DeepSeek，界面会事先标明。已有后台业务保存校验继续生效。
