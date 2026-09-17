package com.tencent.wxcloudrun.content;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Service
@ConditionalOnProperty(name="app.mode",havingValue="admin")
public class AdminAiService {
  private static final String MODEL="deepseek-flash";
  private final ObjectMapper json;
  private final AiFormSchema schema;
  private final ImportDocumentReader documents;
  private final String key,prompt;
  private final URI endpoint;
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
  // ponytail: one admin service, one parser/model job and six requests/minute; use a shared limiter if deployed with multiple replicas.
  private final Semaphore running=new Semaphore(1);
  private final Deque<Long> recent=new ArrayDeque<>();

  @Autowired
  public AdminAiService(ObjectMapper json,AiFormSchema schema,ImportDocumentReader documents,
                        @Value("${app.ai.api-key:}") String key) {
    this(json,schema,documents,key,URI.create("https://api.deepseek.com/chat/completions"));
  }
  // Only package tests replace the endpoint. It is never controllable by an HTTP request or environment setting.
  AdminAiService(ObjectMapper json,AiFormSchema schema,ImportDocumentReader documents,String key,URI endpoint) {
    this.json=json;this.schema=schema;this.documents=documents;this.key=key.trim();this.endpoint=endpoint;
    try(var input=new ClassPathResource("admin-ai-system.txt").getInputStream()) {prompt=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
    catch(IOException error) {throw new IllegalStateException("AI录入规范文件缺失");}
  }

  public ObjectNode status() {
    var result=json.createObjectNode().put("configured",!key.isBlank()).put("model",MODEL)
        .put("maxFileBytes",ImportDocumentReader.MAX_FILE).put("maxTextChars",60000);
    result.set("supportedFormats",json.valueToTree(ImportDocumentReader.EXTENSIONS.stream().sorted().toList()));
    return result;
  }

  public ObjectNode draft(JsonNode request,MultipartFile file) {
    if(key.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AI助手尚未配置，请管理员在服务器填写 DEEPSEEK_API_KEY");
    ContentValidation.object(request);
    ContentValidation.keys(request,Set.of("collection","text","context","previousDraft"));
    String collection=ContentValidation.text(request,"collection",30,true);
    String text=ContentValidation.text(request,"text",60000,false);
    if(text.length()>60000)throw ContentValidation.bad("录入文字不能超过60000字符");
    JsonNode context=request.path("context");
    if(context.isMissingNode())context=json.createObjectNode();
    var definition=schema.describe(collection,context);
    JsonNode previous=request.path("previousDraft");
    if(previous.isMissingNode())previous=json.createObjectNode();
    if(!previous.isObject()||previous.toString().length()>100000)throw ContentValidation.bad("待复核草稿格式不正确或过长");
    if(text.isBlank()&&file==null)throw ContentValidation.bad("请填写材料内容或选择文件");
    if(!running.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"已有材料正在识别，请完成后再试");
    try {
      reserve();
      var warnings=new ArrayList<String>();
      var document=file==null?new ImportDocumentReader.Document("",List.of(),List.of()):documents.read(file);
      warnings.addAll(document.warnings());
      // A target audience restricts model suggestions, not existing human choices in other audiences.
      ObjectNode retainedContext=context.isObject()?((ObjectNode)context).deepCopy():json.createObjectNode();
      String retainedType=context.path("resourceType").asText();
      if(retainedType.isEmpty())retainedType=previous.path("resourceType").asText();
      for(var type:definition.at("/fields/resourceType/enum"))
        if(type.asText().equals(retainedType)){retainedContext.remove("audience");break;}
      var prior=schema.normalize(collection,withoutManualFields(previous),retainedContext,warnings);
      restoreManualFields(collection,previous,prior,warnings);
      ObjectNode proposed=json.createObjectNode();
      if(!text.isBlank()||!document.text().isBlank()||!document.images().isEmpty()) {
        var material=json.createObjectNode().put("collection",collection).put("text",text).put("documentText",document.text());
        material.set("schema",definition);material.set("context",context);material.set("previousDraft",prior);
        JsonNode answer=ask(material,document.images());
        ObjectNode suggestion=((ObjectNode)answer.path("draft")).deepCopy();
        if(collection.equals("resources")&&!hasInformation(suggestion.get("resourceType"))&&prior.has("resourceType"))
          suggestion.set("resourceType",prior.get("resourceType"));
        proposed=schema.normalize(collection,suggestion,context,warnings);
        if(proposed.isEmpty())warnings.add("没有识别到可填入的信息，请人工补充");
      } else warnings.add("没有识别到可填入的信息，请人工补充");
      // Sparse refinements preserve untouched human edits. The model cannot overwrite manual URLs or related IDs.
      ObjectNode merged=prior.deepCopy();
      proposed.fields().forEachRemaining(entry->{
        if(!hasInformation(entry.getValue()))return;
        if(entry.getKey().equals("views")&&merged.path("views").isArray()) {
          ((ArrayNode)merged.get("views")).addAll((ArrayNode)entry.getValue());
        } else if(entry.getValue().isObject()&&merged.path(entry.getKey()).isObject()) {
          ObjectNode nested=((ObjectNode)merged.get(entry.getKey())).deepCopy();
          entry.getValue().fields().forEachRemaining(field->{if(hasInformation(field.getValue()))nested.set(field.getKey(),field.getValue());});
          merged.set(entry.getKey(),nested);
        } else merged.set(entry.getKey(),entry.getValue());
      });
      ObjectNode validated=schema.normalize(collection,withoutManualFields(merged),retainedContext,warnings);
      restoreManualFields(collection,previous,validated,warnings);
      var result=json.createObjectNode().put("collection",collection).put("model",MODEL)
          .put("message","已生成待复核表单，请核对后手动保存或发布");
      result.set("draft",validated);
      result.set("missingFields",json.valueToTree(schema.missingFields(collection,validated)));
      result.set("warnings",json.valueToTree(warnings.stream().distinct().limit(20).toList()));
      result.putObject("source").put("kind",file==null?"text":"file")
          .put("name",file==null?"文字录入":safeName(file.getOriginalFilename()))
          .put("characters",text.length()+document.text().length());
      return result;
    } finally {running.release();}
  }

  private void reserve() {
    long now=System.nanoTime();
    while(!recent.isEmpty()&&now-recent.peekFirst()>=TimeUnit.MINUTES.toNanos(1))recent.removeFirst();
    if(recent.size()>=6)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"每分钟最多识别6次，请稍后重试");
    recent.addLast(now);
  }

  private JsonNode ask(ObjectNode material,List<String> images) {
    var payload=json.createObjectNode().put("model",MODEL).put("stream",false).put("max_tokens",6144).put("temperature",0);
    payload.putObject("thinking").put("type","disabled");
    payload.putObject("response_format").put("type","json_object");
    var messages=payload.putArray("messages");
    messages.addObject().put("role","system").put("content",prompt);
    var content=messages.addObject().put("role","user").putArray("content");
    content.addObject().put("type","text").put("text",material.toString());
    for(String image:images) {
      if(!image.startsWith("data:image/jpeg;base64,")&&!image.startsWith("data:image/png;base64,"))throw ContentValidation.bad("文档图片格式不正确");
      content.addObject().put("type","image_url").putObject("image_url").put("url",image).put("detail","original");
    }
    byte[] bytes;
    try {bytes=json.writeValueAsBytes(payload);} catch(IOException error) {throw failed("录入请求无法编码");}
    if(bytes.length>8*1024*1024)throw ContentValidation.bad("提取材料过大，请拆分文件");
    var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(45))
        .header("Authorization","Bearer "+key).header("Content-Type","application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
    CompletableFuture<HttpResponse<byte[]>> pending=http.sendAsync(request,info->new LimitedBody());
    try {
      var response=pending.get(50,TimeUnit.SECONDS);
      if(response.statusCode()!=200) {
        if(response.statusCode()==401||response.statusCode()==403)throw failed("DeepSeek密钥无效或无调用权限，请管理员检查服务器配置");
        if(response.statusCode()==429)throw failed("DeepSeek额度或请求频率受限，请稍后重试");
        throw failed("DeepSeek服务暂不可用，请稍后重试");
      }
      var reader=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
      JsonNode responseBody=reader.readTree(response.body());
      if(responseBody==null||!responseBody.isObject())throw failed("识别服务返回空结果，请重试");
      JsonNode choice=responseBody.path("choices").path(0);
      if(!choice.path("finish_reason").asText().equals("stop"))throw failed("识别结果未完整返回，请缩短或拆分材料后重试");
      var output=choice.path("message").path("content");
      if(!output.isTextual()||output.asText().length()>100000)throw failed("识别结果格式不正确，请重新提交材料");
      JsonNode result=reader.readTree(output.asText());
      if(result==null||!result.isObject()||!result.path("draft").isObject())throw failed("识别结果不符合表单格式，请重新提交材料");
      return result;
    } catch(InterruptedException error) {
      Thread.currentThread().interrupt();throw failed("识别请求已取消");
    } catch(TimeoutException error) {throw failed("识别超时，请缩短材料后重试");}
    catch(ExecutionException|IOException error) {throw failed("识别服务连接或结果异常，请稍后重试");}
    finally {if(!pending.isDone())pending.cancel(true);}
  }

  private void restoreManualFields(String collection,JsonNode previous,ObjectNode result,List<String> warnings) {
    for(String field:List.of("imageUrl","sourceUrl")) {
      if(!previous.has(field)||field.equals("sourceUrl")&&!collection.equals("policies"))continue;
      try {
        String value=field.equals("imageUrl")?ContentValidation.image(previous,field):ContentValidation.url(previous,field);
        if(!value.isEmpty())result.put(field,value);
      } catch(ResponseStatusException error) {warnings.add("人工草稿中的"+field+"格式无效，已留空");}
    }
    if(collection.equals("institutions")&&previous.path("images").isArray()) {
      var kept=json.createArrayNode();
      for(JsonNode image:previous.path("images")) {
        if(kept.size()==12)break;
        try {var one=json.createObjectNode();one.set("image",image);String url=ContentValidation.image(one,"image");if(!url.isEmpty())kept.add(url);}
        catch(ResponseStatusException error) {warnings.add("人工草稿中有无效图片地址，已忽略");}
      }
      if(!kept.isEmpty())result.set("images",kept);
    }
    if(collection.equals("promos")&&previous.path("action").isObject()&&previous.path("action").path("type").isTextual()&&!previous.path("action").path("type").asText().isBlank()) {
      JsonNode action=previous.path("action");
      try {
        String type=ContentValidation.choice(action,"type",Set.of("article","none","resource","policy"),"article");
        String target=Set.of("resource","policy").contains(type)?ContentValidation.text(action,"targetId",96,true):"";
        result.putObject("action").put("type",type).put("targetId",target);
      } catch(ResponseStatusException error) {warnings.add("人工草稿中的跳转关联不完整，请重新选择");}
    }
  }

  private static ObjectNode withoutManualFields(JsonNode value) {
    ObjectNode copy=value.deepCopy();copy.remove(List.of("imageUrl","sourceUrl","images","sortOrder","homeRecommended"));
    if(!Set.of("article","none").contains(copy.path("action").path("type").asText()))copy.remove("action");
    return copy;
  }
  private static boolean hasInformation(JsonNode value) {
    return value!=null&&!value.isNull()&&(!value.isTextual()||!value.asText().isBlank())&&(!value.isContainerNode()||!value.isEmpty());
  }
  private static String safeName(String name) {
    String clean=Objects.toString(name,"文件").replaceAll("[\\p{Cntrl}\\\\/]","_");
    return clean.substring(0,Math.min(120,clean.length()));
  }
  private static ResponseStatusException failed(String message) {return new ResponseStatusException(HttpStatus.BAD_GATEWAY,message);}

  /** Bound the body while receiving it, before Jackson allocates a model tree. Request timeout covers stalled bodies. */
  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body=new CompletableFuture<>();
    private final ByteArrayOutputStream output=new ByteArrayOutputStream();
    private java.util.concurrent.Flow.Subscription subscription;
    public CompletionStage<byte[]> getBody(){return body;}
    public void onSubscribe(java.util.concurrent.Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
    public void onNext(List<ByteBuffer> items) {
      for(var item:items) {
        if(item.remaining()>128*1024-output.size()) {subscription.cancel();body.completeExceptionally(new IOException("Response exceeds limit"));return;}
        byte[] bytes=new byte[item.remaining()];item.get(bytes);output.writeBytes(bytes);
      }
      subscription.request(1);
    }
    public void onError(Throwable error){body.completeExceptionally(error);}
    public void onComplete(){body.complete(output.toByteArray());}
  }
}
