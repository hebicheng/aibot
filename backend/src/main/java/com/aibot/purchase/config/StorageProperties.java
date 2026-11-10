package com.aibot.purchase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件存储配置。type=cos 时走腾讯云对象存储；type=local 时落本地磁盘。
 */
@ConfigurationProperties(prefix = "storage")
public record StorageProperties(String type, String localDir, String publicBaseUrl, Cos cos) {

  public StorageProperties {
    if (type == null || type.isBlank()) {
      type = "local";
    }
    if (localDir == null || localDir.isBlank()) {
      localDir = "./uploads";
    }
    if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
      publicBaseUrl = "http://localhost:8090/files";
    }
    if (cos == null) {
      cos = new Cos(null, null, "ap-guangzhou", null);
    }
  }

  public record Cos(String secretId, String secretKey, String region, String bucket) {
  }
}
