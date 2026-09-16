-- 微信手机号快速验证组件：仅保存由微信服务端确认的手机号，不修改名片联系电话。
CREATE TABLE IF NOT EXISTS app_user_phone (
  user_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  phone_number VARCHAR(32) NOT NULL,
  country_code VARCHAR(4) NOT NULL,
  verified_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_user_phone_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
