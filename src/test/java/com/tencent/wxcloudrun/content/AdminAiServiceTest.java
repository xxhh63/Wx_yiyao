package com.tencent.wxcloudrun.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminAiServiceTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test void sparseDraftUsesFixedProtocolAndCannotPublishOrInventLinks() throws Exception {
    AtomicReference<ObjectNode> sent = new AtomicReference<>();
    try (Upstream upstream = new Upstream(sent, "{\"title\":\"实验室合作\",\"publicationStatus\":\"PUBLISHED\",\"id\":\"victim\",\"imageUrl\":\"https://evil.test/a.png\"}", "stop")) {
      var service = service("test-key", upstream.uri());
      var result = service.draft(request("promos", "标题：实验室合作"), null);
      assertEquals("实验室合作", result.path("draft").path("title").asText());
      assertFalse(result.path("draft").has("id"));
      assertFalse(result.path("draft").has("publicationStatus"));
      assertFalse(result.path("draft").has("imageUrl"));
      assertFalse(result.path("draft").has("tone"));
      assertEquals("deepseek-flash", sent.get().path("model").asText());
      assertEquals("disabled", sent.get().path("thinking").path("type").asText());
      assertEquals("json_object", sent.get().path("response_format").path("type").asText());
      assertFalse(sent.get().has("tools"));
      assertTrue(sent.get().path("messages").get(0).path("content").asText().contains("不可信"));
    }
  }

  @Test void manualImageAndSelectionSurviveRefinementAndMissingStaysMissing() throws Exception {
    try (Upstream upstream = new Upstream(new AtomicReference<>(), "{\"title\":\"新标题\"}", "stop")) {
      var input = request("promos", "修改标题为新标题");
      input.putObject("previousDraft").put("title","旧标题").put("description","人工摘要")
          .put("imageUrl","https://example.com/a.jpg").putObject("action").put("type","resource").put("targetId","resource-1");
      var draft = service("test", upstream.uri()).draft(input,null).path("draft");
      assertEquals("新标题",draft.path("title").asText());
      assertEquals("人工摘要",draft.path("description").asText());
      assertEquals("https://example.com/a.jpg",draft.path("imageUrl").asText());
      assertEquals("resource-1",draft.path("action").path("targetId").asText());
      assertFalse(draft.has("tone"));
    }
  }

  @Test void refinementsKeepHumanViewsAndAttributesOutsideTheTargetAudience() throws Exception {
    try (Upstream upstream=new Upstream(new AtomicReference<>(),"""
        {"title":"新标题","attributes":{"enterpriseResearchStage":"临床研究","scientistResearchStage":"临床前研究"}}
        ""","stop")) {
      var input=request("resources","修改标题并补充企业研发阶段");
      input.putObject("context").put("audience","enterprise").put("resourceType","technology");
      input.set("previousDraft",json.readTree("""
          {"title":"旧标题","resourceType":"technology",
           "views":[{"audience":"enterprise","category":"technology"},{"audience":"scientist","category":"technology"}],
           "attributes":{"enterpriseResearchStage":"临床前研究","scientistResearchStage":"临床研究"}}
          """));
      var draft=service("test",upstream.uri()).draft(input,null).path("draft");
      assertEquals("新标题",draft.path("title").asText());
      assertEquals(2,draft.path("views").size());
      assertEquals("scientist",draft.at("/views/1/audience").asText());
      assertEquals("临床研究",draft.at("/attributes/enterpriseResearchStage").asText());
      assertEquals("临床研究",draft.at("/attributes/scientistResearchStage").asText(),"The model cannot overwrite a different audience's human field");
    }
  }

  @Test void sparseAttributeRefinementUsesTheExistingResourceType() throws Exception {
    try (Upstream upstream=new Upstream(new AtomicReference<>(),"{\"attributes\":{\"enterpriseResearchStage\":\"临床研究\"}}","stop")) {
      var input=request("resources","补充：已进入临床研究");
      input.putObject("context").put("audience","enterprise");
      var previous=input.putObject("previousDraft").put("resourceType","technology");
      previous.putArray("views").addObject().put("audience","enterprise").put("category","technology");
      previous.putObject("attributes").put("enterpriseResearchStage","临床前研究");
      var draft=service("test",upstream.uri()).draft(input,null).path("draft");
      assertEquals("technology",draft.path("resourceType").asText());
      assertEquals("临床研究",draft.at("/attributes/enterpriseResearchStage").asText());
    }
  }
  @Test void changingAudienceDropsAnUnsupportedPreviousResourceType() throws Exception {
    try (Upstream upstream=new Upstream(new AtomicReference<>(),"{\"title\":\"新标题\"}","stop")) {
      var input=request("resources","改为企业专区，类型由材料确认");
      input.putObject("context").put("audience","enterprise").put("resourceType","");
      var previous=input.putObject("previousDraft").put("resourceType","project");
      previous.putArray("views").addObject().put("audience","investor").put("category","project");
      previous.putObject("attributes").put("investorProjectType","早期创新药项目");
      var draft=service("test",upstream.uri()).draft(input,null).path("draft");
      assertFalse(draft.has("resourceType"));
      assertFalse(draft.has("views"));
      assertFalse(draft.path("attributes").has("investorProjectType"));
    }
  }
  @Test void incompleteModelResponseIsNeverPresentedAsSuccess() throws Exception {
    try (Upstream upstream = new Upstream(new AtomicReference<>(), "{\"title\":\"partial\"}", "length")) {
      var failure = assertThrows(ResponseStatusException.class,
          () -> service("test",upstream.uri()).draft(request("policies","政策正文"),null));
      assertEquals(502,failure.getStatusCode().value());
    }
  }

  @Test void unconfiguredAndInvalidRequestsNeverParseFilesOrCallProvider() throws Exception {
    var reader = mock(ImportDocumentReader.class);
    var service = new AdminAiService(json,new AiFormSchema(new CatalogSchema(json),json),reader,"");
    assertFalse(service.status().path("configured").asBoolean());
    assertEquals(503, assertThrows(ResponseStatusException.class,
        () -> service.draft(request("policies","正文"),null)).getStatusCode().value());
    verifyNoInteractions(reader);
    var configured = service("test",URI.create("http://127.0.0.1:1"));
    assertEquals(400, assertThrows(ResponseStatusException.class,
        () -> configured.draft(request("featured","正文"),null)).getStatusCode().value());
    assertEquals(400, assertThrows(ResponseStatusException.class,
        () -> configured.draft(request("promos","x".repeat(60001)),null)).getStatusCode().value());
  }

  @Test void requestsAreBoundedAndBlankFilesOpenEmptyReview() throws Exception {
    var reader=mock(ImportDocumentReader.class);
    var file=new org.springframework.mock.web.MockMultipartFile("file","scan.pdf","application/pdf",new byte[]{1});
    when(reader.read(file)).thenReturn(new ImportDocumentReader.Document("",java.util.List.of(),java.util.List.of("未识别到文本")));
    var service=new AdminAiService(json,new AiFormSchema(new CatalogSchema(json),json),reader,"test");
    var result=service.draft(request("policies",""),file);
    assertTrue(result.path("draft").isEmpty());
    assertTrue(result.path("missingFields").toString().contains("title"));
    assertTrue(result.path("warnings").toString().contains("未识别"));
    try (Upstream upstream=new Upstream(new AtomicReference<>(),"{\"title\":\"标题\"}","stop")) {
      var limited=service("test",upstream.uri());
      for(int i=0;i<6;i++)limited.draft(request("promos","标题"),null);
      assertEquals(429,assertThrows(ResponseStatusException.class,
          ()->limited.draft(request("promos","标题"),null)).getStatusCode().value());
    }
  }


  @Test void emptyRefinementCannotEraseHumanFactsAndTypeChangeDropsOldClassification() throws Exception {
    try (Upstream upstream=new Upstream(new AtomicReference<>(),"{\"sourceName\":\"\",\"summary\":\"\"}","stop")) {
      var input=request("policies","补充：暂无其他信息");
      input.putObject("previousDraft").put("sourceName","人工核对的发布部门").put("summary","人工摘要");
      var draft=service("test",upstream.uri()).draft(input,null).path("draft");
      assertEquals("人工核对的发布部门",draft.path("sourceName").asText());
      assertEquals("人工摘要",draft.path("summary").asText());
    }
    try (Upstream upstream=new Upstream(new AtomicReference<>(),"{\"resourceType\":\"mah\"}","stop")) {
      var input=request("resources","改为MAH类");
      var previous=input.putObject("previousDraft").put("title","资源").put("resourceType","project");
      previous.putArray("views").addObject().put("audience","investor").put("category","project");
      previous.putObject("attributes").put("investorProjectType","早期创新药项目");
      var draft=service("test",upstream.uri()).draft(input,null).path("draft");
      assertEquals("mah",draft.path("resourceType").asText());
      assertFalse(draft.path("attributes").has("investorProjectType"));
      for(var view:draft.path("views"))assertEquals("mah",view.path("category").asText());
    }
  }

  @Test void multipartImportUsesTheSameDraftContractAndOversizedModelOutputFails() throws Exception {
    try (Upstream upstream=new Upstream(new AtomicReference<>(),"{\"name\":\"测试研究院\"}","stop")) {
      var reader=mock(ImportDocumentReader.class);
      var file=new org.springframework.mock.web.MockMultipartFile("file","机构.txt","text/plain","机构材料".getBytes(StandardCharsets.UTF_8));
      when(reader.read(any())).thenReturn(new ImportDocumentReader.Document("测试研究院",java.util.List.of(),java.util.List.of()));
      var service=new AdminAiService(json,new AiFormSchema(new CatalogSchema(json),json),reader,"test",upstream.uri());
      var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new AdminAiController(service,json))
          .setControllerAdvice(new com.tencent.wxcloudrun.config.ApiExceptionHandler()).build();
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/admin/api/ai/draft")
          .file(file).param("request",request("institutions","提取资料").toString()))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.draft.name").value("测试研究院"))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"));
    }
    try (Upstream upstream=new Upstream(new AtomicReference<>(),"{\"title\":\""+ "x".repeat(150000)+"\"}","stop")) {
      assertEquals(502,assertThrows(ResponseStatusException.class,
          ()->service("test",upstream.uri()).draft(request("promos","标题"),null)).getStatusCode().value());
    }
  }

  @Test void emptyUpstreamResponseIsAnActionableGatewayError() throws Exception {
    try (Upstream upstream=new Upstream(new AtomicReference<>(),null,"stop")) {
      assertEquals(502,assertThrows(ResponseStatusException.class,
          ()->service("test",upstream.uri()).draft(request("promos","标题"),null)).getStatusCode().value());
    }
  }
  private ObjectNode request(String collection,String text) {
    return json.createObjectNode().put("collection",collection).put("text",text);
  }
  private AdminAiService service(String key,URI endpoint) throws Exception {
    return new AdminAiService(json,new AiFormSchema(new CatalogSchema(json),json),mock(ImportDocumentReader.class),key,endpoint);
  }
  private class Upstream implements AutoCloseable {
    final HttpServer server;
    Upstream(AtomicReference<ObjectNode> sent,String draft,String finish) throws Exception {
      server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
      server.createContext("/",exchange->{
        sent.set((ObjectNode)json.readTree(exchange.getRequestBody()));
        var answer=json.createObjectNode(); var choice=answer.putArray("choices").addObject();
        choice.put("finish_reason",finish).putObject("message").put("content","{\"draft\":"+draft+"}");
        byte[] body=draft==null?"null".getBytes(StandardCharsets.UTF_8):json.writeValueAsBytes(answer);
        exchange.sendResponseHeaders(200,body.length);
        try(var output=exchange.getResponseBody()){output.write(body);}
      });
      server.start();
    }
    URI uri(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");}
    public void close(){server.stop(0);}
  }
}
