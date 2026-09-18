package com.tencent.wxcloudrun.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedPoolTest {
  @Test void poolUnifiesPublishedRoleResourcesWithoutDuplicatingOrChangingClassification() throws Exception {
    var json = new ObjectMapper();
    var schema = new CatalogSchema(json);
    var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
    jdbc.execute("CREATE TABLE content_resource (id VARCHAR(96) PRIMARY KEY, payload VARCHAR(4000), publication_status VARCHAR(16), sort_order INT DEFAULT 0, version BIGINT DEFAULT 1, published_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, resource_type VARCHAR(32), kind VARCHAR(16) DEFAULT 'supply', business_status VARCHAR(16) DEFAULT 'open')");
    jdbc.execute("CREATE TABLE content_resource_view (resource_id VARCHAR(96), audience VARCHAR(32), category VARCHAR(32), sort_order INT DEFAULT 0, legacy_id VARCHAR(96), UNIQUE(audience, resource_id))");
    var sql = new NamedParameterJdbcTemplate(jdbc);
    var store = new ContentStore(sql, json);
    var service = new ContentService(store, schema, new ContentValidation(schema), json, sql);
    var expected = new TreeSet<String>();
    for (String role : List.of("pool", "investor", "enterprise", "scientist", "manager")) {
      for (var category : schema.get(role).path("categories")) {
        String type = category.path("value").asText();
        if (type.equals("all") || category.path("pending").asBoolean()) continue;
        String id = role + "-" + type;
        jdbc.update("INSERT INTO content_resource(id,payload,publication_status,resource_type) VALUES(?,?,'PUBLISHED',?)", id, "{\"title\":\"test\",\"tags\":[\"保留标签\"],\"attributes\":{},\"sections\":[]}", type);
        jdbc.update("INSERT INTO content_resource_view(resource_id,audience,category,legacy_id) VALUES(?,?,?,?)", id, role, type, "old-" + id);
        expected.add(id);
      }
    }
    // The same resource in two columns must not occupy two pagination slots.
    jdbc.update("INSERT INTO content_resource_view(resource_id,audience,category,legacy_id) VALUES('investor-project','pool','project','shared-project')");
    for (String state : List.of("DRAFT", "OFFLINE")) {
      jdbc.update("INSERT INTO content_resource(id,payload,publication_status,resource_type) VALUES(?,'{}',?,'project')", state, state);
      jdbc.update("INSERT INTO content_resource_view(resource_id,audience,category,legacy_id) VALUES(?,'investor','project',?)", state, state);
    }
    jdbc.update("INSERT INTO content_resource(id,payload,publication_status,resource_type) VALUES('unassigned','{}','PUBLISHED','project'),('retired','{}','PUBLISHED','project')");
    jdbc.update("INSERT INTO content_resource_view(resource_id,audience,category,legacy_id) VALUES('retired','enterprise','retired-category','retired')");
    var actual = new ArrayList<String>();
    for (int page = 1; actual.size() < expected.size(); page++) {
      JsonNode result = json.valueToTree(service.list("resources", Map.of("audience", "pool", "page", "" + page, "pageSize", "3"), false));
      assertEquals(expected.size(), result.path("total").asInt(), "all five columns contribute to the distinct total");
      assertFalse(result.path("items").isEmpty());
      for (var item : result.path("items")) {
        actual.add(item.path("id").asText());
        assertEquals("保留标签", item.path("tags").get(0).asText());
        assertTrue(item.path("attributes").isObject(), "optional unfilled classifications stay available");
      }
    }
    assertEquals(new ArrayList<>(expected), actual, "stable global pagination without duplicates");
    assertEquals(2, json.valueToTree(service.list("resources", Map.of("audience", "pool"), true)).path("total").asInt(), "admin keeps its original column scope");
    for (String role : List.of("investor", "enterprise", "scientist", "manager")) {
      var result = json.valueToTree(service.list("resources", Map.of("audience", role, "pageSize", "50"), false));
      assertEquals(expected.stream().filter(id -> id.startsWith(role + "-")).count(), result.path("total").asLong());
    }
    var detail = service.detail("resources", "enterprise-technology", "pool", false);
    assertEquals("enterprise-technology", detail.path("id").asText());
    assertEquals("保留标签", detail.path("tags").get(0).asText());
    assertEquals("investor-project", service.detail("resources", "shared-project", "pool", false).path("id").asText(), "old pool links still resolve");
    assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.detail("resources", "DRAFT", "pool", false));
  }
}
