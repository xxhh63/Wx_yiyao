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


  @Test void adminResourceListsFollowRoleAndCategoryWithoutLosingDrafts() throws Exception {
    var input=resource(); input.putArray("views").addObject().put("audience","investor").put("category","project").put("sortOrder",17);
    var created=create("resources",input);
    var query=Map.of("audience","investor","category","project","keyword",actor,"pageSize","1");
    var result=json.valueToTree(service.list("resources",query,true));
    assertEquals(1,result.path("total").asInt());
    assertEquals(created.path("id"),result.path("items").get(0).path("id"));
    assertEquals("DRAFT",result.path("items").get(0).path("publicationStatus").asText());
    assertEquals(17,result.path("items").get(0).path("viewSortOrder").asInt());
    assertEquals(0,json.valueToTree(service.list("resources",query,false)).path("total").asInt());
    assertEquals(0,json.valueToTree(service.list("resources",Map.of("audience","investor","category","mah","keyword",actor),true)).path("total").asInt());
    assertEquals(0,json.valueToTree(service.list("resources",Map.of("audience","enterprise","category","all","keyword",actor),true)).path("total").asInt());
    assertEquals(400,assertThrows(ResponseStatusException.class,()->service.list("resources",Map.of("audience","investor","category","patent"),true)).getStatusCode().value());
  }

  @Test void optionalFiltersAcrossEveryRoleCanBeBlankAndMatchOnlyWhenFilled() throws Exception {
    for(String audience:List.of("investor","enterprise","scientist","manager")) {
      var catalog=service.catalog(audience);
      for(var category:catalog.path("categories")) {
        String type=category.path("value").asText();
        if(type.equals("all")||category.path("pending").asBoolean())continue;
        var input=resource().put("title",actor+" "+audience+" "+type).put("resourceType",type);
        input.putArray("industries");input.putArray("cooperationModes");input.putObject("attributes");
        input.putArray("views").addObject().put("audience",audience).put("category",type);
        var saved=publish("resources",create("resources",input),"PUBLISHED");
        var query=new HashMap<>(Map.of("audience",audience,"category",type,"keyword",input.path("title").asText()));
        assertEquals(1,json.valueToTree(service.list("resources",query,false)).path("total").asInt(),audience+"/"+type+" must show without optional filters");
        String firstKey=null, firstValue=null;
        for(var filter:catalog.path("filtersByCategory").path(type)) {
          String key=filter.path("key").asText();if(key.equals("sort"))continue;
          String option=filter.path("options").get(1).path("value").asText();
          // A resource always has a supply/demand kind; the new enterprise filter reuses it.
          if(key.equals("kind")) {
            query.put(key,"supply");
            assertEquals(1,json.valueToTree(service.list("resources",query,false)).path("total").asInt());
            query.put(key,"demand");
            assertEquals(0,json.valueToTree(service.list("resources",query,false)).path("total").asInt());
            var changed=service.save("resources",saved.path("id").asText(),saved.deepCopy().put("kind","demand"),actor);
            assertEquals(1,json.valueToTree(service.list("resources",query,false)).path("total").asInt());
            saved=service.save("resources",saved.path("id").asText(),saved.deepCopy().put("version",changed.path("version").asLong()),actor);
            query.remove(key);continue;
          }
          query.put(key,option);
          assertEquals(0,json.valueToTree(service.list("resources",query,false)).path("total").asInt(),audience+"/"+type+" blank "+key);
          if(firstKey==null){firstKey=key;firstValue=option;}
          var edit=saved.deepCopy();String field=filter.path("field").asText(key);
          if(field.equals("industries")||field.equals("cooperationModes"))edit.putArray(field).add(option);
          else if(filter.path("multiple").asBoolean()||field.equals("indications"))((ObjectNode)edit.path("attributes")).putArray(field).add(option);
          else ((ObjectNode)edit.path("attributes")).put(field,option);
          edit=service.save("resources",saved.path("id").asText(),edit,actor);
          assertEquals(1,json.valueToTree(service.list("resources",query,false)).path("total").asInt(),audience+"/"+type+" filled "+key);
          if(!key.equals(firstKey)) {
            query.put(firstKey,firstValue);
            assertEquals(0,json.valueToTree(service.list("resources",query,false)).path("total").asInt(),"filters must remain AND combined");
            query.remove(firstKey);
          }
          var reset=saved.deepCopy().put("version",edit.path("version").asLong());
          saved=service.save("resources",saved.path("id").asText(),reset,actor);
          query.remove(key);
        }
      }
    }
  }


  @Test void enterpriseMigrationPreservesSharedRolesAndIsIdempotent() throws Exception {
    var input=resource().put("resourceType","patent");
    input.putArray("views").addObject().put("audience","enterprise").put("category","patent");
    ((com.fasterxml.jackson.databind.node.ArrayNode)input.path("views")).addObject().put("audience","scientist").put("category","patent");
    input.putObject("attributes").put("patentType","发明专利").put("patentField","药物化学")
        .put("patentStatus","有效授权").put("cooperationMode","独占许可");
    var saved=publish("resources",create("resources",input),"PUBLISHED");
    String id=saved.path("id").asText();
    // Emulate an old stored payload that predates the enterprise-specific fields.
    var legacy=(ObjectNode)json.readTree(jdbc.queryForObject("SELECT payload FROM content_resource WHERE id=?",String.class,id));
    var attrs=(ObjectNode)legacy.path("attributes");
    var retired=new ArrayList<String>();attrs.fieldNames().forEachRemaining(key->{if(key.startsWith("enterprise"))retired.add(key);});attrs.remove(retired);
    jdbc.update("UPDATE content_resource SET payload=CAST(? AS JSON) WHERE id=?",legacy.toString(),id);
    String migration=java.nio.file.Files.readString(java.nio.file.Path.of("migration/004_enterprise_categories.sql"));
    Runnable migrate=()->{
      try(var connection=jdbc.getDataSource().getConnection()) {
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
            new org.springframework.core.io.ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));
      } catch(java.sql.SQLException e){throw new RuntimeException(e);}
    };
    migrate.run();
    var migrated=service.detail("resources",id,null,true);
    assertEquals("药物化学",migrated.at("/attributes/patentField").asText());
    assertEquals("独占许可",migrated.at("/attributes/cooperationMode").asText());
    assertEquals("发明专利",migrated.at("/attributes/patentType").asText());
    assertEquals("",migrated.at("/attributes/enterprisePatentField").asText(),"do not guess the modality from an old patent field");
    assertEquals("专利授权",migrated.at("/attributes/enterpriseCooperationMode").asText());
    assertEquals(2,migrated.path("views").size());
    long version=migrated.path("version").asLong();migrate.run();
    assertEquals(version,service.detail("resources",id,null,true).path("version").asLong(),"re-running migration is a no-op");
    var q=new HashMap<>(Map.of("audience","enterprise","category","patent","keyword",actor,"patentType","发明专利"));
    assertEquals(1,json.valueToTree(service.list("resources",q,false)).path("total").asInt());
    q.put("patentField","抗体");
    assertEquals(0,json.valueToTree(service.list("resources",q,false)).path("total").asInt(),"blank enterprise field must not match");
    ((ObjectNode)migrated.path("attributes")).put("enterprisePatentField","抗体");
    var edited=service.save("resources",id,migrated,actor);
    assertEquals(1,json.valueToTree(service.list("resources",q,false)).path("total").asInt());
    assertEquals("药物化学",edited.at("/attributes/patentField").asText());
    assertEquals(1,json.valueToTree(service.list("resources",Map.of("audience","scientist","category","patent","keyword",actor),false)).path("total").asInt());
    assertEquals("药物化学",edited.at("/attributes/patentField").asText(),"old scientific attribute is retained even after its filter is retired");
    q.put("patentField","药物化学");
    assertEquals(400,assertThrows(ResponseStatusException.class,()->service.list("resources",q,false)).getStatusCode().value());
    ((ObjectNode)edited.path("attributes")).put("enterpriseCooperationMode","");
    edited=service.save("resources",id,edited,actor);migrate.run();
    assertEquals("",service.detail("resources",id,null,true).at("/attributes/enterpriseCooperationMode").asText(),"explicit clearing must survive migration");
  }


  @Test void enterprisePublishedClassificationsUpdateStatistics() throws Exception {
    var before=service.stats();
    var expert=resource().put("resourceType","talent");
    expert.putArray("views").addObject().put("audience","enterprise").put("category","talent");
    String maturityField="";
    for(var filter:service.catalog("enterprise").at("/filtersByCategory/talent"))if(filter.path("key").asText().equals("talentMaturity"))maturityField=filter.path("field").asText("talentMaturity");
    expert.putObject("attributes").put(maturityField,"资深专家");
    publish("resources",create("resources",expert),"PUBLISHED");
    assertEquals(((Number)before.get("experts")).longValue()+1,((Number)service.stats().get("experts")).longValue(),"published enterprise expert contributes to the data center");
    var copyright=resource().put("resourceType","patent");
    copyright.putArray("views").addObject().put("audience","enterprise").put("category","patent");
    String patentTypeField="";
    for(var filter:service.catalog("enterprise").at("/filtersByCategory/patent"))if(filter.path("key").asText().equals("patentType"))patentTypeField=filter.path("field").asText("patentType");
    copyright.putObject("attributes").put(patentTypeField,"软著");
    publish("resources",create("resources",copyright),"PUBLISHED");
    assertEquals(((Number)before.get("patentResources")).longValue(),((Number)service.stats().get("patentResources")).longValue(),"software copyrights are not patent resources");
  }


  @Test void enterpriseMigrationPreservesPartiallyFilledAttributes() throws Exception {
    var input=resource().put("resourceType","service");
    input.putArray("views").addObject().put("audience","enterprise").put("category","service");
    input.putObject("attributes").put("serviceType","CRO服务").put("qualification","丰富落地案例")
        .put("cooperationMode","单项项目外包").put("enterpriseServiceType","增值服务").put("enterpriseCooperationMode","");
    var saved=create("resources",input);String id=saved.path("id").asText();
    jdbc.update("UPDATE content_resource SET payload=JSON_REMOVE(payload,'$.attributes.enterpriseQualification') WHERE id=?",id);
    String migration=java.nio.file.Files.readString(java.nio.file.Path.of("migration/004_enterprise_categories.sql"));
    try(var connection=jdbc.getDataSource().getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));
    }
    var result=service.detail("resources",id,null,true);
    assertEquals("增值服务",result.at("/attributes/enterpriseServiceType").asText(),"existing JSON string must not acquire extra quotes");
    assertEquals("",result.at("/attributes/enterpriseCooperationMode").asText(),"intentional blank is preserved when another field is missing");
    assertEquals("丰富案例",result.at("/attributes/enterpriseQualification").asText());
    assertEquals("CRO服务",result.at("/attributes/serviceType").asText(),"legacy classification stays intact");
    assertDoesNotThrow(()->service.save("resources",id,result,actor));
  }

  private ObjectNode create(String collection,ObjectNode input){return assertDoesNotThrow(()->service.save(collection,null,input,actor));}
  private ObjectNode publish(String collection,ObjectNode item,String status){return assertDoesNotThrow(()->service.publication(collection,item.path("id").asText(),json.createObjectNode().put("status",status).put("version",item.path("version").asLong()),actor));}
  private ObjectNode node(String value)throws Exception{return (ObjectNode)json.readTree(value);}

  @Test void serviceProviderCatalogExcludesHistoricalAchievementsButAdminPreservesThem() throws Exception {
    var old=resource().put("resourceType","achievement");
    old.putObject("attributes").put("achievementType","技术成果").put("technicalTrack","小分子药物");
    old.putArray("views").addObject().put("audience","manager").put("category","achievement").put("sortOrder",23);
    old=publish("resources",create("resources",old),"PUBLISHED");
    var beforeStats=service.stats();
    var saved=service.save("resources",old.path("id").asText(),old,actor);
    assertEquals("achievement",saved.path("resourceType").asText());
    assertEquals("技术成果",saved.at("/attributes/achievementType").asText());
    assertEquals(23,saved.at("/views/0/sortOrder").asInt());
    assertEquals(beforeStats,service.stats());
    assertEquals(1,json.valueToTree(service.list("resources",Map.of("keyword",actor),true)).path("total").asInt());
    for(boolean admin:List.of(false,true))assertEquals(0,json.valueToTree(service.list("resources",Map.of("audience","manager","category","all","keyword",actor),admin)).path("total").asInt());
    assertEquals(400,assertThrows(ResponseStatusException.class,()->service.list("resources",Map.of("audience","manager","category","achievement"),false)).getStatusCode().value());
    assertEquals(old.path("id"),service.detail("resources",old.path("id").asText(),"manager",false).path("id"));
    assertEquals(400,assertThrows(ResponseStatusException.class,()->service.list("resources",Map.of("audience","scientist","category","finance"),false)).getStatusCode().value());
  }

  @Test void rolesMigrationPreservesSharedDataAndFiltersArrays() throws Exception {
    var input=resource();
    input.putObject("attributes").put("projectType","早期创新药项目").put("researchStage","2期临床").putArray("indications").add("眼科").add("肿瘤疾病");
    input.putArray("views").addObject().put("audience","investor").put("category","project");
    var saved=publish("resources",create("resources",input),"PUBLISHED");String id=saved.path("id").asText();
    jdbc.update("UPDATE content_resource SET payload=JSON_REMOVE(payload,'$.attributes.investorProjectType','$.attributes.investorResearchStage','$.attributes.investorIndications') WHERE id=?",id);
    runRolesMigration();saved=service.detail("resources",id,null,true);
    assertEquals("临床研究",saved.at("/attributes/investorResearchStage").asText());
    assertEquals("2期临床",saved.at("/attributes/researchStage").asText());
    assertEquals(json.readTree("[\"肿瘤疾病\"]"),saved.at("/attributes/investorIndications"));
    assertEquals(json.readTree("[\"眼科\",\"肿瘤疾病\"]"),saved.at("/attributes/indications"));
    long version=saved.path("version").asLong();runRolesMigration();assertEquals(version,service.detail("resources",id,null,true).path("version").asLong());
    var attrs=(ObjectNode)saved.path("attributes");attrs.putArray("investorIndications").add("肿瘤疾病").add("心血管系统");attrs.put("investorResearchStage","");
    saved=service.save("resources",id,saved,actor);
    jdbc.update("UPDATE content_resource SET payload=JSON_REMOVE(payload,'$.attributes.investorProjectType') WHERE id=?",id);
    runRolesMigration();saved=service.detail("resources",id,null,true);
    assertEquals("",saved.at("/attributes/investorResearchStage").asText());assertEquals(2,saved.at("/attributes/investorIndications").size());
    for(String indication:List.of("肿瘤疾病","心血管系统"))assertEquals(1,json.valueToTree(service.list("resources",Map.of("audience","investor","category","project","keyword",actor,"indication",indication),false)).path("total").asInt());
    assertEquals(400,assertThrows(ResponseStatusException.class,()->service.list("resources",Map.of("audience","investor","category","project","indication","眼科"),false)).getStatusCode().value());
    var invalid=saved.deepCopy();((ObjectNode)invalid.path("attributes")).put("investorIndications","肿瘤疾病");
    assertEquals(400,assertThrows(ResponseStatusException.class,()->service.save("resources",id,invalid,actor)).getStatusCode().value());

    var patent=resource().put("resourceType","patent");
    patent.putArray("views").addObject().put("audience","scientist").put("category","patent");
    ((com.fasterxml.jackson.databind.node.ArrayNode)patent.path("views")).addObject().put("audience","enterprise").put("category","patent");
    patent.putObject("attributes").put("patentField","药物化学").put("patentType","发明专利").put("cooperationMode","独占许可").put("enterprisePatentField","抗体").put("enterpriseCooperationMode","专利转让");
    patent=publish("resources",create("resources",patent),"PUBLISHED");String patentId=patent.path("id").asText();
    jdbc.update("UPDATE content_resource SET payload=JSON_REMOVE(payload,'$.attributes.scientistPatentField','$.attributes.scientistCooperationMode') WHERE id=?",patentId);
    runRolesMigration();patent=service.detail("resources",patentId,null,true);
    assertEquals("",patent.at("/attributes/scientistPatentField").asText());assertEquals("专利授权",patent.at("/attributes/scientistCooperationMode").asText());
    assertEquals("抗体",patent.at("/attributes/enterprisePatentField").asText());assertEquals("独占许可",patent.at("/attributes/cooperationMode").asText());assertEquals(2,patent.path("views").size());
  }

  private void runRolesMigration() throws Exception {
    String migration=java.nio.file.Files.readString(java.nio.file.Path.of("migration/005_role_categories.sql"));
    try(var connection=jdbc.getDataSource().getConnection()) {
      try(var statement=connection.createStatement()){for(String sql:migration.replaceAll("(?m)^--.*$","").split(";"))if(!sql.isBlank())statement.execute(sql);}
      catch(Exception error){connection.rollback();throw error;}
    }
  }

  private ObjectNode policy()throws Exception{return node("{\"title\":\""+actor+"\",\"category\":\"知识产权\",\"date\":\"2026-09-09\",\"sourceName\":\"test source\"}");}
  private ObjectNode resource()throws Exception{return node("{\"title\":\""+actor+"\",\"resourceType\":\"project\",\"kind\":\"supply\",\"industries\":[\"生物医药\"],\"cooperationModes\":[\"投资合作\"],\"views\":[{\"audience\":\"pool\",\"category\":\"project\",\"sortOrder\":3}]}");}
  private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
  private JsonNode get(String path)throws Exception{
    var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    assertEquals(200,response.statusCode(),response.body());return json.readTree(response.body()).path("data");
  }
}