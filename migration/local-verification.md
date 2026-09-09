# 二期迁移本地验证记录

日期：2026-09-09。目标严格限定为本地 `127.0.0.1:13316/wx_yiyao_test`，MySQL 8.4.10；没有调用云数据库。所有凭据由私有 defaults-extra-file 读取，未输出或写入交付物。

| 检查 | 实际结果 |
| --- | --- |
| 内容表增量 DDL | 成功，无 app_user/user_card ALTER/DROP |
| 默认 seed dry-run | DRY_RUN_READY，staging 完整、冲突 0、持久数据新增 0 |
| home_promo INSERT 注入未知列，客户端继续执行 | 最终 ROLLBACK；资源、映射、批次、审计全部 0 |
| 首次正常执行 | IMPORTED，批次 COMPLETED |
| 同一清单再次执行 | SKIPPED_ALREADY_IMPORTED，无新增 |
| NO_BACKSLASH_ESCAPES | 首次导入成功，UTF-8/JSON 原文保留 |
| 审计 INSERT 注入未知列 | 最终 ROLLBACK，内容与批次无残留 |
| 管理员修改广告后重复同批导入 | 标题“后台编辑保留”、version=2 原样保留 |
| 新 hash 与已有 ID 冲突 | 拦截；已有内容、批次和管理员修改未被覆盖 |
| 专区别名 | pool12 / investor29 / enterprise40 / scientist30 / manager8，共119；科研18条显式共用 |
| 两条其他数据 | scientist-data-other / enterprise-data-other 为不同资源 |
| 原业务状态 | withdrawn→OFFLINE，closed→PUBLISHED，两者均不计有效资源 |
| 机构图片 | 8家 × 3次引用=24；3个不同原PNG，仍为小程序自带示意素材 |
| 一期表 | 测试前后 app_user=5、user_card=5；未读用户或名片内容 |

批次：`phase2-fe445a2b8006706417ef8216`。
manifest SHA-256：`fe445a2b8006706417ef8216d5f2e776306c47d39e09da8968d164b6c69a59c5`。

正常内容表最终状态：93 资源、119 专区关联、4 主推、5 广告、6 资讯、8 机构；1 条 COMPLETED 导入批次与1条IMPORT审计。初始实际 SQL 统计为 **13 / 29 / 4 / 0 / 0 / 12**。

可重跑的数据库检查留在 `scripts/export-phase2.test.cjs --mysql`，使用独立 `phase2check_<进程ID>_` 表，测试后按该前缀限定清理。应用内容表保持上述正常 seed 状态，未给云库建表、导入、备份、上传媒体或部署服务。

Node 导出回归验证了资源/视图数量、唯一 ID、18条别名、共享项目扩展字段、空研发阶段、原文/日期/图片引用、来源标记、源字段白名单、稳定 hash、SQL 字符串编码以及前述统计口径。Node 检查不代表小程序开发者工具、真机、后台浏览器或云环境验收。
