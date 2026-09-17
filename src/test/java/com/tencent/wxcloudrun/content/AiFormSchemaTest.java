package com.tencent.wxcloudrun.content;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AiFormSchemaTest {
  private final ObjectMapper json=new ObjectMapper();
  private final AiFormSchema form=new AiFormSchema(new CatalogSchema(json),json);
  AiFormSchemaTest() throws Exception {}
  private JsonNode node(String value) throws Exception { return json.readTree(value); }
  private ObjectNode clean(String collection,String input,String context,List<String> warnings) throws Exception {
    return form.normalize(collection,node(input),node(context),warnings);
  }

  @Test void emptySuggestionsNeverInventFacts() throws Exception {
    for(String collection:List.of("resources","policies","promos","institutions")) {
      var warnings=new ArrayList<String>();
      assertTrue(clean(collection,"{}","{}",warnings).isEmpty());
      assertTrue(warnings.isEmpty());
    }
    var warnings=new ArrayList<String>();
    assertTrue(clean("resources","[]","{}",warnings).isEmpty());
    assertFalse(warnings.isEmpty());
  }

  @Test void retainsValidSuggestionsButDropsCommandsLinksAndUnknownFields() throws Exception {
    var warnings=new ArrayList<String>();
    var out=clean("resources","""
        {"title":"  药物技术  ","summary":"已完成临床前研究","resourceType":"technology",
         "kind":"supply","publisherRole":"enterprise","status":"open","amountWan":12.34,
         "views":[{"audience":"enterprise","category":"technology","sortOrder":99,"resourceId":"injected"}],
         "attributes":{"enterpriseResearchStage":"临床前研究","scientistResearchStage":"临床研究","fake":"bad"},
         "id":"x","version":2,"publicationStatus":"published","publishedAt":"2026-01-01",
         "updatedAt":"2026-01-01","resourceId":"x","homeRecommended":true,"sortOrder":999,
         "imageUrl":"https://example.com/fake.png","sourceUrl":"https://example.com/fake","metadata":{"publish":true}}
        ""","{}",warnings);
    assertEquals("药物技术",out.path("title").asText());
    assertEquals("临床前研究",out.at("/attributes/enterpriseResearchStage").asText());
    assertFalse(out.path("attributes").has("scientistResearchStage"));
    assertEquals(12.34,out.path("amountWan").asDouble());
    assertEquals(node("[{\"audience\":\"enterprise\",\"category\":\"technology\",\"sortOrder\":0}]"),out.path("views"));
    for(String key:List.of("id","version","publicationStatus","publishedAt","updatedAt","resourceId","homeRecommended","sortOrder","imageUrl","sourceUrl","metadata"))assertFalse(out.has(key),key);
    assertFalse(out.path("attributes").has("fake"));
    assertFalse(warnings.isEmpty());
  }

  @Test void contextFixesRoleAndTypeWithoutGuessingAFirstRole() throws Exception {
    var warnings=new ArrayList<String>();
    var out=clean("resources","""
        {"resourceType":"technology","views":[{"audience":"scientist","category":"technology"}],
         "attributes":{"enterpriseMahType":"化学药制剂 MAH 批件","scientistTechnologyField":"小分子"}}
        ""","{\"audience\":\"enterprise\",\"resourceType\":\"mah\"}",warnings);
    assertEquals("mah",out.path("resourceType").asText());
    assertEquals(node("[{\"audience\":\"enterprise\",\"category\":\"mah\",\"sortOrder\":0}]"),out.path("views"));
    assertEquals(1,out.path("attributes").size());
    assertEquals("化学药制剂 MAH 批件",out.at("/attributes/enterpriseMahType").asText());
    var roleOnly=clean("resources","{\"resourceType\":\"mah\"}","{\"audience\":\"investor\"}",new ArrayList<>());
    assertEquals("investor",roleOnly.at("/views/0/audience").asText());
    var typeOnly=clean("resources","{\"resourceType\":\"mah\"}","{}",new ArrayList<>());
    assertFalse(typeOnly.has("views"));
  }

  @Test void invalidModelEnumsAndRolePairsAreRemovedIndividually() throws Exception {
    var warnings=new ArrayList<String>();
    var out=clean("resources","""
        {"resourceType":"project","kind":"all","publisherRole":"admin","tone":"bad","status":"published",
         "views":[{"audience":"pool","category":"project"},{"audience":"enterprise","category":"project"},
                  {"audience":"investor","category":"project"},{"audience":"investor","category":"project"}],
         "attributes":{"projectType":"早期创新药项目","investorProjectType":"早期创新药项目",
                       "investorIndications":["肿瘤疾病","invalid","肿瘤疾病"]},"title":"保留标题"}
        ""","{}",warnings);
    assertEquals("保留标题",out.path("title").asText());
    assertEquals(1,out.path("views").size());
    assertEquals(node("{\"investorProjectType\":\"早期创新药项目\",\"investorIndications\":[\"肿瘤疾病\"]}"),out.path("attributes"));
    for(String key:List.of("kind","publisherRole","tone","status"))assertFalse(out.has(key),key);
    for(String type:List.of("all","finance","achievement"))assertFalse(clean("resources","{\"resourceType\":\""+type+"\"}","{}",new ArrayList<>()).has("resourceType"));
    assertFalse(warnings.isEmpty());
  }

  @Test void invalidContextIsClientError() throws Exception {
    for(String context:List.of("[]","{\"audience\":\"pool\"}","{\"audience\":\"manager\",\"resourceType\":\"project\"}","{\"resourceType\":\"finance\"}","{\"audience\":12}","{\"publish\":true}")) {
      var ex=assertThrows(ResponseStatusException.class,()->form.describe("resources",node(context)));
      assertEquals(400,ex.getStatusCode().value());
    }
  }

  @Test void unknownPolicyFactsStayEmptyAndInvalidDatesAreDropped() throws Exception {
    var out=clean("policies","{\"title\":\"政策摘要\",\"date\":\"\",\"sourceName\":\"\"}","{}",new ArrayList<>());
    assertTrue(out.has("date"));assertTrue(out.has("sourceName"));
    assertEquals("",out.path("date").asText());assertEquals("",out.path("sourceName").asText());
    assertFalse(out.has("category"));assertFalse(out.has("sourceKind"));
    assertTrue(form.missingFields("policies",out).containsAll(List.of("date","sourceName")));
    assertFalse(clean("policies","{\"date\":\"2026-02-30\"}","{}",new ArrayList<>()).has("date"));
  }

  @Test void invalidDateCannotBecomeValidByTruncation() throws Exception {
    assertFalse(clean("policies","{\"date\":\"2026-01-01后续文字\"}","{}",new ArrayList<>()).has("date"));
    assertEquals("2026-01-01",clean("policies","{\"date\":\"2026-01-01\"}","{}",new ArrayList<>()).path("date").asText());
  }

  @Test void moneyAndActionsDoNotCoerceUnknownFactsOrLinkTargets() throws Exception {
    for(String amount:List.of("\"unknown\"","-1","1.001","1000000000000","1e9999"))assertFalse(clean("resources","{\"amountWan\":"+amount+"}","{}",new ArrayList<>()).has("amountWan"));
    assertTrue(clean("resources","{\"amountWan\":null}","{}",new ArrayList<>()).path("amountWan").isNull());
    var action=clean("promos","{\"action\":{\"type\":\"article\",\"targetId\":\"malicious\"}}","{}",new ArrayList<>()).path("action");
    assertEquals(node("{\"type\":\"article\",\"targetId\":\"\"}"),action);
    assertFalse(clean("promos","{\"action\":{\"type\":\"resource\",\"targetId\":\"x\"}}","{}",new ArrayList<>()).has("action"));
  }

  @Test void missingFieldsIncludesExplicitSelectionsNeededByTheReviewForm() throws Exception {
    for(String collection:List.of("resources","policies","promos","institutions"))assertTrue(form.missingFields(collection,node("{}")).contains("sourceKind"));
    assertTrue(form.missingFields("resources",node("{}")).containsAll(List.of("tone","views")));
    assertTrue(form.missingFields("policies",node("{\"sourceKind\":\"official\"}")).containsAll(List.of("category","sourceUrl")));
    assertTrue(form.missingFields("promos",node("{}")).containsAll(List.of("tone","buttonText","action.type")));
    assertTrue(form.missingFields("promos",node("{\"action\":{\"type\":\"resource\"}}")).contains("action.targetId"));
    assertFalse(form.missingFields("promos",node("{\"action\":{\"type\":\"none\"}}")).contains("action.type"));
  }

  @Test void oversizedAndPartlyInvalidSectionsKeepValidTextWithinLimits() throws Exception {
    var input=json.createObjectNode();var sections=input.putArray("sections");
    sections.addObject().put("heading","有效").putArray("paragraphs").add("保留").add(12).add("甲".repeat(2200));
    for(int i=0;i<22;i++){var s=sections.addObject().put("heading","乙".repeat(110));var p=s.putArray("paragraphs");for(int j=0;j<12;j++)p.add("丙".repeat(2100));}
    var warnings=new ArrayList<String>();
    var out=form.normalize("institutions",input,json.createObjectNode(),warnings);
    assertEquals("保留",out.at("/sections/0/paragraphs/0").asText());
    assertTrue(out.path("sections").size()<=20);
    int length=0;
    for(var s:out.path("sections")){assertTrue(s.path("heading").asText().length()<=100);length+=s.path("heading").asText().length();assertTrue(s.path("paragraphs").size()<=10);for(var p:s.path("paragraphs")){assertTrue(p.asText().length()<=2000);length+=p.asText().length();}}
    assertTrue(length<=30000);assertFalse(warnings.isEmpty());
  }

  @Test void schemaDescribesOnlyCurrentAllowedFieldsAndTargetDictionary() throws Exception {
    var description=form.describe("resources",node("{\"audience\":\"investor\",\"resourceType\":\"project\"}"));
    assertEquals(120,description.at("/fields/title/maxLength").asInt());
    assertEquals(node("[\"project\"]"),description.at("/fields/resourceType/enum"));
    assertTrue(description.toString().contains("investorIndications"));
    assertFalse(description.path("fields").has("imageUrl"));
    assertFalse(description.toString().contains("preservedFiltersByCategory"));
    assertFalse(description.toString().contains("achievement"));
  }
}
