package com.tencent.wxcloudrun.controller;

import com.tencent.wxcloudrun.config.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;

@RestController
@ConditionalOnProperty(name = "app.mode", havingValue = "admin")
public class AdminSessionController {
  @GetMapping({"/admin", "/admin/"})
  public ResponseEntity<Void> index() {
    return ResponseEntity.status(302).header("Location", "/admin/index.html").build();
  }

  @GetMapping("/admin/api/session")
  public ApiResponse session(Authentication authentication, CsrfToken csrf) {
    boolean loggedIn = authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken);
    var data = new LinkedHashMap<String, Object>();
    data.put("authenticated", loggedIn);
    if (loggedIn) data.put("username", authentication.getName());
    data.put("csrfToken", csrf.getToken());
    data.put("csrfHeaderName", csrf.getHeaderName());
    return ApiResponse.ok(data);
  }
}