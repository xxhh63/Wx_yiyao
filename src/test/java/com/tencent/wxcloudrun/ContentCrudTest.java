package com.tencent.wxcloudrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.wxcloudrun.content.ContentService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="CONTENT_TEST_JDBC_URL",matches="jdbc:mysql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentCrudTest {
  private final ObjectMapper json=new ObjectMapper();
  private final HttpClient http=HttpClient.newHttpClient();
  private ConfigurableApplicationContext app;
  private ContentService service;
  private JdbcTemplate jdbc;
  private int port;
  private String actor;
  private long users,cards;

  @BeforeAll void start() {
    app=new SpringApplicationBuilder(WxCloudRunApplication.class).run(
        "--server.address=127.0.0.1","--server.port=0","--spring.main.banner-mode=off","--logging.level.root=ERROR",
        "--app.mode=mini","--app.wechat.trusted-ingress=false",
        "--spring.datasource.url="+System.getenv("CONTENT_TEST_JDBC_URL"),
        "--spring.datasource.username="+System.getenv("CONTENT_TEST_DB_USER"),
        "--spring.datasource.password="+System.getenv("CONTENT_TEST_DB_PASSWORD"));
    service=app.getBean(ContentService.class);jdbc=app.getBean(JdbcTemplate.class);
    port=((ServletWebServerApplicationContext)app).getWebServer().getPort();
    users=jdbc.queryForObject("SELECT COUNT(*) FROM app_user",Long.class);
    cards=jdbc.queryForObject("SELECT COUNT(*) FROM user_card",Long.class);
  }
  @BeforeEach void identifyOwnRows(){actor="crud-test-"+UUID.randomUUID();}
  @AfterEach void removeOnlyOwnRows(){
    if(jdbc==null)return;
    // Every test creates fresh rows. The unique actor restricts cleanup to this test's committed audit entries.
    var own=jdbc.queryForList("SELECT DISTINCT entity_type,entity_id FROM content_audit WHERE actor=?",actor);
    for(String collection:List.of("featured","promos","policies","resources","institutions")) {
      String table=Map.of("featured","home_featured","promos","home_promo","policies","policy_article","resources","content_resource","institutions","service_institution").get(collection);
      for(var row:own)if(row.get("entity_type").equals(collection)) {
        String id=row.get("entity_id").toString();
        if(collection.equals("resources"))jdbc.update("DELETE FROM content_resource_view WHERE resource_id=?",id);
        jdbc.update("DELETE FROM "+table+" WHERE id=?",id);
      }
    }
    jdbc.update("DELETE FROM content_audit WHERE actor=?",actor);
  }
  @AfterAll void close(){
    if(app==null)return;
    try{assertEquals(users,jdbc.queryForObject("SELECT COUNT(*) FROM app_user",Long.class));assertEquals(cards,jdbc.queryForObject("SELECT COUNT(*) FROM user_card",Long.class));}
    finally{app.close();}
  }

  @Test void policyCategorySurvivesSavePublicationAndPublicFilter() throws Exception {
    var policy=create("policies",policy());
    assertEquals("知识产权",policy.path("category").asText());
    policy=publish("policies",policy,"PUBLISHED");
    assertEquals("知识产权",policy.path("category").asText());
    var result=get("/api/policies?category="+encode("知识产权")+"&keyword="+actor);
    assertEquals(1,result.path("total").asInt());
    assertEquals(policy.path("id").asText(),result.path("items").get(0).path("id").asText());
  }

  @Test void editingLegacyContentWithoutSourceFieldsPreservesProvenance() throws Exception {
    var created=create("resources",resource().put("sourceKind","legacy_sample").put("sourceNote","original source preserved"));
    var edited=created.deepCopy();edited.remove(List.of("sourceKind","sourceNote"));edited.put("title",actor+" edited");
    var saved=service.save("resources",created.path("id").asText(),edited,actor);
    assertEquals("legacy_sample",saved.path("sourceKind").asText());
    assertEquals("original source preserved",saved.path("sourceNote").asText());
  }

  @Test void optionalNullUrlsAndSelfContainedPromoTargetsNormalize() throws Exception {
    var policy=create("policies",policy().putNull("sourceUrl"));
    assertEquals("",policy.path("sourceUrl").asText());
    for(String type:List.of("article","none")) {
      var input=node("{\"title\":\"test\",\"action\":{\"type\":\""+type+"\",\"targetId\":null}}");
      var saved=create("promos",input);
      assertEquals(type.equals("article")?saved.path("id").asText():"",saved.at("/action/targetId").asText());
      for(String status:List.of("PUBLISHED","OFFLINE")) {
        saved=publish("promos",saved,status);
        assertEquals(type.equals("article")?saved.path("id").asText():"",saved.at("/action/targetId").asText());
      }
    }
  }

  @Test void migratedAndUiTonesCanBeSavedPublishedAndTakenOffline() throws Exception {
    for(String tone:List.of("robot","network","navy")) {
      var created=create("resources",resource().put("tone",tone));
      var published=publish("resources",created,"PUBLISHED");
      assertEquals("OFFLINE",publish("resources",published,"OFFLINE").path("publicationStatus").asText());
    }
  }

  @Test void poolFiltersUseTopLevelArraysAndCatalogDoesNotInventAttributes() throws Exception {
    var resource=publish("resources",create("resources",resource()),"PUBLISHED");
    var result=get("/api/resources?audience=pool&category=project&keyword="+actor+"&industry="+encode("生物医药")+"&cooperation="+encode("投资合作"));
    assertEquals(1,result.path("total").asInt());
    assertEquals(resource.path("id").asText(),result.path("items").get(0).path("id").asText());
    var noMatch=get("/api/resources?audience=pool&category=project&keyword="+actor+"&industry="+encode("新能源"));
    assertEquals(0,noMatch.path("total").asInt());
    var catalog=get("/api/catalogs/pool");
    var fields=new HashMap<String,String>();
    for(var filter:catalog.at("/filtersByCategory/project"))fields.put(filter.path("key").asText(),filter.path("field").asText());
    assertEquals("industries",fields.get("industry"));assertEquals("cooperationModes",fields.get("cooperation"));
    assertFalse(resource.path("attributes").has("industry"));assertFalse(resource.path("attributes").has("cooperation"));
  }

  @Test void firstPublicationTimeAndAuditReflectTheActualOperation() throws Exception {
    var draft=create("resources",resource());String id=draft.path("id").asText();
    jdbc.update("UPDATE content_resource SET published_at='2001-01-01 00:00:00' WHERE id=?",id);
    Instant before=Instant.now().minusSeconds(2);
    var published=publish("resources",draft,"PUBLISHED");
    assertTrue(Instant.parse(published.path("publishedAt").asText()).isAfter(before));
    var edit=published.deepCopy().put("title",actor+" updated");
    var updated=service.save("resources",id,edit,actor);
    assertEquals(published.path("publishedAt"),updated.path("publishedAt"));
    var offline=publish("resources",updated,"OFFLINE");
    var republished=publish("resources",offline,"PUBLISHED");
    assertEquals(published.path("publishedAt"),republished.path("publishedAt"));
    assertEquals(List.of("CREATE","PUBLISH","UPDATE","UNPUBLISH","PUBLISH"),jdbc.queryForList("SELECT operation FROM content_audit WHERE actor=? ORDER BY id",String.class,actor));
  }

  @Test void featuredListShowsResourceTitleAndStaleWritesDoNotOverwrite() throws Exception {
    var resource=publish("resources",create("resources",resource()),"PUBLISHED");
    var featured=create("featured",json.createObjectNode().put("resourceId",resource.path("id").asText()));
    var items=json.valueToTree(service.list("featured",Map.of("pageSize","50"),true)).path("items");
    JsonNode found=null;for(var item:items)if(item.path("id").equals(featured.path("id")))found=item;
    assertNotNull(found);assertEquals(actor,found.path("resourceTitle").asText());assertEquals(actor,found.path("title").asText());
    var matched=json.valueToTree(service.list("featured",Map.of("keyword",actor),true));
    assertEquals(1,matched.path("total").asInt());
    assertEquals(featured.path("id"),matched.path("items").get(0).path("id"));
    var unrelated=json.valueToTree(service.list("featured",Map.of("keyword",actor+" unrelated"),true));
    assertEquals(0,unrelated.path("total").asInt());
    var edit=resource.deepCopy().put("title",actor+" first");
    var saved=service.save("resources",resource.path("id").asText(),edit,actor);
    var conflict=assertThrows(ResponseStatusException.class,()->service.save("resources",resource.path("id").asText(),edit.put("title",actor+" stale"),actor));
    assertEquals(409,conflict.getStatusCode().value());
    assertEquals(saved.path("title"),service.detail("resources",resource.path("id").asText(),null,true).path("title"));
  }

  @Test void everyMigratedRecordRemainsValidWithoutChangingTheSeed() {
    var validation=app.getBean(com.tencent.wxcloudrun.content.ContentValidation.class);
    var checks=new ArrayList<org.junit.jupiter.api.function.Executable>();
    var counts=new LinkedHashMap<String,Integer>();
    for(String collection:List.of("resources","featured","promos","policies","institutions")) {
      String table=Map.of("resources","content_resource","featured","home_featured","promos","home_promo","policies","policy_article","institutions","service_institution").get(collection);
      var ids=jdbc.queryForList("SELECT id FROM "+table+" WHERE import_batch_id IS NOT NULL ORDER BY id",String.class);
      assertFalse(ids.isEmpty(),"load the migrated seed before running this check: "+collection);
      counts.put(collection,ids.size());
      for(String id:ids)checks.add(()->assertDoesNotThrow(()->validation.validate(collection,service.detail(collection,id,null,true)),collection+"/"+id));
    }
    assertAll("Migrated records must pass the current editing/publication validation",checks);
    System.out.println("Validated migrated content: "+counts);
  }

  @Test void oversizedJsonReturns413BeforePrivateIdentityResolution() throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/session"))
        .header("Content-Type","application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{\"padding\":\""+"x".repeat(256*1024)+"\"}"));
    var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    assertEquals(413,response.statusCode(),response.body());
    assertEquals(413,json.readTree(response.body()).path("code").asInt());
  }

  @Test void multipartWithoutFileUsesTheJson400Handler() throws Exception {
    // The shared server is mini mode; exercise the real admin MVC binding/advice without opening a second server.
    var media=new com.tencent.wxcloudrun.content.MediaService(app.getBean(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class),"","","","","","");
    var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.tencent.wxcloudrun.content.MediaController(media))
        .setControllerAdvice(app.getBean(com.tencent.wxcloudrun.config.ApiExceptionHandler.class)).build();
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/admin/api/media").param("note","missing file"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(400));
  }

  private ObjectNode create(String collection,ObjectNode input){return assertDoesNotThrow(()->service.save(collection,null,input,actor));}
  private ObjectNode publish(String collection,ObjectNode item,String status){return assertDoesNotThrow(()->service.publication(collection,item.path("id").asText(),json.createObjectNode().put("status",status).put("version",item.path("version").asLong()),actor));}
  private ObjectNode node(String value)throws Exception{return (ObjectNode)json.readTree(value);}
  private ObjectNode policy()throws Exception{return node("{\"title\":\""+actor+"\",\"category\":\"知识产权\",\"date\":\"2026-09-09\",\"sourceName\":\"test source\"}");}
  private ObjectNode resource()throws Exception{return node("{\"title\":\""+actor+"\",\"resourceType\":\"project\",\"kind\":\"supply\",\"industries\":[\"生物医药\"],\"cooperationModes\":[\"投资合作\"],\"views\":[{\"audience\":\"pool\",\"category\":\"project\",\"sortOrder\":3}]}");}
  private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
  private JsonNode get(String path)throws Exception{
    var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    assertEquals(200,response.statusCode(),response.body());return json.readTree(response.body()).path("data");
  }
}