package com.tencent.wxcloudrun.content;

import com.tencent.wxcloudrun.config.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name="app.mode",havingValue="mini",matchIfMissing=true)
public class ContentController {
  private final ContentService service;
  public ContentController(ContentService service){this.service=service;}
  @ModelAttribute void cache(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
  @GetMapping("/home") public ApiResponse home(){return ApiResponse.ok(service.home());}
  @GetMapping("/stats") public ApiResponse stats(){return ApiResponse.ok(service.stats());}
  @GetMapping("/catalogs/{audience}") public ApiResponse catalog(@PathVariable String audience){return ApiResponse.ok(service.catalog(audience));}
  @GetMapping("/{collection:resources|policies|institutions}") public ApiResponse list(@PathVariable String collection,@RequestParam Map<String,String> query){return ApiResponse.ok(service.list(collection,query,false));}
  @GetMapping("/{collection:resources|policies|institutions|promos}/{id}") public ApiResponse detail(@PathVariable String collection,@PathVariable String id,@RequestParam(required=false) String audience){return ApiResponse.ok(service.detail(collection,id,audience,false));}
}
