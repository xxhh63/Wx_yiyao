package com.tencent.wxcloudrun.controller;

import com.tencent.wxcloudrun.config.ApiResponse;
import com.tencent.wxcloudrun.dao.ProfileMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {
  private final ProfileMapper mapper;

  public HealthController(ProfileMapper mapper) { this.mapper = mapper; }

  @GetMapping("/api/health")
  public ApiResponse health() { return ApiResponse.ok(Map.of("status", "UP", "service", "yiyao-backend")); }

  @GetMapping("/api/ready")
  public ApiResponse ready() {
    mapper.ping();
    return ApiResponse.ok(Map.of("status", "UP", "database", "UP"));
  }
}