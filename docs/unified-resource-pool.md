# 骊珠要素统一资源池（2026-09-18）

## 行为

公开接口 `GET /api/resources?audience=pool`（category省略或all）汇总原pool及investor、enterprise、scientist、manager四个身份栏目的已发布资源。各来源只包含现有目录中已开放分类；取消的历史分类不重新展示。同一resource ID只出现一次，总数和分页均按唯一资源计算。仅在旧pool出现的项目继续保留。

分类只是此入口不提供筛选，不删除资源分类、属性或tags。未填写可选三级属性的项目仍可进入大池。详情沿用canonical资源ID；原pool的legacy ID链接也继续可用。默认按资源本身sortOrder、ID排列，也保留newest接口排序兼容。

管理后台原栏目列表和四个身份专区查询保持不变。兼容已有客户端显式category=project等分类请求；新版前端不再加载pool筛选目录或发送分类条件。机构等资源分类可汇入，但独立技术服务机构表、政策、广告不会混入资源池。

## 发布

这次有后端变更，无表结构或数据改写，无需SQL。前端移除筛选后，必须替换新版后端JAR才会获得扩大的资源范围。

标准打包位置：`target/springboot-wxcloudrun-1.0.jar`。
本次交付副本：`release/springboot-wxcloudrun-1.0-unified-pool-20260918.jar`（Git忽略的本地构建产物）。

按现有宝塔方案，备份 `/www/wwwroot/trade exchange/release/app.jar`，停止jiaoyi-api和jiaoyi-admin，上传本次副本并以app.jar替换，启动两项Java服务。两项共用同一JAR；配置文件保持原值。由用户自行部署，本次不操作服务器。

## 本地验证

`UnifiedPoolTest`使用独立H2内存库执行真实ContentService/ContentStore SQL：修复前期望20条实际2条，修复后验证5个来源所有现行分类、重复映射去重、跨分页顺序、草稿/下架隔离、未关联/废弃分类排除、tags保留、空可选属性、管理后台/四角色独立范围、统一池详情和旧链接。

H2测试不能代替生产MySQL和真机验收。完整Maven检查中依赖独立MySQL环境的测试未配置时仍跳过，最终测试计数以本次构建报告为准。

本次完整构建：2026-09-18 14:21:23（北京时间），`clean verify`成功；68项中50通过、18项独立MySQL测试跳过，0失败/错误。
交付JAR：66,264,573字节；SHA256 `9D94C7EDB6F570DF8E0B878EE121987CD5B43BC1D3232AF58C06CB06ACE0BBEB`。
