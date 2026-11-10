package com.aibot.purchase.service.storage;

import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.config.StorageProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@ConditionalOnProperty(name = "storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {
  private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);
  private final StorageProperties properties;

  public LocalFileStorage(StorageProperties properties) {
    this.properties = properties;
  }

  @Override
  public StoredFile store(byte[] bytes, String originalName, String contentType) {
    String ext = StringUtils.getFilenameExtension(originalName);
    String safeExt = StringUtils.hasText(ext) ? "." + ext.toLowerCase() : "";
    String fileName = UUID.randomUUID().toString().replace("-", "") + safeExt;
    try {
      Path dir = Paths.get(properties.localDir()).toAbsolutePath();
      Files.createDirectories(dir);
      Files.write(dir.resolve(fileName), bytes);
    } catch (IOException ex) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "文件保存失败：" + ex.getMessage());
    }
    String base = properties.publicBaseUrl().endsWith("/")
        ? properties.publicBaseUrl().substring(0, properties.publicBaseUrl().length() - 1)
        : properties.publicBaseUrl();
    return new StoredFile(fileName, base + "/" + fileName, fileType(originalName, contentType), contentType);
  }

  @Override
  public byte[] read(String objectKey) {
    if (!StringUtils.hasText(objectKey)) {
      return new byte[0];
    }
    try {
      Path file = Paths.get(properties.localDir()).toAbsolutePath().resolve(objectKey);
      if (!Files.exists(file)) {
        return new byte[0];
      }
      return Files.readAllBytes(file);
    } catch (IOException ex) {
      log.warn("读取本地文件失败：{}", ex.getMessage());
      return new byte[0];
    }
  }

  @Override
  public String accessUrl(String objectKey) {
    String base = properties.publicBaseUrl().endsWith("/")
        ? properties.publicBaseUrl().substring(0, properties.publicBaseUrl().length() - 1)
        : properties.publicBaseUrl();
    return base + "/" + objectKey;
  }

  private String fileType(String originalName, String contentType) {
    String ext = StringUtils.getFilenameExtension(originalName);
    if (ext != null) {
      ext = ext.toLowerCase();
      if (ext.equals("pdf")) {
        return "PDF";
      }
      if (ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") || ext.equals("gif") || ext.equals("webp") || ext.equals("bmp")) {
        return "图片";
      }
      if (ext.equals("doc") || ext.equals("docx")) {
        return "文档";
      }
      if (ext.equals("xls") || ext.equals("xlsx")) {
        return "Excel";
      }
    }
    if (contentType != null && contentType.startsWith("image/")) {
      return "图片";
    }
    return "文件";
  }
}
