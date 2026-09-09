package com.tencent.wxcloudrun.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.wxcloudrun.config.ApiResponse;
import com.tencent.wxcloudrun.config.WechatIdentityResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.tencent.wxcloudrun.service.ProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "app.mode", havingValue = "mini", matchIfMissing = true)
public class ProfileController {
  private final WechatIdentityResolver identity;
  private final ProfileService profiles;

  public ProfileController(WechatIdentityResolver identity, ProfileService profiles) {
    this.identity = identity;
    this.profiles = profiles;

  }

  @ModelAttribute
  public void preventCaching(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
  }

  @PostMapping("/api/auth/session")
  public ApiResponse session(HttpServletRequest request) {
    return ApiResponse.ok(profiles.session(identity.resolve(request)));
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
