package com.tencent.wxcloudrun.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.HeaderHttpSessionIdResolver;
import org.springframework.session.web.http.HttpSessionIdResolver;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.mode", havingValue = "mini", matchIfMissing = true)
@ConditionalOnProperty(name = "app.wechat.auth-mode", havingValue = "server")
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 7200)
public class WechatSessionConfig {
  @Bean
  HttpSessionIdResolver httpSessionIdResolver() {
    return HeaderHttpSessionIdResolver.xAuthToken();
  }

  @Bean
  @ConditionalOnMissingBean(HttpClient.class)
  HttpClient wechatHttpClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }
}
