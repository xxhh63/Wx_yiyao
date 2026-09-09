package com.tencent.wxcloudrun;
import com.tencent.wxcloudrun.content.MediaService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;
class MediaValidationTest {
  @Test void acceptsActualImageAndRejectsHtmlEmptyAndOversizedPayloads()throws Exception {
    var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(7,9,BufferedImage.TYPE_INT_RGB),"png",output);
    var info=MediaService.validateImage(output.toByteArray());
    assertEquals("image/png",info.mime());assertEquals(7,info.width());assertEquals(9,info.height());
    assertThrows(ResponseStatusException.class,()->MediaService.validateImage("<svg onload='alert(1)'></svg>".getBytes()));
    assertThrows(ResponseStatusException.class,()->MediaService.validateImage(new byte[0]));
    assertEquals(413,assertThrows(ResponseStatusException.class,()->MediaService.validateImage(new byte[5*1024*1024+1])).getStatusCode().value());
  }
}
