package com.tencent.wxcloudrun;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CONTENT_TEST_JDBC_URL",matches="jdbc:mysql:.*")
class ContentApiTest {
  @Test void contentReadEndpointsValidateRequestsWithoutRegisteringUser() throws Exception {
    try (var app = new SpringApplicationBuilder(WxCloudRunApplication.class).run(
        "--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=ERROR",
        "--app.mode=mini", "--app.wechat.trusted-ingress=false",
        "--spring.datasource.url=" + System.getenv("CONTENT_TEST_JDBC_URL"),
        "--spring.datasource.username=" + System.getenv("CONTENT_TEST_DB_USER"),
        "--spring.datasource.password=" + System.getenv("CONTENT_TEST_DB_PASSWORD"))) {
      int port=((ServletWebServerApplicationContext)app).getWebServer().getPort();
      var http=HttpClient.newHttpClient();
      var result=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/home")).GET().build(),HttpResponse.BodyHandlers.ofString());
      assertEquals(200,result.statusCode(),result.body());
      var body=new ObjectMapper().readTree(result.body());
      assertEquals(0,body.path("code").asInt(-1));
      assertTrue(body.at("/data/featured").isArray());
      assertTrue(body.at("/data/stats/technicalDemands").isIntegralNumber());
      for(String path:new String[]{"/api/resources?audience=unknown","/api/policies?page=0"}) {
        var bad=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(400,bad.statusCode(),bad.body());
      }
    }
  }
}
