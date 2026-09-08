package com.tencent.wxcloudrun.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;

@Component
public class WechatIdentityResolver {
  private final String appid;
  private final boolean trustedIngress;

  public WechatIdentityResolver(@Value("${app.wechat.appid:}") String appid,
      @Value("${app.wechat.trusted-ingress:false}") boolean trustedIngress) {
    if (trustedIngress && !appid.matches("wx[0-9a-fA-F]{16}")) {
      throw new IllegalStateException("启用微信身份识别前必须配置合法 WECHAT_APPID");
    }
    this.appid = appid;
    this.trustedIngress = trustedIngress;
  }

  public Identity resolve(HttpServletRequest request) {
    // Header values are not signatures. Enable only after all untrusted ingress is closed.
    if (!trustedIngress) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信身份入口尚未启用");
    }
    String source = singleHeader(request, "X-WX-SOURCE");
    String requestAppid = singleHeader(request, "X-WX-APPID");
    String openid = singleHeader(request, "X-WX-OPENID");
    if (source == null || source.isBlank() || source.length() > 256
        || !appid.equals(requestAppid) || openid == null || !openid.matches("[A-Za-z0-9_-]{1,128}")) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请通过已授权的小程序访问");
    }
    return new Identity(appid, openid);
  }

  private String singleHeader(HttpServletRequest request, String name) {
    var values = Collections.list(request.getHeaders(name));
    return values.size() == 1 ? values.getFirst() : null;
  }

  public record Identity(String appid, String openid) {}
}
