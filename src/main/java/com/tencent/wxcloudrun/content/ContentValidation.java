package com.tencent.wxcloudrun.content;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;

@Component
public class ContentValidation {
  static final Set<String> TYPES=Set.of("project","mah","scene","talent","technology","patent","data","service","achievement","cro","cdmo","solution","ip_service","financing_service");
  static boolean multipleAttribute(String field) { return Set.of("indications","investorIndications").contains(field); }
  static final Set<String> META=Set.of("id","publicationStatus","sortOrder","version","publishedAt","updatedAt");
  private final CatalogSchema schema;
  public ContentValidation(CatalogSchema schema) { this.schema=schema; }
  public static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
  static ObjectNode object(JsonNode node) {
    if(node==null||!node.isObject())throw bad("请求必须为对象");
    return (ObjectNode)node;
  }
  static void keys(JsonNode node,Set<String> allowed) {
    node.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw bad("不支持的字段："+key);});
  }
  static String text(JsonNode node,String key,int max,boolean required) {
    var v=node.get(key);
    if(v==null) { if(required)throw bad("请填写"+key);return ""; }
    if(!v.isTextual())throw bad(key+"必须是文字");
    String s=v.asText().trim();
    if(s.codePointCount(0,s.length())>max || (required&&s.isEmpty()) || s.indexOf('\0')>=0)throw bad(key+"长度不符合要求");
    return s;
  }
  static String choice(JsonNode node,String key,Set<String> allowed,String fallback) {
    String value=node.has(key)?text(node,key,80,true):fallback;
    if(!allowed.contains(value))throw bad(key+"选项无效");
    return value;
  }
  static int integer(JsonNode node,String key,int fallback) {
    if(!node.has(key))return fallback;
    var v=node.get(key);
    if(!v.isIntegralNumber()||!v.canConvertToInt()||v.intValue()<0)throw bad(key+"必须为非负整数");
    return v.intValue();
  }
  static long version(JsonNode node) {
    var v=node.get("version");
    if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<1)throw bad("请提供正确的内容版本");
    return v.longValue();
  }
  static ArrayNode strings(JsonNode input,String key,int maxItems,int maxLength) {
    ArrayNode out=JsonNodeFactory.instance.arrayNode();
    if(!input.has(key))return out;
    JsonNode v=input.get(key);
    if(!v.isArray()||v.size()>maxItems)throw bad(key+"必须为有效列表");
    Set<String> seen=new HashSet<>();
    for(var item:v) {
      if(!item.isTextual())throw bad(key+"每项必须为文字");
      String s=item.asText().trim();
      if(s.isEmpty()||s.codePointCount(0,s.length())>maxLength||s.contains("\0"))throw bad(key+"内容不符合要求");
      if(seen.add(s))out.add(s);
    }
    return out;
  }
  static String url(JsonNode n,String key) {
    if(n.has(key)&&n.get(key).isNull())return "";
    String s=text(n,key,2048,false);
    if(s.isEmpty())return s;
    try {
      URI u=URI.create(s);
      if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)throw bad(key+"必须是HTTPS地址");
    } catch(IllegalArgumentException e){throw bad(key+"地址无效");}
    return s;
  }
  static String image(JsonNode n,String key) {
    String s=text(n,key,2048,false);
    if(Set.of("/assets/phase1/service-lab.png","/assets/phase1/service-equipment.png","/assets/phase1/service-material.png").contains(s))return s;
    return url(n,key);
  }
  static ArrayNode sections(JsonNode input) {
    ArrayNode out=JsonNodeFactory.instance.arrayNode();
    if(!input.has("sections"))return out;
    var sections=input.get("sections");
    if(!sections.isArray()||sections.size()>20)throw bad("正文最多20节");
    int length=0;
    for(var section:sections) {
      object(section);keys(section,Set.of("heading","paragraphs"));
      String heading=text(section,"heading",100,false);
      var paragraphs=strings(section,"paragraphs",10,2000);
      length+=heading.length();
      for(var p:paragraphs)length+=p.asText().length();
      out.addObject().put("heading",heading).set("paragraphs",paragraphs);
    }
    if(length>30000)throw bad("正文过长");
    return out;
  }
  public ObjectNode validate(String collection,JsonNode input) {
    object(input);
    Set<String> allowed=new HashSet<>(META);
    allowed.addAll(Set.of("sourceKind","sourceNote"));
    switch(collection) {
      case "resources" -> allowed.addAll(Set.of("resourceType","category","kind","publisherRole","issuer","title","summary","city","region","amountWan","amountLabel","industries","tags","cooperationModes","attributes","sections","imageUrl","tone","status","views"));
      case "featured" -> allowed.add("resourceId");
      case "promos" -> allowed.addAll(Set.of("eyebrow","title","description","tone","imageUrl","buttonText","action","sections"));
      case "policies" -> allowed.addAll(Set.of("title","summary","category","region","date","sourceName","sourceUrl","sections","homeRecommended"));
      case "institutions" -> allowed.addAll(Set.of("name","summary","region","industries","serviceTags","images","sections"));
      default -> throw bad("未知内容类型");
    }
    keys(input,allowed);
    ObjectNode out=JsonNodeFactory.instance.objectNode();
    out.put("sortOrder",integer(input,"sortOrder",0));
    out.put("sourceKind",choice(input,"sourceKind",Set.of("original","reprint","official","legacy_sample"),"original"));
    out.put("sourceNote",text(input,"sourceNote",1000,false));
    if(collection.equals("featured")) {
      out.put("resourceId",text(input,"resourceId",96,true));return out;
    }
    out.put(collection.equals("institutions")?"name":"title",text(input,collection.equals("institutions")?"name":"title",120,true));
    out.set("sections",sections(input));
    if(!collection.equals("promos")) {
      out.put("summary",text(input,"summary",500,false));out.put("region",text(input,"region",50,false));
    }
    if(collection.equals("resources")||collection.equals("promos")) {
      out.put("imageUrl",image(input,"imageUrl"));
      out.put("tone",choice(input,"tone",Set.of("medical","cyber","material","energy","lab","build","car","bio","mint","blue","aqua","robot","network","navy"),collection.equals("promos")?"blue":"medical"));
    }
    switch(collection) {
      case "resources" -> resource(input,out);
      case "promos" -> {
        out.put("eyebrow",text(input,"eyebrow",20,false));
        out.put("description",text(input,"description",500,false));
        out.put("buttonText",input.has("buttonText")?text(input,"buttonText",20,true):"查看详情");
        var action=input.has("action")?object(input.get("action")):JsonNodeFactory.instance.objectNode().put("type","article").put("targetId","");
        keys(action,Set.of("type","targetId"));
        String type=choice(action,"type",Set.of("article","resource","policy","none"),"article");
        String target=Set.of("resource","policy").contains(type)?text(action,"targetId",96,true):"";
        out.putObject("action").put("type",type).put("targetId",target);
      }
      case "policies" -> {
        out.put("category",choice(input,"category",Set.of("科技创新","成果转化","知识产权","产业扶持"),"科技创新"));
        String date=text(input,"date",10,true);
        try{if(!LocalDate.parse(date).toString().equals(date))throw bad("资讯日期无效");}catch(java.time.DateTimeException e){throw bad("资讯日期无效");}
        out.put("date",date);out.put("sourceName",text(input,"sourceName",120,true));out.put("sourceUrl",url(input,"sourceUrl"));
        if(Set.of("official","reprint").contains(out.path("sourceKind").asText())&&out.path("sourceUrl").asText().isBlank())throw bad("转载或官方资料需要来源链接");
        if(input.has("homeRecommended")&&!input.path("homeRecommended").isBoolean())throw bad("首页推荐必须为布尔值");
        out.put("homeRecommended",input.path("homeRecommended").asBoolean(false));
      }
      case "institutions" -> {
        out.set("industries",strings(input,"industries",12,30));
        out.set("serviceTags",strings(input,"serviceTags",12,30));
        var images=strings(input,"images",12,2048);
        for(var entry:images) image(JsonNodeFactory.instance.objectNode().set("image",entry),"image");
        out.set("images",images);
      }
    }
    return out;
  }
  private void resource(JsonNode input,ObjectNode out) {
    String type=choice(input,"resourceType",TYPES,"project");
    out.put("resourceType",type);
    out.put("kind",choice(input,"kind",Set.of("supply","demand"),"supply"));
    out.put("status",choice(input,"status",Set.of("open","closed","withdrawn"),"open"));
    out.put("publisherRole",choice(input,"publisherRole",Set.of("platform","enterprise","investor"),"platform"));
    out.put("issuer",text(input,"issuer",120,false));out.put("city",text(input,"city",50,false));
    out.put("amountLabel",text(input,"amountLabel",30,false));
    var amount=input.get("amountWan");
    if(amount==null||amount.isNull())out.putNull("amountWan");
    else {
      if(!amount.isNumber())throw bad("金额必须为数字或空值");
      BigDecimal value=amount.decimalValue();
      if(value.signum()<0||value.compareTo(new BigDecimal("999999999999.99"))>0||value.stripTrailingZeros().scale()>2)throw bad("金额应为最多两位小数的非负数字");
      out.put("amountWan",value);
    }
    for(String field:List.of("industries","tags","cooperationModes"))out.set(field,strings(input,field,12,30));
    var attributes=input.has("attributes")?object(input.get("attributes")):JsonNodeFactory.instance.objectNode();
    var fields=schema.attributes(type);keys(attributes,fields.keySet());
    var normalized=out.putObject("attributes");
    fields.forEach((key,options)->{
      if(multipleAttribute(key)) {
        var values=strings(attributes,key,16,80);
        for(var value:values)if(!options.contains(value.asText()))throw bad(key+"选项无效");
        normalized.set(key,values);
      } else {
        String value=text(attributes,key,80,false);
        if(!value.isEmpty()&&!options.contains(value))throw bad(key+"选项无效");
        normalized.put(key,value);
      }
    });
    ArrayNode views=out.putArray("views");
    if(input.has("views")) {
      if(!input.get("views").isArray()||input.get("views").size()>5)throw bad("展示专区列表无效");
      Set<String> seen=new HashSet<>();
      for(var view:input.get("views")) {
        object(view);keys(view,Set.of("audience","category","sortOrder","legacyId","resourceId"));
        String audience=text(view,"audience",32,true),category=text(view,"category",32,true);
        schema.filters(audience,category);
        if(category.equals("all")||!category.equals(type)||!seen.add(audience))throw bad("专区分类重复或与资源类型不符");
        views.addObject().put("audience",audience).put("category",category).put("sortOrder",integer(view,"sortOrder",0));
      }
    }
  }
}
