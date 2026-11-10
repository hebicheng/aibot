package com.aibot.purchase.service.storage;

/**
 * 文件存储抽象。当前提供本地磁盘实现，后续可扩展 OSS / COS 等。
 */
public interface FileStorage {

  StoredFile store(byte[] bytes, String originalName, String contentType);

  String accessUrl(String objectKey);

  /**
   * 读取已存储的文件内容，用于「追问续接」时把原始附件再次交给模型，避免用户重新上传。
   * 读取失败时返回空数组，由调用方降级为纯文本识别。
   */
  default byte[] read(String objectKey) {
    return new byte[0];
  }

  record StoredFile(String objectKey, String url, String fileType, String contentType) {
  }
}
