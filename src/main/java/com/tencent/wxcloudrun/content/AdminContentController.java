package com.tencent.wxcloudrun.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.wxcloudrun.config.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/admin/api")
@ConditionalOnProperty(name="app.mode",havingValue="admin")
public class AdminContentController {
  private final ContentService service;
  public AdminContentController(ContentService service){this.service=service;}
  @ModelAttribute void cache(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
  @GetMapping("/stats") public ApiResponse stats(){return ApiResponse.ok(service.stats());}
  @GetMapping("/audit") public ApiResponse audit(@RequestParam Map<String,String> query){return ApiResponse.ok(service.audit(query));}
  @GetMapping("/catalogs/{audience}") public ApiResponse catalog(@PathVariable String audience){return ApiResponse.ok(service.catalog(audience));}
  @GetMapping("/{collection:resources|featured|promos|policies|institutions}") public ApiResponse list(@PathVariable String collection,@RequestParam Map<String,String> query){return ApiResponse.ok(service.list(collection,query,true));}
  @GetMapping("/{collection:resources|featured|promos|policies|institutions}/{id}") public ApiResponse detail(@PathVariable String collection,@PathVariable String id){return ApiResponse.ok(service.detail(collection,id,null,true));}
  @PostMapping("/{collection:resources|featured|promos|policies|institutions}") public ApiResponse create(@PathVariable String collection,@RequestBody JsonNode body,Principal actor){return ApiResponse.ok(service.save(collection,null,body,actor.getName()));}
  @PutMapping("/{collection:resources|featured|promos|policies|institutions}/{id}") public ApiResponse save(@PathVariable String collection,@PathVariable String id,@RequestBody JsonNode body,Principal actor){return ApiResponse.ok(service.save(collection,id,body,actor.getName()));}
  @PutMapping("/{collection:resources|featured|promos|policies|institutions}/{id}/publication") public ApiResponse publish(@PathVariable String collection,@PathVariable String id,@RequestBody JsonNode body,Principal actor){return ApiResponse.ok(service.publication(collection,id,body,actor.getName()));}
}
