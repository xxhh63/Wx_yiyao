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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdminSecurityTest {
  // Deliberate test-only BCrypt credential (password: password); never a production default.
  private static final String HASH = org.springframework.security.crypto.bcrypt.BCrypt.hashpw("password", org.springframework.security.crypto.bcrypt.BCrypt.gensalt(10));
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  @TempDir Path temporary;

  @Test
  void adminRequiresCsrfRotatesSessionAndPersistsLoginAcrossInstances() throws Exception {
    String url = url("admin");
    try (var first = start(url, "admin", true); var second = start(url, "admin", true)) {
      assertEquals(200, call(first, "GET", "/api/health", null, null, null).statusCode());
      assertEquals(200, call(first, "GET", "/api/ready", null, null, null).statusCode());
      assertEquals(401, call(first, "GET", "/login", null, null, null).statusCode());
      for(String entry:List.of("/admin","/admin/")) {
        var redirect=call(first,"GET",entry,null,null,null);
        assertEquals(302,redirect.statusCode(),redirect.body());
        assertEquals("/admin/index.html",redirect.headers().firstValue("Location").orElse(""));
      }
      for (String privatePath : List.of("/api/auth/session", "/api/me/card")) {
        var rejected = call(first, privatePath.contains("auth") ? "POST" : "GET", privatePath, null, null, null);
        assertTrue(rejected.statusCode() == 401 || rejected.statusCode() == 403, rejected.body());
      }
      assertEquals(401, call(first, "GET", "/admin/api/resources", null, null, null).statusCode());
      assertEquals(403, call(first, "POST", "/admin/api/session", "username=editor&password=password", null, null).statusCode());
      var anonymous = call(first, "GET", "/admin/api/session", null, null, null);
      assertEquals(200, anonymous.statusCode(), anonymous.body());
      assertFalse(data(anonymous).path("authenticated").asBoolean());
      String before = cookie(anonymous);
      assertTrue(anonymous.headers().allValues("set-cookie").stream().anyMatch(v -> v.contains("HttpOnly") && v.contains("SameSite=Lax")));
      String csrf = data(anonymous).path("csrfToken").asText();
      assertFalse(csrf.isBlank());
      assertEquals("X-CSRF-TOKEN", data(anonymous).path("csrfHeaderName").asText());
      var loggedIn = call(first, "POST", "/admin/api/session", "username=editor&password=password", before, csrf);
      assertEquals(200, loggedIn.statusCode(), loggedIn.body());
      String after = cookie(loggedIn);
      assertNotEquals(before, after);
      var state = call(second, "GET", "/admin/api/session", null, after, null);
      assertTrue(data(state).path("authenticated").asBoolean(), state.body());
      assertEquals("editor", data(state).path("username").asText());
      assertEquals(403, call(second, "GET", "/api/me/card", null, after, null).statusCode());
      assertEquals(403, call(second, "POST", "/admin/api/security-test", "value=anything", after, null).statusCode());
      assertEquals(200, call(second, "POST", "/admin/api/security-test", "value=anything", after, data(state).path("csrfToken").asText()).statusCode());
      var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup((org.springframework.web.context.WebApplicationContext) second)
          .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin/api/security-test")
          .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("viewer").roles("VIEWER")))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
      assertFalse(data(call(second, "GET", "/admin/api/session", null, before, null)).path("authenticated").asBoolean());
      assertEquals(403, call(second, "DELETE", "/admin/api/session", null, after, null).statusCode());
      assertEquals(200, call(second, "DELETE", "/admin/api/session", null, after, data(state).path("csrfToken").asText()).statusCode());
      assertFalse(data(call(first, "GET", "/admin/api/session", null, after, null)).path("authenticated").asBoolean());
    }
  }

  @Test
  void loginFailuresAreSharedAndBoundedInDatabase() throws Exception {
    String url = url("throttle");
    try (var first = start(url, "admin", true); var second = start(url, "admin", true)) {
      var anonymous = call(first, "GET", "/admin/api/session", null, null, null);
      assertEquals(200, anonymous.statusCode(), anonymous.body());
      String cookie = cookie(anonymous);
      String csrf = data(anonymous).path("csrfToken").asText();
      for (int index = 0; index < 5; index++) {
        var failed = call(index % 2 == 0 ? first : second, "POST", "/admin/api/session", "username=unknown" + index + "&password=wrong", cookie, csrf);
        assertEquals(401, failed.statusCode(), failed.body());
      }
      assertEquals(429, call(second, "POST", "/admin/api/session", "username=editor&password=password", cookie, csrf).statusCode());
      try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
        var rows = statement.executeQuery("SELECT account_key, failure_count FROM admin_login_attempt");
        assertTrue(rows.next());
        assertTrue(rows.getString(1).matches("[a-f0-9]{64}"));
        assertEquals(5, rows.getInt(2));
        assertFalse(rows.next(), "arbitrary usernames must not create unbounded rows");
        statement.executeUpdate("UPDATE admin_login_attempt SET window_start=0, locked_until=0");
      }
      assertEquals(200, call(second, "POST", "/admin/api/session", "username=editor&password=password", cookie, csrf).statusCode());
    }
  }

  @Test
  void longPasswordIsRejectedAndSessionExpiryIsEnforced() throws Exception {
    String url = url("expiry");
    try (var app = start(url, "admin", true)) {
      var anonymous = call(app, "GET", "/admin/api/session", null, null, null);
      String cookie = cookie(anonymous);
      String csrf = data(anonymous).path("csrfToken").asText();
      assertEquals(401, call(app, "POST", "/admin/api/session", "username=editor&password=" + "x".repeat(1000), cookie, csrf).statusCode());
      var loggedIn = call(app, "POST", "/admin/api/session", "username=editor&password=password", cookie, csrf);
      assertEquals(200, loggedIn.statusCode(), loggedIn.body());
      String authenticatedCookie = cookie(loggedIn);
      try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
        var session = statement.executeQuery("SELECT MAX_INACTIVE_INTERVAL FROM SPRING_SESSION WHERE PRINCIPAL_NAME='editor'");
        assertTrue(session.next());
        assertEquals(1800, session.getInt(1));
        statement.executeUpdate("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME=0, EXPIRY_TIME=0 WHERE PRINCIPAL_NAME='editor'");
      }
      assertFalse(data(call(app, "GET", "/admin/api/session", null, authenticatedCookie, null)).path("authenticated").asBoolean());
    }
  }

  @Test
  void secureCookieIsDefaultAndAdminNeverRegistersWechatIdentity() throws Exception {
    try (var app = start(url("secure"), "admin", true, true)) {
      var response = call(app, "GET", "/admin/api/session", null, null, null);
      assertEquals(200, response.statusCode());
      assertTrue(response.headers().allValues("set-cookie").stream().anyMatch(value -> value.contains("Secure")));
      for(String image:List.of("service-lab.png","service-equipment.png","service-material.png")) {
        int status=call(app,"GET","/assets/phase1/"+image,null,null,null).statusCode();
        assertEquals(200,status,"known legacy image must be readable");
      }
      assertEquals(401,call(app,"GET","/assets/phase1/unknown.png",null,null,null).statusCode());
      assertTrue(app.getBeansOfType(com.tencent.wxcloudrun.config.WechatIdentityResolver.class).isEmpty());
      assertTrue(app.getBeansOfType(com.tencent.wxcloudrun.controller.ProfileController.class).isEmpty());
    }
  }

  @Test
  void miniDisablesAdminAndAdminRejectsMissingCredentials() throws Exception {
    try (var app = start(url("mini"), "mini", false)) {
      var response = call(app, "GET", "/admin/api/session", null, null, null);
      assertTrue(response.statusCode() == 401 || response.statusCode() == 403, response.body());
    }
    assertThrows(Exception.class, () -> start(url("missing"), "admin", false));
  }

  private String url(String name) {
    return "jdbc:h2:file:" + temporary.resolve(name).toString().replace('\\', '/') + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
  }

  private ConfigurableApplicationContext start(String url, String mode, boolean credentials) throws Exception {
    return start(url, mode, credentials, false);
  }

  private ConfigurableApplicationContext start(String url, String mode, boolean credentials, boolean defaultSecure) throws Exception {
    // Session DDL is supplied by the matching Spring Session dependency once auth is implemented.
    for (String resource : List.of("/org/springframework/session/jdbc/schema-h2.sql", "/migration/003-admin.sql")) {
      var input = getClass().getResourceAsStream(resource);
      if (input == null) continue;
      String schema;
      try (input) { schema = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
      if (resource.contains("003-admin")) {
        int offset = schema.indexOf("CREATE TABLE IF NOT EXISTS admin_login_attempt");
        schema = offset < 0 ? "" : schema.substring(offset)
            .replace(" CHARACTER SET ascii COLLATE ascii_bin", "")
            .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
      } else {
        schema = schema.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ").replace("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ").replace("CREATE UNIQUE INDEX ", "CREATE UNIQUE INDEX IF NOT EXISTS ");
      }
      try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
        for (String sql : schema.split(";")) if (!sql.isBlank()) statement.execute(sql);
      }
    }
    var args = new ArrayList<>(List.of("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + url,
        "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
        "--app.mode=" + mode, "--app.wechat.appid=wx2948f6a7ea6a688b", "--app.wechat.trusted-ingress=true",
        "--spring.main.banner-mode=off", "--logging.level.root=ERROR", "--logging.level.org.springframework.boot.SpringApplication=OFF",
        "--app.admin.username=" + (credentials ? "editor" : ""), "--app.admin.password-hash=" + (credentials ? HASH : "")));
    if (!defaultSecure) args.add("--server.servlet.session.cookie.secure=false");
    return new SpringApplicationBuilder(WxCloudRunApplication.class, AdminProbe.class).run(args.toArray(String[]::new));
  }

  private HttpResponse<String> call(ConfigurableApplicationContext app, String method, String path, String body,
      String cookie, String csrf) throws Exception {
    int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .header("X-WX-SOURCE", "forged").header("X-WX-APPID", "wx2948f6a7ea6a688b").header("X-WX-OPENID", "attacker");
    if (cookie != null) request.header("Cookie", cookie);
    if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
    if (body != null) request.header("Content-Type", "application/x-www-form-urlencoded");
    return HTTP.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  @org.springframework.boot.test.context.TestConfiguration
  @org.springframework.web.bind.annotation.RestController
  static class AdminProbe {
    @org.springframework.web.bind.annotation.RequestMapping("/admin/api/security-test")
    public com.tencent.wxcloudrun.config.ApiResponse probe() { return com.tencent.wxcloudrun.config.ApiResponse.ok(); }
  }

  private String cookie(HttpResponse<String> response) {
    return response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
  }

  private JsonNode data(HttpResponse<String> response) throws Exception {
    assertEquals(200, response.statusCode(), response.body());
    return JSON.readTree(response.body()).path("data");
  }
}