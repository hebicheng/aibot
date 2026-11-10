package com.aibot.purchase.service.storage;

import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.config.StorageProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.ResponseHeaderOverrides;
import com.qcloud.cos.region.Region;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.Date;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 腾讯云 COS 文件存储实现。上传后生成带签名的临时访问 URL（有效期 2 小时），
 * 供千问 VL 模型直接读取图片/PDF。
 */
@Component
@ConditionalOnProperty(name = "storage.type", havingValue = "cos")
public class CosFileStorage implements FileStorage {
  private static final Logger log = LoggerFactory.getLogger(CosFileStorage.class);
  private static final long PRESIGNED_URL_EXPIRY_MS = 2 * 60 * 60 * 1000L; // 2小时

  private final StorageProperties properties;
  private final COSClient cosClient;

  public CosFileStorage(StorageProperties properties) {
    this.properties = properties;
    StorageProperties.Cos cos = properties.cos();
    COSCredentials credentials = new BasicCOSCredentials(cos.secretId(), cos.secretKey());
    ClientConfig config = new ClientConfig(new Region(cos.region()));
    config.setHttpProtocol(com.qcloud.cos.http.HttpProtocol.https);
    this.cosClient = new COSClient(credentials, config);
    log.info("腾讯云 COS 存储已初始化：bucket={}, region={}", cos.bucket(), cos.region());
  }

  @PreDestroy
  public void shutdown() {
    if (cosClient != null) {
      cosClient.shutdown();
    }
  }

  @Override
  public StoredFile store(byte[] bytes, String originalName, String contentType) {
    String ext = StringUtils.getFilenameExtension(originalName);
    String safeExt = StringUtils.hasText(ext) ? "." + ext.toLowerCase() : "";
    String key = "documents/" + UUID.randomUUID().toString().replace("-", "") + safeExt;

    try {
      ObjectMetadata metadata = new ObjectMetadata();
      metadata.setContentLength(bytes.length);
      String resolvedContentType = resolveContentType(originalName, contentType);
      if (StringUtils.hasText(resolvedContentType)) {
        metadata.setContentType(resolvedContentType);
      }
      PutObjectRequest putRequest = new PutObjectRequest(
          properties.cos().bucket(), key, new ByteArrayInputStream(bytes), metadata);
      cosClient.putObject(putRequest);
    } catch (Exception ex) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "COS 上传失败：" + ex.getMessage());
    }

    // 生成带签名的临时访问 URL
    String url = generatePresignedUrl(key);
    String resolvedContentType = resolveContentType(originalName, contentType);
    return new StoredFile(key, url, fileType(originalName, resolvedContentType), resolvedContentType);
  }

  @Override
  public byte[] read(String objectKey) {
    if (!StringUtils.hasText(objectKey)) {
      return new byte[0];
    }
    try (var object = cosClient.getObject(properties.cos().bucket(), objectKey);
         var input = object.getObjectContent()) {
      return input.readAllBytes();
    } catch (Exception ex) {
      log.warn("读取 COS 文件失败：{}", ex.getMessage());
      return new byte[0];
    }
  }

  @Override
  public String accessUrl(String objectKey) {
    return generatePresignedUrl(objectKey);
  }

  /**
   * 为指定 key 生成带签名的临时下载 URL。
   * 尽力附带正确的 Content-Type 与 inline 响应头；注意 2024 年后新建的桶
   * 默认域名会被腾讯云强制附加 attachment（x-cos-force-download），覆盖参数无法稳定绕过。
   */
  public String generatePresignedUrl(String key) {
    Date expiration = new Date(System.currentTimeMillis() + PRESIGNED_URL_EXPIRY_MS);
    GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
        properties.cos().bucket(), key, HttpMethodName.GET);
    request.setExpiration(expiration);
    String inlineContentType = inlineContentType(key);
    if (inlineContentType != null) {
      ResponseHeaderOverrides overrides = new ResponseHeaderOverrides();
      overrides.setContentType(inlineContentType);
      overrides.setContentDisposition("inline");
      request.setResponseHeaders(overrides);
    }
    URL url = cosClient.generatePresignedUrl(request);
    return url.toString();
  }

  /** 客户端上传的 Content-Type 缺失或为通用二进制时，按扩展名推导。 */
  private String resolveContentType(String originalName, String providedContentType) {
    if (StringUtils.hasText(providedContentType)
        && !"application/octet-stream".equalsIgnoreCase(providedContentType)) {
      return providedContentType;
    }
    String byExt = contentTypeByExtension(originalName);
    return byExt != null ? byExt : providedContentType;
  }

  /** 可按扩展名识别的常见类型；PDF/图片将以内嵌方式返回，Office 文档浏览器无法渲染仍会走下载。 */
  private String inlineContentType(String objectKey) {
    return contentTypeByExtension(objectKey);
  }

  private String contentTypeByExtension(String name) {
    if (name == null) return null;
    String ext = StringUtils.getFilenameExtension(name);
    if (ext == null) return null;
    return switch (ext.toLowerCase()) {
      case "pdf" -> "application/pdf";
      case "jpg", "jpeg" -> "image/jpeg";
      case "png" -> "image/png";
      case "gif" -> "image/gif";
      case "webp" -> "image/webp";
      case "bmp" -> "image/bmp";
      case "xls" -> "application/vnd.ms-excel";
      case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
      case "doc" -> "application/msword";
      case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
      default -> null;
    };
  }

  private String fileType(String originalName, String contentType) {
    String ext = StringUtils.getFilenameExtension(originalName);
    if (ext != null) {
      ext = ext.toLowerCase();
      if (ext.equals("pdf")) return "PDF";
      if (ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png")
          || ext.equals("gif") || ext.equals("webp") || ext.equals("bmp")) return "图片";
      if (ext.equals("xls") || ext.equals("xlsx")) return "Excel";
      if (ext.equals("doc") || ext.equals("docx")) return "文档";
    }
    if (contentType != null && contentType.startsWith("image/")) return "图片";
    return "文件";
  }
}
