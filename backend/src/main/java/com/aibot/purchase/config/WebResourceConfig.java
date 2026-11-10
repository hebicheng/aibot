package com.aibot.purchase.config;

import java.nio.file.Paths;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把本地存储目录暴露为 /files/** 静态资源，方便小程序预览上传的单据。
 * 仅在 storage.type=local 时有意义；OSS 模式下文件由对象存储直接对外访问。
 */
@Configuration
public class WebResourceConfig implements WebMvcConfigurer {
  private final StorageProperties properties;

  public WebResourceConfig(StorageProperties properties) {
    this.properties = properties;
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    String location = Paths.get(properties.localDir()).toAbsolutePath().toUri().toString();
    registry.addResourceHandler("/files/**").addResourceLocations(location);
  }
}
