package com.tencent.wxcloudrun.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.wxcloudrun.config.WechatIdentityResolver;
import com.tencent.wxcloudrun.config.WechatIdentityResolver.Identity;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "app.mode", havingValue = "mini", matchIfMissing = true)
@ConditionalOnProperty(name = "app.wechat.auth-mode", havingValue = "server")
public class WechatLoginService {
  private final HttpClient client;
  private final ObjectMapper json;
  private final ProfileService profiles;
  private final WechatIdentityResolver identities;
  private final String appid;
  private final String secret;

  public WechatLoginService(HttpClient client, ObjectMapper json, ProfileService profiles,
      WechatIdentityResolver identities, @Value("${app.wechat.appid:}") String appid,
      @Value("${app.wechat.appsecret:}") String secret) {
    if (secret.isBlank()) throw new IllegalStateException("服务器登录模式必须配置 WECHAT_APPSECRET");
    this.client = client;
    this.json = json;
    this.profiles = profiles;
    this.identities = identities;
    this.appid = appid;
    this.secret = secret;
  }

  public Login login(HttpServletRequest request, JsonNode body) {
    if (body == null || !body.isObject() || body.size() != 1 || !body.path("code").isTextual()
        || body.path("code").asText().isBlank() || body.path("code").asText().length() > 256) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提交仅包含 code 字符串的 JSON");
    }
    var previous = identities.currentServerSession(request);
    String openid = exchange(body.get("code").textValue());
    var account = profiles.session(new Identity(appid, openid));
    // Rotate only this caller's mini-program session after successful identity verification.
    if (previous != null) previous.invalidate();
    var session = request.getSession(true);
    session.setMaxInactiveInterval(7200);
    session.setAttribute(WechatIdentityResolver.SESSION_APPID, appid);
    session.setAttribute(WechatIdentityResolver.SESSION_OPENID, openid);
    return new Login(true, account.userId(), account.createdAt(), session.getId(), 7200);
  }

  public void logout(HttpServletRequest request) {
    var session = identities.currentServerSession(request);
    if (session != null) session.invalidate();
  }

  private String exchange(String code) {
    var uri = URI.create("https://api.weixin.qq.com/sns/jscode2session?appid=" + encode(appid)
        + "&secret=" + encode(secret) + "&js_code=" + encode(code) + "&grant_type=authorization_code");
    JsonNode result;
    try {
      var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200 || response.body() == null || response.body().length() > 16384) {
        throw unavailable();
      }
      result = json.readTree(response.body());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (IOException error) {
      // Never log this exception: its request URI can contain AppSecret and the one-time code.
      throw unavailable();
    }
    if (result == null || !result.isObject()) throw unavailable();
    int error = result.path("errcode").asInt(0);
    if (error == 40029 || error == 40163) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "微信登录凭证已失效，请重试");
    }
    if (error == 45011) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "登录过于频繁，请稍后重试");
    String openid = result.path("openid").asText("");
    if (error != 0 || !openid.matches("[A-Za-z0-9_-]{1,128}")
        || !result.path("session_key").isTextual() || result.path("session_key").asText().isBlank()) {
      throw unavailable();
    }
    // session_key is intentionally discarded; only verified identity enters our session.
    return openid;
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信登录服务暂不可用，请稍后重试");
  }

  public record Login(boolean authenticated, String userId, Instant createdAt, String accessToken, int expiresIn) {}
}
