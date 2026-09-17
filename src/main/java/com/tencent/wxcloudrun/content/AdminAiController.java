package com.tencent.wxcloudrun.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.wxcloudrun.config.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/admin/api/ai")
@ConditionalOnProperty(name="app.mode",havingValue="admin")
public class AdminAiController {
  private final AdminAiService service;
  private final ObjectMapper json;
  public AdminAiController(AdminAiService service,ObjectMapper json) {this.service=service;this.json=json;}
  @ModelAttribute void cache(HttpServletResponse response) {response.setHeader("Cache-Control","no-store");}
  @GetMapping("/status") public ApiResponse status() {return ApiResponse.ok(service.status());}
  @PostMapping(value="/draft",consumes=MediaType.APPLICATION_JSON_VALUE)
  public ApiResponse text(@RequestBody JsonNode request) {return ApiResponse.ok(service.draft(request,null));}
  @PostMapping(value="/draft",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse file(@RequestPart("file") MultipartFile file,@RequestParam("request") String request) {
    if(request.length()>256*1024)throw ContentValidation.bad("录入说明过长");
    JsonNode input;
    try {input=json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(request);}
    catch(java.io.IOException error) {throw ContentValidation.bad("录入参数格式不正确");}
    return ApiResponse.ok(service.draft(input,file));
  }
}
