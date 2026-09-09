package com.tencent.wxcloudrun.content;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.wxcloudrun.config.ApiResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+10)
public class ContentRequestLimit extends OncePerRequestFilter {
  private final ObjectMapper json;
  public ContentRequestLimit(ObjectMapper json){this.json=json;}
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
    String type=request.getContentType();
    if(type==null||!type.toLowerCase(Locale.ROOT).contains("json")||!(request.getRequestURI().startsWith("/api/")||request.getRequestURI().startsWith("/admin/api/"))){chain.doFilter(request,response);return;}
    byte[] body=request.getInputStream().readNBytes(256*1024+1);
    if(body.length>256*1024){response.setStatus(413);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");json.writeValue(response.getWriter(),ApiResponse.error(413,"内容不能超过256KB"));return;}
    chain.doFilter(new HttpServletRequestWrapper(request){
      @Override public ServletInputStream getInputStream(){
        ByteArrayInputStream input=new ByteArrayInputStream(body);
        return new ServletInputStream(){
          public int read(){return input.read();}
          public int read(byte[] b,int o,int n){return input.read(b,o,n);}
          public boolean isFinished(){return input.available()==0;}
          public boolean isReady(){return true;}
          public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous content endpoint");}
        };
      }
      @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
    },response);
  }
}
