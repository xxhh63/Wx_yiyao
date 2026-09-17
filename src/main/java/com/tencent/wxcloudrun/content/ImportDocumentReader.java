package com.tencent.wxcloudrun.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

@Component
public class ImportDocumentReader {
  static final int MAX_FILE = 5 * 1024 * 1024;
  static final int MAX_RESULT = 7 * 1024 * 1024;
  static final Set<String> EXTENSIONS = Set.of("pdf", "doc", "docx", "txt", "md", "csv", "rtf", "xlsx", "xls", "pptx", "ppt", "jpg", "jpeg", "png");
  public record Document(String text, List<String> images, List<String> warnings) {}

  public Document read(MultipartFile file) {
    if (file == null || file.isEmpty()) throw ContentValidation.bad("请选择需要识别的文档");
    if (file.getSize() > MAX_FILE) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文档不能超过5MB");
    String name = Objects.toString(file.getOriginalFilename(), "");
    String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    if (!EXTENSIONS.contains(extension)) throw ContentValidation.bad("支持PDF、Word、TXT、MD、CSV、RTF、Excel、PowerPoint及JPG/PNG图片");
    Path directory = null;
    Process worker = null;
    try {
      directory = Files.createTempDirectory("yiyao-import-");
      Path input = directory.resolve("input." + extension), output = directory.resolve("result.json");
      try (var source = file.getInputStream(); var target = Files.newOutputStream(input)) {
        byte[] buffer = new byte[8192]; int total = 0, count;
        while ((count = source.read(buffer)) != -1) {
          total += count;
          if (total > MAX_FILE) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文档不能超过5MB");
          target.write(buffer, 0, count);
        }
      }
      Path arguments = directory.resolve("java.args");
      List<String> args = new ArrayList<>(List.of("-Xmx96m", "-XX:MaxMetaspaceSize=96m", "-XX:MaxDirectMemorySize=16m", "-XX:+ExitOnOutOfMemoryError", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=" + directory, "-Duser.home=" + directory));
      String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
      boolean executableJar = false;
      if (!classpath.contains(File.pathSeparator) && classpath.endsWith(".jar")) {
        try (var jar = new JarFile(classpath)) { executableJar = jar.getEntry("org/springframework/boot/loader/launch/PropertiesLauncher.class") != null; }
      }
      String main = ImportDocumentWorker.class.getName();
      if (executableJar) args.add("-Dloader.main=" + main);
      args.add("-cp"); args.add(classpath);
      args.add(executableJar ? "org.springframework.boot.loader.launch.PropertiesLauncher" : main);
      args.add(input.toString()); args.add(extension); args.add(output.toString());
      Files.write(arguments, args.stream().map(ImportDocumentReader::quoteArgument).toList(), StandardCharsets.UTF_8);
      String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
      var builder = new ProcessBuilder(java, "@" + arguments).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
      // The parser needs no application credentials or Java-agent options inherited from the web process.
      var environment = builder.environment();
      environment.keySet().removeIf(key -> !Set.of("SYSTEMROOT", "WINDIR", "PATH", "TMP", "TEMP", "LANG", "LC_ALL").contains(key.toUpperCase(Locale.ROOT)));
      worker = builder.start();
      if (!finish(worker, Duration.ofSeconds(25))) throw ContentValidation.bad("文档解析超过25秒，请拆分或转换后重试");
      if (worker.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) > MAX_RESULT) throw ContentValidation.bad("文档解析失败或超出资源限制，请转换为较小的PDF/图片后重试");
      var json = new ObjectMapper();
      var result = json.readTree(output.toFile());
      if (result.has("error")) throw ContentValidation.bad(result.path("error").asText());
      var document = json.treeToValue(result, Document.class);
      if (document.text() == null || document.images() == null || document.warnings() == null || document.text().length() > 60_000 || document.images().size() > 6) throw ContentValidation.bad("文档解析结果超出限制");
      return document;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt(); throw ContentValidation.bad("文档解析已取消");
    } catch (IOException error) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文档解析进程无法启动或读取，请联系管理员检查Java运行环境");
    } finally {
      if (worker != null && worker.isAlive()) {
        worker.destroyForcibly();
        try { worker.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
      }
      if (directory != null) {
        try (var files = Files.walk(directory)) {
          for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException error) {
          org.slf4j.LoggerFactory.getLogger(ImportDocumentReader.class).warn("Document import temporary files could not be removed");
        }
      }
    }
  }

  static boolean finish(Process process, Duration timeout) throws InterruptedException {
    if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) return true;
    process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); return false;
  }
  private static String quoteArgument(String argument) {
    if (argument.indexOf('\n') >= 0 || argument.indexOf('\r') >= 0) throw ContentValidation.bad("Java运行路径无效");
    return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}