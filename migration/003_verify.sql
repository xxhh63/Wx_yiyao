-- Read-only verification. Select the same existing business database.
SELECT DATABASE() AS target_database, VERSION() AS mysql_version;
SELECT id,manifest_sha256,source_commit,record_counts,status,started_at,finished_at
FROM content_import_batch WHERE id=CONVERT(0x7068617365322d666534343561326238303036373036343137656638323136 USING utf8mb4);
SELECT 'content_resource' AS entity_type, 93 AS expected_initial_rows, COUNT(*) AS actual_batch_rows FROM content_resource WHERE import_batch_id=CONVERT(0x7068617365322d666534343561326238303036373036343137656638323136 USING utf8mb4);
SELECT 'service_institution' AS entity_type, 8 AS expected_initial_rows, COUNT(*) AS actual_batch_rows FROM service_institution WHERE import_batch_id=CONVERT(0x7068617365322d666534343561326238303036373036343137656638323136 USING utf8mb4);
SELECT 'policy_article' AS entity_type, 6 AS expected_initial_rows, COUNT(*) AS actual_batch_rows FROM policy_article WHERE import_batch_id=CONVERT(0x7068617365322d666534343561326238303036373036343137656638323136 USING utf8mb4);
SELECT 'home_promo' AS entity_type, 5 AS expected_initial_rows, COUNT(*) AS actual_batch_rows FROM home_promo WHERE import_batch_id=CONVERT(0x7068617365322d666534343561326238303036373036343137656638323136 USING utf8mb4);
SELECT 'home_featured' AS entity_type, 4 AS expected_initial_rows, COUNT(*) AS actual_batch_rows FROM home_featured WHERE import_batch_id=CONVERT(0x7068617365322d666534343561326238303036373036343137656638323136 USING utf8mb4);
SELECT audience,COUNT(*) AS view_rows FROM content_resource_view GROUP BY audience ORDER BY audience;
SELECT resource_type,COUNT(*) AS resources,SUM(publication_status='PUBLISHED' AND business_status='open') AS active_resources FROM content_resource GROUP BY resource_type ORDER BY resource_type;
SELECT
  COALESCE(SUM(kind='demand'),0) AS demands,
  COALESCE(SUM(kind='supply' AND (resource_type IN ('project','technology') OR JSON_UNQUOTE(JSON_EXTRACT(payload,'$.attributes.achievementType'))='技术成果')),0) AS achievements,
  COALESCE(SUM(resource_type='talent' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.attributes.talentMaturity'))='资深专家'),0) AS experts,
  0 AS managers,
  0 AS institutions,
  COALESCE(SUM((resource_type='patent' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.attributes.patentType'))<>'软著') OR JSON_UNQUOTE(JSON_EXTRACT(payload,'$.attributes.achievementType')) IN ('专利包','纯专利权利')),0) AS patents
FROM content_resource WHERE publication_status='PUBLISHED' AND business_status='open';
-- Initial expected stats: {"demands":13,"achievements":29,"experts":4,"managers":0,"institutions":0,"patents":12}. Later admin edits legitimately change these values.
SELECT v.audience,v.legacy_id,v.resource_id FROM content_resource_view v
WHERE v.legacy_id IN ('scientist-data-other','enterprise-data-other') ORDER BY v.audience;
SELECT id,business_status,publication_status FROM content_resource WHERE business_status<>'open';
SELECT id,JSON_LENGTH(JSON_EXTRACT(payload,'$.images')) AS image_references FROM service_institution ORDER BY id;
SELECT id FROM content_resource WHERE JSON_EXTRACT(payload,'$.sourceKind') IS NULL OR JSON_EXTRACT(payload,'$.sourceNote') IS NULL;
SELECT v.resource_id FROM content_resource_view v LEFT JOIN content_resource r ON r.id=v.resource_id WHERE r.id IS NULL;
