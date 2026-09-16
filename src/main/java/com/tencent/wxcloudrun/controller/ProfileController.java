package com.tencent.wxcloudrun.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.wxcloudrun.config.ApiResponse;
import com.tencent.wxcloudrun.config.WechatIdentityResolver;
import com.tencent.wxcloudrun.service.ProfileService;
import com.tencent.wxcloudrun.service.WechatLoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name = "app.mode", havingValue = "mini", matchIfMissing = true)
public class ProfileController {
  private final WechatIdentityResolver identity;
  private final ProfileService profiles;
  private final ObjectProvider<WechatLoginService> login;

  public ProfileController(WechatIdentityResolver identity, ProfileService profiles,
      ObjectProvider<WechatLoginService> login) {
    this.identity = identity;
    this.profiles = profiles;
    this.login = login;
  }

  @ModelAttribute
  public void preventCaching(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
  }

  @PostMapping("/api/auth/session")
  public ApiResponse session(HttpServletRequest request, @RequestBody(required = false) JsonNode body) {
    var serverLogin = login.getIfAvailable();
    return ApiResponse.ok(serverLogin == null ? profiles.session(identity.resolve(request)) : serverLogin.login(request, body));
  }

  @DeleteMapping("/api/auth/session")
  public ApiResponse logout(HttpServletRequest request) {
    var serverLogin = login.getIfAvailable();
    if (serverLogin == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "接口不存在");
    serverLogin.logout(request);
    return ApiResponse.ok(Map.of("authenticated", false));
  }

  @GetMapping("/api/me/card")
  public ApiResponse card(HttpServletRequest request) {
    return ApiResponse.ok(profiles.card(identity.resolve(request)));
  }

  @PutMapping("/api/me/card")
  public ApiResponse saveCard(HttpServletRequest request, @RequestBody JsonNode body) {
    return ApiResponse.ok(profiles.saveCard(identity.resolve(request), body));
  }
}
