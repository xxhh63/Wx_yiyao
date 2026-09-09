package com.tencent.wxcloudrun.content;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static com.tencent.wxcloudrun.content.ContentValidation.*;

@Service
@Transactional(readOnly=true)
public class ContentService {
  private final ContentStore store;
  private final CatalogSchema schema;
  private final ContentValidation validation;
  private final ObjectMapper json;
  private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate sql;
  public ContentService(ContentStore store,CatalogSchema schema,ContentValidation validation,ObjectMapper json,org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate sql){this.store=store;this.schema=schema;this.validation=validation;this.json=json;this.sql=sql;}
  static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"内容不存在或已下架");}
  static int page(Map<String,String> query,String key,int fallback,int max) {
    String value=query.get(key);if(value==null)return fallback;
    if(!value.matches("[1-9][0-9]{0,6}"))throw bad("分页参数无效");
    int n=Integer.parseInt(value);if(n>max)throw bad("分页参数超出范围");return n;
  }
  private static String term(Map<String,String> q) {
    String s=q.getOrDefault("keyword","").trim();if(s.codePointCount(0,s.length())>100)throw bad("搜索词过长");return s;
  }
  private static String like(String v){return "%"+v.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
  private static String jsonField(String field) {return "JSON_UNQUOTE(JSON_EXTRACT(c.payload,'$."+field+"'))";}
  public JsonNode catalog(String audience){return schema.get(audience);}
  public Map<String,Object> stats(){return store.stats();}
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Map<String,Object> home() {
    var featured=store.select("featured","JOIN content_resource r ON r.id=c.resource_id","c.publication_status='PUBLISHED' AND r.publication_status='PUBLISHED' AND r.business_status='open'",Map.of(),"c.sort_order,c.id",100,0);
    List<ObjectNode> cards=new ArrayList<>();
    for(var slot:featured) {
      var resource=store.one("resources",slot.path("resourceId").asText(),false);
      ObjectNode card=resource.deepCopy();card.remove(List.of("sections","attributes","views","publicationStatus","version"));
      card.put("resourceId",resource.path("id").asText()).put("id",slot.path("id").asText());cards.add(card);
    }
    var promos=store.select("promos","","c.publication_status='PUBLISHED'",Map.of(),"c.sort_order,c.id",100,0);
    promos.removeIf(p->!validAction(p,false));
    promos.forEach(p->p.remove("sections"));
    var policies=store.select("policies","","c.publication_status='PUBLISHED' AND JSON_EXTRACT(c.payload,'$.homeRecommended')=true",Map.of(),"c.sort_order,"+jsonField("date")+" DESC,c.id",3,0);
    policies.forEach(p->p.remove("sections"));
    return Map.of("featured",cards,"promos",promos,"policies",policies,"stats",stats(),"updatedAt",Instant.now().toString());
  }
  public Map<String,Object> list(String collection,Map<String,String> query,boolean admin) {
    store.table(collection);
    int page=page(query,"page",1,1000000),size=page(query,"pageSize",20,50);
    var params=new HashMap<String,Object>();String join="";
    StringBuilder where=new StringBuilder(admin?"1=1":"c.publication_status='PUBLISHED'");
    Set<String> allowed=new HashSet<>(Set.of("page","pageSize","keyword","sort"));
    String order="c.sort_order,c.id";
    if(collection.equals("resources")&&(!admin||query.containsKey("audience"))) {
      allowed.addAll(Set.of("audience","category"));
      String audience=query.get("audience"),category=query.getOrDefault("category","all");
      var filters=schema.filters(audience,category);
      join="JOIN content_resource_view v ON v.resource_id=c.id";
      params.put("audience",audience);where.append(" AND v.audience=:audience");
      if(!category.equals("all")){where.append(" AND v.category=:category");params.put("category",category);}
      int index=0;
      for(var filter:filters) {
        String key=filter.path("key").asText();allowed.add(key);
        if(key.equals("sort"))continue;
        String value=query.get(key);if(value==null||value.equals("all"))continue;
        boolean valid=false;for(var option:filter.path("options"))if(option.path("value").asText().equals(value))valid=true;
        if(!valid)throw bad("筛选选项无效："+key);
        String field=filter.path("field").asText(key);
        String pname="f"+(index++);params.put(pname,value);
        if(field.equals("kind"))where.append(" AND c.kind=:").append(pname);
        else {
          if(field.equals("cooperation"))field="cooperationModes";
          String path=Set.of("industries","cooperationModes").contains(field)?"$."+field:"$.attributes."+field;
          if(!field.matches("[a-zA-Z][a-zA-Z0-9]*"))throw new IllegalStateException("Invalid catalog schema");
          if(Set.of("industries","cooperationModes","indications").contains(field))where.append(" AND JSON_CONTAINS(JSON_EXTRACT(c.payload,'").append(path).append("'),JSON_QUOTE(:").append(pname).append("))");
          else where.append(" AND JSON_UNQUOTE(JSON_EXTRACT(c.payload,'").append(path).append("'))=:").append(pname);
        }
      }
      order="v.sort_order,c.id";
    }
    if(collection.equals("policies")) {
      allowed.add("category");
      String category=query.getOrDefault("category","全部");
      if(!Set.of("全部","all","科技创新","成果转化","知识产权","产业扶持").contains(category))throw bad("未知政策分类");
      if(!Set.of("全部","all").contains(category)){where.append(" AND ").append(jsonField("category")).append("=:category");params.put("category",category);}
      order=jsonField("date")+" DESC,c.sort_order,c.id";
    }
    if(collection.equals("institutions")) {
      allowed.add("industry");String industry=query.get("industry");
      if(industry!=null&&!industry.equals("all")&&!industry.isBlank()){
        if(industry.length()>30)throw bad("行业筛选无效");
        where.append(" AND JSON_CONTAINS(JSON_EXTRACT(c.payload,'$.industries'),JSON_QUOTE(:industry))");params.put("industry",industry);
      }
    }
    if(admin){allowed.addAll(Set.of("resourceType","publicationStatus"));
      if(query.containsKey("resourceType")){
        String type=query.get("resourceType");if(!TYPES.contains(type)||!collection.equals("resources"))throw bad("资源类型无效");
        where.append(" AND c.resource_type=:type");params.put("type",type);
      }
      if(query.containsKey("publicationStatus")){
        String state=query.get("publicationStatus");if(!Set.of("DRAFT","PUBLISHED","OFFLINE").contains(state))throw bad("发布状态无效");
        where.append(" AND c.publication_status=:state");params.put("state",state);
      }
    }
    for(String key:query.keySet())if(!allowed.contains(key))throw bad("未知查询参数："+key);
    String keyword=term(query);
    if(!keyword.isEmpty()) {
      String searchable=collection.equals("policies")?"CONCAT_WS(' ',"+jsonField("title")+","+jsonField("summary")+","+jsonField("sections")+")"
        :"CONCAT_WS(' ',"+jsonField("title")+","+jsonField("name")+","+jsonField("summary")+","+jsonField("issuer")+","+jsonField("industries")+","+jsonField("tags")+","+jsonField("attributes")+")";
      if(collection.equals("featured")&&admin) {
        join="JOIN content_resource r ON r.id=c.resource_id";
        searchable="JSON_UNQUOTE(JSON_EXTRACT(r.payload,'$.title'))";
      }
      where.append(" AND LOWER(").append(searchable).append(") LIKE :keyword ESCAPE '!'");
      params.put("keyword",like(keyword));
    }
    String sort=query.getOrDefault("sort","default");
    if(!Set.of("default","newest").contains(sort))throw bad("排序方式无效");
    if(sort.equals("newest"))order="c.published_at DESC,"+order;
    long total=store.count(collection,join,where.toString(),params);
    var items=store.select(collection,join,where.toString(),params,order,size,(page-1)*size);
    for(var item:items) {
      item.remove("sections");
      if(collection.equals("featured")&&admin) {
        var resource=store.one("resources",item.path("resourceId").asText(),false);
        if(resource!=null)item.put("resourceTitle",resource.path("title").asText()).put("title",resource.path("title").asText());
      }
      if(collection.equals("resources")&&query.containsKey("audience")) {
        String a=query.get("audience");
        for(var v:store.views(item.path("id").asText()))if(v.get("audience").equals(a)) {
          item.put("category",v.get("category").toString());item.put(admin?"viewSortOrder":"sortOrder",((Number)v.get("sortOrder")).intValue());
          item.put("legacyId",v.get("legacyId").toString());
        }
      }
    }
    return Map.of("items",items,"total",total,"page",page,"pageSize",size);
  }
  public ObjectNode detail(String collection,String id,String audience,boolean admin) {
    if(!id.matches("[A-Za-z0-9_-]{1,96}"))throw missing();
    if(audience!=null){schema.get(audience);id=store.resolve(id,audience);}
    ObjectNode item=store.one(collection,id,false);
    if(item==null||(!admin&&!item.path("publicationStatus").asText().equals("PUBLISHED")))throw missing();
    if(collection.equals("promos")&&!admin&&!validAction(item,false))throw missing();
    if(collection.equals("resources")) {
      var views=store.views(id);
      if(admin)item.set("views",json.valueToTree(views));
      if(audience!=null)for(var v:views)if(v.get("audience").equals(audience))item.put("category",v.get("category").toString());
    }
    return item;
  }
  private boolean validAction(ObjectNode promo,boolean lock) {
    String type=promo.at("/action/type").asText("article");
    if(!Set.of("resource","policy").contains(type))return true;
    var target=store.one(type.equals("resource")?"resources":"policies",promo.at("/action/targetId").asText(),lock);
    return target!=null&&target.path("publicationStatus").asText().equals("PUBLISHED")
      &&(!type.equals("resource")||target.path("status").asText().equals("open"));
  }
  @Transactional
  public ObjectNode save(String collection,String id,JsonNode input,String actor) {
    Long version=id==null?null:version(input);
    ObjectNode old=id==null?null:detail(collection,id,null,true);
    if(old!=null&&old.path("version").asLong()!=version)throw new ResponseStatusException(HttpStatus.CONFLICT,"内容已变更，请保留输入并重新加载");
    ObjectNode submitted=object(input).deepCopy();
    if(old!=null)for(String field:List.of("sourceKind","sourceNote"))
      if(!submitted.has(field)&&old.has(field))submitted.set(field,old.get(field).deepCopy());
    ObjectNode data=validation.validate(collection,submitted);
    if(id==null)id=UUID.randomUUID().toString();
    if(input.has("id")&&!input.path("id").asText().equals(id))throw bad("内容ID不能修改");
    String status=old==null?"DRAFT":old.path("publicationStatus").asText();
    if(collection.equals("promos")&&data.at("/action/type").asText().equals("article"))((ObjectNode)data.path("action")).put("targetId",id);
    references(collection,data,status);
    try{store.write(collection,id,data,status,version,actor,old==null?"CREATE":"UPDATE");}catch(DuplicateKeyException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"该资源已被推荐或关联，请刷新后再试");}
    return detail(collection,id,null,true);
  }
  @Transactional
  public ObjectNode publication(String collection,String id,JsonNode input,String actor) {
    object(input);keys(input,Set.of("status","version"));
    long version=version(input);
    String status=choice(input,"status",Set.of("PUBLISHED","OFFLINE"),"OFFLINE");
    ObjectNode old=detail(collection,id,null,true);
    if(old.path("version").asLong()!=version)throw new ResponseStatusException(HttpStatus.CONFLICT,"内容已变更，请重新加载");
    // Revalidate all currently saved fields at the publishing boundary.
    ObjectNode data=validation.validate(collection,old);
    if(collection.equals("promos")&&data.at("/action/type").asText().equals("article"))((ObjectNode)data.path("action")).put("targetId",id);
    references(collection,data,status);
    store.write(collection,id,data,status,version,actor,status.equals("PUBLISHED")?"PUBLISH":"UNPUBLISH");
    return detail(collection,id,null,true);
  }
  private void references(String collection,ObjectNode data,String publication) {
    if(collection.equals("featured")) {
      ObjectNode target=store.one("resources",data.path("resourceId").asText(),true);
      if(target==null)throw bad("所选主推资源不存在");
      if(publication.equals("PUBLISHED")&&(!target.path("publicationStatus").asText().equals("PUBLISHED")||!target.path("status").asText().equals("open")))throw bad("主推资源必须已发布且可对接");
    }
    if(collection.equals("promos")&&publication.equals("PUBLISHED")&&!validAction(data,true))throw bad("广告关联目标不存在、未发布或已结束");
    if(collection.equals("resources")&&publication.equals("PUBLISHED")&&data.path("status").asText().equals("withdrawn"))throw bad("已撤回资源不能上架，请先调整业务状态");
  }
  public Map<String,Object> audit(Map<String,String> q) {
    int page=page(q,"page",1,1000000),size=page(q,"pageSize",20,50);
    for(var k:q.keySet())if(!Set.of("page","pageSize").contains(k))throw bad("未知查询参数");
    long total=sql.queryForObject("SELECT COUNT(*) FROM content_audit",Map.of(),Long.class);
    var items=sql.queryForList("SELECT id,actor,operation,entity_type AS entityType,entity_id AS entityId,before_version AS beforeVersion,after_version AS afterVersion,created_at AS createdAt FROM content_audit ORDER BY id DESC LIMIT :size OFFSET :offset",Map.of("size",size,"offset",(page-1)*size));
    return Map.of("items",items,"total",total,"page",page,"pageSize",size);
  }
}
