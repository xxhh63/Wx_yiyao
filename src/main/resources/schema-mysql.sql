-- 在专用业务库执行；不创建数据库、不删除或修改模板 Counters 表。
-- 身份字段区分大小写；每个小程序内一个 OpenID 对应一个用户。
CREATE TABLE IF NOT EXISTS app_user (
  id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  appid VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  openid VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_app_user_identity (appid, openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_card (
  user_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  name VARCHAR(30) NOT NULL DEFAULT '',
  phone VARCHAR(24) NOT NULL DEFAULT '',
  company VARCHAR(80) NOT NULL DEFAULT '',
  position VARCHAR(50) NOT NULL DEFAULT '',
  address VARCHAR(120) NOT NULL DEFAULT '',
  email VARCHAR(100) NOT NULL DEFAULT '',
  wechat VARCHAR(50) NOT NULL DEFAULT '',
  intro VARCHAR(500) NOT NULL DEFAULT '',
  saved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_user_card_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 微信手机号快速验证组件：仅保存由微信服务端确认的手机号，不修改名片联系电话。
CREATE TABLE IF NOT EXISTS app_user_phone (
  user_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  phone_number VARCHAR(32) NOT NULL,
  country_code VARCHAR(4) NOT NULL,
  verified_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_user_phone_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
