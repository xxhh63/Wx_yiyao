package com.tencent.wxcloudrun.content;
import com.tencent.wxcloudrun.config.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.security.Principal;
import java.util.Map;
@RestController
@RequestMapping("/admin/api/media")
@ConditionalOnProperty(name="app.mode",havingValue="admin")
public class MediaController {
  private final MediaService media;
  public MediaController(MediaService media){this.media=media;}
  @GetMapping public ApiResponse list(@RequestParam Map<String,String> query){return ApiResponse.ok(media.list(query));}
  @PostMapping(consumes="multipart/form-data") public ApiResponse upload(@RequestPart("file") MultipartFile file,Principal actor){return ApiResponse.ok(media.upload(file,actor.getName()));}
}
