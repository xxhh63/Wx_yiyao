-- Read only. Select the existing business database in Navicat before running.
SELECT DATABASE() AS target_database, VERSION() AS mysql_version;
SELECT table_name,engine,table_collation
FROM information_schema.tables
WHERE table_schema=DATABASE() AND table_name IN ('app_user','user_card')
ORDER BY table_name;
SELECT table_name,column_name,column_type,is_nullable,column_key
FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name IN ('app_user','user_card')
ORDER BY table_name,ordinal_position;
-- Stop if the target is unexpected or either phase-one table is missing.
SELECT table_name,engine FROM information_schema.tables
WHERE table_schema=DATABASE() AND table_name IN
('content_resource','content_resource_view','home_featured','home_promo','policy_article','service_institution','content_import_batch','content_audit','content_media');
