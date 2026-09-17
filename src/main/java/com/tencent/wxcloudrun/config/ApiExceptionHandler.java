package com.tencent.wxcloudrun.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiResponse> status(ResponseStatusException error) {
    return ResponseEntity.status(error.getStatusCode())
        .body(ApiResponse.error(error.getStatusCode().value(), error.getReason()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiResponse> invalidJson() {
    return ResponseEntity.badRequest().body(ApiResponse.error(400, "请求 JSON 格式不正确"));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiResponse> notFound() {
    return ResponseEntity.status(404).body(ApiResponse.error(404, "接口不存在"));
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiResponse> uploadTooLarge() {
    return ResponseEntity.status(413).body(ApiResponse.error(413, "文件不能超过 5MB"));
  }

  @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
  public ResponseEntity<ApiResponse> missingFile() {
    return ResponseEntity.badRequest().body(ApiResponse.error(400, "请选择要上传的文件"));
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiResponse> unsupportedMethod() {
    return ResponseEntity.status(405).body(ApiResponse.error(405, "请求方法不支持"));
  }

  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<ApiResponse> database(DataAccessException error) {
    // Do not log SQL bindings, connection URLs, credentials, or card contents.
    LOG.error("Database request failed: {}", error.getClass().getSimpleName());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ApiResponse.error(503, "数据服务暂不可用，请稍后重试"));
  }
}
