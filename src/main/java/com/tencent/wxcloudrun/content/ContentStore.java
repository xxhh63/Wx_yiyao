package com.tencent.wxcloudrun.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.tencent.wxcloudrun.content.ContentValidation.*;

@Repository
public class ContentStore {
  private static final Map<String,String> TABLES=Map.of("resources","content_resource","featured","home_featured","promos","home_promo","policies","policy_article","institutions","service_institution");
  private final NamedParameterJdbcTemplate sql;
  private final ObjectMapper json;
  public ContentStore(NamedParameterJdbcTemplate sql,ObjectMapper json){this.sql=sql;this.json=json;}
  String table(String kind){String t=TABLES.get(kind);if(t==null)throw bad("未知内容类型");return t;}
  ObjectNode row(ResultSet rs,String kind)throws SQLException {
    ObjectNode n;
    try {n=(ObjectNode)json.readTree(rs.getString("payload"));}catch(Exception e){throw new SQLException("Invalid content record",e);}
    n.put("id",rs.getString("id")).put("publicationStatus",rs.getString("publication_status"))
      .put("sortOrder",rs.getInt("sort_order")).put("version",rs.getLong("version"));
    n.put("publishedAt",rs.getTimestamp("published_at").toLocalDateTime().toInstant(ZoneOffset.UTC).toString())
      .put("updatedAt",rs.getTimestamp("updated_at").toLocalDateTime().toInstant(ZoneOffset.UTC).toString());
    if(kind.equals("resources")) n.put("resourceType",rs.getString("resource_type")).put("category",rs.getString("resource_type"))
      .put("kind",rs.getString("kind")).put("status",rs.getString("business_status"));
    // Legacy display price remains in the import snapshot; API and edits use normalized amountWan/amountLabel.
    if(kind.equals("resources"))n.remove("price");
    if(kind.equals("featured"))n.put("resourceId",rs.getString("resource_id"));
    return n;
  }
  ObjectNode one(String kind,String id,boolean lock) {
    var list=sql.query("SELECT c.* FROM "+table(kind)+" c WHERE id=:id"+(lock?" FOR UPDATE":""),Map.of("id",id),(rs,i)->row(rs,kind));
    return list.isEmpty()?null:list.getFirst();
  }
  List<ObjectNode> select(String kind,String from,String where,Map<String,?> params,String order,int limit,int offset) {
    var p=new HashMap<String,Object>(params);p.put("limit",limit);p.put("offset",offset);
    return sql.query("SELECT c.* FROM "+table(kind)+" c "+from+" WHERE "+where+" ORDER BY "+order+" LIMIT :limit OFFSET :offset",p,(rs,i)->row(rs,kind));
  }
  long count(String kind,String from,String where,Map<String,?> params){
    return sql.queryForObject("SELECT COUNT(*) FROM "+table(kind)+" c "+from+" WHERE "+where,params,Long.class);
  }
  List<Map<String,Object>> views(String id) {
    return sql.query("SELECT audience,category,sort_order,legacy_id FROM content_resource_view WHERE resource_id=:id ORDER BY audience",
      Map.of("id",id),(rs,i)->Map.of("audience",rs.getString(1),"category",rs.getString(2),"sortOrder",rs.getInt(3),"legacyId",rs.getString(4)));
  }
  String resolve(String id,String audience) {
    if(audience==null||audience.isBlank())return id;
    var result=sql.queryForList("SELECT resource_id FROM content_resource_view WHERE audience=:a AND legacy_id=:id",Map.of("a",audience,"id",id),String.class);
    return result.isEmpty()?id:result.getFirst();
  }
  Map<String,Object> stats() {
    // Read actual distinct resource rows, never presentation aliases or app_user/user_card.
    String active="publication_status='PUBLISHED' AND business_status='open'";
    return sql.queryForObject("""
      SELECT
      COALESCE(SUM(kind='demand'),0) AS demands,
      COALESCE(SUM(kind='supply' AND (resource_type IN ('project','technology') OR (resource_type='achievement' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.attributes.achievementType'))='技术成果'))),0) AS achievements,
      COALESCE(SUM(resource_type='talent' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.attributes.talentMaturity'))='资深专家'),0) AS experts,
      COALESCE(SUM((resource_type='patent' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.attributes.patentType'))<>'软著') OR (resource_type='achievement' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.attributes.achievementType')) IN ('专利包','纯专利权利'))),0) AS patents
      FROM content_resource WHERE
      """+active,Map.of(),(rs,i)->{
        Map<String,Object> n=new LinkedHashMap<>();
        n.put("technicalDemands",rs.getLong("demands"));n.put("achievements",rs.getLong("achievements"));
        n.put("experts",rs.getLong("experts"));n.put("technologyManagers",0L);n.put("universities",0L);n.put("patentResources",rs.getLong("patents"));return n;
      });
  }
  void write(String collection,String id,ObjectNode data,String status,Long previousVersion,String actor,String operation) {
    ObjectNode payload=data.deepCopy();
    payload.remove(List.of("id","version","sortOrder","publicationStatus","publishedAt","updatedAt","views"));
    if(collection.equals("resources"))payload.remove(List.of("resourceType","kind","status","category"));
    if(collection.equals("featured"))payload.remove("resourceId");
    Map<String,Object> p=new HashMap<>();
    p.put("id",id);p.put("payload",payload.toString());p.put("status",status);p.put("order",data.path("sortOrder").asInt());
    p.put("version",previousVersion);p.put("type",data.path("resourceType").asText());p.put("kind",data.path("kind").asText());
    p.put("business",data.path("status").asText());p.put("resource",data.path("resourceId").asText());
    String extraColumns="",extraValues="",extraUpdate="";
    if(collection.equals("resources")) {extraColumns=",resource_type,kind,business_status";extraValues=",:type,:kind,:business";extraUpdate=",resource_type=:type,kind=:kind,business_status=:business";}
    if(collection.equals("featured")) {extraColumns=",resource_id";extraValues=",:resource";extraUpdate=",resource_id=:resource";}
    if(previousVersion==null) {
      sql.update("INSERT INTO "+table(collection)+" (id,payload,publication_status,sort_order,version,published_at,updated_at"+extraColumns+") VALUES (:id,:payload,:status,:order,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)"+extraValues+")",p);
    } else {
      int changed=sql.update("UPDATE "+table(collection)+" SET published_at=CASE WHEN publication_status='DRAFT' AND :status='PUBLISHED' THEN UTC_TIMESTAMP(6) ELSE published_at END,payload=:payload,publication_status=:status,sort_order=:order,version=version+1,updated_at=UTC_TIMESTAMP(6)"+extraUpdate+" WHERE id=:id AND version=:version",p);
      if(changed!=1)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"内容已在另一窗口修改，请保留输入并重新加载");
    }
    if(collection.equals("resources")) {
      var before=views(id);Map<String,String> legacy=new HashMap<>();
      before.forEach(v->legacy.put(v.get("audience").toString(),v.get("legacyId").toString()));
      sql.update("DELETE FROM content_resource_view WHERE resource_id=:id",Map.of("id",id));
      for(var v:data.path("views")) {
        String audience=v.path("audience").asText();
        sql.update("INSERT INTO content_resource_view(resource_id,audience,category,sort_order,legacy_id) VALUES(:id,:audience,:category,:ord,:legacy)",
          Map.of("id",id,"audience",audience,"category",v.path("category").asText(),"ord",v.path("sortOrder").asInt(),"legacy",legacy.getOrDefault(audience,id)));
      }
    }
    Map<String,Object> audit=new HashMap<>();
    audit.put("actor",actor);audit.put("op",operation);audit.put("kind",collection);audit.put("id",id);
    audit.put("before",previousVersion);audit.put("after",previousVersion==null?1:previousVersion+1);
    var fields=json.createArrayNode();data.fieldNames().forEachRemaining(fields::add);audit.put("fields",fields.toString());
    sql.update("INSERT INTO content_audit(actor,operation,entity_type,entity_id,before_version,after_version,changed_fields,created_at) VALUES(:actor,:op,:kind,:id,:before,:after,:fields,UTC_TIMESTAMP(6))",audit);
  }
}
