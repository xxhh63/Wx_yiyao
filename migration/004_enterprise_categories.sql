-- 004: Final enterprise classification, 2026-09-11.
-- Select the intended existing business database in your SQL client before executing.
-- No schema change. Adds enterprise-only JSON attributes; keeps original records and shared-role attributes.
-- Re-runnable: existing enterprise attributes (including intentional blanks) are never overwritten.
SET NAMES utf8mb4;
START TRANSACTION;

UPDATE content_resource c JOIN content_resource_view v ON v.resource_id=c.id AND v.audience='enterprise' AND v.category='technology'
SET c.payload=JSON_SET(c.payload,
  '$.attributes.enterpriseTechnologyField',
  CAST(IF(JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseTechnologyField'), JSON_EXTRACT(c.payload,'$.attributes.enterpriseTechnologyField'), JSON_QUOTE(CASE JSON_UNQUOTE(JSON_EXTRACT(c.payload,'$.attributes.technologyField')) WHEN '小分子' THEN '小分子' WHEN '抗体' THEN '抗体' WHEN '多肽' THEN '多肽' WHEN '类器官' THEN '类器官' WHEN '基因与细胞治疗' THEN '基因与细胞治疗' WHEN '其他领域' THEN '其他领域' ELSE '' END)) AS JSON),
  '$.attributes.enterpriseResearchStage',
  CAST(IF(JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseResearchStage'), JSON_EXTRACT(c.payload,'$.attributes.enterpriseResearchStage'), JSON_QUOTE(CASE JSON_UNQUOTE(JSON_EXTRACT(c.payload,'$.attributes.researchStage')) WHEN '早期研究' THEN '早期研究' WHEN '临床前研究' THEN '临床前研究' WHEN '临床研究' THEN '临床研究' WHEN '申请上市' THEN '申请上市' WHEN '批准上市' THEN '批准上市' ELSE '' END)) AS JSON),
  '$.attributes.enterpriseCooperationMode',
  CAST(IF(JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseCooperationMode'), JSON_EXTRACT(c.payload,'$.attributes.enterpriseCooperationMode'), JSON_QUOTE(CASE JSON_UNQUOTE(JSON_EXTRACT(c.payload,'$.attributes.cooperationMode')) WHEN '技术转让' THEN '技术转让' WHEN '技术授权' THEN '技术授权' WHEN '其他模式' THEN '其他模式' ELSE '' END)) AS JSON)), c.version=c.version+1, c.updated_at=CURRENT_TIMESTAMP(6)
WHERE c.resource_type='technology' AND (NOT JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseTechnologyField') OR NOT JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseResearchStage') OR NOT JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseCooperationMode'));

UPDATE content_resource c JOIN content_resource_view v ON v.resource_id=c.id AND v.audience='enterprise' AND v.category='patent'
SET c.payload=JSON_SET(c.payload,
  '$.attributes.enterprisePatentField',
  CAST(IF(JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterprisePatentField'), JSON_EXTRACT(c.payload,'$.attributes.enterprisePatentField'), JSON_QUOTE(CASE JSON_UNQUOTE(JSON_EXTRACT(c.payload,'$.attributes.patentField')) WHEN '小分子' THEN '小分子' WHEN '抗体' THEN '抗体' WHEN '多肽' THEN '多肽' WHEN '类器官' THEN '类器官' WHEN '基因与细胞治疗' THEN '基因与细胞治疗' WHEN '其他领域' THEN '其他领域' ELSE '' END)) AS JSON),
  '$.attributes.enterpriseCooperationMode',
  CAST(IF(JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseCooperationMode'), JSON_EXTRACT(c.payload,'$.attributes.enterpriseCooperationMode'), JSON_QUOTE(CASE JSON_UNQUOTE(JSON_EXTRACT(c.payload,'$.attributes.cooperationMode')) WHEN '专利转让' THEN '专利转让' WHEN '专利授权' THEN '专利授权' WHEN '其他模式' THEN '其他模式' WHEN '专利全权转让' THEN '专利转让' WHEN '独占许可' THEN '专利授权' WHEN '排他许可' THEN '专利授权' WHEN '专项授权使用' THEN '专利授权' ELSE '' END)) AS JSON)), c.version=c.version+1, c.updated_at=CURRENT_TIMESTAMP(6)
WHERE c.resource_type='patent' AND (NOT JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterprisePatentField') OR NOT JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseCooperationMode'));

UPDATE content_resource c JOIN content_resource_view v ON v.resource_id=c.id AND v.audience='enterprise' AND v.category='mah'
SET c.payload=JSON_SET(c.payload,
  '$.attributes.enterpriseMahType',
  CAST(IF(JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseMahType'), JSON_EXTRACT(c.payload,'$.attributes.enterpriseMahType'), JSON_QUOTE(CASE JSON_UNQUOTE(JSON_EXTRACT(c.payload,'$.attributes.mahType')) WHEN '化学药制剂 MAH 批件' THEN '化学药制剂 MAH 批件' WHEN '生物药制剂 MAH 批件' THEN '生物药制剂 MAH 批件' WHEN '中药 MAH 批件' THEN '中药 MAH 批件' WHEN '其他批件' THEN '其他批件' ELSE '' END)) AS JSON),
  '$.attributes.enterpriseApprovalStatus',
  CAST(IF(JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseApprovalStatus'), JSON_EXTRACT(c.payload,'$.attributes.enterpriseApprovalStatus'), JSON_QUOTE(CASE JSON_UNQUOTE(JSON_EXTRACT(c.payload,'$.attributes.approvalStatus')) WHEN '已获批' THEN '已获批' WHEN '其他状态' THEN '其他状态' ELSE '' END)) AS JSON),
  '$.attributes.enterpriseTradeMode',
  CAST(IF(JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseTradeMode'), JSON_EXTRACT(c.payload,'$.attributes.enterpriseTradeMode'), JSON_QUOTE(CASE JSON_UNQUOTE(JSON_EXTRACT(c.payload,'$.attributes.tradeMode')) WHEN 'MAH 转让' THEN 'MAH 转让' WHEN '股权并购' THEN '股权并购' WHEN '委托生产' THEN '委托生产' WHEN '其他合作模式' THEN '其他合作模式' ELSE '' END)) AS JSON)), c.version=c.version+1, c.updated_at=CURRENT_TIMESTAMP(6)
WHERE c.resource_type='mah' AND (NOT JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseMahType') OR NOT JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseApprovalStatus') OR NOT JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseTradeMode'));

UPDATE content_resource c JOIN content_resource_view v ON v.resource_id=c.id AND v.audience='enterprise' AND v.category='data'
SET c.payload=JSON_SET(c.payload,
  '$.attributes.enterpriseDataType',
  CAST(IF(JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseDataType'), JSON_EXTRACT(c.payload,'$.attributes.enterpriseDataType'), JSON_QUOTE(CASE JSON_UNQUOTE(JSON_EXTRACT(c.payload,'$.attributes.dataType')) WHEN '临床研究数据' THEN '临床研究数据' WHEN '真实世界RWD数据' THEN '真实世界RWD数据' WHEN '其他数据' THEN '其他数据' ELSE '' END)) AS JSON),
  '$.attributes.enterpriseComplianceMaturity',
  CAST(IF(JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseComplianceMaturity'), JSON_EXTRACT(c.payload,'$.attributes.enterpriseComplianceMaturity'), JSON_QUOTE(CASE JSON_UNQUOTE(JSON_EXTRACT(c.payload,'$.attributes.complianceMaturity')) WHEN '仅限研究使用' THEN '仅限研究使用' WHEN '已脱敏合规' THEN '已脱敏合规' WHEN '可商用授权' THEN '可商用授权' WHEN '其他' THEN '其他' ELSE '' END)) AS JSON),
  '$.attributes.enterpriseCooperationMode',
  CAST(IF(JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseCooperationMode'), JSON_EXTRACT(c.payload,'$.attributes.enterpriseCooperationMode'), JSON_QUOTE(CASE JSON_UNQUOTE(JSON_EXTRACT(c.payload,'$.attributes.cooperationMode')) WHEN '数据永久授权' THEN '数据永久授权' WHEN '单次项目调取' THEN '单次项目调取' WHEN '其他模式' THEN '其他模式' ELSE '' END)) AS JSON)), c.version=c.version+1, c.updated_at=CURRENT_TIMESTAMP(6)
WHERE c.resource_type='data' AND (NOT JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseDataType') OR NOT JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseComplianceMaturity') OR NOT JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseCooperationMode'));

UPDATE content_resource c JOIN content_resource_view v ON v.resource_id=c.id AND v.audience='enterprise' AND v.category='talent'
SET c.payload=JSON_SET(c.payload,
  '$.attributes.enterpriseTalentType',
  CAST(IF(JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseTalentType'), JSON_EXTRACT(c.payload,'$.attributes.enterpriseTalentType'), JSON_QUOTE(CASE JSON_UNQUOTE(JSON_EXTRACT(c.payload,'$.attributes.talentType')) WHEN '首席科学家' THEN '首席科学家' WHEN '研发型人才' THEN '研发型人才' WHEN '投融资产业专家' THEN '投融资产业专家' WHEN '商业化运营人才' THEN '商业化运营人才' WHEN '其他人才' THEN '其他人才' ELSE '' END)) AS JSON),
  '$.attributes.enterpriseCooperationMode',
  CAST(IF(JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseCooperationMode'), JSON_EXTRACT(c.payload,'$.attributes.enterpriseCooperationMode'), JSON_QUOTE(CASE JSON_UNQUOTE(JSON_EXTRACT(c.payload,'$.attributes.cooperationMode')) WHEN '全职' THEN '全职' WHEN '项目制' THEN '项目制' WHEN '其他模式' THEN '其他模式' ELSE '' END)) AS JSON)), c.version=c.version+1, c.updated_at=CURRENT_TIMESTAMP(6)
WHERE c.resource_type='talent' AND (NOT JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseTalentType') OR NOT JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseCooperationMode'));

UPDATE content_resource c JOIN content_resource_view v ON v.resource_id=c.id AND v.audience='enterprise' AND v.category='service'
SET c.payload=JSON_SET(c.payload,
  '$.attributes.enterpriseServiceType',
  CAST(IF(JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseServiceType'), JSON_EXTRACT(c.payload,'$.attributes.enterpriseServiceType'), JSON_QUOTE(CASE JSON_UNQUOTE(JSON_EXTRACT(c.payload,'$.attributes.serviceType')) WHEN 'CXO服务' THEN 'CXO服务' WHEN '骊珠整体解决方案' THEN '骊珠整体解决方案' WHEN '增值服务' THEN '增值服务' WHEN 'CRO服务' THEN 'CXO服务' WHEN 'CDMO服务' THEN 'CXO服务' ELSE '' END)) AS JSON),
  '$.attributes.enterpriseQualification',
  CAST(IF(JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseQualification'), JSON_EXTRACT(c.payload,'$.attributes.enterpriseQualification'), JSON_QUOTE(CASE JSON_UNQUOTE(JSON_EXTRACT(c.payload,'$.attributes.qualification')) WHEN '丰富案例' THEN '丰富案例' WHEN '丰富落地案例' THEN '丰富案例' ELSE '' END)) AS JSON),
  '$.attributes.enterpriseCooperationMode',
  CAST(IF(JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseCooperationMode'), JSON_EXTRACT(c.payload,'$.attributes.enterpriseCooperationMode'), JSON_QUOTE(CASE JSON_UNQUOTE(JSON_EXTRACT(c.payload,'$.attributes.cooperationMode')) WHEN '单项目合作' THEN '单项目合作' WHEN '年度框架合作' THEN '年度框架合作' WHEN '其他模式' THEN '其他模式' WHEN '单项项目外包' THEN '单项目合作' ELSE '' END)) AS JSON)), c.version=c.version+1, c.updated_at=CURRENT_TIMESTAMP(6)
WHERE c.resource_type='service' AND (NOT JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseServiceType') OR NOT JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseQualification') OR NOT JSON_CONTAINS_PATH(c.payload, 'one', '$.attributes.enterpriseCooperationMode'));

COMMIT;
SELECT v.category, COUNT(*) AS resources FROM content_resource_view v WHERE v.audience='enterprise' GROUP BY v.category;
