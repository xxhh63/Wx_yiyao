package com.tencent.wxcloudrun.content;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;
import static com.tencent.wxcloudrun.content.ContentValidation.bad;

@Component
public class CatalogSchema {
  private final JsonNode schema;
  public CatalogSchema(ObjectMapper json) throws IOException {
    try (var input=new ClassPathResource("catalog-schema.json").getInputStream()) { schema=json.readTree(input); }
    schema.forEach(catalog -> catalog.path("filtersByCategory").forEach(filters -> filters.forEach(filter -> {
      String key=filter.path("key").asText();
      if(!filter.has("field")&&Set.of("industry","cooperation").contains(key))
        ((ObjectNode)filter).put("field",key.equals("industry")?"industries":"cooperationModes");
    })));
  }
  public JsonNode get(String audience) {
    if (audience==null || !schema.has(audience)) throw bad("未知资源专区");
    return schema.get(audience).deepCopy();
  }
  public JsonNode filters(String audience,String category) {
    var filters=get(audience).path("filtersByCategory").get(category);
    if (filters==null) throw bad("未知资源分类");
    return filters;
  }
  public Map<String,Set<String>> attributes(String type) {
    Map<String,Set<String>> result=new LinkedHashMap<>();
    schema.forEach(catalog -> {
      // Preserve historical attributes used by shared resources without offering them as enterprise filters.
      List<JsonNode> definitions=new ArrayList<>();
      catalog.path("filtersByCategory").path(type).forEach(definitions::add);
      catalog.path("preservedFiltersByCategory").path(type).forEach(definitions::add);
      for (var f:definitions) {
        String field=f.path("field").asText(f.path("key").asText());
        if (Set.of("sort","kind","industries","cooperationModes","cooperation").contains(field)) continue;
        Set<String> values=result.computeIfAbsent(field,ignored->new HashSet<>());
        f.path("options").forEach(o->{if(!o.path("value").asText().equals("all"))values.add(o.path("value").asText());});
      }
    });
    return result;
  }
}
