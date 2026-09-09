-- Phase 2 content schema. Select the existing business database before running.
-- Incremental DDL only; DDL commits independently from seed transactions.
CREATE TABLE IF NOT EXISTS content_resource (
  id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  payload JSON NOT NULL,
  publication_status VARCHAR(16) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 1,
  published_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  import_batch_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
  resource_type VARCHAR(32) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  business_status VARCHAR(16) NOT NULL,
  INDEX idx_resource_public (publication_status,business_status,resource_type,kind),
  INDEX idx_resource_published (published_at),
  INDEX idx_resource_batch (import_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS content_resource_view (
  resource_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  audience VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  category VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  legacy_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (audience,legacy_id),
  UNIQUE KEY uq_view_resource (audience,resource_id),
  INDEX idx_view_category (audience,category,sort_order),
  CONSTRAINT fk_view_resource FOREIGN KEY (resource_id) REFERENCES content_resource(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS home_featured (
  id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  payload JSON NOT NULL,
  publication_status VARCHAR(16) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 1,
  published_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  import_batch_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
  resource_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  UNIQUE KEY uq_featured_resource (resource_id),
  INDEX idx_featured_public (publication_status,sort_order),
  CONSTRAINT fk_featured_resource FOREIGN KEY (resource_id) REFERENCES content_resource(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS home_promo (
  id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  payload JSON NOT NULL,
  publication_status VARCHAR(16) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 1,
  published_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  import_batch_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
  INDEX idx_home_promo_public (publication_status,sort_order),
  INDEX idx_home_promo_batch (import_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS policy_article (
  id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  payload JSON NOT NULL,
  publication_status VARCHAR(16) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 1,
  published_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  import_batch_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
  INDEX idx_policy_article_public (publication_status,sort_order),
  INDEX idx_policy_article_batch (import_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS service_institution (
  id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  payload JSON NOT NULL,
  publication_status VARCHAR(16) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 1,
  published_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  import_batch_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
  INDEX idx_service_institution_public (publication_status,sort_order),
  INDEX idx_service_institution_batch (import_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS content_import_batch (
  id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_commit VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  record_counts JSON NOT NULL,
  status VARCHAR(16) NOT NULL,
  started_at DATETIME(6) NOT NULL,
  finished_at DATETIME(6) NULL,
  UNIQUE KEY uq_import_manifest (manifest_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS content_audit (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  actor VARCHAR(120) NOT NULL,
  operation VARCHAR(32) NOT NULL,
  entity_type VARCHAR(32) NOT NULL,
  entity_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  before_version BIGINT NULL,
  after_version BIGINT NULL,
  changed_fields JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  INDEX idx_audit_entity (entity_type,entity_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS content_media (
  id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  object_key VARCHAR(512) NOT NULL,
  mime_type VARCHAR(80) NOT NULL,
  byte_size BIGINT NOT NULL,
  width INT NOT NULL,
  height INT NOT NULL,
  public_url VARCHAR(2048) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  created_by VARCHAR(120) NOT NULL,
  UNIQUE KEY uq_media_key (object_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Standard admin session and throttle tables; apply this section once.
-- Standard session SQL copied verbatim from spring-session-jdbc 3.5.7 schema-mysql.sql (Boot 3.5.16).
-- Apply once after checking existing tables. No automatic production schema initialization.
CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BLOB NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS admin_login_attempt (
  account_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  window_start BIGINT NOT NULL,
  failure_count INT NOT NULL DEFAULT 0,
  locked_until BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;