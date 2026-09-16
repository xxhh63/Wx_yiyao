package com.tencent.wxcloudrun.config;

import com.tencent.wxcloudrun.dao.ProfileMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.Collections;

@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "mini", matchIfMissing = true)
public class WechatIdentityResolver {
  public static final String SESSION_APPID = "wechat.appid";
  public static final String SESSION_OPENID = "wechat.openid";
  private final String appid;
  private final boolean trustedIngress;
  private final boolean serverMode;
  private final ProfileMapper profiles;

  public WechatIdentityResolver(@Value("${app.wechat.appid:}") String appid,
      @Value("${app.wechat.trusted-ingress:false}") boolean trustedIngress,
      @Value("${app.wechat.auth-mode:cloud}") String mode, ProfileMapper profiles) {
    if (!mode.equals("cloud") && !mode.equals("server")) {
      throw new IllegalStateException("WECHAT_AUTH_MODE must be cloud or server");
    }
    if ((trustedIngress || mode.equals("server")) && !appid.matches("wx[0-9a-fA-F]{16}")) {
      throw new IllegalStateException("启用微信身份识别前必须配置合法 WECHAT_APPID");
    }
    this.appid = appid;
    this.trustedIngress = trustedIngress;
    this.serverMode = mode.equals("server");
    this.profiles = profiles;
  }

  public Identity resolve(HttpServletRequest request) {
    if (serverMode) {
      var session = currentServerSession(request);
      if (session == null) throw unauthorized();
      String openid = (String) session.getAttribute(SESSION_OPENID);
      if (profiles.findPhone(appid, openid) == null) {
        throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "请授权手机号后登录");
      }
      return new Identity(appid, openid);
    }
    // Header values are not signatures. Enable only after all untrusted ingress is closed.
    if (!trustedIngress) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信身份入口尚未启用");
    }
    String source = singleHeader(request, "X-WX-SOURCE");
    String requestAppid = singleHeader(request, "X-WX-APPID");
    String openid = singleHeader(request, "X-WX-OPENID");
    if (source == null || source.isBlank() || source.length() > 256
        || !appid.equals(requestAppid) || !validOpenid(openid)) {
      throw unauthorized();
    }
    return new Identity(appid, openid);
  }

  public HttpSession currentServerSession(HttpServletRequest request) {
    if (!serverMode) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "接口不存在");
    var tokens = Collections.list(request.getHeaders("X-Auth-Token"));
    if (tokens.isEmpty()) return null;
    if (tokens.size() != 1 || !tokens.getFirst().matches("[A-Za-z0-9_-]{16,128}")) throw unauthorized();
    var session = request.getSession(false);
    if (session != null && (!appid.equals(session.getAttribute(SESSION_APPID))
        || !(session.getAttribute(SESSION_OPENID) instanceof String openid) || !validOpenid(openid))) {
      throw unauthorized();
    }
    return session;
  }

  private boolean validOpenid(String openid) {
    return openid != null && openid.matches("[A-Za-z0-9_-]{1,128}");
  }

  private ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请重新登录小程序");
  }

  private String singleHeader(HttpServletRequest request, String name) {
    var values = Collections.list(request.getHeaders(name));
    return values.size() == 1 ? values.getFirst() : null;
  }

  public record Identity(String appid, String openid) {}
}
