# 二期内容迁移包

本目录是已核对的原前端内容快照和手工导入材料。它不会随 Spring Boot 启动自动执行。000～003 为此前导入包；用户已执行的云端导入不应重复。本轮新增 004 仅在本地专用测试库验证，尚未在云库执行。

## 文件与顺序

| 文件 | 用途 |
| --- | --- |
| `000_preflight.sql` | 只读检查当前数据库、版本、一期表存在性和表结构 |
| `001_schema.sql` | 增量内容建表，末尾自动附加标准管理会话及登录限流 DDL |
| `002_seed.sql` | 默认 dry-run 的内容导入；预检冲突、事务写入、完整性与审计校验 |
| `004_enterprise_categories.sql` | 最终企业分类的增量 JSON 属性迁移；无表结构变更；在已导入业务库单独执行 |
| `003_verify.sql` | 只读核对批次、条数、别名、来源、图片引用及六项统计 |
| `phase2-source.json` | 冻结的原始公开数组、字典、首页卡片及文件/图片 SHA-256；仅用于来源追溯，不是运行时接口数据 |
| `phase2-manifest.json` | 规范化资源、专区关联、显式别名、来源说明与导入 hash |
| `local-verification.md` | 本地 MySQL 实测范围与结果 |

## 已导入业务库：只执行 004

先在客户端选中正确业务库，执行 `SELECT DATABASE();` 核对，然后完整执行 `004_enterprise_categories.sql`。出现错误立即停止，并在原连接执行 `ROLLBACK;`。该脚本保留已填写或主动清空的新属性，未知旧值不猜测；可重复执行。详情见 [最终企业分类与部署说明](../docs/enterprise-final-20260911.md)。无需重新执行以下首次导入步骤。

## Navicat 首次导入

1. 连接已确认的业务库。云端应先核验目标库、备份与可恢复性；使用受控内网或核验后的加密连接。新建一个专用于迁移的查询连接，不混入其他未提交事务。
2. 先打开并运行 `000_preflight.sql`。确认当前库正确，已有 `app_user`、`user_card`，版本支持 JSON 与 InnoDB。此包不新建库、不替换一期表，也不假定库中只有这两张表。
3. 运行 `001_schema.sql`。末尾已原样附加 `src/main/resources/migration/003-admin.sql`，仅创建标准 SPRING_SESSION / SPRING_SESSION_ATTRIBUTES 及登录限流表，不更改一期表。标准 Session 段只执行一次；已有会话表时先核对结构并跳过这一段，不要重复建表或再次运行独立的 003-admin.sql。DDL 有独立提交语义，不能靠后续 ROLLBACK 撤销建表。
4. 打开 `002_seed.sql`，保持开头 `SET @phase2_dry_run = 1;`，在**同一连接中执行整个文件**。必须看到 `DRY_RUN_READY`，且 `staging_complete=1`、`conflicts=0`；如已成功导入相同清单，会显示 `SKIPPED_ALREADY_IMPORTED`。
5. 首次导入前复核清单。仅把上述值改为 `0`，再执行整个文件。成功结果为 `IMPORTED`，批次状态为 `COMPLETED`。不要逐条选择执行中间语句。
6. 运行 `003_verify.sql`。初始条数为资源 93、专区关联 119、推荐位 4、广告 5、资讯 6、机构 8；统计为 13 / 29 / 4 / 0 / 0 / 12。管理员后续合法修改会改变统计，不能永久要求等于初始数字。

Navicat 应设置“遇到 SQL 错误停止”。若发生错误或中途停止，先在**原连接**执行 `ROLLBACK;`，再关闭该连接并重新开查询窗口重试。不要手工补 `COMMIT`。连接关闭也会回滚未提交的 InnoDB 事务并清理临时表、会话锁。

## 重复导入和冲突行为

- 相同 manifest SHA 且批次为 `COMPLETED`：整批跳过，不修改内容、版本或管理员编辑。
- 新 hash 碰到已有业务 ID、专区唯一关联、主推资源唯一关联或已有未完成批次：保守拦截，输出冲突，不静默合并。确认来源差异后应由管理员或专门增量迁移处理。
- 不使用 `REPLACE`、`ON DUPLICATE KEY UPDATE`、业务表清空、存储过程或 mysql 客户端 `SOURCE`。
- 数据先装入临时 staging 表，检查数量。内容、批次和审计在同一事务中写入；结尾逐字段比较全部目标行与 staging，并确认 IMPORT 审计存在，才动态执行 COMMIT，否则 ROLLBACK。
- 真实 MySQL 回归已证明：中途内容 INSERT 失败或审计 INSERT 失败，即便客户端继续执行，结尾也回滚本批所有内容。GUI 提前停止仍须按上文手工 ROLLBACK。
- 迁移前暂停对同一内容集的其他导入操作。脚本会获取本库的命名锁；拿不到锁时不写入。
- 字符串和 JSON 使用 UTF-8 十六进制 `CONVERT(... USING utf8mb4)`，兼容 `NO_BACKSLASH_ESCAPES`，无需修改全局 SQL mode。

事务及预处理语句机制依据：[MySQL 8.0 Prepared Statements](https://dev.mysql.com/doc/refman/8.0/en/sql-prepared-statements.html)、[Statements That Cause an Implicit Commit](https://dev.mysql.com/doc/refman/8.0/en/implicit-commit.html)。已在 MySQL 8.4.10 实际执行；云实例的版本、账号权限、备份和完整工作流仍须在迁移前核验。

## 来源与字段处理

- 来源 commit：`b26598741a75c31890c5f4607944535b2c07b287`。原始七个 JS 文件分别记录 SHA-256；来源不是接入接口后的前端代码。
- 骊珠要素的 12 条项目与投资人复用相同资源 ID，投资人的 `projectType`、空 `researchStage`、`indications` 合入主记录。
- 只合并清单明确列出的 18 条科研别名：人才 3、专利 9、服务 6。`scientist-data-other` 与 `enterprise-data-other` 是不同内容。
- 首页 4 条卡片创建 `home-featured-1..4`，推荐位为 `featured-1..4`。标题、报价、标签、城市均来自真实 Page 数据；三条需求、一条供给。原数据没有正文、发布方、研发阶段和日期，分别保留为空、不编造；发布时间使用来源 commit 的时间，仅作为迁移缺省值。230万/50万规范成 amountWan=230/50，面议保留 null，并在清单/载荷保留原 price。
- 分类字段进入 `attributes`；通用展示字段保持原 camelCase；`tags`、`industries`、`cooperationModes`、`sections` 保持数组。所有旧记录的 `isDemo` 只留在来源快照中，运行数据以 `sourceKind=legacy_sample` 与逐条 `sourceNote` 说明来源。
- 原 withdrawn 记录为 OFFLINE；closed 保留为 PUBLISHED 的结束记录，两者都不计有效资源。
- 五条广告把 `desc` 映射为 `description`，空图片保留 `imageUrl=''`，默认按钮“查看详情”，动作是打开自身 article。
- 六篇资讯保留日期、正文、地区和原 `sourceName`；来源为原创演示资料，不标成政府发布。
- 八家机构保留 24 次本地示意图片引用，对应 3 个不同 PNG。manifest 记录三图 SHA-256、尺寸和字节数；路径 `/assets/phase1/*` 继续使用原小程序自带素材。**未上传 COS、未产生云媒体地址，不能声称云端资产迁移完成。**
- 专家只计资深专家资源；经理人/高校没有档案来源，当前为 0；技术专利排除软著，专利包每条只记 1。

## 重新生成与检查

在后端仓库根目录，用 PowerShell 7 执行：

```powershell
node scripts/export-phase2.cjs
node scripts/export-phase2.test.cjs
git diff --check
```

导出脚本不会覆盖运行时 `src/main/resources/catalog-schema.json`，也不会生成或覆盖增量 `004_enterprise_categories.sql`。默认从冻结 `phase2-source.json` 重建旧迁移产物，不读取可能已变为接口页面的前端文件。仅在明确要更新迁移来源时，使用 `node scripts/export-phase2.cjs --source <原前端源码目录>` 重新捕获，之后必须重新核对 hash、别名和计数。不要对已经接入 API 的运行前端重做旧种子导出。

可选真实 MySQL 回归：设置 `PHASE2_MYSQL_EXE` 为 mysql.exe 路径，`PHASE2_MYSQL_DEFAULTS` 为私有连接配置文件路径后，执行 `node scripts/export-phase2.test.cjs --mysql`。该检查固定连接 `127.0.0.1:13316/wx_yiyao_test`，只创建和清理自身 `phase2check_<进程ID>_` 前缀表，不用于云库或生产库；配置文件内容不得写入 Git。
