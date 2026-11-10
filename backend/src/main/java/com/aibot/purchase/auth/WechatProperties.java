package com.aibot.purchase.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wechat")
public record WechatProperties(
    String appId,
    String appSecret,
    boolean mockLogin,
    // 小程序码扫码登录：小程序确认页路径与版本（develop / trial / release）
    String qrPage,
    String qrEnvVersion
) {
}
