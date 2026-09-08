package com.tencent.wxcloudrun.config;

import lombok.Data;
import java.util.Map;

@Data
public final class ApiResponse {
  private final Integer code;
  private final String errorMsg;
  private final Object data;

  private ApiResponse(int code, String errorMsg, Object data) {
    this.code = code;
    this.errorMsg = errorMsg;
    this.data = data;
  }

  public static ApiResponse ok() { return ok(Map.of()); }
  public static ApiResponse ok(Object data) { return new ApiResponse(0, "", data); }
  public static ApiResponse error(String message) { return error(400, message); }
  public static ApiResponse error(int code, String message) { return new ApiResponse(code, message, Map.of()); }
}
