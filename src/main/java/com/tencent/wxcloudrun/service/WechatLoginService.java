package com.tencent.wxcloudrun.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.wxcloudrun.config.WechatIdentityResolver;
import com.tencent.wxcloudrun.config.WechatIdentityResolver.Identity;
import com.tencent.wxcloudrun.dao.ProfileMapper.PhoneRow;
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
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "app.mode", havingValue = "mini", matchIfMissing = true)
@ConditionalOnProperty(name = "app.wechat.auth-mode", havingValue = "server")
public class WechatLoginService {
  private static final Logger LOG = LoggerFactory.getLogger(WechatLoginService.class);
  private final HttpClient client;
  private final ObjectMapper json;
  private final ProfileService profiles;
  private final WechatIdentityResolver identities;
  private final String appid;
  private final String secret;
  private String apiToken = "";
  private long apiTokenExpires;

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
    if (body == null || !body.isObject() || body.size() != (body.has("phoneCode") ? 2 : 1)
        || !validCode(body.path("code")) || body.has("phoneCode") && !validCode(body.get("phoneCode"))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提交 code 及可选的 phoneCode 字符串");
    }
    var previous = identities.currentServerSession(request);
    var identity = new Identity(appid, exchange(body.get("code").textValue()));
    PhoneRow phone = body.has("phoneCode") ? exchangePhone(body.get("phoneCode").textValue(), identity.openid())
        : profiles.phone(identity);
    if (phone == null) {
      throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "请授权手机号后登录");
    }
    var account = body.has("phoneCode") ? profiles.phoneSession(identity, phone) : profiles.session(identity);
    // Rotate only after successful verification and persistence; failure leaves the old session intact.
    if (previous != null) previous.invalidate();
    var session = request.getSession(true);
    session.setMaxInactiveInterval(7200);
    session.setAttribute(WechatIdentityResolver.SESSION_APPID, appid);
    session.setAttribute(WechatIdentityResolver.SESSION_OPENID, identity.openid());
    return new Login(true, account.userId(), account.createdAt(), session.getId(), 7200,
        true, phone.phoneNumber(), phone.countryCode());
  }

  public void logout(HttpServletRequest request) {
    var session = identities.currentServerSession(request);
    if (session != null) session.invalidate();
  }

  private boolean validCode(JsonNode node) {
    return node.isTextual() && !node.asText().isBlank() && node.asText().length() <= 256;
  }

  private String exchange(String code) {
    var uri = URI.create("https://api.weixin.qq.com/sns/jscode2session?appid=" + encode(appid)
        + "&secret=" + encode(secret) + "&js_code=" + encode(code) + "&grant_type=authorization_code");
    JsonNode result = send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build());
    int error = result.path("errcode").asInt(0);
    if (error == 40029 || error == 40163) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "微信登录凭证已失效，请重试");
    }
    if (error == 45011) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "登录过于频繁，请稍后重试");
    String openid = result.path("openid").asText("");
    if (error != 0 || !openid.matches("[A-Za-z0-9_-]{1,128}")
        || !result.path("session_key").isTextual() || result.path("session_key").asText().isBlank()) {
      throw upstreamFailure("code2Session", error);
    }
    // session_key is intentionally discarded; only verified identity enters our session.
    return openid;
  }

  private PhoneRow exchangePhone(String code, String openid) {
    String token = accessToken(null);
    JsonNode result = phoneRequest(code, openid, token);
    int error = result.path("errcode").asInt(-1);
    // These errors mean the API token was rejected before the one-time phone code was consumed.
    if (error == 40001 || error == 40014 || error == 42001) {
      result = phoneRequest(code, openid, accessToken(token));
      error = result.path("errcode").asInt(-1);
    }
    if (error == 40029 || error == 40163 || error == 40003) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号授权已失效，请重新点击手机号登录");
    }
    if (error != 0) throw upstreamFailure("getPhoneNumber", error);
    JsonNode phone = result.path("phone_info");
    String number = phone.path("phoneNumber").asText("");
    String pure = phone.path("purePhoneNumber").asText("");
    String country = phone.path("countryCode").asText("");
    String digits = number.startsWith("+") ? number.substring(1) : number;
    if (!phone.path("phoneNumber").isTextual() || !phone.path("purePhoneNumber").isTextual()
        || !phone.path("countryCode").isTextual() || !appid.equals(phone.path("watermark").path("appid").asText())
        || !number.matches("\\+?[0-9]{6,24}") || !pure.matches("[0-9]{6,20}") || !country.matches("[1-9][0-9]{0,3}")
        || !(digits.equals(pure) || digits.equals(country + pure))) {
      throw unavailable();
    }
    return new PhoneRow(number, country);
  }

  private JsonNode phoneRequest(String code, String openid, String token) {
    return post("https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=" + encode(token),
        Map.of("code", code, "openid", openid));
  }

  private synchronized String accessToken(String rejectedToken) {
    if (!apiToken.isEmpty() && System.nanoTime() < apiTokenExpires && !apiToken.equals(rejectedToken)) return apiToken;
    // ponytail: one in-process cache per service; use shared token storage only if multiple apps need coordinated refresh.
    JsonNode result = post("https://api.weixin.qq.com/cgi-bin/stable_token",
        Map.of("grant_type", "client_credential", "appid", appid, "secret", secret, "force_refresh", rejectedToken != null));
    String token = result.path("access_token").asText("");
    long expires = result.path("expires_in").asLong(0);
    int error = result.path("errcode").asInt(0);
    if (error != 0 || !result.path("access_token").isTextual() || token.isBlank() || token.length() > 4096
        || expires <= 0 || expires > 86400) {
      throw upstreamFailure("stableToken", error);
    }
    apiToken = token;
    apiTokenExpires = System.nanoTime() + Duration.ofSeconds(Math.max(1, expires - Math.min(120, expires / 10))).toNanos();
    return apiToken;
  }

  private JsonNode post(String url, Map<String, ?> body) {
    try {
      return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build());
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw unavailable();
    }
  }

  private JsonNode send(HttpRequest request) {
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200 || response.body() == null || response.body().length() > 16384) throw unavailable();
      JsonNode result = json.readTree(response.body());
      if (result == null || !result.isObject()) throw unavailable();
      return result;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (IOException error) {
      // Never log request/response or exception text: they may contain a secret, code, token or phone number.
      throw unavailable();
    }
  }

  private ResponseStatusException upstreamFailure(String operation, int error) {
    LOG.warn("WeChat {} failed with errcode={}", operation, error);
    return unavailable();
  }

  private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

  private ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信登录服务暂不可用，请稍后重试");
  }

  public record Login(boolean authenticated, String userId, Instant createdAt, String accessToken, int expiresIn,
                      boolean phoneVerified, String phoneNumber, String countryCode) {}
}
