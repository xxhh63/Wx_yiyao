package com.tencent.wxcloudrun.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "admin")
public class AdminAuthenticationProvider implements AuthenticationProvider {
  private static final long WINDOW_MILLIS = 15 * 60 * 1000L;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final String username;
  private final String passwordHash;
  private final String accountKey;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  public AdminAuthenticationProvider(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager,
      @Value("${app.admin.username:}") String username, @Value("${app.admin.password-hash:}") String passwordHash) {
    if (username.isBlank() || username.length() > 100 || !username.equals(username.trim())
        || !passwordHash.matches("\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}")) {
      throw new IllegalStateException("admin 模式必须配置 ADMIN_USERNAME 和有效 BCrypt ADMIN_PASSWORD_HASH，禁止默认密码");
    }
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(manager);
    this.username = username;
    this.passwordHash = passwordHash;
    try {
      this.accountKey = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(username.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    try {
      jdbc.update("INSERT INTO admin_login_attempt (account_key, window_start, failure_count, locked_until) VALUES (?, 0, 0, 0)", accountKey);
    } catch (DuplicateKeyException alreadyInitialized) { /* The other instance or a previous run created this account. */ }
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    int result;
    try {
      // ponytail: one configured admin serializes password checks under one DB row lock; split per account if multi-admin support is added.
      result = transaction.execute(status -> {
        var attempts = jdbc.queryForMap("SELECT window_start, failure_count, locked_until FROM admin_login_attempt WHERE account_key=? FOR UPDATE", accountKey);
        long now = System.currentTimeMillis();
        if (((Number) attempts.get("locked_until")).longValue() > now) return 429;
        long start = ((Number) attempts.get("window_start")).longValue();
        int failures = now - start >= WINDOW_MILLIS ? 0 : ((Number) attempts.get("failure_count")).intValue();
        if (failures == 0) start = now;
        String supplied = authentication.getCredentials() == null ? "" : authentication.getCredentials().toString();
        boolean validPassword = supplied.length() <= 1024 && encoder.matches(supplied, passwordHash);
        boolean valid = username.equals(authentication.getName()) && validPassword;
        if (valid) {
          jdbc.update("UPDATE admin_login_attempt SET window_start=?, failure_count=0, locked_until=0 WHERE account_key=?", now, accountKey);
          return 200;
        }
        failures++;
        jdbc.update("UPDATE admin_login_attempt SET window_start=?, failure_count=?, locked_until=? WHERE account_key=?",
            start, failures, failures >= 5 ? now + WINDOW_MILLIS : 0, accountKey);
        return 401;
      });
    } catch (DataAccessException unavailable) { throw new AuthenticationServiceException("认证服务暂不可用"); }
    if (result == 429) throw new LockedException("尝试过于频繁，请稍后重试");
    if (result != 200) throw new BadCredentialsException("账号或密码错误");
    return UsernamePasswordAuthenticationToken.authenticated(username, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
  }

  @Override
  public boolean supports(Class<?> authentication) { return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication); }
}