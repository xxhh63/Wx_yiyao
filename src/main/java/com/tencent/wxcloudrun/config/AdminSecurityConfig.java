package com.tencent.wxcloudrun.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import java.io.IOException;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class AdminSecurityConfig {
  @Bean
  SecurityFilterChain security(HttpSecurity http, ObjectMapper json,
      @Value("${app.mode:mini}") String mode) throws Exception {
    if (!mode.equals("mini") && !mode.equals("admin")) throw new IllegalStateException("APP_MODE must be mini or admin");
    http.requestCache(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .exceptionHandling(errors -> errors
            .authenticationEntryPoint((request, response, exception) -> {
              int code = mode.equals("mini") && request.getServletPath().startsWith("/api/") ? 404 : 401;
              write(json, response, code, ApiResponse.error(code, code == 404 ? "接口不存在" : "请先登录管理员账号"));
            })
            .accessDeniedHandler((request, response, exception) -> write(json, response, 403, ApiResponse.error(403, "无权访问或 CSRF 校验失败"))));
    if (mode.equals("mini")) {
      http.csrf(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
          .logout(AbstractHttpConfigurer::disable)
          .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .authorizeHttpRequests(auth -> auth.requestMatchers("/admin", "/admin/**").denyAll()
              .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
              .requestMatchers(HttpMethod.POST, "/api/auth/session").permitAll()
              .requestMatchers(HttpMethod.DELETE, "/api/auth/session").permitAll()
              .requestMatchers(HttpMethod.PUT, "/api/me/card", "/api/me/identity").permitAll()
              .requestMatchers("/api/**").denyAll().anyRequest().permitAll());
    } else {
      http.csrf(Customizer.withDefaults())
          .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
              .sessionFixation(fixation -> fixation.changeSessionId()))
          .authorizeHttpRequests(auth -> auth
              .requestMatchers(HttpMethod.GET, "/api/health", "/api/ready").permitAll()
              .requestMatchers(HttpMethod.GET, "/assets/phase1/service-lab.png", "/assets/phase1/service-equipment.png", "/assets/phase1/service-material.png").permitAll()
              .requestMatchers(HttpMethod.GET, "/admin/api/session").permitAll()
              .requestMatchers(HttpMethod.POST, "/admin/api/session").permitAll()
              .requestMatchers(HttpMethod.DELETE, "/admin/api/session").authenticated()
              .requestMatchers("/admin/api/**").hasRole("ADMIN")
              .requestMatchers(HttpMethod.GET, "/admin", "/admin/**").permitAll()
              .anyRequest().denyAll())
          .formLogin(login -> login.loginProcessingUrl("/admin/api/session")
              .successHandler((request, response, auth) -> write(json, response, 200,
                  ApiResponse.ok(Map.of("authenticated", true, "username", auth.getName()))))
              .failureHandler((request, response, exception) -> {
                int status = exception instanceof LockedException ? 429 : exception instanceof AuthenticationServiceException ? 503 : 401;
                if (status == 429) response.setHeader("Retry-After", "900");
                write(json, response, status, ApiResponse.error(status,
                    status == 429 ? "尝试过于频繁，请稍后重试" : status == 503 ? "认证服务暂不可用" : "账号或密码错误"));
              }))
          .logout(logout -> logout.logoutRequestMatcher(request -> request.getMethod().equals("DELETE")
                  && request.getServletPath().equals("/admin/api/session"))
              .invalidateHttpSession(true).clearAuthentication(true)
              .logoutSuccessHandler((request, response, auth) -> write(json, response, 200, ApiResponse.ok(Map.of("authenticated", false)))));
    }
    return http.build();
  }

  private static void write(ObjectMapper json, HttpServletResponse response, int status, ApiResponse body) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-store");
    json.writeValue(response.getWriter(), body);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "app.mode", havingValue = "admin")
  @EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 1800)
  static class AdminSessions {
    @Bean
    CookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
      var serializer = new DefaultCookieSerializer();
      serializer.setCookieName("SESSION");
      serializer.setCookiePath("/");
      serializer.setUseHttpOnlyCookie(true);
      serializer.setUseSecureCookie(secure);
      serializer.setSameSite("Lax");
      return serializer;
    }
  }
}