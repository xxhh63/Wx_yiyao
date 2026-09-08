package com.tencent.wxcloudrun.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.wxcloudrun.config.ApiResponse;
import com.tencent.wxcloudrun.config.WechatIdentityResolver;
import com.tencent.wxcloudrun.dao.ProfileMapper;
import com.tencent.wxcloudrun.service.ProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class ProfileController {
  private final WechatIdentityResolver identity;
  private final ProfileService profiles;
  private final ProfileMapper mapper;

  public ProfileController(WechatIdentityResolver identity, ProfileService profiles, ProfileMapper mapper) {
    this.identity = identity;
    this.profiles = profiles;
    this.mapper = mapper;
  }

  @ModelAttribute
  public void preventCaching(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
  }

  @GetMapping("/api/health")
  public ApiResponse health() { return ApiResponse.ok(Map.of("status", "UP", "service", "yiyao-backend")); }

  @GetMapping("/api/ready")
  public ApiResponse ready() {
    mapper.ping();
    return ApiResponse.ok(Map.of("status", "UP", "database", "UP"));
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
