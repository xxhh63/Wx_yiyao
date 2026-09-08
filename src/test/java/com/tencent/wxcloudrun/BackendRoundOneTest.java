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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class BackendRoundOneTest {
  private static final String APPID = "wx2948f6a7ea6a688b";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final String identityPrefix = java.util.UUID.randomUUID().toString() + "_";
  @TempDir Path temporary;

  @Test
  void healthIdentityAndCardSurviveRestartWithoutCrossUserAccess() throws Exception {
    String url = databaseUrl("persistence");
    String userId;
    String savedAt;
    try (var app = start(url, true)) {
      assertEquals(200, call(app, "GET", "/api/health", null, null, APPID).statusCode());
      assertEquals(200, call(app, "GET", "/api/ready", null, null, APPID).statusCode());
      assertEquals(401, call(app, "POST", "/api/auth/session", null, null, APPID).statusCode());
      assertEquals(401, call(app, "GET", "/api/me/card", null, null, APPID).statusCode());
      assertEquals(401, call(app, "PUT", "/api/me/card", "{\"values\":{}}", null, APPID).statusCode());
      assertEquals(401, call(app, "POST", "/api/auth/session", null, "alice", "wx0000000000000000").statusCode());

      var jobs = new ArrayList<CompletableFuture<HttpResponse<String>>>();
      for (int i = 0; i < 6; i++) {
        jobs.add(CompletableFuture.supplyAsync(() -> {
          try { return call(app, "POST", "/api/auth/session", null, "alice", APPID); }
          catch (Exception e) { throw new RuntimeException(e); }
        }));
      }
      var identities = new ArrayList<String>();
      for (var job : jobs) {
        var response = job.join();
        assertEquals(200, response.statusCode(), response.body());
        identities.add(data(response).get("userId").asText());
      }
      assertEquals(1, identities.stream().distinct().count());
      userId = identities.getFirst();
      assertFalse(userId.isBlank());
      assertFalse(data(call(app, "POST", "/api/auth/session", null, "alice", APPID)).has("openid"));
      var empty = data(call(app, "GET", "/api/me/card", null, "alice", APPID));
      assertTrue(empty.get("savedAt").isNull());
      assertEquals("", empty.at("/values/name").asText());

      int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
      var missingSource = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/me/card"))
          .header("X-WX-APPID", APPID).header("X-WX-OPENID", identityPrefix + "alice").GET();
      assertEquals(401, HTTP.send(missingSource.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
      var duplicate = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/me/card"))
          .header("X-WX-SOURCE", "test").header("X-WX-APPID", APPID)
          .header("X-WX-OPENID", identityPrefix + "alice").header("X-WX-OPENID", identityPrefix + "bob").GET();
      assertEquals(401, HTTP.send(duplicate.build(), HttpResponse.BodyHandlers.ofString()).statusCode());

      String card = """
          {"values":{"name":"  测试用户 😀  ","phone":"+86 138-0013-8000","company":"示例科研院所",
          "position":"研究员","address":"广州市","email":"test@example.com","wechat":"sample_wechat","intro":"科研转化\\n合作交流"}}
          """;
      var save = call(app, "PUT", "/api/me/card", card, "alice", APPID);
      assertEquals(200, save.statusCode(), save.body());
      assertEquals("no-store", save.headers().firstValue("Cache-Control").orElse(""));
      assertEquals("测试用户 😀", data(save).at("/values/name").asText());
      savedAt = data(save).get("savedAt").asText();
      assertTrue(savedAt.endsWith("Z"));

      for (String bad : List.of(
          "{\"values\":{\"phone\":\"123\"}}",
          "{\"values\":{\"email\":\"invalid\"}}",
          "{\"values\":{\"name\":42}}",
          "{\"values\":{\"name\":null}}",
          "{\"values\":{\"name\":\"" + "字".repeat(31) + "\"}}",
          "{\"values\":{\"intro\":\"" + "😀".repeat(501) + "\"}}",
          "{\"values\":{},\"userId\":\"someone-else\"}",
          "{\"values\":{\"openid\":\"someone-else\"}}", "{}", "null", "{")) {
        var rejected = call(app, "PUT", "/api/me/card", bad, "alice", APPID);
        assertEquals(400, rejected.statusCode(), rejected.body());
        assertNotEquals(0, JSON.readTree(rejected.body()).get("code").asInt());
      }
      assertEquals("测试用户 😀", data(call(app, "GET", "/api/me/card", null, "alice", APPID)).at("/values/name").asText());
      assertTrue(data(call(app, "GET", "/api/me/card", null, "bob", APPID)).get("savedAt").isNull());
      assertEquals(200, call(app, "PUT", "/api/me/card", "{\"values\":{\"name\":\"Bob\"}}", "bob", APPID).statusCode());
      // Case must stay significant for WeChat IDs, including in the production MySQL schema.
      assertTrue(data(call(app, "GET", "/api/me/card", null, "ALICE", APPID)).get("savedAt").isNull());
    }
    try (var app = start(url, true)) {
      assertEquals(userId, data(call(app, "POST", "/api/auth/session", null, "alice", APPID)).get("userId").asText());
      var restored = data(call(app, "GET", "/api/me/card", null, "alice", APPID));
      assertEquals("测试用户 😀", restored.at("/values/name").asText());
      assertEquals(savedAt, restored.get("savedAt").asText());
      assertEquals("Bob", data(call(app, "GET", "/api/me/card", null, "bob", APPID)).at("/values/name").asText());
      assertEquals(200, call(app, "PUT", "/api/me/card", "{\"values\":{\"name\":\"更新姓名\"}}", "alice", APPID).statusCode());
      assertEquals("", data(call(app, "GET", "/api/me/card", null, "alice", APPID)).at("/values/company").asText());
    }
  }

  @Test
  void identityIsDisabledUntilTrustedIngressIsExplicitlyEnabled() throws Exception {
    try (var app = start(databaseUrl("closed"), false)) {
      assertEquals(200, call(app, "GET", "/api/health", null, null, APPID).statusCode());
      assertEquals(503, call(app, "POST", "/api/auth/session", null, "forged", APPID).statusCode());
      assertEquals(503, call(app, "GET", "/api/me/card", null, "forged", APPID).statusCode());
      assertEquals(503, call(app, "PUT", "/api/me/card", "{\"values\":{}}", "forged", APPID).statusCode());
      assertEquals(404, call(app, "POST", "/api/count", "{\"action\":\"inc\"}", null, APPID).statusCode());
    }
  }

  private String databaseUrl(String name) {
    String external = System.getenv("BACKEND_TEST_JDBC_URL");
    return external != null ? external : "jdbc:h2:file:" + temporary.resolve(name).toString().replace('\\', '/')
        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
  }

  private ConfigurableApplicationContext start(String url, boolean trust) throws Exception {
    String username = System.getenv().getOrDefault("BACKEND_TEST_DB_USER", "sa");
    String password = System.getenv().getOrDefault("BACKEND_TEST_DB_PASSWORD", "");
    // Only a dedicated disposable schema may be supplied through BACKEND_TEST_JDBC_URL.
    var schemaResource = getClass().getResourceAsStream("/schema-mysql.sql");
    if (schemaResource != null) {
      String schema;
      try (schemaResource) { schema = new String(schemaResource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
      if (url.startsWith("jdbc:h2:")) {
        schema = schema.replace(" CHARACTER SET ascii COLLATE ascii_bin", "")
            .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
      }
      try (var connection = DriverManager.getConnection(url, username, password);
           var statement = connection.createStatement()) {
        for (String sql : schema.split(";")) if (!sql.isBlank()) statement.execute(sql);
      }
    }
    return new SpringApplicationBuilder(WxCloudRunApplication.class).run(
        "--server.address=127.0.0.1", "--server.port=0",
        "--spring.datasource.url=" + url, "--spring.datasource.username=" + username,
        "--spring.datasource.password=" + password,
        "--spring.datasource.driver-class-name=" + (url.startsWith("jdbc:h2:") ? "org.h2.Driver" : "com.mysql.cj.jdbc.Driver"),
        "--app.wechat.appid=" + APPID, "--app.wechat.trusted-ingress=" + trust,
        "--spring.main.banner-mode=off", "--logging.level.root=WARN");
  }

  private HttpResponse<String> call(ConfigurableApplicationContext app, String method, String path,
      String body, String openid, String appid) throws Exception {
    int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
    if (openid != null) request.header("X-WX-OPENID", identityPrefix + openid).header("X-WX-APPID", appid).header("X-WX-SOURCE", "test");
    if (body != null) request.header("Content-Type", "application/json");
    return HTTP.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode data(HttpResponse<String> response) throws Exception {
    assertEquals(200, response.statusCode(), response.body());
    var root = JSON.readTree(response.body());
    assertEquals(0, root.get("code").asInt(), response.body());
    return root.get("data");
  }
}
