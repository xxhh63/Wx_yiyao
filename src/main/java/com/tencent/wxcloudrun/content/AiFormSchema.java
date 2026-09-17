package com.tencent.wxcloudrun.content;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.*;
import static com.tencent.wxcloudrun.content.ContentValidation.bad;

/** A suggestion is deliberately sparse; the ordinary save validator still owns publication rules. */
@Component
public class AiFormSchema {
  private static final List<String> ROLES=List.of("investor","enterprise","scientist","manager");
  private static final Set<String> COLLECTIONS=Set.of("resources","policies","promos","institutions");
  private final CatalogSchema schema;
  private final ObjectMapper json;

  public AiFormSchema(CatalogSchema schema,ObjectMapper json) { this.schema=schema;this.json=json; }

  public ObjectNode describe(String collection,JsonNode context) {
    var target=context(collection,context);
    var out=json.createObjectNode().put("collection",collection);
    var fields=out.putObject("fields");
    textField(fields,collection.equals("institutions")?"name":"title",120,"材料中的名称或标题，未知留空");
    textField(fields,"sourceNote",1000,"来源补充说明，未知留空");
    enumField(fields,"sourceKind",List.of("original","reprint","official","legacy_sample"),"来源性质；不得猜测原创或官方");
    var sections=fields.putObject("sections").put("type","array").put("maxItems",20).put("maxTotalLength",30000).put("description","正文分节，保留原意");
    var sectionFields=sections.putObject("items").put("type","object").putObject("fields");
    textField(sectionFields,"heading",100,"小节标题");
    stringsField(sectionFields,"paragraphs",10,2000,"段落");
    if(!collection.equals("promos")) {
      textField(fields,"summary",500,"材料摘要");textField(fields,"region",50,"所在地区");
    }
    if(Set.of("resources","promos").contains(collection))
      enumField(fields,"tone",List.of("medical","cyber","material","energy","lab","build","car","bio","mint","blue","aqua","robot","network","navy"),"展示色调，仅明确时填写");
    switch(collection) {
      case "resources" -> {
        String audience=target.path("audience").asText(),type=target.path("resourceType").asText();
        enumField(fields,"resourceType",type.isEmpty()?types(audience):List.of(type),"目标资源类型，服从用户指定分类");
        enumField(fields,"kind",List.of("supply","demand"),"供需类型");
        enumField(fields,"publisherRole",List.of("platform","enterprise","investor"),"发布主体身份");
        enumField(fields,"status",List.of("open","closed","withdrawn"),"业务状态，未知留空");
        textField(fields,"issuer",120,"发布主体名称");textField(fields,"city",50,"所在城市");
        textField(fields,"amountLabel",30,"金额的原文说明");
        fields.putObject("amountWan").put("type","number").put("nullable",true).put("minimum",0).put("maximum",new BigDecimal("999999999999.99")).put("decimalPlaces",2).put("description","万元；仅材料明确金额时填写，不得将未知填为零");
        for(String field:List.of("industries","tags","cooperationModes"))stringsField(fields,field,12,30,"材料明确的"+field);
        fields.putObject("attributes").put("type","object").set("fields",attributeFields(type,audience.isEmpty()?ROLES:List.of(audience)));
        var viewFields=fields.putObject("views").put("type","array").put("maxItems",4).putObject("items").put("type","object").putObject("fields");
        enumField(viewFields,"audience",audience.isEmpty()?ROLES:List.of(audience),"展示专区，必须与当前字典分类配对");
        enumField(viewFields,"category",type.isEmpty()?types(audience):List.of(type),"必须等于 resourceType 且属于该专区");
        var catalog=out.putObject("catalog");
        for(String role:audience.isEmpty()?ROLES:List.of(audience)) {
          var current=catalog.putObject(role);var categories=current.putArray("categories");var filters=current.putObject("filtersByCategory");
          for(var category:schema.get(role).path("categories")) {
            String value=category.path("value").asText();
            if(!types(role).contains(value)||(!type.isEmpty()&&!type.equals(value)))continue;
            categories.add(category.deepCopy());
            var definitions=filters.putArray(value);
            for(var filter:schema.filters(role,value)) {
              if(filter.path("key").asText().equals("sort"))continue;
              ObjectNode definition=filter.deepCopy();var options=definition.putArray("options");
              for(var option:filter.path("options"))if(!option.path("value").asText().equals("all"))options.add(option.deepCopy());
              definitions.add(definition);
            }
          }
        }
      }
      case "policies" -> {
        enumField(fields,"category",List.of("科技创新","成果转化","知识产权","产业扶持"),"政策资讯分类");
        textField(fields,"date",10,"材料明确的日期 YYYY-MM-DD；未知留空");
        textField(fields,"sourceName",120,"材料明确的来源名称；未知留空");
      }
      case "promos" -> {
        textField(fields,"eyebrow",20,"短标签");textField(fields,"description",500,"展示说明");textField(fields,"buttonText",20,"按钮文字");
        var action=fields.putObject("action").put("type","object").putObject("fields");
        enumField(action,"type",List.of("article","none"),"仅本页正文或无动作；不生成关联内容 ID");
      }
      case "institutions" -> {
        stringsField(fields,"industries",12,30,"服务领域");stringsField(fields,"serviceTags",12,30,"服务标签");
      }
    }
    return out;
  }

  public ObjectNode normalize(String collection,JsonNode input,JsonNode context,List<String> warnings) {
    var fields=describe(collection,context).path("fields");
    var target=context(collection,context);
    var out=json.createObjectNode();
    if(input==null||!input.isObject()){warnings.add("AI 未返回有效对象，请补充材料后重试");return out;}
    input.fieldNames().forEachRemaining(key->{if(!fields.has(key))warnings.add("已忽略 AI 提供的不支持字段");});
    var names=fields.fieldNames();
    while(names.hasNext()) {
      String field=names.next();JsonNode value=input.get(field),definition=fields.get(field);
      if(value==null||Set.of("sections","resourceType","attributes","views","action","amountWan").contains(field))continue;
      JsonNode normalized=scalarOrStrings(value,definition,field,warnings);
      if(normalized!=null)out.set(field,normalized);
    }
    if(input.has("sections")) {
      var value=sections(input.get("sections"),warnings);
      if(value!=null)out.set("sections",value);
    }
    if(collection.equals("policies")&&out.hasNonNull("date")&&!out.path("date").asText().isEmpty()) {
      String date=input.path("date").asText().trim();
      try { if(!LocalDate.parse(date).toString().equals(date))throw new DateTimeException("invalid"); }
      catch(DateTimeException e){out.remove("date");warnings.add("已忽略无效日期，请人工核对");}
    }
    if(collection.equals("resources"))resource(input,out,target,warnings);
    if(collection.equals("promos")&&input.has("action")) {
      var action=input.get("action");String type=action.path("type").asText();
      if(action.isObject()&&Set.of("article","none").contains(type)) {
        out.putObject("action").put("type",type).put("targetId","");
        if(action.has("targetId")&&!action.path("targetId").asText().isEmpty())warnings.add("关联内容须人工选择，已忽略 AI 关联 ID");
        action.fieldNames().forEachRemaining(key->{if(!Set.of("type","targetId").contains(key))warnings.add("已忽略动作中的不支持字段");});
      } else if(!action.isNull())warnings.add("已忽略无效动作，关联内容须人工选择");
    }
    return out;
  }

  public List<String> missingFields(String collection,JsonNode draft) {
    if(!COLLECTIONS.contains(collection))throw bad("未知 AI 内容类型");
    List<String> required=switch(collection) {
      case "resources" -> List.of("title","resourceType","kind","publisherRole","status","sourceKind","tone","views");
      case "policies" -> List.of("title","date","sourceName","sourceKind","category");
      case "institutions" -> List.of("name","sourceKind");
      default -> List.of("title","sourceKind","tone","buttonText","action.type");
    };
    var missing=new ArrayList<String>();
    for(String field:required)if(missing(draft,field))missing.add(field);
    if(collection.equals("policies")&&draft!=null&&Set.of("official","reprint").contains(draft.path("sourceKind").asText())&&missing(draft,"sourceUrl"))missing.add("sourceUrl");
    if(collection.equals("promos")&&draft!=null&&Set.of("resource","policy").contains(draft.at("/action/type").asText())&&missing(draft,"action.targetId"))missing.add("action.targetId");
    return missing;
  }

  private boolean missing(JsonNode draft,String field) {
    var value=draft==null?MissingNode.getInstance():draft.at("/"+field.replace('.','/'));
    return value.isMissingNode()||value.isNull()||(value.isTextual()&&value.asText().isBlank())||(value.isContainerNode()&&value.isEmpty());
  }

  private ObjectNode context(String collection,JsonNode context) {
    if(!COLLECTIONS.contains(collection))throw bad("未知 AI 内容类型");
    var target=json.createObjectNode();
    if(context==null||context.isNull())return target;
    ContentValidation.object(context);ContentValidation.keys(context,Set.of("audience","resourceType"));
    String role=ContentValidation.text(context,"audience",32,false),type=ContentValidation.text(context,"resourceType",32,false);
    if(!role.isEmpty()&&!ROLES.contains(role))throw bad("请选择有效的资源专区");
    if(!type.isEmpty()&&!types(role).contains(type))throw bad("请选择专区当前可用的资源分类");
    return target.put("audience",role).put("resourceType",type);
  }

  private Set<String> types(String role) {
    Set<String> types=new LinkedHashSet<>();
    for(String audience:role.isEmpty()?ROLES:List.of(role))
      for(var category:schema.get(audience).path("categories")) {
        String type=category.path("value").asText();
        if(!category.path("pending").asBoolean()&&ContentValidation.TYPES.contains(type)&&!type.equals("achievement"))types.add(type);
      }
    return types;
  }

  private ObjectNode attributeFields(String type,List<String> roles) {
    var fields=json.createObjectNode();
    Map<String,Set<String>> options=new LinkedHashMap<>();
    for(String role:roles)for(String category:type.isEmpty()?types(role):List.of(type)) {
      if(!types(role).contains(category))continue;
      for(var filter:schema.filters(role,category)) {
        String field=filter.path("field").asText(filter.path("key").asText());
        if(Set.of("sort","kind","industries","cooperationModes","cooperation").contains(field))continue;
        var values=options.computeIfAbsent(field,ignored->new LinkedHashSet<>());
        filter.path("options").forEach(option->{String value=option.path("value").asText();if(!value.isEmpty()&&!value.equals("all"))values.add(value);});
      }
    }
    options.forEach((field,values)->{
      if(ContentValidation.multipleAttribute(field)) {
        stringsField(fields,field,16,80,"当前字典中的多选属性");
        fields.withObject("/"+field).withObject("/items").set("enum",json.valueToTree(values));
      } else enumField(fields,field,values,"当前字典属性");
    });
    return fields;
  }

  private void resource(JsonNode input,ObjectNode out,JsonNode target,List<String> warnings) {
    String role=target.path("audience").asText(),type=target.path("resourceType").asText();
    var proposed=input.get("resourceType");
    if(type.isEmpty()&&proposed!=null&&!proposed.isNull()) {
      if(proposed.isTextual()&&types(role).contains(proposed.asText().trim()))type=proposed.asText().trim();
      else warnings.add("已忽略无效或不属于目标专区的资源分类");
    } else if(!type.isEmpty()&&proposed!=null&&!proposed.asText().equals(type))warnings.add("已保留人工指定的资源分类");
    if(!type.isEmpty())out.put("resourceType",type);
    var views=json.createArrayNode();Set<String> roles=new LinkedHashSet<>();
    if(!role.isEmpty()&&!type.isEmpty()){roles.add(role);views.addObject().put("audience",role).put("category",type).put("sortOrder",0);}
    if(input.has("views")) {
      if(!input.path("views").isArray())warnings.add("已忽略无效展示专区列表");
      else for(var view:input.path("views")) {
        String audience=view.path("audience").asText(),category=view.path("category").asText();
        if(!view.isObject()||!ROLES.contains(audience)||!types(audience).contains(category)||!category.equals(type)||(!role.isEmpty()&&!role.equals(audience))) {
          warnings.add("已忽略不匹配的展示专区或分类");continue;
        }
        if(roles.add(audience)&&views.size()<4)views.addObject().put("audience",audience).put("category",type).put("sortOrder",0);
        view.fieldNames().forEachRemaining(key->{if(!Set.of("audience","category").contains(key))warnings.add("已忽略专区中的排序或关联字段");});
      }
    }
    if(!views.isEmpty())out.set("views",views);
    if(input.has("attributes")) {
      var attributes=input.get("attributes");
      if(!attributes.isObject()||type.isEmpty())warnings.add("属性需有效资源分类，已忽略无效属性");
      else {
        var definitions=attributeFields(type,roles.isEmpty()?(role.isEmpty()?ROLES:List.of(role)):new ArrayList<>(roles));
        var normalized=json.createObjectNode();
        attributes.fields().forEachRemaining(entry->{
          if(!definitions.has(entry.getKey()))warnings.add("已忽略当前专区字典外的属性");
          else {var value=scalarOrStrings(entry.getValue(),definitions.get(entry.getKey()),entry.getKey(),warnings);if(value!=null)normalized.set(entry.getKey(),value);}
        });
        if(!normalized.isEmpty()||attributes.isEmpty())out.set("attributes",normalized);
      }
    }
    if(input.has("amountWan")) {
      var amount=input.get("amountWan");
      if(amount.isNull())out.putNull("amountWan");
      else if(amount.isNumber()&&Double.isFinite(amount.doubleValue())&&amount.decimalValue().signum()>=0&&amount.decimalValue().compareTo(new BigDecimal("999999999999.99"))<=0&&amount.decimalValue().stripTrailingZeros().scale()<=2)out.set("amountWan",amount.deepCopy());
      else warnings.add("已忽略无效金额，请人工核对");
    }
  }

  private JsonNode scalarOrStrings(JsonNode value,JsonNode definition,String field,List<String> warnings) {
    if(value==null||value.isNull())return null;
    if(definition.path("type").asText().equals("array")) {
      if(!value.isArray()){warnings.add(field+"列表无效，已忽略");return null;}
      var values=json.createArrayNode();Set<String> seen=new HashSet<>();
      for(var item:value) {
        var text=scalarOrStrings(item,definition.path("items"),field,warnings);
        if(text==null||text.asText().isEmpty()||!seen.add(text.asText()))continue;
        if(values.size()>=definition.path("maxItems").asInt()){warnings.add(field+"列表过长，已截断");break;}
        values.add(text);
      }
      return values;
    }
    if(!value.isTextual()||value.asText().contains("\0")){warnings.add(field+"内容无效，已忽略");return null;}
    String text=value.asText().trim();
    if(definition.has("enum")) {
      if(text.isEmpty())return null;
      boolean allowed=false;for(var option:definition.get("enum"))if(option.asText().equals(text)){allowed=true;break;}
      if(!allowed){warnings.add(field+"选项无效，已忽略");return null;}
    }
    int max=definition.path("maxLength").asInt(80);
    if(text.codePointCount(0,text.length())>max){text=text.substring(0,text.offsetByCodePoints(0,max));warnings.add(field+"过长，已截断");}
    return TextNode.valueOf(text);
  }

  private ArrayNode sections(JsonNode input,List<String> warnings) {
    if(input==null||input.isNull())return null;
    if(!input.isArray()){warnings.add("正文格式无效，已忽略");return null;}
    var out=json.createArrayNode();int remaining=30000;
    var definition=json.createObjectNode();textField(definition,"heading",100,"标题");stringsField(definition,"paragraphs",10,2000,"段落");
    for(var section:input) {
      if(out.size()>=20||remaining==0){warnings.add("正文过长，已截断");break;}
      if(!section.isObject()){warnings.add("已忽略无效正文小节");continue;}
      var normalized=json.createObjectNode();
      var heading=scalarOrStrings(section.get("heading"),definition.get("heading"),"小节标题",warnings);
      if(heading!=null){String text=clip(heading.asText(),remaining,warnings);normalized.put("heading",text);remaining-=text.length();}
      var paragraphs=scalarOrStrings(section.get("paragraphs"),definition.get("paragraphs"),"正文段落",warnings);
      if(paragraphs!=null) {
        var kept=normalized.putArray("paragraphs");
        for(var paragraph:paragraphs) {
          if(remaining==0){warnings.add("正文过长，已截断");break;}
          String text=clip(paragraph.asText(),remaining,warnings);kept.add(text);remaining-=text.length();
        }
      }
      section.fieldNames().forEachRemaining(key->{if(!Set.of("heading","paragraphs").contains(key))warnings.add("已忽略正文中的不支持字段");});
      if(!normalized.isEmpty())out.add(normalized);
    }
    return out;
  }

  private String clip(String value,int max,List<String> warnings) {
    if(value.length()<=max)return value;
    warnings.add("正文过长，已截断");
    if(max>0&&Character.isHighSurrogate(value.charAt(max-1)))max--;
    return value.substring(0,max);
  }

  private void textField(ObjectNode fields,String field,int max,String description) {
    fields.putObject(field).put("type","string").put("maxLength",max).put("description",description);
  }
  private void enumField(ObjectNode fields,String field,Collection<String> values,String description) {
    fields.putObject(field).put("type","string").put("maxLength",80).put("description",description).set("enum",json.valueToTree(values));
  }
  private void stringsField(ObjectNode fields,String field,int maxItems,int maxLength,String description) {
    fields.putObject(field).put("type","array").put("maxItems",maxItems).put("description",description).putObject("items").put("type","string").put("maxLength",maxLength);
  }
}
