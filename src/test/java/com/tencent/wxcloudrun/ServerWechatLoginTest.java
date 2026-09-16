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
      var login = call(app, "POST", "/api/auth/session", "{\"code\":\"alice-code\"}", null);
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
      String bobToken = data(call(app, "POST", "/api/auth/session", "{\"code\":\"bob-code\"}", null)).path("accessToken").asText();
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
      token = data(call(app, "POST", "/api/auth/session", "{\"code\":\"alice-code\"}", null)).path("accessToken").asText();
      try (var c = DriverManager.getConnection(url, "sa", "");
           var statement = c.prepareStatement("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME=0,EXPIRY_TIME=0 WHERE SESSION_ID=?")) {
        statement.setString(1, token);
        assertEquals(1, statement.executeUpdate());
      }
      assertEquals(401, call(app, "GET", "/api/me/card", null, token).statusCode());
      token = data(call(app, "POST", "/api/auth/session", "{\"code\":\"alice-code-2\"}", null)).path("accessToken").asText();
    }
    try (var app = start(url, wechat(), "wx0000000000000000")) {
      assertEquals(401, call(app, "GET", "/api/me/card", null, token).statusCode());
      assertEquals(401, call(app, "DELETE", "/api/auth/session", null, token).statusCode());
    }
  }

  @SuppressWarnings("unchecked")
  private HttpClient wechat() throws Exception {
    HttpClient client = mock(HttpClient.class);
    doAnswer(invocation -> {
      HttpRequest request = invocation.getArgument(0);
      assertEquals("https", request.uri().getScheme());
      assertEquals("api.weixin.qq.com", request.uri().getHost());
      assertEquals("/sns/jscode2session", request.uri().getPath());
      assertTrue(request.uri().getRawQuery().contains("grant_type=authorization_code"));
      String query = request.uri().getRawQuery();
      if (query.contains("timeout-code")) throw new java.net.http.HttpTimeoutException("must not leak " + request.uri());
      String body = query.contains("invalid-code") ? "{\"errcode\":40029,\"errmsg\":\"invalid code\"}"
          : query.contains("busy-code") ? "{\"errcode\":-1,\"errmsg\":\"busy\"}"
          : query.contains("malformed-code") ? "{\"openid\":\"alice\"}"
          : "{\"openid\":\"" + (query.contains("bob-code") ? "bob" : "alice") + "\",\"session_key\":\"test-session-key\"}";
      HttpResponse<String> response = mock(HttpResponse.class);
      when(response.statusCode()).thenReturn(200);
      when(response.body()).thenReturn(body);
      return response;
    }).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    return client;
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
