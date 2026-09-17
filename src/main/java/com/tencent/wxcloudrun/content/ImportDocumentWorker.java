package com.tencent.wxcloudrun.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.rendering.*;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.openxml4j.opc.*;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xssf.eventusermodel.*;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.xml.sax.InputSource;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.rtf.RTFEditorKit;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Runs without Spring, credentials, network fetching, macro execution or embedded-file recursion. */
public final class ImportDocumentWorker {
  private static final int MAX_TEXT = 60_000, MAX_IMAGES = 6, MAX_IMAGE_DATA = 6 * 1024 * 1024;
  private final StringBuilder text = new StringBuilder();
  private final List<String> images = new ArrayList<>();
  private final Set<String> warnings = new LinkedHashSet<>();
  private int imageCharacters;

  public static void main(String[] args) throws Exception {
    if (args.length != 3) System.exit(2);
    var worker = new ImportDocumentWorker();
    Object result;
    try {
      Path input = Path.of(args[0]);
      if (Files.size(input) > ImportDocumentReader.MAX_FILE) throw new IllegalArgumentException("文档不能超过5MB");
      worker.read(input, args[1]);
      if (worker.text.toString().isBlank() && worker.images.isEmpty()) worker.warnings.add("未识别到可填写内容，请人工补充或转换文档后重试");
      result = new ImportDocumentReader.Document(worker.text.toString(), List.copyOf(worker.images), List.copyOf(worker.warnings));
    } catch (IllegalArgumentException error) {
      result = Map.of("error", "文档格式或大小不符合要求，请检查文件、解压大小和图片编码后重试");
    } catch (Exception error) {
      result = Map.of("error", "文档无法读取，请检查是否加密、损坏，或转换成PDF/图片后重试");
    }
    new ObjectMapper().writeValue(Path.of(args[2]).toFile(), result);
  }

  private void read(Path input, String extension) throws Exception {
    if (Set.of("docx", "xlsx", "pptx").contains(extension)) checkArchive(input);
    switch (extension) {
      case "pdf" -> pdf(input);
      case "docx" -> { try (var doc = new XWPFDocument(OPCPackage.open(input.toFile(), PackageAccess.READ)); var extractor = new XWPFWordExtractor(doc)) {
        append(extractor.getText());
        for (var picture : doc.getAllPictures()) picture(picture.getData());
      } }
      case "doc" -> { try (var stream = Files.newInputStream(input); var doc = new HWPFDocument(stream); var extractor = new WordExtractor(doc)) {
        append(extractor.getText()); for (var picture : doc.getPicturesTable().getAllPictures()) picture(picture.getContent());
      } }
      case "xlsx" -> xlsx(input);
      case "xls" -> { try (var stream = Files.newInputStream(input); var workbook = new HSSFWorkbook(stream)) {
        var formatter = new DataFormatter(Locale.CHINA); formatter.setUseCachedValuesForFormulaCells(true);
        for (int i = 0; i < Math.min(10, workbook.getNumberOfSheets()) && text.length() < MAX_TEXT; i++) {
          append("\n[工作表 " + (i + 1) + "]\n"); int rows = 0, cells = 0;
          for (var row : workbook.getSheetAt(i)) { if (++rows > 2000 || cells > 20_000 || text.length() >= MAX_TEXT) {warnings.add("表格过长，仅提取前2000行/20000单元格及60000字符");break;}
            for (var cell : row) { if (++cells > 20_000) break; append(cell.getAddress() + ": " + formatter.formatCellValue(cell) + "\t"); } append("\n"); }
        }
        if (workbook.getNumberOfSheets() > 10) warnings.add("仅提取前10个工作表");
      } }
      case "pptx" -> { try (var slides = new XMLSlideShow(OPCPackage.open(input.toFile(), PackageAccess.READ))) {
        for (int i = 0; i < Math.min(20, slides.getSlides().size()); i++) {append("\n[幻灯片 " + (i + 1) + "]\n"); slideShapes(slides.getSlides().get(i).getShapes());}
        if (slides.getSlides().size() > 20) warnings.add("仅提取前20页幻灯片");
        warnings.add("幻灯片提取文字与内嵌图片，图表及组合图形请对照原文核对");
      } }
      case "ppt" -> { try (var stream = Files.newInputStream(input); var slides = new HSLFSlideShow(stream)) {
        for (int i = 0; i < Math.min(20, slides.getSlides().size()); i++) {append("\n[幻灯片 " + (i + 1) + "]\n");for (var shape : slides.getSlides().get(i).getShapes()) {
          if (shape instanceof HSLFTextShape value) append(value.getText() + "\n");
          if (shape instanceof HSLFPictureShape value && value.getPictureData() != null) picture(value.getPictureData().getData());
        }}
        if (slides.getSlides().size() > 20) warnings.add("仅提取前20页幻灯片");
        warnings.add("旧版PPT仅提取基础文字和图片，图表/组合图形请人工核对");
      } }
      case "rtf" -> { try (var stream = Files.newInputStream(input)) { var doc = new DefaultStyledDocument();new RTFEditorKit().read(stream,doc,0);append(doc.getText(0,doc.getLength()));warnings.add("RTF仅提取文字，内嵌图片请另传JPG/PNG"); } }
      case "jpg", "jpeg", "png" -> image(input);
      case "txt", "md", "csv" -> plainText(input);
      default -> throw new IllegalArgumentException("不支持的文件类型");
    }
  }

  private void checkArchive(Path input) throws IOException {
    ZipSecureFile.setMaxEntrySize(8L * 1024 * 1024); ZipSecureFile.setMaxFileCount(256); ZipSecureFile.setMaxTextSize(MAX_TEXT);
    // Keep POI's zip-bomb ratio check; count actual inflated bytes rather than trusting ZIP metadata.
    try (var zip = new ZipSecureFile(input.toFile())) {
      var entries = zip.getEntries(); int count = 0; long total = 0; byte[] buffer = new byte[8192];
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if (++count > 256 || entry.getName().contains("\\") || entry.getName().startsWith("/") || Arrays.asList(entry.getName().split("/")).contains("..")) throw new IllegalArgumentException("无效压缩包路径或条目过多");
        if (entry.isDirectory()) continue;
        try (var stream = zip.getInputStream(entry)) {int n;while ((n = stream.read(buffer)) != -1) {total += n;if (total > 16L * 1024 * 1024) throw new IllegalArgumentException("文档解压大小过大");}}
      }
    }
  }

  private void plainText(Path input) throws IOException {
    byte[] bytes = Files.readAllBytes(input); String value;
    if (bytes.length >= 2 && ((bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe) || (bytes[0] == (byte)0xfe && bytes[1] == (byte)0xff))) value = StandardCharsets.UTF_16.decode(ByteBuffer.wrap(bytes)).toString();
    else {
      try {value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
      catch (CharacterCodingException error) {value = Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();warnings.add("原文按GB18030编码读取，请核对有无乱码");}
    }
    if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("文本包含二进制内容");
    append(value.replace("\uFEFF", ""));
  }

  private void pdf(Path input) throws IOException {
    try (var document = Loader.loadPDF(input.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
      if (document.isEncrypted()) throw new IllegalArgumentException("请先移除PDF密码");
      var stripper = new PDFTextStripper(); stripper.setSortByPosition(true);
      var renderer = new PDFRenderer(document); renderer.setSubsamplingAllowed(true);
      if (document.getNumberOfPages() > 20) warnings.add("PDF超过20页，仅提取前20页");
      for (int i = 0; i < Math.min(20, document.getNumberOfPages()); i++) {
        String label = "第" + (i + 1) + "页";
        try {
          stripper.setStartPage(i + 1); stripper.setEndPage(i + 1);
          String pageText = stripper.getText(document);
          if (!pageText.isBlank()) append("\n[" + label + "]\n" + pageText);
          // ponytail: vision covers text-sparse pages; add an explicit all-page mode if diagram extraction is required.
          if (pageText.codePoints().filter(Character::isLetterOrDigit).count() >= 40) continue;
          if (images.size() >= MAX_IMAGES) {warnings.add("扫描/图片页超过6页，其余页面未识别，请拆分上传");continue;}
          if (unsupportedImages(document.getPage(i).getResources(), 0)) {warnings.add(label + "含暂不支持的JBIG2/JPEG2000图像，请转换成JPG/PNG");continue;}
          var box = document.getPage(i).getCropBox();
          double w = box.getWidth(), h = box.getHeight();
          if (!Double.isFinite(w) || !Double.isFinite(h) || w <= 0 || h <= 0) {warnings.add(label + "页面尺寸无效，未识别");continue;}
          float scale = (float)Math.min(1600.0 / Math.max(w,h), Math.sqrt(2_000_000.0 / (w*h)));
          BufferedImage page = renderer.renderImage(i, scale, ImageType.RGB);
          try {if (uniform(page)) warnings.add(label + "为空白或没有可识别内容");else addImage(page, label);}
          finally {page.flush();}
        } catch (IOException | RuntimeException error) {warnings.add(label + "无法完整识别，请转换或单独上传此页");}
        if (text.length() >= MAX_TEXT) {warnings.add("文字超过60000字符，剩余页面未提取");break;}
      }
      warnings.add("PDF以文字提取为主，文字较少的页面转为图片；图表与复杂排版请人工核对");
    }
  }

  private boolean unsupportedImages(PDResources resources, int depth) throws IOException {
    if (resources == null) return false;
    if (depth > 8) return true;
    for (var name : resources.getXObjectNames()) {
      var object = resources.getXObject(name);
      String filter = Objects.toString(object.getCOSObject().getDictionaryObject(COSName.FILTER), "");
      if (filter.contains("JBIG2Decode") || filter.contains("JPXDecode")) return true;
      if (object instanceof PDFormXObject form && unsupportedImages(form.getResources(), depth + 1)) return true;
    }
    return false;
  }

  private void xlsx(Path input) throws Exception {
    try (var pkg = OPCPackage.open(input.toFile(), PackageAccess.READ)) {
      var reader = new XSSFReader(pkg); reader.setUseReadOnlySharedStringsTable(true);
      var strings = reader.getSharedStringsTable(); var styles = reader.getStylesTable(); var sheets = reader.getSheetsData(); int index = 0;
      while (sheets.hasNext()) {
        try (var sheet = sheets.next()) {
          if (++index > 10) {warnings.add("仅提取前10个工作表");break;}
          append("\n[工作表 " + index + "]\n");
          var handler = new XSSFSheetXMLHandler.SheetContentsHandler() {
            int cells;
            public void startRow(int rowNum) {if (rowNum >= 2000 || text.length() >= MAX_TEXT) throw new TableLimit();}
            public void endRow(int rowNum) {append("\n");}
            public void cell(String reference, String value, XSSFComment comment) {if (++cells > 20_000) throw new TableLimit();append(reference + ": " + value + "\t");}
          };
          var parser = XMLHelper.newXMLReader();
          parser.setContentHandler(new XSSFSheetXMLHandler(styles,strings,handler,new DataFormatter(Locale.CHINA),false));
          try {parser.parse(new InputSource(sheet));} catch (TableLimit error) {warnings.add("表格过长，仅提取前2000行/20000单元格及60000字符");}
        }
      }
    }
  }
  private static final class TableLimit extends RuntimeException {}

  private void slideShapes(List<XSLFShape> shapes) {
    for (var shape : shapes) {
      if (shape instanceof XSLFTextShape value) append(value.getText() + "\n");
      if (shape instanceof XSLFTable table) for (var row : table.getRows()) {for (var cell : row.getCells()) append(cell.getText() + "\t");append("\n");}
      if (shape instanceof XSLFPictureShape value && value.getPictureData() != null) picture(value.getPictureData().getData());
    }
  }

  private void picture(byte[] bytes) {
    if (images.size() >= MAX_IMAGES) {warnings.add("文档内嵌图片超过6张，其余图片未识别");return;}
    if (bytes.length > ImportDocumentReader.MAX_FILE) {warnings.add("内嵌图片超过5MB，未识别");return;}
    Path temporary = null;
    try {temporary = Files.createTempFile("picture-", ".bin");Files.write(temporary,bytes);image(temporary);}
    catch (IOException | RuntimeException error) {warnings.add("部分内嵌图片编码不支持，请单独转为JPG/PNG上传");}
    finally {if (temporary != null) try {Files.deleteIfExists(temporary);} catch (IOException ignored) {}}
  }

  private void image(Path input) throws IOException {
    try (var stream = new FileImageInputStream(input.toFile())) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) throw new IllegalArgumentException("不支持的图片编码");
      var reader = readers.next();
      try {
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        if (!Set.of("png", "jpeg", "jpg").contains(format)) throw new IllegalArgumentException("只支持真实JPG/PNG图片");
        reader.setInput(stream,true,true);int w = reader.getWidth(0), h = reader.getHeight(0);
        if (w <= 0 || h <= 0 || w > 30_000 || h > 30_000 || (long)w*h > 100_000_000) throw new IllegalArgumentException("图片尺寸过大");
        double ratio = Math.max(Math.max(w,h)/1600.0, Math.sqrt((double)w*h/2_000_000));
        var parameters = reader.getDefaultReadParam();int sample = Math.max(1,(int)Math.ceil(ratio));parameters.setSourceSubsampling(sample,sample,0,0);
        BufferedImage pixels = reader.read(0,parameters);
        try {addImage(pixels,"图片" );} finally {pixels.flush();}
      } finally {reader.dispose();}
    }
  }

  private void addImage(BufferedImage input, String label) throws IOException {
    if (images.size() >= MAX_IMAGES) {warnings.add("图片超过6张，其余图片未识别");return;}
    double ratio = Math.min(1,Math.min(1600.0/Math.max(input.getWidth(),input.getHeight()),Math.sqrt(2_000_000.0/((double)input.getWidth()*input.getHeight()))));
    int width = Math.max(1,(int)(input.getWidth()*ratio)), height = Math.max(1,(int)(input.getHeight()*ratio));
    var target = new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var graphics = target.createGraphics();
    try {graphics.setColor(Color.WHITE);graphics.fillRect(0,0,width,height);graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);graphics.drawImage(input,0,0,width,height,null);}
    finally {graphics.dispose();}
    try (var encoded = new ByteArrayOutputStream()) {
      if (!ImageIO.write(target,"jpeg",encoded)) throw new IOException("JPEG编码不可用");
      String data = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(encoded.toByteArray());
      if (imageCharacters + data.length() + 4 > MAX_IMAGE_DATA) {warnings.add("图片总数据超过6MB，部分图片未识别，请拆分上传");return;}
      images.add(data);imageCharacters += data.length() + 4;
      append("\n[" + label + "：图片" + images.size() + "]\n");
      warnings.add(label + "已转换为待识别图片，识别内容须人工核对");
    } finally {target.flush();}
  }

  private boolean uniform(BufferedImage image) {
    int first = image.getRGB(0,0);
    for (int y=0;y<image.getHeight();y++) for (int x=0;x<image.getWidth();x++) if (image.getRGB(x,y) != first) return false;
    return true;
  }
  private void append(String value) {
    if (value == null || value.isEmpty()) return;
    int remaining = MAX_TEXT-text.length();
    if (value.length()>remaining) {text.append(value,0,remaining);warnings.add("文字超过60000字符，已截断，请拆分材料补充审核");}
    else text.append(value);
  }
}