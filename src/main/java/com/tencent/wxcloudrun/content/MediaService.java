package com.tencent.wxcloudrun.content;
import com.qcloud.cos.*;
import com.qcloud.cos.auth.*;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.*;
import com.qcloud.cos.region.Region;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;
import java.net.URI;
import java.util.*;

@Service
@ConditionalOnProperty(name="app.mode",havingValue="admin")
public class MediaService {
  private final NamedParameterJdbcTemplate sql;
  private final String secretId,secretKey,token,bucket,region,publicBase;
  public MediaService(NamedParameterJdbcTemplate sql,
    @Value("$"+"{COS_SECRET_ID:}") String secretId,@Value("$"+"{COS_SECRET_KEY:}") String secretKey,
    @Value("$"+"{COS_SESSION_TOKEN:}") String token,@Value("$"+"{COS_BUCKET:}") String bucket,
    @Value("$"+"{COS_REGION:}") String region,@Value("$"+"{COS_PUBLIC_BASE_URL:}") String publicBase){
    this.sql=sql;this.secretId=secretId;this.secretKey=secretKey;this.token=token;this.bucket=bucket;this.region=region;this.publicBase=publicBase;
  }
  public record ImageInfo(byte[] bytes,String mime,int width,int height,String extension) {}
  public static ImageInfo validateImage(byte[] bytes) {
    if(bytes.length==0||bytes.length>5*1024*1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"图片不能为空且不能超过5MB");
    try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers=ImageIO.getImageReaders(input);
      if(!readers.hasNext())throw ContentValidation.bad("只支持真实的PNG或JPEG图片");
      var reader=readers.next();
      try{
        String format=reader.getFormatName().toLowerCase(Locale.ROOT);
        if(!Set.of("png","jpeg","jpg").contains(format))throw ContentValidation.bad("只支持PNG或JPEG图片");
        reader.setInput(input,true,true);
        int width=reader.getWidth(0),height=reader.getHeight(0);
        if(width<1||height<1||width>8192||height>8192||(long)width*height>20000000)throw ContentValidation.bad("图片尺寸过大，请压缩至2000万像素以内");
        if(reader.read(0)==null)throw ContentValidation.bad("图片文件不完整");
        return new ImageInfo(bytes,format.equals("png")?"image/png":"image/jpeg",width,height,format.equals("png")?"png":"jpg");
      } finally{reader.dispose();}
    } catch(IOException e){throw ContentValidation.bad("图片损坏或格式不受支持");}
  }
  public Map<String,Object> upload(MultipartFile file,String actor) {
    if(file==null||file.isEmpty()||file.getSize()>5*1024*1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"请选择5MB以内的PNG/JPEG图片");
    ImageInfo image;
    try{image=validateImage(file.getBytes());}catch(IOException e){throw ContentValidation.bad("读取图片失败");}
    if(secretId.isBlank()||secretKey.isBlank()||!bucket.matches("[a-z0-9][a-z0-9-]{2,62}-[0-9]+")||!region.matches("[a-z]+-[a-z]+(-[a-z]+)?"))
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"图片存储尚未配置，请在管理服务配置COS账号、桶和地域");
    String id=UUID.randomUUID().toString(),key="yiyao/content/"+id+"."+image.extension();
    String base=publicBase.isBlank()?"https://"+bucket+".cos."+region+".myqcloud.com":publicBase;
    try{var u=URI.create(base);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null)throw new IllegalArgumentException();}
    catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"图片展示域名配置无效");}
    String url=base.replaceAll("/+$","")+"/"+key;
    COSCredentials credentials=token.isBlank()?new BasicCOSCredentials(secretId,secretKey):new BasicSessionCredentials(secretId,secretKey,token);
    ClientConfig config=new ClientConfig(new Region(region));config.setHttpProtocol(HttpProtocol.https);config.setConnectionTimeout(5000);config.setSocketTimeout(15000);
    COSClient client=new COSClient(credentials,config);boolean uploaded=false;
    try{
      ObjectMetadata meta=new ObjectMetadata();meta.setContentLength(image.bytes().length);meta.setContentType(image.mime());meta.setCacheControl("public,max-age=31536000,immutable");
      var request=new PutObjectRequest(bucket,key,new ByteArrayInputStream(image.bytes()),meta);
      request.setCannedAcl(CannedAccessControlList.PublicRead);
      client.putObject(request);uploaded=true;
      sql.update("INSERT INTO content_media(id,object_key,mime_type,byte_size,width,height,public_url,created_at,created_by) VALUES(:id,:key,:mime,:size,:width,:height,:url,UTC_TIMESTAMP(6),:actor)",
        Map.of("id",id,"key",key,"mime",image.mime(),"size",image.bytes().length,"width",image.width(),"height",image.height(),"url",url,"actor",actor));
      return Map.of("id",id,"url",url,"mimeType",image.mime(),"width",image.width(),"height",image.height());
    } catch(RuntimeException error){
      if(uploaded)try{client.deleteObject(bucket,key);}catch(RuntimeException cleanup){org.slf4j.LoggerFactory.getLogger(MediaService.class).warn("Unrecorded uploaded media requires cleanup: {}",key);}
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"图片未能保存，请检查存储权限后重试");
    } finally{client.shutdown();}
  }
  public Map<String,Object> list(Map<String,String> query){
    int page=ContentService.page(query,"page",1,1000000),size=ContentService.page(query,"pageSize",20,50);
    for(String key:query.keySet())if(!Set.of("page","pageSize").contains(key))throw ContentValidation.bad("未知分页参数");
    var items=sql.queryForList("SELECT id,public_url AS url,mime_type AS mimeType,width,height,byte_size AS byteSize,created_at AS createdAt FROM content_media ORDER BY created_at DESC,id LIMIT :size OFFSET :offset",Map.of("size",size,"offset",(page-1)*size));
    long total=sql.queryForObject("SELECT COUNT(*) FROM content_media",Map.of(),Long.class);
    return Map.of("items",items,"total",total,"page",page,"pageSize",size);
  }
}
