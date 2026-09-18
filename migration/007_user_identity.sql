-- 当前用户单选身份：仅新增表，不修改名片、手机号或既有用户，不回填默认身份。
-- 在已核对的服务器业务库执行；该文件不会随 Spring Boot 自动执行。
CREATE TABLE IF NOT EXISTS app_user_identity (
  user_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  tag VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  saved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_user_identity_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
