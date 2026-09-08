package com.tencent.wxcloudrun.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.wxcloudrun.config.WechatIdentityResolver.Identity;
import com.tencent.wxcloudrun.dao.ProfileMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class ProfileService {
  private static final Map<String, Integer> LIMITS = Map.of(
      "name", 30, "phone", 24, "company", 80, "position", 50,
      "address", 120, "email", 100, "wechat", 50, "intro", 500);
  private final ProfileMapper mapper;

  public ProfileService(ProfileMapper mapper) { this.mapper = mapper; }

  @Transactional
  public Session session(Identity identity) {
    var user = ensureUser(identity);
    return new Session(true, user.id(), user.createdAt().toInstant(ZoneOffset.UTC));
  }

  public CardSnapshot card(Identity identity) {
    var row = mapper.findCard(identity.appid(), identity.openid());
    if (row == null) {
      var empty = new LinkedHashMap<String, String>();
      LIMITS.keySet().forEach(key -> empty.put(key, ""));
      return new CardSnapshot(1, empty, null);
    }
    return new CardSnapshot(1, Map.of(
        "name", row.name(), "phone", row.phone(), "company", row.company(), "position", row.position(),
        "address", row.address(), "email", row.email(), "wechat", row.wechat(), "intro", row.intro()),
        row.savedAt().toInstant(ZoneOffset.UTC));
  }

  @Transactional
  public CardSnapshot saveCard(Identity identity, JsonNode body) {
    Map<String, String> values = validate(body);
    var user = ensureUser(identity);
    mapper.saveCard(user.id(), values);
    return card(identity);
  }

  private ProfileMapper.UserRow ensureUser(Identity identity) {
    mapper.ensureUser(UUID.randomUUID().toString(), identity.appid(), identity.openid());
    return mapper.findUser(identity.appid(), identity.openid());
  }

  private Map<String, String> validate(JsonNode body) {
    if (body == null || !body.isObject() || body.size() != 1
        || !body.has("values") || !body.get("values").isObject()) {
      throw invalid("请求格式应为包含 values 对象的 JSON");
    }
    var input = body.get("values");
    input.fieldNames().forEachRemaining(key -> {
      if (!LIMITS.containsKey(key)) throw invalid("包含不支持的名片字段");
    });
    var values = new LinkedHashMap<String, String>();
    LIMITS.forEach((key, max) -> {
      JsonNode node = input.get(key);
      if (node != null && !node.isTextual()) throw invalid(key + " 必须是字符串");
      String value = node == null ? "" : node.textValue().strip();
      if (value.codePointCount(0, value.length()) > max) throw invalid(key + " 超出最大长度 " + max);
      values.put(key, value);
    });
    String phone = values.get("phone");
    if (!phone.isEmpty() && (!phone.matches("[+()0-9\\s-]+")
        || phone.chars().filter(c -> c >= '0' && c <= '9').count() < 6)) {
      throw invalid("请检查联系电话格式");
    }
    String email = values.get("email");
    if (!email.isEmpty() && !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
      throw invalid("请检查邮箱格式");
    }
    return values;
  }

  private ResponseStatusException invalid(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  public record Session(boolean authenticated, String userId, Instant createdAt) {}
  public record CardSnapshot(int version, Map<String, String> values, Instant savedAt) {}
}
