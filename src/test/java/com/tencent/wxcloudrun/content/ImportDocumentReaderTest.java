package com.tencent.wxcloudrun.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ImportDocumentReaderTest {
  private JsonNode read(String name, byte[] bytes) throws Exception {
    return new ObjectMapper().valueToTree(new ImportDocumentReader().read(new MockMultipartFile("file", name, "application/octet-stream", bytes)));
  }
  @Test void readsTextWithoutTreatingFilenameAsDocumentFacts() throws Exception {
    var result=read("secret-name.txt", "项目：抗体研发\n融资：500万元".getBytes(StandardCharsets.UTF_8));
    assertTrue(result.path("text").asText().contains("抗体研发"));
    assertFalse(result.path("text").asText().contains("secret-name"));
  }
  @Test void extractsPdfAndWordParagraphs() throws Exception {
    try(var pdf=new PDDocument();var bytes=new ByteArrayOutputStream()) {
      var page=new PDPage();pdf.addPage(page);
      try(var out=new PDPageContentStream(pdf,page)) {out.beginText();out.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);out.newLineAtOffset(50,700);out.showText("Project Alpha 500");out.endText();}
      pdf.save(bytes);
      assertTrue(read("a.pdf",bytes.toByteArray()).path("text").asText().contains("Project Alpha 500"));
    }
    try(var doc=new XWPFDocument();var bytes=new ByteArrayOutputStream()) {
      doc.createParagraph().createRun().setText("抗体研发项目");doc.createTable(1,1).getRow(0).getCell(0).setText("投资金额500万元");doc.write(bytes);
      String text=read("a.docx",bytes.toByteArray()).path("text").asText();assertTrue(text.contains("抗体研发项目"));assertTrue(text.contains("500万元"));
    }
  }
  @Test void turnsScannedPagesIntoBoundedImagesAndWarnsWhenPagesAreOmitted() throws Exception {
    try(var pdf=new PDDocument();var bytes=new ByteArrayOutputStream()) {
      var image=new BufferedImage(1800,2200,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,1800,2200);g.setColor(Color.BLACK);g.drawString("Scan 500",100,100);g.dispose();
      var embedded=LosslessFactory.createFromImage(pdf,image);
      for(int i=0;i<7;i++){var page=new PDPage(PDRectangle.A4);pdf.addPage(page);try(var out=new PDPageContentStream(pdf,page)){out.drawImage(embedded,0,0,595,842);}}
      pdf.save(bytes);var result=read("scan.pdf",bytes.toByteArray());assertEquals(6,result.path("images").size());assertFalse(result.path("warnings").isEmpty());
      var data=result.path("images").get(0).asText();var decoded=ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(data.substring(data.indexOf(',')+1))));
      assertTrue(Math.max(decoded.getWidth(),decoded.getHeight())<=1600);assertTrue((long)decoded.getWidth()*decoded.getHeight()<=2_000_000);
    }
  }
  @Test void readsImagesSpreadsheetPresentationAndRtf() throws Exception {
    var pixels=new BufferedImage(1700,1300,BufferedImage.TYPE_INT_RGB);try(var bytes=new ByteArrayOutputStream()){ImageIO.write(pixels,"png",bytes);assertEquals(1,read("a.png",bytes.toByteArray()).path("images").size());}
    try(var book=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()){book.createSheet("研发").createRow(0).createCell(0).setCellValue("抗体项目");book.write(bytes);assertTrue(read("a.xlsx",bytes.toByteArray()).path("text").asText().contains("抗体项目"));}
    try(var slides=new XMLSlideShow();var bytes=new ByteArrayOutputStream()){slides.createSlide().createTextBox().setText("研发融资");slides.write(bytes);assertTrue(read("a.pptx",bytes.toByteArray()).path("text").asText().contains("研发融资"));}
    assertTrue(read("a.rtf","{\\rtf1\\ansi Project Alpha}".getBytes(StandardCharsets.US_ASCII)).path("text").asText().contains("Project Alpha"));
  }
  @Test void xlsKeepsCellCoordinatesWhenTheAmountColumnIsEmpty() throws Exception {
    try(var book=new org.apache.poi.hssf.usermodel.HSSFWorkbook();var bytes=new ByteArrayOutputStream()) {
      var sheet=book.createSheet("Materials");var header=sheet.createRow(0);
      header.createCell(0).setCellValue("ResourceName");header.createCell(1).setCellValue("AmountWan");header.createCell(2).setCellValue("EmployeeCount");
      var row=sheet.createRow(1);row.createCell(0).setCellValue("Alpha");row.createCell(2).setCellValue(500);
      book.write(bytes);
      String text=read("materials.xls",bytes.toByteArray()).path("text").asText();
      assertTrue(text.contains("A1: ResourceName\tB1: AmountWan\tC1: EmployeeCount\t"));
      assertTrue(text.contains("A2: Alpha\tC2: 500\t"));
      assertFalse(text.contains("B2: 500"));
    }
  }
  @Test void capsTextAndRejectsEmptyUnsupportedOrOversizedInput() throws Exception {
    var result=read("a.md","字".repeat(60_100).getBytes(StandardCharsets.UTF_8));assertTrue(result.path("text").asText().length()<=60_000);assertFalse(result.path("warnings").isEmpty());
    var empty=read("a.txt","   \n".getBytes(StandardCharsets.UTF_8));assertTrue(empty.path("text").asText().isBlank());assertFalse(empty.path("warnings").isEmpty());
    assertThrows(Exception.class,()->read("a.exe",new byte[]{1}));assertThrows(Exception.class,()->read("a.txt",new byte[5*1024*1024+1]));
  }
  @Test void rejectsZipBombAndCleansAllTemporaryFilesAfterSuccessAndFailure() throws Exception {
    Set<String> before=temporaryFolders();
    read("a.csv","项目,金额\n抗体,500".getBytes(StandardCharsets.UTF_8));
    try(var bytes=new ByteArrayOutputStream();var zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry("word/document.xml"));zip.write(new byte[9*1024*1024]);zip.closeEntry();zip.finish();assertThrows(Exception.class,()->read("a.docx",bytes.toByteArray()));}
    assertThrows(Exception.class,()->read("a.pdf","not pdf".getBytes(StandardCharsets.UTF_8)));
    assertEquals(before,temporaryFolders());
  }
  @Test void terminatesAWorkerThatExceedsItsTimeBudget() throws Exception {
    var javaCommand=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
    var process=new ProcessBuilder(javaCommand,"-cp",Path.of("target/test-classes").toAbsolutePath().toString(),SleepingWorker.class.getName()).start();
    try {assertFalse(ImportDocumentReader.finish(process,java.time.Duration.ofMillis(100)));assertFalse(process.isAlive());}
    finally {process.destroyForcibly();}
  }
  public static class SleepingWorker { public static void main(String[] args) throws Exception {Thread.sleep(30_000);} }
  @Test void warnsAboutPdfPageLimitAndRejectsUnsafeZipEntries() throws Exception {
    try(var pdf=new PDDocument();var bytes=new ByteArrayOutputStream()) {
      for(int i=0;i<21;i++){var page=new PDPage();pdf.addPage(page);try(var out=new PDPageContentStream(pdf,page)){out.beginText();out.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);out.newLineAtOffset(20,500);out.showText("Page "+i+" Project Alpha research investment clinical document text");out.endText();}}
      pdf.save(bytes);var result=read("long.pdf",bytes.toByteArray());assertTrue(result.path("warnings").toString().contains("20"));assertFalse(result.path("text").asText().contains("Page 20 "));
    }
    for(boolean tooMany:new boolean[]{false,true}) {
      try(var bytes=new ByteArrayOutputStream();var zip=new ZipOutputStream(bytes)) {
        for(int i=0;i<(tooMany?257:1);i++){zip.putNextEntry(new ZipEntry(tooMany?"entry"+i:"../outside"));zip.write(1);zip.closeEntry();}zip.finish();assertThrows(Exception.class,()->read("a.docx",bytes.toByteArray()));
      }
    }
  }
  @Test void enforcesInflatedSizeOnAnOtherwiseValidWordDocument() throws Exception {
    byte[] padding=new byte[9*1024*1024];var random=new Random(3);for(int i=0;i<padding.length;i+=8)padding[i]=(byte)random.nextInt(256);
    byte[] document=wordWithExtras(Map.of("word/media/unused.xml",padding));
    assertTrue(document.length<5*1024*1024);
    var error=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->read("a.docx",document));assertEquals(400,error.getStatusCode().value());
    byte[] smaller=Arrays.copyOf(padding,1024);assertTrue(read("valid.docx",wordWithExtras(Map.of("word/media/unused.xml",smaller))).path("text").asText().contains("Project Alpha"));
  }
  private byte[] wordWithExtras(Map<String,byte[]> extras) throws IOException {
    byte[] base;
    try(var doc=new XWPFDocument();var bytes=new ByteArrayOutputStream()){doc.createParagraph().createRun().setText("Project Alpha");doc.write(bytes);base=bytes.toByteArray();}
    try(var bytes=new ByteArrayOutputStream();var zip=new ZipOutputStream(bytes);var source=new ZipInputStream(new ByteArrayInputStream(base))){
      ZipEntry entry;while((entry=source.getNextEntry())!=null){zip.putNextEntry(new ZipEntry(entry.getName()));source.transferTo(zip);zip.closeEntry();}
      for(var extra:extras.entrySet()){zip.putNextEntry(new ZipEntry(extra.getKey()));zip.write(extra.getValue());zip.closeEntry();}zip.finish();return bytes.toByteArray();
    }
  }
  public static class PackagedReaderSmoke {
    public static void main(String[] args) {
      System.setProperty("java.class.path",args[0]);
      var result=new ImportDocumentReader().read(new MockMultipartFile("file","a.txt","text/plain","Packaged worker Alpha".getBytes(StandardCharsets.UTF_8)));
      if(!result.text().contains("Packaged worker Alpha"))throw new AssertionError("Packaged worker returned no text");
      System.out.println("PACKAGED_READER_WORKER_OK");
    }
  }
  @Test void neverFetchesExternalOfficeImagesOrXmlEntities() throws Exception {
    var requests=new java.util.concurrent.atomic.AtomicInteger();
    var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",exchange->{requests.incrementAndGet();byte[] response="external data".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);try(var out=exchange.getResponseBody()){out.write(response);}});server.start();
    String url="http://127.0.0.1:"+server.getAddress().getPort()+"/external";
    try {
      byte[] word=wordWithExtras(Map.of());
      byte[] linked=rewriteZip(word,(name,value)->name.equals("word/_rels/document.xml.rels")?value.replace("</Relationships>","<Relationship Id=\"outside\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\""+url+"\" TargetMode=\"External\"/></Relationships>"):value);
      assertTrue(read("linked.docx",linked).path("text").asText().contains("Project Alpha"));
      byte[] entity=rewriteZip(word,(name,value)->{
        if(!name.equals("word/document.xml"))return value;
        int declaration=value.indexOf("?>")+2;
        return value.substring(0,declaration)+"<!DOCTYPE w:document [<!ENTITY outside SYSTEM \""+url+"\">]>"+value.substring(declaration).replace("Project Alpha","&outside;");
      });
      try {read("entity.docx",entity);} catch(org.springframework.web.server.ResponseStatusException expected) {assertEquals(400,expected.getStatusCode().value());}
      assertEquals(0,requests.get(),"Office parsing must not dereference external relationships or XML entities");
    } finally {server.stop(0);}
  }
  private byte[] rewriteZip(byte[] input, java.util.function.BiFunction<String,String,String> transform) throws IOException {
    try(var source=new ZipInputStream(new ByteArrayInputStream(input));var bytes=new ByteArrayOutputStream();var output=new ZipOutputStream(bytes)) {
      ZipEntry entry;while((entry=source.getNextEntry())!=null){output.putNextEntry(new ZipEntry(entry.getName()));byte[] part=source.readAllBytes();if(entry.getName().endsWith(".xml")||entry.getName().endsWith(".rels"))part=transform.apply(entry.getName(),new String(part,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);output.write(part);output.closeEntry();}output.finish();return bytes.toByteArray();
    }
  }
  private Set<String> temporaryFolders() throws IOException {
    try(var files=Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {return files.filter(p->p.getFileName().toString().startsWith("yiyao-import-")).map(Path::toString).collect(java.util.stream.Collectors.toSet());}
  }
}