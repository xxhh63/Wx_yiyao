package com.tencent.wxcloudrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ServerWechatLoginTest {
  private static final String APPID = "wxa64654c604b3af31";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  @TempDir Path temporary;

  @Test
  void codeLoginPersistsAcrossRestartAndIsolatesUsersWithoutTrustingHeaders() throws Exception {
    String url = url("sessions");
    HttpClient wechat = wechat();
    String aliceToken;
    String aliceId;
    try (var app = start(url, wechat, APPID)) {
      assertEquals(401, call(app, "GET", "/api/me/card", null, null).statusCode());
      assertEquals(401, call(app, "GET", "/api/me/card", null, null,
          Map.of("X-WX-APPID", APPID, "X-WX-OPENID", "alice", "X-WX-SOURCE", "forged")).statusCode());
      var login = call(app, "POST", "/api/auth/session", "{\"code\":\"alice-code\",\"phoneCode\":\"alice-phone\"}", null);
      var account = data(login);
      aliceToken = account.path("accessToken").asText();
      aliceId = account.path("userId").asText();
      assertFalse(aliceToken.isBlank());
      assertEquals(7200, account.path("expiresIn").asInt());
      assertEquals(aliceToken, login.headers().firstValue("X-Auth-Token").orElse(""));
      assertTrue(login.headers().allValues("Set-Cookie").isEmpty());
      assertFalse(login.body().contains("session_key"));
      assertFalse(login.body().contains("test-app-secret"));
      assertFalse(account.has("openid"));
      assertEquals(200, call(app, "PUT", "/api/me/card",
          "{\"values\":{\"name\":\"新小程序用户 😀\",\"company\":\"测试科研机构\"}}", aliceToken).statusCode());
      String bobToken = data(call(app, "POST", "/api/auth/session", "{\"code\":\"bob-code\",\"phoneCode\":\"bob-phone\"}", null)).path("accessToken").asText();
      assertTrue(data(call(app, "GET", "/api/me/card", null, bobToken)).path("savedAt").isNull());
      assertEquals("新小程序用户 😀", data(call(app, "GET", "/api/me/card", null, aliceToken,
          Map.of("X-WX-OPENID", "bob"))).at("/values/name").asText());
      assertEquals(401, call(app, "GET", "/api/me/card", null, null,
          Map.of("Cookie", "SESSION=" + aliceToken)).statusCode());
      assertEquals(400, call(app, "PUT", "/api/me/card",
          "{\"values\":{},\"userId\":\"" + aliceId + "\"}", bobToken).statusCode());
      assertEquals(401, call(app, "GET", "/admin/api/resources", null, aliceToken).statusCode());
      var duplicate = HttpRequest.newBuilder(address(app, "/api/me/card"))
          .header("X-Auth-Token", aliceToken).header("X-Auth-Token", bobToken).GET().build();
      assertEquals(401, HTTP.send(duplicate, HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    try (var app = start(url, wechat, APPID)) {
      assertEquals("新小程序用户 😀", data(call(app, "GET", "/api/me/card", null, aliceToken)).at("/values/name").asText());
      var renewed = data(call(app, "POST", "/api/auth/session", "{\"code\":\"alice-code-2\"}", aliceToken));
      assertEquals(aliceId, renewed.path("userId").asText());
      String replacement = renewed.path("accessToken").asText();
      assertNotEquals(aliceToken, replacement);
      assertEquals(401, call(app, "GET", "/api/me/card", null, aliceToken).statusCode());
      assertFalse(data(call(app, "DELETE", "/api/auth/session", null, replacement)).path("authenticated").asBoolean());
      assertEquals(401, call(app, "GET", "/api/me/card", null, replacement).statusCode());
    }
  }

  @Test
  void singleIdentityIsPrivatePersistsAcrossRestartAndNeverChangesTheCard() throws Exception {
    String url = url("identity");
    String aliceToken;
    JsonNode savedIdentity;
    JsonNode savedCard;
    HttpClient wechat = wechat();
    try (var app = start(url, wechat, APPID)) {
      assertEquals(401, call(app, "GET", "/api/me/identity", null, null).statusCode());
      assertEquals(401, call(app, "PUT", "/api/me/identity", "{\"tag\":\"enterprise\"}", null).statusCode());
      assertEquals(401, call(app, "GET", "/api/me/identity", null, null,
          Map.of("X-WX-APPID", APPID, "X-WX-OPENID", "alice", "X-WX-SOURCE", "forged")).statusCode());
      var alice = data(call(app, "POST", "/api/auth/session",
          "{\"code\":\"alice-code\",\"phoneCode\":\"alice-phone\"}", null));
      aliceToken = alice.path("accessToken").asText();
      var emptyResponse = call(app, "GET", "/api/me/identity", null, aliceToken);
      assertTrue(emptyResponse.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
      var empty = data(emptyResponse);
      assertEquals(3, empty.size());
      assertTrue(empty.path("tag").isNull());
      assertEquals("", empty.path("label").asText());
      assertTrue(empty.path("savedAt").isNull());
      savedCard = data(call(app, "PUT", "/api/me/card",
          "{\"values\":{\"name\":\"身份独立测试\",\"company\":\"科研单位\",\"phone\":\"010-12345678\"}}", aliceToken));
      String bobToken = data(call(app, "POST", "/api/auth/session",
          "{\"code\":\"bob-code\",\"phoneCode\":\"bob-phone\"}", null)).path("accessToken").asText();
      for (var option : List.of(Map.entry("enterprise", "企业"), Map.entry("scientist", "科研院所"),
          Map.entry("manager", "服务机构"), Map.entry("investor", "投资人"))) {
        var saved = data(call(app, "PUT", "/api/me/identity", "{\"tag\":\"" + option.getKey() + "\"}", aliceToken));
        assertEquals(3, saved.size());
        assertEquals(option.getKey(), saved.path("tag").asText());
        assertEquals(option.getValue(), saved.path("label").asText());
        assertDoesNotThrow(() -> java.time.Instant.parse(saved.path("savedAt").asText()));
        assertEquals(saved, data(call(app, "GET", "/api/me/identity", null, aliceToken)));
        assertEquals(1, count(url, "SELECT COUNT(*) FROM app_user_identity"));
      }
      savedIdentity = data(call(app, "GET", "/api/me/identity", null, aliceToken));
      assertEquals(empty, data(call(app, "GET", "/api/me/identity", null, bobToken,
          Map.of("X-WX-OPENID", "alice"))));
      assertEquals(400, call(app, "PUT", "/api/me/identity",
          "{\"tag\":\"enterprise\",\"userId\":\"" + alice.path("userId").asText() + "\"}", bobToken).statusCode());
      assertEquals(savedIdentity, data(call(app, "GET", "/api/me/identity", null, aliceToken)));
      assertEquals(savedCard, data(call(app, "GET", "/api/me/card", null, aliceToken)));
      assertEquals(1, count(url, "SELECT COUNT(*) FROM app_user_identity"));
    }
    try (var app = start(url, wechat, APPID)) {
      assertEquals(savedIdentity, data(call(app, "GET", "/api/me/identity", null, aliceToken)));
      assertEquals(savedCard, data(call(app, "GET", "/api/me/card", null, aliceToken)));
      data(call(app, "DELETE", "/api/auth/session", null, aliceToken));
      assertEquals(401, call(app, "GET", "/api/me/identity", null, aliceToken).statusCode());
      assertEquals(401, call(app, "PUT", "/api/me/identity", "{\"tag\":\"enterprise\"}", aliceToken).statusCode());
      String returningToken = data(call(app, "POST", "/api/auth/session",
          "{\"code\":\"alice-returning\"}", null)).path("accessToken").asText();
      assertEquals(savedIdentity, data(call(app, "GET", "/api/me/identity", null, returningToken)));
    }
  }

  @Test
  void identityRejectsMissingInvalidMultipleAndForgedFieldsWithoutChangingTheSavedTag() throws Exception {
    String url = url("identity-validation");
    try (var app = start(url, wechat(), APPID)) {
      String token = data(call(app, "POST", "/api/auth/session",
          "{\"code\":\"alice-code\",\"phoneCode\":\"alice-phone\"}", null)).path("accessToken").asText();
      var saved = data(call(app, "PUT", "/api/me/identity", "{\"tag\":\"scientist\"}", token));
      for (String body : List.of("null", "[]", "{}", "{\"tag\":null}", "{\"tag\":\"\"}",
          "{\"tag\":\" \"}", "{\"tag\":42}", "{\"tag\":true}",
          "{\"tag\":[\"enterprise\",\"investor\"]}", "{\"tags\":[\"enterprise\"]}",
          "{\"tag\":\"enterprise,investor\"}", "{\"tag\":\"other\"}",
          "{\"tag\":\"企业\"}", "{\"tag\":\"enterprise\",\"label\":\"投资人\"}",
          "{\"tag\":\"enterprise\",\"userId\":\"forged\"}",
          "{\"tag\":\"enterprise\",\"unknown\":true}")) {
        var response = call(app, "PUT", "/api/me/identity", body, token);
        assertEquals(400, response.statusCode(), body + " => " + response.body());
        assertEquals(saved, data(call(app, "GET", "/api/me/identity", null, token)));
        assertEquals(1, count(url, "SELECT COUNT(*) FROM app_user_identity"));
      }
    }
  }

  @Test
  void badCodesAndUpstreamFailuresNeverCreateSessionsOrExposeSecrets() throws Exception {
    HttpClient wechat = wechat();
    try (var app = start(url("errors"), wechat, APPID)) {
      for (String body : List.of("{}", "null", "{\"code\":42}", "{\"code\":\"\"}",
          "{\"code\":\"x\",\"openid\":\"forged\"}", "{\"code\":\"" + "x".repeat(257) + "\"}")) {
        assertEquals(400, call(app, "POST", "/api/auth/session", body, null).statusCode());
      }
      verifyNoInteractions(wechat);
      for (var entry : Map.of("invalid-code", 401, "busy-code", 503, "timeout-code", 503, "malformed-code", 503).entrySet()) {
        var response = call(app, "POST", "/api/auth/session", "{\"code\":\"" + entry.getKey() + "\"}", null);
        assertEquals(entry.getValue(), response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("X-Auth-Token").isEmpty());
        assertFalse(response.body().contains("test-app-secret"));
        assertFalse(response.body().contains("session_key"));
      }
    }
  }

  @Test
  void expiredSessionsAndSessionsForAnotherAppAreRejected() throws Exception {
    String url = url("expiry");
    String token;
    try (var app = start(url, wechat(), APPID)) {
      token = data(call(app, "POST", "/api/auth/session", "{\"code\":\"alice-code\",\"phoneCode\":\"alice-phone\"}", null)).path("accessToken").asText();
      try (var c = DriverManager.getConnection(url, "sa", "");
           var statement = c.prepareStatement("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME=0,EXPIRY_TIME=0 WHERE SESSION_ID=?")) {
        statement.setString(1, token);
        assertEquals(1, statement.executeUpdate());
      }
      assertEquals(401, call(app, "GET", "/api/me/card", null, token).statusCode());
      assertEquals(401, call(app, "GET", "/api/me/identity", null, token).statusCode());
      assertEquals(401, call(app, "PUT", "/api/me/identity", "{\"tag\":\"enterprise\"}", token).statusCode());
      token = data(call(app, "POST", "/api/auth/session", "{\"code\":\"alice-code-2\"}", null)).path("accessToken").asText();
    }
    try (var app = start(url, wechat(), "wx0000000000000000")) {
      assertEquals(401, call(app, "GET", "/api/me/card", null, token).statusCode());
      assertEquals(401, call(app, "GET", "/api/me/identity", null, token).statusCode());
      assertEquals(401, call(app, "PUT", "/api/me/identity", "{\"tag\":\"enterprise\"}", token).statusCode());
      assertEquals(401, call(app, "DELETE", "/api/auth/session", null, token).statusCode());
    }
  }


  @Test
  void phoneConsentIsRequiredAndPersistsWithoutChangingEditableCardPhone() throws Exception {
    String url = url("phone");
    String userId;
    try (var app = start(url, wechat(), APPID)) {
      var required = call(app, "POST", "/api/auth/session", "{\"code\":\"alice-login\"}", null);
      assertEquals(428, required.statusCode());
      assertTrue(required.headers().firstValue("X-Auth-Token").isEmpty());
      assertEquals(0, count(url, "SELECT COUNT(*) FROM app_user"));
      for (String body : List.of("{\"code\":\"alice\",\"phoneCode\":null}",
          "{\"code\":\"alice\",\"phoneCode\":\"\"}",
          "{\"code\":\"alice\",\"phoneCode\":\"x\",\"phoneNumber\":\"13800000001\"}")) {
        assertEquals(400, call(app, "POST", "/api/auth/session", body, null).statusCode());
      }
      var login = data(call(app, "POST", "/api/auth/session",
          "{\"code\":\"alice-login\",\"phoneCode\":\"alice-phone\"}", null));
      userId = login.path("userId").asText();
      assertTrue(login.path("phoneVerified").asBoolean());
      assertEquals("13800000001", login.path("phoneNumber").asText());
      assertEquals("86", login.path("countryCode").asText());
      String token = login.path("accessToken").asText();
      assertEquals("", data(call(app, "GET", "/api/me/card", null, token)).at("/values/phone").asText());
      data(call(app, "PUT", "/api/me/card", "{\"values\":{\"phone\":\"010-12345678\"}}", token));
      assertEquals("010-12345678", data(call(app, "GET", "/api/me/card", null, token)).at("/values/phone").asText());
      assertEquals(1, count(url, "SELECT COUNT(*) FROM app_user_phone"));
      data(call(app, "DELETE", "/api/auth/session", null, token));
    }
    try (var app = start(url, wechat(), APPID)) {
      var login = data(call(app, "POST", "/api/auth/session", "{\"code\":\"alice-returning\"}", null));
      assertEquals(userId, login.path("userId").asText());
      assertEquals("13800000001", login.path("phoneNumber").asText());
      String token = login.path("accessToken").asText();
      assertEquals("010-12345678", data(call(app, "GET", "/api/me/card", null, token)).at("/values/phone").asText());
      try (var c = DriverManager.getConnection(url, "sa", ""); var statement = c.createStatement()) {
        assertEquals(1, statement.executeUpdate("DELETE FROM app_user_phone"));
      }
      assertEquals(428, call(app, "GET", "/api/me/card", null, token).statusCode());
      assertEquals(428, call(app, "POST", "/api/auth/session", "{\"code\":\"alice-unbound\"}", token).statusCode());
      assertEquals(200, call(app, "DELETE", "/api/auth/session", null, token).statusCode());
    }
  }

  @Test
  void phoneCodesAreSingleUseAndFailuresNeverBindOrLeakSecrets() throws Exception {
    String url = url("phone-errors");
    HttpClient wechat = wechat();
    try (var app = start(url, wechat, APPID)) {
      for (var entry : Map.of("invalid-phone", 400, "busy-phone", 503, "timeout-phone", 503,
          "wrong-app-phone", 503, "bob-phone", 400, "bad-number-phone", 503).entrySet()) {
        var response = call(app, "POST", "/api/auth/session",
            "{\"code\":\"alice-login\",\"phoneCode\":\"" + entry.getKey() + "\"}", null);
        assertEquals(entry.getValue(), response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("X-Auth-Token").isEmpty());
        assertFalse(response.body().contains("test-api-token"));
        assertFalse(response.body().contains("test-app-secret"));
        assertFalse(response.body().contains("13800000001"));
      }
      assertEquals(0, count(url, "SELECT COUNT(*) FROM app_user"));
      assertEquals(0, count(url, "SELECT COUNT(*) FROM app_user_phone"));
      var login = data(call(app, "POST", "/api/auth/session",
          "{\"code\":\"alice-login\",\"phoneCode\":\"retry-phone\"}", null));
      String token = login.path("accessToken").asText();
      assertFalse(login.toString().contains("test-api-token"));
      var duplicate = call(app, "POST", "/api/auth/session",
          "{\"code\":\"alice-again\",\"phoneCode\":\"retry-phone\"}", token);
      assertEquals(400, duplicate.statusCode());
      assertEquals(200, call(app, "GET", "/api/me/card", null, token).statusCode());
      assertEquals(1, count(url, "SELECT COUNT(*) FROM app_user_phone"));
      verify(wechat, times(2)).send(argThat(req -> req.uri().getPath().equals("/cgi-bin/stable_token")), any(HttpResponse.BodyHandler.class));
    }
  }

  private int count(String url, String sql) throws Exception {
    try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement(); var r = s.executeQuery(sql)) {
      assertTrue(r.next()); return r.getInt(1);
    }
  }

  @SuppressWarnings("unchecked")
  private HttpClient wechat() throws Exception {
    HttpClient client = mock(HttpClient.class);
    var issued = new java.util.concurrent.atomic.AtomicInteger();
    var used = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
    doAnswer(invocation -> {
      HttpRequest request = invocation.getArgument(0);
      assertEquals("https", request.uri().getScheme());
      assertEquals("api.weixin.qq.com", request.uri().getHost());
      String body;
      if (request.uri().getPath().equals("/sns/jscode2session")) {
        assertTrue(request.uri().getRawQuery().contains("grant_type=authorization_code"));
        String query = request.uri().getRawQuery();
        if (query.contains("timeout-code")) throw new java.net.http.HttpTimeoutException("must not leak " + request.uri());
        body = query.contains("invalid-code") ? "{\"errcode\":40029,\"errmsg\":\"invalid code\"}"
            : query.contains("busy-code") ? "{\"errcode\":-1,\"errmsg\":\"busy\"}"
            : query.contains("malformed-code") ? "{\"openid\":\"alice\"}"
            : "{\"openid\":\"" + (query.contains("bob-code") ? "bob" : "alice") + "\",\"session_key\":\"test-session-key\"}";
      } else {
        assertEquals("POST", request.method());
        JsonNode input = JSON.readTree(requestBody(request));
        if (request.uri().getPath().equals("/cgi-bin/stable_token")) {
          assertEquals("client_credential", input.path("grant_type").asText());
          assertEquals(APPID, input.path("appid").asText());
          assertEquals("test-app-secret", input.path("secret").asText());
          assertEquals(issued.get() > 0, input.path("force_refresh").asBoolean());
          body = "{\"access_token\":\"test-api-token-" + issued.incrementAndGet() + "\",\"expires_in\":7200}";
        } else {
          assertEquals("/wxa/business/getuserphonenumber", request.uri().getPath());
          assertTrue(request.uri().getRawQuery().contains("access_token=test-api-token-"));
          String code = input.path("code").asText();
          assertTrue(input.path("openid").isTextual(), "phone request must bind the verified openid");
          if (code.equals("timeout-phone")) throw new java.net.http.HttpTimeoutException("must not leak " + request.uri());
          if (code.equals("invalid-phone") || code.equals("bob-phone") && !input.path("openid").asText().equals("bob")) body = "{\"errcode\":40029}";
          else if (code.equals("busy-phone")) body = "{\"errcode\":-1}";
          else if (code.equals("retry-phone") && issued.get() == 1) body = "{\"errcode\":40001}";
          else if (!used.add(code)) body = "{\"errcode\":40163}";
          else {
            String number = code.equals("bad-number-phone") ? "not-a-phone" : "13800000001";
            body = "{\"errcode\":0,\"phone_info\":{\"phoneNumber\":\"" + number
                + "\",\"purePhoneNumber\":\"" + number + "\",\"countryCode\":\"86\",\"watermark\":{\"appid\":\""
                + (code.equals("wrong-app-phone") ? "wx0000000000000000" : APPID) + "\",\"timestamp\":1789530000}}}";
          }
        }
      }
      HttpResponse<String> response = mock(HttpResponse.class);
      when(response.statusCode()).thenReturn(200);
      when(response.body()).thenReturn(body);
      return response;
    }).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    return client;
  }

  private String requestBody(HttpRequest request) throws Exception {
    var result = new java.util.concurrent.CompletableFuture<String>();
    var bytes = new java.io.ByteArrayOutputStream();
    request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
      public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
      public void onNext(java.nio.ByteBuffer buffer) { byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part); }
      public void onError(Throwable error) { result.completeExceptionally(error); }
      public void onComplete() { result.complete(bytes.toString(java.nio.charset.StandardCharsets.UTF_8)); }
    });
    return result.get(2, java.util.concurrent.TimeUnit.SECONDS);
  }

  private String url(String name) {
    return "jdbc:h2:file:" + temporary.resolve(name).toString().replace('\\', '/') + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
  }

  private ConfigurableApplicationContext start(String url, HttpClient client, String appid) throws Exception {
    for (String resource : List.of("/schema-mysql.sql", "/org/springframework/session/jdbc/schema-h2.sql")) {
      String schema;
      try (var input = getClass().getResourceAsStream(resource)) {
        assertNotNull(input);
        schema = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
            .replace(" CHARACTER SET ascii COLLATE ascii_bin", "")
            .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "")
            .replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ")
            .replace("IF NOT EXISTS IF NOT EXISTS", "IF NOT EXISTS")
            .replace("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ")
            .replace("CREATE UNIQUE INDEX ", "CREATE UNIQUE INDEX IF NOT EXISTS ");
      }
      try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
        for (String sql : schema.split(";")) if (!sql.isBlank()) s.execute(sql);
      }
    }
    return new SpringApplicationBuilder(WxCloudRunApplication.class)
        .initializers(context -> context.getBeanFactory().registerSingleton("wechatHttpClient", client))
        .run("--server.address=127.0.0.1", "--server.port=0",
            "--spring.datasource.url=" + url, "--spring.datasource.username=sa", "--spring.datasource.password=",
            "--spring.datasource.driver-class-name=org.h2.Driver", "--app.mode=mini",
            "--app.wechat.auth-mode=server", "--app.wechat.appid=" + appid,
            "--app.wechat.appsecret=test-app-secret", "--app.wechat.trusted-ingress=false",
            "--spring.main.banner-mode=off", "--logging.level.root=ERROR");
  }

  private URI address(ConfigurableApplicationContext app, String path) {
    return URI.create("http://127.0.0.1:" + ((ServletWebServerApplicationContext) app).getWebServer().getPort() + path);
  }

  private HttpResponse<String> call(ConfigurableApplicationContext app, String method, String path, String body, String token) throws Exception {
    return call(app, method, path, body, token, Map.of());
  }

  private HttpResponse<String> call(ConfigurableApplicationContext app, String method, String path, String body, String token, Map<String,String> headers) throws Exception {
    var request = HttpRequest.newBuilder(address(app, path)).timeout(java.time.Duration.ofSeconds(15));
    if (token != null) request.header("X-Auth-Token", token);
    headers.forEach(request::header);
    if (body != null) request.header("Content-Type", "application/json");
    return HTTP.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode data(HttpResponse<String> response) throws Exception {
    assertEquals(200, response.statusCode(), response.body());
    return JSON.readTree(response.body()).path("data");
  }
}
