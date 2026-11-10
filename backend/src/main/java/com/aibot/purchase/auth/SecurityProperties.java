package com.aibot.purchase.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security")
public record SecurityProperties(String tokenSecret, long tokenTtlSeconds) {
}
