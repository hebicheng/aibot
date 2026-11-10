package com.aibot.purchase.service.ai;

import com.aibot.purchase.common.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DocumentMediaPreprocessor {
  private static final int MAX_PDF_PAGES = 12;
  private static final int MAX_EXCEL_ROWS = 500;
  private static final int MAX_EXCEL_CHARS = 60_000;

  public PreparedDocument prepare(byte[] bytes, String fileName, String contentType) {
    if (isPdf(fileName, contentType)) {
      return new PreparedDocument(renderPdf(bytes), null, "pdf");
    }
    if (isImage(fileName, contentType)) {
      String resolvedType = StringUtils.hasText(contentType) ? contentType : imageContentType(fileName);
      return new PreparedDocument(
          List.of(new QwenClient.MediaInput(dataUri(resolvedType, bytes), resolvedType)),
          null,
          "image"
      );
    }
    if (isExcel(fileName, contentType)) {
      return new PreparedDocument(List.of(), extractExcel(bytes), "excel");
    }
    throw new ApiException(HttpStatus.BAD_REQUEST, "当前支持图片、PDF、Excel，请转换文件后重新上传");
  }

  public record PreparedDocument(
      List<QwenClient.MediaInput> mediaInputs,
      String extractedText,
      String fileType
  ) {
  }

  private List<QwenClient.MediaInput> renderPdf(byte[] bytes) {
    try (PDDocument document = PDDocument.load(bytes)) {
      if (document.getNumberOfPages() == 0) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "PDF没有可识别页面");
      }
      PDFRenderer renderer = new PDFRenderer(document);
      List<QwenClient.MediaInput> pages = new ArrayList<>();
      int pageCount = Math.min(document.getNumberOfPages(), MAX_PDF_PAGES);
      for (int page = 0; page < pageCount; page++) {
        BufferedImage image = renderer.renderImageWithDPI(page, 144, ImageType.RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        pages.add(new QwenClient.MediaInput(dataUri("image/jpeg", output.toByteArray()), "image/jpeg"));
      }
      return pages;
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "PDF解析失败，请检查文件是否损坏或加密");
    }
  }

  private boolean isPdf(String fileName, String contentType) {
    return "application/pdf".equalsIgnoreCase(contentType)
        || (fileName != null && fileName.toLowerCase().endsWith(".pdf"));
  }

  private boolean isImage(String fileName, String contentType) {
    if (contentType != null && contentType.startsWith("image/")) {
      return true;
    }
    if (fileName == null) {
      return false;
    }
    String lower = fileName.toLowerCase();
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
        || lower.endsWith(".webp") || lower.endsWith(".bmp");
  }

  private boolean isExcel(String fileName, String contentType) {
    if ("application/vnd.ms-excel".equalsIgnoreCase(contentType)
        || "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equalsIgnoreCase(contentType)) {
      return true;
    }
    if (fileName == null) return false;
    String lower = fileName.toLowerCase();
    return lower.endsWith(".xls") || lower.endsWith(".xlsx");
  }

  private String extractExcel(byte[] bytes) {
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      DataFormatter formatter = new DataFormatter();
      StringBuilder text = new StringBuilder();
      int rowsRead = 0;
      for (Sheet sheet : workbook) {
        text.append("工作表：").append(sheet.getSheetName()).append('\n');
        for (Row row : sheet) {
          if (rowsRead++ >= MAX_EXCEL_ROWS || text.length() >= MAX_EXCEL_CHARS) {
            text.append("\n[内容已截断]");
            return text.toString();
          }
          for (int cell = 0; cell < row.getLastCellNum(); cell++) {
            if (cell > 0) text.append('\t');
            text.append(formatter.formatCellValue(row.getCell(cell)));
          }
          text.append('\n');
        }
      }
      if (text.isEmpty()) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "Excel没有可识别内容");
      }
      return text.toString();
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Excel解析失败，请检查文件是否损坏或加密");
    }
  }

  private String imageContentType(String fileName) {
    if (fileName != null && fileName.toLowerCase().endsWith(".png")) {
      return "image/png";
    }
    if (fileName != null && fileName.toLowerCase().endsWith(".webp")) {
      return "image/webp";
    }
    return "image/jpeg";
  }

  private String dataUri(String contentType, byte[] bytes) {
    return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
  }
}
