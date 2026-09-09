# 后端二期计划：内容管理后台、首页接入与全站数据迁移

> 状态：二期代码与本地验证已实施；云端导入与发布待执行。日期：2026-09-09。
> 执行方式：按已确认计划由后端任务实施，并由已获授权的“云开发路线”任务同步前端。
> 本文同时保存需求、设计、接口和验收依据，避免两份方案相互漂移。

**目标：** 管理员在网页后台维护内容，小程序从 Java 接口读取；所有现有业务示例迁入云 MySQL，首页数字根据数据库中的有效资源计算。

**架构：** 复用 Spring Boot + MyBatis + MySQL。网页后台随同一代码仓库构建；为保护现有微信身份接口，同一产物分为小程序服务和管理服务两个部署入口。图片存云存储，数据库存内容和图片引用。

**技术栈：** 当前 Java 21、Spring Boot 3.5.16、MyBatis 3.0.5；后台用原生 HTML/CSS/JavaScript，身份与会话使用 Spring Security、Spring Session JDBC，版本由现有 Boot 管理。不引入另一套业务框架、Redis、消息队列或独立前端构建工具。

**需求依据：** 本文第 1 节记录的用户本轮请求和两个已回答的问题；一期用户识别与名片契约见 [README](../../../README.md)。

## 0. 实施结果与相对草案的调整（2026-09-09）

用户已授权开始改造，云端不能直接迁移时交付手动SQL。后端在隔离分支 codex/backend-phase2、基线060e33a实施；本任务旧前端worktree未改，前端协同由“云开发路线”在实际项目执行。

- 已实现：五类内容管理、首页/列表/分类/详情接口、数据库统计、网页后台、Spring Security/Session JDBC、COS上传接口、来源快照及手工SQL。具体上线步骤见 [部署说明](../../phase2-deployment.md)，实测证据见 [验证记录](../../phase2-verification.md)。
- 数据表采用公共发布/版本/排序控制列加校验JSON载荷，资源类型/供需/业务状态和推荐资源引用保留独立列与约束。内容查询复用现有Spring JDBC；一期MyBatis名片不改。
- 迁移产物固定93条资源、119条专区关联、4条推荐、5条广告、6条资讯、8家机构；来源与显式别名保留，不按展示次数重复计数。
- 初始统计为13/29/4/0/0/12，虚拟数字全部去掉。经理人/高校没有对应档案来源，暂为0。
- 3张旧机构PNG作为内置示意素材保留，未上传COS；新增图片由管理员配置COS后上传或填写HTTPS地址。它们是资源占位素材，不是仍在运行的业务假数据回退。
- 公网数据库此前SSL协商未通过。本次没有远程DDL/DML；用户按 migration/README.md 手动执行，不需要将数据库密码交给前端。
- 新内容默认草稿，已发布内容保存后立即更新；不引入草稿版本副本、定时发布、富文本HTML、多角色权限或企业入驻。
- 草案后续章节保留需求推导与原盘点时间点；与实际表结构/接口有差异时，以本节、DDL、部署说明及代码为准。

## 1. 已确认范围

1. 新增管理员使用的网页管理后台，可编辑、预览、发布、下架内容。
2. 今日主推的内容可以修改、增减和排序，不再写死四条。
3. 移动广告可修改文字、图片、详情、跳转和顺序；保留现有慢速自动移动、手动拖动和无缝循环。
4. “我是谁”的四个入口、样式、现有跳转本期不改。
5. 清除数据中心六项虚拟基数，从实际数据库计数；不提供手动填写统计数字的功能。
6. 政策资讯迁移：后台编辑，首页推荐、搜索、分类、列表、详情通过接口读取。
7. 全部现有业务示例迁入数据库。用户明确选择“迁移后全部作为正式内容展示并参与统计”，不按 isDemo 排除。
8. “全部迁移”包括各专区和机构列表的数据源切换，否则只把数据复制到数据库、页面继续读 JS，不能算完成。
9. 私人名片、需求/供给/反馈草稿不是公共示例内容，不纳入这次批量发布；用户和名片保持一期规则。
10. 当前板块名称是“首页 / 骊珠要素 / 骊珠转化 / 我的”，沿用最新前端名称。

**两处含义固定：**

- “归零”是去掉 2967、14246 等旧基数，改为 COUNT；正式导入后有多少有效条目就显示多少，不会始终为 0。
- “正式展示”是内容进入正式数据库和发布流程，不代表示例中的企业、临床阶段、专利、资质自动获得真实性认证。保留原文和来源，不把六篇原创文章标成政府政策，不自动增加“已认证”等徽标。

## 2. 本轮已核对的事实

### 2.1 仓库与边界

| 项目 | 当前路径 / 状态 |
| --- | --- |
| 后端实际仓库 | D:\codex_work\Wxcxk\Wx_yiyao，master，1dd8d0e，盘点时干净 |
| 前端实际仓库 | D:\codex_work\Wxcxk\yiyaodemo，codex/investor-page，b265987 |
| 前端现有改动 | project.config.json；未跟踪 .codex-transaction/，本计划不处理它们 |
| 当前任务的旧前端 worktree | C:\Users\19301\.codex\worktrees\9b7a\yiyaodemo；不是本轮前端事实来源 |
| 协同任务 | “云开发路线”，01a07ec5-ced2-7e50-a995-253616ff35c8 |
| 现有后端接口 | health、ready、auth/session、me/card，尚无首页或后台管理接口 |

后端现有 schema-mysql.sql 只定义 app_user、user_card。本文没有假定云库只有这两张表。

### 2.2 云数据库验证边界

- 用户指定外网地址：sh-cynosdbmysql-grp-lxqg6rn4.sql.tencentcdb.com:24924，目标业务库 yiyao。
- 2026-09-09 尝试 MySQL 客户端只读连接，强制 SSL，端点返回：
  ~~~text
  ERROR 2026 (HY000): SSL connection error: SSL is required but the server doesn't support it
  ~~~
- 尚未认证读取远端 schema、行数或版本，没有执行云端 DDL/DML；临时客户端凭据文件已清理。
- 迁移前优先经云内网执行；若本机通过公网连接，应先核对实例并启用 SSL、获取 CA，使用验证证书的连接。不能静默降级为明文连接。
- 腾讯云文档说明开启 SSL 可能重启实例，需要在迁移执行阶段安排可接受的操作时段，不能把它当作本轮已完成的配置。
- 云托管继续使用核验后的数据库内网地址，不能因为提供了外网地址就替换应用连接配置。

### 2.3 当前内容清单

| 内容 | 源文件 | 当前展示记录数 | 迁移处理 |
| --- | --- | ---: | --- |
| 今日主推 | pages/index/index.js：tradeProjects | 4 | 建成四条独立资源并关联推荐位 |
| 广告 | data/phase1.js：promos | 5 | 五条广告，循环复制项不落库 |
| 政策资讯 | data/phase1.js：policies | 6 | 六篇文章，正文与来源一起导入 |
| 骊珠要素 | data/phase1.js：projects | 12 | 与投资人项目列表共用实体 |
| 投资人专区 | data/investor.js：investorResources | 29 | 15 项目 / 6 MAH / 8 场景 |
| 企业专区 | data/enterprise.js：enterpriseResources | 40 | 人才6 / 技术7 / 专利9 / MAH6 / 数据6 / 服务6 |
| 科研专区 | data/scientist.js：scientistResources | 30 | 人才3 / 技术9 / 专利9 / 数据3 / 服务6 |
| 技术经理人专区 | data/manager.js：managerResources | 8 | 成果8 |
| 骊珠转化机构 | data/phase1.js：institutions | 8 | 八家机构，24 次图片引用 |
| 机构图片 | assets/phase1/*.png | 3 个不同 PNG | 上传一次，通过引用复用 |

资源清单共有 119 次展示引用，按来源关系合并后为 **89 条独立资源**；加上独立主推资源后为 **93 条**。另有推荐位 4 条、广告 5 条、资讯 6 条、机构 8 条。

去重规则必须明确到来源：

- 骊珠要素的 12 条项目被投资人专区直接复用，保持同一资源 ID。
- 科研专区从企业专区复用人才 3 条、专利 9 条、服务 6 条，共 18 条；前端改过 ID 前缀。导入清单为这 18 条建立明确别名。
- 不允许对所有 scientist- ID 直接替换为 enterprise-。scientist-data-other 和 enterprise-data-other 是不同内容，不能合并。
- 保留每个专区的分类和 sortOrder；不能用全局 sortOrder 覆盖专区顺序。
- 同一资源的投资人扩展字段合入主记录，不能丢失 projectType、researchStage、indications。
- 主推 4 条当前并非骊珠要素中的 4 条同名资源，不按数组序号错误关联。

独立资源类型计数：project 15、mah 12、scene 8、talent 6、technology 16、patent 9、data 9、service 6、achievement 8。此清单是当前源码快照，执行前须重算，不能作为永久固定数量。

### 2.4 不迁成数据库记录的内容

- tab 标题、图标、CSS、广告动画布局、表单验证规则。
- “全部”等筛选占位值、页面按钮文案；筛选枚举作为服务端受控字典发布，非业务实体。
- .codex-transaction/、测试夹具、历史文档中的示例。
- 本机用户草稿和名片缓存，不静默公开或覆盖云端资料。
- 科研专区“投融资”仍是已有占位，保持现状，不凭空造记录。

## 3. 两个交付阶段

| 阶段 | 用户可看到的结果 | 完成条件 |
| --- | --- | --- |
| 二期 A：首页管理闭环 | 登录网页后台，修改主推/广告/资讯，发布后小程序读取新内容；数据中心按已入库资源计数 | 数据模型、后台登录、媒体、首页读取、内容发布及真机验收通过 |
| 二期 B：全部业务数据迁移 | 骊珠要素、骊珠转化、四个专区均读云端；内容可在后台维护 | 全量清单导入、去重、搜索筛选分页及详情接入，运行页面不再读取本地示例数组 |

A 是中间里程碑，B 完成后才称“二期全部完成”。A 阶段未迁入的资源不提前计数。可在 A 的迁移窗口一次导入完整已核验清单，随后按页面逐步切换；最终数字以已发布数据库记录为准。

## 4. 网页后台的最小功能

### 4.1 菜单

- 首页管理：今日主推、广告管理。
- 政策资讯：文章列表、编辑、预览、发布、下架。
- 资源管理：项目/需求、MAH、场景、人才、技术、专利、数据、服务、成果。
- 机构管理：骊珠转化的机构资料和图片。
- 数据概览：只读六项计数和计数说明。
- 操作记录：查看谁在什么时间修改、发布或下架了哪条内容。

一个管理员角色，不做部门、复杂权限树、多级审批。二期不含普通用户在线投稿、报名、交易、支付或入驻审核。

### 4.2 编辑与发布规则

| 对象 | 可编辑内容 | 发布规则 |
| --- | --- | --- |
| 今日主推 | 选择资源；编辑该资源标题、类型、金额、标签、地区、封面和正文；推荐位排序 | 主推引用资源，不复制业务内容。关联资源必须已发布且处于 open |
| 广告 | 角标、标题、副标题、图片或现有色彩模板、按钮文案、正文、排序、动作 | 默认打开广告自身专题；也可关联现有资源或政策；不接受任意 JavaScript/小程序路由 |
| 政策资讯 | 标题、摘要、分类、地区、正文段落、日期、来源名称、来源链接、是否首页推荐、排序 | 来源与正文同时保存。六篇旧文章保留来源说明；官方转载需要填写可核对的原文链接 |
| 业务资源 | 通用信息、对应分类字段、展示专区及顺序 | 服务端按分类白名单验证，不能提交任意字段/SQL |
| 机构 | 名称、简介、行业、服务标签、地区、图片、分节正文 | 图片引用必须有效；发布不等于认证机构资质 |

- DRAFT / PUBLISHED / OFFLINE 表示平台发布状态；open / closed / withdrawn 保留原有业务状态，二者分开。
- 保存已发布内容时明确提示“保存后立即更新线上内容”；预览只渲染未保存表单。二期不做多版本草稿系统。
- 采用 version 乐观锁；编辑旧版本返回 409，保留输入并提示重新加载，避免覆盖另一浏览器的编辑。
- 下架优先，不提供硬删除业务记录按钮。下架被主推引用的资源后，首页自动排除相应推荐位；广告的失效跳转同步停用并显示原因。
- 新建默认 DRAFT。种子迁移按用户决定发布，原 withdrawn 资源保持 OFFLINE，不自动重新上架；原 closed 记录可作为结束记录展示，不计有效资源。
- 原始来源说明不批量删改；来源标记与发布状态独立。UI 中写死的“全部为演示”改为逐条来源说明，不错误声称所有新内容也都是演示。当前主推模板无条件显示的 V 标记不能继续暗示已认证；没有审核依据时不显示认证标记。
- 正文沿用 sections[{heading,paragraphs[]}], 以文本渲染，不接受任意 HTML。足以满足当前资讯排版，避免先做富文本系统。

## 5. 后端结构与访问入口

### 5.1 部署选择

**推荐：同一仓库、同一镜像，两种运行配置。**

| 服务 | 访问方式 | 允许内容 |
| --- | --- | --- |
| 现有 springboot-olrl | 小程序 wx.cloud.callContainer 私有链路 | 内容读取、现有微信身份与名片 |
| 拟新增 yiyao-admin | 管理员浏览器 HTTPS | 后台页面、管理员认证、受保护的管理 API |

- 使用明确的 app.mode=mini/admin；已有服务默认 mini，admin 启动时必须禁用微信身份信任。
- admin 模式不注册 /api/auth/session、/api/me/**；小程序模式不启用管理页面或管理写接口。health/ready 提取为公共健康控制器。
- 管理服务不需要读写 app_user、user_card 的数据库权限，使用独立账号。
- 原服务继续关闭未经认证的公网直达入口。不能为了打开后台而直接把所有现有身份接口公开。
- 备选方案是精确路由的管理 HTTP 网关，仅暴露后台路径；当前入口规则未核验，二期不以它作为安全前提。
- 这会新增一个管理服务的运行成本；不预先承诺免费额度或具体月费，部署时按实际规格核对。

此设计基于已有“可信云入口头识别身份”的约束，避免为管理后台重做小程序登录。云托管公网访问本身不提供业务鉴权，且关闭服务公网开关不会关闭独立 HTTP 网关，依据见第 12 节。

### 5.2 管理员认证

- 使用 Spring Security 的账号密码登录；初期一个受控管理员，不开放注册。
- 管理员用户名及密码散列通过服务端私有配置提供，无默认密码，不使用数据库 root 作为网页登录账号。
- Session 使用 Spring Session JDBC 的标准表，避免多容器实例之间登录状态丢失；不自建 JWT/Redis。
- Cookie 设 HttpOnly、Secure、SameSite=Lax；30 分钟无操作过期，退出销毁会话，登录后更新会话 ID。
- 所有修改和上传需要管理员权限与 CSRF token；API 未登录返回 JSON 401，权限不足 403，不能 302 返回登录 HTML冒充 JSON。
- 登录失败限速、错误信息统一；登录密码、session、名片不写日志。限速状态跨管理实例共享，不依赖进程内 Map。
- 后台和接口同域，不开放任意 CORS。未经认证的资源预览、文件上传均拒绝。
- 管理权限绝不由客户端 userId、openid、X-WX-* 或“选择技术经理人”决定。

### 5.3 媒体处理

- 图片二进制放云存储/COS，MySQL 仅保存对象 key、文件类型、字节数、尺寸和公开展示地址。
- 使用服务端存储凭据/受限身份；执行时核实本环境可用授权方式，不假定开通云开发就自动有 COS 写权限。
- 管理上传接口只接收 JPEG/PNG/WebP，最大 5 MB，校验真实文件类型与像素尺寸，随机对象 key。
- 不接收 SVG/HTML、任意远程抓取地址或覆盖已有对象的路径；图片替换用新 key。
- 3 张旧 PNG 去重上传；图片失败保留现有色彩/占位图降级。
- 小程序图片读取使用核验过的 HTTPS 地址及域名配置。临时过期 URL 不能作为永久内容字段保存。
- 上传成功但内容保存失败时标记未引用媒体，后台能查看；二期不自动批量删云文件。

## 6. 数据模型

新增表均为增量迁移，utf8mb4；ID 使用稳定字符串，UTC 时间。下表是具体设计，不是当前云库的已验证结构。

| 表 | 主要字段 / 约束 |
| --- | --- |
| content_resource | id VARCHAR(96) PK、resource_type、kind、publisher_role、issuer、title、summary、city、region、amount_wan DECIMAL(14,2) NULL、amount_label、industries/tags/cooperation_modes JSON、attributes JSON、sections JSON、cover_media_id、tone、business_status、publication_status、source_kind、source_note、published_at、created_at、updated_at、version、import_batch_id |
| content_resource_view | resource_id FK、audience、category、sort_order、legacy_id；PK(audience,legacy_id)，UNIQUE(audience,resource_id) |
| home_featured | id、resource_id FK UNIQUE、sort_order、publication_status、version、updated_at |
| home_promo | id、eyebrow、title、description、tone、media_id、button_text、action_type、target_id、sections JSON、sort_order、publication_status、version、updated_at、import_batch_id |
| policy_article | id、title、summary、category、region、display_date、source_kind、source_name、source_url、sections JSON、home_recommended、sort_order、publication_status、published_at、version、updated_at、import_batch_id |
| service_institution | id、name、summary、region、industries/service_tags/sections JSON、sort_order、publication_status、version、updated_at、import_batch_id |
| institution_media | institution_id FK、media_id FK、sort_order；同机构同媒体唯一 |
| content_media | id、object_key UNIQUE、mime_type、byte_size、width、height、created_at、created_by |
| content_audit | id、actor、operation、entity_type、entity_id、before_version、after_version、changed_fields JSON、created_at；与内容写操作同一事务 |
| content_import_batch | id、manifest_sha256 UNIQUE、source_commit、record_counts JSON、status、started_at、finished_at |
| 标准会话表 | SPRING_SESSION、SPRING_SESSION_ATTRIBUTES；使用 Boot 匹配版本自带 MySQL SQL，不能手写缩水版 |
| admin_login_attempt | account_key、window_start、failure_count、locked_until；失败限速，不存密码或完整请求 |

**模型边界：**

- 通用字段独立列；分类差异放受验证的 attributes JSON。避免给所有分类预造数十张扩展表。
- 分类字典由服务端一个受控 catalog-schema.json 管理并提供接口；二期不做“在线编辑筛选结构”的后台。
- 当前量级用 MySQL 分页/过滤，无全文搜索集群。大文本正文不随列表全部返回。
- JSON 字段同样验证类型、枚举、长度；MyBatis 只使用绑定参数，sort 字段使用固定 SQL 分支，禁止用户输入拼接 SQL。
- 资源 ID 来自服务端或受控导入清单；重复展示通过 view 表关联，不生成新实体。
- 名片八字段、用户唯一身份约束保持原样，迁移不 ALTER/DROP 这两张现有表。
- 为 publication_status、resource_type/kind、published_at、关联表 audience/category/sort_order 建必要索引；不给每个 JSON 字段预建索引。

**统一输入边界：**

- 标题/机构名 120 字，摘要 500 字，角标/按钮文案 20 字，来源名/发布方 120 字，地区/城市 50 字。
- industries/tags/cooperationModes 每项最多 30 字、每组最多 12 项；sections 最多 20 节，每节 heading 100 字、最多 10 段、每段最多 2000 字，总正文最多 30000 字。
- 正常内容 JSON 请求最大 256 KB；金额可空或非负，不能用浮点数计算/存储；“面议”由 null 派生。
- sourceUrl 允许经校验的 HTTPS URL，最长 2048 字；来源链接不由服务器自动抓取。
- sortOrder 非负整数；重复排序值以稳定 ID 作第二排序键。
- 所有日期需真实存在且格式正确；只保留一期已用枚举，不为缺少最高研发阶段的旧项目编造阶段。
- 超界 400、未登录 401、无权限 403、不存在或已下架详情 404、版本冲突 409、过大 413、限速 429、依赖故障 503。

## 7. 数据中心口径

共同条件：publication_status=PUBLISHED 且 business_status=open，按资源主记录去重。不按展示次数、推荐位数量、注册账号数统计。UI 补充“按在架资源条目统计”。

| 保留的名称 | 建议固定口径 | 当前源码按此口径推算的迁入后数量 |
| --- | --- | ---: |
| 技术需求 | kind=demand 的有效需求条目，含投资人所发 MAH/场景需求 | 13 |
| 科技成果 | kind=supply 且类型 project / technology，或 achievementType=技术成果 | 29 |
| 行业专家 | talent 且 talentMaturity=资深专家；统计专家资源条目，不等于认证人数 | 4 |
| 技术经理人 | 当前没有技术经理人个人档案实体；固定返回缺少来源的 0，不能拿八条“成果”替代人数 | 0 |
| 高校院所 | 当前没有高校/科研院所类型档案；不能把八家服务平台全部算成高校 | 0 |
| 技术专利 | patent 类型中排除软著，加成果分类的专利包/纯专利权利；一份资源条目记 1 | 12 |

- 上述 13 / 29 / 4 / 0 / 0 / 12 是方案演算：89 条合并资源 + 4 条主推，关闭/下架不计，尚非云端查询结果，也不能写成前端常量。
- 若管理员修改类别或上下架，下一次读取立即变化；同一资源在两个专区显示不加二。
- “专利包记 1 条”是内容条目口径，不能显示成专利授权件数；不会从标题推测包内数量。
- MAH、场景、数据、服务等资源只有符合六项定义时才计入对应项，不能把所有供给统一称为科技成果。
- 专家来源仍如实说明。计数可包含迁入的原示例，不代表资质已核验。
- 技术经理人/高校院所的 0 是领域数据源缺失，不创建假档案填数；将来建设对应档案模块后再接入计数。
- A 阶段以实际已导入数据为准；空数据库响应 0，数据库故障返回 503，不伪装成业务清零。
- 本轮只改统计来源与口径说明，暂不新增六个统计详情页。现有点击去骊珠要素的行为不据此声称已实现六类资源导航。

后端一次数据库事务内读首页，数据中心 COUNT 与首页内容取同一数据快照；先不加定时汇总或手动刷新统计任务。

## 8. 前后端接口契约

### 8.1 公共规则

沿用一期响应：{"code":0,"errorMsg":"","data":...}，HTTP 状态与错误一致。下列均为**拟新增接口**，未部署。

列表默认 page=1、pageSize=20，pageSize 上限 50；返回 items、total、page、pageSize。page 从 1 开始，搜索 keyword 最多 100 字。默认稳定按专区/内容 sortOrder,id；newest 按 publishedAt DESC,sortOrder,id。政策日期排序用 displayDate DESC,sortOrder,id。

内容端无需先调用用户登录。服务入口仍为已授权小程序的 callContainer；浏览内容不自动创建 app_user。

| 方法 | 路径 | 返回 / 说明 |
| --- | --- | --- |
| GET | /api/home | featured、promos、stats、policies(首页推荐最多3条)、updatedAt |
| GET | /api/promos/{id} | 广告专题正文与动作；未发布返回404 |
| GET | /api/policies | keyword、category、page、pageSize；搜索标题/摘要/分节正文 |
| GET | /api/policies/{id} | 文章、来源和分节正文 |
| GET | /api/catalogs/{audience} | categories、filtersByCategory；audience 仅 pool/investor/enterprise/scientist/manager |
| GET | /api/resources | audience 必填；category、keyword、现有分类筛选、sort、page、pageSize |
| GET | /api/resources/{id} | 主记录详情；canonicalId 为主，也允许 audience 指定专区后将路径中的旧ID按受控别名解析 |
| GET | /api/institutions | industry、keyword、page、pageSize |
| GET | /api/institutions/{id} | 机构正文和图片 |
| GET | /api/stats | 与 /api/home 完全相同口径的六项统计 |

首页示例形状（示例中的空数组表示形状，不代表迁移结果）：

~~~json
{
  "code": 0,
  "errorMsg": "",
  "data": {
    "featured": [],
    "promos": [],
    "stats": {
      "technicalDemands": 0,
      "achievements": 0,
      "experts": 0,
      "technologyManagers": 0,
      "universities": 0,
      "patentResources": 0
    },
    "policies": [],
    "updatedAt": "2026-09-09T00:00:00Z"
  }
}
~~~

- featured 元素：id、resourceId、title、kind、amountWan、amountLabel、tags、city、tone、imageUrl。title/金额等来自资源，id 是推荐位 ID。
- promos 元素：id、eyebrow、title、description、tone、imageUrl、buttonText、action:{type,targetId}。正文走详情接口；type 为 article/resource/policy/none。
- policies 元素：id、title、summary、category、date、region、sourceKind、sourceName、sourceUrl；列表不返回 sections。
- 资源列表保留 id、category、kind、publisherRole、issuer、title、summary、industries、tags、cooperationModes、amountWan、amountLabel、city、region、status、publishedAt、sortOrder；attributes 承载现有分类字段，前端适配展开。
- 列表资源 id 是 canonicalId；legacyId 仅用于兼容旧页面状态。不能要求用户传 userId 读取内容。
- 枚举和筛选项原样迁移。全部分类的排序/搜索保留；manager 没有排序下拉时，后端仍给稳定默认顺序。
- 多筛选条件是 AND；数组字段如 indications/industries 用包含判断。切换分类不会夹带上一个分类不适用的筛选键；未知或跨分类键返回400。
- 超出页码返回 items=[] 且 total 为真实总数；不能返回首页列表充当完整列表。

### 8.2 管理接口

管理服务提供 /admin/ 页面。Spring Security 的登录提交使用 form POST（application/x-www-form-urlencoded），JavaScript 接收统一成功/错误结果。匿名 GET /admin/api/session 返回 HTTP200、authenticated:false 和 CSRF token，登录提交必须携带该 token；它不授予管理权限。登录后的 GET 返回 authenticated:true 和管理员名称，其他管理 API 未登录返回401。

| 路径 | 方法和用途 |
| --- | --- |
| /admin/api/session | GET 管理员状态与 CSRF token；POST 建立登录会话；DELETE 退出 |
| /admin/api/resources | GET 列表，POST 新建 |
| /admin/api/resources/{id} | GET 编辑数据，PUT 保存(含version) |
| /admin/api/featured、/admin/api/promos、/admin/api/policies、/admin/api/institutions | GET 列表，POST 新建 |
| 对应集合路径/{id} | GET 编辑数据，PUT 保存(含version) |
| 对应集合路径/{id}/publication | PUT {"status":"PUBLISHED或OFFLINE","version":整数} |
| /admin/api/media | POST multipart 图片上传，GET 媒体列表 |
| /admin/api/stats | GET 只读统计与口径，无 PUT |
| /admin/api/audit | GET 操作记录分页 |
| /admin/api/catalogs/{audience} | GET 后台表单需要的受控分类字典 |

后台使用同源请求带 Cookie/CSRF，Mini Program 不调用管理接口。发布与审计同事务；数据库失败时不能出现“页面显示已发布但未保存”的状态。

### 8.3 前端改动范围与协同约定

**修改前告知用户的范围：**

- services/backend.js：复用 callContainer 初始化、请求和响应校验，补内容读取方法；错误信息从“名片内容”拆成通用错误和名片专用提示。
- pages/index/index.js / .wxml / .wxss：首页异步内容、状态提示、正确的主推资源详情跳转；政策分页/详情；B 阶段切换骊珠要素和机构数据源。
- pages/investor/investor.js / .wxml：B 阶段只改数据请求、分页、详情与来源说明；各专区入口、筛选样式、返回方式不重设计。
- data/phase1.js 等：保留动画与必要纯函数/字典，业务种子移出小程序运行代码；测试夹具可以保留在 tests/fixtures。
- pages/mine-detail/mine-detail.wxml：只更新“关于”的数据来源说明，登录、名片和私人草稿行为不改。
- tests/home.test.js、backend.test.js，以及受影响的 investor/enterprise/scientist/manager 测试：更新为接口行为和来源说明。

**界面行为：**

1. 首次进入读 /api/home；页面显式 loading、empty、error+retry。成功空列表和网络失败分开。
2. 请求失败不得恢复源码假数据；保留本次会话已成功取得的内容并标明更新失败。
3. 首页显示/刷新后重新获取内容；内容 API 先用 no-store，验收发布后下一次刷新可见。
4. 广告数据更新后重新计算 buildPromoLayout；保留 24 CSS px/秒、手指暂停、拖拽和恢复、循环三组渲染，不落库渲染副本。
5. featured 点击用 resourceId 获取对应详情，修正现有 openTradeProject 忽略 ID 仅去列表的问题。
6. 搜索/筛选的旧请求晚返回时不能覆盖新条件结果；分页去重；详情返回保留分类、关键字和滚动状态。
7. 窄屏与真机验证要单列结果，Node 测试通过不等于手势验证通过。
8. 协同消息以本计划第 8 节为接口草案；实施前写同一份定版契约，不能前后端各自改字段名。未提供真实接口前的 stub 仅测试使用，不能发布成运行回退路径。

## 9. 数据迁移步骤与回滚

### 9.1 执行顺序

1. 核对两个仓库 HEAD 和未提交文件；导出带 SHA-256 的源清单，包含旧 ID → 主 ID → 展示专区映射。
2. 经加密公网或受控云内网连接，读取实际版本、表结构、约束、精确行数；记录只读结果，不导出名片内容到日志。
3. 创建可恢复的云备份并在专用测试库验证恢复；记录备份标识。现有库缺少预期一期表时先停下核对目标，不能换个空库假装成功。
4. 在测试库应用增量建表 SQL；导入执行器默认 dry-run，显示新增/冲突/跳过/别名数量和来源。
5. 上传 3 张 PNG，验证 URL、文件散列、24 次机构引用；媒体完成后才在内容事务中写引用。
6. 按外键顺序写资源→专区关联→机构与图片关联→政策→广告→主推。内容导入使用事务与 import_batch_id。
7. 幂等：相同 manifest hash 不重复导入；遇到已存在但值不同的业务 ID 报冲突，不用 ON DUPLICATE KEY UPDATE 静默覆盖管理员修改。
8. 导入所有业务记录，按发布规则激活；1条旧 withdrawn 保持下架、1条 closed 保持结束，其他 open 可计数。
9. 核对主记录数、专区关联数、空正文/空选项合法性、图片引用、每一项统计和详情链接。
10. 后端先部署并通过接口检查，再由“云开发路线”切换前端；删除运行期种子依赖后完成真机验收。

### 9.2 切换与回滚

- 所有表结构变更只增量新增，不删库、不清表，不用线上业务库跑测试。
- DDL 可能自动提交，不能声称 ROLLBACK 能撤销建表；建表验证与内容事务分阶段执行。
- 代码回滚到上一版本；数据按明确 import_batch_id 回滚且仅限尚未发生用户编辑的该批记录。已有用户修改则报告冲突，不能覆盖。
- 上线后出现内容问题优先下架相关记录；不要恢复整个数据库从而丢失新名片或后台编辑。
- 前端回滚使用明确上个发布包，不在运行时静默切回假数据。
- 迁移凭据和云备份留在私有位置；Git 只保存脱敏清单、脚本、验证结果。
- 导入脚本不随服务启动自动执行，不能每次重启重新灌种子。

## 10. 按顺序实施的任务

### 任务 1：冻结清单与接口

**文件：** 新增 scripts/export-phase2.cjs、migration/phase2-manifest.json、docs/round2-verification.md；维护本文。

- [ ] 导出源码业务数组，移除循环渲染项；为 12+18 条共享关系生成显式映射。
- [ ] 核验独立资源 89、主推资源 4、广告5、政策6、机构8与源 SHA；源码若变化重新计算并记录差异。
- [ ] 将所有筛选定义从源码导出到 src/main/resources/catalog-schema.json；不把 JS 函数或组件样式当字典内容。
- [ ] 前后端确认第8节字段和失败状态，输出契约 fixtures；这一步不改线上功能。

可执行导出检查应包含以下核心断言：

~~~javascript
const assert = require('node:assert/strict');
const manifest = require('./migration/phase2-manifest.json');
// 该检查从后端仓库根目录运行；清单需先由导出任务生成。
assert.equal(manifest.canonicalResources.length, 93);
assert.equal(manifest.promos.length, 5);
assert.equal(manifest.policies.length, 6);
assert.equal(manifest.institutions.length, 8);
assert.notEqual(manifest.aliases['scientist:scientist-data-other'],
                manifest.aliases['enterprise:enterprise-data-other']);
assert.equal(new Set(manifest.canonicalResources.map(x => x.id)).size, 93);
~~~

以上数量用于当前快照测试，后续业务新增不能依赖这些常量计数。

### 任务 2：增量表与导入

**文件：** 新增 src/main/resources/migration/002-content.sql、003-admin-session.sql；scripts/import-phase2.ps1；src/test/java/com/tencent/wxcloudrun/ContentMigrationTest.java。

- [ ] 在专用 MySQL 测试库执行 DDL 和 dry-run；检查一期表结构未被改变。
- [ ] 实现导入批次、别名和冲突检查；第二次导入相同清单新增0。
- [ ] 注入第N条写入错误，验证该内容批事务没有半成品；媒体孤立记录可追踪。
- [ ] 记录精确行数与清单 hash；提交仅本任务文件。

### 任务 3：内容读取与统计

**文件：** 新增 controller/ContentController.java、service/ContentService.java、dao/ContentMapper.java、resources/mapper/ContentMapper.xml；src/test/java/com/tencent/wxcloudrun/ContentApiTest.java。

- [ ] 实现第8节首页、资源/机构/政策列表详情与字典接口，沿用 ApiResponse。
- [ ] 字段校验、白名单排序、参数化 SQL、分页和下架404。
- [ ] 六项数字从去重资源计算，空库0、依赖错误503；推荐关联不重复计数。
- [ ] 检查 “相同资源两个专区→统计不变”，“新增有效需求→+1”，“关闭需求→-1”，“删除推荐位→需求计数不变”。
- [ ] 一期用户隔离与名片重启恢复测试仍通过。

### 任务 4：后台认证与入口隔离

**文件：** 修改 pom.xml、application.yml、ProfileController.java；新增 config/AdminSecurityConfig.java、controller/HealthController.java、resources/application-admin.yml、src/test/java/com/tencent/wxcloudrun/AdminSecurityTest.java。

- [ ] 引入 Boot 管理版本的 Security/Session JDBC；只在 admin 配置启用管理认证和标准会话表。
- [ ] 明确每个模式的允许路由；未知 app.mode 启动失败。健康检查可用，旧模板计数器仍不开放。
- [ ] mini 模式保留一期 cloud 头识别；CSRF 只对管理会话适用，不让新增 Security 默认登录屏障拦截 callContainer。
- [ ] admin 模式 /api/me/card 与 /api/auth/session 不可用，即使请求伪造完整 X-WX-* 头。
- [ ] 未登录写接口401、无CSRF403、退出后旧Cookie失效、多实例会话有效、失败登录限速。
- [ ] 公网管理实例与私有小程序实例使用不同最小权限数据库账号。

### 任务 5：首页发布管理与媒体

**文件：** 新增 controller/AdminContentController.java、service/AdminContentService.java、dao/AdminContentMapper.java、resources/mapper/AdminContentMapper.xml、service/MediaService.java、resources/static/admin/index.html、admin.js、admin.css、src/test/java/com/tencent/wxcloudrun/AdminContentTest.java。

- [ ] 实现主推/广告/政策的普通表单、预览、保存、排序、发布、下架；没有 JSON/SQL 编辑器要求给普通管理员操作。
- [ ] 实现图片上传与验证、机构旧图片引用；正文用安全文本段落渲染。
- [ ] 校验目标记录和版本；非法外链动作、过大文件、伪造类型、旧版本提交都有明确失败。
- [ ] 发布/下架与审计同事务；后台实时读取统计，无修改数字按钮。
- [ ] 浏览器实际完成管理员登录→编辑广告→发布→刷新读取→下架→确认内容消失。
- [ ] “云开发路线”接入首页四个动态区块，验收二期A。

### 任务 6：全站迁移与后台资源管理

**文件：** 扩展上述内容管理文件；前端由“云开发路线”修改第8.3节列出的实际工作仓库文件。

- [ ] 资源编辑按 catalog-schema 提供匹配字段；维护专区关联，不新增一条数据就复制到多个表。
- [ ] 机构管理完成8条机构及图库。
- [ ] 全量内容迁移并对账，保留原有结束/下架状态和逐条来源。
- [ ] 骊珠要素、骊珠转化和各专区使用查询接口；全部分类、AND筛选、分页、返回恢复保持。
- [ ] 运行期移除所有公共示例数据数组依赖；演示 fixtures 仅留测试。
- [ ] 关于页面正确描述云端内容与本机草稿，登录/名片未回归。
- [ ] 按第11节完成验收，才宣布二期B与全量迁移完成。

### 任务 7：云端上线与恢复验证

**文件：** 更新 README.md、.env.example、Dockerfile（仅必要配置）、docs/round2-verification.md；不提交实值密码。

- [ ] 记录实际服务名、版本、数据库迁移批次、备份标识和媒体清单。
- [ ] 用受控链路完成云库盘点和迁移；管理服务新建只在执行阶段进行，不提前开公网/改原服务入口。
- [ ] 后端发布后读接口核对，再接前端预览；不自动把小程序提交正式审核。
- [ ] 从外网验证 admin 不提供私人接口；从真机验证 mini 可识别当前用户并恢复名片。
- [ ] 验证重启后内容、管理员会话策略、计数和名片符合预期；记录可用回滚版本。
- [ ] 每阶段本地提交，推送/云发布按当次用户授权执行，不能将文档提交等同部署完成。

## 11. 验收清单

- [ ] 后台改主推标题/金额/封面并发布，小程序显示修改，点击进入同一资源详情。
- [ ] 广告增至6张仍自动连续循环且可手拖；1张、0张、图片失败均正常。
- [ ] 新建/编辑政策可搜索、按分类查到，详情来源正确；下架后首页、列表与直达详情都不可见。
- [ ] 数据中心不含旧基数，不手动填数；新增、关闭、下架、重复专区引用的变化均符合第7节。
- [ ] 所有种子有稳定 ID、导入批次和来源；重复导入不重复创建，管理员修改不被种子覆盖。
- [ ] scientist-data-other 与 enterprise-data-other 保持独立；18条共享资源不重复计数。
- [ ] 列表 total 来自后端，分页不重不漏；旧请求不会覆盖新筛选；回详情再返回条件不丢。
- [ ] “我是谁”入口与底栏、各专区UI保留；手机号授权和入驻没有被顺带改动。
- [ ] 所有 public 内容读取失败时无本地假数据回退；错误不显示成全站0。
- [ ] 未授权管理调用失败、CSRF失败、版本冲突、上传失败均验证；日志不含密码或私人名片。
- [ ] 一期 session / me/card 测试通过，云端不同用户名片互相隔离。
- [ ] 375px/430px 模拟器、管理员桌面浏览器和至少一台真机分别记录验收证据。
- [ ] 云端备份、迁移对账、实际服务版本和回滚步骤已写入 round2-verification.md。

建议验证命令（在实际仓库执行；pwsh7）：

~~~powershell
# 后端使用已验证的本机 JDK/Maven；命令本身不部署。
$env:JAVA_HOME = 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.1\jbr'
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.1\plugins\maven-plugin\lib\maven3\bin\mvn.cmd' '-Dmaven.repo.local=D:\codex_work\Wxcxk\Wx_yiyao\.local\m2' --batch-mode --no-transfer-progress clean verify
if ($LASTEXITCODE -ne 0) { throw '后端检查失败' }
git diff --check

# 前端接入后，前端任务运行全部现有回归。
Get-ChildItem -LiteralPath tests -Filter '*.test.js' | ForEach-Object {
  & node $_.FullName
  if ($LASTEXITCODE -ne 0) { throw ('前端测试失败：' + $_.Name) }
}
~~~

以上是执行阶段命令，不是本轮已通过的新增功能测试。MySQL JSON/真实 SQL 需要专用 MySQL 测试，不能仅用 H2 证明兼容。

## 12. 文档依据与本轮完成边界

2026-09-09 核对的官方依据：

- [CloudBase 服务设置](https://docs.cloudbase.net/run/deploy/service-setting)：关闭公网不影响 callContainer，公网与内网配置不同。
- [CloudBase 公网访问](https://docs.cloudbase.net/run/deploy/networking/public)：服务需自行实现业务鉴权。
- [CloudBase 入口关系](https://docs.cloudbase.net/run/related)：服务公网与 HTTP 网关是独立入口。
- [MySQL 数据库集成](https://docs.cloudbase.net/run/develop/resource-integration/mysql)：核对业务库与云托管连接配置。
- [TDSQL-C 设置 SSL](https://intl.cloud.tencent.com/zh/document/product/1098/63090)：SSL开启、CA证书与可能重启的操作说明。
- [Spring Session JDBC](https://docs.spring.io/spring-session/reference/configuration/jdbc.html)：使用数据库保存标准会话；执行依赖版本使用项目 Boot BOM，不照抄文档最新主版本。

**本轮完成：** 当前代码盘点、只读数据库握手验证、需求决策记录、二期设计和接口草案、向前端任务发送同步说明。协同工具确认盘点轮次结束，但未返回可读取的盘点正文；本文的数量与结构来自本任务对实际前端仓库的直接核验，不声称前端已经确认或实施契约。

**本轮未完成：** 新接口编码、网页后台、云库表结构读取/改造、种子导入、图片上传、云部署、小程序接口实际接入或真机验收。实施从任务1开始，数据库迁移须先解决第2.2节的连接前提。
