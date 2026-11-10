package com.aibot.purchase;

import com.aibot.purchase.auth.SecurityProperties;
import com.aibot.purchase.auth.WechatProperties;
import com.aibot.purchase.config.AiProperties;
import com.aibot.purchase.config.BillingProperties;
import com.aibot.purchase.config.StorageProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties({
    SecurityProperties.class,
    WechatProperties.class,
    AiProperties.class,
    StorageProperties.class,
    BillingProperties.class
})
@MapperScan("com.aibot.purchase.mapper")
public class PurchaseAiBotApplication {

  public static void main(String[] args) {
    SpringApplication.run(PurchaseAiBotApplication.class, args);
  }
}
